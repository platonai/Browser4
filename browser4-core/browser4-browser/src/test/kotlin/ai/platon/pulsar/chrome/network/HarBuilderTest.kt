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
        // CDP `ResourceTiming` offsets are MILLISECONDS relative to `requestTime`
        // (which is seconds since epoch), and HAR 1.2 wants milliseconds too — so the
        // phases below are written in ms and must come back unchanged.  The fixture
        // used to describe sub-millisecond offsets while the builder multiplied by
        // 1000, so the test pinned a ~1000x inflation: a 2 ms DNS lookup was reported
        // as 2 s and the phases did not add up to the entry's own `time`.
        val timing = ResourceTiming(
            requestTime = 10.0,
            proxyStart = 0.0, proxyEnd = 0.0,
            dnsStart = 10.0, dnsEnd = 12.0,
            connectStart = 12.0, connectEnd = 15.0,
            sslStart = 12.5, sslEnd = 14.5,
            workerStart = 0.0, workerReady = 0.0, workerFetchStart = 0.0, workerRespondWithSettled = 0.0,
            sendStart = 15.0, sendEnd = 16.0,
            pushStart = 0.0, pushEnd = 0.0,
            receiveHeadersEnd = 40.0,
        )
        val entry = harOf(tracked(timing = timing))
        // total = (10.5 - 10.0) * 1000
        assertEquals(500L, entry["time"])
        val timings = entry["timings"] as Map<String, Any?>
        assertEquals(2L, timings["dns"], "a 2 ms lookup must be reported as 2 ms")
        assertEquals(3L, timings["connect"])
        assertEquals(2L, timings["ssl"])
        assertEquals(1L, timings["send"])
        assertEquals(24L, timings["wait"])
        // receive = total - known (500 - 32)
        assertEquals(468L, timings["receive"])
        // The phases a HAR consumer adds up must fit inside the total the same
        // document reports; the 1000x inflation is what broke that invariant.
        val phases = listOf("dns", "connect", "ssl", "send", "wait", "receive")
            .sumOf { (timings[it] as? Number)?.toLong() ?: 0L }
        assertEquals(entry["time"] as Long, phases, "timings must add up to the entry time")
    }

    @Test
    @DisplayName("a timing phase CDP did not report is -1, never a coerced measurement")
    fun unreportedTimingPhaseIsMinusOne() {
        // Only DNS was reported: every other phase is "not available" in HAR, and
        // requestTime is absent so the offsets cannot be interpreted at all.
        val timing = ResourceTiming(
            requestTime = 0.0,
            proxyStart = 0.0, proxyEnd = 0.0,
            dnsStart = 0.0, dnsEnd = 0.0,
            connectStart = -1.0, connectEnd = -1.0,
            sslStart = -1.0, sslEnd = -1.0,
            workerStart = 0.0, workerReady = 0.0, workerFetchStart = 0.0, workerRespondWithSettled = 0.0,
            sendStart = 0.0, sendEnd = 0.0,
            pushStart = 0.0, pushEnd = 0.0,
            receiveHeadersEnd = -1.0,
        )
        val timings = harOf(tracked(timing = timing))["timings"] as Map<String, Any?>

        assertEquals(-1L, timings["dns"])
        assertEquals(-1L, timings["connect"])
        assertEquals(-1L, timings["send"])
        assertEquals(-1L, timings["wait"])
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
        assertEquals("base64", content["encoding"])
        assertEquals(5L, content["size"])
    }

    @Test
    @DisplayName("text bodies are not marked base64-encoded")
    fun textBodiesHaveNoEncoding() {
        val entry = harOf(tracked(responseBody = """{"ok":true}"""), HarContentMode.TEXT)
        val content = (entry["response"] as Map<String, Any?>)["content"] as Map<String, Any?>
        assertEquals("""{"ok":true}""", content["text"])
        assertNull(content["encoding"])
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
