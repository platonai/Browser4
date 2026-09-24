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
import org.springframework.beans.factory.annotation.Value
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
    private val sessionManager: PulsarSessionManager,
    /**
     * Whether an interrupted crawl is resumed automatically when the backend
     * starts (`crawl.autoResume`, **default off**).
     *
     * It is off because auto-resume re-hits third-party sites without anyone
     * asking: a restart is not consent to keep crawling.  With it on, every task
     * that was running when the process died is continued from its checkpoint
     * (see [restoreFromDisk]); with it off such a task is reported as
     * [CrawlStatus.INTERRUPTED] and waits for an explicit `crawl resume`.
     */
    @param:Value("\${crawl.autoResume:false}")
    private val autoResumeOnStart: Boolean = false,
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

    /**
     * Interrupted tasks: the record of a task whose worker died with the process.
     *
     * Deliberately outside [taskStore]: the 100-entry LRU would evict such a task
     * — and with it the only in-memory pointer to a checkpoint that is still
     * resumable — while a resumable task is exactly the one that must survive.
     * Being outside the store also keeps them out of `crawl clear` (which removes
     * *finished* tasks); `crawl clear --all` is the explicit way to discard them.
     */
    private val interruptedStore = ConcurrentHashMap<String, CrawlResponse>()

    /** Terminal task states: OK, TIMEOUT, ERROR.  Tasks in these states are
     *  purgeable, clearable, and never re-finalized by a late cancellation. */
    private val terminalStatuses = CrawlStatus.TERMINAL

    internal val persistence = JsonlPersistence(
        file = crawlPersistencePath(),
        clazz = CrawlResponse::class,
        objectMapper = pulsarObjectMapper()
    )

    /**
     * Where a task's resumable work state lives, outside the bounded task store
     * and outside its TTL (see [CrawlCheckpointStore]).
     *
     * Assignable so a test can point it at a temporary directory, the same way
     * [persistence] is redirected by `CrawlServicePersistenceTest`.
     */
    internal var checkpointStore = CrawlCheckpointStore(crawlCheckpointDir())

    /**
     * The live checkpoint of every task this instance knows about, keyed by task
     * id.  The rounds publish their work state into it; [flushCheckpoint] decides
     * when it reaches the disk.
     */
    private val checkpoints = ConcurrentHashMap<String, CrawlCheckpoint>()

    /** How often each task's checkpoint may be written; one policy per task. */
    private val checkpointPolicies = ConcurrentHashMap<String, CheckpointWritePolicy>()

    /**
     * Checkpoints whose in-memory state changed since their last write.
     *
     * The write policy is evaluated when a round publishes, and a round only
     * publishes when something settles — so a crawl that goes quiet (a page that
     * hangs, the last seed settling before a kill) could leave its state on disk
     * stale for as long as that quiet lasts.  The ticker in [init] drains this set,
     * which is what turns the policy's staleness bound from "the next time something
     * happens" into wall-clock time.
     */
    private val dirtyCheckpoints: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * How many rows of each seed have already been appended to its task's row log,
     * so a publish only appends the new ones.
     */
    private val appendedRows = ConcurrentHashMap<String, MutableMap<Int, Int>>()

    /** Set once [restoreFromDisk] has run, so tests can tell the two states apart. */
    @Volatile
    var autoResume: Boolean = autoResumeOnStart

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

        override fun publishPages(pages: List<CrawlPageResult>, linksDiscovered: Int, diagnostic: String?) {
            publishInFlight(task, seedIndex, pages, linksDiscovered, diagnostic)
            // The rows are the "already fetched" set, so each one is made durable the
            // moment it exists — a periodic state rewrite cannot be, and a resume
            // that lost a row would request that URL a second time (issue #606).
            appendNewRows(task, seedIndex, pages)
        }

        override fun publishDiagnostic(diagnostic: String) =
            publishInFlight(task, seedIndex, emptyList(), task.linksDiscovered.get(), diagnostic)

        override fun publishSubmitted(items: List<CrawlWorkItem>) = recordSubmitted(task, seedIndex, items)

        override fun publishWorkState(settled: Int, build: () -> CrawlWorkSnapshot) =
            recordWorkState(task, seedIndex, settled, build)
    }

    /**
     * Rebuild the task store from the append-only task file at startup.
     *
     * Two things happen here, and the second one is the point of issue #606:
     *
     *  * settled tasks come back as they were (minus the ones already purged);
     *  * a task that was **still running** when the process died comes back as
     *    [CrawlStatus.INTERRUPTED], never as `Processing`.  The worker is gone, so
     *    the old record was a phantom: `crawl status` reported a running task
     *    forever, and nothing could ever finalize it.  The record now says what it
     *    is (stopped), what it left behind (its checkpoint, if any), and what it
     *    would take to finish it ([CrawlResponse.remaining]).
     *
     * With [`crawl.autoResume`][autoResumeOnStart] on, such a task is continued
     * immediately; off (the default), it waits for `crawl resume`.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun restoreFromDisk() {
        val now = System.currentTimeMillis()
        val ttlMillis = taskTtlMinutes * 60_000L
        val interrupted = mutableListOf<CrawlResponse>()
        // The task file is an append-only log: the *last* line for a task is its
        // current state (a resumed crawl appends its new records after the old ones).
        // Collecting first and processing after is what makes "this task was
        // interrupted" and "this task finished" mutually exclusive — processing line
        // by line would leave a stale interrupted record behind a newer terminal one.
        val latest = LinkedHashMap<String, CrawlResponse>()
        persistence.restore { entry ->
            if (entry.taskId.isBlank()) return@restore
            latest[entry.taskId] = entry
        }
        latest.values.forEach { entry ->
            // Skip terminal entries that have already expired — they were
            // purged from memory before shutdown and should not be revived.
            val expired = (now - entry.createdAt) > ttlMillis
            if (!CrawlStatus.isRunning(entry.status)) {
                if (expired) {
                    logger.debug("Skipping expired crawl task {} during restore", entry.taskId)
                    return@forEach
                }
                // A resumed-and-finished task is in the store; nothing may keep a
                // stale interrupted copy of it around.
                interruptedStore.remove(entry.taskId)
                taskStore.put(entry.taskId, entry)
                return@forEach
            }
            if (expired) {
                // A task that never finished and is older than the TTL is not worth
                // resuming; its checkpoint is kept (it is durable state), but the
                // record is not revived.
                logger.debug("Skipping expired interrupted crawl task {} during restore", entry.taskId)
                return@forEach
            }
            val record = buildInterruptedRecord(entry, Instant.ofEpochMilli(now))
            interruptedStore[record.taskId] = record
            interrupted += record
        }

        if (interrupted.isNotEmpty()) {
            logger.warn(
                "Restored {} crawl task(s) that were interrupted by a restart; they are not running. " +
                    "Resume one with 'crawl resume <taskId>'{}",
                interrupted.size,
                if (autoResume) " (crawl.autoResume is on: resuming now)" else ""
            )
            interrupted.forEach { record ->
                if (record.resumable) {
                    logger.info(
                        "Crawl {}: interrupted with {} URL(s) already fetched and {} left to fetch",
                        record.taskId, record.skippedAlreadyFetched, record.remaining
                    )
                } else {
                    logger.warn(
                        "Crawl {}: interrupted with no checkpoint on disk — it cannot be resumed " +
                            "and has to be submitted again",
                        record.taskId
                    )
                }
            }
            if (autoResume) {
                interrupted.forEach { record ->
                    if (!record.resumable) return@forEach
                    runCatching { resume(record.taskId, force = false, retryFailed = false) }
                        .onFailure { logger.warn("Crawl {}: automatic resume failed: {}", record.taskId, it.message) }
                }
            }
        }
    }

    /**
     * Turn a record whose worker died into an honest [CrawlStatus.INTERRUPTED] one.
     *
     * The checkpoint is the source of truth for what the task left behind: the
     * append-only task file may be older than the last checkpoint write (progress
     * publishes are in-memory only), so a record that says "0 pages" can still
     * have 40 rows in its checkpoint.  The report is rebuilt from the checkpoint
     * through the same [planResume] the resume itself uses, which is what keeps
     * `pagesFound + failedPages.size == pagesExpected` true for an interrupted
     * task as well.
     */
    private fun buildInterruptedRecord(entry: CrawlResponse, interruptedAt: Instant): CrawlResponse {
        val checkpoint = checkpoints[entry.taskId] ?: checkpointStore.load(entry.taskId)
        if (checkpoint == null || !checkpoint.resumable) {
            return entry.copy(
                status = CrawlStatus.INTERRUPTED,
                finishTime = interruptedAt,
                resumable = false,
                remaining = 0,
                skippedAlreadyFetched = 0,
                error = "Crawl was interrupted by a server restart and has no checkpoint on disk; " +
                    "it cannot be resumed — submit the crawl again",
                diagnostic = listOfNotNull(
                    entry.diagnostic?.takeIf { it.isNotBlank() },
                    REASON_NO_CHECKPOINT
                ).joinToString(" | ")
            )
        }

        checkpoints[entry.taskId] = checkpoint
        val plan = planResume(entry.taskId, checkpoint.seedUrls, checkpoint)
        val rows = plan.seeds.flatMap { it.restoredPages }
        val keptFailures = plan.seeds.flatMap { it.restoredFailures }
        val pending = plan.seeds.flatMap { seed ->
            seed.work.map { CrawlFailedPage(it.url, it.depth, 0, REASON_INTERRUPTED) }
        }
        val unstarted = plan.seeds.filter { !it.started }
            .map { CrawlFailedPage(it.seedUrl, 0, 0, REASON_INTERRUPTED) }
        val failedPages = keptFailures + pending + unstarted
        val pagesExpected = plan.seeds.sumOf { it.restoredExpected + it.work.size } + unstarted.size
        val lossNote = buildLossNote(rows.size, pagesExpected, failedPages)
        return entry.copy(
            status = CrawlStatus.INTERRUPTED,
            pagesFound = rows.size,
            pages = rows.takeIf { it.isNotEmpty() } ?: entry.pages,
            linksDiscovered = checkpoints[entry.taskId]?.seeds?.sumOf { it.linksDiscovered }
                ?: entry.linksDiscovered,
            failedPages = failedPages.takeIf { it.isNotEmpty() },
            pagesExpected = pagesExpected,
            seedStatuses = restoredSeedStatuses(plan).takeIf { it.isNotEmpty() } ?: entry.seedStatuses,
            finishTime = interruptedAt,
            resumable = true,
            remaining = plan.remaining,
            skippedAlreadyFetched = plan.skippedAlreadyFetched,
            resumeCount = entry.resumeCount,
            resumedFrom = entry.resumedFrom,
            error = "Crawl was interrupted by a server restart; resume it with 'crawl resume ${entry.taskId}' " +
                "to continue from the ${plan.skippedAlreadyFetched} URL(s) already fetched " +
                "(${plan.remaining} left to fetch)",
            diagnostic = listOfNotNull(
                entry.diagnostic?.takeIf { it.isNotBlank() },
                lossNote,
                REASON_INTERRUPTED
            ).joinToString(" | ").takeIf { it.isNotBlank() }
        )
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
        // Drain the checkpoints that changed since their last write, on a wall
        // clock rather than "the next time a page settles": a crawl that is quiet
        // (a hanging fetch, the last seed finishing right before a kill) is exactly
        // when its state on disk matters most.  The write policy still owns the
        // byte rate, so a large checkpoint is not rewritten every tick.
        crawlScope.launch {
            while (isActive) {
                delay(CHECKPOINT_FLUSH_TICK_MS.milliseconds)
                runCatching { flushDirtyCheckpoints() }
                    .onFailure { logger.warn("Checkpoint flush tick failed: {}", it.message) }
            }
        }
    }

    /** Force a write for every checkpoint whose state changed since the last one. */
    private fun flushDirtyCheckpoints() {
        if (dirtyCheckpoints.isEmpty()) return
        dirtyCheckpoints.toList().forEach { taskId ->
            val written = flushCheckpoint(taskId, settled = checkpointSettled(taskId))
            if (written) dirtyCheckpoints.remove(taskId)
        }
    }

    /**
     * Stop the workers and give every task's checkpoint its last, forced write.
     *
     * The forced flush is what makes an orderly shutdown lossless: the progress
     * publishes are throttled, so without it the URLs settled since the last write
     * would be re-fetched by whoever resumes the task.  `SIGKILL` cannot be hooked —
     * that is what the write cadence bounds — but every shutdown that *does* run
     * this method leaves a complete checkpoint behind.
     */
    @PreDestroy
    fun shutdown() {
        crawlScope.cancel()
        checkpoints.keys.forEach { taskId ->
            runCatching { flushCheckpoint(taskId, settled = checkpointSettled(taskId), force = true) }
        }
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

        startTask(task)

        logger.info(
            "Crawl task submitted: {} seeds={} depth={} parallelTabs={} budget={}ms",
            taskId, seedUrls.size, request.depth, task.parallelTabs, task.taskTimeoutMillis
        )
        return taskId
    }

    /**
     * Persist the input contract of a task the moment it is admitted, and start its
     * worker.
     *
     * The checkpoint is written *before* the worker runs: a task killed in its first
     * second still knows its seeds and its `depth`, so "resume" is never a question
     * of "what did it set out to fetch".
     */
    private fun startTask(task: CrawlTaskContext) {
        val checkpoint = task.resume?.let { plan ->
            // A resumed run continues the checkpoint it was planned from, keeping the
            // rows and the work state of every seed that has not been touched yet.
            (checkpoints[task.taskId] ?: checkpointStore.load(task.taskId))?.copy(
                updatedAt = System.currentTimeMillis(),
                resumeCount = task.resumeCount,
                resumedFrom = task.resumedFrom,
                run = task.run
            )
        } ?: CrawlCheckpoint.ofRequest(
            taskId = task.taskId,
            request = task.request,
            seedUrls = task.seedUrls,
            parallelTabs = task.parallelTabs,
            taskTimeoutMillis = task.taskTimeoutMillis,
            run = task.run
        )
        checkpoints[task.taskId] = checkpoint
        checkpointPolicies.computeIfAbsent(task.taskId) { CheckpointWritePolicy() }
        // This run logs its own rows: the previous runs' rows are already in the row
        // log, and each round's publish starts from an empty list again.
        appendedRows[task.taskId] = ConcurrentHashMap()
        dirtyCheckpoints.add(task.taskId)
        // Forced: the input contract reaches the disk before a single page is
        // fetched, so a task killed in its first second is still resumable.
        flushCheckpoint(task.taskId, settled = checkpointSettled(task.taskId), force = true)

        val job = crawlScope.launch { runCrawlTask(task) }

        jobStore[task.taskId] = job
    }

    /**
     * Continue an interrupted (or timed-out) crawl from its checkpoint.
     *
     * The rules, and why each of them exists:
     *
     *  * **A task with a live worker is refused** (`IllegalStateException`, which the
     *    controller maps to `409 Conflict`): two workers on one checkpoint would
     *    fetch everything twice and race each other's work state.
     *  * **A completed task is a no-op** unless [force] is given: there is nothing
     *    to continue, and silently re-crawling a site the caller believes is done
     *    is worse than saying so.
     *  * **A task whose checkpoint is gone is refused** with an explanation rather
     *    than restarted: without the input contract a "resume" would have to
     *    re-fetch from the seeds while claiming to continue.
     *  * **Already-fetched URLs are not requested again** ([CrawlResponse.skippedAlreadyFetched]);
     *    terminally failed ones stay failed unless [retryFailed]; the URLs that were
     *    in flight, plus the discovered frontier, are re-submitted; the task keeps
     *    its id and gains a resume counter.
     *
     * @param taskId the task to continue — the same id the original submission
     *   returned, because it is the same task.
     * @param force continue a task that is already terminal in a *successful* state.
     * @param retryFailed re-submit the URLs that failed terminally before, instead
     *   of keeping them failed.
     */
    fun resume(taskId: String, force: Boolean = false, retryFailed: Boolean = false): CrawlResumeResult {
        val running = jobStore[taskId]
        if (running != null && running.isActive) {
            throw IllegalStateException(
                "Crawl $taskId is still running; a task cannot be resumed while its worker is alive"
            )
        }
        val record = taskStore.getIfPresent(taskId) ?: interruptedStore[taskId]
        val checkpoint = checkpoints[taskId] ?: checkpointStore.load(taskId)
        if (record != null && record.status == CrawlStatus.OK && !force) {
            val hint = if ((checkpoint?.seeds?.sumOf { it.failed.size } ?: 0) > 0) {
                " pass --force --retry-failed to fetch the URLs it lost"
            } else {
                " pass --force to run it again"
            }
            return CrawlResumeResult(
                taskId = taskId,
                resumed = false,
                status = record.status,
                message = "Crawl $taskId already completed successfully;$hint",
                remaining = 0,
                skippedAlreadyFetched = record.skippedAlreadyFetched,
                resumeCount = record.resumeCount
            )
        }
        if (checkpoint == null || !checkpoint.resumable) {
            return CrawlResumeResult(
                taskId = taskId,
                resumed = false,
                status = record?.status ?: CrawlStatus.NOT_FOUND,
                message = "Crawl $taskId has no checkpoint on disk, so there is nothing to continue " +
                    "from; submit the crawl again",
                remaining = 0,
                skippedAlreadyFetched = 0,
                resumeCount = record?.resumeCount ?: 0
            )
        }

        val request = checkpoint.request
        val seedUrls = checkpoint.seedUrls
        val plan = planResume(taskId, seedUrls, checkpoint, retryFailed)
        if (plan.nothingToDo) {
            val keptFailures = plan.seeds.sumOf { it.restoredFailures.size }
            val hint = if (keptFailures > 0) {
                " ($keptFailures URL(s) failed terminally; pass --retry-failed to fetch them again)"
            } else {
                ""
            }
            return CrawlResumeResult(
                taskId = taskId,
                resumed = false,
                status = record?.status ?: CrawlStatus.INTERRUPTED,
                message = "Crawl $taskId has nothing left to fetch: every URL it submitted is settled$hint",
                remaining = 0,
                skippedAlreadyFetched = plan.skippedAlreadyFetched,
                resumeCount = record?.resumeCount ?: 0
            )
        }

        // The interruption the resumed run continues from: the finish time the
        // interrupted record was given (see buildInterruptedRecord), so the merged
        // result can say "these rows are from before that moment".
        val interruptedAt = record?.finishTime ?: record?.startedTime ?: Instant.now()
        val resumeCount = (record?.resumeCount ?: 0) + 1
        val previous = buildInterruptedRecordForResume(record, checkpoint, plan, interruptedAt)

        val task = CrawlTaskContext(
            taskId = taskId,
            request = request,
            seedUrls = seedUrls,
            parallelTabs = if (checkpoint.parallelTabs > 0) checkpoint.parallelTabs else resolveParallelTabs(request),
            // A resumed run gets its own full budget: the URLs it re-submits are
            // fresh work, and measuring them against a clock that already ran out
            // would refuse every one of them.
            taskTimeoutMillis = if (checkpoint.taskTimeoutMillis > 0) {
                checkpoint.taskTimeoutMillis
            } else {
                resolveTaskTimeoutMillis(request)
            },
            resume = plan,
            run = checkpoint.run + 1,
            resumeCount = resumeCount,
            resumedFrom = interruptedAt
        )

        // The interrupted record is replaced by the resumed run's own record, so a
        // poller never sees two states for one task at the same time.
        interruptedStore.remove(taskId)
        val created = previous.copy(
            status = CrawlStatus.CREATED,
            startedTime = null,
            finishTime = null,
            resumedFrom = interruptedAt,
            resumeCount = resumeCount,
            skippedAlreadyFetched = plan.skippedAlreadyFetched,
            remaining = plan.remaining,
            resumable = true,
            error = null,
            diagnostic = listOfNotNull(
                previous.diagnostic?.takeIf { it.isNotBlank() },
                buildResumeNote(plan, task.run, interruptedAt, previous.remaining)
            ).joinToString(" | ").takeIf { it.isNotBlank() }
        )
        taskStore.put(taskId, created)
        onStatusChanged(created)

        startTask(task)

        logger.info(
            "Crawl task {} resumed (run {}, {} already-fetched URL(s) restored, {} left to fetch, retryFailed={})",
            taskId, task.run, plan.skippedAlreadyFetched, plan.remaining, retryFailed
        )
        return CrawlResumeResult(
            taskId = taskId,
            resumed = true,
            status = created.status,
            message = buildResumeNote(plan, task.run, interruptedAt, previous.remaining),
            remaining = plan.remaining,
            skippedAlreadyFetched = plan.skippedAlreadyFetched,
            resumeCount = resumeCount
        )
    }

    /**
     * The record a resumed run starts from: whatever the task already knows about
     * itself, rebuilt from its checkpoint when the old record is gone.
     *
     * A resume must work after the *record* was cleared while the checkpoint
     * survived (`crawl clear` keeps resumable checkpoints on purpose), so this
     * never depends on [record] being present.
     */
    private fun buildInterruptedRecordForResume(
        record: CrawlResponse?,
        checkpoint: CrawlCheckpoint,
        plan: CrawlResumePlan,
        interruptedAt: Instant,
    ): CrawlResponse = record?.takeIf { it.taskId == checkpoint.taskId }
        ?: CrawlResponse(
            taskId = checkpoint.taskId,
            status = CrawlStatus.INTERRUPTED,
            createdAt = checkpoint.createdAt,
            finishTime = interruptedAt,
            resumable = true,
            remaining = plan.remaining,
            skippedAlreadyFetched = plan.skippedAlreadyFetched
        )

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
                    runSeedUnit(task, sharedDepth0Session, index, seedUrl)
                }
            } else {
                for ((index, seedUrl) in task.seedUrls.withIndex()) {
                    runSeedUnit(task, sharedDepth0Session, index, seedUrl)
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
     * Run one seed unit — fetch it, or restore what a previous run already fetched
     * — and record its outcome.
     *
     * A seed the resume plan has nothing left for is **not touched at all**: its
     * rows come out of the checkpoint, and the fact that the target site is never
     * asked for it again is the observable form of the resume promise (see
     * [CrawlResponse.skippedAlreadyFetched]).
     */
    private suspend fun runSeedUnit(
        task: CrawlTaskContext,
        sharedDepth0Session: PulsarSession?,
        index: Int,
        seedUrl: String
    ) {
        val restored = task.resumeFor(index)
        if (restored != null && !restored.needsRun) {
            logger.info(
                "Crawl {}: seed URL {}/{} '{}' is already settled in the checkpoint " +
                    "({} row(s) restored, nothing to fetch)",
                task.taskId, index + 1, task.seedUrls.size, seedUrl, restored.restoredPages.size
            )
            recordSeedProgress(
                task, index, restoredRound(restored),
                CrawlSeedStatus(
                    url = seedUrl,
                    status = if (restored.completed) "fetched" else restored.status,
                    pagesReturned = restored.restoredPages.size,
                    error = restored.error
                )
            )
            return
        }
        val (round, status) = fetchSeedUnit(task, sharedDepth0Session, index, seedUrl, restored)
        recordSeedProgress(task, index, round, status)
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
     *
     * @param restored what a previous run left for this seed, when this is a
     *   resumed task: the URLs to re-submit, the rows to restore, and the identity
     *   sets a continued round has to remember.
     */
    private suspend fun fetchSeedUnit(
        task: CrawlTaskContext,
        sharedDepth0Session: PulsarSession?,
        index: Int,
        seedUrl: String,
        restored: CrawlSeedResume? = null
    ): Pair<CrawlRound, CrawlSeedStatus> {
        // A depth-0 seed is one URL, so resuming it means re-fetching *that* URL —
        // and the URL it was submitted under is the checkpoint's, not necessarily
        // the spelling the seed list carries.
        val targetUrl = if (task.request.depth == 0) {
            restored?.work?.firstOrNull()?.url ?: seedUrl
        } else {
            seedUrl
        }
        logger.info(
            "Crawl {}: processing seed URL {}/{}: {}{}",
            task.taskId, index + 1, task.seedUrls.size, targetUrl,
            if (restored?.isContinuation == true) " (continuing from the checkpoint)" else ""
        )
        val seedRequest = task.request.copy(url = targetUrl, urls = null)
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
                    task.taskId, index + 1, task.seedUrls.size, targetUrl, reason
                )
                // A seed that was already partly crawled keeps everything the
                // checkpoint holds and leaves its remaining work for the next
                // resume — reporting it as one lost page would throw that work (and
                // the rows) away.
                return if (restored != null && restored.started) {
                    val lost = restored.work.map { CrawlFailedPage(it.url, it.depth, 0, reason) }
                    CrawlRound(
                        pages = restored.restoredPages,
                        failedPages = restored.restoredFailures + lost,
                        pagesExpected = restored.restoredExpected + lost.size,
                        timedOut = true,
                        timeoutError = reason,
                        outstanding = lost
                    ) to CrawlSeedStatus(
                        url = seedUrl,
                        status = "skipped",
                        pagesReturned = restored.restoredPages.size,
                        error = reason
                    )
                } else {
                    unstartedSeedRound(seedUrl) to CrawlSeedStatus(
                        url = seedUrl,
                        status = "skipped",
                        pagesReturned = 0,
                        error = reason
                    )
                }
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
                    pages = roundRunner.crawlDepth0(task.taskId, seedRequest, sharedDepth0Session, task.run)
                )
                seedRequest.depth <= 1 -> roundRunner.crawlDepth1(
                    task.taskId, seedRequest, task.linksDiscovered, roundTimeoutMs, seedProgress,
                    resume = restored, run = task.run
                )
                else -> roundRunner.crawlDepthN(
                    task.taskId, seedRequest, task.linksDiscovered, roundTimeoutMs, seedProgress,
                    resume = restored, run = task.run
                )
            }
            // One merge, in one place: a continued round reports only what *this*
            // run fetched, and the rows and the kept failures of the interrupted run
            // are what make the merged result the union of the two.  Doing it here
            // means the terminal write, the progress publishes and the checkpoint all
            // see the same round.
            val round = mergeRestoredRound(restored, fetched)
            logger.info(
                "Crawl {}: seed URL {}/{} completed: {} → {} page(s) ({} restored), {} lost",
                task.taskId, index + 1, task.seedUrls.size, targetUrl,
                round.pages.size, restored?.restoredPages?.size ?: 0, round.failedPages.size
            )
            round to CrawlSeedStatus(
                url = seedUrl,
                status = "fetched",
                pagesReturned = round.pages.size
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
                task.taskId, index + 1, task.seedUrls.size, targetUrl, e.message, e
            )
            // The seed failure is carried by seedStatuses (the
            // synthetic row keeps the URL visible in `pages`),
            // so it is not also a lost page: the
            // `pages + failedPages == pagesExpected` invariant
            // must stay exact.
            val failedRound = CrawlRound(
                pages = listOf(
                    CrawlPageResult(
                        url = targetUrl,
                        title = null,
                        contentLength = null,
                        depth = 0,
                        // The row is a fact of *this* run (the seed did not produce a
                        // page for it), so it carries this run's provenance like any
                        // other row of the merged result.
                        fetchedAt = Instant.now(),
                        run = task.run
                    )
                ),
                failedPages = emptyList(),
                pagesExpected = 1
            )
            // A seed that had already been partly crawled keeps its rows: the
            // failure of *this* attempt is reported by the seed status, and
            // dropping the restored pages would report a smaller crawl than the
            // one that actually happened.
            val round = if (restored != null && restored.started) {
                CrawlRound(
                    pages = restored.restoredPages + failedRound.pages,
                    failedPages = restored.restoredFailures,
                    pagesExpected = restored.restoredExpected + 1
                )
            } else {
                failedRound
            }
            round to CrawlSeedStatus(
                url = seedUrl,
                status = "error",
                pagesReturned = restored?.restoredPages?.size ?: 0,
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
                maxConcurrentFetches = task.peakInFlight.get(),
                // Resume bookkeeping travels on every in-flight record too, so a
                // poller watching a resumed task sees what it skipped and what is
                // left instead of a fresh-looking crawl.
                resumedFrom = task.resumedFrom,
                resumeCount = task.resumeCount,
                skippedAlreadyFetched = task.skippedAlreadyFetched,
                remaining = liveRemaining(task),
                resumable = checkpoints.containsKey(task.taskId)
            )
            taskStore.put(task.taskId, incrementalResponse)
            // A round that has settled is the most valuable thing to checkpoint:
            // these are exactly the URLs a resume must not fetch again.
            updateCheckpointSeed(task, index, round, status)
        }
        // Forced when the round timed out: that is the moment the work state is
        // least recoverable from anywhere else (the round is gone, only the
        // checkpoint knows what it left behind).
        flushCheckpoint(task.taskId, settled = checkpointSettled(task.taskId), force = round.timedOut)
    }

    /**
     * Fold a settled round into the task's checkpoint.
     *
     * Called under the task's publish lock: the checkpoint is the durable twin of
     * the in-memory round bookkeeping, and letting a progress publish and a seed
     * settlement write it concurrently would let the slower writer win.
     */
    private fun updateCheckpointSeed(task: CrawlTaskContext, index: Int, round: CrawlRound, status: CrawlSeedStatus) {
        val current = checkpoints[task.taskId] ?: return
        val seedUrl = task.seedUrls.getOrElse(index) { task.request.url }
        val seed = roundToSeedCheckpoint(
            seedUrl = seedUrl,
            requestDepth = task.request.depth,
            round = round,
            linksDiscovered = task.linksDiscovered.get()
        )
        checkpoints[task.taskId] = current.withSeed(index, seed.copy(status = status.status, error = status.error))
        dirtyCheckpoints.add(task.taskId)
    }

    /**
     * Record the URLs a round just handed to the session, and nothing else.
     *
     * This is the *cheap* half of the checkpoint: an incremental note that a URL is
     * now in flight, so the full state (which costs a scan of everything the round has
     * collected) can stay on its cadence.  Without it, a URL submitted between two
     * state writes is invisible to a resume — and if the page that discovered it is
     * itself restored as already fetched, it is never discovered again: the crawl
     * reports success with fewer pages than the site has, silently.
     *
     * The expected count is raised with each newly recorded URL, so a state that has
     * only ever seen submissions (and not yet a settle) still satisfies
     * `pages + failed + outstanding == pagesExpected`.
     */
    private fun recordSubmitted(task: CrawlTaskContext, seedIndex: Int, items: List<CrawlWorkItem>) {
        if (items.isEmpty()) return
        synchronized(task.publishLock) {
            val current = checkpoints[task.taskId] ?: return
            val seedUrl = task.seedUrls.getOrElse(seedIndex) { task.request.url }
            val seed = current.seed(seedIndex) ?: CrawlSeedCheckpoint(url = seedUrl)
            val known = seed.knownUrls()
            val fresh = items.filterNot { it.key in known }.distinctBy { it.key }
            if (fresh.isEmpty()) return
            val queued = fresh.map { CrawlFailedPage(it.url, it.depth, 0, CrawlLedger.REASON_ROUND_ENDED) }
            checkpoints[task.taskId] = current.withSeed(
                seedIndex,
                seed.copy(
                    outstanding = seed.outstanding + queued,
                    pagesExpected = seed.pagesExpected + queued.size,
                    status = if (seed.status == CrawlSeedCheckpoint.STATUS_PENDING) {
                        CrawlSeedCheckpoint.STATUS_INTERRUPTED
                    } else {
                        seed.status
                    }
                )
            )
        }
        dirtyCheckpoints.add(task.taskId)
    }

    /**
     * Record the work state one round just published, without writing it yet.
     *
     * The round publishes on a cadence it can afford; this is where the *disk*
     * policy lives, so a crawl that settles hundreds of URLs a second does not turn
     * into hundreds of checkpoint rewrites a second.  When the state is due, the
     * snapshot is merged with what the checkpoint already holds for that seed (a
     * continued round reports only its own rows, and the restored rows must not fall
     * out of the checkpoint).
     */
    private fun recordWorkState(
        task: CrawlTaskContext,
        seedIndex: Int,
        settled: Int,
        build: () -> CrawlWorkSnapshot
    ) {
        val policy = checkpointPolicies.computeIfAbsent(task.taskId) { CheckpointWritePolicy() }
        // Ask the policy before building the snapshot: it is linear in the pages
        // recorded so far, and declining early is what keeps publishing cheap.
        if (!policy.due(settled)) return
        val snapshot = build()
        val completed = synchronized(task.publishLock) {
            val current = checkpoints[task.taskId]
            if (current == null) {
                false
            } else {
                val seedUrl = task.seedUrls.getOrElse(seedIndex) { task.request.url }
                val restored = task.resumeFor(seedIndex)
                val slice = if (restored != null && restored.started) {
                    val mergedFailures = restored.restoredFailures +
                        snapshot.failed.filterNot { f -> restored.restoredFailures.any { it.url == f.url } }
                    CrawlSeedCheckpoint(
                        url = seedUrl,
                        depth = current.seed(seedIndex)?.depth ?: 0,
                        completed = snapshot.completed,
                        status = if (snapshot.completed) snapshot.status else CrawlSeedCheckpoint.STATUS_INTERRUPTED,
                        pages = restored.restoredPages + snapshot.pages,
                        failed = mergedFailures,
                        outstanding = snapshot.outstanding,
                        frontier = snapshot.frontier,
                        pagesExpected = restored.restoredExpected + snapshot.pagesExpected,
                        linksDiscovered = snapshot.linksDiscovered,
                        error = snapshot.error
                    )
                } else {
                    snapshot.toSeedCheckpoint(seedUrl, current.seed(seedIndex)?.depth ?: 0)
                }
                checkpoints[task.taskId] = current.withSeed(seedIndex, slice)
                snapshot.completed
            }
        }
        // A changed state is dirty whether or not the write was due: the ticker picks
        // it up as soon as the policy allows, so the state on disk can never be older
        // than that by more than a tick.
        dirtyCheckpoints.add(task.taskId)
        // Forced when the round reports itself complete: that is a state the crawl
        // would otherwise only reach on its next transition, and it is the state a
        // killed process most needs to have said.
        flushCheckpoint(task.taskId, settled = settled, force = completed)
    }

    /**
     * Write a task's checkpoint when its write policy says so.
     *
     * A failure to write never takes the crawl down, but it is not silent either:
     * the whole point of the checkpoint is that a crash does not lose the work, so
     * "the checkpoint could not be written" is a warning the operator has to see.
     *
     * @param settled how many URLs the task has an outcome for; the policy's
     *   change-based trigger.  `force` bypasses the policy entirely and is used on
     *   every transition (a round settling, a status change, shutdown), so the state
     *   on disk is complete at the moments that matter.
     * @return true when the state reached the disk.
     */
    private fun flushCheckpoint(taskId: String, settled: Int, force: Boolean = false): Boolean {
        val checkpoint = checkpoints[taskId] ?: return false
        val policy = checkpointPolicies.computeIfAbsent(taskId) { CheckpointWritePolicy() }
        if (!policy.due(settled, force = force)) return false
        val bytes = checkpointStore.save(checkpoint)
        if (bytes < 0) return false
        policy.record(bytes, settled)
        return true
    }

    /** How many URLs a task's checkpoint already has an outcome for. */
    private fun checkpointSettled(taskId: String): Int =
        checkpoints[taskId]?.seeds?.sumOf { it.pages.size + it.failed.size } ?: 0

    /**
     * Append the rows of [pages] that have not been logged yet to the task's row log.
     *
     * A round publishes its own results list, which only ever grows, so the rows to
     * append are the tail beyond what this seed has already logged.  The append is
     * cheap (one line per row) and monotone (a row is never rewritten), which is what
     * lets it happen on every settle while the state file itself is written on a
     * cadence.
     */
    private fun appendNewRows(task: CrawlTaskContext, seedIndex: Int, pages: List<CrawlPageResult>) {
        if (pages.isEmpty()) return
        val counts = appendedRows.computeIfAbsent(task.taskId) { ConcurrentHashMap() }
        val logged = counts[seedIndex] ?: 0
        if (pages.size <= logged) return
        val fresh = pages.drop(logged)
        var appended = logged
        fresh.forEach { row ->
            if (checkpointStore.appendRow(task.taskId, seedIndex, row)) {
                appended++
            }
        }
        if (appended == logged) return
        counts[seedIndex] = appended
        // The in-memory state is reconciled with the same merge the store applies when
        // it loads: the row settles a URL that was recorded as in flight, so the two
        // must not both claim it while the next full snapshot is still pending.
        val merged = fresh.take(appended - logged)
        synchronized(task.publishLock) {
            checkpoints[task.taskId]?.let { current ->
                checkpoints[task.taskId] =
                    mergeAppendedRows(current, merged.map { CrawlCheckpointRow(seedIndex, it) })
            }
        }
        dirtyCheckpoints.add(task.taskId)
    }

    /** Forget the bookkeeping of a task whose checkpoint is gone. */
    private fun forgetCheckpoint(taskId: String) {
        checkpoints.remove(taskId)
        checkpointPolicies.remove(taskId)
        dirtyCheckpoints.remove(taskId)
        appendedRows.remove(taskId)
    }

    /**
     * How much work a running task still has, computed from its live round
     * bookkeeping rather than from the checkpoint (which is written on a cadence).
     */
    private fun liveRemaining(task: CrawlTaskContext): Int = synchronized(task.publishLock) {
        task.seedUrls.indices.sumOf { index ->
            val round = task.seedRounds[index]
            when {
                round != null -> round.outstanding.size + round.frontier.size
                task.seedStatuses[index] != null -> 0
                task.resumeFor(index)?.completed == true -> 0
                else -> 1
            }
        }
    }

    /**
     * Write the terminal OK/TIMEOUT record for a crawl whose seeds all settled.
     *
     * A round that hit its own timeout already wrote a TIMEOUT record; this
     * terminal write used to overwrite it with OK, so a truncated crawl reported
     * success.  Keep the timeout status and say how much of the work never
     * completed.
     *
     * A crawl that still has work left (a timed-out round's outstanding URLs, a
     * discovered frontier, or a seed the budget refused) keeps its checkpoint and
     * says so: [CrawlResponse.remaining] is what `crawl resume` would pick up, and
     * [CrawlResponse.resumable] is whether it can.  A crawl that finished deletes
     * the checkpoint — there is nothing left to continue, and leaving files behind
     * for every successful task would make the checkpoint store useless.
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
        val remaining = collected.rounds.sumOf { it.outstanding.size + it.frontier.size }
        // A crawl can finish with pages it never received: a URL that failed
        // terminally is settled, not outstanding, and the run is still "complete".
        // Its checkpoint is kept for that case alone — it is the only state
        // `crawl resume --retry-failed` could recover those URLs from.
        val retryableFailures = failedPages.count { loss ->
            collected.rounds.none { round -> round.outstanding.any { it.url == loss.url } }
        }
        val keepCheckpoint = remaining > 0 || retryableFailures > 0
        val resumeNote = when {
            remaining > 0 -> "$remaining URL(s) of this crawl were not delivered; resume it with " +
                "'crawl resume ${task.taskId}' to continue from the checkpoint"
            retryableFailures > 0 -> "$retryableFailures URL(s) failed terminally; " +
                "'crawl resume ${task.taskId} --retry-failed' fetches them again"
            else -> null
        }
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
            diagnostic = listOfNotNull(existingDiagnostic, lossNote, resumeNote).joinToString(" | ")
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
            maxConcurrentFetches = task.peakInFlight.get(),
            resumedFrom = task.resumedFrom,
            resumeCount = task.resumeCount,
            skippedAlreadyFetched = task.skippedAlreadyFetched,
            remaining = remaining,
            resumable = keepCheckpoint && checkpoints.containsKey(task.taskId)
        )
        taskStore.put(task.taskId, completed)
        onStatusChanged(completed)
        finishCheckpoint(task.taskId, resumable = completed.resumable)
        logger.info(
            "Crawl task {} completed: {} pages, {} lost, status {}, parallel budget {} (peak {} in flight){}",
            task.taskId, allPages.size, failedPages.size, completed.status,
            task.parallelTabs, task.peakInFlight.get(),
            when {
                remaining > 0 -> ", $remaining URL(s) resumable"
                retryableFailures > 0 -> ", $retryableFailures failed URL(s) retryable"
                else -> ""
            }
        )
    }

    /**
     * Settle the fate of a task's checkpoint once the task reaches a terminal state.
     *
     * Kept when the task still has something a resume could do — URLs left in
     * flight, a frontier that was discovered but never queued, or URLs that failed
     * terminally (which `--retry-failed` fetches again) — and deleted otherwise: a
     * checkpoint with nothing left to do is dead weight, and its rows are already in
     * the record.
     */
    private fun finishCheckpoint(taskId: String, resumable: Boolean) {
        val checkpoint = checkpoints[taskId]
        if (resumable && checkpoint != null) {
            // The terminal work state is the one a resume reads, so it is written
            // unconditionally rather than on the write cadence.
            flushCheckpoint(taskId, settled = checkpointSettled(taskId), force = true)
            return
        }
        forgetCheckpoint(taskId)
        checkpointStore.delete(taskId)
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
                // A seed whose round never returned is not the only seed with
                // something to carry over: a *resumed* seed may already have rows
                // from the run this one continues, and those rows are as true as the
                // ones collected now.  Claiming only the settled rounds' pages would
                // report a resumed crawl as smaller than the work it actually did.
                val unsettledRestored = task.seedUrls.indices
                    .filter { task.seedRounds[it] == null }
                    .mapNotNull { task.resumeFor(it) }
                    .filter { it.started }
                CancelledSnapshot(
                    pages = unsettledRestored.flatMap { it.restoredPages } + settled.flatMap { it.pages },
                    failures = unsettledRestored.flatMap { it.restoredFailures } + settled.flatMap { it.failedPages },
                    unfinished = unfinished,
                    pagesExpected = unsettledRestored.sumOf { it.restoredExpected } +
                        settled.sumOf { it.pagesExpected } + unfinished.size,
                    seedStatuses = task.seedUrls.indices.map { index ->
                        task.seedStatuses[index] ?: task.resumeFor(index)?.takeIf { it.started }?.let { restored ->
                            CrawlSeedStatus(
                                url = task.seedUrls[index],
                                status = restored.status,
                                pagesReturned = restored.restoredPages.size,
                                error = unfinishedReason
                            )
                        } ?: CrawlSeedStatus(
                            url = task.seedUrls[index], status = "timeout", pagesReturned = 0, error = unfinishedReason
                        )
                    }
                )
            }
            val failedPages = snapshot.failures + snapshot.unfinished
            val lossNote = buildLossNote(snapshot.pages.size, snapshot.pagesExpected, failedPages)
            val remaining = liveRemaining(task)
            val resumeNote = if (remaining > 0) {
                "$remaining URL(s) of this crawl were left unfinished; resume it with " +
                    "'crawl resume ${task.taskId}' to continue from the checkpoint"
            } else {
                null
            }
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
                diagnostic = listOfNotNull(
                    existing?.diagnostic?.takeIf { it.isNotBlank() }, lossNote, resumeNote
                ).joinToString(" | ").takeIf { it.isNotBlank() },
                failedPages = failedPages.takeIf { it.isNotEmpty() },
                pagesExpected = snapshot.pagesExpected,
                // A timed-out crawl still reports the parallelism it was
                // running under and the overlap it achieved, so the
                // partial result says "how" as well as "how much".
                parallelTabs = existing?.parallelTabs ?: task.parallelTabs,
                taskTimeoutMillis = existing?.taskTimeoutMillis ?: task.taskTimeoutMillis,
                maxConcurrentFetches = maxOf(existing?.maxConcurrentFetches ?: 0, task.peakInFlight.get()),
                startedTime = existing?.startedTime ?: now,
                finishTime = now,
                resumedFrom = task.resumedFrom,
                resumeCount = task.resumeCount,
                skippedAlreadyFetched = task.skippedAlreadyFetched,
                remaining = remaining,
                // A run cut off by the task limit or by a caller is the case resume
                // exists for: its checkpoint is written out and kept, so the task can
                // be continued instead of re-submitted.
                resumable = remaining > 0 && checkpoints.containsKey(task.taskId)
            )
            taskStore.put(task.taskId, timedOut)
            onStatusChanged(timedOut)
            finishCheckpoint(task.taskId, resumable = timedOut.resumable)
            logger.warn(
                "Crawl task {} cancelled or timed out: {} — {} page(s) recorded, {} lost, " +
                    "{} seed(s) never settled, {} URL(s) resumable",
                task.taskId, e.message, snapshot.pages.size, failedPages.size,
                snapshot.unfinished.size, remaining
            )
        } else {
            logger.warn("Crawl task {} cancelled or timed out: {}", task.taskId, e.message)
        }
    }

    /** Write the terminal record of a crawl that died on an unexpected error. */
    private fun writeFailed(task: CrawlTaskContext, e: Exception) {
        val existing = taskStore.getIfPresent(task.taskId)
        val now = Instant.now()
        val remaining = liveRemaining(task)
        val failed = CrawlResponse(
            taskId = task.taskId,
            status = CrawlStatus.INTERNAL_SERVER_ERROR,
            error = e.message ?: "Unknown error",
            parallelTabs = task.parallelTabs,
            taskTimeoutMillis = task.taskTimeoutMillis,
            maxConcurrentFetches = task.peakInFlight.get(),
            startedTime = existing?.startedTime ?: now,
            finishTime = now,
            resumedFrom = task.resumedFrom,
            resumeCount = task.resumeCount,
            skippedAlreadyFetched = task.skippedAlreadyFetched,
            remaining = remaining,
            // A task that died on an error is resumable exactly when it has work
            // left: whatever it had fetched is in the checkpoint, and the URLs it
            // never reached are what a resume would fetch.
            resumable = remaining > 0 && checkpoints.containsKey(task.taskId)
        )
        taskStore.put(task.taskId, failed)
        onStatusChanged(failed)
        finishCheckpoint(task.taskId, resumable = failed.resumable)
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
                task.taskId, previous, aggregated, linksDiscovered, diagnostic, terminalStatuses,
                remaining = liveRemaining(task),
                // The checkpoint exists from the first page of the task on, so an
                // in-flight record says "resumable" for the same reason the settled
                // ones do.
                resumable = checkpoints.containsKey(task.taskId)
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
     *
     * A cancellation is a *choice*, not a loss: the task's checkpoint is written
     * out and kept, the record says how much is left, and `crawl resume` continues
     * it.  The workflow that made this worth doing is the one where a caller
     * cancels a crawl that is taking too long and then decides to finish it.
     *
     * @return true if the task was found and cancelled, false otherwise.
     */
    fun cancel(taskId: String): Boolean {
        val job = jobStore.remove(taskId) ?: return false
        job.cancel()
        val now = Instant.now()
        val previous = taskStore.getIfPresent(taskId)
        val checkpoint = checkpoints[taskId]
        val remaining = checkpoint?.remaining() ?: 0
        val cancelled = CrawlResponse(
            taskId = taskId,
            status = CrawlStatus.REQUEST_TIMEOUT,
            error = "Cancelled by user",
            parallelTabs = previous?.parallelTabs ?: 0,
            taskTimeoutMillis = previous?.taskTimeoutMillis ?: 0,
            maxConcurrentFetches = previous?.maxConcurrentFetches ?: 0,
            startedTime = previous?.startedTime ?: now,
            finishTime = now,
            pagesFound = previous?.pagesFound ?: 0,
            pages = previous?.pages,
            failedPages = previous?.failedPages,
            pagesExpected = previous?.pagesExpected ?: 0,
            resumedFrom = previous?.resumedFrom,
            resumeCount = previous?.resumeCount ?: 0,
            skippedAlreadyFetched = previous?.skippedAlreadyFetched ?: 0,
            remaining = remaining,
            resumable = remaining > 0 && checkpoint != null,
            diagnostic = listOfNotNull(
                previous?.diagnostic?.takeIf { it.isNotBlank() },
                if (remaining > 0) {
                    "cancelled with $remaining URL(s) left; resume it with 'crawl resume $taskId'"
                } else {
                    null
                }
            ).joinToString(" | ").takeIf { it.isNotBlank() }
        )
        taskStore.put(taskId, cancelled)
        onStatusChanged(cancelled)
        finishCheckpoint(taskId, resumable = cancelled.resumable)
        logger.info("Crawl task {} cancelled by user ({} URL(s) left, resumable={})", taskId, remaining, cancelled.resumable)
        return true
    }

    /**
     * Remove all terminal-state tasks from the store.
     *
     * A checkpoint that still has work left is **kept**: `crawl resume <taskId>`
     * reads the input contract and the work state from it, so a cleared
     * timed-out task can still be continued (the record is what was cleared, not
     * the ability to finish the work).  `crawl clear --all` is the explicit way to
     * throw resumable state away.
     *
     * @return the number of tasks removed.
     */
    fun clearTerminal(): Int {
        val toRemove = taskStore.asMap().entries.filter { it.value.status in terminalStatuses }
        toRemove.forEach { taskStore.invalidate(it.key) }
        val keptResumable = toRemove.count { entry -> checkpoints[entry.key]?.hasWork() == true }
        toRemove.filterNot { entry -> checkpoints[entry.key]?.hasWork() == true }
            .forEach { entry -> finishCheckpoint(entry.key, resumable = false) }
        logger.info(
            "Cleared {} terminal crawl tasks ({} resumable checkpoint(s) kept)",
            toRemove.size, keptResumable
        )

        // Rewrite the JSONL persistence file so cleared tasks don't revive on restart.
        // Without this, terminal tasks removed from the in-memory Caffeine cache are
        // re-read from the append-only JSONL file at startup.
        if (toRemove.isNotEmpty()) {
            rewritePersistence()
        }

        return toRemove.size
    }

    /**
     * Remove ALL tasks from the store, including actively-running ones, and discard
     * every checkpoint.  Cancels running jobs before clearing.  Use with caution.
     *
     * This is the one explicit way to throw resumable state away: after it, an
     * interrupted task cannot be resumed, only submitted again.
     *
     * @return the number of tasks removed.
     */
    fun clearAll(): Int {
        // Cancel all active jobs first
        jobStore.values.forEach { it.cancel() }
        jobStore.clear()

        val size = taskStore.asMap().size + interruptedStore.size
        taskStore.invalidateAll()
        // Interrupted tasks live outside the bounded store (they must survive
        // eviction), so clearing them is explicit here — and only here.
        interruptedStore.clear()
        checkpoints.clear()
        checkpointPolicies.clear()
        dirtyCheckpoints.clear()
        appendedRows.clear()
        val discarded = checkpointStore.deleteAll()
        persistence.clear()
        logger.info("Cleared all {} crawl tasks (including active) and {} checkpoint file(s)", size, discarded)
        return size
    }

    /**
     * Purge tasks whose TTL has expired.  Only removes terminal-state tasks;
     * actively-running tasks are never purged.
     *
     * Interrupted tasks are not in [taskStore] at all, so they are never purged —
     * that is the point (see [interruptedStore]).  A task that is terminal but
     * still has resumable work keeps its **checkpoint** when its record expires:
     * the record is a report, the checkpoint is the ability to finish the crawl.
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
        expired.forEach { entry ->
            val checkpoint = checkpoints[entry.key]
            if (checkpoint != null && checkpoint.hasWork()) {
                // Keep the files (resume must still work), drop the in-memory copy:
                // nothing is running, and `crawl resume` loads it from disk.
                forgetCheckpoint(entry.key)
            } else {
                finishCheckpoint(entry.key, resumable = false)
            }
        }
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
        // Interrupted tasks are not in the store but are very much part of the
        // state to restore, so they are written back explicitly.
        interruptedStore.values.forEach { persistence.append(it) }
    }

    /**
     * Get the current status/result of a crawl task.
     *
     * An interrupted task is answered from [interruptedStore]: it is still a task
     * the caller can ask about (and resume), even though it is deliberately kept
     * out of the bounded store.
     */
    fun getResult(taskId: String): CrawlResponse {
        taskStore.getIfPresent(taskId)?.let { return it }
        interruptedStore[taskId]?.let { return it }
        return CrawlResponse(
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
        /**
         * What this run continues, or null when it is the task's first run.
         *
         * A plan is not a summary of the checkpoint: it is the decision of what to
         * fetch, what to skip and what to leave failed, taken once so the worker and
         * the report cannot disagree about it (see [planResume]).
         */
        val resume: CrawlResumePlan? = null,
        /**
         * Which run this is: 1 for the submission, 2 for the first resume, and so on.
         * Recorded on every row ([CrawlPageResult.run]) so a merged result says
         * which rows came from which run.
         */
        val run: Int = 1,
        /** How many times this task has been resumed, this run included. */
        val resumeCount: Int = 0,
        /** The interruption this run continues from; null for a first run. */
        val resumedFrom: Instant? = null,
    ) {
        // Out-links discovered beyond the seed URLs (depth>=1 crawls),
        // aggregated across seeds.  Kept separate from the result size so a
        // crawl that only records seed page(s) (0 discovered links) is
        // distinguishable from one that followed links.
        //
        // A resumed run starts from what the checkpoint already discovered, so the
        // merged report counts the links of both runs instead of restarting at zero.
        val linksDiscovered = AtomicInteger(resume?.seeds?.sumOf { it.linksDiscovered } ?: 0)

        // How many fetch units are in flight right now, and the peak this crawl
        // reached.  The peak is the observed counterpart of the budget:
        // reporting it is what makes "the pages were collected in parallel"
        // inspectable instead of asserted.
        val inFlight = AtomicInteger()
        val peakInFlight = AtomicInteger()

        /** URLs the checkpoint already delivered and this run therefore never requests. */
        val skippedAlreadyFetched: Int get() = resume?.skippedAlreadyFetched ?: 0

        // Seed rounds and per-seed statuses are stored by seed index, so the
        // final response keeps the seed order even when the seeds are fetched
        // concurrently (see [mapCrawlSeedsConcurrently]).
        val seedRounds: Array<CrawlRound?> = arrayOfNulls(seedUrls.size)
        val seedStatuses: Array<CrawlSeedStatus?> = arrayOfNulls(seedUrls.size)

        /** What the resume planned for seed [index] (null for a seed it runs from scratch). */
        fun resumeFor(index: Int): CrawlSeedResume? = resume?.seeds?.getOrNull(index)

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
         * How often the service looks for checkpoints that changed since their last
         * write (ms).
         *
         * The checkpoint's write policy is evaluated when a round publishes, and a
         * round publishes when something settles — so without a tick, a crawl that
         * goes quiet leaves its state on disk stale for as long as the quiet lasts
         * (measured: a `SIGKILL` 8 s after the last settle found a state older than
         * the 2 s bound).  A tick shorter than the policy's staleness bound is what
         * makes that bound real; the policy still owns the write rate.
         */
        private const val CHECKPOINT_FLUSH_TICK_MS = 1_000L

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

        /** Why an interrupted URL is still outstanding: its worker died. */
        const val REASON_INTERRUPTED =
            "the crawl was interrupted by a server restart before this URL settled"

        /** Why an interrupted task cannot be resumed: its work state was never persisted. */
        const val REASON_NO_CHECKPOINT =
            "no resume checkpoint was found, so the work this crawl had already done is not recoverable"

        fun crawlPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "crawl", "crawl-tasks.jsonl"
        )

        /**
         * Where the resumable checkpoints live: a directory of their own, next to
         * the task file but *outside* its lifecycle.
         *
         * The task file is rewritten whenever tasks are cleared or purged, and the
         * task store is bounded at 100 entries; a checkpoint has to outlive both, so
         * it gets its own file per task and its own explicit deletion
         * (`crawl clear --all`, or a terminal state with nothing left to do).
         */
        fun crawlCheckpointDir(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "crawl", "checkpoints"
        )
    }
}
