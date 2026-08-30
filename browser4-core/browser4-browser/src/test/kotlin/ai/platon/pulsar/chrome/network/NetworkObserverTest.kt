package ai.platon.pulsar.chrome.network

import ai.platon.cdt.kt.protocol.events.network.LoadingFailed
import ai.platon.cdt.kt.protocol.events.network.LoadingFinished
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSent
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSentExtraInfo
import ai.platon.cdt.kt.protocol.events.network.ResponseReceived
import ai.platon.cdt.kt.protocol.events.network.ResponseReceivedExtraInfo
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.cdt.kt.protocol.types.network.IPAddressSpace
import ai.platon.cdt.kt.protocol.types.network.Initiator
import ai.platon.cdt.kt.protocol.types.network.InitiatorType
import ai.platon.cdt.kt.protocol.types.network.Request
import ai.platon.cdt.kt.protocol.types.network.RequestReferrerPolicy
import ai.platon.cdt.kt.protocol.types.network.ResourcePriority
import ai.platon.cdt.kt.protocol.types.network.ResourceType
import ai.platon.cdt.kt.protocol.types.network.Response
import ai.platon.cdt.kt.protocol.types.security.SecurityState
import ai.platon.pulsar.api.BrowserProtocol
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

@DisplayName("NetworkObserver")
class NetworkObserverTest {

    private fun newObserver(): Pair<NetworkObserver, BrowserProtocol> {
        val protocol = mock<BrowserProtocol>()
        stubListenerRegistration(protocol)
        val observer = NetworkObserver(protocol)
        return observer to protocol
    }

    /** The typed on* listener methods return an EventListener on a mock. */
    private fun stubListenerRegistration(protocol: BrowserProtocol) {
        val listener = mock<EventListener>()
        whenever(protocol.onRequestWillBeSent(any())).thenReturn(listener)
        whenever(protocol.onRequestWillBeSentExtraInfo(any())).thenReturn(listener)
        whenever(protocol.onResponseReceived(any())).thenReturn(listener)
        whenever(protocol.onResponseReceivedExtraInfo(any())).thenReturn(listener)
        whenever(protocol.onLoadingFinished(any())).thenReturn(listener)
        whenever(protocol.onLoadingFailed(any())).thenReturn(listener)
    }

    /** Stub Network.getResponseBody to return the given body. */
    private fun stubResponseBody(protocol: BrowserProtocol, body: String, base64: Boolean = false) {
        wheneverBlocking {
            protocol.executeCdpCommand(eq("Network.getResponseBody"), any())
        }.thenReturn(mapOf("body" to body, "base64Encoded" to base64))
    }

    private fun requestWillBeSent(
        requestId: String,
        url: String = "https://example.com/api/users?page=1",
        method: String = "GET",
        type: ResourceType = ResourceType.XHR,
        timestamp: Double = 10.0,
        wallTime: Double = 1_700_000_000.0,
        postData: String? = null,
        headers: Map<String, Any?> = mapOf("Accept" to "application/json"),
    ) = RequestWillBeSent(
        requestId = requestId,
        loaderId = "loader-1",
        documentURL = "https://example.com/",
        request = Request(
            url = url,
            urlFragment = null,
            method = method,
            headers = headers,
            postData = postData,
            hasPostData = if (postData != null) true else null,
            postDataEntries = null,
            mixedContentType = null,
            initialPriority = ResourcePriority.MEDIUM,
            referrerPolicy = RequestReferrerPolicy.NO_REFERRER_WHEN_DOWNGRADE,
            trustTokenParams = null,
        ),
        timestamp = timestamp,
        wallTime = wallTime,
        initiator = Initiator(InitiatorType.OTHER, null, null, null, null, null),
        redirectResponse = null,
        type = type,
        frameId = "frame-1",
        hasUserGesture = null,
    )

    private fun responseReceived(
        requestId: String,
        timestamp: Double = 10.2,
        status: Int = 200,
        mimeType: String = "application/json",
    ) = ResponseReceived(
        requestId = requestId,
        loaderId = "loader-1",
        timestamp = timestamp,
        type = ResourceType.XHR,
        response = Response(
            url = "https://example.com/api/users?page=1",
            status = status,
            statusText = "OK",
            headers = mapOf("Content-Type" to mimeType),
            headersText = null,
            mimeType = mimeType,
            requestHeaders = null,
            requestHeadersText = null,
            connectionReused = false,
            connectionId = 1.0,
            remoteIPAddress = "93.184.216.34",
            remotePort = 443,
            fromDiskCache = null,
            fromServiceWorker = null,
            fromPrefetchCache = null,
            encodedDataLength = 512.0,
            timing = null,
            serviceWorkerResponseSource = null,
            responseTime = null,
            cacheStorageCacheName = null,
            protocol = "http/1.1",
            securityState = SecurityState.UNKNOWN,
            securityDetails = null,
        ),
        frameId = "frame-1",
    )

    private fun loadingFinished(requestId: String, timestamp: Double = 10.5) =
        LoadingFinished(requestId = requestId, timestamp = timestamp, encodedDataLength = 512.0, shouldReportCorbBlocking = null)

    @Test
    @DisplayName("ensureEnabled is idempotent and enables the Network domain once")
    fun ensureEnabledIsIdempotent() {
        val (observer, protocol) = newObserver()
        runBlocking {
            observer.ensureEnabled()
            observer.ensureEnabled()
        }
        runBlocking { verify(protocol).networkEnable() }
    }

    @Test
    @DisplayName("tracks a request through its lifecycle events")
    fun tracksRequestLifecycle() {
        val (observer, _) = newObserver()
        observer.onRequestWillBeSent(requestWillBeSent("1"))
        observer.onRequestWillBeSentExtraInfo(
            RequestWillBeSentExtraInfo(
                requestId = "1",
                associatedCookies = emptyList(),
                headers = mapOf("Accept" to "application/json", "X-Custom" to "yes"),
                clientSecurityState = null,
            )
        )
        observer.onResponseReceived(responseReceived("1"))
        observer.onResponseReceivedExtraInfo(
            ResponseReceivedExtraInfo(
                requestId = "1",
                blockedCookies = emptyList(),
                headers = mapOf("Content-Type" to "application/json", "X-Resp" to "v1"),
                resourceIPAddressSpace = IPAddressSpace.PUBLIC,
                headersText = null,
            )
        )
        runBlocking { observer.onLoadingFinished(loadingFinished("1")) }

        val requests = observer.networkRequests()
        assertEquals(1, requests.size)
        val tracked = requests.first()
        assertEquals("1", tracked.requestId)
        assertEquals("https://example.com/api/users?page=1", tracked.url)
        assertEquals("GET", tracked.method)
        assertEquals("XHR", tracked.resourceType)
        assertEquals("yes", tracked.requestHeaders["X-Custom"])
        assertEquals("v1", tracked.responseHeaders["X-Resp"])
        assertEquals(200, tracked.status)
        assertEquals("application/json", tracked.mimeType)
        assertEquals("93.184.216.34", tracked.remoteIPAddress)
        assertTrue(tracked.finished)
        assertEquals(1_700_000_000_000L, tracked.timestamp)
    }

    @Test
    @DisplayName("loadingFailed marks the request with the error text")
    fun loadingFailedMarksError() {
        val (observer, _) = newObserver()
        observer.onRequestWillBeSent(requestWillBeSent("1"))
        observer.onLoadingFailed(
            LoadingFailed(
                requestId = "1",
                timestamp = 10.3,
                type = ResourceType.XHR,
                errorText = "net::ERR_CONNECTION_RESET",
                canceled = true,
                blockedReason = null,
                corsErrorStatus = null,
            )
        )
        val tracked = observer.networkRequests().first()
        assertTrue(tracked.finished)
        assertEquals("net::ERR_CONNECTION_RESET", tracked.errorText)
        assertEquals(true, tracked.canceled)
    }

    @Test
    @DisplayName("filters by URL substring, resource type, method, and status")
    fun filtersRequests() {
        val (observer, _) = newObserver()
        observer.onRequestWillBeSent(requestWillBeSent("1", url = "https://example.com/api/users", method = "GET", type = ResourceType.XHR))
        observer.onResponseReceived(responseReceived("1", status = 200))
        observer.onRequestWillBeSent(requestWillBeSent("2", url = "https://example.com/api/login", method = "POST", type = ResourceType.FETCH))
        observer.onResponseReceived(responseReceived("2", status = 400))
        observer.onRequestWillBeSent(requestWillBeSent("3", url = "https://cdn.example.com/app.js", method = "GET", type = ResourceType.SCRIPT))
        observer.onResponseReceived(responseReceived("3", status = 404, mimeType = "application/javascript"))
        runBlocking {
            observer.onLoadingFinished(loadingFinished("1"))
            observer.onLoadingFinished(loadingFinished("2"))
            observer.onLoadingFinished(loadingFinished("3"))
        }

        assertEquals(3, observer.networkRequests().size)
        assertEquals(listOf("1", "2"), observer.networkRequests(filter = "api").map { it.requestId })
        assertEquals(listOf("1", "2"), observer.networkRequests(type = "xhr,fetch").map { it.requestId })
        assertEquals(listOf("2"), observer.networkRequests(method = "post").map { it.requestId })
        assertEquals(listOf("1"), observer.networkRequests(status = "200").map { it.requestId })
        assertEquals(listOf("2", "3"), observer.networkRequests(status = "4xx").map { it.requestId })
        assertEquals(listOf("1", "2", "3"), observer.networkRequests(status = "2xx,4xx").map { it.requestId })
        assertEquals(listOf("1", "2", "3"), observer.networkRequests(status = "200-499").map { it.requestId })
        assertEquals(listOf("3"), observer.networkRequests(type = "script", status = "4xx").map { it.requestId })
        assertEquals(emptyList<String>(), observer.networkRequests(filter = "nomatch").map { it.requestId })
    }

    @Test
    @DisplayName("clear drops all tracked requests")
    fun clearDropsRequests() {
        val (observer, _) = newObserver()
        observer.onRequestWillBeSent(requestWillBeSent("1"))
        observer.onRequestWillBeSent(requestWillBeSent("2"))
        assertEquals(2, observer.networkRequests().size)
        assertTrue(observer.networkRequests(clear = true).isEmpty())
        assertEquals(0, observer.networkRequests().size)
    }

    @Test
    @DisplayName("detail returns the tracked request and fetched body")
    fun detailReturnsRequestAndBody() {
        val protocol = mock<BrowserProtocol>()
        stubListenerRegistration(protocol)
        wheneverBlocking { protocol.networkEnable() }.thenReturn(Unit)
        stubResponseBody(protocol, """{"ok":true}""")
        val observer = NetworkObserver(protocol)

        observer.onRequestWillBeSent(requestWillBeSent("1"))
        observer.onResponseReceived(responseReceived("1"))
        runBlocking { observer.onLoadingFinished(loadingFinished("1")) }

        val detail = runBlocking { observer.networkRequestDetail("1") }
        assertEquals("""{"ok":true}""", detail["responseBody"])
        val request = detail["request"] as TrackedNetworkRequest
        assertEquals("1", request.requestId)
    }

    @Test
    @DisplayName("detail throws for unknown request ids")
    fun detailThrowsForUnknownRequest() {
        val (observer, _) = newObserver()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { observer.networkRequestDetail("nope") }
        }
    }

    @Test
    @DisplayName("har start/stop records and returns a HAR document")
    fun harStartStopReturnsHar() {
        val protocol = mock<BrowserProtocol>()
        stubListenerRegistration(protocol)
        wheneverBlocking { protocol.networkEnable() }.thenReturn(Unit)
        stubResponseBody(protocol, """{"ok":true}""")
        val recording = NetworkObserver(protocol)

        recording.onRequestWillBeSent(requestWillBeSent("1"))
        recording.onResponseReceived(responseReceived("1"))
        runBlocking { recording.onLoadingFinished(loadingFinished("1")) }

        val start = runBlocking { recording.harStart(HarContentMode.TEXT) }
        assertEquals(true, start["recording"])
        assertEquals("text", start["contentMode"])
        assertTrue(recording.isHarRecording)

        // A request finishing while recording gets its body captured.
        recording.onRequestWillBeSent(requestWillBeSent("2", url = "https://example.com/api/login", method = "POST"))
        recording.onResponseReceived(responseReceived("2"))
        runBlocking { recording.onLoadingFinished(loadingFinished("2", timestamp = 11.0)) }

        val stop = runBlocking { recording.harStop() }
        assertEquals(false, stop["recording"])
        assertFalse(recording.isHarRecording)
        assertEquals(2, stop["entries"])
        val har = stop["har"] as Map<String, Any?>
        val entries = (har["log"] as Map<String, Any?>)["entries"] as List<Map<String, Any?>>
        assertEquals(2, entries.size)
        // Entry 2's body was captured (text mode, json mime).
        val body = ((entries.last()["response"] as Map<String, Any?>)["content"] as Map<String, Any?>).get("text")
        assertEquals("""{"ok":true}""", body)
        // Entry 1 finished before recording started — no body expected.
        assertNull(((entries.first()["response"] as Map<String, Any?>)["content"] as Map<String, Any?>).get("text"))
    }

    @Test
    @DisplayName("invalid status filters fail loudly")
    fun invalidStatusFilterFails() {
        val (observer, _) = newObserver()
        assertThrows(IllegalArgumentException::class.java) {
            observer.networkRequests(status = "banana")
        }
    }
}
