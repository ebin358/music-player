package dev.lelonio.square.desktop.model

import kotlinx.serialization.Serializable

@Serializable
enum class Source(val label: String) {
    YOUTUBE("YouTube Music"),
    SPOTIFY("Spotify"),
    LOCAL("This Mac"),
}

/**
 * One playable thing.
 *
 * [id] is what the source plays it by: a YouTube video id, a Spotify track URI,
 * or an absolute file path.
 */
@Serializable
data class Track(
    val id: String,
    val source: Source,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0,
    val artistId: String? = null,
    val albumId: String? = null,
    val explicit: Boolean = false,
) {
    val key: String get() = "${source.name}:$id"
}

@Serializable
enum class CollectionKind { ALBUM, PLAYLIST, ARTIST, LIKED }

@Serializable
data class Collection(
    val id: String,
    val source: Source,
    val kind: CollectionKind,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
) {
    val key: String get() = "${source.name}:${kind.name}:$id"
}

data class Shelf(
    val title: String,
    val tracks: List<Track> = emptyList(),
    val collections: List<Collection> = emptyList(),
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Collection> = emptyList(),
    val artists: List<Collection> = emptyList(),
    val playlists: List<Collection> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

data class CollectionPage(
    val collection: Collection,
    val tracks: List<Track>,
    val shelves: List<Shelf> = emptyList(),
    val description: String? = null,
)

/** One line of lyrics; [timeMs] is null when the lyrics are not synced. */
data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val provider: String) {
    val synced: Boolean get() = lines.any { it.timeMs != null }
}

enum class RepeatMode { OFF, ALL, ONE }
