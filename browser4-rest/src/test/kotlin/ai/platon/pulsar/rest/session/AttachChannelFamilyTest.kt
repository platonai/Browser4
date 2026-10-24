package ai.platon.pulsar.rest.session

import ai.platon.pulsar.rest.session.PulsarSessionManager.Companion.channelFamily
import ai.platon.pulsar.rest.session.PulsarSessionManager.Companion.channelMismatch
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `attach_browser` with a channel must not bind the wrong browser family.
 *
 * Chrome and Edge share the conventional debugging port (9222), so
 * `--cdp msedge` can resolve a Chrome instance. The backend compares the
 * channel with the product `/json/version` (or `Browser.getVersion`) reports
 * **before** creating the session, the driver or any tab — these tests pin the
 * decision itself.
 */
@DisplayName("attach channel vs actual browser family")
class AttachChannelFamilyTest {

    private val chromeUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/141.0.0.0 Safari/537.36"
    private val edgeUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0"
    private val braveUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/141.0.0.0 Safari/537.36 Brave/1.68.0"

    @Test
    fun channelFamilyRecognisesBothFamiliesAndTheirVariants() {
        for (channel in listOf("msedge", "MsEdge", "msedge-beta", "msedge-dev", "msedge-canary", "edge", "microsoft-edge")) {
            assertEquals("edge", channelFamily(channel), "channel '$channel' must be Edge")
        }
        for (channel in listOf("chrome", "chrome-beta", "chrome-dev", "chrome-canary", "google-chrome")) {
            assertEquals("chrome", channelFamily(channel), "channel '$channel' must be Chrome")
        }
        // A raw endpoint or an unknown name selects no family, so there is
        // nothing to compare — the caller named one concrete browser.
        assertNull(channelFamily("http://localhost:9222"))
        assertNull(channelFamily("ws://127.0.0.1:9222/devtools/browser/x"))
        assertNull(channelFamily("firefox"))
        assertNull(channelFamily(""))
    }

    @Test
    fun anEdgeRequestRefusesTheChromeThatAnswered() {
        val refusal = assertNotNull(
            channelMismatch("msedge", chromeUa, "endpoint http://localhost:9222"),
            "Chrome answering an msedge request must be refused"
        )
        assertTrue(refusal.contains("channel 'msedge'"), refusal)
        assertTrue(refusal.contains("Google Chrome"), refusal)
        assertTrue(refusal.contains("different browser family"), refusal)
        assertTrue(refusal.contains("endpoint http://localhost:9222"), refusal)
        // The fix has to be in the message: name the right channel or pin the endpoint.
        assertTrue(refusal.contains("--cdp msedge") && refusal.contains("exact endpoint"), refusal)
    }

    @Test
    fun anEdgeRequestAcceptsEdge() {
        assertNull(channelMismatch("msedge", edgeUa, "endpoint http://localhost:9222"))
        assertNull(channelMismatch("msedge-dev", edgeUa, "endpoint http://localhost:9222"))
    }

    @Test
    fun aChromeRequestRefusesEdgeButAcceptsChromiumForks() {
        val refusal = assertNotNull(channelMismatch("chrome", edgeUa, "endpoint http://localhost:9222"))
        assertTrue(refusal.contains("channel 'chrome'"), refusal)
        assertTrue(refusal.contains("Microsoft Edge"), refusal)

        // Brave/Opera/Vivaldi report `Chrome/` without an Edge marker; there is
        // no closer channel name, so a chrome request must keep working.
        assertNull(channelMismatch("chrome", braveUa, "endpoint http://localhost:9222"))
    }

    @Test
    fun nothingIsRefusedWhenNothingWasRequestedOrNothingWasObserved() {
        // No channel requested: the endpoint is what the caller named.
        assertNull(channelMismatch(null, chromeUa, "endpoint http://localhost:9222"))
        assertNull(channelMismatch("   ", chromeUa, "endpoint http://localhost:9222"))
        // No identity reported (older endpoint / socket refused getVersion):
        // nothing was observed, so nothing can be contradicted.
        assertNull(channelMismatch("msedge", null, "endpoint http://localhost:9222"))
        assertNull(channelMismatch("msedge", "", "endpoint http://localhost:9222"))
        assertNull(channelMismatch("msedge", "totally-not-a-browser", "endpoint http://localhost:9222"))
    }
}
