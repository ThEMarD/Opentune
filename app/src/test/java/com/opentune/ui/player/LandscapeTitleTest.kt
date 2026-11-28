package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.test.FakeImageLoaderEngine
import com.opentune.data.library.LibraryStore
import com.opentune.data.lyrics.LyricLine
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.InterfaceSettings
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A phone on its side: the window the screen reports, and how much of its top and bottom the
 * system bars keep — the player lays itself out in what is left, as its own
 * `windowInsetsPadding(systemBars)` does. `wm size` and `wm density` give the window.
 *
 * The Lenovo's bars are read off the phone in landscape, from the `InsetsSource` frames in
 * `dumpsys window`: the status bar runs 0..77px down and the navigation bar 1014..1080px, on a
 * 2340x1080 window at 440dpi — 28dp and 24dp of the 393dp it has. Its cutout is on the left edge
 * in this orientation (75px wide, 27dp), and `systemBars` leaves the cutout out, so the player
 * takes it off the start itself: the side has 27dp less width than the window's 393dp.
 * motorola (still derived): 2400x1080 at 400dpi, its content running 115..1000px down the window.
 */
internal class Side(val window: Int, val barTop: Int, val barBottom: Int, val cutout: Int = 0)

internal val lenovoSide = Side(window = 393, barTop = 28, barBottom = 24, cutout = 27)
internal val motorolaSide = Side(window = 432, barTop = 28, barBottom = 22)

/**
 * The title on its side: a name too long for one line takes two and the rows under it keep
 * their places, on the narrow screens where that is tightest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w851dp-h350dp-land-xxhdpi", application = android.app.Application::class)
class LandscapeTitleTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    /** 45 characters: the longest name in the library, "I Remember Everything (feat. Kacey Musgraves)". */
    private val longTitle = "I Remember Everything (feat. Kacey Musgraves)"
    private val shortTitle = "Nine Ball"

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components { add(FakeImageLoaderEngine.Builder().default(android.graphics.drawable.BitmapDrawable(context.resources, coverArt()).asImage()).build()) }
                .coroutineContext(Dispatchers.Unconfined).build(),
        )
    }

    private val lyrics = LyricsState.Found(
        Lyrics.Synced(
            listOf(
                "Rot gut whiskey's gonna ease my mind", "Beach towel rests on the dryin' line",
                "Do you remember that beatdown basement couch", "I'd sing you my love songs and you'd tell me about",
            ).mapIndexed { i, t -> LyricLine(i * 20_000L, i * 20_000L + 19_000, t, emptyList(), false) },
            "test",
        ),
    )

    /**
     * The Lenovo's side is the tightest: 393dp of window, and its bars leave the column 314dp to
     * hold a second title line beside everything else.
     */
    @Test @Config(qualifiers = "w851dp-h393dp-land-xxhdpi")
    fun longTitleTakesTwoLinesAndNothingIsClipped() = shoot(lenovoSide, "landscape/title-long-lenovo", longTitle, lines = 2)

    @Test @Config(qualifiers = "w851dp-h393dp-land-xxhdpi")
    fun shortTitleStaysOnOneLine() = shoot(lenovoSide, "landscape/title-short-lenovo", shortTitle, lines = 1)

    /** The motorola's taller side has room for the second line and both lyric lines. */
    @Test @Config(qualifiers = "w960dp-h432dp-land-xxhdpi")
    fun longTitleTakesTwoLinesOnTallerScreen() = shoot(motorolaSide, "landscape/title-long-motorola", longTitle, lines = 2)

    private fun shoot(side: Side, name: String, title: String, lines: Int) {
        val song = Song("x", title, "Zach Bryan", "https://covers.invalid/x.jpg", "3:00", albumName = "The Great American Bar Scene")
        val state = PlayerUiState(
            song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true, shuffle = false, repeatMode = Player.REPEAT_MODE_OFF,
            durationMs = 180_000, lyrics = lyrics, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), ui = InterfaceSettings(liquidGlass = false, reduceAnimation = true),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                // The bars' and the cutout's share of the window, taken as the player's own
                // `systemBars.union(displayCutout)` padding takes it.
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .padding(start = side.cutout.dp, top = side.barTop.dp, bottom = side.barBottom.dp),
                ) {
                    PlayerLayout(state, position = { 29_000 }, buffered = { 60_000 }, actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}), initialLyrics = false)
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // The title takes as many lines as this name needs: one line of headlineSmall is 32dp.
        val line = with(compose.density) { 32.dp.toPx() }
        val titleBox = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        assertTrue("$title should take $lines line(s), took ${titleBox.height / line}", titleBox.height > (lines - 0.4f) * line)
        assertTrue("$title took more than $lines line(s): ${titleBox.height / line}", titleBox.height < (lines + 0.4f) * line)

        // Nothing pushed out of the space the bars leave, and the volume row keeps its place: the
        // second title line must not cost it. The queue button sits at the end of that row.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val window = root.height / compose.density.density
        assertTrue("the side should be the phone's ${side.window}dp window, rendered ${window}dp", kotlin.math.abs(window - side.window) < 1.5f)
        val room = with(compose.density) {
            androidx.compose.ui.geometry.Rect(root.left + side.cutout.dp.toPx(), root.top + side.barTop.dp.toPx(), root.right, root.bottom - side.barBottom.dp.toPx())
        }
        assertTrue("the title runs past the room left on this side ($titleBox in $room)", titleBox.bottom <= room.bottom + 0.5f)
        for (label in listOf("Close player", "Pause", "Choose where it plays", "Audio output", "Volume down", "Queue")) {
            val box = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$label sits above the room ($box in $room)", box.top >= room.top - 0.5f)
            assertTrue("$label is clipped at the bottom ($box in $room)", box.bottom <= room.bottom + 0.5f)
            assertTrue("$label is clipped at the right ($box in $room)", box.right <= root.right + 0.5f)
        }
    }
}
