package dev.lelonio.square.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lelonio.square.desktop.Settings
import dev.lelonio.square.desktop.model.Collection
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track
import dev.lelonio.square.desktop.player.PlayerController
import dev.lelonio.square.desktop.spotify.SpotifyMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Library : Screen
    data object Queue : Screen
    data object Settings : Screen
    data class Detail(val collection: Collection) : Screen
}

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Ready(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (t: Throwable) {
    dev.lelonio.square.desktop.Log.e("UI", "load failed", t)
    Load.Failed(t.message ?: t.toString())
}

/** What the window is showing, and the commands every screen shares. */
class AppState(val player: PlayerController) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var source by mutableStateOf(Settings.value.source ?: Source.YOUTUBE)
        private set

    /** Settings as Compose state, so liked songs and saved lists redraw when they change. */
    var settings by mutableStateOf(Settings.value)
        private set

    init {
        scope.launch { Settings.data.collect { settings = it } }
    }

    val needsOnboarding get() = settings.source == null

    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    var nowPlayingOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")

    /** Bumped to make screens reload what they show. */
    var refreshKey by mutableStateOf(0)
        private set

    fun chooseSource(next: Source) {
        source = next
        Settings.update { it.copy(source = next) }
        stack.clear()
        stack.add(Screen.Home)
        if (next == Source.SPOTIFY && !SpotifyMusic.isConnected) {
            scope.launch { SpotifyMusic.connect(player.output) }
        }
    }

    fun navigate(to: Screen) {
        nowPlayingOpen = false
        if (stack.lastOrNull() == to) return
        stack.add(to)
    }

    fun root(to: Screen) {
        nowPlayingOpen = false
        stack.clear()
        stack.add(to)
    }

    fun back() {
        if (nowPlayingOpen) {
            nowPlayingOpen = false
        } else if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
    }

    fun refresh() {
        refreshKey++
    }

    fun open(collection: Collection) = navigate(Screen.Detail(collection))

    // --- Things kept on this Mac ----------------------------------------

    fun isLiked(track: Track): Boolean = settings.likedTracks.any { it.key == track.key }

    fun toggleLike(track: Track) = Settings.update { s ->
        if (s.likedTracks.any { it.key == track.key }) {
            s.copy(likedTracks = s.likedTracks.filterNot { it.key == track.key })
        } else {
            s.copy(likedTracks = listOf(track) + s.likedTracks)
        }
    }

    fun isSaved(collection: Collection): Boolean = settings.savedCollections.any { it.key == collection.key }

    fun toggleSaved(collection: Collection) = Settings.update { s ->
        if (s.savedCollections.any { it.key == collection.key }) {
            s.copy(savedCollections = s.savedCollections.filterNot { it.key == collection.key })
        } else {
            s.copy(savedCollections = listOf(collection) + s.savedCollections)
        }
    }

    fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        Settings.update { s -> s.copy(recentSearches = (listOf(q) + s.recentSearches.filterNot { it.equals(q, true) }).take(12)) }
    }
}
