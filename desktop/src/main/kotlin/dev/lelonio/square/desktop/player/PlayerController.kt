package dev.lelonio.square.desktop.player

import dev.lelonio.square.desktop.Log
import dev.lelonio.square.desktop.Settings
import dev.lelonio.square.desktop.audio.DesktopAudioOutput
import dev.lelonio.square.desktop.model.Collection
import dev.lelonio.square.desktop.model.RepeatMode
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track
import dev.lelonio.square.desktop.spotify.SpotifyMusic
import dev.lelonio.square.desktop.youtube.YouTubeMusic
import dev.lelonio.square.nativecore.NativeBridge
import dev.lelonio.square.nativecore.StreamBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The queue, and which engine is playing it.
 *
 * Both sources go through the one Rust engine and the one [DesktopAudioOutput]:
 * Spotify tracks through librespot, YouTube and local files through the stream
 * decoder. The queue is this class's own, so it can mix sources; each track is
 * handed to its engine as it comes up, and the end of a track is noticed here
 * and answered by starting the next one.
 */
class PlayerController(val output: DesktopAudioOutput) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    val current: StateFlow<Track?> = combine(_queue, _index) { q, i -> q.getOrNull(i) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeat: StateFlow<RepeatMode> = _repeat.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _volume = MutableStateFlow(Settings.value.volume)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The album or playlist the queue came from, if any. */
    private val _context = MutableStateFlow<Collection?>(null)
    val context: StateFlow<Collection?> = _context.asStateFlow()

    /** The queue as it was given, for turning shuffle back off. */
    private var unshuffled: List<Track> = emptyList()

    /** Which engine holds the current track. */
    @Volatile private var mode: Source? = null

    private var loadJob: Job? = null

    /** Bumped on every start; an end or failure is answered once per start. */
    @Volatile private var generation = 0
    @Volatile private var answered = -1
    @Volatile private var spotifyTickAt = 0L

    init {
        output.volume = _volume.value
        SpotifyMusic.listener = ::onSpotifyEvent
        scope.launch { pollLoop() }
    }

    // --- Commands --------------------------------------------------------

    fun play(tracks: List<Track>, start: Int = 0, context: Collection? = null) {
        if (tracks.isEmpty()) return
        val first = start.coerceIn(tracks.indices)
        unshuffled = tracks
        _context.value = context
        if (_shuffle.value) {
            val chosen = tracks[first]
            _queue.value = listOf(chosen) + tracks.filterIndexed { i, _ -> i != first }.shuffled()
            _index.value = 0
        } else {
            _queue.value = tracks
            _index.value = first
        }
        startCurrent()
    }

    fun toggle() {
        val track = current.value ?: return
        when (mode) {
            Source.SPOTIFY -> {
                if (!NativeBridge.isConnected) return startCurrent(_position.value)
                runCatching { if (_playing.value) NativeBridge.pause() else NativeBridge.play() }
                    .onFailure { startCurrent(_position.value) }
                _playing.value = !_playing.value
            }

            Source.YOUTUBE, Source.LOCAL -> when (StreamBridge.state) {
                StreamBridge.PLAYING, StreamBridge.LOADING -> {
                    StreamBridge.setPaused(true)
                    _playing.value = false
                }

                StreamBridge.PAUSED -> {
                    StreamBridge.setPaused(false)
                    _playing.value = true
                }

                else -> startCurrent(if (StreamBridge.state == StreamBridge.ENDED) 0 else _position.value)
            }

            null -> if (track.source == Source.SPOTIFY || track.source == Source.YOUTUBE || track.source == Source.LOCAL) {
                startCurrent(_position.value)
            }
        }
    }

    fun next() {
        val q = _queue.value
        val i = _index.value
        when {
            i + 1 < q.size -> jumpTo(i + 1)
            _repeat.value == RepeatMode.ALL && q.isNotEmpty() -> jumpTo(0)
            else -> scope.launch { if (extendWithRadio()) jumpTo(_index.value + 1) }
        }
    }

    fun previous() {
        if (_position.value > 3_000 || _index.value <= 0) {
            seek(0)
        } else {
            jumpTo(_index.value - 1)
        }
    }

    fun jumpTo(i: Int) {
        if (i !in _queue.value.indices) return
        _index.value = i
        startCurrent()
    }

    fun seek(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0)
        _position.value = target
        when (mode) {
            Source.SPOTIFY -> runCatching { NativeBridge.seek(target) }
            Source.YOUTUBE, Source.LOCAL -> {
                val s = StreamBridge.state
                if (s == StreamBridge.ENDED || s == StreamBridge.IDLE || s == StreamBridge.FAILED) {
                    startCurrent(target)
                } else {
                    StreamBridge.seek(target)
                }
            }

            null -> Unit
        }
        spotifyTickAt = System.currentTimeMillis()
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        _volume.value = v
        output.volume = v
        Settings.update { it.copy(volume = v) }
    }

    fun cycleRepeat() {
        _repeat.value = when (_repeat.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun toggleShuffle() {
        val now = current.value
        val turnOn = !_shuffle.value
        _shuffle.value = turnOn
        val q = _queue.value
        if (q.isEmpty()) return
        if (turnOn) {
            unshuffled = q
            val i = _index.value
            _queue.value = q.take(i + 1) + q.drop(i + 1).shuffled()
        } else {
            val restored = unshuffled.ifEmpty { q }
            _queue.value = restored
            _index.value = now?.let { t -> restored.indexOfFirst { it.key == t.key } }?.takeIf { it >= 0 } ?: 0
        }
    }

    fun playNext(track: Track) {
        if (_queue.value.isEmpty()) return play(listOf(track))
        val q = _queue.value.toMutableList()
        q.add(_index.value + 1, track)
        _queue.value = q
        unshuffled = unshuffled + track
    }

    fun addToQueue(track: Track) {
        if (_queue.value.isEmpty()) return play(listOf(track))
        _queue.value = _queue.value + track
        unshuffled = unshuffled + track
    }

    fun removeFromQueue(i: Int) {
        val q = _queue.value
        if (i !in q.indices || i == _index.value) return
        _queue.value = q.filterIndexed { j, _ -> j != i }
        if (i < _index.value) _index.value = _index.value - 1
    }

    fun startRadio(seed: Track) {
        play(listOf(seed))
        scope.launch { extendWithRadio(seed) }
    }

    fun clearError() {
        _error.value = null
    }

    fun shutdown() {
        runCatching { StreamBridge.stop() }
        runCatching { NativeBridge.stop() }
        output.close()
    }

    // --- Starting tracks -------------------------------------------------

    private fun startCurrent(positionMs: Long = 0) {
        val track = current.value ?: return
        val gen = ++generation
        loadJob?.cancel()
        _error.value = null
        _loading.value = true
        _position.value = positionMs
        _duration.value = track.durationMs

        when (track.source) {
            Source.SPOTIFY -> {
                if (mode != Source.SPOTIFY) runCatching { StreamBridge.stop() }
                mode = Source.SPOTIFY
                loadJob = scope.launch(Dispatchers.IO) {
                    if (!NativeBridge.isConnected) SpotifyMusic.connect(output)
                    if (!NativeBridge.isConnected) {
                        fail(gen, "Sign in to Spotify in Settings to play this song.", skip = false)
                        return@launch
                    }
                    ensureActive()
                    runCatching {
                        NativeBridge.setRepeat(false, false)
                        NativeBridge.loadQueue(listOf(track.id), 0, true, positionMs.toInt(), "")
                        spotifyTickAt = System.currentTimeMillis()
                        _playing.value = true
                    }.onFailure { fail(gen, "Spotify could not play this: ${it.message}", skip = true) }
                }
            }

            Source.YOUTUBE, Source.LOCAL -> {
                if (mode == Source.SPOTIFY) runCatching { NativeBridge.pause() }
                mode = track.source
                runCatching { StreamBridge.stop() }
                loadJob = scope.launch(Dispatchers.IO) {
                    val source = try {
                        if (track.source == Source.YOUTUBE) resolveYouTube(track.id) else track.id
                    } catch (e: Exception) {
                        Log.e("Player", "could not resolve ${track.title}", e)
                        fail(gen, "Couldn't load “${track.title}”: ${e.message}", skip = true)
                        return@launch
                    }
                    ensureActive()
                    if (gen != generation) return@launch
                    StreamBridge.play(source, positionMs, output)
                    _playing.value = true
                    // Ready the next song's address while this one plays.
                    _queue.value.getOrNull(_index.value + 1)
                        ?.takeIf { it.source == Source.YOUTUBE }
                        ?.let { YouTubeMusic.prefetch(it.id) }
                }
            }
        }
    }

    private fun resolveYouTube(id: String): String = try {
        YouTubeMusic.streamUrl(id)
    } catch (first: Exception) {
        YouTubeMusic.forget(id)
        Log.w("Player", "retrying $id after: ${first.message}")
        YouTubeMusic.streamUrl(id)
    }

    private fun fail(gen: Int, message: String, skip: Boolean) {
        if (gen != generation || answered == gen) return
        answered = gen
        _error.value = message
        _loading.value = false
        _playing.value = false
        if (skip && _index.value + 1 < _queue.value.size) {
            scope.launch {
                delay(1_500)
                if (gen == generation) jumpTo(_index.value + 1)
            }
        }
    }

    private fun trackEnded(gen: Int) {
        if (gen != generation || answered == gen) return
        answered = gen
        val q = _queue.value
        val i = _index.value
        when {
            _repeat.value == RepeatMode.ONE -> startCurrent()
            i + 1 < q.size -> jumpTo(i + 1)
            _repeat.value == RepeatMode.ALL && q.isNotEmpty() -> jumpTo(0)
            else -> scope.launch {
                if (Settings.value.autoplay && extendWithRadio()) {
                    jumpTo(i + 1)
                } else {
                    _playing.value = false
                    _position.value = 0
                }
            }
        }
    }

    /** Adds a YouTube Music station after the queue; true when something was added. */
    private suspend fun extendWithRadio(seed: Track? = current.value): Boolean {
        val from = seed?.takeIf { it.source == Source.YOUTUBE } ?: return false
        val have = _queue.value.map { it.key }.toSet()
        val more = runCatching { YouTubeMusic.radio(from) }
            .onFailure { Log.w("Player", "radio failed", it) }
            .getOrDefault(emptyList())
            .filter { it.key !in have }
        if (more.isEmpty()) return false
        _queue.value = _queue.value + more
        unshuffled = unshuffled + more
        return true
    }

    // --- Following the engines -------------------------------------------

    private suspend fun pollLoop() {
        while (scope.isActive) {
            delay(200)
            val gen = generation
            when (mode) {
                Source.YOUTUBE, Source.LOCAL -> {
                    if (loadJob?.isActive == true) continue
                    when (StreamBridge.state) {
                        StreamBridge.LOADING -> _loading.value = true
                        StreamBridge.PLAYING -> {
                            _loading.value = false
                            _playing.value = true
                        }

                        StreamBridge.PAUSED -> {
                            _loading.value = false
                            _playing.value = false
                        }

                        StreamBridge.ENDED -> trackEnded(gen)
                        StreamBridge.FAILED -> fail(gen, "Playback failed: ${StreamBridge.error}", skip = true)
                    }
                    val s = StreamBridge.state
                    if (s == StreamBridge.PLAYING || s == StreamBridge.PAUSED) {
                        _position.value = StreamBridge.positionMs
                        StreamBridge.durationMs.takeIf { it > 0 }?.let { _duration.value = it }
                    }
                }

                Source.SPOTIFY -> if (_playing.value && !_loading.value) {
                    val now = System.currentTimeMillis()
                    _position.value = (_position.value + (now - spotifyTickAt)).coerceAtMost(
                        _duration.value.takeIf { it > 0 } ?: Long.MAX_VALUE,
                    )
                    spotifyTickAt = now
                }

                null -> Unit
            }
        }
    }

    private fun onSpotifyEvent(type: String, uri: String, positionMs: Long) {
        if (mode != Source.SPOTIFY) return
        val track = current.value ?: return
        val ours = uri.isEmpty() || uri == track.id
        val gen = generation
        when (type) {
            "loading" -> if (ours) _loading.value = true
            "playing" -> if (ours) {
                _loading.value = false
                _playing.value = true
                _position.value = positionMs
                spotifyTickAt = System.currentTimeMillis()
            }

            "paused" -> if (ours) {
                _loading.value = false
                _playing.value = false
                _position.value = positionMs
            }

            "position" -> if (ours) {
                _position.value = positionMs
                spotifyTickAt = System.currentTimeMillis()
            }

            "end_of_track" -> if (ours) trackEnded(gen)
            "stopped" -> if (ours) _playing.value = false
            "unavailable" -> if (ours) fail(gen, "“${track.title}” isn't available on Spotify here.", skip = true)
        }
    }
}
