package ai.platon.pulsar.rest.api.service.crawl

import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-round settle bookkeeping for a link-discovery crawl.
 *
 * A crawl round may only be reported complete when **every URL it handed to the
 * session has been settled** — either a page was recorded for it, or it is known
 * to have failed terminally — **and no parse handler is still running**.
 *
 * Why a bare `completed == submitted` comparison is not enough (issue #592):
 *
 *  * The steps of a parse handler (record the page → discover and submit its
 *    children → count itself done) are not atomic with respect to other
 *    handlers.  Ticking the completion counter only at the end of the handler
 *    lets handler A's children be submitted *after* the counters of handlers
 *    B..E have already caught up with the submitted count, so the round is
 *    declared complete before A's children were ever queued.  That is exactly
 *    the "expected 10 pages, got 8, status=OK" symptom.  [enter]/[leave] close
 *    the window: the round cannot complete while any handler is in flight.
 *  * A fetch that fails — the snapshot-origin guard exhausting its retry
 *    budget, a dropped/evicted task, a 408/417 — never fires the parse event,
 *    so a counter that only knows "parsed" can never learn that the page is
 *    never coming.  [recordFailure] gives such a URL a terminal outcome, which
 *    both releases the wait and makes the loss reportable instead of silent.
 *  * A late duplicate parse event can still submit links minutes after the
 *    round returned ("+2m31s: submitted 2 links at depth 2").  [close], set the
 *    moment the round reaches a terminal state, makes [enter] and [submit]
 *    refuse that work.
 *
 * Counting is deliberately **URL-agnostic on the success path**: a page's final
 * URL can legitimately differ from the URL it was submitted under (redirects,
 * `document.baseURI`), so settled pages are counted, never matched.  URLs are
 * tracked for *reporting* only: which submitted URLs never settled, and why.
 *
 * Threading: the ledger is safe to use from concurrent parse handlers and crawl
 * event handlers.  All completion decisions are made on atomics.
 *
 * @param taskId the crawl task id, used for logging only.
 */
class CrawlLedger(val taskId: String = "") {

    companion object {
        private val logger = LoggerFactory.getLogger(CrawlLedger::class.java)

        /** Terminal failure: the fetch failed and its retry budget was exhausted. */
        const val REASON_FETCH_FAILED = "the page fetch failed and its retry budget was exhausted"

        /** The page was never fetched at all (dropped or evicted before a document was produced). */
        const val REASON_NEVER_FETCHED = "the page was never fetched"

        /** A successful load that never produced a result row. */
        const val REASON_NOT_PARSED = "the page loaded but produced no result row (no parse event fired)"

        /** The round ended (timeout/abort) before this page produced a document. */
        const val REASON_ROUND_ENDED = "the crawl finished before this page produced a document"
    }

    private val submittedCount = AtomicInteger(0)
    private val settledCount = AtomicInteger(0)
    private val inFlightCount = AtomicInteger(0)

    /**
     * Set the moment the round reaches a terminal state — completion, or an
     * explicit [close] when the round is abandoned.  Once set, [enter] and
     * [submit] refuse further work, so nothing is queued for a task the caller
     * has already been told is finished.
     */
    private val terminal = AtomicBoolean(false)

    private val allSettled = CompletableDeferred<Unit>()

    /** Submitted URL key -> discovery depth.  Reporting only. */
    private val submittedUrls = ConcurrentHashMap<String, Int>()

    /** Submitted URL keys already settled as failures — makes failure reporting idempotent. */
    private val failedKeys = ConcurrentHashMap.newKeySet<String>()

    /** Recorded page URL keys.  Reporting only. */
    private val succeededKeys = ConcurrentHashMap.newKeySet<String>()

    private val failures = ConcurrentHashMap<String, CrawlFailedPage>()

    /** How many URLs this round handed to the session (seeds included). */
    val pagesExpected: Int get() = submittedCount.get()

    /** How many submitted URLs reached a terminal outcome (a row, or a reported failure). */
    val settled: Int get() = settledCount.get()

    /** How many parse handlers are running right now. */
    val inFlight: Int get() = inFlightCount.get()

    /** True once the round can no longer accept work (completed, or closed). */
    val isTerminal: Boolean get() = terminal.get()

    /** True when the round completed because every submitted URL settled. */
    val isComplete: Boolean get() = allSettled.isCompleted

    /**
     * Register a URL handed to the session.  Submitting the same URL twice
     * counts once, so a duplicated out-link cannot leave the round waiting for
     * a page that will only ever be recorded once.
     *
     * @return true when this URL was newly registered.
     */
    fun submit(url: String, depth: Int): Boolean {
        if (url.isBlank() || terminal.get()) return false
        val key = normalizeForVisit(url)
        if (submittedUrls.putIfAbsent(key, depth) != null) return false
        submittedCount.incrementAndGet()
        return true
    }

    /** Register several URLs discovered at the same depth. */
    fun submit(urls: Iterable<String>, depth: Int) = urls.forEach { submit(it, depth) }

    /**
     * Mark that a parse handler is about to run for a page of this round.
     *
     * @return false when the round is already terminal: the caller must then do
     *   nothing at all, and in particular must not submit links — that is the
     *   "work continues after the task reported completed" failure mode.
     */
    fun enter(): Boolean {
        if (terminal.get()) return false
        inFlightCount.incrementAndGet()
        // Re-check: a concurrent completion could have observed inFlight == 0
        // between the check above and the increment.  Backing off here keeps
        // "the round is complete" and "a handler is running" mutually exclusive.
        if (terminal.get()) {
            leave()
            return false
        }
        return true
    }

    /** Mark that a parse handler finished; the last one out may complete the round. */
    fun leave() {
        val remaining = inFlightCount.decrementAndGet()
        if (remaining < 0) {
            // Unbalanced leave(): a caller bug, not a runtime condition.  Reset
            // so the round can still complete instead of hanging on a negative
            // in-flight count.
            logger.error("Crawl {}: unbalanced ledger leave(); in-flight count {}", taskId, remaining)
            inFlightCount.set(0)
        }
        checkComplete()
    }

    /**
     * A page was recorded for a submitted URL.
     *
     * The caller owns exactly-once semantics here (both crawl loops guard the
     * recording path with their own first-event set), because the URL a page
     * ends up under can differ from the URL it was submitted under.
     */
    fun recordSuccess(url: String? = null) {
        if (!settle()) return
        url?.takeIf { it.isNotBlank() }?.let { succeededKeys.add(normalizeForVisit(it)) }
    }

    /**
     * A submitted URL reached a terminal failure and will never produce a page.
     *
     * Idempotent per URL: the crawl `onLoaded` event fires once per load
     * attempt, including every retry of a failed fetch.
     */
    fun recordFailure(url: String?, depth: Int, protocolStatus: Int, reason: String?) {
        val submittedUrl = url?.takeIf { it.isNotBlank() }
        val key = submittedUrl?.let { normalizeForVisit(it) }
        if (key != null && !failedKeys.add(key)) return
        if (!settle()) return
        if (submittedUrl != null && key != null) {
            failures.putIfAbsent(
                key,
                CrawlFailedPage(
                    url = submittedUrl,
                    depth = depth,
                    protocolStatus = protocolStatus,
                    reason = reason?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    /** True when a result row was recorded for [url]. */
    fun isRecordedSuccess(url: String?): Boolean =
        !url.isNullOrBlank() && normalizeForVisit(url) in succeededKeys

    /** The URLs that failed terminally, in submission order. */
    fun failedPages(): List<CrawlFailedPage> =
        submittedUrls.keys.intersect(failedKeys)
            .mapNotNull { failures[it] }
            .sortedWith(compareBy({ it.depth }, { it.url }))

    /**
     * Submitted URLs that never settled.  Used when a round is abandoned
     * (timeout, cancellation) so the loss is reported instead of the partial
     * result passing as a complete one.
     */
    fun outstanding(): List<CrawlFailedPage> =
        submittedUrls.keys
            .filter { it !in failedKeys && it !in succeededKeys }
            .map { key ->
                CrawlFailedPage(
                    url = key,
                    depth = submittedUrls[key] ?: -1,
                    protocolStatus = 0,
                    reason = REASON_ROUND_ENDED
                )
            }
            .sortedWith(compareBy({ it.depth }, { it.url }))

    /**
     * Abandon the round: refuse further work without pretending it completed.
     * Outstanding URLs are then reported by [outstanding].
     */
    fun close() {
        terminal.set(true)
    }

    /** Suspend until every submitted URL has settled (or the round is closed). */
    suspend fun awaitAllSettled() {
        allSettled.await()
    }

    private fun settle(): Boolean {
        if (terminal.get()) return false
        val settledNow = settledCount.incrementAndGet()
        if (settledNow > submittedCount.get()) {
            // More settlements than submissions means a caller double-counted a
            // page.  Completing is safer than waiting forever for a page that
            // was already counted, but it must never pass silently.
            logger.error(
                "Crawl {}: settle bookkeeping overran — {} settled for {} submitted URL(s); " +
                    "completing the round instead of waiting for a page that cannot arrive",
                taskId, settledNow, submittedCount.get()
            )
            complete()
            return true
        }
        checkComplete()
        return true
    }

    private fun checkComplete() {
        if (settledCount.get() == submittedCount.get() && inFlightCount.get() == 0) {
            complete()
        }
    }

    private fun complete() {
        if (terminal.compareAndSet(false, true)) {
            allSettled.complete(Unit)
        }
    }
}
