package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.ScrapeAPIUtils
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import kotlinx.coroutines.runBlocking
import org.h2.tools.SimpleResultSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue

/**
 * The crawl hands its X-SQL to the h2 engine, which resolves the page in the FROM clause itself
 * through `load_and_select()` and `PulsarSession.load()`. These tests pin the two halves of that
 * contract at the crawl's call site: the page the round already holds is what the engine is given,
 * the statement that reaches h2 is sealed read-only, and a page that is not local is refused
 * instead of being fetched *while* the query runs.
 */
class CrawlXSqlTest {

    private val conf = MutableConfig(true).toVolatileConfig()
    private val url = "https://example.com/p"
    private val sql = "select dom_text(dom) as t from load_and_select(@url, ':root')"

    @Test
    @DisplayName("the statement that reaches h2 is sealed read-only, and its rows come back ordered")
    fun theStatementReachingH2IsSealedReadOnly() {
        val session = mock<PulsarSession>()
        val globalCache = GlobalCache(ImmutableConfig())
        val sqlContext = mock<AbstractBrowser4SQLContext>()
        stubSession(session, globalCache, sqlContext)
        val statement = statementReturning(sqlContext, extractedResultSet())
        // The page is already local — the crawl fetched it moments ago.
        globalCache.pageCache.putDatum(url, fetchedPage(url))

        val (rows, error) = executeCrawlSqlQuery(session, url, sql)

        assertNull(error)
        assertEquals(listOf("title", "price"), rows!!.single().keys.toList(), "rows: $rows")
        assertEquals("4K OLED TV", rows.single()["title"])

        val statementSentToH2 = argumentCaptor<String>()
        verify(statement).executeQuery(statementSentToH2.capture())
        assertTrue(
            ScrapeAPIUtils.isReadOnlyQuery(statementSentToH2.firstValue),
            "The url inside the statement must be sealed: ${statementSentToH2.firstValue}"
        )
        assertTrue(statementSentToH2.firstValue.contains("-readonly"), statementSentToH2.firstValue)
    }

    @Test
    @DisplayName("the round's own page is what the engine is given, and nothing is fetched again")
    fun theRoundsOwnPageIsFrozenForTheQuery() {
        // This is the wiring that makes the statement a local read.  Dropping the arguments would
        // still usually pass, because the page cache answers for the same url — until the key does
        // not match and the query silently downloads the page a second time.
        val session = mock<PulsarSession>()
        val globalCache = GlobalCache(ImmutableConfig())
        val sqlContext = mock<AbstractBrowser4SQLContext>()
        stubSession(session, globalCache, sqlContext)
        val statement = statementReturning(sqlContext, extractedResultSet())
        val page = fetchedPage(url)
        val document = FeaturedDocument(org.jsoup.Jsoup.parse("<html><body><h3>t</h3></body></html>"), false)

        val (rows, error) = executeCrawlSqlQuery(session, url, sql, page, document)

        assertNull(error)
        assertNotNull(rows)
        assertSame(page, globalCache.pageCache.getDatum(url), "the round's page must be frozen for the query")
        assertSame(
            document, globalCache.documentCache.getDatum(url),
            "the parsed document must be frozen too, so the engine does not re-parse"
        )
        // The page was in hand: the query must not have gone back to the web for it.
        runBlocking { verify(session, never()).load(any<String>(), any<String>()) }
        verify(statement).executeQuery(any<String>())
    }

    @Test
    @DisplayName("a page that is not local is refused instead of fetched while the query runs")
    fun aPageThatIsNotLocalIsRefused() {
        val session = mock<PulsarSession>()
        val globalCache = GlobalCache(ImmutableConfig())
        stubSession(session, globalCache, null)
        whenever(session.getOrNull(any())).thenReturn(null)
        runBlocking { whenever(session.load(any<String>(), any<String>())).thenReturn(GoraWebPage.NIL) }

        val (rows, error) = executeCrawlSqlQuery(session, url, sql)

        assertNull(rows, "A refused query must not report rows")
        assertNotNull(error)
        assertTrue(
            error!!.contains("could not be downloaded"),
            "The report must say why the query was not run: $error"
        )
    }

    @Test
    @DisplayName("a result whose every field is empty is reported alongside its rows")
    fun anAllEmptyResultIsReportedWithItsRows() {
        // Rows come back, but nothing in them is data.  The page was local, so this is the query
        // matching nothing: the rows stay in the answer and the reason travels with them.
        val session = mock<PulsarSession>()
        val globalCache = GlobalCache(ImmutableConfig())
        val sqlContext = mock<AbstractBrowser4SQLContext>()
        stubSession(session, globalCache, sqlContext)
        statementReturning(sqlContext, emptyTextResultSet())
        globalCache.pageCache.putDatum(url, fetchedPage(url))

        val (rows, error) = executeCrawlSqlQuery(session, url, sql)

        assertEquals(1, rows?.size, "the rows must still be reported: $rows")
        assertNotNull(error, "an all-empty result must be explained")
        assertTrue(error!!.contains("no data"), "error: $error")
    }

    @Test
    @DisplayName("a query without a url in its FROM clause is reported, not executed")
    fun aQueryWithoutUrlIsReported() {
        val session = mock<PulsarSession>()

        val (rows, error) = executeCrawlSqlQuery(session, url, "select 1")

        assertNull(rows)
        assertNotNull(error)
        assertTrue(error!!.contains("no url found"), "error: $error")
    }

    @Test
    @DisplayName("a page that is only in local storage is promoted into the cache, not fetched")
    fun aStoredPageIsPromotedInsteadOfFetched() {
        val session = mock<PulsarSession>()
        val globalCache = GlobalCache(ImmutableConfig())
        val sqlContext = mock<AbstractBrowser4SQLContext>()
        stubSession(session, globalCache, sqlContext)
        statementReturning(sqlContext, extractedResultSet())
        val stored = fetchedPage(url)
        whenever(session.getOrNull(any())).thenReturn(stored)

        val (rows, error) = executeCrawlSqlQuery(session, url, sql)

        assertNull(error)
        assertNotNull(rows)
        assertSame(
            stored, globalCache.pageCache.getDatum(url),
            "a page that is already in local storage must be promoted, not downloaded"
        )
        runBlocking { verify(session, never()).load(any<String>(), any<String>()) }
    }

    @Test
    @DisplayName("a page that is nowhere local is downloaded immediately before the query")
    fun aPageThatIsNowhereLocalIsDownloadedForTheQuery() {
        val session = mock<PulsarSession>()
        val globalCache = GlobalCache(ImmutableConfig())
        val sqlContext = mock<AbstractBrowser4SQLContext>()
        stubSession(session, globalCache, sqlContext)
        val statement = statementReturning(sqlContext, extractedResultSet())
        whenever(session.getOrNull(any())).thenReturn(null)
        val downloaded = fetchedPage(url)
        runBlocking { whenever(session.load(any<String>(), any<String>())).thenReturn(downloaded) }

        val (rows, error) = executeCrawlSqlQuery(session, url, sql)

        assertNull(error)
        assertNotNull(rows)
        assertSame(
            downloaded, globalCache.pageCache.getDatum(url),
            "the page downloaded for the query must be frozen for it"
        )
        runBlocking { verify(session).load(any<String>(), any<String>()) }
        verify(statement).executeQuery(any<String>())
    }

    @Test
    @DisplayName("the round's page is not served for a url the statement did not ask it for")
    fun theRoundsPageIsNotServedForAnotherUrl() {
        // A statement that names another url asks about another page.  Freezing the round's page
        // under that key would answer the query with content that does not belong to the url, which
        // is worse than refusing to answer.
        val other = "https://other.example.com/q"
        val sqlForOther = "select dom_text(dom) as t from load_and_select('$other', ':root')"
        val session = mock<PulsarSession>()
        val globalCache = GlobalCache(ImmutableConfig())
        stubSession(session, globalCache, null)
        whenever(session.normalize(any<String>(), any<String>(), any<Boolean>()))
            .thenAnswer { normUrl(it.getArgument(0)) }
        whenever(session.getOrNull(any())).thenReturn(null)
        runBlocking { whenever(session.load(any<String>(), any<String>())).thenReturn(GoraWebPage.NIL) }

        val (rows, error) = executeCrawlSqlQuery(session, url, sqlForOther, page = fetchedPage(url))

        assertNull(rows, "the query must not run on another page's content")
        assertNotNull(error)
        assertNull(
            globalCache.pageCache.getDatum(other),
            "the round's page must never be frozen under a url it does not belong to"
        )
        runBlocking { verify(session).load(eq(other), any<String>()) }
    }

    @Test
    @DisplayName("a query that matches nothing is an empty answer, not an error")
    fun aQueryThatMatchesNothingIsNotAnError() {
        val session = mock<PulsarSession>()
        val globalCache = GlobalCache(ImmutableConfig())
        val sqlContext = mock<AbstractBrowser4SQLContext>()
        stubSession(session, globalCache, sqlContext)
        statementReturning(sqlContext, columnOnlyResultSet())
        globalCache.pageCache.putDatum(url, fetchedPage(url))

        val (rows, error) = executeCrawlSqlQuery(session, url, sql)

        assertNull(error, "0 rows is a legitimate answer: $error")
        assertEquals(emptyList<Map<String, Any?>>(), rows)
    }

    // -----------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------

    private fun stubSession(
        session: PulsarSession,
        globalCache: GlobalCache,
        sqlContext: AbstractBrowser4SQLContext?,
    ) {
        whenever(session.globalCache).thenReturn(globalCache)
        whenever(session.options(any())).thenReturn(LoadOptions.parse("-readonly"))
        whenever(session.normalize(any<String>(), any<String>(), any<Boolean>())).thenReturn(normUrl(url))
        sqlContext?.let { whenever(session.context).thenReturn(it) }
    }

    /**
     * Wire [resultSet] into a mocked SQL context and return the statement the executor will call,
     * so a test can assert what was sent to h2.
     * */
    private fun statementReturning(sqlContext: AbstractBrowser4SQLContext, resultSet: ResultSet): Statement {
        val connection = mock<Connection>()
        val statement = mock<Statement>()
        val connections = ArrayBlockingQueue<Connection>(1).apply { add(connection) }
        whenever(connection.createStatement(any<Int>(), any<Int>())).thenReturn(statement)
        whenever(statement.executeQuery(any<String>())).thenReturn(resultSet)
        whenever(sqlContext.connectionPool).thenReturn(connections)
        return statement
    }

    private fun fetchedPage(url: String): GoraWebPage {
        val page = GoraWebPage.newWebPage(url, conf)
        page.protocolStatus = ProtocolStatus.STATUS_SUCCESS
        page.prevFetchTime = Instant.now()
        val html = "<html><body><h3>title</h3></body></html>"
        // A page that came through a fetch has a real content length; a page that only exists in
        // memory does not, and a stored page without content is not worth promoting.
        page.originalContentLength = html.toByteArray().size.toLong()
        page.setStringContent(html)
        return page
    }

    /**
     * What the engine hands back for the statement under test: two columns, one row. The column
     * order here is the SELECT order the rows must come back in.
     * */
    private fun extractedResultSet(): SimpleResultSet = SimpleResultSet().apply {
        autoClose = false
        addColumn("Title", Types.VARCHAR, 255, 0)
        addColumn("Price", Types.VARCHAR, 255, 0)
        addRow("4K OLED TV", "899.99")
    }

    /** The same shape, with a url column and an empty text column: rows, but no data. */
    private fun emptyTextResultSet(): SimpleResultSet = SimpleResultSet().apply {
        autoClose = false
        addColumn("Url", Types.VARCHAR, 255, 0)
        addColumn("Title", Types.VARCHAR, 255, 0)
        addRow(url, "")
    }

    /** Columns but no rows: the query ran and matched nothing. */
    private fun columnOnlyResultSet(): SimpleResultSet = SimpleResultSet().apply {
        autoClose = false
        addColumn("Title", Types.VARCHAR, 255, 0)
    }

    private fun normUrl(url: String) = NormURL(url, LoadOptions.parse("", conf))
}
