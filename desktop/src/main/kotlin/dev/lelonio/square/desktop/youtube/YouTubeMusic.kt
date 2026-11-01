package dev.lelonio.square.desktop.youtube

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import dev.lelonio.square.desktop.Log
import dev.lelonio.square.desktop.model.Collection
import dev.lelonio.square.desktop.model.CollectionKind
import dev.lelonio.square.desktop.model.CollectionPage
import dev.lelonio.square.desktop.model.SearchResults
import dev.lelonio.square.desktop.model.Shelf
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music without an account: the InnerTube client for the catalogue,
 * NewPipe for stream addresses. The same split as the Android app's
 * YouTubeBackend, which this is a slimmed copy of.
 */
object YouTubeMusic {

    suspend fun home(): List<Shelf> = withContext(Dispatchers.IO) {
        val page = YouTube.home().getOrThrow()
        page.sections.mapNotNull { section -> shelfOf(section.title, section.items) }
    }

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        coroutineScope {
            val songs = async { items(query, YouTube.SearchFilter.FILTER_SONG) }
            val albums = async { items(query, YouTube.SearchFilter.FILTER_ALBUM) }
            val artists = async { items(query, YouTube.SearchFilter.FILTER_ARTIST) }
            val playlists = async { items(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST) }
            SearchResults(
                tracks = songs.await().filterIsInstance<SongItem>().map(::track),
                albums = albums.await().filterIsInstance<AlbumItem>().map(::collection),
                artists = artists.await().filterIsInstance<ArtistItem>().map(::collection),
                playlists = playlists.await().filterIsInstance<PlaylistItem>().map(::collection),
            )
        }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        YouTube.searchSuggestions(query).getOrNull()?.queries.orEmpty().take(8)
    }

    suspend fun open(collection: Collection): CollectionPage = withContext(Dispatchers.IO) {
        when (collection.kind) {
            CollectionKind.ALBUM -> {
                val page = YouTube.album(collection.id).getOrThrow()
                val art = page.album.thumbnail.atSize(COVER)
                CollectionPage(
                    collection = collection.copy(
                        title = page.album.title,
                        subtitle = listOfNotNull(
                            page.album.artists?.joinToString(", ") { it.name },
                            page.album.year?.toString(),
                        ).joinToString(" · "),
                        artworkUrl = art,
                    ),
                    tracks = page.songs.map { track(it).copy(artworkUrl = art, album = page.album.title, albumId = collection.id) },
                    shelves = listOfNotNull(
                        page.otherVersions.takeIf { it.isNotEmpty() }
                            ?.let { Shelf("Other versions", collections = it.map(::collection)) },
                    ),
                )
            }

            CollectionKind.PLAYLIST -> {
                val first = YouTube.playlist(collection.id).getOrThrow()
                val songs = first.songs.toMutableList()
                var token = first.songsContinuation ?: first.continuation
                var pages = 0
                while (token != null && songs.size < 1000 && pages++ < 20) {
                    val more = YouTube.playlistContinuation(token).getOrNull() ?: break
                    if (more.songs.isEmpty()) break
                    songs += more.songs
                    token = more.continuation
                }
                CollectionPage(
                    collection = collection.copy(
                        title = first.playlist.title,
                        subtitle = listOfNotNull(first.playlist.author?.name, first.playlist.songCountText)
                            .joinToString(" · ").ifBlank { collection.subtitle },
                        artworkUrl = first.playlist.thumbnail?.atSize(COVER) ?: collection.artworkUrl,
                    ),
                    tracks = songs.distinctBy { it.id }.map(::track),
                    description = first.playlist.description,
                )
            }

            CollectionKind.ARTIST -> {
                val page = YouTube.artist(collection.id).getOrThrow()
                val songs = page.sections.firstOrNull { s -> s.items.any { it is SongItem } }
                CollectionPage(
                    collection = collection.copy(
                        title = page.artist.title,
                        subtitle = page.monthlyListenerCount ?: page.subscriberCountText ?: "Artist",
                        artworkUrl = page.artist.thumbnail?.atSize(COVER) ?: collection.artworkUrl,
                    ),
                    tracks = songs?.items?.filterIsInstance<SongItem>()?.map(::track).orEmpty(),
                    shelves = page.sections.filter { it !== songs }.mapNotNull { shelfOf(it.title, it.items) },
                    description = page.description,
                )
            }

            CollectionKind.LIKED -> error("liked songs are kept on this Mac")
        }
    }

    /** A station from [seed]: what YouTube Music plays after it. */
    suspend fun radio(seed: Track): List<Track> = withContext(Dispatchers.IO) {
        val first = YouTube.next(WatchEndpoint(videoId = seed.id, playlistId = "RDAMVM${seed.id}")).getOrThrow()
        val more = first.continuation
            ?.let { runCatching { YouTube.next(first.endpoint, it).getOrThrow().items }.getOrNull() }
            .orEmpty()
        (first.items + more).distinctBy { it.id }.map(::track)
    }

    private val streams = ConcurrentHashMap<String, Pair<Long, String>>()

    /** An audio address for [videoId]: AAC (itag 140) first, which the engine decodes. */
    fun streamUrl(videoId: String): String {
        val now = System.currentTimeMillis()
        streams[videoId]?.takeIf { now - it.first < STREAM_TTL_MS }?.let { return it.second }
        NewPipeDownloader.ensure()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val url = info.audioStreams
            .filter { !it.content.isNullOrEmpty() && it.format == MediaFormat.M4A }
            .maxByOrNull { it.averageBitrate }
            ?.content
            ?: error("no AAC stream for $videoId (only ${info.audioStreams.mapNotNull { it.format?.name }.distinct()})")
        streams[videoId] = now to url
        return url
    }

    /** Resolves ahead of time, so the next song starts without a pause. */
    fun prefetch(videoId: String) {
        Thread {
            runCatching { streamUrl(videoId) }.onFailure { Log.d("YouTube", "prefetch $videoId: $it") }
        }.apply { isDaemon = true }.start()
    }

    fun forget(videoId: String) {
        streams.remove(videoId)
    }

    private suspend fun items(query: String, filter: YouTube.SearchFilter): List<YTItem> =
        YouTube.search(query, filter).map { it.items }
            .onFailure { Log.w("YouTube", "search failed", it) }
            .getOrDefault(emptyList())

    private fun shelfOf(title: String, items: List<YTItem>): Shelf? {
        val tracks = items.filterIsInstance<SongItem>().map(::track)
        val collections = items.mapNotNull { item ->
            when (item) {
                is AlbumItem -> collection(item)
                is PlaylistItem -> collection(item)
                is ArtistItem -> collection(item)
                else -> null
            }
        }
        if (tracks.isEmpty() && collections.isEmpty()) return null
        return Shelf(title, tracks, collections)
    }

    fun track(item: SongItem): Track {
        val named = item.artists.filter { a ->
            val n = a.name.trim()
            n.isNotEmpty() && n.lowercase() !in JOINERS && n.any { it.isLetterOrDigit() }
        }
        return Track(
            id = item.id,
            source = Source.YOUTUBE,
            title = item.title,
            artist = named.joinToString(", ") { it.name.trim() },
            album = item.album?.name,
            artworkUrl = item.thumbnail.atSize(COVER),
            durationMs = item.duration?.takeIf { it > 0 }?.times(1000L) ?: 0L,
            artistId = named.firstNotNullOfOrNull { it.id?.takeIf(String::isNotBlank) },
            albumId = item.album?.id?.takeIf { it.startsWith("MPRE") },
            explicit = item.explicit,
        )
    }

    private fun collection(item: AlbumItem) = Collection(
        id = item.browseId,
        source = Source.YOUTUBE,
        kind = CollectionKind.ALBUM,
        title = item.title,
        subtitle = listOfNotNull(item.artists?.joinToString(", ") { it.name }, item.year?.toString())
            .joinToString(" · ").ifBlank { "Album" },
        artworkUrl = item.thumbnail.atSize(COVER),
    )

    private fun collection(item: PlaylistItem) = Collection(
        id = item.id,
        source = Source.YOUTUBE,
        kind = CollectionKind.PLAYLIST,
        title = item.title,
        subtitle = item.author?.name ?: "Playlist",
        artworkUrl = item.thumbnail?.atSize(COVER),
    )

    private fun collection(item: ArtistItem) = Collection(
        id = item.id,
        source = Source.YOUTUBE,
        kind = CollectionKind.ARTIST,
        title = item.title,
        subtitle = "Artist",
        artworkUrl = item.thumbnail?.atSize(COVER),
    )

    private const val COVER = 720
    private const val STREAM_TTL_MS = 2 * 60 * 60 * 1000L
    private val JOINERS = setOf(",", "&", "and", "e", "y", "et", "und", "x", "feat.", "ft.", "with")
}

/** Asks Google's image servers for a bigger picture; see the Android app's Thumbnails.kt. */
fun String.atSize(width: Int, height: Int = width): String {
    if (GOOGLE_USER_CONTENT.matches(this)) return "${substringBefore("=w")}=w$width-h$height-p-l90-rj"
    if (GGPHT.matches(this)) return "$this-s$width"
    YTIMG.matchEntire(this)?.groupValues?.let { (_, id) -> return "https://i.ytimg.com/vi/$id/sddefault.jpg" }
    return this
}

private val GOOGLE_USER_CONTENT = Regex("https://(?:lh3|yt3)\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*")
private val GGPHT = Regex("https://yt3\\.ggpht\\.com/.*=s(\\d+)")
private val YTIMG = Regex("https://i\\.ytimg\\.com/vi/([^/]+)/.*")
