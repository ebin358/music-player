package dev.lelonio.square.desktop

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import dev.lelonio.square.desktop.youtube.NewPipeDownloader
import dev.lelonio.square.nativecore.StreamBridge
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.nio.ByteBuffer

/**
 * `Square --selftest`: the whole YouTube path without a window.
 *
 * Run by CI on a real Mac after packaging, so a build that compiles but cannot
 * load its engine, search, resolve a stream or decode it is caught there.
 * Prints one line per step and exits non-zero on the first failure.
 */
object SelfTest {

    /** An output that only counts what it is given; the CI Mac has no speaker. */
    class CountingOutput {
        @Volatile var bytes = 0L
        @Volatile var rate = 0
        @Volatile var channels = 0
        fun start() = Unit
        fun stop() = Unit
        fun pause() = Unit
        fun drain() = Unit
        fun write(data: ByteBuffer, size: Int, sampleRate: Int, channelCount: Int) {
            bytes += size
            rate = sampleRate
            channels = channelCount
        }
        fun writeVoice(data: ByteBuffer, size: Int, sampleRate: Int, channelCount: Int) =
            write(data, size, sampleRate, channelCount)
    }

    fun run(): Int {
        fun step(name: String, block: () -> String): Boolean = try {
            println("PASS $name: ${block()}")
            true
        } catch (t: Throwable) {
            println("FAIL $name: $t")
            t.printStackTrace(System.out)
            false
        }

        var song: SongItem? = null
        var url: String? = null

        val ok = step("native engine") {
            NativeLib.load()
            "loaded"
        } && step("youtube music search") {
            val found = runBlocking {
                YouTube.search("daft punk one more time", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            }.items.filterIsInstance<SongItem>()
            song = found.firstOrNull() ?: error("no songs in ${found.size} results")
            "${found.size} songs, first: ${song!!.title} — ${song!!.artists.joinToString { it.name }} (${song!!.id})"
        } && step("stream url") {
            NewPipeDownloader.ensure()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${song!!.id}")
            val stream = info.audioStreams
                .filter { !it.content.isNullOrEmpty() && it.format == MediaFormat.M4A }
                .maxByOrNull { it.averageBitrate }
                ?: error("no M4A among ${info.audioStreams.map { it.format }}")
            url = stream.content
            "M4A ${stream.averageBitrate} kbps"
        } && step("decode") {
            val output = CountingOutput()
            StreamBridge.play(url!!, 30_000, output)
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline &&
                StreamBridge.state < StreamBridge.ENDED &&
                (output.rate == 0 || output.bytes < output.rate * output.channels * 2L * 3)
            ) Thread.sleep(50)
            val state = StreamBridge.state
            val position = StreamBridge.positionMs
            StreamBridge.stop()
            if (state == StreamBridge.FAILED) error("decoder failed: ${StreamBridge.error}")
            if (output.rate == 0) error("no audio in 30 s (state $state)")
            "%.1f s of audio at %d Hz × %d from 0:30, position %d ms".format(
                output.bytes / (output.rate * output.channels * 2.0), output.rate, output.channels, position,
            )
        } && step("audio device") {
            val format = javax.sound.sampled.AudioFormat(44_100f, 16, 2, true, false)
            val line = javax.sound.sampled.AudioSystem.getSourceDataLine(format)
            line.open(format)
            line.close()
            "44.1 kHz stereo line opens"
        }
        return if (ok) 0 else 1
    }
}
