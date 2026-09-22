package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.context.PulsarContext
import ai.platon.pulsar.skeleton.session.PulsarSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import java.time.Instant

/**
 * Tests for the reporting helpers that used to be private members of
 * [CrawlService] and now live in `CrawlSupport.kt`.
 *
 * They are all pure functions over crawl data, so their contracts — URL
 * canonicalization, the title fallback, pattern filtering and the wording of
 * the loss/readonly notes the CLI surfaces — are pinned here instead of only
 * through a browser run.
 */
class CrawlSupportTest {

    // ------------------------------------------------------------------
    // normalizeForVisit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("URL canonicalization strips the fragment, the query and a trailing slash")
    fun testNormalizeForVisitCanonicalizes() {
        assertEquals(
            "https://example.com/product/1",
            normalizeForVisit("https://example.com/product/1")
        )
        assertEquals(
            "https://example.com/product/1",
            normalizeForVisit("  HTTPS://Example.com/product/1/  ")
        )
        // A query string or a fragment must not create a second identity for the
        // same page, or the crawl would submit it twice.
        assertEquals(
            normalizeForVisit("https://example.com/product/1?utm_source=x"),
            normalizeForVisit("https://example.com/product/1#details")
        )
    }

    @Test
    @DisplayName("a fragment-only href canonicalizes onto the portal URL it was resolved against")
    fun testNormalizeForVisitCollapsesFragmentOnlyHref() {
        // Resolving '#' against the portal appends it to the URL; the dedupe key
        // must be the fragment-less form or the crawl reports a hollow extra page.
        assertEquals(
            normalizeForVisit("https://example.com/hub.html"),
            normalizeForVisit("https://example.com/hub.html#")
        )
    }

    // ------------------------------------------------------------------
    // selectDiscoveredLinks
    // ------------------------------------------------------------------

    /** The duplicate hub fixture: five destinations offered through eight anchors. */
    private fun duplicateHubHrefs(): List<String> = listOf(
        "http://h/generated/crawl/product/1.html",
        "http://h/generated/crawl/product/1.html",
        "http://h/generated/crawl/product/2.html",
        "http://h/generated/crawl/product/2.html",
        "http://h/generated/crawl/product/3.html",
        "http://h/generated/crawl/product/4.html?src=grid",
        "http://h/generated/crawl/product/4.html?src=list",
        "http://h/generated/crawl/product/5.html#specs"
    )

    @Test
    @DisplayName("repeats on one page do not spend the -top-links budget")
    fun testRepeatsDoNotSpendTheBudget() {
        // The image and the title of a product are two anchors with one href, and
        // a grid/list toggle spells one page twice.  Taking the budget before the
        // repeats are gone spends three slots on two pages.
        val selection = selectDiscoveredLinks(
            hrefs = duplicateHubHrefs(),
            visited = emptySet(),
            outLinkPattern = null,
            topLinks = 3,
            ignoreUrlQuery = false
        )

        assertEquals(
            listOf(
                "http://h/generated/crawl/product/1.html",
                "http://h/generated/crawl/product/2.html",
                "http://h/generated/crawl/product/3.html"
            ),
            selection.links
        )
        assertEquals(3, selection.repeated, "two repeats of product/1 and one of product/4")
        assertEquals(0, selection.alreadyVisited)
        assertEquals(2, selection.overBudget, "product/4 and product/5 did not fit")
        assertEquals(5, selection.skipped)
    }

    @Test
    @DisplayName("the budget is spent on distinct pages the crawl has not seen yet")
    fun testBudgetSkipsVisitedIdentities() {
        // A visited candidate costs nothing — it must not consume a slot the next
        // fresh link needs, or a crawl shrinks as the round progresses.
        val selection = selectDiscoveredLinks(
            hrefs = duplicateHubHrefs(),
            visited = setOf(
                normalizeForVisit("http://h/generated/crawl/product/1.html"),
                normalizeForVisit("http://h/generated/crawl/product/2.html")
            ),
            outLinkPattern = null,
            topLinks = 3,
            ignoreUrlQuery = false
        )

        assertEquals(
            listOf(
                "http://h/generated/crawl/product/3.html",
                "http://h/generated/crawl/product/4.html?src=grid",
                "http://h/generated/crawl/product/5.html"
            ),
            selection.links
        )
        assertEquals(3, selection.repeated)
        assertEquals(2, selection.alreadyVisited)
        assertEquals(0, selection.overBudget)
    }

    @Test
    @DisplayName("one page offered twice is queued once, under the first spelling seen")
    fun testOnePageIsQueuedUnderItsFirstSpelling() {
        val selection = selectDiscoveredLinks(
            hrefs = listOf(
                "http://h/p/4.html?src=grid",
                "http://h/p/4.html?src=list",
                "http://h/p/5.html#specs"
            ),
            visited = emptySet(),
            outLinkPattern = null,
            topLinks = 20,
            ignoreUrlQuery = false
        )

        // The identity ignores the query (that is the documented dedup rule), so
        // the first spelling is the one the row will report.
        assertEquals(listOf("http://h/p/4.html?src=grid", "http://h/p/5.html"), selection.links)
        assertEquals(1, selection.repeated)
    }

    @Test
    @DisplayName("-ignoreUrlQuery strips the query from the href the crawl queues")
    fun testIgnoreUrlQueryStripsTheQueuedSpelling() {
        val selection = selectDiscoveredLinks(
            hrefs = listOf(
                "http://h/p/4.html?src=grid",
                "http://h/p/4.html?src=list",
                "http://h/p/5.html#specs"
            ),
            visited = emptySet(),
            outLinkPattern = null,
            topLinks = 20,
            ignoreUrlQuery = true
        )

        assertEquals(listOf("http://h/p/4.html", "http://h/p/5.html"), selection.links)
        assertEquals(1, selection.repeated)
    }

    @Test
    @DisplayName("a fragment never survives into the queued URL")
    fun testFragmentIsNeverQueued() {
        // A jump target inside a document is not a page.  The load path used to be
        // the only thing stripping it, which made a fragment-carrying url visible
        // to every earlier decision (dedup, pattern, budget) under a spelling the
        // crawl would never fetch.
        val selection = selectDiscoveredLinks(
            hrefs = listOf("http://h/p/1.html#reviews", "http://h/p/1.html"),
            visited = emptySet(),
            outLinkPattern = null,
            topLinks = 20,
            ignoreUrlQuery = false
        )

        assertEquals(listOf("http://h/p/1.html"), selection.links)
        assertEquals(1, selection.repeated, "the plain spelling is the same page")
    }

    @Test
    @DisplayName("the out-link pattern is matched against the spelling the crawl queues")
    fun testPatternSeesTheQueuedSpelling() {
        // -ignoreUrlQuery is applied before the pattern is matched, which is what
        // a pattern written against the stripped URL expects.  Matching the raw
        // href instead would let a query string decide whether a link is followed.
        val selection = selectDiscoveredLinks(
            hrefs = listOf("http://h/p/1.html?src=grid", "http://h/p/2.html"),
            visited = emptySet(),
            outLinkPattern = "src=grid",
            topLinks = 20,
            ignoreUrlQuery = true
        )

        assertTrue(selection.links.isEmpty(), "the stripped href must not match: ${selection.links}")
        // Rejected anchors are their own bucket: they are not repeats, and they
        // must not be reported as something the budget refused.
        assertEquals(2, selection.filtered)
        assertEquals(0, selection.repeated)
        assertEquals(0, selection.overBudget)
    }

    @Test
    @DisplayName("every anchor a page offered is either queued or accounted for")
    fun testCountersAccountForEveryAnchor() {
        // The log line reads "N of M anchors were not queued (…)", so the buckets
        // have to add up to M - links.size.  Without the filtered bucket a page
        // whose links were all rejected by the pattern reported "0 skipped".
        val hrefs = duplicateHubHrefs()
        val selection = selectDiscoveredLinks(
            hrefs = hrefs,
            visited = setOf(normalizeForVisit("http://h/generated/crawl/product/1.html")),
            outLinkPattern = "product/",
            topLinks = 2,
            ignoreUrlQuery = false
        )

        assertEquals(hrefs.size, selection.links.size + selection.skipped)
    }

    @Test
    @DisplayName("a blank or non-matching anchor list queues nothing and is not miscounted")
    fun testEmptyInputs() {
        val selection = selectDiscoveredLinks(
            hrefs = listOf("   ", ""),
            visited = emptySet(),
            outLinkPattern = null,
            topLinks = 20,
            ignoreUrlQuery = false
        )

        assertTrue(selection.links.isEmpty())
        assertEquals(0, selection.skipped)
    }

    @Test
    @DisplayName("-top-links 0 queues nothing but still reports what it refused")
    fun testZeroBudget() {
        val selection = selectDiscoveredLinks(
            hrefs = duplicateHubHrefs(),
            visited = emptySet(),
            outLinkPattern = null,
            topLinks = 0,
            ignoreUrlQuery = false
        )

        assertTrue(selection.links.isEmpty())
        assertEquals(0, selection.filtered)
        assertEquals(3, selection.repeated)
        assertEquals(5, selection.overBudget)
    }

    // ------------------------------------------------------------------
    // buildLinkArgs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a depth>=2 link load carries the flags that shape a discovered fetch")
    fun testLinkArgsForExpandedPages() {
        val options = LoadOptions.parse(
            "-outLink \"a.pick\" -outLinkPattern \"product/\" -refresh -readonly -ignoreUrlQuery -noNorm"
        )

        assertEquals(
            "-parse -outLink \"a.pick\" -outLinkPattern \"product/\" -refresh -readonly -ignoreUrlQuery -noNorm",
            buildLinkArgs(options, expandable = true)
        )
    }

    @Test
    @DisplayName("a depth-1 link load carries no out-link selector, but keeps the fetch flags")
    fun testLinkArgsForLeafPages() {
        // Nothing reads an out-link selector on a page whose children are not
        // followed, but -readonly / -ignoreUrlQuery / -noNorm have to reach the
        // load or the flag silently applies to the seed only.
        val options = LoadOptions.parse(
            "-outLink \"a.pick\" -outLinkPattern \"product/\" -refresh -readonly -ignoreUrlQuery -noNorm"
        )

        assertEquals("-parse -refresh -readonly -ignoreUrlQuery -noNorm", buildLinkArgs(options, expandable = false))
    }

    @Test
    @DisplayName("the catch-all out-link pattern is not forwarded")
    fun testCatchAllPatternIsNotForwarded() {
        val options = LoadOptions.parse("-outLink \"a\" -outLinkPattern \".+\" -refresh")

        assertEquals("-parse -outLink \"a\" -refresh", buildLinkArgs(options, expandable = true))
    }

    // ------------------------------------------------------------------
    // resolveQueueDepth
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a page keeps the depth it was queued at when it is served under another URL")
    fun testRedirectKeepsQueueDepth() {
        // http -> https: the document's base URI is the URL it landed on, which
        // this crawl never queued.  Reading the served URL first would report an
        // unknown depth (and the old code dropped the page entirely).
        val depths = mapOf(normalizeForVisit("http://example.com/") to 0)

        assertEquals(
            0,
            resolveQueueDepth("http://example.com/", "https://example.com/", depths)
        )
    }

    @Test
    @DisplayName("the submitted URL's depth wins over the URL the document was served under")
    fun testSubmittedUrlDepthWins() {
        // A site whose pages all share `<base href="/">`: every product page's
        // document base URI is the site root, which this crawl also queued — as
        // the seed.  The served URL must not label every product as depth 0, nor
        // make the seed's own row the only one recorded.
        val depths = mapOf(
            normalizeForVisit("https://example.com/") to 0,
            normalizeForVisit("https://example.com/product/1.html") to 1
        )

        assertEquals(
            1,
            resolveQueueDepth("https://example.com/product/1.html", "https://example.com/", depths)
        )
    }

    @Test
    @DisplayName("the served URL resolves the depth only as a fallback")
    fun testServedUrlIsTheFallback() {
        // The page was also discovered and queued directly under the URL it was
        // served from, so that queued depth is a legitimate answer.
        val depths = mapOf(normalizeForVisit("https://example.com/a") to 2)

        assertEquals(
            2,
            resolveQueueDepth("https://example.com/redirect-me", "https://example.com/a", depths)
        )
    }

    @Test
    @DisplayName("a document this crawl never queued reports an unknown depth, never a made-up one")
    fun testUnqueuedDocumentHasNoDepth() {
        // The caller records the page with UNKNOWN_DEPTH in this case: a null here
        // is a reporting gap, not a lost page, and must never be read as depth 0.
        assertNull(resolveQueueDepth("https://example.com/a", "https://cdn.example.com/b", emptyMap()))
        // A blank or absent base URI must not resolve onto the empty key either.
        assertNull(resolveQueueDepth("https://example.com/a", "", emptyMap()))
        assertNull(resolveQueueDepth("https://example.com/a", null, emptyMap()))
    }

    @Test
    @DisplayName("the depth lookup survives query, fragment and case drift")
    fun testDepthLookupIsCanonical() {
        val depths = mapOf(normalizeForVisit("https://example.com/product/1") to 3)

        assertEquals(3, resolveQueueDepth("HTTPS://Example.com/Product/1?utm=1", null, depths))
        assertEquals(3, resolveQueueDepth("https://example.com/product/1#details", null, depths))
        // The fallback canonicalizes too, or a redirect's trailing slash would
        // silence a depth the crawl does know.
        assertEquals(3, resolveQueueDepth("https://example.com/redirect-me", "HTTPS://Example.com/Product/1/", depths))
    }

    @Test
    @DisplayName("a trailing slash before a query or a fragment collapses onto the plain URL")
    fun testTrailingSlashBeforeQueryCollapses() {
        // normalizeForVisit strips the fragment and the query *before* it removes
        // the trailing slash.  It used to do it the other way round, so
        // "…/product/1/?utm=1" kept its slash while "…/product/1" lost its own:
        // one page, two identities, two submissions, two rows, two depths.  Every
        // crawl path derives its dedupe key from this one function, so the order
        // is the fix and it is pinned here.
        val plain = normalizeForVisit("https://example.com/product/1")
        assertEquals(plain, normalizeForVisit("https://example.com/product/1/"))
        assertEquals(plain, normalizeForVisit("https://example.com/product/1/?utm=1"))
        assertEquals(plain, normalizeForVisit("https://example.com/product/1/#details"))
        assertEquals(plain, normalizeForVisit("https://example.com/product/1/?utm=1#details"))

        // The depth lookup is the main consumer of this key: it inherits the
        // collapse, or the page would be queued twice with two depths.
        val depths = mapOf(plain to 3)
        assertEquals(3, resolveQueueDepth("https://example.com/product/1/?utm=1", null, depths))
        assertEquals(3, resolveQueueDepth("https://example.com/product/1/#details", null, depths))
        assertEquals(3, resolveQueueDepth("https://example.com/product/1/?utm=1/", null, depths))
    }

    @Test
    @DisplayName("a root URL keeps one identity with or without its slash")
    fun testRootUrlKeepsOneIdentity() {
        assertEquals("https://example.com", normalizeForVisit("https://example.com/"))
        assertEquals("https://example.com", normalizeForVisit("https://example.com/?q=1"))
        assertEquals("https://example.com", normalizeForVisit("https://example.com/#top"))
    }

    // ------------------------------------------------------------------
    // round budget (resolveRoundTimeoutMs / hasBudgetForRound)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a round budget is drawn from what the task has left, never from depth alone")
    fun testRoundBudgetIsDerivedFromTheTaskBudget() {
        // A fresh task grants the full per-depth budget…
        assertEquals(
            ROUND_BUDGET_PER_DEPTH_MS,
            resolveRoundTimeoutMs(depth = 1, remainingTaskBudgetMs = CrawlService.DEFAULT_TASK_TIMEOUT_MS)
        )
        // …and never more than the task has left, minus the margin the round needs
        // to snapshot its pages, close its session and publish its losses.
        assertEquals(
            120_000L,
            resolveRoundTimeoutMs(depth = 1, remainingTaskBudgetMs = 150_000L)
        )
    }

    @Test
    @DisplayName("a deep round budget stays inside the task limit instead of outliving it")
    fun testDeepRoundFitsInsideTheTaskLimit() {
        // The regression this replaces: `depth * 5 min` (capped at 30 min) is
        // >= the whole 10-minute task limit from depth 2 up, so the round could
        // only ever be killed by the task limit — and a killed round reports no
        // losses at all.  Every depth must now time out while it can still report.
        listOf(1, 2, 3, 10).forEach { depth ->
            val round = resolveRoundTimeoutMs(depth, CrawlService.DEFAULT_TASK_TIMEOUT_MS)
            assertTrue(
                round < CrawlService.DEFAULT_TASK_TIMEOUT_MS,
                "depth=$depth round budget ${round}ms must leave room inside the " +
                    "${CrawlService.DEFAULT_TASK_TIMEOUT_MS}ms task limit"
            )
            assertTrue(round > 0, "depth=$depth must still get a usable budget")
        }
        // The 30-minute ceiling still caps the depth product when the task limit
        // is raised: 100 levels do not buy 500 minutes.
        assertEquals(
            MAX_ROUND_BUDGET_MS,
            resolveRoundTimeoutMs(depth = 100, remainingTaskBudgetMs = 10 * MAX_ROUND_BUDGET_MS)
        )
    }

    @Test
    @DisplayName("a budget too small for a round is refused, not stretched")
    fun testBudgetGate() {
        // Just enough for a round plus its report: allowed.
        assertTrue(hasBudgetForRound(ROUND_REPORT_MARGIN_MS + MIN_ROUND_BUDGET_MS))
        // One millisecond short: the round is not started, the URL is reported.
        assertFalse(hasBudgetForRound(ROUND_REPORT_MARGIN_MS + MIN_ROUND_BUDGET_MS - 1))
        // An exhausted or never-armed clock: the reported budget is the floor,
        // which is why a round budget is never negative.
        assertEquals(MIN_ROUND_BUDGET_MS, resolveRoundTimeoutMs(depth = 3, remainingTaskBudgetMs = 0L))
        assertFalse(hasBudgetForRound(0L))
    }

    @Test
    @DisplayName("a seed that was never started is reported as one lost URL, not silently dropped")
    fun testUnstartedSeedRoundAccountsForItself() {
        val round = unstartedSeedRound("https://example.com/never-started")

        assertTrue(round.pages.isEmpty())
        assertEquals(1, round.pagesExpected)
        assertEquals(1, round.failedPages.size, "the seed must be reported, not dropped")
        assertEquals("https://example.com/never-started", round.failedPages.first().url)
        assertEquals(REASON_BUDGET_EXHAUSTED, round.failedPages.first().reason)
        assertTrue(round.timedOut, "a crawl that skipped a seed is a timed-out crawl, not an OK one")
        // The accounting law the CLI relies on.
        assertEquals(round.pagesExpected, round.pages.size + round.failedPages.size)
    }

    @Test
    @DisplayName("seeds whose rounds never returned are named as lost, one URL each")
    fun testUnfinishedSeedsAreReported() {
        val statuses = arrayOf<CrawlSeedStatus?>(
            CrawlSeedStatus("https://example.com/a", "fetched", 3),
            null,
            null
        )

        val losses = unfinishedSeedLosses(
            listOf("https://example.com/a", "https://example.com/b", "https://example.com/c"),
            statuses,
            REASON_TASK_LIMIT
        )

        assertEquals(
            listOf("https://example.com/b", "https://example.com/c"),
            losses.map { it.url },
            "only the seeds without a settled status are lost"
        )
        assertTrue(losses.all { it.reason == REASON_TASK_LIMIT })
    }

    // ------------------------------------------------------------------
    // isDocumentDelivered
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a page is only a page when the load delivered a document for it")
    fun testDocumentDelivery() {
        assertTrue(
            isDocumentDelivered(fetched = true, html = "<html><head><title>Widget</title></head></html>"),
            "a fetched page with HTML is delivered"
        )

        // A failed fetch is papered over by -ignoreFailure (which the crawl's
        // forced -refresh implies): the page keeps the store's content length and
        // a 200 status, but nothing was fetched for it this round.
        assertFalse(
            isDocumentDelivered(fetched = false, html = "<html><head><title>stored</title></head></html>"),
            "a stored copy substituted for a failed fetch is not a delivered page"
        )

        // A zero-byte fetch parses into an empty document.
        assertFalse(isDocumentDelivered(fetched = true, html = ""))
        assertFalse(isDocumentDelivered(fetched = true, html = "   "))
        assertFalse(isDocumentDelivered(fetched = true, html = null))
        assertFalse(isDocumentDelivered(fetched = false, html = null))
    }

    @Test
    @DisplayName("a read-only load answered from the page store delivered the page it was asked for")
    fun testReadOnlyStoreServeIsDelivered() {
        // `--readonly` asks for the stored copy — that is what makes the X-SQL engine's second read
        // a cache hit — so "not fetched this round" is not "not received".  The page carries the
        // store's content length and its age is reported per row.
        assertTrue(
            isDocumentDelivered(
                fetched = false, html = "<html><head><title>stored</title></head></html>", storeServed = true
            ),
            "a read-only round that the store answered has the page it asked for"
        )
        // Content is still the test: a stored copy with no body is not a page.
        assertFalse(isDocumentDelivered(fetched = false, html = "", storeServed = true))
        assertFalse(isDocumentDelivered(fetched = false, html = null, storeServed = true))
    }

    @Test
    @DisplayName("only a read-only round may treat a store hit as delivered")
    fun testStoreServeNeedsReadOnly() {
        val page = mock<WebPage>()
        whenever(page.isCached).thenReturn(true)

        assertTrue(isReadOnlyStoreServe(page, readonly = true))
        // A refreshed round substitutes the store copy for a *failed* fetch; recording that would
        // put a hollow row in the listing under a URL the crawl never received (§18).
        assertFalse(isReadOnlyStoreServe(page, readonly = false))

        val fetched = mock<WebPage>()
        whenever(fetched.isCached).thenReturn(false)
        assertFalse(isReadOnlyStoreServe(fetched, readonly = true))
    }

    // ------------------------------------------------------------------
    // extractTitleFromHtml
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the title fallback reads <title> out of raw HTML")
    fun testExtractTitleFromHtml() {
        assertEquals(
            "Crawl Test Hub",
            extractTitleFromHtml("<html><head><title>  Crawl Test Hub  </title></head></html>")
        )
        // Attributes and casing vary in the wild.
        assertEquals(
            "Widget",
            extractTitleFromHtml("""<TITLE lang="en" data-x="1">Widget</TITLE>""")
        )
    }

    @Test
    @DisplayName("the title fallback returns null instead of inventing a title")
    fun testExtractTitleFromHtmlReturnsNullWhenAbsent() {
        assertNull(extractTitleFromHtml(null))
        assertNull(extractTitleFromHtml(""))
        assertNull(extractTitleFromHtml("<html><body>no title here</body></html>"))
        assertNull(extractTitleFromHtml("<html><head><title>   </title></head></html>"))
    }

    // ------------------------------------------------------------------
    // matchesPattern
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank or catch-all out-link pattern admits every URL")
    fun testMatchesPatternAdmitsEverythingWithoutAPattern() {
        assertTrue(matchesPattern("https://example.com/a", null))
        assertTrue(matchesPattern("https://example.com/a", ""))
        assertTrue(matchesPattern("https://example.com/a", "   "))
        assertTrue(matchesPattern("https://example.com/a", ".+"))
    }

    @Test
    @DisplayName("an out-link pattern filters the URLs it does not match")
    fun testMatchesPatternFilters() {
        assertTrue(matchesPattern("https://example.com/product/1", "/product/"))
        assertFalse(matchesPattern("https://example.com/category/1", "/product/"))
    }

    @Test
    @DisplayName("an invalid pattern admits everything instead of emptying the crawl")
    fun testInvalidPatternIsNotAFilter() {
        // A broken regex must not silently turn a crawl into "no links found".
        assertTrue(matchesPattern("https://example.com/a", "([unclosed"))
    }

    // ------------------------------------------------------------------
    // releaseCrawlSession
    // ------------------------------------------------------------------

    @Test
    @DisplayName("releasing a round session deregisters it, not just closes it")
    fun testReleaseDeregistersTheSession() {
        val session = mock(PulsarSession::class.java)
        val context = mock(PulsarContext::class.java)

        assertNull(releaseCrawlSession(session, context), "a clean release reports no failure")

        // `closeSession` is what removes the session from the context registry;
        // `session.close()` alone would leave one dead entry per round behind.
        verify(context).closeSession(session)
        verify(session, never()).close()
    }

    @Test
    @DisplayName("a release that fails is reported instead of swallowed")
    fun testReleaseReportsTheFailure() {
        val session = mock(PulsarSession::class.java)
        val context = mock(PulsarContext::class.java)
        val failure = IllegalStateException("cannot unbind the driver")
        doThrow(failure).`when`(context).closeSession(session)

        // The caller logs this: the close is what unbinds browser/driver resources,
        // and after it fails nothing else owns the session.
        assertSame(failure, releaseCrawlSession(session, context))
    }

    // ------------------------------------------------------------------
    // mergeIncrementalProgress
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an incremental publish keeps the losses and totals already reported")
    fun testIncrementalPublishKeepsLossCounters() {
        val previous = CrawlResponse(
            taskId = "t-merge",
            status = CrawlStatus.PROCESSING,
            pagesFound = 1,
            pagesExpected = 3,
            failedPages = listOf(CrawlFailedPage("https://example.com/lost", 1, 408, "timeout")),
            parallelTabs = 4,
            maxConcurrentFetches = 2,
            seedStatuses = listOf(CrawlSeedStatus("https://example.com/", "fetched", 1)),
            startedTime = Instant.parse("2026-01-01T00:00:00Z")
        )

        val merged = requireNotNull(
            mergeIncrementalProgress(
                taskId = "t-merge",
                previous = previous,
                pages = listOf(CrawlPageResult("https://example.com/a"), CrawlPageResult("https://example.com/b")),
                linksDiscovered = 5,
                diagnostic = null,
                terminalStatuses = TERMINAL
            )
        )

        assertEquals(2, merged.pagesFound)
        assertEquals(5, merged.linksDiscovered)
        assertEquals(CrawlStatus.PROCESSING, merged.status)
        // The point of the merge: a page publish must not erase what the settled
        // seeds already reported, or the in-flight view under-reports loss.
        assertEquals(previous.failedPages, merged.failedPages)
        assertEquals(previous.pagesExpected, merged.pagesExpected)
        assertEquals(previous.parallelTabs, merged.parallelTabs)
        assertEquals(previous.maxConcurrentFetches, merged.maxConcurrentFetches)
        assertEquals(previous.seedStatuses, merged.seedStatuses)
        assertEquals(previous.startedTime, merged.startedTime)
        // Age drives the TTL purge: publishing progress must not make a task young again.
        assertEquals(previous.createdAt, merged.createdAt)
        // A diagnostic that is not supplied now is preserved, never erased.
        val withDiagnostic = CrawlResponse(taskId = "t-merge", status = CrawlStatus.PROCESSING, diagnostic = "no out-links")
        assertEquals(
            "no out-links",
            mergeIncrementalProgress("t-merge", withDiagnostic, emptyList(), 0, null, TERMINAL)?.diagnostic
        )
    }

    @Test
    @DisplayName("an incremental publish never revives a finished task")
    fun testIncrementalPublishRefusesATerminalTask() {
        // A parse handler can outlive the round that submitted it (round timeout,
        // cancellation).  Writing PROCESSING over the TIMEOUT record would leave the
        // poller waiting for a task that nobody will finalize again.
        val timedOut = CrawlResponse(
            taskId = "t-done",
            status = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            pagesFound = 3
        )

        assertNull(
            mergeIncrementalProgress("t-done", timedOut, emptyList(), 0, null, TERMINAL),
            "a terminal record must be left alone"
        )
    }

    // ------------------------------------------------------------------
    // aggregateInFlightPages
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the in-flight view sums the rounds that published, it does not follow the last one")
    fun testAggregateInFlightPagesSumsTheRounds() {
        // Round 0 published two pages, round 1 published one: the record a poller
        // reads must count three.  Reporting only the round that published last is
        // what made a real crawl's count fall back from 3 to 1.
        val published = mutableMapOf(
            0 to listOf(CrawlPageResult("https://example.com/a"), CrawlPageResult("https://example.com/b")),
            1 to listOf(CrawlPageResult("https://example.com/c"))
        )
        assertEquals(3, aggregateInFlightPages(published).size)

        // A round only ever grows, so its latest publish replaces its own earlier
        // one rather than piling up snapshots of the same page.
        published[0] = published.getValue(0) + CrawlPageResult("https://example.com/d")
        assertEquals(4, aggregateInFlightPages(published).size)
    }

    @Test
    @DisplayName("two seeds that fetched one URL are two rows in flight, as they are in the result")
    fun testAggregateIsNotAUrlUnion() {
        // The terminal record keeps one row per fetch, so a URL two seeds both
        // collected is two rows.  A URL union here would make the in-flight count
        // smaller than the terminal one — the same "the count went down" symptom,
        // deferred to the end of the crawl.
        val shared = mapOf(
            0 to listOf(CrawlPageResult("https://example.com/a")),
            1 to listOf(CrawlPageResult("https://example.com/a"))
        )

        assertEquals(2, aggregateInFlightPages(shared).size)
    }

    @Test
    @DisplayName("the aggregate is reported in seed order")
    fun testAggregateFollowsSeedOrder() {
        val published = mapOf(
            2 to listOf(CrawlPageResult("https://example.com/c")),
            0 to listOf(CrawlPageResult("https://example.com/a")),
            1 to listOf(CrawlPageResult("https://example.com/b"))
        )

        assertEquals(
            listOf("https://example.com/a", "https://example.com/b", "https://example.com/c"),
            aggregateInFlightPages(published).map { it.url }
        )
    }

    // ------------------------------------------------------------------
    // resolveRoundArgs
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a crawl still refreshes by default, so a stale stored page cannot empty the crawl")
    fun testRoundArgsRefreshByDefault() {
        assertEquals("-refresh", resolveRoundArgs(""))
        assertEquals("-refresh", resolveRoundArgs("   "))
        assertEquals("-outLink \"a.pick\" -refresh", resolveRoundArgs("-outLink \"a.pick\" -refresh"))
        assertEquals("-outLink \"a.pick\" -refresh", resolveRoundArgs("-outLink \"a.pick\""))
        // Any spelling counts, or the crawl would add a second, redundant refresh.
        assertEquals("--refresh -topLinks 3", resolveRoundArgs("--refresh -topLinks 3"))
    }

    @Test
    @DisplayName("-readonly wins over -refresh, in either order and under any spelling")
    fun testReadOnlyWinsOverRefresh() {
        // The X-SQL second read is a guaranteed cache hit only while the page is local, and
        // `-refresh` (= -ignoreFailure -i 0s) makes every local copy look expired. The two options
        // mean opposite things, so the read-only one decides.
        assertEquals("-readonly", resolveRoundArgs("-readonly -refresh"))
        assertEquals("-readonly", resolveRoundArgs("-refresh -readonly"))
        assertEquals("-readonly", resolveRoundArgs("-readonly -refresh=true"))
        // A read-only crawl must not gain a refresh it did not ask for.
        assertEquals("-readonly", resolveRoundArgs("-readonly"))
        assertEquals(
            "-outLink \"a.pick\" -topLinks 3 -readonly",
            resolveRoundArgs("-outLink \"a.pick\" -topLinks 3 -readonly -refresh")
        )
        // Whatever the caller asked for besides refresh is left exactly as it was.
        assertEquals(
            "-expires 1d -readonly -parse",
            resolveRoundArgs("-expires 1d -readonly -parse -refresh")
        )
    }

    @Test
    @DisplayName("an option value is not an option: a selector survives a strip")
    fun testOptionValuesSurviveTheStrip() {
        // The tokens are split on whitespace, so a quoted selector is its own token; a strip that
        // matched substrings would corrupt it.
        assertEquals(
            "-outLink \"a.refresh.js\" -readonly",
            resolveRoundArgs("-outLink \"a.refresh.js\" -readonly -refresh")
        )
        // `-refresh` inside a pattern value is not the option either.
        assertEquals(
            "-outLinkPattern refresh -readonly",
            resolveRoundArgs("-outLinkPattern refresh -readonly -refresh")
        )
    }

    // ------------------------------------------------------------------
    // delivery attempts: resolveDeliveryAttempt / lossReasonForLoaded
    // ------------------------------------------------------------------

    /** The facts of a load that went well and delivered content, unless changed. */
    private fun facts(
        url: String = "https://example.com/page.html",
        fetched: Boolean = true,
        canceled: Boolean = false,
        nil: Boolean = false,
        retry: Boolean = false,
        failed: Boolean = false,
        success: Boolean = true,
        statusCode: Int = 200,
        statusReason: String? = null,
        contentLength: Long = 1024,
    ) = LoadedPageFacts(
        url, fetched, canceled, nil, retry, failed, success, statusCode, statusReason, contentLength
    )

    @Test
    @DisplayName("a load that delivered no document is retried once, and its loss is not settled yet")
    fun testDeliveryFailureIsRetriedOnce() {
        val ledger = CrawlLedger("t-retry")
        val url = "https://example.com/flaky.html"
        ledger.submit(url, 1)
        val attempt = ledger.beginAttempt(url)
        // The parse event saw the load and carried an empty document.  That is what this call
        // is handed; it is also what tells it "a parse event fired and delivered nothing".
        val emptyDeliveries = mutableSetOf(normalizeForVisit(url))

        val next = resolveDeliveryAttempt(
            ledger, url, 1, attempt, facts(url = url, contentLength = 0), emptyDeliveries
        )

        assertEquals(2L, next, "the first delivery failure is worth one more load")
        assertEquals(0, ledger.settled, "nothing is settled while the retry is on its way")
        assertEquals(1, ledger.pagesExpected, "a retry is not another page")
        assertTrue(ledger.failedPages().isEmpty(), "the withdrawn loss is not reported")
        assertFalse(ledger.isComplete)
        assertTrue(ledger.isCurrentAttempt(url, requireNotNull(next)))
    }

    @Test
    @DisplayName("a retry that also delivered nothing is reported as lost")
    fun testSecondFailureIsReportedAsLost() {
        val ledger = CrawlLedger("t-retry-lost")
        val url = "https://example.com/flaky.html"
        ledger.submit(url, 1)
        ledger.beginAttempt(url)
        val retry = requireNotNull(ledger.startRetry(url))

        val next = resolveDeliveryAttempt(
            ledger, url, 1, retry, facts(url = url, contentLength = 0), mutableSetOf(normalizeForVisit(url))
        )

        assertNull(next, "the URL has no attempt left")
        assertEquals(1, ledger.settled)
        val lost = ledger.failedPages().single()
        assertEquals(CrawlLedger.REASON_NOT_DELIVERED, lost.reason)
        assertEquals(url, lost.url)
        assertEquals(1, lost.depth)
    }

    @Test
    @DisplayName("a load the engine is still retrying is waited for, never retried by the crawl")
    fun testEngineScheduledRetryIsWaitedFor() {
        val ledger = CrawlLedger("t-engine-retry")
        val url = "https://example.com/flaky.html"
        ledger.submit(url, 1)
        val attempt = ledger.beginAttempt(url)

        // A failed fetch the engine classifies as retryable comes back with a retry status, and
        // the engine schedules the next load itself (37-45s later, as the probe runs show).  The
        // crawl must neither settle the URL nor spend an attempt of its own on it.
        val next = resolveDeliveryAttempt(
            ledger, url, 1, attempt,
            facts(url = url, fetched = false, success = false, retry = true, statusCode = 1601),
            mutableSetOf()
        )

        assertNull(next)
        assertEquals(0, ledger.settled)
        assertEquals(1, ledger.attemptCount(url), "the second load is the engine's, not the crawl's")
        assertTrue(ledger.failedPages().isEmpty())
        assertFalse(ledger.isComplete, "the round keeps waiting for the load the engine scheduled")
    }

    @Test
    @DisplayName("an attempt a retry has superseded settles nothing")
    fun testSupersededAttemptSettlesNothing() {
        val ledger = CrawlLedger("t-stale-attempt")
        val url = "https://example.com/flaky.html"
        ledger.submit(url, 1)
        val first = ledger.beginAttempt(url)
        val retry = requireNotNull(ledger.startRetry(url))

        // The failed attempt's load event arrives after the retry was submitted.
        val next = resolveDeliveryAttempt(
            ledger, url, 1, first, facts(url = url, contentLength = 0), mutableSetOf(normalizeForVisit(url))
        )

        assertNull(next)
        assertEquals(0, ledger.settled, "the retry's URL is still outstanding")
        assertTrue(ledger.failedPages().isEmpty())
        assertTrue(ledger.isCurrentAttempt(url, retry))
    }

    @Test
    @DisplayName("a terminal fetch failure is retried once, and the retry's own reason is reported")
    fun testTerminalFetchFailureIsRetriedOnce() {
        val ledger = CrawlLedger("t-fetch-failed")
        val url = "https://example.com/dead.html"
        ledger.submit(url, 1)
        val attempt = ledger.beginAttempt(url)
        // The engine classified this one as failed, so no retry of its own is coming and no
        // parse event ever fired.
        val page = facts(
            url = url, fetched = false, success = false, failed = true,
            statusCode = 1604, statusReason = "not found", contentLength = 0
        )

        val retry = resolveDeliveryAttempt(ledger, url, 1, attempt, page, mutableSetOf())

        assertEquals(2L, retry, "a finished failure is still worth one more load")
        assertEquals(0, ledger.settled, "the loss is withdrawn while the retry runs")
        assertTrue(ledger.failedPages().isEmpty())

        // The retry fails the same way: now it is reported, with the reason of the attempt that
        // actually settled the URL.
        val next = resolveDeliveryAttempt(ledger, url, 1, requireNotNull(retry), page, mutableSetOf())

        assertNull(next)
        val lost = ledger.failedPages().single()
        assertEquals("not found", lost.reason)
        assertEquals(1604, lost.protocolStatus)
    }

    @Test
    @DisplayName("a page that arrived but never reached the parser is reported, not re-fetched")
    fun testPageWithoutParseEventIsNotRefetched() {
        val ledger = CrawlLedger("t-not-parsed")
        val url = "https://example.com/no-parse.html"
        ledger.submit(url, 1)
        val attempt = ledger.beginAttempt(url)

        val next = resolveDeliveryAttempt(ledger, url, 1, attempt, facts(url = url), mutableSetOf())

        assertNull(next, "the content is already here: loading it again buys nothing")
        assertEquals(1, ledger.attemptCount(url))
        assertEquals(CrawlLedger.REASON_NOT_PARSED, ledger.failedPages().single().reason)
    }

    @Test
    @DisplayName("a zero-byte page that never reached the parser is a delivery failure, so it is retried")
    fun testZeroBytePageWithoutParseEventIsRetried() {
        val ledger = CrawlLedger("t-zero-byte")
        val url = "https://example.com/zero.html"
        ledger.submit(url, 1)
        val attempt = ledger.beginAttempt(url)

        val next = resolveDeliveryAttempt(
            ledger, url, 1, attempt, facts(url = url, contentLength = 0), mutableSetOf()
        )

        assertEquals(2L, next, "nothing arrived, whatever the status said")
        assertEquals(0, ledger.settled)
        assertTrue(ledger.failedPages().isEmpty())
    }

    @Test
    @DisplayName("a page whose row was recorded settles nothing, however late its load event is")
    fun testDeliveredPageSettlesNothing() {
        val ledger = CrawlLedger("t-delivered")
        val url = "https://example.com/ok.html"
        ledger.submit(url, 1)
        val attempt = ledger.beginAttempt(url)
        ledger.recordSuccess(url)

        val next = resolveDeliveryAttempt(ledger, url, 1, attempt, facts(url = url), mutableSetOf())

        assertNull(next)
        assertEquals(1, ledger.settled)
        assertTrue(ledger.failedPages().isEmpty())
        assertTrue(ledger.isComplete)
    }

    // ------------------------------------------------------------------
    // resolveRequestTaskTimeout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a request with no budget of its own runs under the server's default")
    fun testAbsentTaskBudgetUsesTheServerDefault() {
        assertEquals(600_000L, resolveRequestTaskTimeout(null, 600_000L))
        // A budget is a limit, not a switch: a non-positive value means "no preference".
        assertEquals(600_000L, resolveRequestTaskTimeout(0L, 600_000L))
        assertEquals(600_000L, resolveRequestTaskTimeout(-5L, 600_000L))
    }

    @Test
    @DisplayName("a requested budget is honoured, and clamped to the per-request range")
    fun testRequestedTaskBudgetIsClamped() {
        assertEquals(120_000L, resolveRequestTaskTimeout(120_000L, 600_000L))
        // Above the server default is allowed — the default is a policy, not a grant ...
        assertEquals(1_800_000L, resolveRequestTaskTimeout(1_800_000L, 600_000L))
        // ... but not without bound: one crawl may not hold browsers for a day.
        assertEquals(
            MAX_REQUEST_TASK_TIMEOUT_MS,
            resolveRequestTaskTimeout(24 * 3_600_000L, 600_000L)
        )
        // Below the floor is raised to it, so the clock a task arms is never zero-length.
        assertEquals(MIN_REQUEST_TASK_TIMEOUT_MS, resolveRequestTaskTimeout(1L, 600_000L))
    }

    // ------------------------------------------------------------------
    // buildLossNote
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a crawl that lost nothing has no loss note")
    fun testNoLossesProduceNoNote() {
        assertNull(buildLossNote(pagesFound = 10, pagesExpected = 10, failedPages = emptyList()))
    }

    @Test
    @DisplayName("the loss note names the lost pages and their reason")
    fun testLossNoteNamesTheLosses() {
        val note = buildLossNote(
            pagesFound = 8,
            pagesExpected = 10,
            failedPages = listOf(
                CrawlFailedPage("https://example.com/2.html", 1, 1601, "refused"),
                CrawlFailedPage("https://example.com/7.html", 2, 0)
            )
        )

        requireNotNull(note)
        assertTrue(note.contains("2 of 10 page(s) were submitted but never delivered (8 recorded)"), note)
        assertTrue(note.contains("https://example.com/2.html (depth=1, status=1601, refused)"), note)
        // A page with no protocol status must not print a fake "status=0".
        assertTrue(note.contains("https://example.com/7.html (depth=2)"), note)
    }

    @Test
    @DisplayName("the loss note caps the URLs it spells out but never the count")
    fun testLossNoteIsCappedButExact() {
        val failed = (1..8).map { CrawlFailedPage("https://example.com/$it.html", 1, 0) }

        val note = requireNotNull(buildLossNote(pagesFound = 2, pagesExpected = 10, failedPages = failed))

        assertTrue(note.contains("8 of 10 page(s)"), note)
        assertTrue(note.contains("(+3 more)"), "the omitted pages are still accounted for: $note")
        assertFalse(note.contains("https://example.com/6.html"), "only the first five URLs are named: $note")
    }

    // ------------------------------------------------------------------
    // buildReadonlyNote
    // ------------------------------------------------------------------

    @Test
    @DisplayName("--readonly says so when every page was fetched fresh")
    fun testReadonlyNoteWhenEverythingWasFresh() {
        val note = buildReadonlyNote(listOf(CrawlPageResult("https://example.com/a")))

        assertTrue(note.startsWith("readonly: verified fresh"), note)
        assertTrue(note.contains("all 1 page(s) fetched from the live site"), note)
    }

    @Test
    @DisplayName("--readonly reports how much was served from the store, and how old it was")
    fun testReadonlyNoteWhenPagesCameFromTheStore() {
        val note = buildReadonlyNote(
            listOf(
                CrawlPageResult("https://example.com/a", servedFromStore = true, storeAgeSeconds = 3661),
                CrawlPageResult("https://example.com/b", servedFromStore = true, storeAgeSeconds = 30),
                CrawlPageResult("https://example.com/c")
            )
        )

        assertTrue(note.contains("2/3 page(s) served from the page store"), note)
        // The oldest stored content is the one that bounds how fresh the crawl is.
        assertTrue(note.contains("(stored content up to 1h 1m old)"), note)
        assertTrue(note.contains("1 fetched fresh"), note)
    }

    private companion object {
        /** The statuses [CrawlService] treats as terminal, built the way it builds them. */
        val TERMINAL = setOf(
            ResourceStatus.getStatusText(ResourceStatus.SC_OK),
            ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR)
        )
    }
}
