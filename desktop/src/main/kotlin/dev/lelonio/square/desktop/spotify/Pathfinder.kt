package dev.lelonio.square.desktop.spotify

import dev.lelonio.square.desktop.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The persisted-query hashes Spotify's web gateway wants. They change whenever
 * Spotify rebuilds its web client, so the upstream project publishes the
 * current ones in pathfinder.json; a copy ships inside the app as a fallback.
 */
object Pathfinder {
    data class Keys(val search: String, val home: String, val appVersion: String)

    private const val URL = "https://raw.githubusercontent.com/Lelonio/Square/master/pathfinder.json"
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    @Volatile private var cached: Keys? = null
    @Volatile private var fetchedAt = 0L

    @Synchronized
    fun keys(): Keys {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - fetchedAt < TTL_MS }?.let { return it }
        val fresh = runCatching {
            val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                parse(r.body!!.string())
            }
        }.onFailure { Log.w("Pathfinder", "using the bundled keys: $it") }.getOrNull()
        val keys = fresh ?: cached ?: bundled()
        cached = keys
        fetchedAt = now
        return keys
    }

    private fun bundled(): Keys = parse(
        Pathfinder::class.java.getResource("/pathfinder.json")?.readText() ?: error("pathfinder.json missing"),
    )

    private fun parse(text: String): Keys {
        val o = JSONObject(text)
        return Keys(o.getString("search"), o.getString("home"), o.getString("appVersion"))
    }
}
