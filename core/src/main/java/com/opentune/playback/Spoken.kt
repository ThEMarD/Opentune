package com.opentune.playback

import android.os.Bundle
import android.provider.MediaStore
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song

/** What a spoken request wants queued, as EXTRA_MEDIA_FOCUS says: an album, an artist, or a song. */
enum class VoiceFocus { SONG, ALBUM, ARTIST }

/** EXTRA_MEDIA_FOCUS's album and artist values, as the Assistant and Android Auto send them. */
internal const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
internal const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"

/**
 * "Play … on OpenTune" as the Assistant sends it: the words it sent, and the title, artist and
 * album it named when the request is only those. [kind] is EXTRA_MEDIA_FOCUS: an album or an
 * artist it focuses on plays that whole page, anything else plays the song.
 */
data class SpokenRequest(
    val query: String = "",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val kind: VoiceFocus = VoiceFocus.SONG,
) {
    /** The words to search for: the query itself, or what the request named. */
    val text: String
        get() = query.trim().ifEmpty {
            listOfNotNull(title, album, artist).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }
}

internal fun voiceFocus(focus: String?): VoiceFocus {
    val value = focus?.lowercase().orEmpty()
    return when {
        "album" in value -> VoiceFocus.ALBUM
        "artist" in value -> VoiceFocus.ARTIST
        else -> VoiceFocus.SONG
    }
}

/** A spoken request read off the search intent's or the media item's extras. */
fun spokenRequest(query: String?, extras: Bundle?): SpokenRequest = SpokenRequest(
    query = query?.trim().orEmpty(),
    title = extras?.text(MediaStore.EXTRA_MEDIA_TITLE),
    artist = extras?.text(MediaStore.EXTRA_MEDIA_ARTIST),
    album = extras?.text(MediaStore.EXTRA_MEDIA_ALBUM),
    kind = voiceFocus(extras?.text(MediaStore.EXTRA_MEDIA_FOCUS)),
)

private fun Bundle.text(key: String): String? =
    getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

/** Just the songs of a search's rows, in order. */
internal fun songsIn(results: List<SearchResult>): List<Song> = results.mapNotNull {
    when (it) {
        is SearchResult.TopTrack -> it.song
        is SearchResult.Track -> it.song
        is SearchResult.Browse -> null
    }
}

/** An album's or an artist's songs, as a spoken request needs them. */
internal suspend fun pageSongs(type: BrowseType, id: String): List<Song> = when (type) {
    BrowseType.ARTIST -> MusicRepository.artist(id).songs
    else -> MusicRepository.collection(id).songs
}

/**
 * The songs a spoken request asks for: the album or artist it focuses on queues that page's
 * songs rather than one of them; anything else plays the song it named. Empty when the search
 * found nothing to play.
 */
suspend fun spokenSongs(
    request: SpokenRequest,
    search: suspend (String, SearchFilter) -> List<SearchResult>,
    songsOf: suspend (BrowseType, String) -> List<Song>,
): List<Song> {
    val text = request.text
    if (text.isEmpty()) return emptyList()
    if (request.kind == VoiceFocus.SONG) {
        // The song the request named, not simply YouTube's first: "money for nothing dire
        // straits" leads with Yelawolf's "Money". Songs only if the mixed search has none.
        val found = songsIn(search(text, SearchFilter.ALL)).ifEmpty { songsIn(search(text, SearchFilter.SONGS)) }
        return listOfNotNull(bestVoiceMatch(found, request.query, request.title, request.artist))
    }
    return page(request, search, songsOf)
}

/**
 * The songs a spoken request asks for, searched and looked up on YouTube Music. Not defaults on
 * the call below: a suspend reference in a default argument crashes the Kotlin 2.4 backend.
 */
suspend fun spokenSongs(request: SpokenRequest): List<Song> =
    spokenSongs(request, MusicRepository::search, ::pageSongs)

/** The whole album or artist the request focused on. */
private suspend fun page(
    request: SpokenRequest,
    search: suspend (String, SearchFilter) -> List<SearchResult>,
    songsOf: suspend (BrowseType, String) -> List<Song>,
): List<Song> {
    val album = request.kind == VoiceFocus.ALBUM
    val rows = search(request.text, if (album) SearchFilter.ALBUMS else SearchFilter.ARTISTS)
        .mapNotNull { (it as? SearchResult.Browse)?.item }
    // An album is named by its album extra, and its artist settles two of the same name; an
    // artist row's credit is its own name, so asking for it again would not tell any apart.
    val named = if (album) request.album ?: request.title else request.artist ?: request.title
    val found = bestVoiceBrowse(rows, request.query, named, if (album) request.artist else null)
        ?: return emptyList()
    return songsOf(if (album) BrowseType.ALBUM else BrowseType.ARTIST, found.browseId)
}
