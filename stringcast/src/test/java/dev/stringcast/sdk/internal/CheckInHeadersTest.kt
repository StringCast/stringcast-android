package dev.stringcast.sdk.internal

import dev.stringcast.sdk.BuildConfig
import dev.stringcast.sdk.StringCastConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class CheckInHeadersTest {

    @Test fun buildsAllHeaders() {
        val h = CheckInHeaders.build("com.example.app", "2.3.1", "0.1.0", "es")
        assertEquals(
            mapOf(
                "X-StringCast-Platform" to "android",
                "X-StringCast-App-Id" to "com.example.app",
                "X-StringCast-App-Version" to "2.3.1",
                "X-StringCast-SDK-Version" to "0.1.0",
                "X-StringCast-Language" to "es",
            ),
            h,
        )
    }

    @Test fun omitsNullAndEmptyValues() {
        val h = CheckInHeaders.build(null, "", "0.1.0", "  ")
        assertEquals(setOf("X-StringCast-Platform", "X-StringCast-SDK-Version"), h.keys)
    }

    @Test fun valuesAreAsciiSafe() {
        assertEquals("1.0 ()", CheckInHeaders.asciiSafe(" 1.0 (β)\r\n"))
        assertEquals("", CheckInHeaders.asciiSafe("βé"))
        assertFalse(CheckInHeaders.build(null, "ü", null, null).containsKey("X-StringCast-App-Version"))
    }

    @Test fun sdkVersionAndDefaultBaseUrl() {
        assertEquals("0.1.0", BuildConfig.SDK_VERSION)
        assertEquals("https://console.stringcast.app", StringCastConfig.DEFAULT_BASE_URL)
    }

    // ---- wire-level: what Http actually puts on the request -------------------------------

    private lateinit var server: ServerSocket
    private val requests = Collections.synchronizedList(mutableListOf<Map<String, String>>())

    @Before fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                s.use { sock ->
                    val r = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                    r.readLine() // request line
                    val headers = LinkedHashMap<String, String>()
                    var len = 0
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                    }
                    headers["content-length"]?.toIntOrNull()?.let { len = it }
                    repeat(len) { r.read() }
                    requests += headers
                    sock.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    sock.getOutputStream().flush()
                }
            }
        }
    }

    @After fun stopServer() {
        server.close()
    }

    private val base get() = "http://127.0.0.1:${server.localPort}"

    private fun http() = Http("pk_test", "test-agent") {
        CheckInHeaders.build("com.example.app", "2.3.1", BuildConfig.SDK_VERSION, "es")
    }

    @Test fun manifestAndMissingCarryCheckInHeaders() {
        val http = http()
        assertEquals(200, http.get("$base/v1/sdk/p_test/manifest", ifNoneMatch = "\"v1\"").code)
        assertEquals(200, http.postJson("$base/v1/sdk/p_test/missing", "{}").code)
        assertEquals(2, requests.size)
        for (h in requests) {
            assertEquals("pk_test", h["x-api-key"])
            assertEquals("android", h["x-stringcast-platform"])
            assertEquals("com.example.app", h["x-stringcast-app-id"])
            assertEquals("2.3.1", h["x-stringcast-app-version"])
            assertEquals("0.1.0", h["x-stringcast-sdk-version"])
            assertEquals("es", h["x-stringcast-language"])
        }
    }

    @Test fun bundleDownloadsCarryNoKeyAndNoCheckInHeaders() {
        http().get("$base/v1/sdk/p_test/bundles/1/es.json", sendKey = false)
        val h = requests.single()
        assertNull(h["x-api-key"])
        assertTrue(h.keys.none { it.startsWith("x-stringcast-") })
    }

    @Test fun throwingProviderNeverBreaksTheRequest() {
        val http = Http("pk_test", "test-agent") { error("boom") }
        assertEquals(200, http.get("$base/v1/sdk/p_test/manifest").code)
        assertTrue(requests.single().keys.none { it.startsWith("x-stringcast-") })
    }
}
