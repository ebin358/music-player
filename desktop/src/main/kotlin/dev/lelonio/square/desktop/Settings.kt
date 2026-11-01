package dev.lelonio.square.desktop

import dev.lelonio.square.desktop.model.Collection
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis. */
    val expiresAt: Long,
)

@Serializable
data class SettingsData(
    val source: Source? = null,
    val volume: Float = 0.8f,
    val spotify: SpotifyTokens? = null,
    val spotifyDeviceId: String? = null,
    val spotifyBitrate: Int = 320,
    /** Songs and lists saved on this Mac, for sources without a signed-in library. */
    val likedTracks: List<Track> = emptyList(),
    val savedCollections: List<Collection> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val localFolders: List<String> = emptyList(),
    val autoplay: Boolean = true,
)

/** Where the app keeps its files: ~/Library/Application Support/Square. */
object Paths {
    val root: File by lazy {
        val home = System.getProperty("user.home")
        val mac = File(home, "Library/Application Support/Square")
        (if (File(home, "Library").isDirectory) mac else File(home, ".square")).apply { mkdirs() }
    }
    val spotifyCredentials: File get() = File(root, "spotify-credentials").apply { mkdirs() }
    val spotifyCache: File get() = File(root, "spotify-cache").apply { mkdirs() }
}

/** Settings as one JSON file, written whenever they change. */
object Settings {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file get() = File(Paths.root, "settings.json")

    private val _data = MutableStateFlow(load())
    val data: StateFlow<SettingsData> = _data.asStateFlow()
    val value: SettingsData get() = _data.value

    @Synchronized
    fun update(change: (SettingsData) -> SettingsData) {
        val next = change(_data.value)
        if (next == _data.value) return
        _data.value = next
        runCatching {
            val tmp = File(file.parentFile, "settings.json.tmp")
            tmp.writeText(json.encodeToString(SettingsData.serializer(), next))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.e("Settings", "could not save", it) }
    }

    private fun load(): SettingsData = runCatching {
        if (file.isFile) json.decodeFromString(SettingsData.serializer(), file.readText()) else SettingsData()
    }.getOrElse {
        Log.e("Settings", "unreadable settings, starting fresh", it)
        SettingsData()
    }
}
