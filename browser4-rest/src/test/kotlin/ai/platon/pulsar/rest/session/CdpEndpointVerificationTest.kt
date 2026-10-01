package ai.platon.pulsar.rest.session

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Tests for the CDP endpoint verification added to [PulsarSessionManager]
 * (attach --cdp must fail loud instead of silently attaching to nothing).
 */
class CdpEndpointVerificationTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun stubCdpEndpoints(browser: String?, pageTargets: List<Map<String, String>>) {
        server.createContext("/json/version") { exchange ->
            val body = browser?.let {
                """{"Browser":"$it","Protocol-Version":"1.3","webSocketDebuggerUrl":"ws://x/"}"""
            } ?: ""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/json") { exchange ->
            val array = pageTargets.joinToString(",", "[", "]") { t ->
                """{"id":"x","type":"${t["type"]}","title":"${t["title"]}","url":"${t["url"]}"}"""
            }
            exchange.sendResponseHeaders(200, array.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(array.toByteArray()) }
        }
    }

    @Test
    @DisplayName("verifyCdpEndpoint accepts a healthy endpoint with page targets")
    fun verifyCdpEndpointAcceptsHealthyEndpoint() {
        stubCdpEndpoints("Chrome/151.0.0.0", listOf(mapOf("type" to "page", "title" to "t", "url" to "about:blank")))
        val result = PulsarSessionManager.verifyCdpEndpoint("http://127.0.0.1:$port")
        assertTrue(result.reachable)
        assertEquals("Chrome/151.0.0.0", result.browser)
        assertEquals(1, result.pageTargetCount)
    }

    @Test
    @DisplayName("verifyCdpEndpoint counts only page targets, ignoring browser_ui")
    fun verifyCdpEndpointCountsOnlyPageTargets() {
        stubCdpEndpoints(
            "Chrome/151.0.0.0",
            listOf(
                mapOf("type" to "page", "title" to "t", "url" to "about:blank"),
                mapOf("type" to "browser_ui", "title" to "Omnibox", "url" to "chrome://omnibox"),
            )
        )
        val result = PulsarSessionManager.verifyCdpEndpoint("http://127.0.0.1:$port")
        assertEquals(1, result.pageTargetCount)
    }

    @Test
    @DisplayName("verifyCdpEndpoint reports unreachable endpoint as not reachable")
    fun verifyCdpEndpointReportsUnreachable() {
        val deadPort = runCatching { java.net.ServerSocket(0).use { it.localPort } }.getOrNull() ?: return
        val result = PulsarSessionManager.verifyCdpEndpoint("http://127.0.0.1:$deadPort")
        assertFalse(result.reachable)
        assertEquals(0, result.pageTargetCount)
        assertEquals(null, result.versionStatus)
    }

    @Test
    @DisplayName("verifyCdpEndpoint records the /json/version status when the endpoint answers 404")
    fun verifyCdpEndpointRecordsHttpStatus() {
        server.createContext("/json/version") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val result = PulsarSessionManager.verifyCdpEndpoint("http://127.0.0.1:$port")
        assertFalse(result.reachable)
        assertEquals(404, result.versionStatus)
    }

    @Test
    @DisplayName("describeAttachFailure explains HTTP 404 as Chrome's built-in remote debugging")
    fun describeAttachFailureExplainsBuiltInRemoteDebugging() {
        server.createContext("/json/version") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val endpoint = "http://127.0.0.1:$port"
        val result = PulsarSessionManager.verifyCdpEndpoint(endpoint)
        val failure = PulsarSessionManager.describeAttachFailure(endpoint, result)
            ?: error("an unreachable endpoint must produce a failure message")

        assertTrue(failure.contains("is not reachable"), failure)
        assertTrue(failure.contains("HTTP 404"), failure)
        assertTrue(failure.contains("chrome://inspect/#remote-debugging"), failure)
        assertTrue(failure.contains("attach --extension"), failure)
    }

    @Test
    @DisplayName("describeAttachFailure keeps page-target guidance and adds the extension fallback")
    fun describeAttachFailureGuidesOnPageLessEndpoint() {
        stubCdpEndpoints("Chrome/151.0.0.0", emptyList())
        val endpoint = "http://127.0.0.1:$port"
        val result = PulsarSessionManager.verifyCdpEndpoint(endpoint)
        assertEquals(0, result.pageTargetCount)

        val failure = PulsarSessionManager.describeAttachFailure(endpoint, result)
            ?: error("a page-less endpoint must produce a failure message")
        assertTrue(failure.contains("has no page targets"), failure)
        assertTrue(failure.contains("Open a tab in the target browser"), failure)
        assertTrue(failure.contains("attach --extension"), failure)
    }

    @Test
    @DisplayName("describeAttachFailure accepts an endpoint that lists a page target")
    fun describeAttachFailureAcceptsAttachableEndpoint() {
        stubCdpEndpoints(
            "Chrome/151.0.0.0",
            listOf(mapOf("type" to "page", "title" to "t", "url" to "about:blank"))
        )
        val endpoint = "http://127.0.0.1:$port"
        val result = PulsarSessionManager.verifyCdpEndpoint(endpoint)
        assertEquals(200, result.versionStatus)
        assertEquals(null, PulsarSessionManager.describeAttachFailure(endpoint, result))
    }

    @Test
    @DisplayName("verifyCdpEndpoint reports zero page targets when /json is missing")
    fun verifyCdpEndpointReportsZeroPagesWhenJsonMissing() {
        server.createContext("/json/version") { exchange ->
            val body = """{"Browser":"Chrome/151.0.0.0"}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        val result = PulsarSessionManager.verifyCdpEndpoint("http://127.0.0.1:$port")
        assertTrue(result.reachable)
        assertEquals(0, result.pageTargetCount)
    }

    @Test
    @DisplayName("browserLevelWebSocketUrl accepts browser sockets and leaves page sockets alone")
    fun browserLevelWebSocketUrlClassifiesEndpoints() {
        val browserSocket = "ws://127.0.0.1:9222/devtools/browser/8b91cacf-d8aa-4fa3-8b45-687c7de7af8a"
        assertEquals(browserSocket, PulsarSessionManager.browserLevelWebSocketUrl(browserSocket))
        assertEquals(
            "wss://cloud.example.com/devtools/browser/x",
            PulsarSessionManager.browserLevelWebSocketUrl("  wss://cloud.example.com/devtools/browser/x ")
        )

        // A page socket carries one tab and cannot answer Target.getTargets, so
        // it keeps going through the HTTP-discovery path.
        assertEquals(null, PulsarSessionManager.browserLevelWebSocketUrl("ws://127.0.0.1:9222/devtools/page/ABC"))
        assertEquals(null, PulsarSessionManager.browserLevelWebSocketUrl("http://127.0.0.1:9222"))
        assertEquals(null, PulsarSessionManager.browserLevelWebSocketUrl("127.0.0.1:9222"))
        assertEquals(null, PulsarSessionManager.browserLevelWebSocketUrl(""))
    }

    @Test
    @DisplayName("normalizeCdpEndpoint handles host:port and ws forms")
    fun normalizeCdpEndpointHandlesForms() {
        assertEquals("http://localhost:9222", PulsarSessionManager.normalizeCdpEndpoint("http://localhost:9222", 9222))
        assertEquals("http://127.0.0.1:38653", PulsarSessionManager.normalizeCdpEndpoint("127.0.0.1:38653", 38653))
        assertEquals(
            "http://localhost:9222",
            PulsarSessionManager.normalizeCdpEndpoint("ws://localhost:9222/devtools/browser/x", 9222)
        )
        assertEquals("http://example.com:9222", PulsarSessionManager.normalizeCdpEndpoint("http://example.com", 9222))
    }
}
