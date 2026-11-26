package com.opentune.playback

import android.os.Bundle
import android.provider.MediaStore
import com.opentune.data.MusicRepository
import com.opentune.data.model.BrowseType
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.Song

/**
 * What a spoken request wants queued: an album, an artist, a playlist, or a song, as
 * EXTRA_MEDIA_FOCUS says or as the request's own words name it.
 */
enum class VoiceFocus { SONG, ALBUM, ARTIST, PLAYLIST }

/** EXTRA_MEDIA_FOCUS's album, artist and playlist values, as the Assistant and Android Auto send them. */
internal const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
internal const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
internal const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"

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
        get() = withoutFocusWord(query.trim().ifEmpty { names(title, album, artist) })

    /**
     * The words a song search uses: the query, or just the title and artist. The album names the
     * page the song is on, and joining it in can lead a mixed search to that page instead.
     */
    val songText: String
        get() = query.trim().ifEmpty { names(title, artist) }
}

private fun names(vararg parts: String?): String =
    parts.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/** The words that name a kind of page rather than a name to search for, and what they ask for. */
private val WORD_HINTS = mapOf(
    "album" to VoiceFocus.ALBUM,
    "playlist" to VoiceFocus.PLAYLIST,
)

/**
 * "play the album abbey road" searches "abbey road": the word naming the kind is not a name.
 * Only once such a word is there is the opening "play the" taken off as well, so a title that
 * starts with "The" is left as it is.
 */
private fun withoutFocusWord(text: String): String {
    val parts = text.split(' ')
    val kept = parts.filterNot { it.plain() in WORD_HINTS }
    if (kept.size == parts.size) return text
    return kept.dropWhile { it.plain() in FILLER_WORDS }.joinToString(" ").trim().ifEmpty { text }
}

private fun String.plain() = lowercase().trim { !it.isLetterOrDigit() }

/**
 * What kind of page the request wants. EXTRA_MEDIA_FOCUS names it when it can: an album or an
 * artist the Assistant focused on. The Assistant's handoff to a media app sends only the query
 * and a wildcard focus ([WILDCARD_FOCUS]), so there the request's own words decide: "play the
 * album …" searches albums, "play my playlist …" playlists.
 */
internal fun voiceFocus(focus: String?, query: String = ""): VoiceFocus {
    val value = focus?.lowercase().orEmpty()
    return when {
        "album" in value -> VoiceFocus.ALBUM
        "artist" in value -> VoiceFocus.ARTIST
        "playlist" in value -> VoiceFocus.PLAYLIST
        // No kind named above, so the request's own word is all there is to go on.
        else -> query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).firstNotNullOfOrNull { WORD_HINTS[it] }
            ?: VoiceFocus.SONG
    }
}

/** The focus the Assistant's media handoff sends: it names no kind, only the query does. */
internal const val WILDCARD_FOCUS = "vnd.android.cursor.item/*"

/** A spoken request read off the search intent's or the media item's extras. */
fun spokenRequest(query: String?, extras: Bundle?): SpokenRequest = SpokenRequest(
    query = query?.trim().orEmpty(),
    title = extras?.text(MediaStore.EXTRA_MEDIA_TITLE),
    artist = extras?.text(MediaStore.EXTRA_MEDIA_ARTIST),
    album = extras?.text(MediaStore.EXTRA_MEDIA_ALBUM),
    kind = voiceFocus(extras?.text(MediaStore.EXTRA_MEDIA_FOCUS), query.orEmpty()),
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
    return if (request.kind == VoiceFocus.SONG) song(request, search, songsOf) else page(request, search, songsOf)
}

/** The song the request named, or the album whose name it is. */
private suspend fun song(
    request: SpokenRequest,
    search: suspend (String, SearchFilter) -> List<SearchResult>,
    songsOf: suspend (BrowseType, String) -> List<Song>,
): List<Song> {
    val text = request.songText
    if (text.isEmpty()) return emptyList()
    // Not simply YouTube's first: "money for nothing dire straits" leads with Yelawolf's
    // "Money". Songs only if the mixed search has none.
    val found = songsIn(search(text, SearchFilter.ALL)).ifEmpty { songsIn(search(text, SearchFilter.SONGS)) }
    val best = bestVoiceMatch(found, request.query, request.title, request.artist)
    // The song the words name, and nothing more to look for.
    if (best != null && requestNamesSong(best, request.query, request.title, request.artist)) return listOf(best)
    // "Play abbey road by the beatles" reaches a media app as a wildcard focus with only those
    // words, so it looks like a song request and the song that wins is a song off the album.
    // When an album's name is what the words say, the album was the request instead. The lookup
    // costs a second search, so a request that names only the artist keeps the song: an album
    // shared their name and would otherwise take the whole page.
    if (best != null && !requestCouldNameAnAlbum(best, request.query, request.title, request.artist)) return listOf(best)
    val album = requestNamesAlbum(
        search(text, SearchFilter.ALBUMS).mapNotNull { (it as? SearchResult.Browse)?.item },
        request.query,
        request.title,
    )
    if (album != null) return songsOf(BrowseType.ALBUM, album.browseId)
    return listOfNotNull(best)
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
    val text = request.text
    if (text.isEmpty()) return emptyList()
    val (filter, type) = when (request.kind) {
        VoiceFocus.ALBUM -> SearchFilter.ALBUMS to BrowseType.ALBUM
        VoiceFocus.PLAYLIST -> SearchFilter.PLAYLISTS to BrowseType.PLAYLIST
        else -> SearchFilter.ARTISTS to BrowseType.ARTIST
    }
    val rows = search(text, filter).mapNotNull { (it as? SearchResult.Browse)?.item }
    // An album is named by its album extra, and its artist settles two of the same name; an
    // artist row's credit is its own name, so asking for it again would not tell any apart.
    val named = when (request.kind) {
        VoiceFocus.ALBUM -> request.album ?: request.title
        VoiceFocus.PLAYLIST -> request.title ?: request.album
        else -> request.artist ?: request.title
    }
    val found = bestVoiceBrowse(rows, request.query, named, if (request.kind == VoiceFocus.ALBUM) request.artist else null)
        ?: return emptyList()
    return songsOf(type, found.browseId)
}
