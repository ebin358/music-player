package dev.lelonio.square.desktop.audio

import dev.lelonio.square.desktop.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * The speaker, as the Rust engine sees it.
 *
 * Both of the engine's players call these methods over JNI: the librespot sink
 * (Spotify) and the stream decoder (YouTube Music, local files). Interleaved
 * signed 16-bit little-endian PCM arrives in [write], and the blocking
 * `SourceDataLine.write` is what paces the decoder, exactly as `AudioTrack`
 * does on Android.
 *
 * The method names and signatures are fixed by native/src/sink.rs and
 * native/src/stream.rs.
 */
class DesktopAudioOutput {

    /** 0..1, applied in software so it works on every output device. */
    @Volatile
    var volume: Float = 1f

    private val lock = Any()
    private var line: SourceDataLine? = null
    private var rate = 0
    private var channels = 0
    private var scratch = ByteArray(0)

    fun start() {
        synchronized(lock) { line?.start() }
    }

    /** Holds the audio already written; [start] carries on from it. */
    fun pause() {
        synchronized(lock) { line?.stop() }
    }

    /** Stops and throws away whatever has not reached the speaker. */
    fun stop() {
        synchronized(lock) {
            line?.let {
                it.stop()
                it.flush()
            }
        }
    }

    /** Lets the last of a song play out. */
    fun drain() {
        val current = synchronized(lock) { line } ?: return
        runCatching { if (current.isRunning) current.drain() }
    }

    fun write(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channelCount: Int) {
        val output = ensureLine(sampleRate, channelCount) ?: return
        if (scratch.size < sizeInBytes) scratch = ByteArray(sizeInBytes)
        val source = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.position(0)
        source.get(scratch, 0, sizeInBytes)

        val gain = volume
        if (gain < 0.999f) {
            var i = 0
            while (i + 1 < sizeInBytes) {
                val sample = ((scratch[i + 1].toInt() shl 8) or (scratch[i].toInt() and 0xFF)).toShort()
                val scaled = (sample * gain * gain).toInt().coerceIn(-32768, 32767)
                scratch[i] = scaled.toByte()
                scratch[i + 1] = (scaled shr 8).toByte()
                i += 2
            }
        }

        if (!output.isRunning) output.start()
        var offset = 0
        while (offset < sizeInBytes) {
            val written = output.write(scratch, offset, sizeInBytes - offset)
            if (written <= 0) break
            offset += written
        }
    }

    /** The Spotify DJ's voice; no effects to keep off it here. */
    fun writeVoice(data: ByteBuffer, sizeInBytes: Int, sampleRate: Int, channelCount: Int) =
        write(data, sizeInBytes, sampleRate, channelCount)

    fun close() {
        synchronized(lock) {
            line?.let {
                it.stop()
                it.flush()
                it.close()
            }
            line = null
        }
    }

    private fun ensureLine(sampleRate: Int, channelCount: Int): SourceDataLine? = synchronized(lock) {
        val existing = line
        if (existing != null && rate == sampleRate && channels == channelCount) return existing
        existing?.run {
            stop()
            flush()
            close()
        }
        line = null
        return try {
            val format = AudioFormat(sampleRate.toFloat(), 16, channelCount, true, false)
            // Half a second of headroom: enough to ride out a busy UI thread,
            // short enough that pause and seek still feel immediate.
            val bufferBytes = sampleRate * channelCount * 2 / 2
            AudioSystem.getSourceDataLine(format).apply {
                open(format, bufferBytes)
                start()
            }.also {
                line = it
                rate = sampleRate
                channels = channelCount
            }
        } catch (e: Exception) {
            Log.e("Audio", "could not open an output at $sampleRate Hz × $channelCount", e)
            null
        }
    }
}
