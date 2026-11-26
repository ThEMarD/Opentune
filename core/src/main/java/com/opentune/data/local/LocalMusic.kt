package com.opentune.data.local

import android.content.ContentUris
import android.content.Context
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import com.opentune.data.download.Downloads
import com.opentune.data.library.LibraryStore
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.LibrarySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Audio files on the device, read from MediaStore. */
object LocalMusic {
    const val PREFIX = "local:"
    private val ALBUM_ART = "content://media/external/audio/albumart".toUri()

    fun isLocal(videoId: String) = videoId.startsWith(PREFIX)

    fun contentUri(videoId: String): Uri = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        videoId.removePrefix(PREFIX).toLong(),
    )

    data class Track(val song: Song, val folder: String, val mimeType: String?, val durationMs: Long, val isMusic: Boolean)

    suspend fun tracks(context: Context, settings: LibrarySettings): List<Song> = withContext(Dispatchers.IO) {
        query(context)
            .filter { settings.localFolder == null || it.folder == settings.localFolder }
            .filter { !settings.filterNonMusic || looksLikeMusic(it) }
            .map { it.song }
    }

    suspend fun folders(context: Context): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        query(context).groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }
    }

    /**
     * Songs this device already has — liked, in the app's playlists, downloaded and in the
     * Local Music folder — with every word of [q] in the title, artist or album: an exact title
     * first, then titles starting with it, then the rest. What the app's Library search and the
     * car's search both show.
     */
    suspend fun search(context: Context, q: String, limit: Int): List<Song> = withContext(Dispatchers.Default) {
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return@withContext emptyList()
        val text = q.lowercase()
        val all = LibraryStore.liked.value.map { it.toSong() } +
            LibraryStore.playlists.value.flatMap { playlist -> playlist.songs.map { it.toSong() } } +
            Downloads.doneNow().map { it.song.toSong() } +
            folderSongs(context)
        all.distinctBy { it.videoId }
            .filter { song -> "${song.title} ${song.artist} ${song.albumName.orEmpty()}".lowercase().let { hay -> words.all { it in hay } } }
            .sortedBy { song ->
                val title = song.title.lowercase()
                when {
                    title == text -> 0
                    title.startsWith(text) -> 1
                    text in title -> 2
                    else -> 3
                }
            }
            .take(limit)
    }

    /** The Local Music folder as the settings have it, read once; a changed folder reads afresh. */
    private var folder: Pair<LibrarySettings, List<Song>>? = null

    private suspend fun folderSongs(context: Context): List<Song> {
        val settings = AppSettings.library.value
        folder?.takeIf { it.first == settings }?.let { return it.second }
        val songs = runCatching { tracks(context, settings) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return emptyList()
        folder = settings to songs
        return songs
    }

    /** Forgets the memoised folder scan, so tests read their own MediaStore rows. */
    @VisibleForTesting
    internal fun clearFolderCache() {
        folder = null
    }

    /**
     * Short clips, WAV recordings, voice notes and system sounds aren't music,
     * even when they sit in a music folder.
     */
    fun looksLikeMusic(t: Track): Boolean {
        val folder = t.folder.lowercase()
        return t.isMusic &&
            t.durationMs >= 30_000 &&
            t.mimeType?.contains("wav") != true &&
            listOf("recording", "voice", "whatsapp audio", "notifications", "ringtones", "alarms", "call").none { it in folder }
    }

    // Lint's Recycle check doesn't see Kotlin's use {}, which closes the cursor.
    @SuppressLint("Recycle")
    private fun query(context: Context): List<Track> {
        val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.IS_MUSIC,
            folderColumn,
        )
        val out = mutableListOf<Track>()
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE",
        ) ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val duration = c.getLong(5)
                val rawFolder = c.getString(8).orEmpty()
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rawFolder else rawFolder.substringBeforeLast('/') + "/"
                val artist = c.getString(2)?.takeUnless { it == "<unknown>" } ?: "Unknown artist"
                out += Track(
                    song = Song(
                        videoId = "$PREFIX$id",
                        title = c.getString(1).orEmpty(),
                        artist = artist,
                        thumbnailUrl = ContentUris.withAppendedId(ALBUM_ART, c.getLong(4)).toString(),
                        durationText = formatDuration(duration),
                        albumName = c.getString(3),
                    ),
                    folder = folder,
                    mimeType = c.getString(6),
                    durationMs = duration,
                    isMusic = c.getInt(7) != 0,
                )
            }
        }
        return out
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
