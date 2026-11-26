package com.opentune.playback

import android.os.Bundle
import android.provider.MediaStore
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
    @Test
    fun theRequestIsTheQueryOrTheNamesItSent() {
        assertEquals("blinding lights", SpokenRequest("  blinding lights ").text)
        assertEquals("Blinding Lights The Weeknd", SpokenRequest(title = "Blinding Lights", artist = " The Weeknd ").text)
        assertEquals("", SpokenRequest().text)
    }

    @Test
    fun theTitleAndArtistComeFromTheExtrasWithoutTheAlbum() = runBlocking {
        // The album extra names the page the song is on; searching it too can lead the mixed
        // results to that page instead of the song.
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_TITLE, "Money for Nothing")
            putString(MediaStore.EXTRA_MEDIA_ARTIST, "Dire Straits")
            putString(MediaStore.EXTRA_MEDIA_ALBUM, "Brothers in Arms")
        }
        val request = spokenRequest("", extras)
        assertEquals("Money for Nothing Dire Straits", request.text)
        var searched: String? = null
        val queued = spokenSongs(request, search = { text, _ -> searched = text; emptyList() })
        assertEquals("Money for Nothing Dire Straits", searched)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun aSongRequestPlaysTheSongItNames() = runBlocking {
        val money = Song("yelawolf", "Money (feat. Jelly Roll)", "Yelawolf", null)
        val dire = Song("dire", "Money for Nothing (Remastered 1996)", "Dire Straits", null)
        val searched = mutableListOf<SearchFilter>()
        val queued = spokenSongs(
            SpokenRequest(query = "money for nothing dire straits"),
            search = { _, filter ->
                searched += filter
                listOf(SearchResult.Track(money), SearchResult.Track(dire))
            },
        )
        assertEquals(listOf(dire), queued)
        assertEquals(listOf(SearchFilter.ALL), searched)
    }

    @Test
    fun theSongsSearchAnswersWhenTheMixedOneHasNoSong() = runBlocking {
        val song = Song("only1", "Wonderboy", "Tenacious D", null)
        val queued = spokenSongs(
            SpokenRequest(query = "wonderboy"),
            search = { _, filter -> if (filter == SearchFilter.ALL) emptyList() else listOf(SearchResult.Track(song)) },
        )
        assertEquals(listOf(song), queued)
    }

    @Test
    fun aWildcardRequestPlaysTheSongTheWordsName() = runBlocking {
        // "Play abbey road by the beatles" reaches a media app with a wildcard focus and those
        // words alone, and the Assistant only ever asks for a song: one search, one song.
        val sun = Song("here1", "Here Comes the Sun", "The Beatles", null)
        val searched = mutableListOf<SearchFilter>()
        val queued = spokenSongs(
            SpokenRequest(query = "abbey road the beatles"),
            search = { _, filter ->
                searched += filter
                listOf(SearchResult.Track(sun))
            },
        )
        assertEquals(listOf(sun), queued)
        assertEquals(listOf(SearchFilter.ALL), searched)
    }

    @Test
    fun aPlaylistNamePlaysTheSongOfThatName() = runBlocking {
        // "Play the playlist Road Trip" arrives as the words "Road Trip" and a wildcard focus,
        // the word playlist already stripped: the song of that name is what is played.
        val song = Song("trip_song", "Road Trip", "A Shell in the Pit", null)
        val searched = mutableListOf<SearchFilter>()
        val queued = spokenSongs(
            SpokenRequest(query = "road trip"),
            search = { _, filter ->
                searched += filter
                listOf(SearchResult.Track(song))
            },
        )
        assertEquals(listOf(song), queued)
        assertEquals(listOf(SearchFilter.ALL), searched)
    }

    @Test
    fun anArtistNamePlaysTheSongWithOneSearch() = runBlocking {
        // "Play the beatles" names the artist; the words are searched as songs, once.
        val song = Song("ussr", "Back In The U.S.S.R. (Remastered 2009)", "The Beatles", null)
        val searched = mutableListOf<SearchFilter>()
        val queued = spokenSongs(
            SpokenRequest(query = "the beatles"),
            search = { _, filter ->
                searched += filter
                listOf(SearchResult.Track(song))
            },
        )
        assertEquals(listOf(song), queued)
        assertEquals(listOf(SearchFilter.ALL), searched)
    }

    @Test
    fun aRequestWithNoWordsPlaysNothing() = runBlocking {
        // "Play music" is answered from history by the car, not by a search.
        val song = Song("any1", "Anything", "Anyone", null)
        val queued = spokenSongs(SpokenRequest(), search = { _, _ -> listOf(SearchResult.Track(song)) })
        assertTrue(queued.isEmpty())
    }
}
