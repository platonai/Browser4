package ai.platon.pulsar.rest.api.service.crawl

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Wire model + reporting model of the crawl API.
 *
 * These types are deliberately free of crawl machinery: they are what a caller
 * sends, what the REST layer serializes, and what [CrawlService] persists to its
 * JSONL task file.  The execution side lives in [CrawlService] (task lifecycle
 * and seed scheduling) and [CrawlRoundRunner] (one round of link discovery or
 * bulk fetch).
 */
data class CrawlRequest @JsonCreator constructor(
    @param:JsonProperty("url") val url: String = "",
    @param:JsonProperty("args") val args: String = "",
    @param:JsonProperty("depth") val depth: Int = 1,
    @param:JsonProperty("urls") val urls: List<String>? = null,
    @param:JsonProperty("sql") val sql: String? = null,
    /**
     * How many independent fetch units this crawl may drive at the same time,
     * one browser tab each.
     *
     * A crawl is a set of independent seeds (and, at depth>=1, independent seed
     * rounds), so the units are parallelizable in principle; the fetch layer only
     * realizes it when each unit owns its own tab.  This value is the budget the
     * crawl enforces on itself, and the browser driver pool
     * (`browser.context.number` x `browser.max.active.tabs`) is the hard ceiling
     * above it.
     *
     * `null` (the default) uses [CrawlService.DEFAULT_PARALLEL_TABS], and `1`
     * means the historical strictly sequential crawl.
     */
    @param:JsonProperty("parallelTabs") val parallelTabs: Int? = null
)

data class CrawlSeedStatus(
    val url: String,
    val status: String,  // "fetched", "skipped", "error"
    val pagesReturned: Int = 0,
    val error: String? = null,
)

data class CrawlResponse(
    val taskId: String = "",
    val status: String = "CREATED",
    val pagesFound: Int = 0,
    /**
     * Number of out-links discovered and submitted beyond the seed URLs
     * (depth>=1 crawls).  Kept separate from [pagesFound] so a crawl that only
     * records the seed page (linksDiscovered == 0) is distinguishable from one
     * that actually found pages.
     */
    val linksDiscovered: Int = 0,
    val pages: List<CrawlPageResult>? = null,
    val error: String? = null,
    val diagnostic: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val taskTTLMinutes: Int = 60,
    /** Set when a worker first picks up the task (first non-CREATED status). */
    var startedTime: Instant? = null,
    /** Set when the task reaches a terminal state (OK, TIMEOUT, ERROR). */
    var finishTime: Instant? = null,
    /** Per-seed-URL processing status (populated when verbose). */
    val seedStatuses: List<CrawlSeedStatus>? = null,
    /**
     * Readonly-mode surfacing: what --readonly actually did for this crawl —
     * served pages from the store (with the age of the stored content) or
     * verified that every page was fetched fresh from the live site.
     */
    val readonlyNote: String? = null,
    /**
     * URLs this crawl handed to the browser and never got a page back for —
     * a fetch refused by the snapshot-origin guard until its retry budget ran
     * out, a dropped/evicted task, a terminal 4xx/5xx, or a page that was
     * parsed but never queued by this crawl.
     *
     * When this list is non-empty the crawl is *incomplete*, not merely small:
     * `pagesFound + failedPages.size == pagesExpected` always holds, so a
     * consumer can tell "the site does not have this page" from "this crawl
     * lost it".  Without it a truncated crawl is indistinguishable from a
     * complete one, because `status` stays `OK` (the CLI's poller treats
     * `OK`/`SC_OK` as the only successful terminal state, so a new status text
     * here would make every poll hang).
     */
    val failedPages: List<CrawlFailedPage>? = null,
    /** URLs this crawl submitted, seeds included.  Equals `pagesFound + failedPages.size`. */
    val pagesExpected: Int = 0,
    /**
     * The parallelism budget this crawl ran under: how many independent fetch
     * units (one browser tab each) it was allowed to drive at the same time.
     *
     * Reported so "the crawl was parallel" is inspectable instead of a claim:
     * `1` means the historical sequential crawl, and the value is always the
     * *effective* budget after clamping (see [CrawlService.resolveParallelTabs]),
     * never the raw request.
     */
    val parallelTabs: Int = 0,
    /**
     * The peak number of fetch units this crawl actually had in flight at the
     * same time — the observed counterpart of [parallelTabs].
     *
     * A value greater than 1 is proof that pages were collected in parallel.
     * It is `0` for a task that never started, and it can stay below
     * [parallelTabs] when there is simply not enough work (fewer seeds than the
     * budget) or when the browser driver pool refused to hand out that many
     * tabs.
     */
    val maxConcurrentFetches: Int = 0,
)

/**
 * A page a crawl lost: submitted to the browser, never delivered.
 *
 * @property url the URL as this crawl submitted it.
 * @property depth the discovery depth it was queued at, or -1 when unknown.
 * @property protocolStatus protocol status code when the browser reported one (0 otherwise).
 * @property reason human-readable cause, so a loss is never just a missing row.
 */
data class CrawlFailedPage(
    val url: String,
    val depth: Int = 0,
    val protocolStatus: Int = 0,
    val reason: String? = null,
)

/**
 * The outcome of one seed URL's crawl round: the pages it recorded, the pages
 * it lost, and whether it ended on its own or hit the round timeout.
 *
 * Carrying the losses out of the round is what makes them reportable: the
 * round is the only place that knows which URLs were submitted and which of
 * them never came back.
 */
data class CrawlRound(
    val pages: List<CrawlPageResult>,
    val failedPages: List<CrawlFailedPage> = emptyList(),
    val pagesExpected: Int = pages.size,
    /** True when the round hit its timeout — its losses include the outstanding URLs. */
    val timedOut: Boolean = false,
    val timeoutError: String? = null,
)

data class CrawlPageResult(
    val url: String,
    val title: String? = null,
    val contentLength: Long? = null,
    val depth: Int = 0,
    val extracted: List<Map<String, Any?>>? = null,
    /** Non-null when X-SQL extraction was attempted but failed on this page. */
    val extractionError: String? = null,
    /** True when the content was served from the page store (--readonly), not fetched from the live site. */
    val servedFromStore: Boolean = false,
    /** Age in seconds of the stored content when [servedFromStore]. */
    val storeAgeSeconds: Long? = null,
)
