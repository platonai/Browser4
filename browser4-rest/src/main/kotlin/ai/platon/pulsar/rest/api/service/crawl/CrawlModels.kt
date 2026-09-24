package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.common.ResourceStatus
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
    @param:JsonProperty("parallelTabs") val parallelTabs: Int? = null,
    /**
     * How long this crawl may run before the server cancels it (ms).
     *
     * The budget is the crawl's own clock, not a per-request timeout: a round derives its
     * timeout from what is left of it (see `CrawlService.resolveRoundTimeoutMs`), and a seed
     * that cannot fit is reported as an unstarted loss instead of being dropped.  It is
     * therefore also what a caller tunes to make a big crawl finish *and report* rather than
     * be killed mid-flight.
     *
     * `null` (the default) uses the server's `CrawlService.taskTimeoutMillis` (10 minutes).
     * A value above zero is clamped to the per-request range
     * (`MIN_REQUEST_TASK_TIMEOUT_MS`..`MAX_REQUEST_TASK_TIMEOUT_MS`), which
     * `CrawlResponse.taskTimeoutMillis` always reports — so a clamped request is visible
     * rather than silently different.  A non-positive value means "no preference" and falls
     * back to the server default: the budget is a limit, not a switch, and `0` must not mean
     * "cancel immediately".
     */
    @param:JsonProperty("taskTimeoutMillis") val taskTimeoutMillis: Long? = null
)

/**
 * The one spelling per crawl task state, defined once.
 *
 * The service used to mix two vocabularies: bare tokens ([CrawlResponse.status]
 * defaulted to `"CREATED"`, and the worker wrote `"PROCESSING"`) next to
 * [ResourceStatus] display text (`"OK"`, `"Request Timeout"`).  Every consumer
 * had to guess which one it would see, and the CLI guessed wrong: it matched the
 * token spellings, so `"Created"` came out as `"created"`, `"Request Timeout"` as
 * `"request timeout"` and `"Not Found"` fell through entirely -- only `"OK"`
 * happened to agree between the two vocabularies.
 *
 * Display text is canonical here because that is what the REST payload has always
 * carried for settled tasks; test-side waits that need to survive either spelling
 * should compare through [isTerminal] / [isRunning] instead of literals.
 */
object CrawlStatus {
    val CREATED: String = ResourceStatus.getStatusText(ResourceStatus.SC_CREATED)
    val PROCESSING: String = ResourceStatus.getStatusText(ResourceStatus.SC_PROCESSING)
    val OK: String = ResourceStatus.getStatusText(ResourceStatus.SC_OK)
    val REQUEST_TIMEOUT: String = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT)
    val INTERNAL_SERVER_ERROR: String = ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR)
    val NOT_FOUND: String = ResourceStatus.getStatusText(ResourceStatus.SC_NOT_FOUND)

    /**
     * The state of a task whose worker is gone because the process died
     * (restart, crash, `SIGKILL`) — see [CrawlService.restoreFromDisk].
     *
     * It is deliberately *terminal*: nothing will ever move such a task again
     * unless someone asks for it in so many words (`crawl resume`), and a status
     * that is neither terminal nor pollable would make every `crawl status` wait
     * hang forever.  It is also resumable, which is what [CrawlResponse.resumable]
     * reports, so "terminal" here means "the worker stopped", not "the work is
     * gone".
     *
     * The display text is spelled out rather than derived from [ResourceStatus]
     * because the status vocabulary has no interrupted code — `Gone` (410) is the
     * closest and means something else entirely.
     */
    val INTERRUPTED: String = "Interrupted"

    /** States a task never leaves. */
    val TERMINAL: Set<String> = setOf(OK, REQUEST_TIMEOUT, INTERNAL_SERVER_ERROR, NOT_FOUND, INTERRUPTED)

    /** States a task is still making progress in. */
    val RUNNING: Set<String> = setOf(CREATED, PROCESSING)

    fun isTerminal(status: String): Boolean = status in TERMINAL

    fun isRunning(status: String): Boolean = status in RUNNING

    /** True when a task in [status] stopped because its worker died, and can be resumed. */
    fun isInterrupted(status: String): Boolean = status == INTERRUPTED
}

data class CrawlSeedStatus(
    val url: String,
    val status: String,  // "fetched", "skipped", "error"
    val pagesReturned: Int = 0,
    val error: String? = null,
)

data class CrawlResponse(
    val taskId: String = "",
    val status: String = CrawlStatus.CREATED,
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
     * The task budget this crawl ran under (ms): how long it was allowed to run before the
     * server's task limit cancels it.
     *
     * Reported for the same reason [parallelTabs] is: the value is always the *effective*
     * one — the request's own budget after clamping, or the server default when the request
     * asked for none (see `CrawlService.resolveTaskTimeoutMillis`) — never the raw request.
     * It is `0` for a task that never started.
     */
    val taskTimeoutMillis: Long = 0,
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
    /**
     * When this run resumed an interrupted crawl: the instant the interruption it
     * continued from was recorded.  `null` for a task that never resumed, so
     * "this task ran twice" is readable off the record instead of being inferred
     * from timestamps.
     */
    val resumedFrom: Instant? = null,
    /**
     * How many times this task has been resumed.  `0` for a task that ran once,
     * `1` after the first `crawl resume`, and so on.
     */
    val resumeCount: Int = 0,
    /**
     * URLs that were already fetched before the interruption and were therefore
     * **not** requested again — the observable form of the promise that a resume
     * does not re-hit the target site for work it already has.
     */
    val skippedAlreadyFetched: Int = 0,
    /**
     * How much work is left that a resume would pick up **without being asked
     * twice**: the URLs that were submitted and never settled, the links the crawl
     * discovered but never queued, and one per seed it never started.  It is `0` for
     * a crawl that finished its submissions — which can still be [resumable], because
     * a crawl that ended with terminally failed URLs keeps a checkpoint for
     * `crawl resume --retry-failed`.
     */
    val remaining: Int = 0,
    /**
     * True when this task carries a checkpoint a resume could use: URLs left to fetch
     * ([remaining] > 0), or URLs that failed terminally and `--retry-failed` would
     * fetch again.  False when no work state was persisted, or when it has nothing
     * left to do at all — so `crawl resume` never has to guess whether it is worth
     * calling.
     */
    val resumable: Boolean = false,
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
    /**
     * The URLs the round handed to the session that never settled.  A subset of
     * [failedPages] for reporting (`pagesFound + failedPages.size == pagesExpected`
     * must hold), and kept separately because they are *not* terminal failures:
     * a resume re-submits them with a fresh attempt budget, while a [failedPages]
     * entry that is not outstanding stays failed.
     */
    val outstanding: List<CrawlFailedPage> = emptyList(),
    /**
     * Links this round discovered at `depth < maxDepth` that it never handed to
     * the session — the frontier a resumed crawl continues from instead of
     * restarting at the seeds.
     *
     * Deliberately *not* part of [pagesExpected]: nothing was submitted, so
     * counting them would break the loss accounting.
     */
    val frontier: List<CrawlFailedPage> = emptyList(),
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
    /**
     * When this row was recorded.  On a resumed crawl it is the provenance marker
     * that separates the rows of the first run from the rows of the resume, so a
     * consumer can tell which rows were fetched in which run (see [run]).
     */
    val fetchedAt: Instant? = null,
    /**
     * Which run produced this row: `1` for the run the task was submitted for,
     * `2` for the first resume, and so on.  A resumed crawl's result is a merge
     * of several runs, and this is what makes the merge readable.
     */
    val run: Int = 1,
    /**
     * True when the row was restored from a checkpoint instead of being fetched by
     * the current run — the per-URL counterpart of
     * [CrawlResponse.skippedAlreadyFetched].
     */
    val restoredFromCheckpoint: Boolean = false,
)
