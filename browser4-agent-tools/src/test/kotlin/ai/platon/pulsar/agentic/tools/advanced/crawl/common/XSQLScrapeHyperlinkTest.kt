package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.refresh
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Regression tests for issue #577: completed swarm tasks must either contain
 * fetched data or report the real failure reason. Previously the onLoaded
 * handler completed the hyperlink with whatever page it received — including
 * nil/unfetched pages — so a dropped task was reported "done" with an empty
 * result set and pageContentBytes = 0.
 */
class XSQLScrapeHyperlinkTest {

    private val conf = MutableConfig(true).toVolatileConfig()

    private fun hyperlink(): XSQLHyperlink {
        return XSQLHyperlink(
            ScrapeRequest("select dom_text(dom) as t from load_and_select('@url', ':root')"),
            NormXSQL("https://example.com", "", "select dom_text(dom) as t"),
            fakeSession(),
        )
    }

    /**
     * The hyperlink never touches the session for the onLoaded paths under
     * test, so a no-op dynamic proxy is enough.
     */
    private fun fakeSession(): PulsarSession {
        val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Void.TYPE -> null
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            PulsarSession::class.java.classLoader,
            arrayOf(PulsarSession::class.java),
            handler,
        ) as PulsarSession
    }

    private fun fireLoaded(hyperlink: XSQLHyperlink, page: WebPage?) {
        hyperlink.eventHandlers.crawlEventHandlers.onLoaded.invoke(hyperlink, page)
    }

    private fun newPage(url: String = "https://example.com"): GoraWebPage {
        return GoraWebPage.newWebPage(url, conf)
    }

    @Test
    fun `nil page marks the task as failed with a clear reason`() {
        val link = hyperlink()

        fireLoaded(link, GoraWebPage.NIL)

        val response = link.response
        assertTrue(response.isDone, "A nil page must produce a terminal state")
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, response.statusCode)
        assertEquals(0, response.pageContentBytes)
        assertNotNull(response.message)
        assertTrue(
            response.message!!.contains("never fetched"),
            "Message must explain that the page was never fetched: ${response.message}"
        )
    }

    @Test
    fun `null page marks the task as failed with a clear reason`() {
        val link = hyperlink()

        fireLoaded(link, null)

        val response = link.response
        assertTrue(response.isDone, "A null page must produce a terminal state")
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, response.statusCode)
        assertNotNull(response.message)
        assertTrue(response.message!!.contains("never fetched"), "Message: ${response.message}")
    }

    @Test
    fun `failed page marks the task as failed with the page status`() {
        val link = hyperlink()
        val page = newPage()
        page.protocolStatus = ProtocolStatus.failed(ProtocolStatusCodes.SC_NOT_FOUND)
        page.isFetched = true

        fireLoaded(link, page)

        val response = link.response
        assertTrue(response.isDone, "A failed page must produce a terminal state")
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, response.statusCode)
        assertEquals(ProtocolStatusCodes.SC_NOT_FOUND, response.pageStatusCode)
        assertNotNull(response.message)
        assertTrue(response.message!!.contains("failed"), "Message: ${response.message}")
    }

    @Test
    fun `retry page keeps the task in a non-terminal state`() {
        val link = hyperlink()
        val page = newPage()
        page.protocolStatus = ProtocolStatus.retry(RetryScope.CRAWL, "privacy context exhausted")
        page.isFetched = true

        fireLoaded(link, page)

        val response = link.response
        assertFalse(response.isDone, "A retrying task must not be reported as done")
        assertEquals("retry", response.event)
        assertNotNull(response.message)
        assertTrue(response.message!!.contains("retried"), "Message: ${response.message}")
    }

    @Test
    fun `canceled page keeps the task in a non-terminal state`() {
        val link = hyperlink()
        val page = newPage()
        page.protocolStatus = ProtocolStatus.cancel("privacy context recycled")
        page.isCanceled = true

        fireLoaded(link, page)

        val response = link.response
        assertFalse(response.isDone, "A canceled task that is re-queued must not be reported as done")
        assertEquals("retry", response.event)
    }

    @Test
    fun `retry attempt after transient failure can still complete the task`() {
        val link = hyperlink()
        val page = newPage()
        page.protocolStatus = ProtocolStatus.retry(RetryScope.CRAWL, "privacy context exhausted")
        page.isFetched = true

        fireLoaded(link, page)
        assertFalse(link.response.isDone, "Transient failure must not complete the task")

        // Later attempt succeeds — the task completes.
        val successPage = newPage()
        successPage.protocolStatus = ProtocolStatus.STATUS_SUCCESS
        successPage.isFetched = true
        successPage.setByteArrayContent("<html><body><h3>title</h3></body></html>".toByteArray())
        link.response.refresh(ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK, false)

        fireLoaded(link, successPage)

        assertTrue(link.response.isDone, "A later success must complete the task")
    }

    @Test
    fun `success page completes the task`() {
        val link = hyperlink()
        val page = newPage()
        page.protocolStatus = ProtocolStatus.STATUS_SUCCESS
        page.isFetched = true
        page.setByteArrayContent("<html><body>hello</body></html>".toByteArray())

        fireLoaded(link, page)

        assertTrue(link.response.isDone, "A successful fetch must complete the task")
        assertEquals(link.uuid, link.response.id)
    }

    @Test
    fun `cached success page completes the task even without a fresh fetch`() {
        // Regression: a page served from the WebDB cache (load without
        // -refresh) has a success protocol status and content, but isFetched
        // stays false because only FetchComponent sets it on a real network
        // fetch. The onLoaded handler used to fail such tasks with 417
        // "The page was never fetched", breaking repeated swarm queries on
        // already-cached URLs.
        val link = hyperlink()
        // Mirrors the real flow: executeQuery sets SC_OK before the SQL runs.
        link.response.refresh(ResourceStatus.SC_OK, ProtocolStatusCodes.SC_OK, false)

        val page = newPage()
        page.protocolStatus = ProtocolStatus.STATUS_SUCCESS
        page.setByteArrayContent("<html><body><h3>title</h3></body></html>".toByteArray())

        fireLoaded(link, page)

        val response = link.response
        assertTrue(response.isDone, "A cache-served page must complete the task")
        assertEquals(ResourceStatus.SC_OK, response.statusCode)
        assertNull(response.message, "A cache-served page must not carry a failure message")
    }

    @Test
    fun `failed status after a retry attempt marks the task as failed`() {
        val link = hyperlink()
        val retryPage = newPage()
        retryPage.protocolStatus = ProtocolStatus.retry(RetryScope.CRAWL, "privacy context exhausted")
        retryPage.isFetched = true

        fireLoaded(link, retryPage)
        assertFalse(link.response.isDone)

        // The retry budget is exhausted — the runner marks the page as failed.
        val exhaustedPage = newPage()
        exhaustedPage.protocolStatus = ProtocolStatus.failed(ProtocolStatusCodes.SC_REQUEST_TIMEOUT)
        exhaustedPage.isFetched = true

        fireLoaded(link, exhaustedPage)

        assertTrue(link.response.isDone, "A failed final attempt must produce a terminal state")
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, link.response.statusCode)
    }
}
