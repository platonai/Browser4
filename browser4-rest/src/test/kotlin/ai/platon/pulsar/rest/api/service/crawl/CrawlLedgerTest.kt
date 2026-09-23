package ai.platon.pulsar.rest.api.service.crawl

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The settle contract of a crawl round (issue #592).
 *
 * A round may only complete when every submitted URL produced a page or a
 * reported failure, and no parse handler is still running.  These tests encode
 * the two failures that contract prevents:
 *
 *  1. A round completing while a handler is still discovering links, so the
 *     links it was about to submit are never fetched ("expected 10 pages, got
 *     8", status=OK).
 *  2. A fetch that fails being invisible, so the round either waits forever for
 *     a page that is not coming, or reports a page count smaller than the
 *     number of pages it set out to fetch.
 */
class CrawlLedgerTest {

    @Test
    @DisplayName("a round completes when every submitted URL is settled")
    fun roundCompletesWhenEverySubmittedUrlIsSettled() {
        val ledger = CrawlLedger("t-round")

        ledger.submit("https://example.com/a", 0)
        ledger.submit("https://example.com/b", 1)
        assertEquals(2, ledger.pagesExpected)

        ledger.recordSuccess("https://example.com/a")
        assertFalse(ledger.isTerminal, "one URL is still outstanding")

        ledger.recordFailure("https://example.com/b", 1, 408, "boom")
        assertTrue(ledger.isComplete, "both URLs settled")
        assertTrue(ledger.isTerminal)

        assertEquals(1, ledger.failedPages().size)
        assertEquals("https://example.com/b", ledger.failedPages().first().url)
        assertEquals(408, ledger.failedPages().first().protocolStatus)
    }

    /**
     * A load that returns no document of its own (a zero-byte fetch, or the page
     * store substituted for a failed fetch) must be reported as lost rather than
     * recorded as a hollow row.  The reason travels with it so the caller can
     * tell it from a fetch that failed and from a page that never parsed.
     */
    @Test
    @DisplayName("a load that delivered no document is reported as lost with its own reason")
    fun notDeliveredLoadIsReportedAsLost() {
        val ledger = CrawlLedger("t-not-delivered")

        ledger.submit("https://example.com/hollow.html", 1)
        ledger.recordFailure(
            "https://example.com/hollow.html", 1, 200, CrawlLedger.REASON_NOT_DELIVERED
        )

        assertTrue(ledger.isComplete, "the URL is settled by the loss, not left outstanding")
        val failed = ledger.failedPages().single()
        assertEquals(CrawlLedger.REASON_NOT_DELIVERED, failed.reason)
        assertEquals("https://example.com/hollow.html", failed.url)
        assertEquals(1, failed.depth)
        assertEquals(200, failed.protocolStatus, "the status stays 200 — that is why the row looked fine")
    }

    /**
     * The regression test for "expected 10 pages, got 8": the round must not
     * complete while a handler that has not submitted its children yet is in
     * flight, even though every page submitted so far has settled.
     */
    @Test
    @DisplayName("a handler in flight keeps the round open, so its children are not lost")
    fun aHandlerInFlightKeepsTheRoundOpen() {
        val ledger = CrawlLedger("t-inflight")

        // Seed handler is running and has already recorded its page.
        ledger.submit("https://example.com/seed", 0)
        assertTrue(ledger.enter())
        ledger.recordSuccess("https://example.com/seed")

        // Another page settles; without the in-flight gate the counters would
        // now be equal and the round would be declared complete.
        ledger.submit("https://example.com/other", 1)
        ledger.recordSuccess("https://example.com/other")
        assertFalse(ledger.isTerminal, "a parse handler is still running")

        // Only now does the seed handler submit the links it discovered.
        ledger.submit("https://example.com/child-1", 1)
        ledger.submit("https://example.com/child-2", 1)
        ledger.leave()

        assertFalse(ledger.isTerminal, "the two children have not settled yet")
        assertEquals(4, ledger.pagesExpected)

        ledger.recordSuccess("https://example.com/child-1")
        ledger.recordSuccess("https://example.com/child-2")
        assertTrue(ledger.isComplete)
    }

    @Test
    @DisplayName("work is refused once the round is terminal")
    fun lateWorkIsRefusedAfterTheRoundCompleted() {
        val ledger = CrawlLedger("t-late")
        ledger.submit("https://example.com/seed", 0)
        ledger.recordSuccess("https://example.com/seed")
        assertTrue(ledger.isTerminal)

        // A duplicate parse event that arrives minutes later must not submit
        // links for a task the caller has already been told is finished.
        assertFalse(ledger.enter(), "no new handler may start")
        assertFalse(ledger.submit("https://example.com/late", 2), "no new URL may be queued")
        assertEquals(1, ledger.pagesExpected)
        assertEquals(1, ledger.settled)
    }

    @Test
    @DisplayName("a failure is reported once, however many load attempts fail")
    fun duplicateFailuresAreIdempotent() {
        val ledger = CrawlLedger("t-dup")
        ledger.submit("https://example.com/seed", 0)
        ledger.submit("https://example.com/flaky", 1)

        // The onLoaded event fires once per attempt, including every retry.
        ledger.recordFailure("https://example.com/flaky", 1, 1601, "retry budget exhausted")
        ledger.recordFailure("https://example.com/flaky", 1, 1601, "retry budget exhausted")
        ledger.recordFailure("https://example.com/flaky", 1, 408, "gone")

        assertEquals(1, ledger.settled, "the URL settled exactly once")
        assertEquals(1, ledger.failedPages().size)
        assertEquals(
            "retry budget exhausted", ledger.failedPages().first().reason,
            "the first reason wins — a later retry must not overwrite the cause"
        )

        ledger.recordSuccess("https://example.com/seed")
        assertTrue(ledger.isComplete)
    }

    @Test
    @DisplayName("a delivery failure can be retried once, and the loss is withdrawn while the retry runs")
    fun aDeliveryFailureIsWorthOneRetry() {
        val ledger = CrawlLedger("t-retry")
        val url = "https://example.com/flaky"
        ledger.submit(url, 1)
        val first = ledger.beginAttempt(url)
        assertEquals(1, ledger.attemptCount(url))
        assertTrue(ledger.isCurrentAttempt(url, first))

        // The load event of the failed attempt runs while the round is still open —
        // CrawlRoundRunner holds it with enter/leave, because that event is what
        // submits the retry.
        assertTrue(ledger.enter())
        ledger.recordFailure(url, 1, 200, CrawlLedger.REASON_NOT_DELIVERED)
        val retry = requireNotNull(ledger.startRetry(url)) { "the first failure is worth one retry" }
        ledger.leave()

        assertFalse(ledger.isComplete, "the round waits for the retry instead of reporting the loss")
        assertEquals(0, ledger.settled, "the withdrawn loss is not settled")
        assertEquals(1, ledger.pagesExpected, "a retry is not another page")
        assertTrue(ledger.failedPages().isEmpty(), "a withdrawn failure is not reported")
        assertEquals(2, ledger.attemptCount(url))
        assertFalse(ledger.isCurrentAttempt(url, first), "the failed attempt is stale")
        assertTrue(ledger.isCurrentAttempt(url, retry))

        // The retry delivers: the URL settles once, as a page.
        ledger.recordSuccess(url)
        assertTrue(ledger.isComplete)
        assertEquals(1, ledger.settled)
    }

    @Test
    @DisplayName("a URL gets exactly one retry, and its failure is the one reported")
    fun aUrlGetsExactlyOneRetry() {
        val ledger = CrawlLedger("t-retry-budget")
        val url = "https://example.com/flaky"
        ledger.submit(url, 1)
        ledger.beginAttempt(url)
        assertTrue(ledger.enter())
        ledger.recordFailure(url, 1, 200, CrawlLedger.REASON_NOT_DELIVERED)
        requireNotNull(ledger.startRetry(url))
        assertNull(ledger.startRetry(url), "the budget is one retry, not two")

        // The retry fails as well: its outcome is what the crawl reports, because the
        // withdrawn failure cannot be the reason for a loss that happened later.
        ledger.recordFailure(url, 1, 404, "the server answered 404")
        ledger.leave()

        assertTrue(ledger.isComplete)
        assertEquals(1, ledger.settled)
        assertEquals("the server answered 404", ledger.failedPages().single().reason)
    }

    @Test
    @DisplayName("events of a superseded attempt settle nothing")
    fun eventsOfASupersededAttemptSettleNothing() {
        val ledger = CrawlLedger("t-stale")
        val url = "https://example.com/flaky"
        ledger.submit(url, 1)
        val first = ledger.beginAttempt(url)
        assertTrue(ledger.enter())
        val retry = requireNotNull(ledger.startRetry(url))
        ledger.leave()

        // The failed attempt keeps reporting (a duplicate parse event, the load event
        // that fires after it): none of it may settle a URL the round is reloading.
        assertFalse(ledger.isCurrentAttempt(url, first))
        assertTrue(ledger.isCurrentAttempt(url, retry))
        assertEquals(0, ledger.settled, "the retry's URL is still outstanding")
        assertTrue(ledger.failedPages().isEmpty())

        ledger.recordSuccess(url)
        assertTrue(ledger.isComplete)
        assertEquals(1, ledger.settled, "the URL settled once, under the retry")
    }

    @Test
    @DisplayName("a round that already completed is not re-opened for a retry")
    fun retriesAreRefusedAfterTheRoundCompleted() {
        val ledger = CrawlLedger("t-retry-terminal")
        val url = "https://example.com/flaky"
        ledger.submit(url, 1)
        ledger.beginAttempt(url)
        ledger.recordFailure(url, 1, 200, "boom")
        assertTrue(ledger.isTerminal)

        assertNull(ledger.startRetry(url), "a finished round stays finished")
        assertEquals(1, ledger.settled, "the settlement is not given back")
        assertEquals(1, ledger.failedPages().size)
    }

    @Test
    @DisplayName("submitting the same URL twice counts once")
    fun duplicateSubmissionsCountOnce() {
        val ledger = CrawlLedger("t-dupe-submit")

        assertTrue(ledger.submit("https://example.com/a", 1))
        assertFalse(
            ledger.submit("https://example.com/a#section", 1),
            "fragment-only variant is the same page"
        )
        assertEquals(1, ledger.pagesExpected)

        ledger.recordSuccess("https://example.com/a")
        assertTrue(ledger.isComplete, "a duplicated out-link must not leave the round waiting")
    }

    @Test
    @DisplayName("outstanding URLs are reportable when the round is abandoned")
    fun outstandingListsUrlsThatNeverSettled() {
        val ledger = CrawlLedger("t-timeout")
        ledger.submit("https://example.com/a", 0)
        ledger.submit("https://example.com/b", 1)
        ledger.submit("https://example.com/c", 2)
        ledger.recordSuccess("https://example.com/a")

        ledger.close()

        val outstanding = ledger.outstanding()
        assertEquals(listOf("https://example.com/b", "https://example.com/c"), outstanding.map { it.url })
        assertEquals(1, outstanding.first().depth)
        assertNotNull(outstanding.first().reason)
        assertFalse(ledger.isComplete, "an abandoned round did not complete")
    }

    @Test
    @DisplayName("closed rounds reject the work of late handlers")
    fun closedRoundRejectsLateHandlers() {
        val ledger = CrawlLedger("t-closed")
        ledger.submit("https://example.com/a", 0)
        ledger.close()

        assertFalse(ledger.enter())
        assertFalse(ledger.submit("https://example.com/b", 1))
        assertEquals(1, ledger.pagesExpected)
    }

    @Test
    @DisplayName("over-settling completes the round loudly instead of hanging")
    fun overSettlingDoesNotHang() {
        val ledger = CrawlLedger("t-over")
        ledger.submit("https://example.com/a", 0)
        // Keep the round open past the first settle, so the second one is what
        // the guard has to absorb.
        assertTrue(ledger.enter())

        // A caller bug: the same page settled twice.  Hanging until the crawl
        // timeout would be worse than completing with a loud log.
        ledger.recordSuccess("https://example.com/a")
        ledger.recordSuccess("https://example.com/a")
        ledger.leave()

        assertTrue(ledger.isComplete)
        assertEquals(2, ledger.settled)
        assertEquals(1, ledger.pagesExpected)
    }

    @Test
    @DisplayName("settles arriving after the round completed are refused")
    fun settlesAfterCompletionAreRefused() {
        val ledger = CrawlLedger("t-late-settle")
        ledger.submit("https://example.com/a", 0)
        ledger.recordSuccess("https://example.com/a")
        assertTrue(ledger.isTerminal)

        // A duplicate parse event settles nothing and changes no count.
        ledger.recordSuccess("https://example.com/a")
        ledger.recordFailure("https://example.com/b", 1, 408, "too late")

        assertEquals(1, ledger.settled, "the round settled exactly once per submitted URL")
        assertEquals(1, ledger.pagesExpected)
        assertTrue(ledger.failedPages().isEmpty(), "a late failure adds nothing to a finished round")
    }

    @Test
    @DisplayName("unbalanced leave does not wedge the round")
    fun unbalancedLeaveDoesNotWedgeTheRound() {
        val ledger = CrawlLedger("t-unbalanced")
        ledger.submit("https://example.com/a", 0)
        ledger.enter()

        // Defensive: if a handler pipeline ever calls leave() twice, the round
        // must still complete rather than wait for an in-flight count that can
        // never return to zero.
        ledger.leave()
        ledger.leave()
        ledger.recordSuccess("https://example.com/a")

        assertTrue(ledger.isComplete)
    }

    @Test
    @DisplayName("awaitAllSettled returns only after the last URL settles")
    fun awaitAllSettledWaitsForTheLastUrl() = runBlocking {
        val ledger = CrawlLedger("t-await")
        ledger.submit("https://example.com/a", 0)
        ledger.submit("https://example.com/b", 1)
        assertFalse(ledger.isComplete)

        ledger.recordSuccess("https://example.com/a")
        ledger.recordFailure("https://example.com/b", 1, 417, "fetch failed")

        ledger.awaitAllSettled() // must not hang
        assertTrue(ledger.isComplete)
    }

    @Test
    @DisplayName("a round opened and closed by nobody stays open until work arrives")
    fun emptyRoundIsNotCompleteUntilSomethingIsSubmitted() {
        val ledger = CrawlLedger("t-empty")
        assertFalse(ledger.isComplete)
        assertFalse(ledger.isTerminal)
        assertEquals(0, ledger.pagesExpected)

        ledger.submit("https://example.com/a", 0)
        ledger.recordSuccess("https://example.com/a")
        assertTrue(ledger.isComplete)
    }

    /**
     * Concurrent handlers must settle a round exactly once.  The interleaving
     * that loses pages in production is timing-dependent, so this exercises the
     * real contention: handlers entering, settling and leaving from many
     * threads.
     */
    @Test
    @Timeout(30)
    @DisplayName("concurrent handlers settle the round exactly once")
    fun concurrentHandlersSettleTheRoundExactlyOnce() {
        val ledger = CrawlLedger("t-concurrent")
        val pageCount = 64
        repeat(pageCount) { index -> ledger.submit("https://example.com/page/$index", index % 3) }

        val start = CountDownLatch(1)
        val executors = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until pageCount).map { index ->
                executors.submit {
                    start.await()
                    assertTrue(ledger.enter())
                    try {
                        // Half the pages deliver a row, half fail their fetch.
                        if (index % 2 == 0) {
                            ledger.recordSuccess("https://example.com/page/$index")
                        } else {
                            ledger.recordFailure("https://example.com/page/$index", 0, 408, "failed")
                        }
                    } finally {
                        ledger.leave()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executors.shutdownNow()
        }

        assertTrue(ledger.isComplete, "every submitted URL settled")
        assertEquals(pageCount, ledger.settled)
        assertEquals(pageCount / 2, ledger.failedPages().size)
    }
}
