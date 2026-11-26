package com.opentune.playback

import android.os.Bundle
import android.provider.MediaStore
import com.opentune.data.MusicRepository
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song

/**
 * "Play … on OpenTune": the words the Assistant heard, and the title and artist it named when
 * it sent those instead.
 *
 * The Assistant's handoff to a media app only ever asks for a song: it sends a wildcard
 * EXTRA_MEDIA_FOCUS with the words it heard and strips "album" and "playlist" from them, so no
 * request from it says which page it wants. Its album extra names the page the song is on, so
 * that one is not searched with the title and artist.
 */
data class SpokenRequest(
    val query: String = "",
    val title: String? = null,
    val artist: String? = null,
) {
    /** The words to search for: the query itself, or the title and artist it named. */
    val text: String
        get() = query.trim().ifEmpty { names(title, artist) }
}

private fun names(vararg parts: String?): String =
    parts.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/** A spoken request read off the search intent's or the media item's extras. */
fun spokenRequest(query: String?, extras: Bundle?): SpokenRequest = SpokenRequest(
    query = query?.trim().orEmpty(),
    title = extras?.text(MediaStore.EXTRA_MEDIA_TITLE),
    artist = extras?.text(MediaStore.EXTRA_MEDIA_ARTIST),
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

/**
 * The song a spoken request asks for. Empty when the request says nothing, or when nothing the
 * search found is playable: the car then fails the request rather than clearing what it plays.
 */
suspend fun spokenSongs(
    request: SpokenRequest,
    search: suspend (String, SearchFilter) -> List<SearchResult>,
): List<Song> {
    val text = request.text
    if (text.isEmpty()) return emptyList()
    // Not simply YouTube's first: "money for nothing dire straits" leads with Yelawolf's
    // "Money". Songs only if the mixed search has none.
    val found = songsIn(search(text, SearchFilter.ALL)).ifEmpty { songsIn(search(text, SearchFilter.SONGS)) }
    return listOfNotNull(bestVoiceMatch(found, request.query, request.title, request.artist))
}

/**
 * The songs a spoken request asks for, searched on YouTube Music. Not a default on the call
 * above: a suspend reference in a default argument crashes the Kotlin 2.4 backend.
 */
suspend fun spokenSongs(request: SpokenRequest): List<Song> =
    spokenSongs(request, MusicRepository::search)
