package ai.platon.pulsar.chrome.network

import ai.platon.cdt.kt.protocol.types.network.ResourceTiming
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HarBuilder")
class HarBuilderTest {

    private fun tracked(
        requestId: String = "1",
        url: String = "https://example.com/api/users?page=1&q=a+b",
        method: String = "GET",
        resourceType: String = "XHR",
        timestamp: Long = 1_700_000_000_000L,
        timestampMonotonic: Double = 10.0,
        finishedTimestampMonotonic: Double? = 10.5,
        status: Int? = 200,
        statusText: String? = "OK",
        mimeType: String? = "application/json",
        requestHeaders: Map<String, Any?> = mapOf("Accept" to "application/json"),
        responseHeaders: Map<String, Any?> = mapOf("Content-Type" to "application/json"),
        postData: String? = null,
        errorText: String? = null,
        protocol: String? = "http/1.1",
        responseBody: String? = null,
        responseBodyBase64: Boolean = false,
        responseBodyBytes: Long? = null,
        timing: ResourceTiming? = null,
    ) = TrackedNetworkRequest(
        requestId = requestId,
        url = url,
        method = method,
        resourceType = resourceType,
        timestamp = timestamp,
        timestampMonotonic = timestampMonotonic,
        finishedTimestampMonotonic = finishedTimestampMonotonic,
        status = status,
        statusText = statusText,
        mimeType = mimeType,
        requestHeaders = requestHeaders,
        responseHeaders = responseHeaders,
        postData = postData,
        errorText = errorText,
        protocol = protocol,
        responseBody = responseBody,
        responseBodyBase64 = responseBodyBase64,
        responseBodyBytes = responseBodyBytes ?: run {
            val bytes = responseBody?.let {
                if (responseBodyBase64) java.util.Base64.getDecoder().decode(it)
                else it.toByteArray(Charsets.UTF_8)
            }
            bytes?.size?.toLong()
        },
        timing = timing,
        finished = finishedTimestampMonotonic != null,
    )

    @Suppress("UNCHECKED_CAST")
    private fun harOf(request: TrackedNetworkRequest, mode: HarContentMode = HarContentMode.NONE): Map<String, Any?> {
        val har = HarBuilder.build(listOf(request), mode)
        return ((har["log"] as Map<String, Any?>)["entries"] as List<Map<String, Any?>>).first()
    }

    @Test
    @DisplayName("builds a valid HAR 1.2 document skeleton")
    fun buildsHarSkeleton() {
        val har = HarBuilder.build(listOf(tracked()), HarContentMode.NONE)
        val log = har["log"] as Map<String, Any?>
        assertEquals("1.2", log["version"])
        assertEquals("Browser4", (log["creator"] as Map<String, Any?>)["name"])
        val pages = log["pages"] as List<Map<String, Any?>>
        assertEquals(1, pages.size)
        assertEquals("page_1", pages.first()["id"])
        val entries = log["entries"] as List<Map<String, Any?>>
        assertEquals(1, entries.size)
    }

    @Test
    @DisplayName("embeds browser metadata into log.browser when provided")
    fun embedsBrowserMetadata() {
        val browser = mapOf("name" to "Chrome", "version" to "151.0.0.0", "userAgent" to "Mozilla/5.0")
        val har = HarBuilder.build(listOf(tracked()), HarContentMode.NONE, browser = browser)
        val log = har["log"] as Map<String, Any?>
        assertEquals(browser, log["browser"])

        val without = HarBuilder.build(listOf(tracked()), HarContentMode.NONE)
        assertNull(((without["log"] as Map<String, Any?>).get("browser")))
    }

    @Test
    @DisplayName("maps request metadata into the HAR entry")
    fun mapsRequestMetadata() {
        val entry = harOf(tracked(postData = "a=1"))
        val request = entry["request"] as Map<String, Any?>
        assertEquals("GET", request["method"])
        assertEquals("https://example.com/api/users?page=1&q=a+b", request["url"])
        assertEquals("HTTP/1.1", request["httpVersion"])
        assertEquals(listOf(mapOf("name" to "Accept", "value" to "application/json")), request["headers"])
        assertEquals(
            listOf(mapOf("name" to "page", "value" to "1"), mapOf("name" to "q", "value" to "a b")),
            request["queryString"]
        )
        assertEquals("a=1", (request["postData"] as Map<String, Any?>)["text"])
        assertEquals("2023-11-14T22:13:20.000Z", entry["startedDateTime"])
    }

    @Test
    @DisplayName("maps response metadata into the HAR entry")
    fun mapsResponseMetadata() {
        val entry = harOf(tracked())
        val response = entry["response"] as Map<String, Any?>
        assertEquals(200, response["status"])
        assertEquals("OK", response["statusText"])
        assertEquals("HTTP/1.1", response["httpVersion"])
        assertEquals(
            listOf(mapOf("name" to "Content-Type", "value" to "application/json")),
            response["headers"]
        )
        assertEquals("application/json", (response["content"] as Map<String, Any?>)["mimeType"])
        assertNull((response["content"] as Map<String, Any?>)["text"])
    }

    @Test
    @DisplayName("computes entry time and timings from monotonic timestamps and ResourceTiming")
    fun computesTimeAndTimings() {
        val timing = ResourceTiming(
            requestTime = 10.0,
            proxyStart = 0.0, proxyEnd = 0.0,
            dnsStart = 10.0, dnsEnd = 10.05,
            connectStart = 10.05, connectEnd = 10.15,
            sslStart = 10.06, sslEnd = 10.10,
            workerStart = 0.0, workerReady = 0.0, workerFetchStart = 0.0, workerRespondWithSettled = 0.0,
            sendStart = 10.15, sendEnd = 10.20,
            pushStart = 0.0, pushEnd = 0.0,
            receiveHeadersEnd = 10.40,
        )
        val entry = harOf(tracked(timing = timing))
        // total = (10.5 - 10.0) * 1000
        assertEquals(500L, entry["time"])
        val timings = entry["timings"] as Map<String, Any?>
        assertEquals(50L, timings["dns"])
        assertEquals(100L, timings["connect"])
        assertEquals(40L, timings["ssl"])
        assertEquals(50L, timings["send"])
        assertEquals(200L, timings["wait"])
        // receive = total - known
        assertEquals(60L, timings["receive"])
    }

    @Test
    @DisplayName("unfinished requests have time -1")
    fun unfinishedRequestHasNegativeTime() {
        val entry = harOf(tracked(finishedTimestampMonotonic = null, status = null))
        assertEquals(-1L, entry["time"])
    }

    @Test
    @DisplayName("failed requests carry the error text")
    fun failedRequestCarriesError() {
        val entry = harOf(tracked(errorText = "net::ERR_CONNECTION_RESET"))
        assertEquals("net::ERR_CONNECTION_RESET", entry["_error"])
    }

    @Test
    @DisplayName("none mode never embeds response bodies")
    fun noneModeOmitsBodies() {
        val entry = harOf(tracked(responseBody = """{"ok":true}"""), HarContentMode.NONE)
        assertNull((entry["response"] as Map<String, Any?>)["content"]?.let { (it as Map<String, Any?>)["text"] })
    }

    @Test
    @DisplayName("text mode embeds text-like bodies only")
    fun textModeEmbedsTextLikeBodies() {
        val json = harOf(tracked(responseBody = """{"ok":true}"""), HarContentMode.TEXT)
        assertEquals("""{"ok":true}""", ((json["response"] as Map<String, Any?>)["content"] as Map<String, Any?>)["text"])

        val binary = harOf(tracked(mimeType = "image/png", responseBody = "aGVsbG8=", responseBodyBase64 = true), HarContentMode.TEXT)
        assertNull(((binary["response"] as Map<String, Any?>)["content"] as Map<String, Any?>).get("text"))
    }

    @Test
    @DisplayName("all mode embeds binary bodies base64-encoded")
    fun allModeEmbedsBinaryBodies() {
        val entry = harOf(
            tracked(mimeType = "image/png", responseBody = "aGVsbG8=", responseBodyBase64 = true),
            HarContentMode.ALL
        )
        val content = (entry["response"] as Map<String, Any?>)["content"] as Map<String, Any?>
        assertEquals("aGVsbG8=", content["text"])
        assertEquals(5L, content["size"])
    }

    @Test
    @DisplayName("oversized bodies keep metadata but no text")
    fun oversizedBodiesKeepMetadataOnly() {
        val big = "x".repeat(HarBuilder.MAX_BODY_BYTES + 1)
        val entry = harOf(tracked(responseBody = big), HarContentMode.ALL)
        val content = (entry["response"] as Map<String, Any?>)["content"] as Map<String, Any?>
        assertNull(content["text"])
        assertEquals(big.toByteArray().size.toLong(), content["size"])
    }

    @Test
    @DisplayName("total body budget is enforced across entries")
    fun totalBodyBudgetIsEnforced() {
        // Two bodies that fit individually but exceed the total budget together.
        val first = tracked(requestId = "1", responseBody = "aa")
        val second = tracked(requestId = "2", responseBody = "bb")
        val har = HarBuilder.build(listOf(first, second), HarContentMode.ALL, maxTotalBodyBytes = 3)
        val entries = (har["log"] as Map<String, Any?>)["entries"] as List<Map<String, Any?>>
        val contents = entries.map { ((it["response"] as Map<String, Any?>)["content"] as Map<String, Any?>) }
        assertTrue(contents[0].containsKey("text"), "first body should be embedded")
        assertFalse(contents[1].containsKey("text"), "second body should exceed the remaining budget")
        // Metadata (size) is kept even for the skipped body.
        assertEquals(2L, contents[1]["size"])
    }

    @Test
    @DisplayName("parses request Cookie and response Set-Cookie headers")
    fun parsesCookies() {
        val request = tracked(requestHeaders = mapOf("Cookie" to "session=abc; theme=dark"))
        val response = tracked(responseHeaders = mapOf("Set-Cookie" to "sid=xyz; Path=/; HttpOnly"))
        val requestEntry = harOf(request)
        val responseEntry = harOf(response)
        assertEquals(
            listOf(mapOf("name" to "session", "value" to "abc"), mapOf("name" to "theme", "value" to "dark")),
            (requestEntry["request"] as Map<String, Any?>)["cookies"]
        )
        assertEquals(
            listOf(mapOf("name" to "sid", "value" to "xyz")),
            (responseEntry["response"] as Map<String, Any?>)["cookies"]
        )
    }

    @Test
    @DisplayName("parses content mode strings")
    fun parsesContentModes() {
        assertEquals(HarContentMode.NONE, HarContentMode.parse("none"))
        assertEquals(HarContentMode.TEXT, HarContentMode.parse("Text"))
        assertEquals(HarContentMode.ALL, HarContentMode.parse("ALL"))
        assertTrue(runCatching { HarContentMode.parse("huge") }.isFailure)
    }
}
