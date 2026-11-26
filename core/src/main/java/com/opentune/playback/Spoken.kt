package com.opentune.playback

import android.os.Bundle
import android.provider.MediaStore
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song

/** EXTRA_MEDIA_FOCUS's album and artist values, as the Assistant and Android Auto send them. */
const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"

/**
 * "Play … on OpenTune" as the Assistant sends it: the words it sent, and the title, artist and
 * album it named when the request is only those. [focus] is EXTRA_MEDIA_FOCUS: an album or an
 * artist the request focuses on plays that whole page, anything else plays the song.
 */
data class SpokenRequest(
    val query: String = "",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val focus: String? = null,
) {
    /** The words to search for: the query itself, or what the request named. */
    val text: String
        get() = query.trim().ifEmpty {
            listOfNotNull(title, album, artist).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        }

    /** What the request wants queued: an album, an artist's songs, or one song. */
    val kind: VoiceFocus get() = voiceFocus(focus)
}

/** What a spoken request focused on, as EXTRA_MEDIA_FOCUS says. */
enum class VoiceFocus { SONG, ALBUM, ARTIST }

fun voiceFocus(focus: String?): VoiceFocus {
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
    focus = extras?.text(MediaStore.EXTRA_MEDIA_FOCUS),
)

private fun Bundle.text(key: String): String? =
    getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

/** Just the songs of a search's rows, in order. */
fun songsIn(results: List<SearchResult>): List<Song> = results.mapNotNull {
    when (it) {
        is SearchResult.TopTrack -> it.song
        is SearchResult.Track -> it.song
        is SearchResult.Browse -> null
    }
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
    return when (request.kind) {
        VoiceFocus.ALBUM -> pageQueue(
            request, text, SearchFilter.ALBUMS,
            name = request.album ?: request.title ?: request.artist, artist = request.artist,
            type = BrowseType.ALBUM, search = search, songsOf = songsOf,
        )
        VoiceFocus.ARTIST -> pageQueue(
            request, text, SearchFilter.ARTISTS,
            name = request.artist ?: request.title ?: request.album, artist = null,
            type = BrowseType.ARTIST, search = search, songsOf = songsOf,
        )
        VoiceFocus.SONG -> {
            // The song the request named, not simply YouTube's first: "money for nothing dire
            // straits" leads with Yelawolf's "Money". Songs only if the mixed search has none.
            val found = songsIn(search(text, SearchFilter.ALL))
                .ifEmpty { songsIn(search(text, SearchFilter.SONGS)) }
            listOfNotNull(bestVoiceMatch(found, request.query, request.title, request.artist))
        }
    }
}

/** The whole album or artist that [name] picks out of that filter's search results. */
private suspend fun pageQueue(
    request: SpokenRequest,
    text: String,
    filter: SearchFilter,
    name: String?,
    artist: String?,
    type: BrowseType,
    search: suspend (String, SearchFilter) -> List<SearchResult>,
    songsOf: suspend (BrowseType, String) -> List<Song>,
): List<Song> {
    val pages = search(text, filter).mapNotNull { (it as? SearchResult.Browse)?.item }
    val page = bestVoiceBrowse(pages, request.query, name, artist) ?: return emptyList()
    return songsOf(type, page.browseId)
}
