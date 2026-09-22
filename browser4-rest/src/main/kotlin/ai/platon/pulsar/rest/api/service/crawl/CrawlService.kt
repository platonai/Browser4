package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.session.PulsarSession
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Crawl task lifecycle: admission, seed scheduling, progress reporting and the
 * terminal record of a crawl.
 *
 * The service is the only owner of crawl *state* — the task store, the running
 * jobs and the JSONL persistence — while the actual fetching of a round lives in
 * [CrawlRoundRunner] and the report shaping in [CrawlSupport].  A crawl runs
 * asynchronously: [submit] returns a task id immediately and [getResult] is
 * polled for the outcome.
 */
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
    private val terminalStatuses = CrawlStatus.TERMINAL

    internal val persistence = JsonlPersistence(
        file = crawlPersistencePath(),
        clazz = CrawlResponse::class,
        objectMapper = pulsarObjectMapper()
    )

    /** Executes one round of a crawl; stateless, so it is shared by all tasks. */
    private val roundRunner = CrawlRoundRunner(sessionManager)

    /**
     * Where one seed round publishes what it collected.
     *
     * Bound to its task and its seed index, because a publish is a
     * read-modify-write of the task record *and* of that round's slice of the
     * in-flight page view — both under the task's [CrawlTaskContext.publishLock],
     * which a shared, id-keyed sink could not take.
     */
    private inner class SeedProgressSink(
        private val task: CrawlTaskContext,
        private val seedIndex: Int,
    ) : CrawlProgressSink {

        override fun publishPages(pages: List<CrawlPageResult>, linksDiscovered: Int, diagnostic: String?) =
            publishInFlight(task, seedIndex, pages, linksDiscovered, diagnostic)

        override fun publishDiagnostic(diagnostic: String) =
            publishInFlight(task, seedIndex, emptyList(), task.linksDiscovered.get(), diagnostic)
    }

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

    /**
     * How long one crawl task may run before the server-side limit cancels it
     * (ms), see [DEFAULT_TASK_TIMEOUT_MS].
     *
     * A round derives its own timeout from what is left of this budget
     * ([resolveRoundTimeoutMs]), so the two are one mechanism, not two
     * independent timers.  Overridable so the budget paths can be driven without
     * waiting minutes; the CLI-facing error text always reports this value.
     */
    @Volatile
    var taskTimeoutMillis: Long = DEFAULT_TASK_TIMEOUT_MS

    /**
     * Parallelism budget for a crawl that does not ask for one: how many
     * independent fetch units (one browser tab each) it may drive at once.
     *
     * A crawl is a set of independent seeds — and at depth>=1, each seed's round
     * is independent too — so the units are parallelizable in principle.  They
     * only *are* parallel when each unit owns its own browser tab: a session
     * bound to a driver pins every fetch of that session onto one tab, which is
     * the serialization this budget exists to remove (see
     * [ai.platon.pulsar.protocol.browser.emulator.impl.PrivacyManagedBrowserFetcher]).
     *
     * The hard ceiling is the browser driver pool
     * (`browser.context.number` x `browser.max.active.tabs`, 2 x 8 by default);
     * the value is kept small so a bulk download stays polite to the target site
     * and to the rest of the server.  Set it (or `--parallel 1`) for the
     * historical one-unit-at-a-time behavior.
     */
    @Volatile
    var defaultParallelTabs: Int = DEFAULT_PARALLEL_TABS

    /**
     * Resolve the parallelism budget for one crawl from its request.
     *
     * The value is clamped rather than rejected: a caller asking for more tabs
     * than the server is willing to hand out still gets a working crawl, and
     * [CrawlResponse.parallelTabs] always reports the budget that was actually
     * used, so the caller can see the clamp instead of guessing.  A non-positive
     * request falls back to the server default — `parallelTabs` is a budget, not
     * a switch, and treating `0` as "no parallelism at all" would silently turn
     * every page into a serial fetch.
     */
    fun resolveParallelTabs(request: CrawlRequest): Int {
        val requested = request.parallelTabs ?: defaultParallelTabs
        val budget = if (requested > 0) requested else defaultParallelTabs
        return budget.coerceIn(1, MAX_PARALLEL_TABS)
    }

    /**
     * Resolve the task budget for one crawl from its request.
     *
     * [taskTimeoutMillis] is the server's default — what a request that asks for nothing runs
     * under.  A request may ask for its own, clamped to the per-request range
     * ([resolveRequestTaskTimeout]); the effective value is what the task arms its clock with,
     * what every round derives its own timeout from, and what
     * [CrawlResponse.taskTimeoutMillis] reports, so a clamp is visible to the caller instead of
     * being a silent difference between what it asked for and what it got.
     */
    fun resolveTaskTimeoutMillis(request: CrawlRequest): Long =
        resolveRequestTaskTimeout(request.taskTimeoutMillis, taskTimeoutMillis)

    init {
        // Periodically purge expired tasks so stale entries don't accumulate
        crawlScope.launch {
            while (isActive) {
                delay((5 * 60 * 1000L).milliseconds) // every 5 minutes
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
            status = CrawlStatus.CREATED
        )
        taskStore.put(taskId, response)
        onStatusChanged(response)

        val seedUrls = resolveSeedUrls(request)
        if (seedUrls.isEmpty()) {
            rejectWithoutSeeds(taskId)
            return taskId
        }

        // The parallelism budget and the task budget are resolved once, before the worker
        // starts, so every branch of the worker — including its terminal timeout/error
        // records — reports the same values the crawl actually ran under.
        val task = CrawlTaskContext(
            taskId = taskId,
            request = request,
            seedUrls = seedUrls,
            parallelTabs = resolveParallelTabs(request),
            taskTimeoutMillis = resolveTaskTimeoutMillis(request)
        )

        val job = crawlScope.launch { runCrawlTask(task) }

        jobStore[taskId] = job

        logger.info(
            "Crawl task submitted: {} seeds={} depth={} parallelTabs={} budget={}ms",
            taskId, seedUrls.size, request.depth, task.parallelTabs, task.taskTimeoutMillis
        )
        return taskId
    }

    /**
     * The seed URLs this crawl will process: an explicit list wins over the
     * single-URL form, and a request with neither is rejected by the caller.
     */
    private fun resolveSeedUrls(request: CrawlRequest): List<String> = when {
        !request.urls.isNullOrEmpty() -> request.urls
        request.url.isNotBlank() -> listOf(request.url)
        else -> emptyList()
    }

    /**
     * Record the terminal failure of a request that has no seed URL at all.
     *
     * Terminal records always carry started/finish timestamps so consumers can
     * distinguish an instantly-failed task from one that never ran (and the CLI
     * summary can show elapsed time).
     */
    private fun rejectWithoutSeeds(taskId: String) {
        val now = Instant.now()
        val errorResponse = CrawlResponse(
            taskId = taskId,
            status = CrawlStatus.INTERNAL_SERVER_ERROR,
            error = "No URLs provided",
            startedTime = now,
            finishTime = now
        )
        taskStore.put(taskId, errorResponse)
        onStatusChanged(errorResponse)
    }

    // ------------------------------------------------------------------
    // One task's lifetime
    // ------------------------------------------------------------------

    /**
     * Run one submitted crawl from PROCESSING to a terminal record.
     *
     * The terminal write is what a poller waits for, so every exit path has to
     * reach one: success, timeout/cancellation, or an unexpected failure.
     */
    private suspend fun runCrawlTask(task: CrawlTaskContext) {
        try {
            markProcessing(task)
            // Arm the task clock together with the task-level limit, so a round's
            // derived budget and the limit itself measure exactly the same span.
            val taskLimitMs = task.taskTimeoutMillis
            task.armBudget(taskLimitMs)
            val collected = withTimeout(taskLimitMs.milliseconds) { collectSeeds(task) }
            writeCompleted(task, collected)
        } catch (e: CancellationException) {
            writeCancelled(task, e)
        } catch (e: Exception) {
            writeFailed(task, e)
        } finally {
            jobStore.remove(task.taskId)
        }
    }

    /**
     * Mark the task as [CrawlStatus.PROCESSING] as soon as the worker picks it up.
     * Without this, the CLI sees [CrawlStatus.CREATED] for the entire duration of
     * the crawl (which can be 80-100s for many URLs), making it appear as if
     * nothing is happening.
     */
    private fun markProcessing(task: CrawlTaskContext) {
        val processing = CrawlResponse(
            taskId = task.taskId,
            status = CrawlStatus.PROCESSING,
            pagesFound = 0,
            startedTime = Instant.now()
        )
        taskStore.put(task.taskId, processing)
        onStatusChanged(processing)
    }

    /**
     * Fetch every seed URL of the task, at most [CrawlTaskContext.parallelTabs]
     * of them at a time, and return everything they collected.
     *
     * Each seed is an independent fetch unit: at depth=0 the unit is one page, at
     * depth>=1 it is one link-discovery round (whose own out-pages are then
     * fetched from the shared driver pool).  Either way the units have no
     * ordering dependency on each other, so they are driven concurrently up to
     * the budget instead of one after another.
     */
    private suspend fun collectSeeds(task: CrawlTaskContext): SeedCollection = withContext(Dispatchers.IO) {
        val totalSeeds = task.seedUrls.size
        // For depth=0 (bulk fetch mode), reuse a single session across
        // all seed URLs.  Creating a new session per seed causes the HTTP
        // protocol handler to be deregistered when the previous session is
        // closed, and the next session's handler may not re-register in time
        // for the page load — producing "Protocol not found" (status 1600)
        // for every seed after the first.  A single session avoids the
        // deregistration/re-registration cycle entirely.
        //
        // Sharing one *session* does not share one *tab*: this session
        // binds no driver, so each concurrent load leases its own tab
        // from the browser driver pool and the seeds really do run in
        // parallel.  Binding a driver here is what would serialize them.
        val sharedDepth0Session = if (task.request.depth == 0) {
            // Labelled so a running crawl's session is identifiable in logs and
            // context dumps; this task is its only owner (see the finally block).
            sessionManager.agenticContext.createSession(PulsarSettings(label = crawlSessionLabel(task.taskId)))
        } else {
            null
        }

        try {
            val budget = task.parallelTabs.coerceAtMost(totalSeeds)
            if (budget > 1) {
                mapCrawlSeedsConcurrently(task.seedUrls, budget) { index, seedUrl ->
                    val (round, status) = fetchSeedUnit(task, sharedDepth0Session, index, seedUrl)
                    recordSeedProgress(task, index, round, status)
                }
            } else {
                for ((index, seedUrl) in task.seedUrls.withIndex()) {
                    val (round, status) = fetchSeedUnit(task, sharedDepth0Session, index, seedUrl)
                    recordSeedProgress(task, index, round, status)
                    if (index < totalSeeds - 1) {
                        // The delay lets the browser settle between
                        // seeds.  It only exists on the sequential
                        // path: when the seeds run in parallel, the
                        // next seed is already in flight and a delay
                        // would just serialize them again.
                        delay(SEED_INTERVAL_MS.milliseconds)
                    }
                }
            }
        } finally {
            // Close the session *and* deregister it: nothing else tracks it (it was
            // not created through PulsarSessionManager), so a failure to close here
            // leaks browser resources with no one left to reconcile them.
            sharedDepth0Session?.let { session ->
                releaseCrawlSession(session, sessionManager.agenticContext)?.let { failure ->
                    logger.warn(
                        "Crawl {}: the shared depth-0 session {} could not be closed and deregistered; " +
                        "the browser resources it holds are no longer tracked by anything",
                        task.taskId, session.id, failure
                    )
                }
            }
        }

        val settledRounds = task.seedRounds.filterNotNull()
        SeedCollection(
            pages = settledRounds.flatMap { it.pages },
            seedStatuses = task.seedStatuses.filterNotNull(),
            rounds = settledRounds
        )
    }

    /**
     * Fetch one seed URL and classify its outcome.
     *
     * A failed seed never throws (except cancellation): it is reported
     * as an "error" seed status plus a synthetic row, so one bad seed
     * cannot take the other seeds down with it — which matters when the
     * seeds of a crawl are fetched concurrently.
     *
     * The in-flight counters are maintained here, around the whole
     * fetch, so the reported peak measures how much collection
     * actually overlapped.
     *
     * A seed whose remaining budget cannot carry a round is not started at all
     * ([hasBudgetForRound]): it comes back as a "skipped" seed status plus one
     * lost page, because a round killed by the task limit would report neither.
     */
    private suspend fun fetchSeedUnit(
        task: CrawlTaskContext,
        sharedDepth0Session: PulsarSession?,
        index: Int,
        seedUrl: String
    ): Pair<CrawlRound, CrawlSeedStatus> {
        logger.info(
            "Crawl {}: processing seed URL {}/{}: {}",
            task.taskId, index + 1, task.seedUrls.size, seedUrl
        )
        val seedRequest = task.request.copy(url = seedUrl, urls = null)
        val concurrent = task.inFlight.incrementAndGet()
        task.peakInFlight.accumulateAndGet(concurrent) { a, b -> maxOf(a, b) }
        return try {
            // Budget gate: a round that is killed by the task limit reports
            // nothing at all, so a URL is not submitted once the remaining budget
            // can no longer carry a round plus its report.  The URL is reported as
            // a lost page instead — visibly, and with the accounting intact.
            val remainingBudgetMs = task.remainingBudgetMs()
            if (!hasBudgetForRound(remainingBudgetMs)) {
                val reason = "$REASON_BUDGET_EXHAUSTED " +
                    "(${remainingBudgetMs}ms of the ${task.taskTimeoutMillis}ms task budget left)"
                logger.warn(
                    "Crawl {}: seed URL {}/{} '{}' was not started — {}",
                    task.taskId, index + 1, task.seedUrls.size, seedUrl, reason
                )
                return unstartedSeedRound(seedUrl) to CrawlSeedStatus(
                    url = seedUrl,
                    status = "skipped",
                    pagesReturned = 0,
                    error = reason
                )
            }
            // Depth>=1 rounds wait for their URLs to settle, so they get a deadline
            // they can actually meet.  Depth=0 is a single blocking load, which no
            // suspend timeout can interrupt — the task limit is its bound.
            val roundTimeoutMs = resolveRoundTimeoutMs(seedRequest.depth, remainingBudgetMs)
            // One sink per seed round: it carries the task (and its publish lock),
            // so this round's progress is merged into the crawl's record under
            // that lock instead of racing the seed bookkeeping.
            val seedProgress = SeedProgressSink(task, index)
            val fetched = when {
                // Depth=0 is bulk fetch: one URL, no link
                // discovery, so its pages are its whole round.
                seedRequest.depth == 0 -> CrawlRound(
                    pages = roundRunner.crawlDepth0(task.taskId, seedRequest, sharedDepth0Session)
                )
                seedRequest.depth <= 1 -> roundRunner.crawlDepth1(
                    task.taskId, seedRequest, task.linksDiscovered, roundTimeoutMs, seedProgress
                )
                else -> roundRunner.crawlDepthN(
                    task.taskId, seedRequest, task.linksDiscovered, roundTimeoutMs, seedProgress
                )
            }
            logger.info(
                "Crawl {}: seed URL {}/{} completed: {} → {} page(s), {} lost",
                task.taskId, index + 1, task.seedUrls.size, seedUrl,
                fetched.pages.size, fetched.failedPages.size
            )
            fetched to CrawlSeedStatus(
                url = seedUrl,
                status = "fetched",
                pagesReturned = fetched.pages.size
            )
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
                task.taskId, index + 1, task.seedUrls.size, seedUrl, e.message, e
            )
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
            ) to CrawlSeedStatus(
                url = seedUrl,
                status = "error",
                pagesReturned = 0,
                error = e.message
            )
        } finally {
            task.inFlight.decrementAndGet()
        }
    }

    /**
     * Record a settled seed and publish incremental progress to the
     * in-memory task store so the CLI polling loop can show per-seed
     * extraction progress (e.g. "2/5 seeds done, 4 rows extracted so
     * far").  Only the in-memory store is updated — persistence.append
     * is deferred until the crawl completes to avoid writing
     * intermediate states.  Concurrent seeds publish under a lock so
     * a reader never sees a half-written record.
     *
     * The pages it reports are the crawl's aggregate view, not only this seed's:
     * the rounds that are still running have published their own pages, and the
     * count a poller sees must never fall back when the next seed starts.
     */
    private fun recordSeedProgress(task: CrawlTaskContext, index: Int, round: CrawlRound, status: CrawlSeedStatus) {
        synchronized(task.publishLock) {
            task.seedRounds[index] = round
            task.seedStatuses[index] = status
            // The settled round confirms what its publishes reported; recording it
            // here keeps the aggregate complete even for a round that never
            // published (a depth-0 seed collects without reporting page by page).
            task.publishedPages[index] = round.pages
            val settled = task.seedRounds.filterNotNull()
            val pages = aggregateInFlightPages(task.publishedPages)
            val currentResult = taskStore.getIfPresent(task.taskId)
            val incrementalResponse = CrawlResponse(
                taskId = task.taskId,
                status = CrawlStatus.PROCESSING,
                pagesFound = pages.size,
                linksDiscovered = task.linksDiscovered.get(),
                pages = pages,
                diagnostic = currentResult?.diagnostic,
                startedTime = currentResult?.startedTime ?: Instant.now(),
                seedStatuses = task.seedStatuses.filterNotNull(),
                // Losses are visible while the crawl still runs,
                // not only in the terminal response.
                failedPages = settled.flatMap { it.failedPages },
                pagesExpected = settled.sumOf { it.pagesExpected },
                // Parallelism is visible while the crawl runs too,
                // so a poller can tell a slow serial crawl from a
                // fast parallel one.
                parallelTabs = task.parallelTabs,
                taskTimeoutMillis = task.taskTimeoutMillis,
                maxConcurrentFetches = task.peakInFlight.get()
            )
            taskStore.put(task.taskId, incrementalResponse)
        }
    }

    /**
     * Write the terminal OK/TIMEOUT record for a crawl whose seeds all settled.
     *
     * A round that hit its own timeout already wrote a TIMEOUT record; this
     * terminal write used to overwrite it with OK, so a truncated crawl reported
     * success.  Keep the timeout status and say how much of the work never
     * completed.
     */
    private fun writeCompleted(task: CrawlTaskContext, collected: SeedCollection) {
        val allPages = collected.pages
        val existingDiagnostic = taskStore.getIfPresent(task.taskId)?.diagnostic
        val now = Instant.now()
        val previous = taskStore.getIfPresent(task.taskId)
        // --readonly must not be silent: either pages came from the page
        // store (say so, with the age of the stored content) or every
        // page was verified fetched fresh from the live site.
        val readonlyRequested = task.request.args.split(Regex("\\s+")).any {
            it == "-readonly" || it.startsWith("-readonly=")
        }
        val readonlyNote = if (readonlyRequested) buildReadonlyNote(allPages) else null
        val failedPages = collected.rounds.flatMap { it.failedPages }
        val pagesExpected = collected.rounds.sumOf { it.pagesExpected }
        val timedOut = collected.rounds.firstOrNull { it.timedOut }
        val lossNote = buildLossNote(allPages.size, pagesExpected, failedPages)
        val completed = CrawlResponse(
            taskId = task.taskId,
            status = if (timedOut != null) {
                CrawlStatus.REQUEST_TIMEOUT
            } else {
                CrawlStatus.OK
            },
            pagesFound = allPages.size,
            linksDiscovered = task.linksDiscovered.get(),
            pages = allPages,
            // The loss note is appended, never substituted: a diagnostic
            // that explained "no out-links" must not hide dropped pages.
            diagnostic = listOfNotNull(existingDiagnostic, lossNote).joinToString(" | ")
                .takeIf { it.isNotBlank() },
            error = timedOut?.timeoutError,
            startedTime = previous?.startedTime ?: now,
            finishTime = now,
            seedStatuses = collected.seedStatuses,
            readonlyNote = readonlyNote,
            failedPages = failedPages.takeIf { it.isNotEmpty() },
            pagesExpected = pagesExpected,
            parallelTabs = task.parallelTabs,
            taskTimeoutMillis = task.taskTimeoutMillis,
            maxConcurrentFetches = task.peakInFlight.get()
        )
        taskStore.put(task.taskId, completed)
        onStatusChanged(completed)
        logger.info(
            "Crawl task {} completed: {} pages, {} lost, status {}, parallel budget {} (peak {} in flight)",
            task.taskId, allPages.size, failedPages.size, completed.status,
            task.parallelTabs, task.peakInFlight.get()
        )
    }

    /**
     * Finalize a task whose worker was cancelled or hit the task-wide timeout.
     *
     * A cancellation always transitions the task to a terminal state.
     * Historically this branch only wrote TIMEOUT when the task was still
     * CREATED, so a task whose worker died inside withTimeout(taskTimeoutMillis)
     * stayed PROCESSING forever (uncancellable, invisible to 'crawl clear',
     * purged only by TTL).  Distinguish the two sources: [cancel] removes the
     * job from [jobStore] and already wrote a terminal record, so a record in a
     * terminal state is left alone; anything still PROCESSING/CREATED is a
     * seed-processing timeout and must be finalized with whatever partial
     * progress exists.
     *
     * The partial record reports the *accounting*, not just the pages: the losses
     * of the seeds that did settle are carried over, and every seed whose round
     * never returned is named as a lost URL.  A round killed by the task limit
     * returns nothing (it never reaches its own timeout branch), so before this
     * the terminal record of a killed deep crawl was a smaller page count with no
     * losses at all — the exact "fewer pages, no complaint" shape the loss
     * accounting exists to prevent.
     *
     * Only a seed whose round *returned* can be accounted exactly.  A seed whose
     * round was killed mid-flight has an unknown expected count, so it is
     * reported as exactly one lost URL and the pages its round may already have
     * published are deliberately not claimed: claiming them without their
     * submitted count would break `pagesFound + failedPages.size == pagesExpected`,
     * which is the only thing that lets a caller tell "the site does not have it"
     * from "the crawl lost it".  Re-run such a seed.
     */
    private fun writeCancelled(task: CrawlTaskContext, e: CancellationException) {
        val existing = taskStore.getIfPresent(task.taskId)
        val now = Instant.now()
        val alreadyTerminal = existing != null && existing.status in terminalStatuses
        if (!alreadyTerminal) {
            val timedOutByTaskLimit = e is TimeoutCancellationException
            val unfinishedReason = if (timedOutByTaskLimit) REASON_TASK_LIMIT else REASON_TASK_CANCELLED
            // Take one consistent snapshot: `recordSeedProgress` publishes under
            // this lock and assigns the round before its status, so reading
            // without it could see a seed as both settled and unfinished.
            val snapshot = synchronized(task.publishLock) {
                val settled = task.seedRounds.filterNotNull()
                val unfinished = unfinishedSeedLosses(task.seedUrls, task.seedStatuses, unfinishedReason)
                CancelledSnapshot(
                    pages = settled.flatMap { it.pages },
                    failures = settled.flatMap { it.failedPages },
                    unfinished = unfinished,
                    pagesExpected = settled.sumOf { it.pagesExpected } + unfinished.size,
                    seedStatuses = task.seedUrls.indices.map { index ->
                        task.seedStatuses[index] ?: CrawlSeedStatus(
                            url = task.seedUrls[index], status = "timeout", pagesReturned = 0, error = unfinishedReason
                        )
                    }
                )
            }
            val failedPages = snapshot.failures + snapshot.unfinished
            val lossNote = buildLossNote(snapshot.pages.size, snapshot.pagesExpected, failedPages)
            val timedOut = CrawlResponse(
                taskId = task.taskId,
                status = CrawlStatus.REQUEST_TIMEOUT,
                error = if (timedOutByTaskLimit) {
                    "Crawl timed out while processing seeds (server-side limit of " +
                        "${task.taskTimeoutMillis / 1000}s exceeded). Partial results below."
                } else {
                    "Crawl cancelled or timed out"
                },
                pagesFound = snapshot.pages.size,
                linksDiscovered = existing?.linksDiscovered ?: task.linksDiscovered.get(),
                pages = snapshot.pages.takeIf { it.isNotEmpty() },
                seedStatuses = snapshot.seedStatuses,
                // The loss note is appended, never substituted: a diagnostic that
                // explained "no out-links" must not hide the seeds that never ran.
                diagnostic = listOfNotNull(existing?.diagnostic?.takeIf { it.isNotBlank() }, lossNote)
                    .joinToString(" | ")
                    .takeIf { it.isNotBlank() },
                failedPages = failedPages.takeIf { it.isNotEmpty() },
                pagesExpected = snapshot.pagesExpected,
                // A timed-out crawl still reports the parallelism it was
                // running under and the overlap it achieved, so the
                // partial result says "how" as well as "how much".
                parallelTabs = existing?.parallelTabs ?: task.parallelTabs,
                taskTimeoutMillis = existing?.taskTimeoutMillis ?: task.taskTimeoutMillis,
                maxConcurrentFetches = maxOf(existing?.maxConcurrentFetches ?: 0, task.peakInFlight.get()),
                startedTime = existing?.startedTime ?: now,
                finishTime = now
            )
            taskStore.put(task.taskId, timedOut)
            onStatusChanged(timedOut)
            logger.warn(
                "Crawl task {} cancelled or timed out: {} — {} page(s) recorded, {} lost, {} seed(s) never settled",
                task.taskId, e.message, snapshot.pages.size, failedPages.size, snapshot.unfinished.size
            )
        } else {
            logger.warn("Crawl task {} cancelled or timed out: {}", task.taskId, e.message)
        }
    }

    /** Write the terminal record of a crawl that died on an unexpected error. */
    private fun writeFailed(task: CrawlTaskContext, e: Exception) {
        val existing = taskStore.getIfPresent(task.taskId)
        val now = Instant.now()
        val failed = CrawlResponse(
            taskId = task.taskId,
            status = CrawlStatus.INTERNAL_SERVER_ERROR,
            error = e.message ?: "Unknown error",
            parallelTabs = task.parallelTabs,
            taskTimeoutMillis = task.taskTimeoutMillis,
            maxConcurrentFetches = task.peakInFlight.get(),
            startedTime = existing?.startedTime ?: now,
            finishTime = now
        )
        taskStore.put(task.taskId, failed)
        onStatusChanged(failed)
        logger.error("Crawl task {} failed: {}", task.taskId, e.message, e)
    }

    /**
     * Add one round's publish to the in-flight record of a running crawl.
     *
     * The record is the crawl's *aggregate* view: the pages of every round that
     * has published so far ([aggregateInFlightPages]), merged into what the
     * settled seeds have already reported.  Publishing only the round's own pages
     * made the count fall back — a poller watched 3 pages become 1 when the next
     * seed's round published its first page.
     *
     * Both publishers ([SeedProgressSink] and [recordSeedProgress]) take
     * [CrawlTaskContext.publishLock], so the read-modify-write cannot lose a
     * contribution from the other one.  In-memory only: persistence is deferred
     * until the crawl reaches a terminal state.
     */
    private fun publishInFlight(
        task: CrawlTaskContext,
        seedIndex: Int,
        pages: List<CrawlPageResult>,
        linksDiscovered: Int,
        diagnostic: String? = null
    ) {
        synchronized(task.publishLock) {
            task.publishedPages[seedIndex] = pages
            val aggregated = aggregateInFlightPages(task.publishedPages)
            val previous = taskStore.getIfPresent(task.taskId)
            val merged = mergeIncrementalProgress(
                task.taskId, previous, aggregated, linksDiscovered, diagnostic, terminalStatuses
            )
            if (merged == null) {
                // The task is finished (round timeout, cancellation, completion)
                // while a parse handler is still running.  Reviving it would leave
                // the poller waiting for a record nobody will ever finalize again.
                logger.debug(
                    "Crawl {}: dropping an incremental publish — the task is already terminal ({})",
                    task.taskId, previous?.status
                )
                return
            }
            taskStore.put(task.taskId, merged)
        }
    }

    /**
     * Cancel a running crawl task by its ID.
     * @return true if the task was found and cancelled, false otherwise.
     */
    fun cancel(taskId: String): Boolean {
        val job = jobStore.remove(taskId) ?: return false
        job.cancel()
        val now = Instant.now()
        val previous = taskStore.getIfPresent(taskId)
        val cancelled = CrawlResponse(
            taskId = taskId,
            status = CrawlStatus.REQUEST_TIMEOUT,
            error = "Cancelled by user",
            parallelTabs = previous?.parallelTabs ?: 0,
            taskTimeoutMillis = previous?.taskTimeoutMillis ?: 0,
            maxConcurrentFetches = previous?.maxConcurrentFetches ?: 0,
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
            rewritePersistence()
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
        rewritePersistence()
    }

    /**
     * Rewrite the append-only JSONL file from the tasks that are still in
     * memory.  The file is a log, not a source of truth, so anything removed
     * from the store — cleared or purged — must be dropped from it too, or it
     * would be revived by [restoreFromDisk] on the next start.
     */
    private fun rewritePersistence() {
        persistence.clear()
        taskStore.asMap().values.forEach { persistence.append(it) }
    }

    /**
     * Get the current status/result of a crawl task.
     */
    fun getResult(taskId: String): CrawlResponse {
        return taskStore.getIfPresent(taskId) ?: CrawlResponse(
            taskId = taskId,
            status = CrawlStatus.NOT_FOUND,
            error = "Task not found: $taskId"
        )
    }

    /**
     * The per-task state shared by the worker, its seed units and the progress
     * publishers.  It is created in [submit] so every terminal record — including
     * the ones written by a timeout or a failure — reports the same parallelism
     * budget the crawl ran under.
     */
    private class CrawlTaskContext(
        val taskId: String,
        val request: CrawlRequest,
        val seedUrls: List<String>,
        val parallelTabs: Int,
        /**
         * The task budget this crawl runs under (ms), resolved from its request once — the
         * clock it arms, the limit every round derives its own timeout from, and the value
         * the terminal record reports (see [resolveTaskTimeoutMillis]).
         */
        val taskTimeoutMillis: Long,
    ) {
        // Out-links discovered beyond the seed URLs (depth>=1 crawls),
        // aggregated across seeds.  Kept separate from the result size so a
        // crawl that only records seed page(s) (0 discovered links) is
        // distinguishable from one that followed links.
        val linksDiscovered = AtomicInteger()

        // How many fetch units are in flight right now, and the peak this crawl
        // reached.  The peak is the observed counterpart of the budget:
        // reporting it is what makes "the pages were collected in parallel"
        // inspectable instead of asserted.
        val inFlight = AtomicInteger()
        val peakInFlight = AtomicInteger()

        // Seed rounds and per-seed statuses are stored by seed index, so the
        // final response keeps the seed order even when the seeds are fetched
        // concurrently (see [mapCrawlSeedsConcurrently]).
        val seedRounds: Array<CrawlRound?> = arrayOfNulls(seedUrls.size)
        val seedStatuses: Array<CrawlSeedStatus?> = arrayOfNulls(seedUrls.size)

        /**
         * The pages each seed round has published so far, by seed index.
         *
         * The in-flight record is derived from this map, so it is the crawl's
         * aggregate view of what it has collected: every round's latest publish
         * replaces its own earlier one (a round only ever grows), and the record
         * reports all of them (see [aggregateInFlightPages]).
         *
         * Guarded by [publishLock] — the publishers are the rounds' parse handlers
         * (one per seed) and [CrawlService.recordSeedProgress], and all of them
         * read-modify-write the same task record.
         */
        val publishedPages = mutableMapOf<Int, List<CrawlPageResult>>()

        /** Serializes the progress publishers so a poller never reads a half-written record. */
        val publishLock = Any()

        /**
         * The instant the task budget expires (nanoTime), set by the worker when
         * it arms the clock.  Rounds derive their own timeout from what is left
         * of it, so the round deadline and the task limit are one mechanism.
         */
        private var budgetDeadlineNanos = 0L

        /**
         * Start the task clock.  Called by the worker right before the
         * task-level limit, never at submission: a task that waited in the
         * dispatcher queue must get its full budget, not a shortened one.
         */
        fun armBudget(timeoutMs: Long) {
            budgetDeadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        }

        /**
         * Milliseconds left of the task budget, never negative.
         *
         * A task whose clock was never armed reports [Long.MAX_VALUE], so no
         * round is ever skipped for a budget that has not started running.
         */
        fun remainingBudgetMs(): Long {
            val deadline = budgetDeadlineNanos
            if (deadline == 0L) return Long.MAX_VALUE
            return ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        }
    }

    /**
     * Everything the settled seeds collected, in seed order: the pages, their
     * per-seed statuses, and the rounds they came from (which carry the losses
     * and the expected page count of the terminal record).
     */
    private class SeedCollection(
        val pages: List<CrawlPageResult>,
        val seedStatuses: List<CrawlSeedStatus>,
        val rounds: List<CrawlRound>,
    )

    /**
     * What the task knows at the moment the task-level limit (or a caller's
     * cancellation) finalizes it: the pages of the seeds whose rounds returned,
     * their losses, and one lost URL for every seed whose round never settled.
     *
     * Taken as one snapshot so the record cannot mix a settled round with a
     * still-null status for the same seed (see [writeCancelled]).
     */
    private class CancelledSnapshot(
        val pages: List<CrawlPageResult>,
        val failures: List<CrawlFailedPage>,
        val unfinished: List<CrawlFailedPage>,
        val pagesExpected: Int,
        val seedStatuses: List<CrawlSeedStatus>,
    )

    companion object {
        /** Delay in ms between seed URL processing to allow session cleanup. */
        private const val SEED_INTERVAL_MS = 500L

        /**
         * Default parallelism budget for a crawl that does not ask for one, see
         * [CrawlService.defaultParallelTabs].
         *
         * Kept well below the browser driver pool's ceiling
         * (`browser.context.number` x `browser.max.active.tabs`, 2 x 8 by
         * default) so one bulk crawl cannot monopolize every tab on the server.
         * */
        const val DEFAULT_PARALLEL_TABS = 4

        /**
         * Hard ceiling on a requested parallelism budget.
         *
         * The driver pool refuses to hand out more than
         * `browser.context.number` x `browser.max.active.tabs` tabs (16 by
         * default), and each open tab is a real browser target; the ceiling
         * stops a typo in `--parallel` from asking the server to open hundreds
         * of them.
         * */
        const val MAX_PARALLEL_TABS = 32

        /**
         * Default server-side limit on one crawl task (ms): 10 minutes.
         *
         * The limit a task actually runs under is [CrawlService.taskTimeoutMillis];
         * no round ever gets the whole of it, because a round derives its own
         * budget from what is left minus the margin it needs to report
         * ([resolveRoundTimeoutMs]).
         */
        const val DEFAULT_TASK_TIMEOUT_MS = 600_000L // 10 minutes

        fun crawlPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "crawl", "crawl-tasks.jsonl"
        )
    }
}
