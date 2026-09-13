package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.url.ParsableHyperlink
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.sql.ResultSet
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

data class CrawlRequest @JsonCreator constructor(
    @param:JsonProperty("url") val url: String = "",
    @param:JsonProperty("args") val args: String = "",
    @param:JsonProperty("depth") val depth: Int = 1,
    @param:JsonProperty("urls") val urls: List<String>? = null,
    @param:JsonProperty("sql") val sql: String? = null
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
    var startedTime: java.time.Instant? = null,
    /** Set when the task reaches a terminal state (OK, TIMEOUT, ERROR). */
    var finishTime: java.time.Instant? = null,
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

@Service
class CrawlService(
    private val sessionManager: PulsarSessionManager
) {
    private val logger = LoggerFactory.getLogger(CrawlService::class.java)

    /**
     * Task store: taskId -> CrawlResponse.
     *
     * Size-bounded at 100 entries; Window TinyLFU eviction beyond that.
     * Terminal tasks are purged by TTL in [purgeExpiredTasks] long before the
     * cache fills up in normal operation.
     */
    private val taskStore: Cache<String, CrawlResponse> = Caffeine.newBuilder()
        .maximumSize(100)
        .recordStats()
        .build()

    /** Active coroutine jobs: taskId -> Job (for cancellation) */
    private val jobStore = ConcurrentHashMap<String, Job>()

    /** Terminal task states: OK, TIMEOUT, ERROR.  Tasks in these states are
     *  purgeable, clearable, and never re-finalized by a late cancellation. */
    private val terminalStatuses = setOf(
        ResourceStatus.getStatusText(ResourceStatus.SC_OK),
        ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
        ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
    )

    internal val persistence = JsonlPersistence(
        file = crawlPersistencePath(),
        clazz = CrawlResponse::class,
        objectMapper = pulsarObjectMapper()
    )

    @EventListener(ApplicationReadyEvent::class)
    fun restoreFromDisk() {
        val now = System.currentTimeMillis()
        val ttlMillis = taskTtlMinutes * 60_000L
        persistence.restore { entry ->
            if (entry.taskId.isBlank()) return@restore
            // Skip terminal entries that have already expired — they were
            // purged from memory before shutdown and should not be revived.
            if (entry.status in terminalStatuses && (now - entry.createdAt) > ttlMillis) {
                logger.debug("Skipping expired crawl task {} during restore", entry.taskId)
                return@restore
            }
            taskStore.put(entry.taskId, entry)
        }
    }

    private fun onStatusChanged(response: CrawlResponse) {
        persistence.append(response)
    }

    /** Dedicated dispatcher for crawl operations */
    private val crawlDispatcher = Dispatchers.IO.limitedParallelism(5)

    private val crawlScope = CoroutineScope(
        crawlDispatcher + SupervisorJob() + CoroutineName("crawl")
    )

    /** How long to keep completed/failed tasks in the store (minutes). */
    @Volatile
    var taskTtlMinutes: Int = 1440 // 1 day

    init {
        // Periodically purge expired tasks so stale entries don't accumulate
        crawlScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // every 5 minutes
                purgeExpiredTasks()
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        crawlScope.cancel()
    }

    /**
     * Submit a crawl task. Returns the task ID immediately; the crawl runs
     * asynchronously.  Poll [getResult] to retrieve the final response.
     */
    fun submit(request: CrawlRequest): String {
        val taskId = UUID.randomUUID().toString()
        val response = CrawlResponse(
            taskId = taskId,
            status = ResourceStatus.getStatusText(ResourceStatus.SC_CREATED)
        )
        taskStore.put(taskId, response)
        onStatusChanged(response)

        // Compute effective seed URL list
        val seedUrls = if (!request.urls.isNullOrEmpty()) {
            request.urls
        } else if (request.url.isNotBlank()) {
            listOf(request.url)
        } else {
            emptyList()
        }

        if (seedUrls.isEmpty()) {
            // Terminal records always carry started/finish timestamps so
            // consumers can distinguish an instantly-failed task from one that
            // never ran (and the CLI summary can show elapsed time).
            val now = java.time.Instant.now()
            val errorResponse = CrawlResponse(
                taskId = taskId,
                status = ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
                error = "No URLs provided",
                startedTime = now,
                finishTime = now
            )
            taskStore.put(taskId, errorResponse)
            onStatusChanged(errorResponse)
            return taskId
        }

        val job = crawlScope.launch {
            try {
                // Mark as "PROCESSING" as soon as the worker picks up the task.
                // Without this, the CLI sees "CREATED" for the entire duration
                // of the crawl (which can be 80-100s for many URLs), making it
                // appear as if nothing is happening.
                val processing = CrawlResponse(
                    taskId = taskId,
                    status = "PROCESSING",
                    pagesFound = 0,
                    startedTime = java.time.Instant.now()
                )
                taskStore.put(taskId, processing)
                onStatusChanged(processing)
                // Out-links discovered beyond the seed URLs (depth>=1 crawls),
                // aggregated across seeds.  Kept separate from results.size so a
                // crawl that only records seed page(s) (0 discovered links) is
                // distinguishable from one that followed links.
                val linksDiscovered = AtomicInteger()
                // Per-seed round outcomes, kept so the terminal response can
                // report the pages that were submitted and never delivered.
                // Written only by the (sequential) seed loop below.
                val rounds = mutableListOf<CrawlRound>()
                val result = withTimeout(CRAWL_TASK_TIMEOUT_MS.milliseconds) {
                    withContext(Dispatchers.IO) {
                    val results = mutableListOf<CrawlPageResult>()
                    val seedStatuses = mutableListOf<CrawlSeedStatus>()
                    val totalSeeds = seedUrls.size

                    // For depth=0 (bulk fetch mode), reuse a single session across
                    // all seed URLs.  Creating a new session per seed causes the HTTP
                    // protocol handler to be deregistered when the previous session is
                    // closed, and the next session's handler may not re-register in time
                    // for the page load — producing "Protocol not found" (status 1600)
                    // for every seed after the first.  A single session avoids the
                    // deregistration/re-registration cycle entirely.
                    val sharedDepth0Session = if (request.depth == 0) {
                        sessionManager.agenticContext.createSession()
                    } else {
                        null
                    }

                    try {
                        for ((index, seedUrl) in seedUrls.withIndex()) {
                            logger.info(
                                "Crawl {}: processing seed URL {}/{}: {}",
                                taskId, index + 1, totalSeeds, seedUrl
                            )
                            val seedRequest = request.copy(url = seedUrl, urls = null)
                            val round = try {
                                val fetched = when {
                                    // Depth=0 is bulk fetch: one URL, no link
                                    // discovery, so its pages are its whole round.
                                    seedRequest.depth == 0 -> CrawlRound(
                                        pages = crawlDepth0(taskId, seedRequest, sharedDepth0Session)
                                    )
                                    seedRequest.depth <= 1 -> crawlDepth1(taskId, seedRequest, linksDiscovered)
                                    else -> crawlDepthN(taskId, seedRequest, linksDiscovered)
                                }
                                logger.info(
                                    "Crawl {}: seed URL {}/{} completed: {} → {} page(s), {} lost",
                                    taskId, index + 1, totalSeeds, seedUrl,
                                    fetched.pages.size, fetched.failedPages.size
                                )
                                seedStatuses.add(CrawlSeedStatus(
                                    url = seedUrl,
                                    status = "fetched",
                                    pagesReturned = fetched.pages.size
                                ))
                                fetched
                            } catch (e: CancellationException) {
                                // The task-wide timeout cancelled this crawl: let it
                                // propagate so the outer handler can write the
                                // TIMEOUT record.  Swallowing it here marked the seed
                                // "error", continued with the next seed and then let
                                // the terminal write report OK — a cancelled crawl
                                // that looked successful.
                                throw e
                            } catch (e: Exception) {
                                logger.error(
                                    "Crawl {}: seed URL {}/{} failed: {} — {}",
                                    taskId, index + 1, totalSeeds, seedUrl, e.message, e
                                )
                                seedStatuses.add(CrawlSeedStatus(
                                    url = seedUrl,
                                    status = "error",
                                    pagesReturned = 0,
                                    error = e.message
                                ))
                                // The seed failure is carried by seedStatuses (the
                                // synthetic row keeps the URL visible in `pages`),
                                // so it is not also a lost page: the
                                // `pages + failedPages == pagesExpected` invariant
                                // must stay exact.
                                CrawlRound(
                                    pages = listOf(
                                        CrawlPageResult(
                                            url = seedUrl,
                                            title = null,
                                            contentLength = null,
                                            depth = 0
                                        )
                                    ),
                                    failedPages = emptyList(),
                                    pagesExpected = 1
                                )
                            }
                            rounds.add(round)
                            results.addAll(round.pages)

                            // Publish incremental progress to the in-memory task
                            // store so the CLI polling loop can show per-seed
                            // extraction progress (e.g. "2/5 seeds done, 4 rows
                            // extracted so far").  We only update the in-memory
                            // store — persistence.append is deferred until the
                            // crawl completes to avoid writing intermediate states.
                            val currentResult = taskStore.getIfPresent(taskId)
                            val incrementalResponse = CrawlResponse(
                                taskId = taskId,
                                status = "PROCESSING",
                                pagesFound = results.size,
                                linksDiscovered = linksDiscovered.get(),
                                pages = results.toList(),
                                diagnostic = currentResult?.diagnostic,
                                startedTime = currentResult?.startedTime ?: java.time.Instant.now(),
                                seedStatuses = seedStatuses.toList(),
                                // Losses are visible while the crawl still runs,
                                // not only in the terminal response.
                                failedPages = rounds.flatMap { it.failedPages },
                                pagesExpected = rounds.sumOf { it.pagesExpected }
                            )
                            taskStore.put(taskId, incrementalResponse)

                            // Small delay between seed URLs to allow the browser
                            // time to settle between page loads.  When using a shared
                            // session this is less critical (no protocol handler
                            // re-registration), but still prevents resource contention.
                            if (index < totalSeeds - 1) {
                                delay(SEED_INTERVAL_MS.milliseconds)
                            }
                        }
                    } finally {
                        runCatching { sharedDepth0Session?.close() }
                    }
                    Pair(results, seedStatuses)
                }
                } // withTimeout
                val (allPages, seedStatuses) = result
                val existingDiagnostic = taskStore.getIfPresent(taskId)?.diagnostic
                val now = java.time.Instant.now()
                val previous = taskStore.getIfPresent(taskId)
                // --readonly must not be silent: either pages came from the page
                // store (say so, with the age of the stored content) or every
                // page was verified fetched fresh from the live site.
                val readonlyRequested = request.args.split(Regex("\\s+")).any {
                    it == "-readonly" || it.startsWith("-readonly=")
                }
                val readonlyNote = if (readonlyRequested) buildReadonlyNote(allPages) else null
                val failedPages = rounds.flatMap { it.failedPages }
                val pagesExpected = rounds.sumOf { it.pagesExpected }
                // A round that hit its own timeout already wrote a TIMEOUT record;
                // this terminal write used to overwrite it with OK, so a truncated
                // crawl reported success.  Keep the timeout status and say how
                // much of the work never completed.
                val timedOut = rounds.firstOrNull { it.timedOut }
                val lossNote = buildLossNote(allPages.size, pagesExpected, failedPages)
                val completed = CrawlResponse(
                    taskId = taskId,
                    status = if (timedOut != null) {
                        ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT)
                    } else {
                        ResourceStatus.getStatusText(ResourceStatus.SC_OK)
                    },
                    pagesFound = allPages.size,
                    linksDiscovered = linksDiscovered.get(),
                    pages = allPages,
                    // The loss note is appended, never substituted: a diagnostic
                    // that explained "no out-links" must not hide dropped pages.
                    diagnostic = listOfNotNull(existingDiagnostic, lossNote).joinToString(" | ")
                        .takeIf { it.isNotBlank() },
                    error = timedOut?.timeoutError,
                    startedTime = previous?.startedTime ?: now,
                    finishTime = now,
                    seedStatuses = seedStatuses,
                    readonlyNote = readonlyNote,
                    failedPages = failedPages.takeIf { it.isNotEmpty() },
                    pagesExpected = pagesExpected
                )
                taskStore.put(taskId, completed)
                onStatusChanged(completed)
                logger.info(
                    "Crawl task {} completed: {} pages, {} lost, status {}",
                    taskId, allPages.size, failedPages.size, completed.status
                )
            } catch (e: CancellationException) {
                val existing = taskStore.getIfPresent(taskId)
                val now = java.time.Instant.now()
                // A cancellation always transitions the task to a terminal
                // state.  Historically this branch only wrote TIMEOUT when the
                // task was still CREATED, so a task whose worker died inside
                // withTimeout(CRAWL_TASK_TIMEOUT_MS) stayed PROCESSING forever
                // (uncancellable, invisible to 'crawl clear', purged only by
                // TTL).  Distinguish the two sources: cancel() removes the job
                // from jobStore and already wrote a terminal record, so a
                // record in a terminal state is left alone; anything still
                // PROCESSING/CREATED is a seed-processing timeout and must be
                // finalized with whatever partial progress exists.
                val alreadyTerminal = existing != null && existing.status in terminalStatuses
                if (!alreadyTerminal) {
                    val timedOut = CrawlResponse(
                        taskId = taskId,
                        status = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
                        error = if (existing?.status == "PROCESSING") {
                            "Crawl timed out while processing seeds (server-side limit of " +
                                "${CRAWL_TASK_TIMEOUT_MS / 1000}s exceeded). Partial results below."
                        } else {
                            "Crawl cancelled or timed out"
                        },
                        pagesFound = existing?.pages?.size ?: 0,
                        linksDiscovered = existing?.linksDiscovered ?: 0,
                        pages = existing?.pages,
                        seedStatuses = existing?.seedStatuses,
                        diagnostic = existing?.diagnostic,
                        startedTime = existing?.startedTime ?: now,
                        finishTime = now
                    )
                    taskStore.put(taskId, timedOut)
                    onStatusChanged(timedOut)
                }
                logger.warn("Crawl task {} cancelled or timed out: {}", taskId, e.message)
            } catch (e: Exception) {
                val existing = taskStore.getIfPresent(taskId)
                val now = java.time.Instant.now()
                val failed = CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_INTERNAL_SERVER_ERROR),
                    error = e.message ?: "Unknown error",
                    startedTime = existing?.startedTime ?: now,
                    finishTime = now
                )
                taskStore.put(taskId, failed)
                onStatusChanged(failed)
                logger.error("Crawl task {} failed: {}", taskId, e.message, e)
            } finally {
                jobStore.remove(taskId)
            }
        }

        jobStore[taskId] = job

        logger.info("Crawl task submitted: {} seeds={} depth={}", taskId, seedUrls.size, request.depth)
        return taskId
    }

    /**
     * Cancel a running crawl task by its ID.
     * @return true if the task was found and cancelled, false otherwise.
     */
    fun cancel(taskId: String): Boolean {
        val job = jobStore.remove(taskId) ?: return false
        job.cancel()
        val now = java.time.Instant.now()
        val previous = taskStore.getIfPresent(taskId)
        val cancelled = CrawlResponse(
            taskId = taskId,
            status = ResourceStatus.getStatusText(ResourceStatus.SC_REQUEST_TIMEOUT),
            error = "Cancelled by user",
            startedTime = previous?.startedTime ?: now,
            finishTime = now
        )
        taskStore.put(taskId, cancelled)
        onStatusChanged(cancelled)
        logger.info("Crawl task {} cancelled by user", taskId)
        return true
    }

    /**
     * Remove all terminal-state tasks from the store.
     * @return the number of tasks removed.
     */
    fun clearTerminal(): Int {
        val toRemove = taskStore.asMap().entries.filter { it.value.status in terminalStatuses }
        toRemove.forEach { taskStore.invalidate(it.key) }
        logger.info("Cleared {} terminal crawl tasks", toRemove.size)

        // Rewrite the JSONL persistence file so cleared tasks don't revive on restart.
        // Without this, terminal tasks removed from the in-memory Caffeine cache are
        // re-read from the append-only JSONL file at startup.
        if (toRemove.isNotEmpty()) {
            persistence.clear()
            taskStore.asMap().values.forEach { persistence.append(it) }
        }

        return toRemove.size
    }

    /**
     * Remove ALL tasks from the store, including actively-running ones.
     * Cancels running jobs before clearing.  Use with caution.
     * @return the number of tasks removed.
     */
    fun clearAll(): Int {
        // Cancel all active jobs first
        jobStore.values.forEach { it.cancel() }
        jobStore.clear()

        val size = taskStore.asMap().size.toInt()
        taskStore.invalidateAll()
        persistence.clear()
        logger.info("Cleared all {} crawl tasks (including active)", size)
        return size
    }

    /**
     * Purge tasks whose TTL has expired.  Only removes terminal-state tasks;
     * actively-running tasks are never purged.
     */
    private fun purgeExpiredTasks() {
        val now = System.currentTimeMillis()
        val ttlMillis = taskTtlMinutes * 60_000L

        val expired = taskStore.asMap().entries.filter {
            it.value.status in terminalStatuses &&
                (now - it.value.createdAt) > ttlMillis
        }
        if (expired.isEmpty()) return

        expired.forEach { taskStore.invalidate(it.key) }
        logger.info("Purged {} expired crawl tasks (TTL: {} min)", expired.size, taskTtlMinutes)

        // Rewrite the persistence file so purged tasks don't revive on restart.
        // The JSONL is append-only and would otherwise accumulate stale entries forever.
        persistence.clear()
        taskStore.asMap().values.forEach { persistence.append(it) }
    }

    /**
     * Get the current status/result of a crawl task.
     */
    fun getResult(taskId: String): CrawlResponse {
        return taskStore.getIfPresent(taskId) ?: CrawlResponse(
            taskId = taskId,
            status = ResourceStatus.getStatusText(ResourceStatus.SC_NOT_FOUND),
            error = "Task not found: $taskId"
        )
    }

    // ------------------------------------------------------------------
    // Depth=0: fetch each seed URL directly, no link discovery
    // ------------------------------------------------------------------

    /**
     * Fetch a single seed URL at depth=0 and optionally run X-SQL extraction.
     *
     * @param sharedSession When provided, this session is reused instead of
     *   creating a new one, and it is NOT closed when the method returns.
     *   This eliminates protocol-handler deregistration races when processing
     *   multiple depth=0 seeds in sequence.
     */
    private suspend fun crawlDepth0(
        taskId: String,
        request: CrawlRequest,
        sharedSession: PulsarSession? = null
    ): List<CrawlPageResult> {
        // Depth=0 bulk-fetch: always use -refresh so that internal HTTP
        // caches and protocol-level state from prior sessions don't cause
        // 0-byte responses for URLs after the first.
        val effectiveArgs = if (request.args.isBlank()) "-refresh" else "${request.args} -refresh"

        var lastError: Exception? = null
        repeat(MAX_FETCH_RETRIES) { attempt ->
            // Reuse the shared session if provided; otherwise create a private one.
            // Only private (owned) sessions are closed in the finally block.
            val ownsSession = sharedSession == null
            val session: PulsarSession = sharedSession ?: sessionManager.agenticContext.createSession()

            try {
                val options = parseOptions(session, effectiveArgs)
                val page = session.load(request.url, options)

                // If the page loaded but content is empty, retry after a delay.
                // When using a shared session the protocol handler shouldn't be
                // an issue, but transient network problems can still cause this.
                if (page.contentLength == 0L) {
                    val msg = "fetch returned 0 bytes (possible protocol handler not ready)"
                    if (attempt < MAX_FETCH_RETRIES - 1) {
                        val retryDelay = FETCH_RETRY_DELAY_MS * (1L shl attempt)
                        logger.warn("Crawl {}: {} for '{}', retrying in {}ms (attempt {}/{})",
                            taskId, msg, request.url, retryDelay, attempt + 1, MAX_FETCH_RETRIES)
                        if (ownsSession) runCatching { session.close() }
                        delay(retryDelay)
                        return@repeat
                    }
                    logger.error("Crawl {}: {} for '{}' after {} attempts", taskId, msg, request.url, MAX_FETCH_RETRIES)
                    return listOf(CrawlPageResult(
                        url = request.url, title = null, contentLength = 0, depth = 0,
                        extractionError = msg
                    ))
                }

                val document = session.parse(page)

                val extractionResult = if (request.sql != null) {
                    executeSqlQuery(session, request.url, request.sql)
                } else Pair(null, null)

                // Fallback: if document.title is blank, try extracting <title>
                // from the raw HTML. The parse pipeline may skip title extraction
                // on cached/stale content, leaving document.title null/empty even
                // though the raw HTML has a valid <title> tag.
                val title = document.title.takeIf { !it.isNullOrBlank() }
                    ?: extractTitleFromHtml(document.html)

                val (servedFromStore, storeAgeSeconds) = storeServeMarkers(page)
                val result = CrawlPageResult(
                    url = request.url,
                    title = title,
                    contentLength = page.contentLength,
                    depth = 0,
                    extracted = extractionResult.first,
                    extractionError = extractionResult.second,
                    servedFromStore = servedFromStore,
                    storeAgeSeconds = storeAgeSeconds
                )
                logger.info("Crawl {}: fetched seed URL {} ({} bytes)", taskId, request.url, page.contentLength)
                return listOf(result)
            } catch (e: Exception) {
                lastError = e
                val isProtocolError = e.message?.contains("Protocol not found", ignoreCase = true) == true
                    || e.javaClass.simpleName.contains("ProtocolNotFound")
                if (attempt < MAX_FETCH_RETRIES - 1 && isProtocolError) {
                    val retryDelay = FETCH_RETRY_DELAY_MS * (1L shl attempt)
                    logger.warn("Crawl {}: protocol error for '{}', retrying in {}ms (attempt {}/{})",
                        taskId, request.url, retryDelay, attempt + 1, MAX_FETCH_RETRIES)
                    if (ownsSession) runCatching { session.close() }
                    delay(retryDelay)
                    return@repeat
                }
                logger.error("Crawl {}: failed to fetch seed URL {}", taskId, request.url, e)
                return listOf(
                    CrawlPageResult(
                        url = request.url,
                        title = null,
                        contentLength = null,
                        depth = 0
                    )
                )
            } finally {
                if (ownsSession) runCatching { session.close() }
            }
        }

        // All retries exhausted with 0-byte results
        logger.error("Crawl {}: all {} fetch attempts returned 0 bytes for '{}'", taskId, MAX_FETCH_RETRIES, request.url)
        return listOf(CrawlPageResult(
            url = request.url, title = null, contentLength = 0, depth = 0,
            extractionError = lastError?.message ?: "All fetch attempts returned 0 bytes"
        ))
    }

    // ------------------------------------------------------------------
    // Depth=1: extract out-links from the portal page and load each one
    // ------------------------------------------------------------------

    private suspend fun crawlDepth1(taskId: String, request: CrawlRequest, linksDiscovered: AtomicInteger): CrawlRound {
        val session = sessionManager.agenticContext.createSession()
        val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
        // Per-crawl settle bookkeeping: a round is complete only when every
        // submitted URL produced a page or a reported failure, and no parse
        // handler is still running.  A bare `pendingCount == 0` comparison
        // cannot see a fetch that failed (issue #592), and lets a handler
        // that is still discovering links be overtaken by the others.
        val ledger = CrawlLedger(taskId)
        try {
            // Always add -refresh so the portal page is loaded with fresh content.
            // Without this, cached empty/malformed pages cause link discovery to
            // return 0 elements even when the page has many anchors in the live DOM.
            val effectiveArgs = buildEffectiveArgs(request.args)
            val options = parseOptions(session, effectiveArgs)
            if (options.outLinkSelector.isNullOrBlank()) {
                // If X-SQL extraction was requested but no out-link selector is configured,
                // auto-switch to depth=0 behavior (bulk fetch + extraction).  This prevents
                // the silent "0 pages returned" UX trap where users specify --sql without
                // --depth 0 (which defaults to depth=1 link-discovery mode).
                if (request.sql != null) {
                    logger.info(
                        "Crawl {}: X-SQL extraction requested without --out-link-selector; " +
                        "auto-switching to depth=0 (bulk fetch + extraction mode)",
                        taskId
                    )
                    runCatching { session.close() }
                    return CrawlRound(pages = crawlDepth0(taskId, request))
                }
                logger.warn("Crawl {}: no outLinkSelector provided, returning empty result", taskId)
                return CrawlRound(pages = emptyList(), pagesExpected = 0)
            }
            val outLinks = extractOutLinks(session, request.url, options)

            if (outLinks.isEmpty()) {
                // Build a diagnostic that checks document health before blaming the selector.
                // extractOutLinks already logged document.select("a").size and html.length;
                // produce a user-facing diagnostic that distinguishes "page was empty" from
                // "page had content but selector didn't match".
                val diagnostic = try {
                    val normOptions = session.normalize(options)
                    val document = session.loadDocument(request.url, normOptions)
                    emptyOutLinksDiagnostic(document, options.outLinkSelector, options.outLinkPattern)
                } catch (e: Exception) {
                    "Failed to load portal page: ${e.message}. " +
                    "Verify the URL is accessible and retry."
                }
                logger.info("Crawl {}: {}", taskId, diagnostic)
                taskStore.put(taskId, CrawlResponse(
                    taskId = taskId,
                    status = ResourceStatus.getStatusText(ResourceStatus.SC_OK),
                    pagesFound = 0,
                    diagnostic = diagnostic
                ))
                return CrawlRound(pages = emptyList(), pagesExpected = 0)
            }

            logger.info("Crawl {}: found {} out-links, submitting...", taskId, outLinks.size)
            linksDiscovered.addAndGet(outLinks.size)

            // Per-crawl settle bookkeeping: a round is complete only when every
            // submitted URL produced a page or a reported failure, and no parse
            // handler is still running.  A bare `pendingCount == 0` comparison
            // cannot see a fetch that failed (issue #592), and lets a handler
            // that is still discovering links be overtaken by the others.
            // onHTMLDocumentParsed can fire more than once for a single page
            // (re-parse after refresh, shared-session reloads).  Only the first
            // event may add the result or settle the URL — otherwise one page
            // can appear twice in the listing and the crawl can complete
            // before every page has been collected.
            val recorded = ConcurrentHashMap.newKeySet<String>()

            // Submit each out-link as a ParsableHyperlink so we can collect results.
            // Include -refresh so each out-link is fetched fresh — without it, internal
            // HTTP caches or stale protocol state can cause 0-byte responses.
            outLinks.forEach { linkUrl ->
                // -readonly is forwarded so depth-1 page loads honor it too (no
                // store writes) instead of silently ignoring the flag.
                val readonlySuffix = if (options.readonly) " -readonly" else ""
                val onParse = parse@{ _page: WebPage, _document: FeaturedDocument ->
                    if (!ledger.enter()) {
                        logger.debug(
                            "Crawl {}: ignoring a late parse event for '{}' — the round is already complete",
                            taskId, linkUrl
                        )
                        return@parse null
                    }
                    try {
                        // Only the first parse event for a URL records the result
                        // and settles the URL; duplicates are dropped.
                        if (recorded.add(normalizeForVisit(linkUrl))) {
                            val extractionResult = if (request.sql != null) {
                                executeSqlQuery(session, linkUrl, request.sql)
                            } else Pair(null, null)
                            val (servedFromStore, storeAgeSeconds) = storeServeMarkers(_page)
                            synchronized(results) {
                                results.add(
                                    CrawlPageResult(
                                        url = linkUrl,
                                        title = _document.title.takeIf { !it.isNullOrBlank() }
                                            ?: extractTitleFromHtml(_document.html),
                                        contentLength = _page.contentLength,
                                        depth = 1,
                                        extracted = extractionResult.first,
                                        extractionError = extractionResult.second,
                                        servedFromStore = servedFromStore,
                                        storeAgeSeconds = storeAgeSeconds
                                    )
                                )
                                // Publish in-memory progress so the CLI poll loop sees
                                // pages as they arrive instead of 'waiting for first
                                // page' for the whole seed round.
                                publishIncremental(taskId, results.toList(), linksDiscovered.get())
                            }
                            ledger.recordSuccess(linkUrl)
                        } else {
                            logger.debug("Crawl {}: duplicate parse event for {}; already recorded", taskId, linkUrl)
                        }
                    } catch (t: Throwable) {
                        // A page whose parse handler died is a lost page, not a
                        // page that is still coming: report it, then keep the
                        // original propagation semantics.
                        ledger.recordFailure(
                            linkUrl, 1, _page.protocolStatus.minorCode,
                            "the parse handler failed for this page: ${t.message}"
                        )
                        throw t
                    } finally {
                        ledger.leave()
                    }
                    null
                }
                val hyperlink = ParsableHyperlink("$linkUrl -parse -refresh$readonlySuffix", onParse)
                // A fetch that never fires a parse event (retry budget exhausted,
                // dropped task, terminal 4xx/5xx) is settled here instead of
                // vanishing from the result.
                hyperlink.eventHandlers.crawlEventHandlers.onLoaded.addLast { _, loaded ->
                    settleFromLoaded(ledger, linkUrl, 1, loaded)
                    null
                }
                ledger.submit(linkUrl, 1)
                session.submit(hyperlink)
            }

            // Wait until every submitted out-page settled (per-crawl, not global)
            withTimeout(300_000L) { // 5 minute timeout for depth=1
                ledger.awaitAllSettled()
            }

            // Deterministic ordering: identical runs over an unchanged site
            // produce identical listings.
            return CrawlRound(
                pages = results.toList().sortedBy { it.url },
                failedPages = ledger.failedPages(),
                pagesExpected = ledger.pagesExpected
            )
        } catch (e: TimeoutCancellationException) {
            // The round ran out of time.  Report it as a timed-out round with its
            // outstanding URLs instead of re-throwing: the throw used to be
            // swallowed by the per-seed catch, which then let the terminal write
            // replace the TIMEOUT record with OK — a truncated crawl that looked
            // successful.
            logger.warn("Crawl {}: depth=1 timed out after collecting {} pages; saving partial results", taskId, results.size)
            return CrawlRound(
                pages = results.toList().sortedBy { it.url },
                failedPages = ledger.failedPages() + ledger.outstanding(),
                pagesExpected = ledger.pagesExpected,
                timedOut = true,
                timeoutError = "Crawl timed out after collecting ${results.size} pages (partial results saved)"
            )
        } finally {
            ledger.close()
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------
    // Depth>1: BFS continuous crawl using ParsableHyperlink parse handlers
    // ------------------------------------------------------------------

    private suspend fun crawlDepthN(taskId: String, request: CrawlRequest, linksDiscovered: AtomicInteger): CrawlRound {
        // Use sequential browsers for continuous crawling (same as _5_ContinuousCrawler.kt)
        try { PulsarSettings.withSequentialBrowsers().maxOpenTabs(8) } catch (e: Exception) { /* optional config */ }

        val session = sessionManager.agenticContext.createSession()
        val results = Collections.synchronizedList(mutableListOf<CrawlPageResult>())
        // Per-round settle bookkeeping.  The round can only complete when every
        // URL it submitted produced a page or a reported failure *and* no parse
        // handler is still running: the old `completed == submitted` counter let
        // handler A's children be submitted after the counters of the other
        // handlers had already caught up, and could not see a fetch that failed
        // at all (issue #592: "expected 10 pages, got 8", status=OK).
        val ledger = CrawlLedger(taskId)
        try {
            val effectiveArgs = buildEffectiveArgs(request.args)
            val options = parseOptions(session, effectiveArgs)
            val maxDepth = request.depth
            val visited = ConcurrentHashMap.newKeySet<String>()

            // Per-URL crawl bookkeeping:
            //  - recorded: URLs whose parse event already produced a result entry.
            //    onHTMLDocumentParsed can fire more than once per page (re-parse
            //    after refresh, shared-session reloads); only the first event may
            //    add a result or tick the completion counter — otherwise a single
            //    page appears twice in the listing and the crawl can complete
            //    early (nondeterministic totals across runs).
            //  - depths: discovery depth captured at submission time.  Re-deriving
            //    it from page.configuredUrl is unreliable (the -depth args are not
            //    always preserved), which made every page — including the seed —
            //    fall back to depth=1.
            val recorded = ConcurrentHashMap.newKeySet<String>()
            val depths = ConcurrentHashMap<String, Int>()
            // Serializes the visited-check + visited-mark + submit sequence so two
            // pages that discover the same child cannot both pass the check and
            // submit it twice.
            val discoveryLock = Any()

            // Use lateinit to allow recursive reference within the parse handler
            lateinit var parseHandler: (WebPage, FeaturedDocument) -> Any?

            parseHandler = crawlParse@{ page: WebPage, document: FeaturedDocument ->
                val pageUrl = document.baseURI ?: page.url
                val key = normalizeForVisit(pageUrl)
                // Refuse everything once the round is terminal: a duplicate parse
                // event arriving minutes later used to keep submitting links for a
                // task the caller had already been told was finished (issue #592).
                if (!ledger.enter()) {
                    logger.debug(
                        "Crawl {}: ignoring a late parse event for '{}' — the round is already complete",
                        taskId, pageUrl
                    )
                    return@crawlParse null
                }
                try {
                // Depth resolution is queue-time bookkeeping, never a guess:
                // every URL this crawl submits is registered in `depths` before
                // submission (seed = 0, each link = discovering page's depth + 1),
                // so the map is the source of truth.  extractDepth() re-derives
                // the same queue-time value from the '-depth N' marker embedded in
                // page.configuredUrl and covers parse events whose final URL
                // differs from the submitted URL (redirects).  A page with neither
                // record was not queued by this crawl — say so instead of silently
                // labeling it depth=1 (the old `?: 1` fallback), and settle it so
                // the round cannot wait for a page this crawl never submitted.
                val currentDepth = depths[key] ?: extractDepth(page)
                if (currentDepth == null) {
                    logger.error(
                        "Crawl {}: parsed page '{}' has no queue-time depth record " +
                        "(configuredUrl='{}'); it was not submitted by this crawl — " +
                        "dropping it from the result listing instead of silently reporting depth=1",
                        taskId, pageUrl, page.configuredUrl
                    )
                    ledger.recordFailure(key, -1, page.protocolStatus.minorCode, CrawlLedger.REASON_NOT_QUEUED)
                    return@crawlParse null
                }

                // First parse event for this URL owns the result entry and the
                // completion tick.  Later events (re-parses of the same page) only
                // re-run discovery, which finds every link already visited and
                // therefore does nothing.
                val firstEvent = recorded.add(key)

                if (firstEvent) {
                    // Record this page
                    val extractionResult = if (request.sql != null) {
                        executeSqlQuery(session, pageUrl, request.sql)
                    } else Pair(null, null)
                    val (servedFromStore, storeAgeSeconds) = storeServeMarkers(page)
                    synchronized(results) {
                        results.add(
                            CrawlPageResult(
                                url = pageUrl,
                                title = document.title.takeIf { !it.isNullOrBlank() }
                                    ?: extractTitleFromHtml(document.html),
                                contentLength = page.contentLength,
                                depth = currentDepth,
                                extracted = extractionResult.first,
                                extractionError = extractionResult.second,
                                servedFromStore = servedFromStore,
                                storeAgeSeconds = storeAgeSeconds
                            )
                        )
                        // Publish in-memory progress so the CLI poll loop sees real
                        // page counts while the crawl is still running, instead of
                        // repeating 'waiting for first page' until the whole crawl
                        // finishes.
                        publishIncremental(taskId, results.toList(), linksDiscovered.get())
                    }
                    ledger.recordSuccess(key)
                    logger.debug("Crawl {}: depth={} page={}", taskId, currentDepth, pageUrl)
                } else {
                    logger.debug(
                        "Crawl {}: duplicate parse event for {} (depth={}); already recorded",
                        taskId, pageUrl, currentDepth
                    )
                }

                // If we haven't reached max depth, extract and submit more links
                if (currentDepth < maxDepth) {
                    val selector = options.outLinkSelector
                    if (!selector.isNullOrBlank()) {
                        val allLinks = document.selectHyperlinks(selector)
                            .map { it.url }
                            .toList()
                        // Check-and-mark must be atomic with submission, so two
                        // pages discovering the same link cannot both submit it.
                        val (newLinks, dupes) = synchronized(discoveryLock) {
                            val fresh = allLinks.filter { link ->
                                normalizeForVisit(link) !in visited
                            }
                            val chosen = fresh
                                .filter { link -> matchesPattern(link, options.outLinkPattern) }
                                .take(options.topLinks)
                            chosen.forEach { link -> visited.add(normalizeForVisit(link)) }
                            chosen to (allLinks.size - fresh.size)
                        }
                        if (dupes > 0) {
                            logger.debug(
                                "Crawl {}: {} link(s) skipped — already visited (depth={})",
                                taskId, dupes, currentDepth
                            )
                        }

                        if (newLinks.isNotEmpty()) {
                            linksDiscovered.addAndGet(newLinks.size)
                            val childDepth = currentDepth + 1
                            val args = buildArgsForDepth(options, childDepth)
                            newLinks.forEach { link ->
                                depths[normalizeForVisit(link)] = childDepth
                                val hyperlink = ParsableHyperlink("$link $args", parseHandler)
                                // A fetch that never fires a parse event (retry
                                // budget exhausted, dropped task, terminal
                                // 4xx/5xx) is settled here instead of vanishing.
                                hyperlink.eventHandlers.crawlEventHandlers.onLoaded.addLast { _, loaded ->
                                    settleFromLoaded(ledger, link, childDepth, loaded)
                                    null
                                }
                                // Register before submitting: a page that settles
                                // faster than it is counted would end the round.
                                ledger.submit(link, childDepth)
                                session.submit(hyperlink)
                            }
                            logger.debug(
                                "Crawl {}: submitted {} links at depth {}",
                                taskId,
                                newLinks.size,
                                childDepth
                            )
                        } else if (currentDepth == 0 && ledger.pagesExpected == 1) {
                            // The seed page's round produced no followable
                            // out-links (nothing but the seed has been submitted),
                            // so this crawl will only report the seed page.
                            // Explain why instead of letting it pass as a silent
                            // '1 pages found' success (mirrors crawlDepth1).
                            val diagnostic = runCatching {
                                emptyOutLinksDiagnostic(
                                    document, options.outLinkSelector, options.outLinkPattern
                                )
                            }.getOrNull()
                            if (diagnostic != null) {
                                logger.info("Crawl {}: {}", taskId, diagnostic)
                                publishIncremental(
                                    taskId,
                                    synchronized(results) { results.toList() },
                                    linksDiscovered.get(),
                                    diagnostic
                                )
                            }
                        }
                    }
                }

                } catch (t: Throwable) {
                    // A page whose parse handler died is a lost page, not a page
                    // that is still coming: report it, then keep the original
                    // propagation semantics.
                    ledger.recordFailure(
                        key, depths[key] ?: -1, page.protocolStatus.minorCode,
                        "the parse handler failed for this page: ${t.message}"
                    )
                    throw t
                } finally {
                    // The round may only complete once no handler is in flight:
                    // this is what stops handler A's children from being lost
                    // after the other handlers have already settled everything.
                    ledger.leave()
                }
            } // parseHandler defined

            // Submit the seed URL (depth 0 — it is the starting page).
            val seedKey = normalizeForVisit(request.url)
            visited.add(seedKey)
            depths[seedKey] = 0
            val seedArgs = buildArgsForDepth(options, 0)
            val seedHyperlink = ParsableHyperlink("${request.url} $seedArgs", parseHandler)
            seedHyperlink.eventHandlers.crawlEventHandlers.onLoaded.addLast { _, loaded ->
                settleFromLoaded(ledger, request.url, 0, loaded)
                null
            }
            ledger.submit(request.url, 0)
            session.submit(seedHyperlink)

            // Wait until every submitted URL settled (per-crawl completion, not global)
            val timeoutMs = (maxDepth * 300_000L).coerceAtMost(1_800_000L) // max 30 min
            withTimeout(timeoutMs) {
                ledger.awaitAllSettled()
            }

            // Deterministic ordering: identical runs over an unchanged site
            // produce identical listings.
            return CrawlRound(
                pages = results.toList().sortedWith(compareBy({ it.depth }, { it.url })),
                failedPages = ledger.failedPages(),
                pagesExpected = ledger.pagesExpected
            )
        } catch (e: TimeoutCancellationException) {
            // The round ran out of time.  Report it as a timed-out round with its
            // outstanding URLs instead of re-throwing: the throw used to be
            // swallowed by the per-seed catch, which then let the terminal write
            // replace the TIMEOUT record with OK — a truncated crawl that looked
            // successful.
            logger.warn("Crawl {}: depth>1 timed out after collecting {} pages; saving partial results", taskId, results.size)
            return CrawlRound(
                pages = results.toList().sortedWith(compareBy({ it.depth }, { it.url })),
                failedPages = ledger.failedPages() + ledger.outstanding(),
                pagesExpected = ledger.pagesExpected,
                timedOut = true,
                timeoutError = "Crawl timed out after collecting ${results.size} pages (partial results saved)"
            )
        } finally {
            ledger.close()
            runCatching { session.close() }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Ensure -refresh is present in the args string so portal/link pages are
     * always loaded with fresh content.  Stale internal HTTP caches are the
     * root cause of both "0 elements for any CSS selector" (Issue 1) and
     * "0 byte fetch" (Issue 2).
     */
    private fun buildEffectiveArgs(rawArgs: String): String {
        return when {
            rawArgs.isBlank() -> "-refresh"
            rawArgs.contains("-refresh") -> rawArgs
            else -> "$rawArgs -refresh"
        }
    }

    /**
     * Publish an in-memory progress snapshot to the task store so the CLI poll
     * loop sees real page counts while the crawl is still running.  In-memory
     * only — persistence is deferred until the crawl reaches a terminal state.
     */
    private fun publishIncremental(
        taskId: String,
        pages: List<CrawlPageResult>,
        linksDiscovered: Int,
        diagnostic: String? = null
    ) {
        val previous = taskStore.getIfPresent(taskId)
        taskStore.put(taskId, CrawlResponse(
            taskId = taskId,
            status = "PROCESSING",
            pagesFound = pages.size,
            linksDiscovered = linksDiscovered,
            pages = pages,
            diagnostic = diagnostic ?: previous?.diagnostic,
            startedTime = previous?.startedTime ?: java.time.Instant.now(),
            seedStatuses = previous?.seedStatuses
        ))
    }

    /**
     * Build a user-facing diagnostic explaining why link discovery found no
     * followable out-links on an already-parsed document.  Distinguishes
     * "page was empty" from "page had anchors but the selector / pattern
     * matched nothing".
     */
    private fun emptyOutLinksDiagnostic(
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

    private fun parseOptions(session: PulsarSession, args: String): LoadOptions {
        return if (args.isBlank()) {
            session.options()
        } else {
            session.options(args)
        }
    }

    /**
     * Execute an X-SQL query against the page at [pageUrl].
     * Uses [SQLTemplate] to substitute @url placeholder, then runs the query
     * via the session's SQL context.  Returns a pair of (extracted rows, error message).
     * On success the error is null; on failure the rows are null and the error
     * describes what went wrong.
     */
    private fun executeSqlQuery(
        session: PulsarSession,
        pageUrl: String,
        sql: String
    ): Pair<List<Map<String, Any?>>?, String?> {
        return try {
            // Pre-load the page into the session's WebDB so load_and_select
            // UDFs find the page in the local cache.  Without this the X-SQL
            // engine may silently return 0 rows even when the page was loaded
            // earlier in a different session lifecycle stage.
            val preloadSucceeded = try {
                runBlocking {
                    withTimeout(10_000L) {
                        session.load(pageUrl, "-refresh")
                    }
                }
                logger.debug("Crawl X-SQL: pre-loaded '{}' into session cache", pageUrl)
                true
            } catch (preloadError: Exception) {
                // Pre-load failure means the WebDB cache may be empty for this
                // page, and DOM_LOAD_AND_SELECT / DOM UDFs will not find the
                // page content.  This is a warning (not debug) because it
                // directly causes empty extraction results for the user.
                logger.warn(
                    "Crawl X-SQL: pre-load of '{}' failed ({}). " +
                    "X-SQL extraction may return 0 rows or empty fields. " +
                    "This can happen when the session's WebDB cache was cleared " +
                    "between page load and query execution.",
                    pageUrl, preloadError.message
                )
                false
            }

            val processedSql = SQLTemplate(sql).createSQL(pageUrl)
            logger.info("Crawl X-SQL: executing query on '{}': {}", pageUrl, processedSql.take(300))

            val sqlContext = session.context as? AbstractBrowser4SQLContext
                ?: run {
                    val msg = "Session context is not an SQL context; cannot execute X-SQL"
                    logger.warn(msg)
                    return Pair(null, msg)
                }
            val rs: ResultSet = sqlContext.executeQuery(processedSql)
            val copied = ResultSetUtils.copyResultSet(rs)

            // Read column names from ResultSetMetaData BEFORE converting to
            // text entities.  JDBC metadata preserves the SQL SELECT column
            // order, which is the deterministic source of truth for column
            // ordering in CSV/JSON/table output.  Without this, column order
            // depends on Map iteration order which varies across implementations.
            val metaData = copied.metaData
            // Use lowercase column labels so they match the keys produced by
            // ResultSetUtils.getTextEntitiesFromResultSet (which lowercases
            // via ResultSetMetaData.getColumnName + toLowerCase).  Without
            // this the reorder pass produces duplicate columns: the original-
            // case lookup returns null, and the "append extras" loop re-adds
            // the lowercased key — giving each column twice.
            val sqlColumnOrder = (1..metaData.columnCount).map { metaData.getColumnLabel(it).lowercase() }

            val rawRows = ResultSetUtils.getTextEntitiesFromResultSet(copied)
            // Reorder each row to match the SQL SELECT column order so that
            // CSV headers, JSON keys, and table columns are deterministic.
            val rows = if (sqlColumnOrder.isNotEmpty()) {
                rawRows.map { row ->
                    val ordered = linkedMapOf<String, Any?>()
                    for (col in sqlColumnOrder) {
                        ordered[col] = row[col]
                    }
                    // Append any columns not in metadata (computed/dynamic names)
                    for ((key, value) in row) {
                        if (key !in sqlColumnOrder) {
                            ordered[key] = value
                        }
                    }
                    ordered
                }
            } else {
                rawRows
            }
            if (rows.isEmpty()) {
                logger.info("Crawl X-SQL: query returned 0 rows for '{}'", pageUrl)
            } else {
                logger.info("Crawl X-SQL: extracted {} row(s) from '{}'", rows.size, pageUrl)

                // Detect silent failures: rows exist but ALL extracted data
                // fields (excluding URL/URI columns) are empty.  This almost
                // always means the WebDB cache was empty when the UDFs ran,
                // which happens when the pre-load above fails or the cache
                // layer used by session.load() differs from the one the UDFs
                // read.  Surface this as a warning so the user knows something
                // is wrong.
                val nonUrlColumnsHaveContent = rows.any { row ->
                    row.any { (key, value) ->
                        !key.contains("url", ignoreCase = true)
                            && !key.contains("uri", ignoreCase = true)
                            && !key.contains("base", ignoreCase = true)
                            && value != null && value.toString().isNotBlank()
                    }
                }
                if (!nonUrlColumnsHaveContent && !preloadSucceeded) {
                    val msg = "All extracted fields are empty — the WebDB cache may have been " +
                        "empty when the X-SQL UDFs ran. This is likely a cache-coherence issue " +
                        "between session.load() and the DOM UDF layer."
                    logger.warn("Crawl X-SQL: {} for '{}'", msg, pageUrl)
                    return Pair(rows, msg)
                }
            }
            Pair(rows, null)
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            logger.error("Failed to execute X-SQL on '{}': {} (SQL: {})", pageUrl, msg, sql.take(300))
            Pair(null, msg)
        }
    }

    /**
     * Load the portal page and extract out-links using the same logic as
     * [ai.platon.pulsar.skeleton.session.AbstractPulsarSession.submitForOutPages0].
     */
    private suspend fun extractOutLinks(
        session: PulsarSession,
        portalUrl: String,
        options: LoadOptions
    ): List<String> {
        val normOptions = session.normalize(options)
        val rawSelector = normOptions.outLinkSelector.orEmpty()
        if (rawSelector.isBlank()) return emptyList()

        // Diagnostic: log the selector as-provided; normalize() already
        // calls correctOutLinkSelector() internally, so outLinkSelector is corrected.
        val correctedSelector = normOptions.outLinkSelector
        logger.debug(
            "extractOutLinks: rawSelector='{}' correctedSelector='{}' ignoreUrlQuery={}",
            rawSelector, correctedSelector, normOptions.ignoreUrlQuery
        )

        val document = session.loadDocument(portalUrl, normOptions)

        // Diagnostic: verify the document has meaningful content
        val docHtmlLength = document.html.length
        val allAnchors = document.select("a").size
        logger.debug(
            "extractOutLinks: document.html.length={} document.select('a').size={}",
            docHtmlLength, allAnchors
        )

        val selector = correctedSelector ?: rawSelector
        val matchedElements = document.select(selector)
        if (matchedElements.isEmpty() && allAnchors > 0) {
            logger.warn(
                "extractOutLinks: document has {} anchors but selector '{}' matched 0 elements. " +
                "Verify the CSS selector targets the correct elements.",
                allAnchors, selector
            )
        }
        logger.debug(
            "extractOutLinks: selector='{}' matched {} element(s)",
            selector, matchedElements.size
        )

        return matchedElements.mapNotNull { element ->
            val href = element.attr("href").takeIf { it.isNotBlank() }
                ?: element.attr("src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // Fragment-only anchors (href='#', '#section') can never navigate to
            // a new document — resolving them yields the portal page itself with
            // a fragment, which then masquerades as a discovered out-link (it can
            // even match --out-link-pattern after resolution and produce a hollow
            // 'discovered but nothing new' crawl).  Skip them before resolution.
            if (href.trimStart().startsWith("#")) {
                return@mapNotNull null
            }
            // Normalize: resolve relative URLs, optional query stripping
            val resolved = runCatching {
                java.net.URI(portalUrl).resolve(href).toString()
            }.getOrElse { href }

            if (normOptions.ignoreUrlQuery) {
                resolved.substringBefore('?')
            } else {
                resolved
            }
        }
            .filter { link -> matchesPattern(link, normOptions.outLinkPattern) }
            .distinct()
            .take(normOptions.topLinks)
            .toList()
    }

    companion object {
        /** Earliest plausible fetch time; earlier values are unset sentinels. */
        private val MIN_FETCH_TIME: Instant = Instant.parse("2000-01-01T00:00:00Z")

        /** Maximum fetch retries when content is 0 bytes or protocol error occurs. */
        private const val MAX_FETCH_RETRIES = 3

        /** Base delay in ms between fetch retries; doubles each attempt (exponential backoff). */
        private const val FETCH_RETRY_DELAY_MS = 1000L

        /** Delay in ms between seed URL processing to allow session cleanup. */
        private const val SEED_INTERVAL_MS = 500L

        /** Maximum time (ms) a crawl task may run before being cancelled. */
        private const val CRAWL_TASK_TIMEOUT_MS = 600_000L // 10 minutes

        /**
         * How many lost URLs the diagnostic spells out.  The full list is in
         * [CrawlResponse.failedPages]; the note only has to prove the loss is
         * real and name the first few.
         */
        private const val MAX_REPORTED_FAILED_PAGES = 5

        fun crawlPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "crawl", "crawl-tasks.jsonl"
        )
    }

    /**
     * Extract the <title> text from raw HTML when [FeaturedDocument.title]
     * returns blank.  Handles the case where the parse pipeline skips title
     * extraction on cached content.  Returns null when no <title> tag is found.
     */
    private fun extractTitleFromHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val match = Regex("""<title[^>]*>\s*(.*?)\s*</title>""", RegexOption.IGNORE_CASE)
            .find(html)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun matchesPattern(url: String, pattern: String?): Boolean {
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
    private fun storeServeMarkers(page: WebPage): Pair<Boolean, Long?> {
        if (!page.isCached) return false to null
        val fetchTime = page.fetchTime
        val now = java.time.Instant.now()
        // Guard against sentinel/unset fetch times (e.g. epoch) that would
        // produce absurd ages for stored content.
        val ageSeconds = if (fetchTime.isAfter(MIN_FETCH_TIME) && fetchTime.isBefore(now)) {
            java.time.Duration.between(fetchTime, now).seconds.coerceAtLeast(0)
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
    private fun buildReadonlyNote(pages: List<CrawlPageResult>): String {
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
     * Settle a submitted URL from the crawl `onLoaded` event, which fires for
     * every load attempt — including the ones that no parse event ever follows.
     *
     * Without this, a fetch that fails is invisible to the crawl: the parse
     * event never fires, so the completion wait can never learn that the page
     * is not coming, and the URL simply disappears from the result (issue
     * #592).  Classification mirrors `XSQLHyperlink.CrawlEventHandlers`, which
     * is the established reading of these states in this codebase:
     *
     *  * a retry/canceled status means the page is still in flight — settle
     *    nothing, so the round keeps waiting for the attempt that finally lands;
     *  * `!isFetched` alone is NOT a failure: a page served from the page store
     *    (`-readonly` without `-refresh`) legitimately completes with content
     *    while `isFetched` stays false.
     */
    private fun settleFromLoaded(ledger: CrawlLedger, submittedUrl: String, depth: Int, page: WebPage?) {
        if (ledger.isTerminal) return
        if (page == null) {
            ledger.recordFailure(submittedUrl, depth, 0, CrawlLedger.REASON_NEVER_FETCHED)
            return
        }
        val status = page.protocolStatus
        when {
            page.isCanceled || status.isRetry -> Unit

            page.isNil -> ledger.recordFailure(
                submittedUrl, depth, status.minorCode, CrawlLedger.REASON_NEVER_FETCHED
            )

            status.isFailed -> ledger.recordFailure(
                submittedUrl, depth, status.minorCode,
                status.reason?.toString() ?: CrawlLedger.REASON_FETCH_FAILED
            )

            !page.isFetched && !status.isSuccess -> ledger.recordFailure(
                submittedUrl, depth, status.minorCode, CrawlLedger.REASON_NEVER_FETCHED
            )

            // A successful load is settled by the parse event that records its
            // row, and `onLoaded` fires *after* that event.  A success no row was
            // recorded for will never produce one, so report it instead of
            // letting the round wait out its whole timeout.
            !ledger.isRecordedSuccess(submittedUrl) && !ledger.isRecordedSuccess(page.url) ->
                ledger.recordFailure(submittedUrl, depth, status.minorCode, CrawlLedger.REASON_NOT_PARSED)

            else -> Unit
        }
    }

    /**
     * The terminal note that makes a loss visible.  A crawl that dropped pages
     * must say so — with the URLs and the reason — instead of reporting a page
     * count smaller than the number of pages it set out to fetch.
     */
    private fun buildLossNote(
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

    private fun extractDepth(page: WebPage): Int? {
        // Depth is embedded as a synthetic option in the URL's args string.
        // `configuredUrl` carries the args (e.g. "https://... -depth 2 -parse"),
        // while `page.url` is the resolved URL without args.
        val url = page.configuredUrl
        val match = Regex("""-depth\s+(\d+)""").find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun buildArgsForDepth(options: LoadOptions, depth: Int): String {
        val parts = mutableListOf("-depth $depth -parse")
        if (options.outLinkSelector.isNotBlank()) {
            parts.add("-outLink \"${options.outLinkSelector}\"")
        }
        if (options.outLinkPattern.isNotBlank() && options.outLinkPattern != ".+") {
            parts.add("-outLinkPattern \"${options.outLinkPattern}\"")
        }
        // -readonly must reach every page load, not just the seed: without this
        // the flag silently stops applying at depth>=2 and the crawl writes
        // pages to the store while claiming nothing was written.
        if (options.refresh) parts.add("-refresh")
        if (options.readonly) parts.add("-readonly")
        return parts.joinToString(" ")
    }
}

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
