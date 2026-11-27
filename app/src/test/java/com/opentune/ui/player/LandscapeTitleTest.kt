package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
     * The Lenovo's side: 393dp tall, less the 43dp its system bars keep, which is the height the
     * column really gets — where a second title line has to fit beside everything else.
     */
    @Test @Config(qualifiers = "w851dp-h350dp-land-xxhdpi")
    fun longTitleTakesTwoLinesAndNothingIsClipped() = shoot("landscape/title-long-lenovo", longTitle, lines = 2)

    @Test @Config(qualifiers = "w851dp-h350dp-land-xxhdpi")
    fun shortTitleStaysOnOneLine() = shoot("landscape/title-short-lenovo", shortTitle, lines = 1)

    /** The motorola's taller side has room for the second line and both lyric lines. */
    @Test @Config(qualifiers = "w960dp-h432dp-land-xxhdpi")
    fun longTitleTakesTwoLinesOnTallerScreen() = shoot("landscape/title-long-motorola", longTitle, lines = 2)

    private fun shoot(name: String, title: String, lines: Int) {
        val song = Song("x", title, "Zach Bryan", "https://covers.invalid/x.jpg", "3:00", albumName = "The Great American Bar Scene")
        val state = PlayerUiState(
            song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true, shuffle = false, repeatMode = Player.REPEAT_MODE_OFF,
            durationMs = 180_000, lyrics = lyrics, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(), ui = InterfaceSettings(liquidGlass = false, reduceAnimation = true),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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

        // Nothing pushed off the window, and the volume row keeps its place: the second title line
        // must not cost it. The queue button sits at the end of that row.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        for (label in listOf("Close player", "Pause", "Choose where it plays", "Audio output", "Volume down", "Queue")) {
            val box = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$label sits above the window: $box", box.top >= 0f)
            assertTrue("$label is clipped at the bottom ($box in $root)", box.bottom <= root.bottom + 0.5f)
            assertTrue("$label is clipped at the right ($box in $root)", box.right <= root.right + 0.5f)
        }
    }
}
