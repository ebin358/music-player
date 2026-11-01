package dev.lelonio.square.desktop.spotify

import dev.lelonio.square.desktop.Log
import dev.lelonio.square.desktop.Paths
import dev.lelonio.square.desktop.Settings
import dev.lelonio.square.desktop.SpotifyTokens
import dev.lelonio.square.desktop.audio.DesktopAudioOutput
import dev.lelonio.square.desktop.model.Collection
import dev.lelonio.square.desktop.model.CollectionKind
import dev.lelonio.square.desktop.model.CollectionPage
import dev.lelonio.square.desktop.model.LyricLine
import dev.lelonio.square.desktop.model.Lyrics
import dev.lelonio.square.desktop.model.SearchResults
import dev.lelonio.square.desktop.model.Shelf
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.nativecore.NativeEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * Spotify through the librespot engine: sign-in, the Connect session, and the
 * catalogue the access point and the web gateway serve. Playback itself is
 * driven by [dev.lelonio.square.desktop.player.PlayerController].
 */
object SpotifyMusic {

    sealed interface State {
        data object SignedOut : State
        data object Connecting : State
        data class Connected(val username: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(
        if (Settings.value.spotify != null) State.Connecting else State.SignedOut,
    )
    val state: StateFlow<State> = _state.asStateFlow()

    val isConnected: Boolean get() = _state.value is State.Connected

    /** Engine events, handed on to whoever is playing; set by the player. */
    @Volatile
    var listener: ((type: String, uri: String, positionMs: Long) -> Unit)? = null

    private val events = object : NativeEvents {
        override fun onEvent(type: String, uri: String, positionMs: Long) {
            listener?.invoke(type, uri, positionMs)
        }
    }

    private val connectLock = Mutex()

    /** Browser sign-in, then the engine. */
    suspend fun signIn(output: DesktopAudioOutput) = withContext(Dispatchers.IO) {
        _state.value = State.Connecting
        try {
            val tokens = SpotifyAuth.authorize()
            Settings.update { it.copy(spotify = tokens) }
            connect(output)
        } catch (e: Exception) {
            Log.e("Spotify", "sign-in failed", e)
            _state.value = State.Failed(e.message ?: "Sign-in failed")
        }
    }

    /** Starts the engine with the stored sign-in; quietly does nothing without one. */
    suspend fun connect(output: DesktopAudioOutput) = withContext(Dispatchers.IO) {
        connectLock.withLock {
            if (NativeBridge.isConnected) {
                _state.value = State.Connected(runCatching { NativeBridge.username() }.getOrDefault(""))
                return@withLock
            }
            val stored = Settings.value.spotify ?: run {
                _state.value = State.SignedOut
                return@withLock
            }
            _state.value = State.Connecting
            try {
                val tokens = fresh(stored)
                NativeBridge.setAudioOutput(output)
                NativeBridge.start(
                    clientId = SpotifyAuth.CLIENT_ID,
                    deviceName = "Square on ${deviceName()}",
                    deviceId = deviceId(),
                    accessToken = tokens.accessToken,
                    credentialsDir = Paths.spotifyCredentials.absolutePath,
                    cacheDir = Paths.spotifyCache.absolutePath,
                    language = Locale.getDefault().language.ifBlank { "en" },
                    bitrateKbps = Settings.value.spotifyBitrate,
                    crossfadeMs = 0,
                    listener = events,
                )
                runCatching { NativeBridge.volume = 65535 }
                _state.value = State.Connected(runCatching { NativeBridge.username() }.getOrDefault(""))
                Log.i("Spotify", "connected")
            } catch (e: Throwable) {
                Log.e("Spotify", "engine start failed", e)
                _state.value = State.Failed(e.message ?: "Could not connect to Spotify")
            }
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching { NativeBridge.shutdown() }
        Settings.update { it.copy(spotify = null) }
        Paths.spotifyCredentials.listFiles()?.forEach { it.deleteRecursively() }
        _state.value = State.SignedOut
    }

    private fun fresh(tokens: SpotifyTokens): SpotifyTokens {
        if (tokens.expiresAt - System.currentTimeMillis() > 5 * 60 * 1000) return tokens
        return SpotifyAuth.refresh(tokens).also { renewed -> Settings.update { it.copy(spotify = renewed) } }
    }

    private fun deviceId(): String = Settings.value.spotifyDeviceId ?: run {
        val id = (1..40).map { "0123456789abcdef".random() }.joinToString("")
        Settings.update { it.copy(spotifyDeviceId = id) }
        id
    }

    private fun deviceName(): String = runCatching {
        java.net.InetAddress.getLocalHost().hostName.substringBefore('.').replace('-', ' ')
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Mac"

    // --- Catalogue -------------------------------------------------------

    suspend fun library(): List<Collection> = withContext(Dispatchers.IO) {
        requireConnected()
        val liked = Collection(
            id = NativeBridge.collectionUri(),
            source = Source.SPOTIFY,
            kind = CollectionKind.LIKED,
            title = "Liked Songs",
            subtitle = "Spotify",
        )
        val playlists = JSONArray(NativeBridge.rootlist()).objects().mapNotNull { item ->
            val uri = item.optString("uri").takeIf { it.startsWith("spotify:playlist:") } ?: return@mapNotNull null
            Collection(
                id = uri,
                source = Source.SPOTIFY,
                kind = CollectionKind.PLAYLIST,
                title = item.optString("name"),
                subtitle = if (item.optBoolean("mine", false)) "Your playlist" else "Playlist",
                artworkUrl = item.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        listOf(liked) + playlists
    }

    suspend fun home(): List<Shelf> {
        val library = library()
        return listOf(
            Shelf("Your playlists", collections = library),
        )
    }

    suspend fun open(collection: Collection): CollectionPage = withContext(Dispatchers.IO) {
        requireConnected()
        val uris = JSONArray(NativeBridge.contextTracks(collection.id)).strings()
            .filter { it.startsWith("spotify:track:") }
            .distinct()
        val tracks = metadata(uris)
        val title = if (collection.kind == CollectionKind.PLAYLIST) {
            runCatching { JSONObject("{\"n\":${NativeBridge.playlistName(collection.id)}}").optString("n") }
                .getOrNull()?.takeIf { it.isNotBlank() && it != "null" } ?: collection.title
        } else {
            collection.title
        }
        val artwork = collection.artworkUrl
            ?: if (collection.kind == CollectionKind.PLAYLIST) {
                runCatching { JSONObject("{\"c\":${NativeBridge.playlistCover(collection.id)}}").optString("c") }
                    .getOrNull()?.takeIf { it.startsWith("http") }
            } else {
                null
            }
            ?: tracks.firstOrNull()?.artworkUrl
        CollectionPage(
            collection = collection.copy(
                title = title,
                artworkUrl = artwork,
                subtitle = collection.subtitle ?: "${tracks.size} songs",
            ),
            tracks = tracks,
        )
    }

    /** Display metadata, in the order asked, a hundred at a time. */
    fun metadata(uris: List<String>): List<Track> {
        val byUri = HashMap<String, Track>()
        uris.chunked(100).forEach { chunk ->
            val raw = runCatching { NativeBridge.tracksMetadata(JSONArray(chunk).toString()) }
                .onFailure { Log.w("Spotify", "metadata failed", it) }
                .getOrNull() ?: return@forEach
            JSONArray(raw).objects().forEach { o ->
                val uri = o.optString("uri")
                byUri[uri] = Track(
                    id = uri,
                    source = Source.SPOTIFY,
                    title = o.optString("name"),
                    artist = o.optString("artist"),
                    album = o.optString("album").takeIf { it.isNotBlank() },
                    artworkUrl = o.optString("artworkUrl").takeIf { it.startsWith("http") },
                    durationMs = o.optLong("durationMs"),
                    artistId = o.optString("artistUri").takeIf { it.startsWith("spotify:") },
                    albumId = o.optString("albumUri").takeIf { it.startsWith("spotify:") },
                    explicit = o.optBoolean("explicit"),
                )
            }
        }
        return uris.mapNotNull(byUri::get)
    }

    suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        requireConnected()
        val keys = Pathfinder.keys()
        val raw = NativeBridge.gatewayQuery(
            operation = "searchDesktop",
            hash = keys.search,
            variables = JSONObject()
                .put("searchTerm", query)
                .put("offset", 0)
                .put("limit", 10)
                .put("numberOfTopResults", 5)
                .put("includeAudiobooks", true)
                .put("includeArtistHasConcertsField", false)
                .put("includePreReleases", true)
                .put("includeLocalConcertsField", false)
                .put("includeAlbumPreReleases", false)
                .put("includeAuthors", false)
                .put("includeEpisodeContentRatingsV2", false)
                .toString(),
            language = Locale.getDefault().language.ifBlank { "en" },
            appVersion = keys.appVersion,
        )
        val search = JSONObject(raw).optJSONObject("data")?.optJSONObject("searchV2")
            ?: error("Spotify search is unavailable right now")

        SearchResults(
            tracks = search.items("tracksV2").mapNotNull { gatewayTrack(it.optJSONObject("item")?.optJSONObject("data")) },
            albums = search.items("albumsV2").mapNotNull { w ->
                val d = w.optJSONObject("data") ?: return@mapNotNull null
                val uri = d.optString("uri").takeIf { it.startsWith("spotify:album:") } ?: return@mapNotNull null
                Collection(
                    id = uri, source = Source.SPOTIFY, kind = CollectionKind.ALBUM,
                    title = d.optString("name"),
                    subtitle = d.optJSONObject("artists")?.optJSONArray("items")?.objects()
                        ?.joinToString(", ") { it.optJSONObject("profile")?.optString("name").orEmpty() }
                        ?.ifBlank { null } ?: "Album",
                    artworkUrl = d.optJSONObject("coverArt").image(),
                )
            },
            artists = search.items("artists").mapNotNull { w ->
                val d = w.optJSONObject("data") ?: return@mapNotNull null
                val uri = d.optString("uri").takeIf { it.startsWith("spotify:artist:") } ?: return@mapNotNull null
                Collection(
                    id = uri, source = Source.SPOTIFY, kind = CollectionKind.ARTIST,
                    title = d.optJSONObject("profile")?.optString("name").orEmpty(),
                    subtitle = "Artist",
                    artworkUrl = d.optJSONObject("visuals")?.optJSONObject("avatarImage").image(),
                )
            },
            playlists = search.items("playlists").mapNotNull { w ->
                val d = w.optJSONObject("data") ?: return@mapNotNull null
                val uri = d.optString("uri").takeIf { it.startsWith("spotify:playlist:") } ?: return@mapNotNull null
                Collection(
                    id = uri, source = Source.SPOTIFY, kind = CollectionKind.PLAYLIST,
                    title = d.optString("name"),
                    subtitle = d.optJSONObject("ownerV2")?.optJSONObject("data")?.optString("name")
                        ?.ifBlank { null } ?: "Playlist",
                    artworkUrl = d.optJSONObject("images")?.optJSONArray("items")?.optJSONObject(0).image(),
                )
            },
        )
    }

    suspend fun lyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        if (!isConnected) return@withContext null
        val raw = runCatching { NativeBridge.lyrics(track.id) }.getOrNull() ?: return@withContext null
        if (raw == "null" || raw.isBlank()) return@withContext null
        val lyrics = runCatching { JSONObject(raw) }.getOrNull()?.let { it.optJSONObject("lyrics") ?: it }
            ?: return@withContext null
        val synced = lyrics.optString("syncType") != "UNSYNCED"
        val lines = lyrics.optJSONArray("lines")?.objects()?.map { line ->
            LyricLine(
                timeMs = if (synced) line.optString("startTimeMs").toLongOrNull() else null,
                text = line.optString("words").replace("♪", "").trim(),
            )
        }.orEmpty()
        lines.takeIf { it.isNotEmpty() }?.let { Lyrics(it, "Spotify") }
    }

    private fun gatewayTrack(d: JSONObject?): Track? {
        d ?: return null
        val uri = d.optString("uri").takeIf { it.startsWith("spotify:track:") } ?: return null
        val artists = d.optJSONObject("artists")?.optJSONArray("items")?.objects().orEmpty()
        val album = d.optJSONObject("albumOfTrack")
        return Track(
            id = uri,
            source = Source.SPOTIFY,
            title = d.optString("name"),
            artist = artists.joinToString(", ") { it.optJSONObject("profile")?.optString("name").orEmpty() },
            album = album?.optString("name"),
            artworkUrl = album?.optJSONObject("coverArt").image(),
            durationMs = d.optJSONObject("duration")?.optLong("totalMilliseconds") ?: 0,
            artistId = artists.firstOrNull()?.optString("uri")?.takeIf { it.startsWith("spotify:") },
            albumId = album?.optString("uri")?.takeIf { it.startsWith("spotify:") },
            explicit = d.optJSONObject("contentRating")?.optString("label") == "EXPLICIT",
        )
    }

    private fun requireConnected() {
        if (!NativeBridge.isConnected) error("Spotify is not connected")
    }

    private fun JSONObject.items(section: String): List<JSONObject> =
        optJSONObject(section)?.optJSONArray("items")?.objects().orEmpty()

    /** The picture closest to 300 px wide, or whatever there is. */
    private fun JSONObject?.image(): String? {
        val sources = this?.optJSONArray("sources")?.objects() ?: return null
        return sources.minByOrNull { kotlin.math.abs(it.optInt("width", 300) - 300) }
            ?.optString("url")?.takeIf { it.startsWith("http") }
    }

    fun timeZone(): String = TimeZone.getDefault().id
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }
