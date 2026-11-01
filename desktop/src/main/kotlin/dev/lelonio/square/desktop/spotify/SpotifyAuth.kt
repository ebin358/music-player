package dev.lelonio.square.desktop.spotify

import dev.lelonio.square.desktop.Log
import dev.lelonio.square.desktop.SpotifyTokens
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.awt.Desktop
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Authorization Code + PKCE against Spotify's accounts service, with the
 * redirect landing on a one-shot loopback listener; the same flow as the
 * Android app's SpotifyOAuth, with the browser being the Mac's default one.
 *
 * The client id is librespot's, because the access point refuses tokens minted
 * for any other; see the Android SpotifyOAuth for the full story, including
 * that this is where the app leaves what Spotify's terms permit.
 */
object SpotifyAuth {
    const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    private const val PORT = 5588
    private const val REDIRECT_URI = "http://127.0.0.1:$PORT/login"
    private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    private val SCOPES = listOf(
        "app-remote-control", "playlist-modify", "playlist-modify-private", "playlist-modify-public",
        "playlist-read", "playlist-read-collaborative", "playlist-read-private", "streaming",
        "user-follow-modify", "user-follow-read", "user-library-modify", "user-library-read",
        "user-modify", "user-modify-playback-state", "user-modify-private", "user-personalized",
        "user-read-currently-playing", "user-read-email", "user-read-play-history",
        "user-read-playback-position", "user-read-playback-state", "user-read-private",
        "user-read-recently-played", "user-top-read",
    )

    private val http = OkHttpClient()
    private val random = SecureRandom()

    @Volatile
    private var listening: ServerSocket? = null

    /** Opens the browser and blocks until Spotify redirects back, or two minutes pass. */
    fun authorize(): SpotifyTokens {
        cancel()
        val verifier = base64Url(ByteArray(64).also(random::nextBytes))
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = base64Url(ByteArray(16).also(random::nextBytes))

        ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).use { server ->
            listening = server
            server.soTimeout = 2 * 60 * 1000
            val url = AUTHORIZE_URL +
                "?client_id=$CLIENT_ID&response_type=code" +
                "&redirect_uri=${enc(REDIRECT_URI)}" +
                "&code_challenge_method=S256&code_challenge=$challenge" +
                "&state=$state&scope=${enc(SCOPES.joinToString(" "))}"
            openBrowser(url)

            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    error("Sign-in timed out. Try again.")
                }
                val query = socket.use { s ->
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = line.split(" ").getOrNull(1).orEmpty()
                    val isLogin = path.startsWith("/login")
                    val body = if (isLogin) PAGE else "Not found"
                    val status = if (isLogin) "200 OK" else "404 Not Found"
                    s.getOutputStream().write(
                        ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                    )
                    if (isLogin) path.substringAfter('?', "") else null
                } ?: continue

                val params = query.split('&').mapNotNull {
                    val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                    k to URLDecoder.decode(v, Charsets.UTF_8)
                }.toMap()
                params["error"]?.let { error("Spotify said: $it") }
                if (params["state"] != state) error("The sign-in answer did not match. Try again.")
                val code = params["code"] ?: error("Spotify sent no code.")
                return postToken(
                    FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("redirect_uri", REDIRECT_URI)
                        .add("client_id", CLIENT_ID)
                        .add("code_verifier", verifier)
                        .build(),
                    previousRefresh = null,
                )
            }
        }
    }

    fun refresh(tokens: SpotifyTokens): SpotifyTokens = postToken(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refreshToken)
            .add("client_id", CLIENT_ID)
            .build(),
        previousRefresh = tokens.refreshToken,
    )

    fun cancel() {
        runCatching { listening?.close() }
        listening = null
    }

    private fun postToken(form: FormBody, previousRefresh: String?): SpotifyTokens {
        val request = Request.Builder().url(TOKEN_URL).post(form).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Spotify refused the sign-in (${response.code}): ${text.take(200)}")
            val json = JSONObject(text)
            return SpotifyTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifBlank { previousRefresh ?: error("no refresh token") },
                expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000,
            )
        }
    }

    private fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (e: Exception) {
            Log.w("SpotifyAuth", "Desktop.browse failed", e)
        }
        ProcessBuilder("open", url).start()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private const val PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>Square</title>
<style>body{font:16px -apple-system,system-ui;background:#111;color:#eee;display:grid;place-items:center;height:90vh}</style>
</head><body><div><h2>Signed in to Spotify</h2><p>You can close this tab and go back to Square.</p></div></body></html>"""
}
