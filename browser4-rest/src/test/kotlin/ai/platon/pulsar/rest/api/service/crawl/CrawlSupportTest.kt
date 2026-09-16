package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.common.ResourceStatus
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
            status = "PROCESSING",
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
        assertEquals("PROCESSING", merged.status)
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
        val withDiagnostic = CrawlResponse(taskId = "t-merge", status = "PROCESSING", diagnostic = "no out-links")
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
