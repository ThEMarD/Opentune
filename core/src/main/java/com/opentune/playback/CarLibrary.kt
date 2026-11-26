package com.opentune.playback

import com.opentune.data.radio.Radio
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import com.opentune.data.DebugLog as Log
import com.opentune.data.MusicRepository
import com.opentune.data.account.AccountStore
import com.opentune.data.download.Downloads
import com.opentune.data.history.History
import com.opentune.data.library.LibraryStore
import com.opentune.data.local.LocalMusic
import com.opentune.data.model.BrowseType
import com.opentune.data.model.CARD_ART_PX
import com.opentune.data.model.HomeShelf
import com.opentune.data.model.SearchFilter
import com.opentune.data.model.SearchResult
import com.opentune.data.model.ShelfItem
import com.opentune.data.model.Song
import com.opentune.data.model.artworkAt
import com.opentune.data.subsonic.Subsonic
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * What Android Auto (and any other media browser) can browse and play:
 *
 *  - Home: YouTube Music's shelves, each shown as a titled group;
 *  - Recent: this device's listening history;
 *  - Library: Liked, Downloads, your own music server, playlists made in
 *    the app and the account's YouTube playlists;
 *  - search, typed or spoken.
 *
 * Albums, playlists and artists open as their own pages. A song carries the
 * list it was shown in (`<parent>|<videoId>`), so tapping it queues that list
 * from there, as tapping a song in the app does; [resolve] turns those back
 * into the plain queue items the rest of the app uses.
 */
@UnstableApi
class CarLibrary(
    private val context: Context,
    /** YouTube search, given as an argument so a test can answer without the network. */
    private val remoteSearch: suspend (String, SearchFilter) -> List<SearchResult> = MusicRepository::search,
) {
    /** The songs last listed under each node, for queueing a tapped song's list. */
    private val listed = ConcurrentHashMap<String, List<Song>>()

    /** Every item handed out, so a browser can look one up again by id. */
    private val known = ConcurrentHashMap<String, MediaItem>()

    private var homeCache: Pair<Long, List<HomeShelf>>? = null

    fun root(): MediaItem = folder(ROOT, "OpenTune", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)

    /** Extras for the root: search is supported, folders show as a grid and songs as a list. */
    fun rootExtras(): Bundle = Bundle().apply {
        putBoolean(EXTRA_SEARCH_SUPPORTED, true)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    fun item(mediaId: String): MediaItem? = known[mediaId] ?: if (mediaId == ROOT) root() else null

    suspend fun children(parentId: String, browser: MediaSession.ControllerInfo?): List<MediaItem> {
        val items = when {
            parentId == ROOT -> listOf(
                folder(HOME, "Home", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
                folder(RECENT, "Recent"),
                folder(LIBRARY, "Library", browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            )
            parentId == HOME -> homeItems()
            parentId == RECENT -> songs(RECENT, History.recents(History.records.value, MAX_SONGS))
            parentId == LIBRARY -> libraryItems()
            parentId == LIKED -> songs(LIKED, LibraryStore.liked.value.map { it.toSong() })
            parentId == DOWNLOADS -> songs(DOWNLOADS, Downloads.doneNow().map { it.song.toSong() })
            // Favourite stations, then others played lately; next and previous change station.
            parentId == RADIO -> songs(RADIO, radioStations().map { it.toSong() })
            parentId.startsWith(PLAYLIST) -> {
                val playlist = LibraryStore.playlists.value.firstOrNull { it.id == parentId.removePrefix(PLAYLIST) }
                songs(parentId, playlist?.songs.orEmpty().map { it.toSong() })
            }
            parentId.startsWith(COLLECTION) ->
                songs(parentId, MusicRepository.collection(parentId.removePrefix(COLLECTION)).songs)
            parentId.startsWith(ARTIST) -> artistItems(parentId)
            parentId.startsWith(SEARCH_FILTER) -> filterResults(parentId)
            parentId == SERVER -> serverItems()
            parentId.startsWith(SERVER_ALBUM) -> songs(parentId, Subsonic.album(parentId.removePrefix(SERVER_ALBUM)).second)
            parentId.startsWith(SERVER_PLAYLIST) -> songs(parentId, Subsonic.playlist(parentId.removePrefix(SERVER_PLAYLIST)).second)
            else -> emptyList()
        }
        grantArtwork(items, browser)
        return items
    }

    suspend fun search(query: String, browser: MediaSession.ControllerInfo?): List<MediaItem> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        // What this device already has answers at once, so the car's list has something to play
        // even while YouTube is being asked, or when it can't be reached.
        val local = LocalMusic.search(context, text, MAX_LOCAL)
        val remote = runCatching { remoteSearch(text, SearchFilter.ALL) }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Search for \"$text\" failed", it)
            }
            .getOrDefault(emptyList())
        // Songs lead: Android Auto draws search results as one flat list, and a song plays where
        // a folder needs another tap. A song found on the device and on YouTube shows once.
        val songs = (local + songsIn(remote)).distinctBy { it.videoId }
        listed[SEARCH] = songs
        // The app's filters head the results as pages of their own: the car draws a search as one
        // flat list with no room for tabs, so a folder is how it can narrow one down.
        val shown = (CAR_FILTERS.map { filter -> filterFolder(filter, text) } +
            songs.map { song(SEARCH, it) } + remote.filterIsInstance<SearchResult.Browse>().map { result ->
                browseFolder(result.item.browseId, result.item.title, result.item.subtitle, result.item.thumbnailUrl, null)
            }).take(MAX_SONGS)
        grantArtwork(shown, browser)
        return shown
    }

    /**
     * Items a browser asked to play, as queue items. A single tapped song
     * brings its list with it; a spoken request ("play … on OpenTune")
     * plays the best match, and autoplay carries on after it.
     */
    suspend fun resolve(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        val single = items.singleOrNull()
        if (single != null) {
            // Android Auto's voice search arrives this way too, often with a placeholder id.
            if (isSpokenRequest(single)) {
                return voice(single.requestMetadata.searchQuery?.toString().orEmpty(), single.requestMetadata.extras)
            }
            val (parent, videoId) = split(single.mediaId) ?: (null to single.mediaId)
            if (parent != null) {
                val songs = listed[parent] ?: run {
                    runCatching { children(parent, null) }
                    listed[parent]
                }
                val at = songs?.indexOfFirst { it.videoId == videoId } ?: -1
                if (songs != null && at >= 0) {
                    return MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, at, startPositionMs)
                }
            }
        }
        return MediaSession.MediaItemsWithStartPosition(items.map(::toQueueItem), startIndex, startPositionMs)
    }

    /** A queue item for anything a controller sends: the app's own, or a browsed song. */
    fun toQueueItem(item: MediaItem): MediaItem {
        val videoId = split(item.mediaId)?.second ?: item.mediaId
        if (videoId == item.mediaId) return item.buildUpon().setUri(trackUri(videoId)).build()
        val known = listed.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.videoId == videoId } }
        val song = known ?: Song(
            videoId = videoId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            thumbnailUrl = null,
        )
        return song.toMediaItem()
    }

    /** A spoken request ("play … on OpenTune") carries a query rather than a song. */
    fun isSpokenRequest(item: MediaItem): Boolean = item.requestMetadata.searchQuery != null

    private suspend fun voice(query: String, extras: Bundle?): MediaSession.MediaItemsWithStartPosition {
        val request = spokenRequest(query, extras)
        val text = request.text
        val songs = if (text.isBlank()) {
            // "Play music": what was played lately, or the liked songs.
            History.recents(History.records.value, MAX_SONGS).ifEmpty { LibraryStore.liked.value.map { it.toSong() } }.shuffled()
        } else {
            // The song it named, not simply the first of the search's results.
            spokenSongs(request, remoteSearch)
        }
        // In logcat (debug builds), beside onSearch's line, so an Assistant request can be
        // told from a typed car search. Before the guard, so a request that finds nothing
        // still says what it asked for. What the Assistant sent beyond the query is logged
        // too, since its title and artist settle the song.
        Log.d(TAG, "Assistant search \"$text\": ${songs.size} song(s)")
        Log.d(TAG, "Assistant search extras: ${extras?.texts() ?: "none"}")
        // No match is no queue: handed over empty, it would end the song the car is playing
        // and leave it showing nothing. Failing the request reports that to the car instead,
        // and the session leaves the player untouched, so the queue that was there stays.
        // "Play music" with no history and no liked songs has nothing to play either.
        if (songs.isEmpty()) error(if (text.isBlank()) "Nothing to play" else "Nothing was found for \"$text\"")
        return MediaSession.MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, 0, 0L)
    }

    /**
     * One filter's results: songs for the Songs filter (this device's matches first), the
     * albums, artists or playlists YouTube found for the rest. The query travels in the id, so
     * a later search cannot make an older list answer for the newer words.
     */
    private suspend fun filterResults(parentId: String): List<MediaItem> {
        val (filter, text) = splitFilterId(parentId) ?: return emptyList()
        val found = runCatching { remoteSearch(text, filter) }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Search for \"$text\" (${filter.label}) failed", it)
            }
            .getOrDefault(emptyList())
        if (filter == SearchFilter.SONGS) {
            val local = LocalMusic.search(context, text, MAX_LOCAL)
            // A song found on the device and on YouTube shows once, as in the unfiltered list.
            return songs(parentId, (local + songsIn(found)).distinctBy { it.videoId })
        }
        return found.filterIsInstance<SearchResult.Browse>().map { result ->
            browseFolder(result.item.browseId, result.item.title, result.item.subtitle, result.item.thumbnailUrl, null)
        }.take(MAX_SONGS)
    }

    /** One of the app's filters, as its own page under the query that opened it. */
    private fun filterFolder(filter: SearchFilter, query: String): MediaItem =
        folder(
            filterId(filter, query),
            filter.label,
            subtitle = query,
            browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )

    private fun filterId(filter: SearchFilter, query: String) = "$SEARCH_FILTER${filter.name}:$query"

    private fun splitFilterId(parentId: String): Pair<SearchFilter, String>? {
        val parts = parentId.removePrefix(SEARCH_FILTER).split(':', limit = 2)
        val name = parts.getOrNull(0) ?: return null
        val filter = SearchFilter.entries.firstOrNull { it.name == name } ?: return null
        return filter to (parts.getOrNull(1) ?: return null)
    }

    /** What a request carried besides its query, as text: the title and artist it added. */
    private fun Bundle.texts(): String = keySet().sorted().mapNotNull { key ->
        getCharSequence(key)?.let { "$key=$it" }
    }.joinToString().ifEmpty { "none" }

    private suspend fun homeItems(): List<MediaItem> {
        val now = System.currentTimeMillis()
        val shelves = homeCache?.takeIf { now - it.first < HOME_FRESH_MS }?.second
            ?: com.opentune.data.ContentFilter.shelves(MusicRepository.home() + MusicRepository.homeMore.value).also { homeCache = now to it }
        // Hidden songs are taken off on every read, so hiding one shows at once rather than when the cache runs out.
        return LibraryStore.hidden.value.shelves(shelves).take(MAX_SHELVES).flatMapIndexed { index, shelf ->
            val parent = "$HOME_SHELF$index"
            val shelfSongs = shelf.items.mapNotNull { it.toSongOrNull() }
            listed[parent] = shelfSongs
            shelf.items.take(PER_SHELF).mapNotNull { item ->
                val built = if (item.browseId != null) {
                    browseFolder(item.browseId, item.title, item.subtitle, item.thumbnailUrl, shelf.title)
                } else {
                    item.toSongOrNull()?.let { song(parent, it, group = shelf.title) }
                }
                built
            }
        }
    }

    private suspend fun libraryItems(): List<MediaItem> = buildList {
        val liked = LibraryStore.liked.value
        add(folder(LIKED, "Liked songs", subtitle = com.opentune.i18n.Tr.f("{0} songs", liked.size), artwork = liked.firstOrNull()?.thumbnailUrl))
        val downloads = Downloads.doneNow()
        add(folder(DOWNLOADS, "Downloads", subtitle = com.opentune.i18n.Tr.f("{0} songs", downloads.size), artwork = downloads.firstOrNull()?.song?.thumbnailUrl))
        LibraryStore.playlists.value.forEach { p ->
            add(folder("$PLAYLIST${p.id}", p.name, subtitle = com.opentune.i18n.Tr.f("{0} songs", p.songs.size), artwork = p.songs.firstOrNull()?.thumbnailUrl, type = MediaMetadata.MEDIA_TYPE_PLAYLIST))
        }
        if (radioStations().isNotEmpty()) {
            add(folder(RADIO, "Radio", subtitle = "Your stations", artwork = radioStations().firstOrNull()?.favicon))
        }
        Subsonic.server.value?.let { server ->
            add(folder(SERVER, "Your music server", subtitle = server.url.substringAfter("://"), browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM))
        }
        if (AccountStore.signedIn.value) {
            // One linked to a playlist above is already listed, as that one.
            val account = runCatching { MusicRepository.libraryPlaylists() }.getOrDefault(emptyList())
            com.opentune.data.library.PlaylistLinks.unlinked(account, LibraryStore.playlists.value).forEach { p ->
                p.browseId?.let { add(browseFolder(it, p.title, p.subtitle, p.thumbnailUrl, null)) }
            }
        }
    }

    /** Your own server: its newest albums, then its playlists. */
    private suspend fun serverItems(): List<MediaItem> {
        val albums = runCatching { Subsonic.albums("newest", PER_SHELF * 4) }.getOrDefault(emptyList()).map { a ->
            folder("$SERVER_ALBUM${a.id}", a.name, a.artist, a.cover, MediaMetadata.MEDIA_TYPE_ALBUM, group = "Recently added")
        }
        val playlists = runCatching { Subsonic.playlists() }.getOrDefault(emptyList()).map { p ->
            folder("$SERVER_PLAYLIST${p.id}", p.name, com.opentune.i18n.Tr.f("{0} songs", p.songCount), p.cover, MediaMetadata.MEDIA_TYPE_PLAYLIST, group = "Playlists")
        }
        return albums + playlists
    }

    private suspend fun artistItems(parentId: String): List<MediaItem> {
        val page = MusicRepository.artist(parentId.removePrefix(ARTIST))
        val top = songs(parentId, page.songs.take(MAX_SONGS), group = "Top songs")
        val more = page.sections.flatMap { section ->
            section.items.take(PER_SHELF).mapNotNull { item ->
                item.browseId?.let { browseFolder(it, item.title, item.subtitle, item.thumbnailUrl, section.title) }
            }
        }
        return top + more
    }

    private fun radioStations() = (Radio.favourites.value + Radio.recent.value).distinctBy { it.uuid }

    private fun songs(parent: String, songs: List<Song>, group: String? = null): List<MediaItem> {
        listed[parent] = songs
        return songs.take(MAX_SONGS).map { song(parent, it, group) }
    }

    private fun song(parent: String, song: Song, group: String? = null): MediaItem {
        val id = "$parent$SEP${song.videoId}"
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.albumName)
                    .setArtworkUri(CarArtwork.uriFor(context, song.videoId, song.thumbnailUrl))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(groupExtras(group))
                    .build(),
            )
            .build()
            .also { known[id] = it }
    }

    /** An album, playlist or artist: a page of its own. */
    private fun browseFolder(browseId: String, title: String, subtitle: String, art: String?, group: String?): MediaItem =
        when (MusicRepository.typeOf(browseId)) {
            BrowseType.ARTIST -> folder("$ARTIST$browseId", title, subtitle, art, MediaMetadata.MEDIA_TYPE_ARTIST, group)
            BrowseType.ALBUM -> folder("$COLLECTION$browseId", title, subtitle, art, MediaMetadata.MEDIA_TYPE_ALBUM, group)
            else -> folder("$COLLECTION$browseId", title, subtitle, art, MediaMetadata.MEDIA_TYPE_PLAYLIST, group)
        }

    private fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
        artwork: String? = null,
        type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        group: String? = null,
        browsableStyle: Int? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                // The car draws these itself, so they're put in the app's language here.
                .setTitle(com.opentune.i18n.Tr.t(title))
                .setSubtitle(subtitle?.takeIf { it.isNotBlank() }?.let(com.opentune.i18n.Tr::t))
                .setArtworkUri(CarArtwork.uriFor(context, null, artwork))
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(type)
                .setExtras(
                    groupExtras(group?.let(com.opentune.i18n.Tr::t)).apply {
                        browsableStyle?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
                    },
                )
                .build(),
        )
        .build()
        .also { known[id] = it }

    private fun groupExtras(group: String?) = Bundle().apply {
        group?.takeIf { it.isNotBlank() }?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
    }

    /** Lets the browser that asked read the artwork this app serves it. */
    private fun grantArtwork(items: List<MediaItem>, browser: MediaSession.ControllerInfo?) {
        val pkg = browser?.packageName?.takeIf { it.isNotBlank() && it != context.packageName } ?: return
        items.mapNotNull { it.mediaMetadata.artworkUri }.filter { it.scheme == "content" }.forEach { uri ->
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun ShelfItem.toSongOrNull(): Song? = videoId?.takeIf { browseId == null }?.let {
        Song(videoId = it, title = title, artist = com.opentune.data.innertube.InnertubeParser.artistFromSubtitle(subtitle), thumbnailUrl = thumbnailUrl)
    }

    private fun split(mediaId: String): Pair<String, String>? {
        val at = mediaId.lastIndexOf(SEP)
        return if (at <= 0) null else mediaId.substring(0, at) to mediaId.substring(at + 1)
    }

    companion object {
        private const val TAG = "CarLibrary"
        const val ROOT = "root"
        const val HOME = "home"
        const val RECENT = "recent"
        const val LIBRARY = "library"
        const val LIKED = "liked"
        const val DOWNLOADS = "downloads"
        const val SEARCH = "search"
        private const val SEARCH_FILTER = "search-filter:"
        /** The app's search filters the car offers. Videos is left out: watching video while driving is a distraction. */
        private val CAR_FILTERS = listOf(SearchFilter.SONGS, SearchFilter.ALBUMS, SearchFilter.ARTISTS, SearchFilter.PLAYLISTS)
        private const val HOME_SHELF = "home:"
        private const val PLAYLIST = "playlist:"
        private const val COLLECTION = "collection:"
        private const val ARTIST = "artist:"
        const val SERVER = "server"
        const val RADIO = "radio"
        private const val SERVER_ALBUM = "server-album:"
        private const val SERVER_PLAYLIST = "server-playlist:"
        private const val SEP = '|'
        private const val MAX_SONGS = 100
        /** Songs already on the device put at the head of a search's results. */
        private const val MAX_LOCAL = 20
        private const val MAX_SHELVES = 12
        private const val PER_SHELF = 8
        private const val HOME_FRESH_MS = 10 * 60 * 1000L
        /** Android Auto shows its search box when the root says so. */
        private const val EXTRA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}

/**
 * Artwork URIs Android Auto can load. It won't fetch web addresses for the
 * items it browses, so covers go through [ArtworkProvider] as content URIs:
 * a download's own cover file when there is one, the web image otherwise.
 */
object CarArtwork {
    fun uriFor(context: Context, videoId: String?, url: String?): Uri? {
        val authority = "${context.packageName}.artwork"
        if (videoId != null && Downloads.artFor(videoId) != null) {
            return Uri.Builder().scheme("content").authority(authority).appendPath("download").appendPath(videoId).build()
        }
        val web = Subsonic.resolveCover(url, CARD_ART_PX)?.artworkAt(CARD_ART_PX) ?: return null
        return Uri.Builder().scheme("content").authority(authority).appendPath("web").appendQueryParameter("u", web).build()
    }
}
