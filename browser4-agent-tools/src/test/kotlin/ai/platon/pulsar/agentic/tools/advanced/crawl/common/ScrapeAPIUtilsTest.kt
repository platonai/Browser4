package ai.platon.pulsar.agentic.tools.advanced.crawl.common

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The h2 engine resolves the page of an X-SQL through the `load_and_select()` UDF and
 * `PulsarSession.load()`, so a statement that is not sealed is a statement that can fetch and
 * rewrite the page while the query runs. These tests pin both halves of that contract: the seal on
 * the url inside the statement, and the local copy the read-only load is served from.
 */
class ScrapeAPIUtilsTest {

    private val conf = MutableConfig(true).toVolatileConfig()

    private val query = """
        select dom_first_text(dom, '#title') as title
        from load_and_select('https://example.com/p -refresh -expires 1h -parse', ':root')
    """.trimIndent()

    // -----------------------------------------------------------------
    // Sealing the statement
    // -----------------------------------------------------------------

    @Test
    @DisplayName("sealing erases every option that could force a web load and adds -readonly")
    fun sealingErasesWebLoadOptions() {
        val sealed = ScrapeAPIUtils.normalizeForReadOnlyQuery(query)

        assertTrue(ScrapeAPIUtils.isReadOnlyQuery(sealed.sql), "sealed statement: ${sealed.sql}")
        assertTrue(sealed.sql.contains(ScrapeAPIUtils.READ_ONLY_OPTION), "sealed statement: ${sealed.sql}")
        assertFalse(sealed.sql.contains("-refresh"), "sealed statement must not force a re-fetch: ${sealed.sql}")
        assertFalse(sealed.sql.contains("-expires"), "sealed statement must not expire the local copy: ${sealed.sql}")
        assertTrue(ScrapeAPIUtils.findWebLoadOptions(sealed.sql).isEmpty())
    }

    @Test
    @DisplayName("sealing is idempotent so a statement is never rewritten twice")
    fun sealingIsIdempotent() {
        val once = ScrapeAPIUtils.normalizeForReadOnlyQuery(query).sql
        val twice = ScrapeAPIUtils.normalizeForReadOnlyQuery(once).sql

        assertEquals(once, twice)
        assertEquals(1, Regex("-readonly").findAll(twice).count(), "readonly must not be duplicated: $twice")
    }

    @Test
    @DisplayName("a statement without options is sealed as well")
    fun aStatementWithoutOptionsIsSealed() {
        val plain = "select dom_text(dom) as t from load_and_select('https://example.com', ':root')"

        val sealed = ScrapeAPIUtils.normalizeForReadOnlyQuery(plain)

        assertTrue(sealed.sql.contains("https://example.com -readonly"), "sealed statement: ${sealed.sql}")
        assertTrue(ScrapeAPIUtils.isReadOnlyQuery(sealed.sql))
    }

    @Test
    @DisplayName("the decisive web-load options are detected on an unsealed statement")
    fun webLoadOptionsAreDetected() {
        val refresh = "select 1 from load_and_select('https://example.com -refresh', ':root')"
        val expires = "select 1 from load_and_select('https://example.com -expires 1h', ':root')"
        val none = "select 1 from load_and_select('https://example.com -readonly', ':root')"

        assertEquals(listOf("refresh"), ScrapeAPIUtils.findWebLoadOptions(refresh))
        assertEquals(listOf("expires"), ScrapeAPIUtils.findWebLoadOptions(expires))
        assertTrue(ScrapeAPIUtils.findWebLoadOptions(none).isEmpty())
    }

    @Test
    @DisplayName("-readonly alone is not enough: a refresh option still fails the check")
    fun readonlyAloneDoesNotPassTheCheck() {
        val sql = "select 1 from load_and_select('https://example.com -readonly -refresh', ':root')"

        assertFalse(ScrapeAPIUtils.isReadOnlyQuery(sql))
        assertThrows(IllegalStateException::class.java) {
            ScrapeAPIUtils.checkReadOnlyQuery(NormXSQL("https://example.com", "-readonly -refresh", sql))
        }
    }

    @Test
    @DisplayName("a statement without -readonly fails the check")
    fun statementWithoutReadonlyFailsTheCheck() {
        val sql = "select 1 from load_and_select('https://example.com', ':root')"

        assertFalse(ScrapeAPIUtils.isReadOnlyQuery(sql))
        val failure = assertThrows(IllegalStateException::class.java) {
            ScrapeAPIUtils.checkReadOnlyQuery(NormXSQL("https://example.com", "", sql))
        }
        assertTrue(failure.message!!.contains("-readonly"), "message: ${failure.message}")
    }

    @Test
    @DisplayName("a statement without a url in its FROM clause cannot be sealed")
    fun aStatementWithoutUrlCannotBeSealed() {
        assertThrows(IllegalArgumentException::class.java) {
            ScrapeAPIUtils.normalizeForReadOnlyQuery("select dom_text(dom) as t")
        }
    }

    // -----------------------------------------------------------------
    // The local copy the read-only load is served from
    // -----------------------------------------------------------------

    @Test
    @DisplayName("freezing a page turns its read-only load into a local read")
    fun freezingAPageMakesTheReadOnlyLoadLocal() {
        val session = fakeSession()
        val page = newPage("https://example.com/p")

        assertFalse(
            ScrapeAPIUtils.isPageLocalForQuery(session, "https://example.com/p"),
            "nothing is cached before the freeze"
        )

        assertTrue(ScrapeAPIUtils.freezePageForQuery(session, "https://example.com/p", page))

        assertTrue(ScrapeAPIUtils.isPageLocalForQuery(session, "https://example.com/p"))
        assertSame(page, session.globalCache.pageCache.getDatum("https://example.com/p"))
    }

    @Test
    @DisplayName("freezing registers the page under the url the engine resolves, redirect included")
    fun freezingRegistersTheUrlTheEngineResolves() {
        val session = fakeSession()
        // A redirect keeps the page but not the key: the crawl submitted /p, the page answers /p/.
        val page = newPage("https://example.com/p/")

        assertTrue(ScrapeAPIUtils.freezePageForQuery(session, "https://example.com/p", page))

        assertSame(page, session.globalCache.pageCache.getDatum("https://example.com/p"))
        assertSame(page, session.globalCache.pageCache.getDatum("https://example.com/p/"))
    }

    @Test
    @DisplayName("a nil page is never frozen under the query url")
    fun aNilPageIsNeverFrozen() {
        val session = fakeSession()

        assertFalse(ScrapeAPIUtils.freezePageForQuery(session, "https://example.com/p", GoraWebPage.NIL))
        assertFalse(ScrapeAPIUtils.isPageLocalForQuery(session, "https://example.com/p"))
        assertNull(session.globalCache.pageCache.getDatum("https://example.com/p"))
    }

    @Test
    @DisplayName("a page in local storage is promoted into the page cache")
    fun aStoredPageIsPromotedIntoThePageCache() {
        val url = "https://example.com/p"
        val stored = newPage(url, "<html><body><h3>stored</h3></body></html>")
        val session = fakeSession(mapOf(url to stored))

        assertFalse(ScrapeAPIUtils.isPageLocalForQuery(session, url), "the store is not the page cache")

        val promoted = ScrapeAPIUtils.promoteStoredPage(session, url)

        assertSame(stored, promoted)
        assertTrue(ScrapeAPIUtils.isPageLocalForQuery(session, url))
    }

    @Test
    @DisplayName("nothing is promoted when local storage holds nothing usable")
    fun nothingIsPromotedFromAnEmptyStore() {
        val session = fakeSession()

        assertNull(ScrapeAPIUtils.promoteStoredPage(session, "https://example.com/p"))
        assertFalse(ScrapeAPIUtils.isPageLocalForQuery(session, "https://example.com/p"))
    }

    @Test
    @DisplayName("a page with no content is not promoted: there is nothing to extract from it")
    fun anEmptyStoredPageIsNotPromoted() {
        val url = "https://example.com/p"
        val session = fakeSession(mapOf(url to newPage(url)))

        assertNull(ScrapeAPIUtils.promoteStoredPage(session, url))
    }

    // -----------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------

    private fun newPage(url: String, content: String? = null): GoraWebPage {
        val page = GoraWebPage.newWebPage(url, conf)
        // A page that was just fetched, which is the only state the read-only load is written for:
        // the engine treats a copy whose fetch time is older than `expires` as absent.
        page.prevFetchTime = java.time.Instant.now()
        if (content != null) {
            // contentLength is computed from the original length, which a page that never went
            // through a fetch has to be told explicitly.
            page.originalContentLength = content.toByteArray().size.toLong()
            page.setStringContent(content)
        }
        return page
    }

    /**
     * A [PulsarSession] that answers the read-only machinery and nothing else: the caches the
     * engine resolves the page through, the normalization that keys them, and the local store.
     * */
    private fun fakeSession(storedPages: Map<String, WebPage> = emptyMap()): PulsarSession {
        val globalCache = GlobalCache(ImmutableConfig())
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            val arguments = args ?: emptyArray()
            when {
                method.name == "toString" -> "fake-session"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === arguments.firstOrNull()
                method.name == "getGlobalCache" -> globalCache
                method.name == "getPageCache" -> globalCache.pageCache
                method.name == "getDocumentCache" -> globalCache.documentCache
                method.name == "options" -> LoadOptions.parse(arguments.firstOrNull() as? String ?: "", conf)
                method.name == "normalize" && arguments.size == 3 && arguments[0] is String ->
                    normUrl(arguments[0] as String, arguments[1] as? String ?: "")
                method.name == "getOrNull" -> storedPages[arguments.firstOrNull()]
                method.returnType == java.lang.Boolean.TYPE -> false
                method.returnType == Integer.TYPE -> 0
                method.returnType == java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            PulsarSession::class.java.classLoader,
            arrayOf(PulsarSession::class.java),
            handler
        ) as PulsarSession
    }

    /**
     * The same normalization the engine applies before it looks a page up: the inline args of the
     * url merged into the options, the url itself left as the cache key.
     * */
    private fun normUrl(url: String, args: String): NormURL {
        val (url0, inlineArgs) = URLUtils.splitUrlArgs(url)
        val merged = listOf(inlineArgs, args).filter { it.isNotBlank() }.joinToString(" ")
        return NormURL(url0, LoadOptions.parse(merged, conf))
    }
}
