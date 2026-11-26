package com.opentune.playback

import android.os.Bundle
import android.provider.MediaStore
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpokenTest {
    private val abbey = BrowseItem("MPREb_abbey", "Abbey Road", "The Beatles • 1969 • 17 songs", null, BrowseType.ALBUM)
    private val letItBe = BrowseItem("MPREb_let", "Let It Be", "The Beatles • 1970 • 12 songs", null, BrowseType.ALBUM)
    private val beatles = BrowseItem("UCbeatles", "The Beatles", "Artist", null, BrowseType.ARTIST)

    @Test
    fun theFocusSaysWhatToQueue() {
        assertEquals(VoiceFocus.ALBUM, voiceFocus(FOCUS_ALBUM))
        assertEquals(VoiceFocus.ARTIST, voiceFocus(FOCUS_ARTIST))
        assertEquals(VoiceFocus.SONG, voiceFocus("vnd.android.cursor.item/audio"))
        assertEquals(VoiceFocus.SONG, voiceFocus(null))
    }

    @Test
    fun theRequestIsTheQueryOrTheNamesItSent() {
        assertEquals("blinding lights", SpokenRequest("  blinding lights ").text)
        assertEquals("Blinding Lights The Weeknd", SpokenRequest(title = "Blinding Lights", artist = " The Weeknd ").text)
        assertEquals("Abbey Road The Beatles", SpokenRequest(album = "Abbey Road", artist = "The Beatles", focus = FOCUS_ALBUM).text)
        assertEquals("", SpokenRequest().text)
    }

    @Test
    fun theNamesAndFocusComeFromTheExtras() {
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, FOCUS_ALBUM)
            putString(MediaStore.EXTRA_MEDIA_ALBUM, "Abbey Road")
            putString(MediaStore.EXTRA_MEDIA_ARTIST, "The Beatles")
        }
        val request = spokenRequest("", extras)
        assertEquals(VoiceFocus.ALBUM, request.kind)
        assertEquals("Abbey Road The Beatles", request.text)
        assertEquals(VoiceFocus.SONG, spokenRequest("", null).kind)
    }

    @Test
    fun anAlbumRequestQueuesTheAlbumsSongs() = runBlocking {
        val tracks = listOf(
            Song("abbey1", "Come Together", "The Beatles", null),
            Song("abbey2", "Something", "The Beatles", null),
        )
        val queued = spokenSongs(
            SpokenRequest(album = "Abbey Road", artist = "The Beatles", focus = FOCUS_ALBUM),
            search = { _, filter ->
                // Another album of the same artist leads; the request named this one.
                if (filter == SearchFilter.ALBUMS) listOf(SearchResult.Browse(letItBe), SearchResult.Browse(abbey)) else emptyList()
            },
            songsOf = { type, id -> if (type == BrowseType.ALBUM && id == abbey.browseId) tracks else emptyList() },
        )
        assertEquals(tracks, queued)
    }

    @Test
    fun anArtistRequestQueuesTheArtistsSongs() = runBlocking {
        val tracks = listOf(Song("help1", "Help!", "The Beatles", null))
        val queued = spokenSongs(
            SpokenRequest(artist = "The Beatles", focus = FOCUS_ARTIST),
            search = { _, filter -> if (filter == SearchFilter.ARTISTS) listOf(SearchResult.Browse(beatles)) else emptyList() },
            songsOf = { type, id -> if (type == BrowseType.ARTIST && id == beatles.browseId) tracks else emptyList() },
        )
        assertEquals(tracks, queued)
    }

    @Test
    fun anAlbumRequestThatFindsNoAlbumQueuesNothing() = runBlocking {
        // No song of another result is played instead: the car fails the request and keeps playing.
        val queued = spokenSongs(
            SpokenRequest(album = "Abbey Road", focus = FOCUS_ALBUM),
            search = { _, _ -> emptyList() },
            songsOf = { _, _ -> emptyList() },
        )
        assertTrue(queued.isEmpty())
    }

    @Test
    fun aSongRequestStillPlaysTheSongItNames() = runBlocking {
        val money = Song("yelawolf", "Money (feat. Jelly Roll)", "Yelawolf", null)
        val dire = Song("dire", "Money for Nothing (Remastered 1996)", "Dire Straits", null)
        val queued = spokenSongs(
            SpokenRequest(query = "money for nothing dire straits"),
            search = { _, _ -> listOf(SearchResult.Track(money), SearchResult.Track(dire)) },
            songsOf = { _, _ -> emptyList() },
        )
        assertEquals(listOf(dire), queued)
    }
}
