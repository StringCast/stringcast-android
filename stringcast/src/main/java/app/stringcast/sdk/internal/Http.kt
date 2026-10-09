package app.stringcast.sdk.internal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HttpURLConnection client. All methods throw IOException on transport failure.
 *
 * API requests (`sendKey = true`: manifest, missing) carry the SDK key and the check-in headers
 * from [checkInHeaders]; public requests (`sendKey = false`: CDN bundle downloads) carry neither.
 */
internal class Http(
    private val apiKey: String,
    private val userAgent: String,
    private val checkInHeaders: () -> Map<String, String> = { emptyMap() },
) {

    class Response(val code: Int, val body: ByteArray?, val etag: String?) {
        val isSuccess get() = code in 200..299
        fun text(): String = body?.toString(Charsets.UTF_8).orEmpty()
    }

    @Throws(IOException::class)
    fun get(url: String, ifNoneMatch: String? = null, sendKey: Boolean = true): Response =
        request("GET", url, null, ifNoneMatch, sendKey)

    @Throws(IOException::class)
    fun postJson(url: String, json: String): Response =
        request("POST", url, json.toByteArray(Charsets.UTF_8), null, true)

    private fun request(
        method: String,
        url: String,
        body: ByteArray?,
        ifNoneMatch: String?,
        sendKey: Boolean,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", userAgent)
            if (sendKey) {
                conn.setRequestProperty("X-Api-Key", apiKey)
                val extra = try {
                    checkInHeaders()
                } catch (t: Throwable) {
                    Logger.w("Building check-in headers failed", t)
                    emptyMap()
                }
                for ((k, v) in extra) conn.setRequestProperty(k, v)
            }
            if (ifNoneMatch != null) conn.setRequestProperty("If-None-Match", ifNoneMatch)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code >= 400) conn.errorStream else if (code == 304) null else conn.inputStream
            val bytes = stream?.use { readLimited(it) }
            return Response(code, bytes, conn.getHeaderField("ETag"))
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BODY_BYTES) throw IOException("response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val MAX_BODY_BYTES = 32L * 1024 * 1024
    }
}
