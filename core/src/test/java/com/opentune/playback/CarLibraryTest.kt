package com.opentune.playback

import android.app.Application
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CarLibraryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val car = CarLibrary(context)
    private val songs = listOf(
        Song("aaaaaaaaaa1", "First", "One", "https://lh3.googleusercontent.com/a=w60-h60"),
        Song("bbbbbbbbbb2", "Second", "Two", null),
        Song("cccccccccc3", "Third", "Three", null),
    )

    @Before
    fun likeSongs() {
        LibraryStore.init(context)
        // setLiked puts each newest first, so like in reverse to keep the order.
        songs.reversed().forEach { LibraryStore.setLiked(it, true) }
    }

    @Test
    fun rootHasHomeRecentAndLibrary() = runBlocking {
        val tabs = car.children(CarLibrary.ROOT, null)
        assertEquals(listOf(CarLibrary.HOME, CarLibrary.RECENT, CarLibrary.LIBRARY), tabs.map { it.mediaId })
        assertTrue(tabs.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
    }

    @Test
    fun libraryOpensLiked() = runBlocking {
        val library = car.children(CarLibrary.LIBRARY, null)
        assertEquals(CarLibrary.LIKED, library.first().mediaId)
        val liked = car.children(CarLibrary.LIKED, null)
        assertEquals(songs.map { "${CarLibrary.LIKED}|${it.videoId}" }, liked.map { it.mediaId })
        assertTrue(liked.all { it.mediaMetadata.isPlayable == true })
        // Artwork goes through the app's content provider, which Android Auto can read.
        assertEquals("content", liked.first().mediaMetadata.artworkUri?.scheme)
    }

    @Test
    fun tappingASongQueuesItsListFromThere() = runBlocking {
        val tapped = car.children(CarLibrary.LIKED, null)[1]
        val queue = car.resolve(listOf(tapped), 0, 0L)
        assertEquals(songs.map { it.videoId }, queue.mediaItems.map { it.mediaId })
        assertEquals(1, queue.startIndex)
        // Plain queue items, as the app's own player builds them.
        assertEquals("opentune", queue.mediaItems[1].localConfiguration?.uri?.scheme)
    }

    @Test
    fun appItemsPassThroughWithTheirUri() = runBlocking {
        val fromApp = MediaItem.Builder().setMediaId(songs[0].videoId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("First").build()).build()
        val queue = car.resolve(listOf(fromApp, fromApp), 1, 5_000L)
        assertEquals(listOf(songs[0].videoId, songs[0].videoId), queue.mediaItems.map { it.mediaId })
        assertEquals(1, queue.startIndex)
        assertEquals(5_000L, queue.startPositionMs)
        assertEquals("opentune", queue.mediaItems[0].localConfiguration?.uri?.scheme)
    }

    @Test
    fun blankVoiceRequestPlaysLikedWithoutHistory() = runBlocking {
        val spoken = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build())
            .build()
        val queue = car.resolve(listOf(spoken), 0, 0L)
        assertEquals(songs.map { it.videoId }.toSet(), queue.mediaItems.map { it.mediaId }.toSet())
    }

    @Test
    fun aSpokenRequestThatFindsNothingFailsTheRequest() = runBlocking {
        // YouTube answers even nonsense with a result, so the no-match is played here:
        // a search that comes back empty, as it does when the account has nothing for it.
        val emptyCar = CarLibrary(context) { _, _ -> emptyList() }
        val spoken = MediaItem.Builder()
            .setMediaId("androidx.media3.session.legacy")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("zzzz not a song").build())
            .build()
        // The session fails the request on an exception and never changes the player, so the
        // car hears that nothing was found rather than being handed an empty queue.
        val failed = runCatching { emptyCar.resolve(listOf(spoken), 0, 0L) }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun blankVoiceRequestWithNothingToPlayFailsTheRequest() = runBlocking {
        // "Play music" falls back on history, then liked songs; with neither there is no queue,
        // and an empty one would clear what the car is playing just as a no-match search would.
        songs.forEach { LibraryStore.setLiked(it, false) }
        History.clear()
        val spoken = MediaItem.Builder()
            .setMediaId("androidx.media3.session.legacy")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build())
            .build()
        val failed = runCatching { car.resolve(listOf(spoken), 0, 0L) }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun aSpokenRequestIsAQueryWhateverItsId() = runBlocking {
        // As the Assistant sends it: a query in the metadata, and an id of the sender's own.
        val spoken = MediaItem.Builder()
            .setMediaId("androidx.media3.session.legacy")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("").build())
            .build()
        val queue = car.resolve(listOf(spoken), 0, 0L)
        assertEquals(songs.map { it.videoId }.toSet(), queue.mediaItems.map { it.mediaId }.toSet())
    }

    @Test
    fun aSpokenRequestPlaysTheSongItNamesNotYouTubesFirst() = runBlocking {
        val yelawolf = Song("yelawolf1", "Money (feat. Jelly Roll & Struggle Jennings)", "Yelawolf", null)
        val dire = Song("dire1", "Money for Nothing (Remastered 1996)", "Dire Straits", null)
        // YouTube leads with a song that names one of the words; the request names five.
        val searchCar = CarLibrary(context) { _, _ -> listOf(SearchResult.Track(yelawolf), SearchResult.Track(dire)) }
        val queue = searchCar.resolve(listOf(spokenItem("money for nothing dire straits")), 0, 0L)
        assertEquals(listOf(dire.videoId), queue.mediaItems.map { it.mediaId })
    }

    @Test
    fun theTitleAndArtistTheAssistantSentSettleTheSong() = runBlocking {
        val cover = Song("cover1", "Wonderboy", "Some Cover Band", null)
        val original = Song("tenacious1", "Wonderboy", "Tenacious D", null)
        val searchCar = CarLibrary(context) { _, _ -> listOf(SearchResult.Track(cover), SearchResult.Track(original)) }
        val queue = searchCar.resolve(listOf(spokenItem("", "Wonderboy", "Tenacious D")), 0, 0L)
        assertEquals(listOf(original.videoId), queue.mediaItems.map { it.mediaId })
    }

    @Test
    fun theSongsSearchAnswersWhenTheMixedOneHasNoSong() = runBlocking {
        val song = Song("only1", "Wonderboy", "Tenacious D", null)
        val searchCar = CarLibrary(context) { _, filter ->
            if (filter == SearchFilter.ALL) emptyList() else listOf(SearchResult.Track(song))
        }
        val queue = searchCar.resolve(listOf(spokenItem("wonderboy")), 0, 0L)
        assertEquals(listOf(song.videoId), queue.mediaItems.map { it.mediaId })
    }

    @Test
    fun searchOffersTheAppsFiltersWithoutVideos() = runBlocking {
        val searchCar = CarLibrary(context) { _, _ -> emptyList() }
        val results = searchCar.search("abbey road", null)
        val folders = results.filter { it.mediaMetadata.isBrowsable == true }
        assertEquals(
            listOf(SearchFilter.SONGS, SearchFilter.ALBUMS, SearchFilter.ARTISTS, SearchFilter.PLAYLISTS).map { it.label },
            folders.map { it.mediaMetadata.title.toString() },
        )
        assertTrue(folders.none { it.mediaMetadata.title.toString() == SearchFilter.VIDEOS.label })
    }

    @Test
    fun theAlbumsFilterListsTheAlbumsItFinds() = runBlocking {
        val album = BrowseItem("MPREb_abbey", "Abbey Road", "The Beatles", null, BrowseType.ALBUM)
        val searchCar = CarLibrary(
            context,
            remoteSearch = { _, filter -> if (filter == SearchFilter.ALBUMS) listOf(SearchResult.Browse(album)) else emptyList() },
        )
        val folders = searchCar.search("abbey road", null)
        val albums = folders.first { it.mediaMetadata.title.toString() == SearchFilter.ALBUMS.label }
        val listed = searchCar.children(albums.mediaId, null)
        assertEquals(listOf("collection:${album.browseId}"), listed.map { it.mediaId })
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, listed.single().mediaMetadata.mediaType)
    }

    @Test
    fun theSongsFilterListsTheSongsAndQueuesThem() = runBlocking {
        val track = Song("abbey1", "Come Together", "The Beatles", null)
        val searchCar = CarLibrary(
            context,
            remoteSearch = { _, filter -> if (filter == SearchFilter.SONGS) listOf(SearchResult.Track(track)) else emptyList() },
        )
        val folders = searchCar.search("come together", null)
        val songsFolder = folders.first { it.mediaMetadata.title.toString() == SearchFilter.SONGS.label }
        val listed = searchCar.children(songsFolder.mediaId, null)
        assertEquals(listOf("${songsFolder.mediaId}|${track.videoId}"), listed.map { it.mediaId })
        // Tapping a song in a filter's page queues that page, as anywhere else in the car.
        val queue = searchCar.resolve(listOf(listed.single()), 0, 0L)
        assertEquals(listOf(track.videoId), queue.mediaItems.map { it.mediaId })
    }

    /** A spoken request as the Assistant sends it, with the names it sent if any. */
    private fun spokenItem(query: String, title: String? = null, artist: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId("androidx.media3.session.legacy")
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setSearchQuery(query)
                .setExtras(
                    Bundle().apply {
                        title?.let { putString(MediaStore.EXTRA_MEDIA_TITLE, it) }
                        artist?.let { putString(MediaStore.EXTRA_MEDIA_ARTIST, it) }
                    },
                )
                .build(),
        )
        .build()
}
