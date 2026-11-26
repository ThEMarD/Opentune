package com.opentune.data.local

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.download.DownloadEntry
import com.opentune.data.download.DownloadState
import com.opentune.data.download.Downloads
import com.opentune.data.library.LibraryStore
import com.opentune.data.library.SongRef
import com.opentune.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one local lookup: what the app's Library search and the car's search both show. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalMusicSearchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val lights = Song("liked-lights", "Lights", "Ellie Goulding", null)
    private val lightsUp = Song("liked-lights-up", "Lights Up", "Harry Styles", null)
    private val cityLights = Song("liked-city-lights", "City Lights", "The Bouncing Souls", null)
    private val comet = Song("liked-comet", "Comet", "The Bouncing Souls", null, albumName = "Strange Glue")

    /** Saved for offline listening: in no folder, playlist or like. */
    private val downloaded = Song("offline-bury-a-friend", "Bury a Friend", "Billie Eilish", null, albumName = "When We All Fall Asleep")

    /** The two files in the phone's Music folder, as MediaStore lists them. */
    private lateinit var breath: String
    private lateinit var tears: String

    @Before
    fun setup() {
        // The folder scan is memoised for the process and every test here shares it, so each
        // case starts by forgetting it and then reading the files it just seeded.
        LocalMusic.clearFolderCache()
        LibraryStore.init(context)
        listOf(lights, lightsUp, cityLights, comet).forEach { LibraryStore.setLiked(it, true) }
        Downloads.put(downloaded.videoId) { DownloadEntry(SongRef.of(downloaded), state = DownloadState.DONE) }
        breath = audio("Take My Breath", "The Weeknd", "Dawn FM", 200_000)
        tears = audio("Save Your Tears", "The Weeknd", "After Hours", 215_000)
        assertTheSeededFilesWereRead()
    }

    /** Fails unless this test's own MediaStore rows are what the local lookup sees. */
    private fun assertTheSeededFilesWereRead() {
        assertEquals(
            "this test's MediaStore rows weren't read",
            setOf(breath, tears),
            ids("weeknd").toSet(),
        )
    }

    private fun audio(title: String, artist: String, album: String, durationMs: Long): String {
        val uri = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM, album)
                put(MediaStore.Audio.Media.ALBUM_ID, 7L)
                put(MediaStore.Audio.Media.DURATION, durationMs)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
            },
        )
        return LocalMusic.PREFIX + ContentUris.parseId(checkNotNull(uri) { "\"$title\" wasn't added to MediaStore" })
    }

    private fun ids(q: String): List<String> = runBlocking { LocalMusic.search(context, q, 100) }.map { it.videoId }

    @Test
    fun likedSongsMatchEveryWordInTheTitleArtistOrAlbum() {
        // Title, artist and album words alike: all three count as a match.
        assertEquals(listOf(lights.videoId), ids("lights ellie"))
        assertEquals(listOf(lights.videoId), ids("goulding"))
        assertEquals(listOf(comet.videoId), ids("strange glue"))
        // Every word has to be there, and a word nothing has finds nothing.
        assertTrue(ids("lights weeknd").isEmpty())
        assertTrue(ids("nothing here").isEmpty())
        assertTrue(ids("   ").isEmpty())
    }

    @Test
    fun anExactTitleComesBeforeAStartsWithThenTheRest() = runBlocking {
        val found = LocalMusic.search(context, "lights", 100)
        // Not the order they were liked in: how well the title answers the words.
        assertEquals(listOf(lights.videoId, lightsUp.videoId, cityLights.videoId), found.map { it.videoId })
    }

    @Test
    fun aSongLikedAndInAPlaylistShowsOnce() = runBlocking {
        val playlist = LibraryStore.createPlaylist("Favourites", first = lights)
        LibraryStore.addToPlaylist(playlist, cityLights)
        val found = LocalMusic.search(context, "lights", 100)
        assertEquals(1, found.count { it.videoId == lights.videoId })
        assertEquals(setOf(lights.videoId, lightsUp.videoId, cityLights.videoId), found.map { it.videoId }.toSet())
    }

    @Test
    fun downloadedSongsAreSearchedTooAndShowOnce() = runBlocking {
        // Nothing else holds it: the download is what puts it in the list.
        assertEquals(listOf(downloaded.videoId), LocalMusic.search(context, "billie", 100).map { it.videoId })
        val found = LocalMusic.search(context, "bury a friend", 100).single()
        assertEquals("Bury a Friend", found.title)
        assertEquals("When We All Fall Asleep", found.albumName)
        // Downloading a song you've also liked is still one entry, not two.
        LibraryStore.setLiked(downloaded, true)
        try {
            assertEquals(listOf(downloaded.videoId), LocalMusic.search(context, "billie", 100).map { it.videoId })
        } finally {
            LibraryStore.setLiked(downloaded, false)
        }
    }

    @Test
    fun filesInTheMusicFolderAreSearchedToo() = runBlocking {
        val found = LocalMusic.search(context, "weeknd", 100)
        assertEquals(setOf(breath, tears), found.map { it.videoId }.toSet())
        val takeMyBreath = found.first { it.videoId == breath }
        assertEquals("Take My Breath", takeMyBreath.title)
        assertEquals("Dawn FM", takeMyBreath.albumName)
    }
}
