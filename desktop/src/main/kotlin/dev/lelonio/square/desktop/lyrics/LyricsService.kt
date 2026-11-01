package dev.lelonio.square.desktop.lyrics

import dev.lelonio.square.desktop.Log
import dev.lelonio.square.desktop.model.LyricLine
import dev.lelonio.square.desktop.model.Lyrics
import dev.lelonio.square.desktop.model.Source
import dev.lelonio.square.desktop.model.Track
import dev.lelonio.square.desktop.spotify.SpotifyMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Spotify's own lyrics for Spotify tracks; LrcLib for everything, as the fallback. */
object LyricsService {
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Lyrics>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    suspend fun lyricsFor(track: Track): Lyrics? {
        cache[track.key]?.let { return it }
        if (track.key in missing) return null
        val found = (if (track.source == Source.SPOTIFY) SpotifyMusic.lyrics(track) else null)
            ?: lrclib(track)
        if (found != null) cache[track.key] = found else missing += track.key
        return found
    }

    private suspend fun lrclib(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val title = cleanTitle(track.title)
        val artist = track.artist.substringBefore(',').trim()
        runCatching {
            val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .apply {
                    track.album?.let { addQueryParameter("album_name", it) }
                    if (track.durationMs > 0) addQueryParameter("duration", (track.durationMs / 1000).toString())
                }
                .build()
            get(exact.toString())?.let(::JSONObject)?.let(::fromRecord)
                ?: run {
                    val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                        .addQueryParameter("track_name", title)
                        .addQueryParameter("artist_name", artist)
                        .build()
                    val results = get(search.toString())?.let(::JSONArray) ?: return@run null
                    val records = (0 until results.length()).mapNotNull(results::optJSONObject)
                    val best = records.sortedWith(
                        compareByDescending<JSONObject> { !it.optString("syncedLyrics").isNullOrBlank() && it.optString("syncedLyrics") != "null" }
                            .thenBy { kotlin.math.abs(it.optDouble("duration", 0.0) * 1000 - track.durationMs) },
                    ).firstOrNull()
                    best?.let(::fromRecord)
                }
        }.onFailure { Log.w("Lyrics", "lrclib failed for ${track.title}", it) }.getOrNull()
    }

    private fun fromRecord(o: JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, "LrcLib")
        }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Lyrics(plain.lines().map { LyricLine(null, it.trim()) }, "LrcLib")
    }

    /** `[mm:ss.xx] words`, possibly several stamps per line. */
    fun parseLrc(text: String): List<LyricLine> {
        val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val out = mutableListOf<LyricLine>()
        text.lineSequence().forEach { raw ->
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) return@forEach
            val words = raw.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].let { if (it.isEmpty()) 0L else it.padEnd(3, '0').take(3).toLong() }
                out += LyricLine(min * 60_000 + sec * 1000 + frac, words)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun cleanTitle(title: String) = title
        .replace(Regex("""\s*[(\[](official|lyric|audio|video|visualizer|remaster|hd|4k)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).header("User-Agent", "Square for Mac (github.com/Lelonio/Square)").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
}
