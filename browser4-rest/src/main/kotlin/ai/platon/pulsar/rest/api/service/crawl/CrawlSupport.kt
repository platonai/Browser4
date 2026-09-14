package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
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
 * Note the deliberate asymmetry: this key is used to *dedupe submissions* and
 * to report losses, never to decide that a page arrived.  A page's final URL
 * can differ from the URL it was submitted under (redirects,
 * `document.baseURI`), so settlement is counted per page, not matched by URL.
 */
internal fun normalizeForVisit(url: String): String {
    return url.trim().lowercase()
        .removeSuffix("/")
        .substringBefore('#')
        .substringBefore('?')  // strip query for dedup
}

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
