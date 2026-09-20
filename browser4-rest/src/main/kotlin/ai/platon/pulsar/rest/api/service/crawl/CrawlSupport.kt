package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.context.PulsarContext
import ai.platon.pulsar.skeleton.session.PulsarSession
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 */
internal fun unstartedSeedRound(seedUrl: String): CrawlRound = CrawlRound(
    pages = emptyList(),
    failedPages = listOf(
        CrawlFailedPage(url = seedUrl, depth = 0, protocolStatus = 0, reason = REASON_BUDGET_EXHAUSTED)
    ),
    pagesExpected = 1,
    timedOut = true,
    timeoutError = "Crawl timed out: the task budget was exhausted before every seed was submitted " +
        "(partial results saved)"
)

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
 */
internal fun isDocumentDelivered(fetched: Boolean, html: String?): Boolean =
    fetched && !html.isNullOrBlank()

/**
 * Why a page that loaded carried no bytes, in the words of its own protocol
 * status.
 *
 * The message this replaces guessed ("possible protocol handler not ready") and
 * sent users after a component that is usually healthy, while the evidence sat
 * in the page all along: a RETRY status means the fetch layer already queued
 * another attempt (typically the privacy/browser layer refusing the fetch before
 * any network I/O), and a FAILED status carries the reason that was recorded.
 * [isRetry] is checked first — a retry status also reports failed.
 */
internal fun buildZeroByteDiagnostic(page: WebPage): String {
    val status = page.protocolStatus
    val reason = status.reason?.toString()?.takeIf { it.isNotBlank() }
    val suffix = reason?.let { " ($it)" }.orEmpty()
    return when {
        status.isRetry ->
            "fetch returned 0 bytes: the fetch layer queued a retry$suffix — usually the " +
                "privacy/browser layer refusing the fetch before any network I/O; retry once " +
                "the browser pool recovers"

        status.isFailed ->
            "fetch returned 0 bytes: the fetch failed with status ${status.minorCode}$suffix"

        else -> "fetch returned 0 bytes: the page loaded but has no content"
    }
}

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
 * Compute the readonly store-serving markers for a recorded page.
 *
 * When a load serves the stored page core (options.readonly without a
 * forced -refresh), [WebPage.isCached] is true and [WebPage.fetchTime]
 * preserves the time the content was originally fetched, so the age of the
 * served content is computable.  Fresh fetches keep served=false.
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
): CrawlResponse? {
    if (previous != null && previous.status in terminalStatuses) return null
    return CrawlResponse(
        taskId = taskId,
        status = "PROCESSING",
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
