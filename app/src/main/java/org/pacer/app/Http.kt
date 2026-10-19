package org.pacer.app

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place this app makes an HTTP GET.
 *
 * It exists so that the tile fetcher has no retry policy of its own to get wrong: a tile
 * that fails is simply not drawn, and the next time it is on screen it is asked for again.
 * No retries, no backoff, no queue - a map being panned asks for a tile once and moves on,
 * and a retry loop behind a pan gesture is how a client gets itself rate-limited.
 *
 * One shared client means one place to say what this app identifies itself as, which is a
 * courtesy to the tile services that run on donated capacity.
 */
object Http {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) Pacer/1.0 (route editor; https://github.com/925316/pacer)"

    private const val TAG = "Pacer"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000

    /**
     * Fetches [url] and returns its body, or null on any failure.
     *
     * Null rather than an exception because every caller treats a failure the same way: it
     * shows a placeholder and tries again later if the thing is still wanted.
     */
    fun getBytes(url: String, referer: String? = null): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (referer != null) conn.setRequestProperty("Referer", referer)
            if (conn.responseCode !in 200..299) {
                null
            } else {
                conn.inputStream.use { it.readBytes() }
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Log.d(TAG, "GET failed: $url", t)
        null
    }
}
