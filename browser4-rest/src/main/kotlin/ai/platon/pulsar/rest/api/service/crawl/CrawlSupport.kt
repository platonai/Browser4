package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.context.PulsarContext
import ai.platon.pulsar.skeleton.session.PulsarSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Stateless helpers shared by the crawl task lifecycle ([CrawlService]), the
 * round execution ([CrawlRoundRunner]) and their reporting.
 *
 * They are top-level rather than members because none of them touches crawl
 * state: a URL canonicalization, a title fallback, a loss note.  Keeping them
 * free of the service is what lets each of them be read (and, where the input
 * is a plain string, tested) on its own.
 */

/** Earliest plausible fetch time; earlier values are unset sentinels. */
private val MIN_FETCH_TIME: Instant = Instant.parse("2000-01-01T00:00:00Z")

/**
 * Floor for a task budget a request may ask for (ms).
 *
 * It only has to be positive: a budget below the round floor (report margin + minimum round
 * budget) cannot start a single round, and that is a *reported* outcome — every seed is
 * refused by name with its reason — not a silent one.  Unit tests drive that path with 10s.
 */
internal const val MIN_REQUEST_TASK_TIMEOUT_MS = 1_000L

/**
 * Ceiling for a task budget a request may ask for (ms): one hour.
 *
 * A caller may raise its own budget above the server default (the default is a policy, not a
 * grant), but not without bound: one crawl holding browser tabs for a day is the failure this
 * ceiling prevents.  An operator who needs longer raises `CrawlService.taskTimeoutMillis`.
 */
internal const val MAX_REQUEST_TASK_TIMEOUT_MS = 3_600_000L

/**
 * The task budget a request runs under, from what it asked for and what the server defaults to.
 *
 * `null` and non-positive values mean "no preference" (a budget is a limit, not a switch, so
 * `0` must not mean "cancel now"), and anything positive is clamped into the per-request range.
 * The caller sees the result in `CrawlResponse.taskTimeoutMillis`, so a clamp is visible rather
 * than silently different — the same contract `resolveParallelTabs` has for tabs.
 */
internal fun resolveRequestTaskTimeout(requested: Long?, serverDefault: Long): Long {
    if (requested == null || requested <= 0L) return serverDefault
    return requested.coerceIn(MIN_REQUEST_TASK_TIMEOUT_MS, MAX_REQUEST_TASK_TIMEOUT_MS)
}

/**
 * How many lost URLs the diagnostic spells out.  The full list is in
 * [CrawlResponse.failedPages]; the note only has to prove the loss is real and
 * name the first few.
 */
private const val MAX_REPORTED_FAILED_PAGES = 5

/**
 * Canonical form used to dedupe URLs inside a crawl round.
 *
 * Fragment-only suffixes are stripped too: resolving a fragment-only href
 * against the portal URL appends '#' to it, and the canonicalized form must
 * dedupe against the fragment-less URL instead of surfacing a spurious
 * trailing-'#' page (or counting it as a new link).
 *
 * Order matters, and it is fragment/query **first**, trailing slash **last**.
 * Dropping the slash first only collapsed `…/product/1/` onto `…/product/1`;
 * the very same page linked as `…/product/1/?utm_source=x` kept its slash
 * (`substringBefore('?')` then ends in '/'), so one page submitted under two
 * spellings produced two identities — two submissions, two rows, two depths —
 * which is exactly what this function exists to prevent.  Every crawl path
 * derives its dedup key (and the `visited` / `depths` / `recorded` lookups)
 * from here, so the fix has to live here: `http://h/p/` = `http://h/p` =
 * `http://h/p?q=1` = `http://h/p#f`.
 *
 * Note the deliberate asymmetry: this key is used to *dedupe submissions* and
 * to report losses, never to decide that a page arrived.  A page's final URL
 * can differ from the URL it was submitted under (redirects,
 * `document.baseURI`), so settlement is counted per page, not matched by URL.
 */
internal fun normalizeForVisit(url: String): String {
    return url.trim().lowercase()
        .substringBefore('#')  // strip the fragment for dedup
        .substringBefore('?')  // strip the query for dedup
        .removeSuffix("/")     // …last, so '/?query' and '/#frag' fold onto the page
}

/**
 * Resolve the queue-time depth of a parsed page.
 *
 * A link-discovery round registers every URL it hands to the session in a
 * `depths` map *before* submitting it (seed = 0, each link = discovering page's
 * depth + 1), and that map is the only source of truth for a page's depth.  The
 * URL a document is *served* under can differ from the URL it was *submitted*
 * under — a redirect, and equally a `<base href>` element (jsoup takes the
 * document base URI from it) — so the lookup is anchored to the URLs this crawl
 * actually queued.
 *
 * The submission is tried first on purpose.  Preferring the served URL labels a
 * redirecting page with the depth of whatever URL it landed on, and on a site
 * whose pages share a `<base href>` it labels every page with the seed's depth.
 * [servedUrl] is only a fallback, and only when it is a URL this crawl queued
 * too (a page reachable under two submitted URLs).
 *
 * @return the queue-time depth, or null when neither URL was queued by this
 *   crawl.  A null is a *reporting* gap, not a lost page: the caller must still
 *   record the page (with `UNKNOWN_DEPTH`), never drop it and never settle it
 *   as a failure — the round cannot lose a document it fetched.
 */
internal fun resolveQueueDepth(
    submittedUrl: String,
    servedUrl: String?,
    depths: Map<String, Int>,
): Int? {
    depths[normalizeForVisit(submittedUrl)]?.let { return it }
    val servedKey = servedUrl?.takeIf { it.isNotBlank() }?.let { normalizeForVisit(it) }
        ?: return null
    return depths[servedKey]
}

/**
 * The label of a session a crawl round owns: it appears in session logs and
 * context dumps, so a running crawl's browser resources can be attributed.
 *
 * Nothing filters on the label.  A round session is created on the context
 * rather than through `PulsarSessionManager`, so the round itself is its only
 * owner — see [releaseCrawlSession].
 */
internal fun crawlSessionLabel(taskId: String) = "crawl-$taskId"

/**
 * Release a session a crawl round owns: close it **and** deregister it.
 *
 * A round creates its session on the context rather than through
 * `PulsarSessionManager`, so nothing else tracks it: the round's `finally` is the
 * only owner of its lifetime.  Calling `session.close()` alone leaves the closed
 * session in `AbstractPulsarContext.sessions` for the lifetime of the process
 * (only `closeSession` removes it) — one dead entry per round — and
 * `getOrCreateSession()` hands back `sessions.values.firstOrNull()`, so a closed
 * round session can be returned to a caller that asked for a live one.
 *
 * Failures are returned instead of thrown (and instead of being swallowed):
 * closing unbinds browser/driver resources, so a failure there is a real leak
 * that no other component can reconcile once the session is unregistered.
 *
 * @return null when the session was closed and deregistered, the failure
 *   otherwise.
 */
internal fun releaseCrawlSession(session: PulsarSession, context: PulsarContext): Throwable? =
    runCatching { context.closeSession(session) }.exceptionOrNull()

/** What one depth level of a round is granted: 5 minutes. */
internal const val ROUND_BUDGET_PER_DEPTH_MS = 300_000L

/**
 * Ceiling on a round's own budget whatever the depth (30 minutes).
 *
 * A round budget is a *scheduling* limit, never a promise: the task-level limit
 * ([CrawlService.DEFAULT_TASK_TIMEOUT_MS], 10 minutes by default) is what a
 * crawl really runs under and it is smaller, which is why a round budget is
 * always derived from what the task has left rather than from its depth alone.
 */
internal const val MAX_ROUND_BUDGET_MS = 1_800_000L

/**
 * How much of the task budget a round leaves untouched so it can *report* before
 * the task-level limit fires.
 *
 * A round's timeout is only the beginning of its end: it snapshots its results,
 * closes its ledger and its session (unbinding browser and driver resources) and
 * the service then publishes the losses.  A round still doing that when the task
 * limit fires is killed mid-report and its accounting — the URLs it knows are
 * missing — never reaches the terminal record at all.  The margin is that
 * window, and it is the reason a deep crawl's own timeout is reachable at all:
 * before, a depth>=2 round asked for 10+ minutes from a 10-minute task and could
 * only ever be killed, never time out on its own terms.
 */
internal const val ROUND_REPORT_MARGIN_MS = 30_000L

/**
 * Below this, the remaining task budget cannot carry a round: the URL is not
 * submitted at all and is reported as a loss instead (see [unstartedSeedRound]).
 */
internal const val MIN_ROUND_BUDGET_MS = 15_000L

/** Why a URL was never submitted: the crawl spent its budget before reaching it. */
internal const val REASON_BUDGET_EXHAUSTED =
    "the crawl ran out of its time budget before this URL was submitted"

/** Why a URL never settled: the server-side task limit fired while its round ran. */
internal const val REASON_TASK_LIMIT =
    "the server-side task limit fired while this URL was still being fetched"

/** Why a URL never settled: a caller cancelled the crawl while its round ran. */
internal const val REASON_TASK_CANCELLED =
    "the crawl was cancelled before this URL settled"

/**
 * The time budget of one round: the smaller of what its depth grants it and what
 * the task has left, minus the [ROUND_REPORT_MARGIN_MS] it needs to report.
 *
 * Deriving the round budget from the *task's* remaining budget is what makes a
 * timed-out round reportable.  A fixed `depth * 5 min` does not fit inside a
 * 10-minute task from depth 2 up, so such a round was always killed by the task
 * limit — and a killed round returns nothing, so its outstanding URLs were
 * neither collected nor reported as lost.  A round that is granted less than
 * [MIN_ROUND_BUDGET_MS] is not started at all; see [hasBudgetForRound].
 *
 * @param depth the round's discovery depth (>= 1; the caller owns depth 0).
 * @param remainingTaskBudgetMs what is left of the task budget; the worker of a
 *   task derives it from the moment it armed the task clock, so the round's
 *   deadline and the task's own limit measure the same span.
 */
internal fun resolveRoundTimeoutMs(depth: Int, remainingTaskBudgetMs: Long): Long {
    val depthBudget = (depth.coerceAtLeast(1) * ROUND_BUDGET_PER_DEPTH_MS)
        .coerceAtMost(MAX_ROUND_BUDGET_MS)
    return minOf(depthBudget, remainingTaskBudgetMs - ROUND_REPORT_MARGIN_MS)
        .coerceAtLeast(MIN_ROUND_BUDGET_MS)
}

/**
 * Whether the task can still afford to start one more round.
 *
 * Starting a round the task limit will kill costs the crawl the URL: nothing
 * downstream can report a round that never returned.  Reporting the seed as lost
 * *before* submitting it keeps `pagesFound + failedPages.size == pagesExpected`
 * exact, which is the whole point of the loss accounting.
 */
internal fun hasBudgetForRound(remainingTaskBudgetMs: Long): Boolean =
    remainingTaskBudgetMs - ROUND_REPORT_MARGIN_MS >= MIN_ROUND_BUDGET_MS

/**
 * The round of a seed that was never started: no page, one submitted URL, and
 * that URL reported as lost.
 *
 * The accounting is deliberately one-for-one (`pagesExpected = 1`,
 * `failedPages.size = 1`): the seed URL is the only URL this crawl can still
 * prove it set out to fetch, and claiming any more would invent pages it never
 * submitted.
 *
 * The URL is also reported as [CrawlRound.outstanding] — not a second loss, the
 * *same* one — because it is work, not a settled outcome: a resume re-submits it.
 * Without that, a crawl the budget refused every seed of could be reported as
 * resumable while having nothing left to resume (see `CrawlSeedCheckpoint`).
 */
internal fun unstartedSeedRound(seedUrl: String): CrawlRound {
    val lost = CrawlFailedPage(url = seedUrl, depth = 0, protocolStatus = 0, reason = REASON_BUDGET_EXHAUSTED)
    return CrawlRound(
        pages = emptyList(),
        failedPages = listOf(lost),
        pagesExpected = 1,
        timedOut = true,
        timeoutError = "Crawl timed out: the task budget was exhausted before every seed was submitted " +
            "(partial results saved)",
        outstanding = listOf(lost)
    )
}

/**
 * The seeds that never settled: no round of theirs completed, so their pages are
 * not part of the result at all and the task-level terminal write has to name
 * them instead of letting them vanish.
 *
 * Used by the task-limit/cancellation path, where the in-flight rounds are gone
 * (a killed round returns nothing) and the expected count of a round that never
 * finished is unknowable — so each unfinished seed is accounted as exactly one
 * expected, lost URL.
 */
internal fun unfinishedSeedLosses(
    seedUrls: List<String>,
    seedStatuses: Array<CrawlSeedStatus?>,
    reason: String,
): List<CrawlFailedPage> = seedUrls.filterIndexed { index, _ -> seedStatuses.getOrNull(index) == null }
    .map { CrawlFailedPage(url = it, depth = 0, protocolStatus = 0, reason = reason) }

/**
 * Run [block] for every URL with at most [concurrency] URLs in flight, and
 * return the results in input order.
 *
 * Used to drive the independent units of a crawl in parallel: the seed URLs of
 * a depth=0 (bulk fetch) crawl, and the link-discovery rounds of a depth>=1
 * crawl. Neither has an ordering dependency on its siblings, so running them one
 * at a time only wasted the browser driver pool's capacity.
 *
 * [concurrency] is a *ceiling on units in flight*, not a promise that many tabs
 * are busy: each unit leases its own tab from the browser driver pool, and the
 * pool is what ultimately bounds real parallelism.  A [concurrency] larger than
 * the number of URLs simply runs every URL concurrently; the results are still
 * returned in input order, so the crawl listing stays deterministic.
 *
 * The block is expected to settle its own failures (see the per-seed handling
 * in [CrawlService.submit]); a block that throws cancels the remaining URLs.
 * */
internal suspend fun <T> mapCrawlSeedsConcurrently(
    urls: List<String>,
    concurrency: Int,
    block: suspend (index: Int, url: String) -> T,
): List<T> = coroutineScope {
    val permits = Semaphore(concurrency.coerceAtLeast(1))
    urls.mapIndexed { index, url ->
        async { permits.withPermit { block(index, url) } }
    }.awaitAll()
}

/**
 * Whether a load actually delivered a document for its URL.
 *
 * A crawl may only record a row for a page it *received*.  Two failures used to
 * slip through as rows because the page object still looked usable:
 *
 *  * the engine substitutes the stored copy when a fetch fails — `-refresh`
 *    implies `-ignoreFailure`, and the crawl forces `-refresh` — so the page
 *    comes back carrying the store's content length while `isFetched` is false;
 *  * a zero-byte fetch parses into an empty document, and the page keeps the
 *    metadata of whatever it held before.
 *
 * Either way the row carried a URL, a content length and no title: a page the
 * crawl never received, reported as one it had — which is exactly what the
 * per-URL metadata guarantee exists to prevent.  The document is what the caller
 * asked for, so its presence answers the question; the protocol status does not,
 * because it stays 200 in both cases.
 *
 * A read-only load is the one case where "not fetched" does not mean "not received": the caller
 * asked for the stored copy — that is what `--readonly` means, and what makes the X-SQL engine's
 * second read of a page a cache hit ([resolveRoundArgs]) — so a page that came back from the store
 * with content counts as delivered when the round was read-only.  The substitution case above is
 * not that: it happens under `-ignoreFailure`, which `-refresh` implies and which a read-only round
 * never carries, so a failed fetch still comes back as a loss.
 */
internal fun isDocumentDelivered(fetched: Boolean, html: String?, storeServed: Boolean = false): Boolean =
    !html.isNullOrBlank() && (fetched || storeServed)

/**
 * Whether a load answered from the page store because the round is read-only.
 *
 * [WebPage.isCached] is set when the load served the stored page core, and a read-only round is the
 * only one allowed to treat that as a delivered page (see [isDocumentDelivered]).
 */
internal fun isReadOnlyStoreServe(page: WebPage, readonly: Boolean): Boolean = readonly && page.isCached

/**
 * The facts about a finished load that decide what the crawl does with the page it asked
 * for, and eventually lose it.
 *
 * Captured into a value on purpose: the delivery rules below are then plain functions of a
 * ledger and these fields, which is what makes them testable without a browser, a session or
 * a mocked engine type.  The engine's own status vocabulary is read exactly once, in
 * [loadedPageFacts].
 */
internal data class LoadedPageFacts(
    val url: String,
    val isFetched: Boolean,
    val isCanceled: Boolean,
    val isNil: Boolean,
    val isRetry: Boolean,
    val isFailed: Boolean,
    val isSuccess: Boolean,
    val statusCode: Int,
    val statusReason: String?,
    val contentLength: Long,
)

/** Read the delivery-relevant facts of a load; null when the load produced no page at all. */
internal fun loadedPageFacts(page: WebPage?): LoadedPageFacts? {
    if (page == null) return null
    val status = page.protocolStatus
    return LoadedPageFacts(
        url = page.url,
        isFetched = page.isFetched,
        isCanceled = page.isCanceled,
        isNil = page.isNil,
        isRetry = status.isRetry,
        isFailed = status.isFailed,
        isSuccess = status.isSuccess,
        statusCode = status.minorCode,
        statusReason = status.reason?.toString(),
        contentLength = page.contentLength
    )
}

/**
 * Why a completed load left the crawl without a row for [submittedUrl].
 *
 * Without this, a fetch that fails is invisible to the crawl: the parse event never fires, so
 * the completion wait can never learn that the page is not coming, and the URL simply
 * disappears from the result (issue #592).  The classification mirrors
 * `XSQLHyperlink.CrawlEventHandlers`, the established reading of these states here:
 *
 *  * a retry/canceled status means the page is still in flight — report nothing, so the
 *    round keeps waiting for the attempt that finally lands;
 *  * `!isFetched` alone is NOT a failure: a page served from the page store (`-readonly`
 *    without `-refresh`) legitimately completes with content while `isFetched` stays false;
 *  * a successful load is settled by the parse event that records its row, and the load event
 *    fires *after* it — so a success no row was recorded for will never produce one and is
 *    reported here instead of letting the round wait out its whole timeout.
 *
 * @return the reason to report, or null when there is nothing to report.
 */
internal fun lossReasonForLoaded(ledger: CrawlLedger, submittedUrl: String, page: LoadedPageFacts?): String? {
    if (page == null) return CrawlLedger.REASON_NEVER_FETCHED
    return when {
        page.isCanceled || page.isRetry -> null

        page.isNil -> CrawlLedger.REASON_NEVER_FETCHED

        page.isFailed -> page.statusReason ?: CrawlLedger.REASON_FETCH_FAILED

        !page.isFetched && !page.isSuccess -> CrawlLedger.REASON_NEVER_FETCHED

        !ledger.isRecordedSuccess(submittedUrl) && !ledger.isRecordedSuccess(page.url) ->
            CrawlLedger.REASON_NOT_PARSED

        else -> null
    }
}

/**
 * Decide what happens to the delivery attempt [token] of [url] once its load is over: settle
 * the URL, or claim one more load of it.
 *
 * The loss of a page is decided here and not in the parse event, because this is the only
 * place that knows *which* attempt it is looking at — the token — and that a load which
 * delivered no document (the parse event fired, the document was empty: a zero-byte response,
 * or a stored copy substituted for a failed fetch) may still be worth one more load.
 * Everything a superseded attempt reports is ignored: its URL is already being loaded again,
 * and settling it here would report the loss that retry is about to disprove.
 *
 * The order matters: the retry is claimed *before* the failure is recorded, because a
 * recorded loss can complete the round (when this was its last outstanding URL, which is the
 * single-page case), and a completed round refuses the retry this very call was about to
 * submit.
 *
 * @param emptyDeliveries URLs whose parse event carried no document; this attempt's entry is
 *   consumed here.
 * @return the attempt token to load [url] again under, or null when this attempt is settled
 *   (or still in flight and about to decide for itself).
 */
internal fun resolveDeliveryAttempt(
    ledger: CrawlLedger,
    url: String,
    depth: Int,
    token: Long,
    page: LoadedPageFacts?,
    emptyDeliveries: MutableSet<String>
): Long? {
    if (ledger.isTerminal || !ledger.isCurrentAttempt(url, token)) return null
    // The row this URL was waiting for exists: a duplicate event of an earlier attempt must
    // not settle it a second time.
    if (ledger.isRecordedSuccess(url)) return null

    // The engine is still working on this URL (a retry/canceled status): settle nothing.  Its
    // own scheduled retry is the second load, and the round waits for it.
    if (page != null && (page.isCanceled || page.isRetry)) return null

    val empty = emptyDeliveries.remove(normalizeForVisit(url))
    // A load that produced a parse event carries the loss the parse event saw; one that
    // produced none is classified from its own status.
    val reason = if (empty) CrawlLedger.REASON_NOT_DELIVERED else lossReasonForLoaded(ledger, url, page)
    // A page that arrived but never reached the parser is not a *delivery* failure: the content
    // is there, and loading it again would fetch the same bytes twice.  A page with no content
    // at all is the exception — a zero-byte response that no parse event followed either, which
    // is a delivery failure the retry can clear.
    if (reason == CrawlLedger.REASON_NOT_PARSED && (page?.contentLength ?: 0) > 0) {
        ledger.recordFailure(url, depth, page?.statusCode ?: 0, reason)
        return null
    }
    if (reason == null) return null

    claimDeliveryRetry(ledger, url, token, page)?.let { return it }

    if (empty) {
        logger.warn(
            "Crawl {}: the load of '{}' returned no document (fetched={}, status={}, contentLength={}) " +
                "on attempt {}; reporting it as lost",
            ledger.taskId, url, page?.isFetched, page?.statusCode, page?.contentLength, token
        )
    }
    ledger.recordFailure(url, depth, page?.statusCode ?: 0, reason)
    return null
}

/**
 * Claim one more load of [url], when a second load can still change the outcome and the URL
 * has an attempt to spend ([CrawlLedger.startRetry]).
 *
 * Every failure that reaches this point is retried once, whatever its protocol status, because
 * the engine's *own* retry has already been accounted for: a status the engine considers
 * retryable (`isRetry`) never gets here — it keeps the URL in flight and the round waits for
 * the attempt the engine scheduled.  What is left is a load the engine considers finished and
 * the crawl did not receive: a dropped task, a fetch that failed for good, or a stored copy
 * substituted for a failed fetch.  One more load of a page the caller asked for is cheap next
 * to reporting it lost, and if it fails too, its status is what the loss report carries.
 *
 * @return the token of the new attempt, or null when the URL is out of attempts.
 */
internal fun claimDeliveryRetry(
    ledger: CrawlLedger,
    url: String,
    token: Long,
    page: LoadedPageFacts?
): Long? {
    val next = ledger.startRetry(url) ?: return null
    logger.warn(
        "Crawl {}: '{}' delivered nothing on attempt {} (fetched={}, status={}, contentLength={}); " +
            "loading it once more (attempt {})",
        ledger.taskId, url, token, page?.isFetched, page?.statusCode ?: 0, page?.contentLength, next
    )
    return next
}

/** Logger for the delivery rules above; they run outside the round runner. */
private val logger = LoggerFactory.getLogger("ai.platon.pulsar.rest.api.service.crawl.CrawlDelivery")

/**
 * Extract the <title> text from raw HTML when [FeaturedDocument.title]
 * returns blank.  Handles the case where the parse pipeline skips title
 * extraction on cached content.  Returns null when no <title> tag is found.
 */
internal fun extractTitleFromHtml(html: String?): String? {
    if (html.isNullOrBlank()) return null
    val match = Regex("""<title[^>]*>\s*(.*?)\s*</title>""", RegexOption.IGNORE_CASE)
        .find(html)
    return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
}

/**
 * Whether [url] passes the crawl's out-link pattern.
 *
 * A blank pattern (or the catch-all `.+`) admits everything, and an invalid
 * regular expression admits everything too: a bad pattern must not silently
 * turn a crawl into "no links found".
 */
internal fun matchesPattern(url: String, pattern: String?): Boolean {
    if (pattern.isNullOrBlank() || pattern == ".+") return true
    return runCatching {
        Regex(pattern).containsMatchIn(url)
    }.getOrDefault(true)
}

/**
 * The out-links one discovery pass may queue, and the anchors it refused.
 *
 * A storefront routinely offers one destination through several anchors — the
 * product image and the product title are two `href`s to the same page, and a
 * grid/list toggle spells another page twice with different query strings.  The
 * budget (`-top-links`) is a budget for *pages*, so it can only be spent after
 * the repeats are gone: feeding the anchors straight into `take(n)` lets two
 * copies of one link take two slots, and the crawl then queues fewer distinct
 * pages than it was asked for.  (The ledger refuses the second submission of one
 * identity, so nothing is fetched twice — the promise is lost silently instead.)
 *
 * Identity is [normalizeForVisit], the crawl's one dedup key, and the spelling
 * that survives is the first one seen.  The fragment is always dropped: a jump
 * target inside a document never identifies a page, so it must not reach the
 * URL a row reports.  The query is dropped when [ignoreUrlQuery] is set — the
 * flag is documented as stripping the query from a *discovered* href, so it has
 * to be applied where discovered hrefs become queued URLs; the load path never
 * sees it for these links.
 *
 * [visited] is the crawl's cross-page memory (identities this crawl already
 * queued).  A single-level crawl passes an empty set: it has no memory to keep,
 * and the ledger refuses a second submission of one identity anyway.
 *
 * @param hrefs the anchors one page offers, in document order, already absolute.
 * @param visited identities this crawl has already queued.
 * @param outLinkPattern `-out-link-pattern`, matched against the prepared spelling.
 * @param topLinks `-top-links`, the number of *distinct* links one page may add.
 * @param ignoreUrlQuery `-ignoreUrlQuery`: drop the query from each candidate.
 * @return the links to queue and the count of anchors each rule refused; see
 *   [DiscoverySelection] for why the counters are part of the result.
 */
internal fun selectDiscoveredLinks(
    hrefs: List<String>,
    visited: Set<String>,
    outLinkPattern: String?,
    topLinks: Int,
    ignoreUrlQuery: Boolean,
): DiscoverySelection {
    // Blank hrefs are not candidates at all, so they are not counted as skipped:
    // an anchor with nothing to resolve was never a link.
    val prepared = hrefs.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { href -> href.substringBefore('#').let { if (ignoreUrlQuery) it.substringBefore('?') else it } }
        .toList()

    val matched = prepared.filter { matchesPattern(it, outLinkPattern) }

    // First spelling of each identity wins, in document order.
    val distinct = LinkedHashMap<String, String>(matched.size)
    matched.forEach { distinct.putIfAbsent(normalizeForVisit(it), it) }

    val fresh = distinct.filterKeys { it !in visited }.values.toList()
    val chosen = fresh.take(topLinks.coerceAtLeast(0))

    return DiscoverySelection(
        links = chosen,
        filtered = prepared.size - matched.size,
        alreadyVisited = distinct.size - fresh.size,
        repeated = matched.size - distinct.size,
        overBudget = fresh.size - chosen.size,
    )
}

/**
 * The outcome of [selectDiscoveredLinks]: the links to queue, and why the rest
 * of the page's anchors were not queued.
 *
 * The counters exist so the crawl's log can name the reason ("3 repeated on the
 * page, 2 already visited, 5 beyond -top-links 3") instead of one opaque
 * "skipped" number that hides a budget mistake behind a normal-looking run.
 * They add up: `links.size + skipped` is the number of non-blank anchors the
 * page offered, so a crawl that queued nothing always says which rule did it.
 */
internal data class DiscoverySelection(
    val links: List<String>,
    /** Anchors the out-link pattern rejected. */
    val filtered: Int,
    /** Anchors whose page another anchor on the same page already offered. */
    val repeated: Int,
    /** Anchors whose page this crawl has already queued. */
    val alreadyVisited: Int,
    /** Distinct, unvisited links that did not fit in `-top-links`. */
    val overBudget: Int,
) {
    val skipped: Int get() = filtered + repeated + alreadyVisited + overBudget
}

/**
 * The option tokens of an args string: the whitespace-separated words that start with `-`, with any
 * `=value` suffix removed so `-refresh=true` and `-refresh` are the same option.
 *
 * Option *values* are left alone: a quoted CSS selector arrives as its own token and is not an
 * option, so it survives a strip untouched.
 */
private fun optionTokensIn(args: String): Set<String> =
    args.split(Regex("\\s+"))
        .filter { it.startsWith("-") }
        .map { it.substringBefore('=') }
        .toSet()

/** True when [args] requests the option named [fieldName], under any of its spellings. */
internal fun hasOption(args: String, fieldName: String): Boolean {
    val tokens = optionTokensIn(args)
    return LoadOptions.getOptionNames(fieldName).any { it in tokens }
}

/** [args] without the option named [fieldName] (all spellings), blanks collapsed. */
internal fun stripOption(args: String, fieldName: String): String {
    val names = LoadOptions.getOptionNames(fieldName)
    return args.split(Regex("\\s+"))
        .filterNot { token -> names.any { name -> token == name || token.startsWith("$name=") } }
        .joinToString(" ")
        .trim()
}

/**
 * The args a crawl round loads its pages with.
 *
 * A crawl forces `-refresh` by default, because a stale or half-written stored copy is what makes a
 * portal page return 0 out-links — `buildEffectiveArgs` existed for that (see
 * `docs-dev/copilot/ci-stabilization-4.13.x.md` §18), and this keeps it.
 *
 * **`-readonly` wins over `-refresh`** when the request asks for both, which is the one thing that
 * changed. The two options mean opposite things, and the engine has no notion of precedence between
 * them: `-refresh` expands to `-ignoreFailure -i 0s` and resets the fetch retry counters, so
 * `LoadOptions.isExpired()` answers true for *every* local copy. `PulsarSession.load()` then misses
 * its read-only shortcut (`AbstractPulsarSession.createPageWithCachedCoreOrNull`, which needs both
 * `readonly` and a page that has not expired) and goes to the web — taking the store writes with it.
 * So "read-only" can only mean anything if the refresh is *gone*, not merely present alongside it:
 * the round erases `-refresh` and adds none.
 *
 * That is also what makes the X-SQL execution engine's *second* read of a page a guaranteed cache
 * hit (`CrawlXSql`/`ScrapeAPIUtils.normalizeForReadOnlyQuery` seal the statement's own url the same
 * way, for the same reason). A call that also passes `-expires 0s` explicitly still fetches: this
 * stops the crawl from *adding* a fetch, it does not overrule a caller who asks for one in so many
 * words.
 */
internal fun resolveRoundArgs(rawArgs: String): String {
    val args = rawArgs.trim()
    if (hasOption(args, "readonly")) {
        return stripOption(args, "refresh")
    }
    return when {
        args.isBlank() -> "-refresh"
        hasOption(args, "refresh") -> args
        else -> "$args -refresh"
    }
}

/**
 * The args a crawl puts on each URL it discovered.
 *
 * Discovered pages are loaded through the session, so an option that is not in
 * these args simply does not apply to them: `-readonly` used to stop applying at
 * depth >= 2 (the crawl wrote pages to the store while claiming it did not), and
 * `-ignoreUrlQuery` / `-noNorm` are documented as options for *discovered*
 * out-link hrefs — the very links this string carries.  They are forwarded here
 * for the same reason `-refresh` is: the load, not the crawl, is what normalizes
 * a URL, and the load only knows what these args tell it.
 *
 * A child's discovery depth is deliberately NOT embedded here.  It used to be
 * (`-depth N`) so that it could be re-read out of `page.configuredUrl`, but
 * [LoadOptions] has no such option: `LoadOptions.toString()` — which is what
 * builds `configuredUrl` — only serializes options it knows, so the marker was
 * dropped on submission and every read of it failed.  Depth is queue-time
 * bookkeeping owned by `CrawlRoundRunner`'s `depths` map and is never re-derived
 * from a URL.
 *
 * @param expandable whether the loaded page may discover further links
 *   (depth >= 2 rounds submit children; a depth-1 round does not, so it does not
 *   carry an out-link selector that nothing would read).
 */
internal fun buildLinkArgs(options: LoadOptions, expandable: Boolean): String {
    val parts = mutableListOf("-parse")
    if (expandable) {
        if (options.outLinkSelector.isNotBlank()) {
            parts.add("-outLink \"${options.outLinkSelector}\"")
        }
        if (options.outLinkPattern.isNotBlank() && options.outLinkPattern != ".+") {
            parts.add("-outLinkPattern \"${options.outLinkPattern}\"")
        }
    }
    if (options.refresh) parts.add("-refresh")
    if (options.readonly) parts.add("-readonly")
    if (options.ignoreUrlQuery) parts.add("-ignoreUrlQuery")
    if (options.noNorm) parts.add("-noNorm")
    return parts.joinToString(" ")
}

/**
 * Compute the readonly store-serving markers for a recorded page.
 *
 * When a load serves the stored page core, [WebPage.isCached] is true and [WebPage.fetchTime]
 * preserves the time the content was originally fetched, so the age of the served content is
 * computable.  Fresh fetches keep served=false.
 *
 * A read-only crawl is the case this reports on: it loads without `-refresh`
 * ([resolveRoundArgs]), which is what lets the engine answer from local storage — and that copy can
 * be older than the crawl, so the age has to reach the caller.
 */
internal fun storeServeMarkers(page: WebPage): Pair<Boolean, Long?> {
    if (!page.isCached) return false to null
    val fetchTime = page.fetchTime
    val now = Instant.now()
    // Guard against sentinel/unset fetch times (e.g. epoch) that would
    // produce absurd ages for stored content.
    val ageSeconds = if (fetchTime.isAfter(MIN_FETCH_TIME) && fetchTime.isBefore(now)) {
        Duration.between(fetchTime, now).seconds.coerceAtLeast(0)
    } else null
    return true to ageSeconds
}

/**
 * Build the terminal note that tells the user what --readonly did: either
 * pages were served from the page store (with the age of the oldest stored
 * content) or every page was verified fetched fresh from the live site.
 * See the readonly acceptance criteria: "must surface that it served
 * stored content (with age) or verify freshness".
 */
internal fun buildReadonlyNote(pages: List<CrawlPageResult>): String {
    val served = pages.filter { it.servedFromStore }
    val freshCount = pages.size - served.size
    return if (served.isEmpty()) {
        "readonly: verified fresh — all ${pages.size} page(s) fetched from the live site " +
        "(none served from the page store); nothing was written to the page store"
    } else {
        val oldest = served.maxOfOrNull { it.storeAgeSeconds ?: 0L } ?: 0L
        "readonly: ${served.size}/${pages.size} page(s) served from the page store " +
        "(stored content up to ${formatAge(oldest)} old)" +
        (if (freshCount > 0) "; $freshCount fetched fresh" else "") +
        "; nothing was written to the page store"
    }
}

private fun formatAge(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/**
 * The terminal note that makes a loss visible.  A crawl that dropped pages
 * must say so — with the URLs and the reason — instead of reporting a page
 * count smaller than the number of pages it set out to fetch.
 */
internal fun buildLossNote(
    pagesFound: Int,
    pagesExpected: Int,
    failedPages: List<CrawlFailedPage>
): String? {
    if (failedPages.isEmpty()) return null
    val shown = failedPages.take(MAX_REPORTED_FAILED_PAGES).joinToString("; ") { f ->
        buildString {
            append(f.url)
            append(" (depth=").append(f.depth)
            if (f.protocolStatus != 0) append(", status=").append(f.protocolStatus)
            f.reason?.let { append(", ").append(it) }
            append(")")
        }
    }
    val more = if (failedPages.size > MAX_REPORTED_FAILED_PAGES) {
        " (+${failedPages.size - MAX_REPORTED_FAILED_PAGES} more)"
    } else ""
    return "${failedPages.size} of $pagesExpected page(s) were submitted but never delivered " +
        "(${pagesFound} recorded): $shown$more"
}

/**
 * The pages a crawl has published so far, aggregated over its seed rounds.
 *
 * One round publishes only what *it* collected, and several rounds publish at the
 * same time, so the in-flight record has to add them up.  Keyed by seed index,
 * each round's latest publish replaces its own earlier one (a round only ever
 * grows), and the result is every round's pages in seed order.
 *
 * A URL-keyed union would be wrong here.  The terminal record keeps one row per
 * *fetch*: [CrawlService] concatenates the rounds' page lists, so a URL two seeds
 * both fetched is two rows.  Merging by URL would make the in-flight count
 * smaller than the terminal one — the same "the count went down" symptom this
 * aggregation exists to remove, only deferred to the end of the crawl.
 */
internal fun aggregateInFlightPages(published: Map<Int, List<CrawlPageResult>>): List<CrawlPageResult> =
    published.entries.sortedBy { it.key }.flatMap { it.value }

/**
 * Merge an in-flight progress publish into the record of a running crawl.
 *
 * A round publishes every page it records so a poller can watch a crawl fill up.
 * That publish **adds to** the record, it does not replace it: the losses and the
 * expected total reported for the seeds that already settled (`recordSeedProgress`)
 * stay visible while the remaining seeds run.  Rebuilding the record without them
 * made `failed_pages`/`pages_expected` flicker back to empty/0 mid-crawl, so a
 * consumer watching progress could not see accumulated loss.
 *
 * A terminal record is never overwritten.  A parse handler can still be running
 * when the task has already been finalized (round timeout, cancellation), and
 * moving a finished task back to PROCESSING would resurrect it: the poller would
 * wait for a task that nobody will ever finalize again.
 *
 * @return the record to store, or null when the task is already terminal and the
 *   publish must be dropped.
 */
internal fun mergeIncrementalProgress(
    taskId: String,
    previous: CrawlResponse?,
    pages: List<CrawlPageResult>,
    linksDiscovered: Int,
    diagnostic: String?,
    terminalStatuses: Set<String>,
    remaining: Int = previous?.remaining ?: 0,
    resumable: Boolean = previous?.resumable ?: false,
): CrawlResponse? {
    if (previous != null && previous.status in terminalStatuses) return null
    return CrawlResponse(
        taskId = taskId,
        status = CrawlStatus.PROCESSING,
        pagesFound = pages.size,
        linksDiscovered = linksDiscovered,
        pages = pages,
        diagnostic = diagnostic ?: previous?.diagnostic,
        // Preserve the identity and age of the task: a publish is progress, not a
        // new task (createdAt also drives the TTL purge).
        createdAt = previous?.createdAt ?: System.currentTimeMillis(),
        startedTime = previous?.startedTime ?: Instant.now(),
        seedStatuses = previous?.seedStatuses,
        // Carried over so the in-flight view stays as complete as the last
        // authoritative write: these are only recomputed when a seed settles.
        failedPages = previous?.failedPages,
        pagesExpected = previous?.pagesExpected ?: 0,
        parallelTabs = previous?.parallelTabs ?: 0,
        maxConcurrentFetches = previous?.maxConcurrentFetches ?: 0,
        // Resume bookkeeping is part of the task's identity, not of the progress a
        // publish reports: dropping it here would make a resumed task look like a
        // fresh one the moment its first page arrives.
        resumedFrom = previous?.resumedFrom,
        resumeCount = previous?.resumeCount ?: 0,
        skippedAlreadyFetched = previous?.skippedAlreadyFetched ?: 0,
        remaining = remaining,
        resumable = resumable,
    )
}

/**
 * Build a user-facing diagnostic explaining why link discovery found no
 * followable out-links on an already-parsed document.  Distinguishes
 * "page was empty" from "page had anchors but the selector / pattern
 * matched nothing".
 */
internal fun emptyOutLinksDiagnostic(
    document: FeaturedDocument,
    selector: String?,
    pattern: String?
): String {
    val allAnchors = document.select("a").size
    val htmlLength = document.html.length
    val totalElements = document.select("*").size
    return when {
        htmlLength < 200 && allAnchors == 0 ->
            "Portal page returned near-empty content ($htmlLength bytes, " +
            "$totalElements total elements, 0 anchors). " +
            "The page may not have loaded correctly. Try --refresh, " +
            "verify the URL is reachable, or check network connectivity."
        allAnchors > 0 -> {
            val selectorMatches = if (selector.isNullOrBlank()) 0 else runCatching {
                document.select(selector).size
            }.getOrDefault(-1)
            if (selectorMatches > 0) {
                "The page has $allAnchors anchors and ${htmlLength}B of HTML, " +
                "and the selector '$selector' matched " +
                "$selectorMatches element(s), but the out-link pattern " +
                "'$pattern' filtered them all. " +
                "Try a broader pattern (or drop -olp / --out-link-pattern)."
            } else {
                "The page has $allAnchors anchors and ${htmlLength}B of HTML, " +
                "but the CSS selector '$selector' matched zero " +
                "elements. Try a broader selector (e.g., 'a') or use " +
                "'htmlsnapshot inspect' to discover valid selectors."
            }
        }
        else ->
            "No out-links found. The page has 0 anchors and ${htmlLength}B " +
            "of HTML ($totalElements elements). The page may have loaded " +
            "but contains no links — verify the URL."
    }
}
