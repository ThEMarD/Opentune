package com.opentune.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.theme.OpenTuneTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TitleFlightActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** The travelling title has one row now, so its menu opens wherever the title has got to. */
    @Test fun moreWorksOnTheTravellingTitle() {
        AppSettings.init(ApplicationProvider.getApplicationContext<Application>())
        val progress = mutableFloatStateOf(0f)
        var opens = 0
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                Box(Modifier.fillMaxSize()) {
                    TitleFlight(Song("test", "Track", "Artist", null), false,
                        bigTitle = Rect(0f, 320f, 400f, 400f), big = Rect(0f, 0f, 300f, 300f),
                        small = Rect(0f, 0f, 64f, 64f), q = { progress.floatValue }, origin = Offset.Zero,
                        onLike = {}, onMore = { opens++ })
                }
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        assertEquals(1, opens)
        // Still the same button once the title has travelled to the header.
        compose.runOnIdle { progress.floatValue = 0.8f }
        compose.onNodeWithContentDescription("More").performClick()
        assertEquals(2, opens)
    }
}
