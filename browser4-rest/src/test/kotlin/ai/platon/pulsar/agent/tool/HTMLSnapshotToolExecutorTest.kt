package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryAlgorithm
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryAlgorithmRegistry
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryInput
import ai.platon.pulsar.skeleton.workflow.parse.html.WpsiPageSummaryAlgorithm
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.Duration
import java.time.Instant

/**
 * The parts of the `html_snapshot` family that hold without a browser: the
 * capture metadata assembly, the X-SQL error hints, and — the reason this class
 * exists — the family's contract.
 *
 * **Every command works on a fresh snapshot of the active page.**  A command
 * captures the tab first (serializing the document the tab already shows, with
 * no navigation) and then operates on that snapshot: `capture` returns its
 * metadata, while the reads (`get`, `get all`, `export`, `summary`, `inspect`,
 * `readability`, `query`) consume it — which is why a read sees form
 * submissions, SPA updates and `eval` mutations without a capture of its own.
 *
 * A url the tab does *not* show cannot be captured, so such a target keeps the
 * read-only path: that url's stored copy, or an independent read-only load when
 * the store has nothing.  The tab's document is never filed under a requested
 * url.
 *
 * The session is mocked here, so a test can assert both halves of that contract:
 * what was captured, and what was *not*.
 */
@DisplayName("HTMLSnapshotToolExecutor")
class HTMLSnapshotToolExecutorTest {

    private val h2MissingColumn = "Column \"A\" not found; SQL statement"
    private val h2HexError = "Hexadecimal string contains non-hex character: \"899.99\" (SQL 90004-197)"

    private val mapper = pulsarObjectMapper()

    /** Test-contributed algorithm; the global registry is restored after every test. */
    private class MarkerAlgorithm(
        override val id: String = "rest-marker",
        private val marker: String = "MARKER-SUMMARY",
    ) : PageSummaryAlgorithm {
        override val displayName = "Marker Algorithm"
        override val description = "test-only summary algorithm"
        var calls = 0
        override fun generate(input: PageSummaryInput): String {
            calls++
            return "$marker|${input.pageUrl}|${input.title}"
        }
    }

    @AfterEach
    fun resetAlgorithmRegistry() {
        PageSummaryAlgorithmRegistry.instance.clear()
        PageSummaryAlgorithmRegistry.instance.register(WpsiPageSummaryAlgorithm)
    }

    /** The two urls the contract tests work with: what the tab shows, and what the caller asks for. */
    private val liveUrl = "https://example.com/form"
    private val otherUrl = "https://example.com/article"

    /**
     * A page with enough prose for `ReadabilityExtractor`, so the foreign-url half of the contract can
     * assert on really extracted text instead of an empty payload.
     */
    private val articleHtml = """
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
        // `boundDriver` is the *non-creating* accessor, and it is what the query path reads to decide
        // whether a requested url is the page on screen — so it must be stubbed like the creating one.
        on { boundDriver } doReturn driver
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
        // The `expires` tests need control over how old a stored copy is; every other test keeps the
        // fixed timestamp the capture-metadata assertions read back.
        fetchedAt: Instant = Instant.parse("2026-09-03T10:00:00Z"),
    ): WebPage = mock {
        on { this.url } doReturn url
        on { this.href } doReturn href
        on { this.contentLength } doReturn contentLength
        on { this.contentType } doReturn contentType
        on { this.prevFetchTime } doReturn fetchedAt
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
            // that LoadOptions.parse understands, not a constant's value.  `-expires 0s` is the
            // spelling — "no stored copy is current, write this one" — where `-refresh` used to be,
            // because `-refresh` reads like "reload the page", which a capture never does.
            onBlocking { capture(driver, "$address -expires 0s") } doReturn page
            on { parse(page, true) } doReturn document(html, "https://example.com/product/1")
        }

        val payload = executor().call("capture", mapOf("sessionId" to "s"), ManagedSession("s", session, null)) as String

        // The write happened, through the driver the tab belongs to, with must-write semantics.
        verify(session).capture(driver, "$address -expires 0s", null)

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
    @DisplayName("a read of the active page captures it first and reads the snapshot it just took")
    fun aReadOfTheActivePageReadsTheSnapshotItJustCaptured() = runBlocking<Unit> {
        val address = "https://example.com/form"
        val driver = tab(address)
        val captured = page(address, href = "$address#step-2")
        val session = sessionShowing(driver) {
            // The must-write option is pinned as a literal on purpose — see
            // HTMLSnapshotToolExecutor.MUST_WRITE_OPTION.
            onBlocking { capture(driver, "$address -expires 0s") } doReturn captured
            on { parse(captured, true) } doReturn
                document("<html><body><h1>Live after submit</h1></body></html>", address)
        }

        val payload = executor().call(
            // `scrape` is the executor's method name for the CLI's `htmlsnapshot get`.
            "scrape",
            mapOf("sessionId" to "s", "field" to "text", "selector" to "h1"),
            ManagedSession("s", session, null),
        ) as String

        assertEquals("Live after submit", payload)
        // Captured first, with must-write semantics: the snapshot is the tab as it is now.
        verify(session).capture(driver, "$address -expires 0s", null)
        // And then read from that snapshot — the store is never consulted for the active page.
        verify(session, never()).getOrNull(any<String>())
        verify(session, never()).load(any<String>())
    }

    @Test
    @DisplayName("every read refreshes the active page first — none of them serves an older copy")
    fun everyReadRefreshesTheActivePageFirst() = runBlocking<Unit> {
        val response = ScrapeResponse(
            id = "task-1",
            statusCode = 200,
            pageStatusCode = 200,
            pageContentBytes = 64,
            isDone = true,
            resultSet = listOf(mapOf("title" to "Widget Alpha")),
            event = "completed",
        )

        for (method in listOf("scrape", "scrape_all", "export", "summary", "inspect", "readability", "query")) {
            val address = "https://example.com/article"
            val driver = tab(address)
            val captured = page(address)
            val session = sessionShowing(driver) {
                onBlocking { capture(driver, "$address -expires 0s") } doReturn captured
                on { parse(captured, true) } doReturn document(articleHtml, address)
            }
            val scrapeService = mock<ScrapeService> { on { executeQuery(any()) } doReturn response }
            val args = buildMap<String, Any?> {
                put("sessionId", "s")
                when (method) {
                    "scrape", "scrape_all" -> {
                        put("field", "text")
                        put("selector", "h1")
                    }

                    "query" -> put(
                        "sql", "SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, 'body')"
                    )
                }
            }

            assertNotNull(
                HTMLSnapshotToolExecutor(mock<PulsarSessionManager>(), scrapeService)
                    .call(method, args, ManagedSession("s", session, null)),
                "read '$method' must return a result",
            )

            try {
                verify(session).capture(driver, "$address -expires 0s", null)
            } catch (e: AssertionError) {
                throw AssertionError("read '$method' must capture the active page first: ${e.message}", e)
            }
            verify(session, never()).getOrNull(any<String>())
            verify(session, never()).load(any<String>())
        }
    }

    @Test
    @DisplayName("a read on a tab with nothing archivable is refused by name, without capturing")
    fun aReadOnAnUnarchivableTabIsRefusedByName() = runBlocking<Unit> {
        val bound = sessionShowing(tab("about:blank"))

        val exception = assertThrows<IllegalArgumentException> {
            runBlocking {
                executor().call(
                    "scrape",
                    mapOf("sessionId" to "s", "field" to "text", "selector" to "h1"),
                    ManagedSession("s", bound, null),
                )
            }
        }

        assertTrue(
            exception.message!!.contains("about:blank"),
            "the refusal must name what the tab shows, got: ${exception.message}",
        )
        verify(bound, never()).capture(any(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("readability of another url reads that url's stored copy and never captures the live tab")
    fun readabilityOfAnotherUrlNeverFilesTheLiveDocument() = runBlocking<Unit> {
        val stored = page(otherUrl)
        // The tab shows a different page, and the store has the requested url — which is exactly when
        // the old code captured the live tab and filed it under `otherUrl`.
        val session = sessionShowing(tab(liveUrl)) {
            on { getOrNull(otherUrl) } doReturn stored
            on { parse(stored, true) } doReturn document(articleHtml, otherUrl)
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
    @DisplayName("a read of a url the tab does not show loads it independently, without touching the tab")
    fun aReadOfAUrlTheTabDoesNotShowLoadsItReadOnly() = runBlocking<Unit> {
        val loaded = page(otherUrl)
        // A url the tab does not show cannot be captured, so when the store has nothing the only way
        // left is an independent, read-only load — and that load must NOT run on the session's tab: a
        // browser load resolves its driver from the page config, which inherits the session's bound
        // driver, so `session.load()` would navigate the page the caller is looking at.
        val session = sessionShowing(tab(liveUrl)) {
            on { getOrNull(otherUrl) } doReturn null
            on { parse(loaded, true) } doReturn document(articleHtml, otherUrl)
        }
        val swarmSession = sessionShowing(tab("about:blank")) {
            onBlocking { load("$otherUrl -readonly") } doReturn loaded
        }
        val sessionManager = mock<PulsarSessionManager> {
            on { ensureSwarmSession() } doReturn ManagedSession("swarm", swarmSession, null)
        }
        val executor = HTMLSnapshotToolExecutor(sessionManager)

        val payload = executor.call(
            "readability", mapOf("sessionId" to "s", "url" to otherUrl), ManagedSession("s", session, null),
        ) as String

        assertTrue(
            mapper.readTree(payload)["textContent"].asText().contains("Rust brings memory safety"),
            "the article must come from the independent load",
        )
        // The page was loaded on the shared scrape session (which has no bound driver, so the fetch
        // runs on a privacy-scoped one) …
        verify(swarmSession).load("$otherUrl -readonly")
        // … and the caller's session was used to read the store and parse the result, never to load.
        verify(session).getOrNull(otherUrl)
        verify(session, never()).load(any<String>())
        verify(session, never()).capture(any(), anyOrNull(), anyOrNull())
    }

    @Test
    @DisplayName("a url-targeted query of another page resolves no session and captures nothing")
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
        // A url-targeted query must not even look for a session: the url is not the page the session is
        // showing, so there is nothing to capture, and the arguments below deliberately carry no
        // sessionId to make a regression loud.
        val sessionManager = mock<PulsarSessionManager> {
            on { getOrRecoverSession(any()) } doThrow IllegalStateException("a url-targeted query must not resolve a session")
            on { getSession(any()) } doThrow IllegalStateException("a url-targeted query must not look up a session")
        }
        val executor = HTMLSnapshotToolExecutor(sessionManager, scrapeService)

        val payload = executor.callFunctionOn(
            "html_snapshot", "query",
            mapOf("sql" to "select dom_first_text(dom, 'h1') as title", "url" to "https://example.com"),
            Unit,
        ) as String

        assertEquals(true, mapper.readTree(payload)["isDone"].asBoolean())
        verify(sessionManager, never()).getOrRecoverSession(any())
        verify(sessionManager, never()).getSession(any())
    }

    @Test
    @DisplayName("a query that targets the page the session is showing refreshes it first")
    fun aQueryOfTheSessionsOwnPageRefreshesItFirst() = runBlocking<Unit> {
        val address = "https://example.com/article"
        val response = ScrapeResponse(
            id = "task-1",
            statusCode = 200,
            pageStatusCode = 200,
            pageContentBytes = 64,
            isDone = true,
            resultSet = listOf(mapOf("title" to "Widget Alpha")),
            event = "completed",
        )
        val scrapeService = mock<ScrapeService> { on { executeQuery(any()) } doReturn response }
        val driver = tab(address)
        val captured = page(address)
        val session = sessionShowing(driver) {
            onBlocking { capture(driver, "$address -expires 0s") } doReturn captured
        }
        // The session is found through the manager's NON-recovering lookup: a query may ask "is the
        // session on this url?" without resurrecting a browser to answer it.
        val sessionManager = mock<PulsarSessionManager> {
            on { getSession("s") } doReturn ManagedSession("s", session, null)
        }
        val executor = HTMLSnapshotToolExecutor(sessionManager, scrapeService)
        val sql = "SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, 'body')"

        val payload = executor.callFunctionOn(
            "html_snapshot", "query",
            mapOf("sessionId" to "s", "url" to address, "sql" to sql),
            Unit,
        ) as String

        assertTrue(mapper.readTree(payload)["isDone"].asBoolean())
        verify(session).capture(driver, "$address -expires 0s", null)
        // The X-SQL was pointed at the url that was just written — the same snapshot a read serves.
        val request = argumentCaptor<ScrapeRequest>()
        verify(scrapeService).executeQuery(request.capture())
        assertTrue(
            request.firstValue.sql.contains("'$address'"),
            "the query must target the captured page's url, got: ${request.firstValue.sql}",
        )
        verify(sessionManager, never()).getOrRecoverSession(any())
    }

    // =========================================================================
    // expires — how old a stored snapshot a read may serve
    // =========================================================================

    /** The live document the tab shows, distinct from anything in the store. */
    private val liveHtml = "<html><body><h1>Live</h1></body></html>"

    /** The stored snapshot of the same url — the previous version a store-only read must serve. */
    private val storedHtml = "<html><body><h1>Stored</h1></body></html>"

    @Test
    @DisplayName("expires defaults to 0s: a read ignores even a fresh stored copy and captures the live page")
    fun expiresDefaultsToZeroAndNeverServesAStoredCopy() = runBlocking<Unit> {
        val address = liveUrl
        val driver = tab(address)
        val captured = page(address)
        // The store HAS a copy of the active page, captured a minute ago — a store-first read would
        // find it perfectly fresh, which is exactly what the default must not do.
        val stored = page(address, fetchedAt = Instant.now().minusSeconds(60))
        val session = sessionShowing(driver) {
            on { getOrNull(address) } doReturn stored
            onBlocking { capture(driver, "$address -expires 0s") } doReturn captured
            on { parse(captured, true) } doReturn document(liveHtml, address)
            on { parse(stored, true) } doReturn document(storedHtml, address)
        }

        for (expires in listOf(null, "0s", "0m", "0ms")) {
            val args = buildMap<String, Any?> {
                put("sessionId", "s")
                put("field", "text")
                put("selector", "h1")
                if (expires != null) put("expires", expires)
            }
            assertEquals(
                "Live",
                executor().call("scrape", args, ManagedSession("s", session, null)) as String,
                "expires='$expires' must read the live page, not the stored copy",
            )
        }

        verify(session, never()).getOrNull(any<String>())
    }

    @Test
    @DisplayName("a positive expires serves the stored snapshot while it is fresh, without touching the tab")
    fun aPositiveExpiresServesTheFreshStoredSnapshot() = runBlocking<Unit> {
        val address = liveUrl
        val driver = tab(address)
        val stored = page(address, fetchedAt = Instant.now().minusSeconds(60))
        val session = sessionShowing(driver) {
            on { getOrNull(address) } doReturn stored
            on { parse(stored, true) } doReturn document(storedHtml, address)
        }

        val payload = executor().call(
            "scrape",
            mapOf("sessionId" to "s", "field" to "text", "selector" to "h1", "expires" to "1d"),
            ManagedSession("s", session, null),
        ) as String

        assertEquals("Stored", payload, "expires=1d must serve the snapshot the store has")
        // Store-only, on purpose: the point of the option is to work on the PREVIOUS snapshot version,
        // so neither the tab (capture) nor the network (load) may be touched.
        verify(session, never()).capture(any(), anyOrNull(), anyOrNull())
        verify(session, never()).load(any<String>())
    }

    @Test
    @DisplayName("a stored snapshot older than the expires window is replaced: the read captures the live page")
    fun aStaleStoredSnapshotIsReplaced() = runBlocking<Unit> {
        val address = liveUrl
        val driver = tab(address)
        val captured = page(address)
        // Two days old against a one-day window: expired, i.e. as good as missing.
        val stored = page(address, fetchedAt = Instant.now().minusSeconds(2 * 24 * 3600))
        val session = sessionShowing(driver) {
            on { getOrNull(address) } doReturn stored
            onBlocking { capture(driver, "$address -expires 0s") } doReturn captured
            on { parse(captured, true) } doReturn document(liveHtml, address)
        }

        val payload = executor().call(
            "scrape",
            mapOf("sessionId" to "s", "field" to "text", "selector" to "h1", "expires" to "1d"),
            ManagedSession("s", session, null),
        ) as String

        assertEquals("Live", payload)
        verify(session).getOrNull(address)
        verify(session).capture(driver, "$address -expires 0s", null)
    }

    @Test
    @DisplayName("an empty store falls back to the live page when expires is positive")
    fun anEmptyStoreFallsBackToTheLivePage() = runBlocking<Unit> {
        val address = liveUrl
        val driver = tab(address)
        val captured = page(address)
        val session = sessionShowing(driver) {
            on { getOrNull(address) } doReturn null
            onBlocking { capture(driver, "$address -expires 0s") } doReturn captured
            on { parse(captured, true) } doReturn document(liveHtml, address)
        }

        val payload = executor().call(
            "scrape",
            mapOf("sessionId" to "s", "field" to "text", "selector" to "h1", "expires" to "1d"),
            ManagedSession("s", session, null),
        ) as String

        assertEquals("Live", payload)
        verify(session).capture(driver, "$address -expires 0s", null)
    }

    @Test
    @DisplayName("every read honours expires: a fresh stored snapshot means no read captures")
    fun everyReadHonoursExpires() = runBlocking<Unit> {
        val response = ScrapeResponse(
            id = "task-1",
            statusCode = 200,
            pageStatusCode = 200,
            pageContentBytes = 64,
            isDone = true,
            resultSet = listOf(mapOf("title" to "Stored")),
            event = "completed",
        )

        for (method in listOf("scrape", "scrape_all", "export", "summary", "inspect", "readability", "query")) {
            val address = "https://example.com/article"
            val driver = tab(address)
            val stored = page(address, fetchedAt = Instant.now().minusSeconds(60))
            val session = sessionShowing(driver) {
                on { getOrNull(address) } doReturn stored
                on { parse(stored, true) } doReturn document(articleHtml, address)
            }
            val scrapeService = mock<ScrapeService> { on { executeQuery(any()) } doReturn response }
            val args = buildMap<String, Any?> {
                put("sessionId", "s")
                put("expires", "1d")
                when (method) {
                    "scrape", "scrape_all" -> {
                        put("field", "text")
                        put("selector", "h1")
                    }

                    "query" -> put(
                        "sql", "SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, 'body')"
                    )
                }
            }

            assertNotNull(
                HTMLSnapshotToolExecutor(mock<PulsarSessionManager>(), scrapeService)
                    .call(method, args, ManagedSession("s", session, null)),
                "read '$method' must return a result",
            )

            try {
                verify(session, never()).capture(any(), anyOrNull(), anyOrNull())
            } catch (e: AssertionError) {
                throw AssertionError("read '$method' must not capture with expires=1d: ${e.message}", e)
            }
            verify(session, never()).load(any<String>())
        }
    }

    @Test
    @DisplayName("a url-targeted query of the page on screen honours expires as well")
    fun aQueryOfTheSessionsOwnPageHonoursExpires() = runBlocking<Unit> {
        val address = "https://example.com/article"
        val response = ScrapeResponse(
            id = "task-1",
            statusCode = 200,
            pageStatusCode = 200,
            pageContentBytes = 64,
            isDone = true,
            resultSet = listOf(mapOf("title" to "Stored")),
            event = "completed",
        )
        val scrapeService = mock<ScrapeService> { on { executeQuery(any()) } doReturn response }
        val stored = page(address, fetchedAt = Instant.now().minusSeconds(60))
        val session = sessionShowing(tab(address)) {
            on { getOrNull(address) } doReturn stored
        }
        val sessionManager = mock<PulsarSessionManager> {
            on { getSession("s") } doReturn ManagedSession("s", session, null)
        }
        val sql = "SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, 'body')"

        val payload = HTMLSnapshotToolExecutor(sessionManager, scrapeService).callFunctionOn(
            "html_snapshot", "query",
            mapOf("sessionId" to "s", "url" to address, "sql" to sql, "expires" to "1d"),
            Unit,
        ) as String

        assertTrue(mapper.readTree(payload)["isDone"].asBoolean())
        verify(session, never()).capture(any(), anyOrNull(), anyOrNull())
        // The query is still pointed at the page's store identity, which is what it queried before.
        val request = argumentCaptor<ScrapeRequest>()
        verify(scrapeService).executeQuery(request.capture())
        assertTrue(
            request.firstValue.sql.contains("'$address'"),
            "the query must target the stored page's url, got: ${request.firstValue.sql}",
        )
    }

    @Test
    @DisplayName("expires never governs a url the tab does not show: that path stays store-first")
    fun expiresDoesNotGovernAForeignUrl() = runBlocking<Unit> {
        val stored = page(otherUrl)
        val session = sessionShowing(tab(liveUrl)) {
            on { getOrNull(otherUrl) } doReturn stored
            on { parse(stored, true) } doReturn document(articleHtml, otherUrl)
        }

        val payload = executor().call(
            "readability",
            mapOf("sessionId" to "s", "url" to otherUrl, "expires" to "1d"),
            ManagedSession("s", session, null),
        ) as String

        assertTrue(
            mapper.readTree(payload)["textContent"].asText().contains("Rust brings memory safety"),
            "the article must come from the requested url's stored copy",
        )
        // The foreign-url path reads the store whatever the age of the copy — an offline corpus
        // query must not start fetching the network because a window passed — and never captures.
        verify(session).getOrNull(otherUrl)
        verify(session, never()).capture(any(), anyOrNull(), anyOrNull())
        verify(session, never()).load(any<String>())
    }

    @Test
    @DisplayName("an unparsable expires value is rejected by name instead of silently meaning 0s")
    fun anUnparsableExpiresValueIsRejected() {
        for (bad in listOf("5", "1x", "abc", "1w", "1 y", "soon")) {
            val exception = assertThrows<IllegalArgumentException>("expires='$bad' must be rejected") {
                HTMLSnapshotToolExecutor.parseExpiresValue(bad, "scrape")
            }
            assertTrue(
                exception.message!!.contains(bad),
                "the refusal must name the value it refused, got: ${exception.message}",
            )
        }
    }

    @Test
    @DisplayName("expires values are read with the LoadOptions duration grammar")
    fun expiresValuesUseTheLoadOptionsGrammar() {
        // Absent and empty mean the default: never reuse a stored copy.
        assertEquals(Duration.ZERO, HTMLSnapshotToolExecutor.parseExpiresValue(null, "scrape"))
        assertEquals(Duration.ZERO, HTMLSnapshotToolExecutor.parseExpiresValue("", "scrape"))

        val expected = mapOf(
            "0s" to Duration.ZERO,
            "0" to Duration.ZERO, // "0" is read as "0s" by the CLI, and `Duration.ZERO` here
            "500ms" to Duration.ofMillis(500),
            "30s" to Duration.ofSeconds(30),
            "10m" to Duration.ofMinutes(10),
            "2h" to Duration.ofHours(2),
            "1d" to Duration.ofDays(1),
            "PT30S" to Duration.ofSeconds(30),
            "P1D" to Duration.ofDays(1),
            "PT1H30M" to Duration.ofMinutes(90),
            "1D" to Duration.ofDays(1),
        )
        for ((raw, duration) in expected) {
            assertEquals(
                duration,
                HTMLSnapshotToolExecutor.parseExpiresValue(raw, "scrape"),
                "expires='$raw'",
            )
        }
    }

    @Test
    @DisplayName("isExpiredFromStore applies exactly the expires rule of LoadOptions.isExpired")
    fun isExpiredFromStoreMatchesLoadOptions() {
        val now = Instant.now()
        // Ages chosen away from the window boundaries, so the two `Instant.now()` calls inside the
        // implementations can never disagree about a boundary case.
        val ages = listOf(0L, 30L, 900L, 7200L, 2 * 86400L)

        for (expires in listOf("0s", "1s", "1h", "1d", "30d")) {
            val duration = HTMLSnapshotToolExecutor.parseExpiresValue(expires, "scrape")
            val options = LoadOptions.parse("-expires $expires", VolatileConfig.UNSAFE)
            for (age in ages) {
                val prevFetchTime = now.minusSeconds(age)
                assertEquals(
                    options.isExpired(prevFetchTime),
                    HTMLSnapshotToolExecutor.isExpiredFromStore(prevFetchTime, duration),
                    "expires=$expires age=${age}s",
                )
            }
        }
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

    // =========================================================================
    // summary algorithms (pluggable PageSummaryAlgorithm SPI)
    // =========================================================================

    private fun sessionWithSnapshot(html: String, address: String = "https://example.com/article"):
        ManagedSession {
        val driver = tab(address)
        val captured = page(address)
        val session = sessionShowing(driver) {
            onBlocking { capture(driver, "$address -expires 0s") } doReturn captured
            on { parse(captured, true) } doReturn document(html, address)
        }
        return ManagedSession("s", session, null)
    }

    @Test
    @DisplayName("summary defaults to the built-in wpsi algorithm and accepts it explicitly")
    fun summaryDefaultsToWpsi() = runBlocking<Unit> {
        val managed = sessionWithSnapshot(articleHtml)

        val defaultOut = executor().call(
            "summary", mapOf("sessionId" to "s"), managed
        ) as String
        val explicitOut = executor().call(
            "summary", mapOf("sessionId" to "s", "algorithm" to "wpsi"), managed
        ) as String

        assertTrue(defaultOut.contains("page:"), "wpsi output should be YAML, got: $defaultOut")
        assertEquals(defaultOut, explicitOut)
    }

    @Test
    @DisplayName("summary invokes a plugin-contributed algorithm with the fresh snapshot input")
    fun summaryUsesContributedAlgorithm() = runBlocking<Unit> {
        val algorithm = MarkerAlgorithm()
        PageSummaryAlgorithmRegistry.instance.register(algorithm)
        val managed = sessionWithSnapshot(articleHtml)

        val out = executor().call(
            "summary", mapOf("sessionId" to "s", "algorithm" to "rest-marker"), managed
        ) as String

        assertEquals(1, algorithm.calls)
        assertEquals(
            "MARKER-SUMMARY|https://example.com/article|How Rust Conquered the Kernel", out
        )
    }

    @Test
    @DisplayName("summary with an unknown algorithm fails and the message lists available ids")
    fun summaryUnknownAlgorithmFailsWithAvailableIds() = runBlocking<Unit> {
        val managed = sessionWithSnapshot(articleHtml)

        val exception = assertThrows<IllegalArgumentException> {
            runBlocking {
                executor().call(
                    "summary", mapOf("sessionId" to "s", "algorithm" to "nope"), managed
                )
            }
        }
        assertTrue(exception.message!!.contains("nope"), exception.message)
        assertTrue(exception.message!!.contains("wpsi"), exception.message)
    }

    @Test
    @DisplayName("algorithms lists built-in and contributed algorithms and needs no session")
    fun algorithmsListsRegisteredOnesWithoutSession() = runBlocking<Unit> {
        PageSummaryAlgorithmRegistry.instance.register(MarkerAlgorithm())

        // A bare placeholder receiver (what CustomToolTargets passes when there is no session):
        // the method must read only the registry.
        val raw = HTMLSnapshotToolExecutor(mock<PulsarSessionManager>())
            .callFunctionOn("html_snapshot", "algorithms", emptyMap(), Any()) as String

        val array = mapper.readTree(raw)
        assertTrue(array.isArray)
        assertEquals(2, array.size())

        val byId = array.associateBy { it.get("id").asText() }
        val wpsi = byId.getValue("wpsi")
        assertTrue(wpsi.get("builtin").asBoolean())
        assertTrue(wpsi.get("default").asBoolean())
        assertTrue(wpsi.get("displayName").asText().isNotBlank())

        val marker = byId.getValue("rest-marker")
        assertFalse(marker.get("builtin").asBoolean())
        assertFalse(marker.get("default").asBoolean())
    }

    @Test
    @DisplayName("summary without algorithm uses the configured default algorithm")
    fun summaryUsesConfiguredDefaultAlgorithm() = runBlocking<Unit> {
        val algorithm = MarkerAlgorithm(id = "rest-default")
        PageSummaryAlgorithmRegistry.instance.register(algorithm)
        PageSummaryAlgorithmRegistry.instance.setDefaultId("rest-default")
        val managed = sessionWithSnapshot(articleHtml)

        val out = executor().call(
            "summary", mapOf("sessionId" to "s"), managed
        ) as String

        assertEquals(1, algorithm.calls)
        assertTrue(out.startsWith("MARKER-SUMMARY|"), out)
    }

    @Test
    @DisplayName("summary without algorithm fails fast when the configured default is not registered")
    fun summaryConfiguredDefaultMissingFailsFast() = runBlocking<Unit> {
        PageSummaryAlgorithmRegistry.instance.setDefaultId("not-installed")
        val managed = sessionWithSnapshot(articleHtml)

        val exception = assertThrows<IllegalArgumentException> {
            runBlocking {
                executor().call("summary", mapOf("sessionId" to "s"), managed)
            }
        }
        assertTrue(exception.message!!.contains("not-installed"), exception.message)
        assertTrue(
            exception.message!!.contains("browser4.htmlsnapshot.summary.algorithm"),
            exception.message
        )
        assertTrue(exception.message!!.contains("wpsi"), exception.message)
    }

    @Test
    @DisplayName("algorithms marks the configured default instead of hardcoded wpsi")
    fun algorithmsMarksConfiguredDefault() = runBlocking<Unit> {
        PageSummaryAlgorithmRegistry.instance.register(MarkerAlgorithm(id = "rest-default"))
        PageSummaryAlgorithmRegistry.instance.setDefaultId("rest-default")

        val raw = HTMLSnapshotToolExecutor(mock<PulsarSessionManager>())
            .callFunctionOn("html_snapshot", "algorithms", emptyMap(), Any()) as String

        val byId = mapper.readTree(raw).associateBy { it.get("id").asText() }
        assertFalse(byId.getValue("wpsi").get("default").asBoolean())
        assertTrue(byId.getValue("rest-default").get("default").asBoolean())
    }
}
