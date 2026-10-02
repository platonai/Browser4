package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.test.TestUrls
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A capture must not change how the driver loads the pages that follow it.
 *
 * `capture` is the one load that wants `AbstractWebDriver.ignoreDOMFeatures`: it tells the fetch
 * pipeline to skip the driver-side DOM feature computation (`EmulateEvents.willComputeFeature`) and
 * the HTML integrity check for the load it is building, because the capture serializes the live
 * document itself.  The flag lives on the **driver**, so it has to be scoped to that load.  It used
 * to be set and never restored, and every later navigation on the same driver silently skipped
 * `computeDocumentFeatures` as well:
 *
 *  * the pages loaded afterwards carried no DOM features, so `LoadComponent`'s `-requireImages` /
 *    `-requireAnchors` checks read 0 images and 0 anchors for them (see the `activeDOMStatTrace`
 *    lookups there), and
 *  * `WebPageInfoFormatters` lost the js-state it reports.
 *
 * The assertions are deliberately two-sided:
 *
 *  1. a plain load computes and records the page's DOM features at all — the observable this test
 *     rests on, asserted first so a fixture change cannot masquerade as a leak;
 *  2. a load that follows a capture still computes them — the regression — with the driver flag
 *     itself asserted as restored in between.
 *
 * Verified as a negative control: removing the flag-restoring `finally` in
 * `InteractiveBrowserEmulator.browseWithWebDriver` (and reinstalling browser4-protocol) makes **both**
 * detectors fail — the driver keeps `ignoreDOMFeatures = true` after the capture, and the pipeline
 * load that follows arrives with an empty trace — while the control in step 1 keeps passing.
 *
 * Tagged [E2ETest]: needs a real Chrome and the mock site.
 */
@Tag("E2ETest")
class CaptureIgnoreDomFeaturesE2ETest : RestAPITestBase() {

    @Autowired
    lateinit var sessionManager: PulsarSessionManager

    private val objectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)

    private val createdSessions = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        try {
            createdSessions.forEach { sessionId ->
                try {
                    callTool("close_session", mapOf("sessionId" to sessionId))
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            // best-effort cleanup
        }
        createdSessions.clear()
    }

    @Test
    @DisplayName("a capture leaves the driver's DOM feature computation alone")
    fun captureDoesNotDisableFeatureComputationForLaterLoads() {
        val sessionId = openTemporarySession()
        val session = requireSession(sessionId)

        // 1. The control: a *pipeline* load records the features its load computed.  Features are a
        //    property of the fetch pipeline (`EmulateEvents.willComputeFeature`), not of a bare
        //    `driver.navigate` — the MCP `browser_navigate` never computes them — so the observable
        //    has to be produced by `session.load`, and this half is asserted first: an empty trace
        //    here means the fixture changed, not that the capture leaked.
        val controlUrl = fresh(TestUrls.MOCK_NEWS_URL)
        val control = runBlocking { session.load(controlUrl) }
        assertTrue(
            control.activeDOMStatTrace.isNotEmpty(),
            "a pipeline load must compute the page's DOM features, got an empty trace for ${control.url}",
        )
        assertFalse(
            driverOf(sessionId).ignoreDOMFeatures,
            "a plain load must not need ignoreDOMFeatures",
        )

        // 2. Put a real page back in the tab (the pipeline load above retires the driver it used) and
        //    run the capture — the path that used to leave the flag set on the driver.
        navigate(sessionId, fresh(TestUrls.MOCK_PRODUCT_DETAIL_URL))
        assertNotError(callTool("html_snapshot_capture", mapOf("sessionId" to sessionId)))
        assertFalse(
            driverOf(sessionId).ignoreDOMFeatures,
            "capture must restore the driver's flag: it is scoped to the capture's own load",
        )

        // 3. The regression: the next pipeline load still computes its features.
        val afterUrl = fresh(TestUrls.MOCK_JOBS_URL)
        val afterCapture = runBlocking { session.load(afterUrl) }
        assertTrue(
            afterCapture.activeDOMStatTrace.isNotEmpty(),
            "a load after a capture must still compute the page's DOM features, " +
                "got an empty trace for ${afterCapture.url}",
        )
        assertFalse(
            driverOf(sessionId).ignoreDOMFeatures,
            "the flag must not become sticky after a capture",
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun requireSession(sessionId: String): AgenticSession =
        requireNotNull(sessionManager.getOrRecoverSession(sessionId)) {
            "the session $sessionId must exist while the test owns it"
        }.agenticSession

    private fun driverOf(sessionId: String): AbstractWebDriver {
        val managed = requireNotNull(sessionManager.getOrRecoverSession(sessionId)) {
            "the session $sessionId must exist while the test owns it"
        }
        val driver = managed.driver
        assertTrue(driver is AbstractWebDriver, "the browser driver carries the feature flag")
        return driver as AbstractWebDriver
    }

    /**
     * [url] with a unique probe parameter: the store must not answer this navigation from an earlier
     * run of the suite, or the feature computation this test observes would never happen.
     */
    private fun fresh(url: String): String =
        "$url${if (url.contains('?')) '&' else '?'}b4probe=${System.nanoTime()}"

    private fun callTool(tool: String, arguments: Map<String, Any?> = emptyMap()): MCPToolCallResponse {
        val request = mapOf("tool" to tool, "arguments" to arguments)
        val body = client.post().uri("/mcp/call-tool")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody!!

        val tree = objectMapper.readTree(body)
        if (tree is ObjectNode && (tree.get("isError") == null || tree.get("isError").isNull)) {
            tree.put("isError", false)
        }
        return objectMapper.treeToValue(tree, MCPToolCallResponse::class.java)
    }

    private fun textContent(response: MCPToolCallResponse): String =
        response.content.firstOrNull()?.text.orEmpty()

    private fun assertNotError(response: MCPToolCallResponse) {
        assertFalse(
            response.isError,
            "Expected a successful MCP response but got: ${textContent(response)}",
        )
    }

    private fun openTemporarySession(): String {
        val response = callTool(
            "open_session",
            mapOf(
                "sessionId" to "capture-ignore-dom-features",
                "profileMode" to "SEQUENTIAL",
                "interactLevel" to "FASTEST",
            ),
        )
        assertNotError(response)
        val sessionId = objectMapper.readTree(textContent(response)).path("sessionId").asText()
        assertTrue(sessionId.isNotBlank(), "open_session must return a non-blank sessionId")
        createdSessions.add(sessionId)
        return sessionId
    }

    private fun navigate(sessionId: String, url: String) {
        assertNotError(
            callTool("browser_navigate", mapOf("sessionId" to sessionId, "url" to url)),
        )
    }
}
