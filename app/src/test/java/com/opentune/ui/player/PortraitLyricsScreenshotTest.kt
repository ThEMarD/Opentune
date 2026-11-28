package com.opentune.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.opentune.data.settings.PlayerStyle
import com.opentune.data.settings.SoundSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.PANE_WIDTH
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lyrics upright: the phone's page, the lyrics-first page, and the docked player's pane, to
 * build/screenshots/portrait. The ends of each are checked against the edge fade, which is tight
 * on the short landscape pane but must not eat a whole line of the tall ones.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class PortraitLyricsScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        AppSettings.init(context)
        LibraryStore.init(context)
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components { add(FakeImageLoaderEngine.Builder().default(android.graphics.drawable.BitmapDrawable(context.resources, coverArt()).asImage()).build()) }
                .coroutineContext(Dispatchers.Unconfined).build(),
        )
    }

    private val song = Song("x", "Sanctuary", "Joji", "https://covers.invalid/sanctuary.jpg", "3:00", albumName = "Nectar")
    private val lyrics = LyricsState.Found(
        Lyrics.Synced(
            listOf(
                "In the middle of the night", "More than fun, you're the sanctuary", "Souls that dream alone lie awake",
                "I'll give you something so real", "Hold on to me", "'Cause I need you more than I let on",
                "And the world can wait", "For a little while", "You're the only thing I know",
                "In the middle of the night", "More than fun, you're the sanctuary", "Souls that dream alone lie awake",
            ).mapIndexed { i, t -> LyricLine(i * 7_000L, i * 7_000L + 6_500, t, emptyList(), false) },
            "test",
        ),
    )

    /** The phone's lyrics page, mid-song: lines coming and going at both ends of the list. */
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun lyricsOnThePhone() = shoot("portrait/lyrics-phone", docked = false, initialLyrics = true)

    /** The lyrics-first design: the words are the cover page, under a small header. */
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun lyricsFirstOnThePhone() = shoot("portrait/lyrics-first-phone", docked = false, initialLyrics = false, style = PlayerStyle.LYRICS_FIRST)

    /** The docked player's pane, in its 380dp card down a wide window's end. */
    @Test @Config(qualifiers = "w900dp-h850dp-xxhdpi")
    fun lyricsInTheDockedPane() = shoot("portrait/lyrics-docked", docked = true, initialLyrics = true)

    /**
     * The same pane in a window short enough to drop the extras under the controls, so only a
     * few lines fit. Both ends must still fall between lines, not through the words of one.
     */
    @Test @Config(qualifiers = "w900dp-h700dp-xxhdpi")
    fun lyricsInAShortDockedPane() = shoot("portrait/lyrics-docked-short", docked = true, initialLyrics = true)

    private fun shoot(name: String, docked: Boolean, initialLyrics: Boolean, style: PlayerStyle = PlayerStyle.CLASSIC) {
        val state = PlayerUiState(
            song = song, isPlaying = true, isSounding = true, isBuffering = false, hasNext = true, shuffle = false, repeatMode = Player.REPEAT_MODE_OFF,
            durationMs = 180_000, lyrics = lyrics, queue = listOf(song), currentIndex = 0, upNext = emptyList(),
            sound = SoundSettings(), theme = ThemeSettings(),
            ui = InterfaceSettings(liquidGlass = false, reduceAnimation = true, playerStyle = style, lyricsFocus = false),
        )
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                // The bars' share of the window, taken as the player's own inset padding takes it.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 24.dp, bottom = 24.dp)) {
                    val player = @androidx.compose.runtime.Composable {
                        PlayerLayout(state, position = { 45_500 }, buffered = { 60_000 }, actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}), initialLyrics = initialLyrics, docked = docked)
                    }
                    if (docked) {
                        Box(Modifier.align(Alignment.CenterEnd).width(PANE_WIDTH).fillMaxHeight()) { player() }
                    } else {
                        Box(Modifier.fillMaxSize()) { player() }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
