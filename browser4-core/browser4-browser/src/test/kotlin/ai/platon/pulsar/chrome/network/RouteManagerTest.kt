package ai.platon.pulsar.chrome.network

import ai.platon.cdt.kt.protocol.events.fetch.RequestPaused
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.cdt.kt.protocol.types.network.ErrorReason
import ai.platon.cdt.kt.protocol.types.network.Request
import ai.platon.cdt.kt.protocol.types.network.RequestReferrerPolicy
import ai.platon.cdt.kt.protocol.types.network.ResourcePriority
import ai.platon.cdt.kt.protocol.types.network.ResourceType
import ai.platon.pulsar.api.BrowserProtocol
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

@DisplayName("RouteManager")
class RouteManagerTest {

    private fun newManager(): Pair<RouteManager, BrowserProtocol> {
        val protocol = mock<BrowserProtocol>()
        whenever(protocol.onRequestPaused(any())).thenReturn(mock<EventListener>())
        wheneverBlocking { protocol.fetchEnable(any(), any()) }.thenReturn(Unit)
        wheneverBlocking { protocol.failRequest(any(), any()) }.thenReturn(Unit)
        wheneverBlocking { protocol.fulfillRequest(any(), any(), any(), any(), any(), any()) }.thenReturn(Unit)
        wheneverBlocking { protocol.continueRequest(any(), isNull(), isNull(), isNull(), isNull()) }.thenReturn(Unit)
        val manager = RouteManager(protocol)
        return manager to protocol
    }

    private fun pausedRequest(
        requestId: String,
        url: String,
        resourceType: ResourceType = ResourceType.XHR,
    ) = RequestPaused(
        requestId = requestId,
        request = Request(
            url = url,
            urlFragment = null,
            method = "GET",
            headers = emptyMap(),
            postData = null,
            hasPostData = null,
            postDataEntries = null,
            mixedContentType = null,
            initialPriority = ResourcePriority.MEDIUM,
            referrerPolicy = RequestReferrerPolicy.NO_REFERRER_WHEN_DOWNGRADE,
            trustTokenParams = null,
        ),
        frameId = "frame-1",
        resourceType = resourceType,
        responseErrorReason = null,
        responseStatusCode = null,
        responseHeaders = null,
        networkId = null,
    )

    @Test
    @DisplayName("plain patterns match by URL substring")
    fun plainPatternIsSubstringMatch() {
        val (manager, _) = newManager()
        assertTrue(manager.routeUrlMatches("api/users", "https://example.com/api/users?page=1"))
        assertTrue(manager.routeUrlMatches("example.com", "https://example.com/"))
        assertFalse(manager.routeUrlMatches("api/users", "https://example.com/other"))
    }

    @Test
    @DisplayName("star matches everything")
    fun starMatchesEverything() {
        val (manager, _) = newManager()
        assertTrue(manager.routeUrlMatches("*", "https://anything.example/x"))
        assertTrue(manager.routeUrlMatches("*", "data:text/plain,hi"))
    }

    @Test
    @DisplayName("glob patterns anchor start and end unless wildcarded")
    fun globPatternsAnchor() {
        val (manager, _) = newManager()
        // Anchored start: must begin with the first segment.
        assertTrue(manager.routeUrlMatches("https://example.com/api/*", "https://example.com/api/users"))
        assertTrue(manager.routeUrlMatches("https://example.com/api/*", "https://example.com/api/users/1"))
        assertFalse(manager.routeUrlMatches("https://example.com/api/*", "https://other.com/api/users"))
        // Anchored end: must end with the last segment.
        assertTrue(manager.routeUrlMatches("*/api/users", "https://example.com/api/users"))
        assertFalse(manager.routeUrlMatches("*/api/users", "https://example.com/api/users/extra"))
        // Consecutive stars collapse.
        assertTrue(manager.routeUrlMatches("**/api**", "https://example.com/x/api/users"))
        assertTrue(manager.routeUrlMatches("**/api**", "https://example.com/api"))
        assertFalse(manager.routeUrlMatches("**/api**", "https://example.com/other"))
    }

    @Test
    @DisplayName("route registers a Fetch pattern and unroute all disables Fetch")
    fun routeAndUnrouteControlFetch() = runBlocking {
        val (manager, protocol) = newManager()
        manager.route(
            "**/api/users",
            response = RouteManager.RouteResponse(body = """{"users":[]}""", contentType = "application/json"),
        )
        manager.route("*", abort = true, resourceTypes = listOf("script"))

        val captor = argumentCaptor<List<ai.platon.cdt.kt.protocol.types.fetch.RequestPattern>>()
        verify(protocol, times(2)).fetchEnable(captor.capture(), eq(false))
        assertEquals(listOf("*/api/users"), captor.allValues[0].map { it.urlPattern })
        assertEquals(listOf("*/api/users", "*"), captor.allValues[1].map { it.urlPattern })
        assertEquals(2, manager.routeList().size)

        manager.unroute("**/api/users")
        verify(protocol, times(3)).fetchEnable(any(), any())
        assertEquals(1, manager.routeList().size)

        manager.unroute()
        verify(protocol).executeCdpCommand(eq("Fetch.disable"), eq(null))
        assertFalse(manager.isFetchEnabled)
        assertTrue(manager.routeList().isEmpty())
    }

    @Test
    @DisplayName("abort routes fail the paused request")
    fun abortFailsPausedRequest() = runBlocking {
        val (manager, protocol) = newManager()
        manager.route("**/blocked*", abort = true)

        manager.onRequestPaused(pausedRequest("1", "https://example.com/blocked.js", ResourceType.SCRIPT))
        verify(protocol).failRequest(eq("1"), eq(ErrorReason.FAILED))
        verify(protocol, never()).continueRequest(any(), isNull(), isNull(), isNull(), isNull())
    }

    @Test
    @DisplayName("body routes fulfill with base64 body and content type")
    fun bodyRoutesFulfill() = runBlocking {
        val (manager, protocol) = newManager()
        manager.route(
            "**/api/users",
            response = RouteManager.RouteResponse(body = """{"ok":true}""", contentType = "application/json"),
        )

        manager.onRequestPaused(pausedRequest("2", "https://example.com/api/users"))
        val captor = argumentCaptor<String>()
        verify(protocol).fulfillRequest(
            eq("2"),
            eq(200),
            any(),
            isNull(),
            captor.capture(),
            isNull(),
        )
        val expected = java.util.Base64.getEncoder().encodeToString("""{"ok":true}""".toByteArray())
        assertEquals(expected, captor.firstValue)
        verify(protocol, never()).continueRequest(any(), isNull(), isNull(), isNull(), isNull())
    }

    @Test
    @DisplayName("resource type filters gate the route")
    fun resourceTypeFiltersGate() = runBlocking {
        val (manager, protocol) = newManager()
        manager.route("**/api/*", response = RouteManager.RouteResponse(body = "mocked"), resourceTypes = listOf("xhr"))

        // Script paused on a URL that matches the pattern but not the type.
        manager.onRequestPaused(pausedRequest("3", "https://example.com/api/app.js", ResourceType.SCRIPT))
        verify(protocol).continueRequest(eq("3"), isNull(), isNull(), isNull(), isNull())
        verify(protocol, never()).fulfillRequest(any(), any(), any(), isNull(), any(), isNull())

        // XHR matches.
        manager.onRequestPaused(pausedRequest("4", "https://example.com/api/users"))
        verify(protocol).fulfillRequest(eq("4"), eq(200), any(), isNull(), any(), isNull())
    }

    @Test
    @DisplayName("unmatched paused requests continue unchanged")
    fun unmatchedRequestsContinue() = runBlocking {
        val (manager, protocol) = newManager()
        manager.route("**/api/*", response = RouteManager.RouteResponse(body = "mocked"))

        manager.onRequestPaused(pausedRequest("5", "https://example.com/other"))
        verify(protocol).continueRequest(eq("5"), isNull(), isNull(), isNull(), isNull())
    }
}
