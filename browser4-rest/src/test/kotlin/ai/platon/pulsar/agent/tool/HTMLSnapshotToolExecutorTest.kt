package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.KStubbing
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.Instant

/**
 * The parts of the `html_snapshot` family that hold without a browser: the
 * capture metadata assembly, the X-SQL error hints, and — the reason this class
 * exists — the family's read/write contract.
 *
 * **`capture` is the only command that writes.**  It serializes the document the
 * active tab already shows and files it in the page store under the tab's
 * normalized url; every other command (`get`, `get all`, `export`, `summary`,
 * `inspect`, `readability`, `query`) serves the store, or loads the page
 * independently when the store has nothing, and never files a document — not
 * even when the target url happens to be the page on screen.
 *
 * The session is mocked here, so a test can assert both halves of that contract:
 * what was written, and what was *not*.
 */
@DisplayName("HTMLSnapshotToolExecutor")
class HTMLSnapshotToolExecutorTest {

    private val h2MissingColumn = "Column \"A\" not found; SQL statement"
    private val h2HexError = "Hexadecimal string contains non-hex character: \"899.99\" (SQL 90004-197)"

    private val mapper = pulsarObjectMapper()

    private fun executor(scrapeService: ScrapeService? = null) =
        HTMLSnapshotToolExecutor(mock<PulsarSessionManager>(), scrapeService)

    private suspend fun HTMLSnapshotToolExecutor.call(
        method: String,
        args: Map<String, Any?>,
        managed: ManagedSession,
    ): Any? = callFunctionOn("html_snapshot", method, args, managed)

    /** A session bound to the tab [driver], plus whatever [stub] adds for the command under test. */
    private fun sessionShowing(
        driver: WebDriver,
        stub: KStubbing<AgenticSession>.() -> Unit = {},
    ): AgenticSession = mock {
        on { getOrCreateBoundDriver() } doReturn driver
        on { normalize(any<String>()) } doAnswer { NormURL(it.getArgument<String>(0), LoadOptions.DEFAULT) }
        stub()
    }

    /** A tab showing [address]; the address is what `capture` decides on. */
    private fun tab(address: String): WebDriver = mock {
        onBlocking { currentUrl() } doReturn address
    }

    private fun page(
        url: String,
        href: String? = url,
        contentLength: Long = 2048L,
        contentType: String = "text/html",
    ): WebPage = mock {
        on { this.url } doReturn url
        on { this.href } doReturn href
        on { this.contentLength } doReturn contentLength
        on { this.contentType } doReturn contentType
        on { this.prevFetchTime } doReturn Instant.parse("2026-09-03T10:00:00Z")
    }

    private fun document(html: String, baseUri: String = "https://example.com/") =
        FeaturedDocument(Jsoup.parse(html, baseUri))

    // =========================================================================
    // The read/write contract
    // =========================================================================

    @Test
    @DisplayName("capture writes the live document, keyed by the normalized url and carrying the raw href")
    fun captureAlwaysWritesTheLiveDocument() = runBlocking<Unit> {
        val html = """
            <html><head><title>Live</title></head><body>
            <a href="/next" vi="10 10 60 20">Next</a>
            </body></html>
        """.trimIndent()
        val address = "https://example.com/product/1?utm_source=ad#reviews"
        val driver = tab(address)
        val page = page(url = "https://example.com/product/1", href = address)
        val session = sessionShowing(driver) {
            // The must-write option is part of the contract, not an implementation detail: without it
            // the load pipeline may answer from a cached page shell, and `persist` drops the CONTENT
            // field of a cached shell — a capture would then report the new document while the store
            // kept the old one (see HTMLSnapshotToolExecutor.MUST_WRITE_OPTION).  The stub below spells
            // the flag out as a literal on purpose: what has to keep working is the option *string*
            // that LoadOptions.parse understands, not a constant's value.
            onBlocking { capture(driver, "$address -refresh") } doReturn page
            on { parse(page, true) } doReturn document(html, "https://example.com/product/1")
        }

        val payload = executor().call("capture", mapOf("sessionId" to "s"), ManagedSession("s", session, null)) as String

        // The write happened, through the driver the tab belongs to, with must-write semantics.
        verify(session).capture(driver, "$address -refresh", null)

        val json = mapper.readTree(payload)
        assertEquals("https://example.com/product/1", json["url"].asText(), "the store key is the normalized url")
        assertEquals(
            address, json["href"].asText(),
            "the browser-facing address keeps the query and the fragment",
        )
        assertEquals("2048", json["sizeBytes"].asText())
        assertEquals("text/html", json["contentType"].asText())
        assertEquals("2026-09-03T10:00:00Z", json["capturedAt"].asText())
    }

    @Test
    @DisplayName("capture refuses a tab that shows no archivable document")
    fun captureRefusesAnUnarchivableTab() = runBlocking<Unit> {
        for (address in listOf("about:blank", "chrome-error://chromewebdata", "data:text/html,<p>hi</p>")) {
            val bound = sessionShowing(tab(address))

            val exception = assertThrows<IllegalArgumentException> {
                runBlocking {
                    executor().call("capture", mapOf("sessionId" to "s"), ManagedSession("s", bound, null))
                }
            }

            assertTrue(
                exception.message!!.contains(address),
                "the refusal must name the address it refused, got: ${exception.message}",
            )
            // Refusing is the whole point: nothing was written.
            verify(bound, never()).capture(any(), anyOrNull(), anyOrNull())
        }
    }

    @Test
    @DisplayName("readability of another url reads that url's stored copy and never captures the live tab")
    fun readabilityOfAnotherUrlNeverFilesTheLiveDocument() = runBlocking<Unit> {
        val liveUrl = "https://example.com/form"
        val otherUrl = "https://example.com/article"
        val articleHtml = """
            <html><head>
              <title>How Rust Conquered the Kernel</title>
              <meta name="author" content="Ada Lovelace">
              <meta property="og:site_name" content="Systems Weekly">
            </head><body>
              <nav id="main-nav"><a href="/">Home</a> <a href="/news">News</a></nav>
              <div id="content"><article class="post">
                <h1>How Rust Conquered the Kernel</h1>
                <p>Rust brings memory safety to the Linux kernel without sacrificing performance. This
                   article explores the history, the technical design and the community effort behind the
                   largest incremental rewrite in kernel history.</p>
                <p>The first Rust code landed in Linux 6.1, guarded by a strict configuration flag. Since
                   then, device drivers, filesystems and networking components have begun migrating to safe
                   abstractions that eliminate entire classes of vulnerabilities.</p>
                <p>Critics point to the learning curve and the difficulty of auditing unsafe blocks.
                   Proponents counter that the ecosystem tooling catches whole bug families at compile time
                   rather than at runtime.</p>
              </article></div>
            </body></html>
        """.trimIndent()
        val stored = page(otherUrl)
        // The tab shows a different page, and the store has nothing for it — which is exactly when the
        // old code captured the live tab and filed it under `otherUrl`.
        val session = sessionShowing(tab(liveUrl)) {
            on { getOrNull(otherUrl) } doReturn stored
            on { parse(stored) } doReturn document(articleHtml, otherUrl)
        }

        val payload = executor().call(
            "readability", mapOf("sessionId" to "s", "url" to otherUrl), ManagedSession("s", session, null),
        ) as String

        val json = mapper.readTree(payload)
        assertEquals(otherUrl, json["url"].asText())
        assertTrue(json["length"].asInt() > 0, "the fixture must be readable, otherwise this test proves nothing")
        assertTrue(
            json["textContent"].asText().contains("Rust brings memory safety"),
            "the article must come from the requested url's stored copy",
        )

        // The requested url was the lookup key, the live page was never consulted, and — the point of the
        // test — the live tab was never serialized, so nothing could be filed under `otherUrl`.
        verify(session).getOrNull(otherUrl)
        verify(session, never()).getOrNull(liveUrl)
        verify(session, never()).capture(any(), anyOrNull(), anyOrNull())
        verify(session, never()).load(any<String>())
    }

    @Test
    @DisplayName("a read that misses the store loads the page independently instead of capturing the live tab")
    fun aReadThatMissesTheStoreLoadsInsteadOfCapturing() = runBlocking<Unit> {
        val liveUrl = "https://example.com/form"
        val loaded = page(liveUrl)
        val session = sessionShowing(tab(liveUrl)) {
            on { getOrNull(liveUrl) } doReturn null
            onBlocking { load("$liveUrl -readonly") } doReturn loaded
            on { parse(loaded) } doReturn document("<html><body><h1>Stored copy</h1></body></html>", liveUrl)
        }

        val payload = executor().call(
            // `scrape` is the executor's method name for the CLI's `htmlsnapshot get`.
            "scrape",
            mapOf("sessionId" to "s", "field" to "text", "selector" to "h1"),
            ManagedSession("s", session, null),
        ) as String

        assertEquals("Stored copy", payload)
        verify(session).load("$liveUrl -readonly")
        verify(session, never()).capture(any(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("a url-targeted query runs session-less: it resolves no session and captures nothing")
    fun aUrlTargetedQueryNeverTouchesASession() = runBlocking<Unit> {
        val response = ScrapeResponse(
            id = "task-1",
            statusCode = 200,
            pageStatusCode = 200,
            pageContentBytes = 128,
            isDone = true,
            resultSet = listOf(mapOf("title" to "Widget Alpha")),
            event = "completed",
        )
        val scrapeService = mock<ScrapeService> { on { executeQuery(any()) } doReturn response }
        // A url-targeted query must not even ask for a session: seeding the store from the live page is
        // gone, and the arguments below deliberately carry no sessionId to make a regression loud.
        val sessionManager = mock<PulsarSessionManager> {
            on { getOrRecoverSession(any()) } doThrow IllegalStateException("a url-targeted query must not resolve a session")
        }
        val executor = HTMLSnapshotToolExecutor(sessionManager, scrapeService)

        val payload = executor.callFunctionOn(
            "html_snapshot", "query",
            mapOf("sql" to "select dom_first_text(dom, 'h1') as title", "url" to "https://example.com"),
            Unit,
        ) as String

        assertEquals(true, mapper.readTree(payload)["isDone"].asBoolean())
        verify(sessionManager, never()).getOrRecoverSession(any())
    }

    // =========================================================================
    // Capture metadata
    // =========================================================================

    @Test
    @DisplayName("the capture metadata describes the serialized document and its interactive elements")
    fun captureMetadataListsInteractiveElements() {
        // Mirrors a post-interaction live document: the state-log text and the
        // <title> only exist in the live DOM (a PROBE-TITLE capture proves the
        // serializer reads the live document, not an independent page load).
        val html = """
            <html><head><title>PROBE-TITLE</title></head><body>
            <div id="state-log">submit-success:email; submitCount: 1</div>
            <form id="registration-form" vi="100 20 240 200">
              <input type="text" id="first-name" value="" vi="110 40 220 30">
              <button id="submit-btn" class="primary-button" type="submit" vi="110 90 220 36">Submit form</button>
              <a href="/ec/dp/B0E000001" class="product-link" vi="110 140 220 30">Product link</a>
            </form>
            <img src="/img/a.jpg" alt="A" vi="10 10 60 40">
            </body></html>
        """.trimIndent()
        val json = mapper.readTree(
            htmlSnapshotMetadataJson(
                document = document(html, "https://example.com/form"),
                url = "https://example.com/form",
                href = "https://example.com/form#probe",
                sizeBytes = html.length.toLong(),
                capturedAt = "2026-09-03T10:00:00Z",
                contentType = "text/html",
            )
        )

        // The captured title is the LIVE title (an independent load would report
        // the server's original title instead)
        assertEquals("PROBE-TITLE", json["title"].asText(), "Capture must serialize the live document title")
        assertEquals("https://example.com/form", json["url"].asText())
        assertEquals("https://example.com/form#probe", json["href"].asText())
        assertEquals(1, json["imageCount"].asInt())
        assertEquals(1, json["linkCount"].asInt())

        val elements = json["interactiveElements"]
        assertNotNull(elements, "Metadata must carry interactive elements")
        assertTrue(elements.size() > 0, "Interactive elements should be detected from vi boxes")

        val submit = elements.firstOrNull { it["ref"].asText().contains("submit-btn") }
        assertNotNull(submit, "The submit button must be listed, got: $elements")
        assertEquals("primary", submit!!["tier"].asText())
        assertTrue(submit["weight"].asInt() > 0, "Weight must be computed from the vi box")
        assertTrue(submit["text"].asText().contains("Submit form"), "Button text should be sampled")

        // Every interactive element keeps the CLI-compatible metadata fields
        for (el in elements) {
            assertTrue(el.has("ref"), "Element must carry a ref")
            assertTrue(el.has("tier"), "Element must carry a tier")
            assertTrue(el.has("weight"), "Element must carry a weight")
        }
    }

    @Test
    @DisplayName("the capture metadata assembles for a document without vi attributes")
    fun captureMetadataWorksWithoutViAttributes() {
        // A capture on a page whose helper is missing serializes plain
        // outerHTML; without vi boxes the weighted interactive list degrades
        // gracefully (may be empty) but the metadata must still assemble.
        val html = """
            <html><head><title>Static</title></head><body>
            <h1>Static page</h1>
            <a href="/next">Next</a>
            </body></html>
        """.trimIndent()
        val json = mapper.readTree(
            htmlSnapshotMetadataJson(document(html, "https://example.com/static"), "https://example.com/static",
                "https://example.com/static", html.length.toLong(), "2026-09-03T10:00:00Z", "text/html")
        )

        assertEquals("Static", json["title"].asText())
        assertEquals(1, json["linkCount"].asInt())
        assertTrue(json.has("interactiveElements"))
    }

    // =========================================================================
    // X-SQL error hints
    // =========================================================================

    @Test
    @DisplayName("a double quoted DOM selector receives the single quote hint")
    fun doubleQuotedSelectorReceivesTheHint() {
        assertTrue(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT DOM_TEXT(DOM) FROM DOM_LOAD_AND_SELECT('https://example.com', \"a\")"
            )
        )
    }

    @Test
    @DisplayName("an unrelated missing quoted column does not receive the selector hint")
    fun unrelatedMissingColumnReceivesNoHint() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT \"missing_column\" FROM pages"
            )
        )
    }

    @Test
    @DisplayName("a single quoted DOM selector does not receive the hint")
    fun singleQuotedSelectorReceivesNoHint() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT DOM_TEXT(DOM) FROM DOM_LOAD_AND_SELECT('https://example.com', 'a')"
            )
        )
    }

    @Test
    @DisplayName("the hex error on DOM_FIRST_FLOAT in WHERE receives the cast hint")
    fun hexErrorOnDomFirstFloatReceivesTheCastHint() {
        assertTrue(
            HTMLSnapshotToolExecutor.shouldAppendDomFirstFloatCastHint(
                h2HexError,
                "SELECT DOM_BASE_URI(DOM) FROM DOM_LOAD_AND_SELECT(@url, 'body') " +
                    "WHERE DOM_FIRST_FLOAT(DOM, '.price') >= 25.0"
            )
        )
    }

    @Test
    @DisplayName("the hex error on DOM_FIRST_INTEGER in WHERE receives the cast hint")
    fun hexErrorOnDomFirstIntegerReceivesTheCastHint() {
        assertTrue(
            HTMLSnapshotToolExecutor.shouldAppendDomFirstFloatCastHint(
                h2HexError,
                "SELECT DOM_BASE_URI(DOM) FROM DOM_LOAD_AND_SELECT(@url, 'body') " +
                    "WHERE DOM_FIRST_INTEGER(DOM, '.stock') < 10"
            )
        )
    }

    @Test
    @DisplayName("a hex error without a DOM_FIRST_FLOAT or INTEGER call does not receive the cast hint")
    fun hexErrorWithoutNumericCallReceivesNoHint() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendDomFirstFloatCastHint(
                h2HexError,
                "SELECT DOM_FIRST_TEXT(DOM, '.price') FROM DOM_LOAD_AND_SELECT(@url, 'body') " +
                    "WHERE DOM_FIRST_TEXT(DOM, '.price') >= '25.0'"
            )
        )
    }

    @Test
    @DisplayName("an unrelated error with DOM_FIRST_FLOAT does not receive the cast hint")
    fun unrelatedErrorWithNumericCallReceivesNoHint() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendDomFirstFloatCastHint(
                h2MissingColumn,
                "SELECT DOM_FIRST_FLOAT(DOM, '.price') FROM DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    @Test
    @DisplayName("DOM_FIRST_IMG with an expr selector is detected")
    fun domFirstImgWithExprIsDetected() {
        assertTrue(
            HTMLSnapshotToolExecutor.hasDomFirstImgExpr(
                "SELECT DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img:expr(src^=https://cdn)')) FROM " +
                    "DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    @Test
    @DisplayName("DOM_NTH_IMG and DOM_ALL_IMGS with expr selectors are detected")
    fun domNthImgAndAllImgsWithExprAreDetected() {
        assertTrue(
            HTMLSnapshotToolExecutor.hasDomFirstImgExpr(
                "SELECT DOM_ABS_SRC(DOM_NTH_IMG(DOM, 'img:expr(data-price > 10)', 2)), " +
                    "DOM_SRCS(DOM_ALL_IMGS(DOM, 'img:expr(alt)')) FROM DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    @Test
    @DisplayName("img selectors without expr are not flagged")
    fun imgSelectorsWithoutExprAreNotFlagged() {
        assertFalse(
            HTMLSnapshotToolExecutor.hasDomFirstImgExpr(
                "SELECT DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img.hero')) FROM " +
                    "DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }

    @Test
    @DisplayName("expr selectors on non-img functions are not flagged")
    fun exprSelectorsOnNonImgFunctionsAreNotFlagged() {
        assertFalse(
            HTMLSnapshotToolExecutor.hasDomFirstImgExpr(
                "SELECT DOM_FIRST_ATTR(DOM, 'img:expr(src^=https://cdn)', 'src') FROM " +
                    "DOM_LOAD_AND_SELECT(@url, 'body')"
            )
        )
    }
}
