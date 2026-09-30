package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.refresh
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.RetryScope
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import org.h2.tools.SimpleResultSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.sql.Types
import java.util.concurrent.ArrayBlockingQueue

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

    /**
     * A session with the caches the read-only X-SQL path resolves the page through, so a test can
     * observe what that path froze and where. Pass [sqlContext] to let the statement actually run.
     * */
    private fun sessionWithCaches(sqlContext: AbstractBrowser4SQLContext? = null): PulsarSession {
        val globalCache = GlobalCache(ImmutableConfig())
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            val arguments = args ?: emptyArray()
            when {
                method.name == "toString" -> "fake-session"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "getGlobalCache" -> globalCache
                method.name == "getPageCache" -> globalCache.pageCache
                method.name == "getDocumentCache" -> globalCache.documentCache
                method.name == "getContext" -> sqlContext
                method.name == "options" -> LoadOptions.parse(arguments.firstOrNull() as? String ?: "", conf)
                method.name == "normalize" && arguments.size == 3 && arguments[0] is String ->
                    NormURL(arguments[0] as String, LoadOptions.parse(arguments[1] as? String ?: "", conf))
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == java.lang.Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            PulsarSession::class.java.classLoader,
            arrayOf(PulsarSession::class.java),
            handler,
        ) as PulsarSession
    }

    /**
     * A session whose SQL context can run a statement that returns [resultSet] — the state in which
     * the query succeeds and its outcome is what the response must report.
     * */
    private fun sessionThatRuns(resultSet: SimpleResultSet): PulsarSession {
        val connection = mock<Connection>()
        val statement = mock<Statement>()
        val connections = ArrayBlockingQueue<Connection>(1).apply { add(connection) }
        val sqlContext = mock<AbstractBrowser4SQLContext>()
        whenever(connection.createStatement(any<Int>(), any<Int>())).thenReturn(statement)
        whenever(statement.executeQuery(any<String>())).thenReturn(resultSet)
        whenever(sqlContext.connectionPool).thenReturn(connections)
        return sessionWithCaches(sqlContext)
    }

    /**
     * A page that just came out of a successful fetch, which is what [XSQLHyperlink.extract]
     * expects.
     * */
    private fun fetchedPage(html: String = "<html><body><h3>title</h3></body></html>"): GoraWebPage {
        val page = newPage()
        page.protocolStatus = ProtocolStatus.STATUS_SUCCESS
        page.isFetched = true
        page.prevFetchTime = java.time.Instant.now()
        page.setByteArrayContent(html.toByteArray())
        return page
    }

    private fun document(html: String): FeaturedDocument =
        FeaturedDocument(org.jsoup.Jsoup.parse(html), false)

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

    // -----------------------------------------------------------------
    // The read-only contract of the X-SQL the hyperlink hands to h2
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a statement that cannot be sealed read-only is refused, not executed")
    fun unsealableStatementIsRefused() {
        // The hyperlink's statement has no url in a FROM clause, so it cannot be sealed: the
        // engine would resolve the page by itself, and nothing would keep it from fetching.
        val link = hyperlink()

        link.extract(fetchedPage(), FeaturedDocument.NIL)

        val response = link.response
        assertFalse(response.isDone, "A refused extraction must not look like a completed one")
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, response.statusCode)
        assertEquals(emptyList<Map<String, Any?>>(), response.resultSet)
        assertTrue(
            response.message?.contains("sealed read-only") == true,
            "Message must say the statement was refused: ${response.message}"
        )
    }

    @Test
    @DisplayName("extraction freezes the page and its document under the url the engine resolves")
    fun extractionFreezesThePageForTheEngine() {
        val session = sessionWithCaches()
        val sql = "select dom_text(dom) as t from load_and_select('https://example.com', ':root')"
        val link = XSQLHyperlink(ScrapeRequest(sql), ScrapeAPIUtils.normalize(sql), session)
        val html = "<html><body><h3>title</h3></body></html>"
        val page = fetchedPage(html)
        val document = document(html)

        link.extract(page, document)

        val queryUrl = "https://example.com"
        assertSame(
            page,
            session.globalCache.pageCache.getDatum(queryUrl),
            "The page must be frozen under the url inside the statement, which is what the UDF resolves"
        )
        assertSame(
            document,
            session.globalCache.documentCache.getDatum(queryUrl),
            "The parsed document must be frozen too, so the engine does not re-parse"
        )
        assertTrue(ScrapeAPIUtils.isPageLocalForQuery(session, queryUrl))
    }

    @Test
    @DisplayName("a failed query leaves a message and a non-null result set on the response")
    fun aFailedQueryLeavesAMessageAndAResultSet() {
        // The session has caches but no SQL context, so the statement cannot run: the failure must
        // be reported on the response a swarm task is watching, not thrown at the load event.
        val session = sessionWithCaches()
        val sql = "select dom_text(dom) as t from load_and_select('https://example.com', ':root')"
        val link = XSQLHyperlink(ScrapeRequest(sql), ScrapeAPIUtils.normalize(sql), session)
        val html = "<html><body><h3>title</h3></body></html>"

        link.extract(fetchedPage(html), document(html))

        val response = link.response
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, response.statusCode)
        assertNotNull(response.message, "A failed statement must explain itself")
        assertTrue(
            response.message!!.contains("SQL context"),
            "the reason must name what is missing: ${response.message}"
        )
        assertEquals(
            emptyList<Map<String, Any?>>(), response.resultSet,
            "resultSet must never be null: a null one plus isDone=true races the CLI"
        )
    }

    @Test
    @DisplayName("a page with no content still reports the query's own outcome, not the page's")
    fun aContentlessPageStillReportsTheQueryOutcome() {
        // A successful fetch that carried no body: the statement still runs against the frozen
        // page, so its outcome — not the 204 the no-content branch sets on the way in — is what
        // the response must report.  Leaving the page status in place would make a task that
        // produced a row look like "no content".
        val link = XSQLHyperlink(
            ScrapeRequest("select t from load_and_select('https://example.com', ':root')"),
            ScrapeAPIUtils.normalize("select t from load_and_select('https://example.com', ':root')"),
            sessionThatRuns(resultSetOf(listOf("t"), listOf("title"))),
        )
        val page = newPage()
        page.protocolStatus = ProtocolStatus.STATUS_SUCCESS
        page.isFetched = true
        page.prevFetchTime = java.time.Instant.now()

        link.extract(page, FeaturedDocument.NIL)

        val response = link.response
        assertEquals(ResourceStatus.SC_OK, response.statusCode, "the query's outcome wins over the page's")
        assertNull(response.message, "a query that ran must not carry a failure message")
        assertEquals(listOf(mapOf("t" to "title")), response.resultSet)
    }

    @Test
    @DisplayName("content that arrived is extracted even when the protocol status is not a success")
    fun contentThatArrivedIsExtractedDespiteTheStatus() {
        // A slow load can record a timeout (a retry/canceled status) while its
        // bytes are already in hand.  Discarding those bytes turned a
        // slow-but-arrived fetch into an empty result set, so the extraction now
        // runs anyway and the message says why the status disagrees.
        val html = "<html><body><h3>title</h3></body></html>"
        val link = XSQLHyperlink(
            ScrapeRequest("select t from load_and_select('https://example.com', ':root')"),
            ScrapeAPIUtils.normalize("select t from load_and_select('https://example.com', ':root')"),
            sessionThatRuns(resultSetOf(listOf("t"), listOf("title"))),
        )
        val page = newPage()
        page.protocolStatus = ProtocolStatus.retry(RetryScope.CRAWL, "recorded timeout")
        page.isFetched = true
        page.prevFetchTime = java.time.Instant.now()
        page.setByteArrayContent(html.toByteArray())

        link.extract(page, document(html))

        val response = link.response
        assertEquals(
            listOf(mapOf("t" to "title")), response.resultSet,
            "the bytes arrived, so the data must be extracted instead of dropped"
        )
        assertTrue(
            response.message?.contains("Extracted anyway") == true,
            "the message must explain the protocol status: ${response.message}"
        )
    }

    @Test
    @DisplayName("a failed fetch that carried no bytes reports the failure instead of extracting")
    fun failedFetchWithoutContentReportsTheFailure() {
        // No bytes means there is nothing to select from: the message must stay
        // the actionable failure, and the query must not run at all.
        val link = hyperlink()
        val page = newPage()
        page.protocolStatus = ProtocolStatus.failed(ProtocolStatusCodes.SC_REQUEST_TIMEOUT)
        page.isFetched = true

        link.extract(page, FeaturedDocument.NIL)

        assertTrue(
            link.response.message?.contains("Page fetch failed with status") == true,
            "a contentless failure must keep its failure message: ${link.response.message}"
        )
        assertNull(link.response.resultSet, "a fetch with no bytes must not produce a result set")
    }

    /** A one-row result set, as the engine would hand it back for the statement under test. */
    private fun resultSetOf(columns: List<String>, row: List<String>): SimpleResultSet =
        SimpleResultSet().apply {
            autoClose = false
            columns.forEach { addColumn(it, Types.VARCHAR, 255, 0) }
            addRow(*row.toTypedArray())
        }
}
