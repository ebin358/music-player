package dev.lelonio.square.nativecore

/**
 * The Rust engine's plain-stream player: YouTube Music's AAC and local files,
 * decoded natively and written to an [dev.lelonio.square.desktop.audio.DesktopAudioOutput].
 * See native/src/stream.rs.
 */
object StreamBridge {
    init {
        dev.lelonio.square.desktop.NativeLib.load()
    }

    const val IDLE = 0
    const val LOADING = 1
    const val PLAYING = 2
    const val PAUSED = 3
    const val ENDED = 4
    const val FAILED = 5

    fun play(source: String, startMs: Long, output: Any) = nativePlay(source, startMs, output)
    fun setPaused(paused: Boolean) = nativeSetPaused(paused)
    fun seek(positionMs: Long) = nativeSeek(positionMs)
    fun stop() = nativeStop()
    val state: Int get() = nativeState()
    val positionMs: Long get() = nativePosition()
    val durationMs: Long get() = nativeDuration()
    val error: String get() = nativeError() ?: ""

    @JvmStatic private external fun nativePlay(source: String, startMs: Long, output: Any)
    @JvmStatic private external fun nativeSetPaused(paused: Boolean)
    @JvmStatic private external fun nativeSeek(positionMs: Long)
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeState(): Int
    @JvmStatic private external fun nativePosition(): Long
    @JvmStatic private external fun nativeDuration(): Long
    @JvmStatic private external fun nativeError(): String?
}
