package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.browser4.common.B4Constants.PAGE_STATUS_UNKNOWN
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.agents.RunEngine
import ai.platon.pulsar.agentic.agents.RobustBrowserAgent
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.detail.DefaultServerSideAgentEventHandlers
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class StatefulAgentRunner(
    val session: AgenticSession,
    /**
     * Optional dispatcher the agent run itself is confined to. Waiting for the
     * [runMutex] happens on [taskScope] regardless — a queued task does not
     * occupy one of the bounded dispatcher's slots, only the actually RUNNING
     * task does.
     */
    private val runDispatcher: CoroutineDispatcher? = null,
) : Closeable {
    private val logger = getLogger(StatefulAgentRunner::class)

    /**
     * Size-bounded, time-expiring cache of agent task statuses.
     *
     * - Entries live at most 2 hours after last write.
     * - At most 100 entries; Window TinyLFU eviction beyond that.
     * - Persisted to JSONL so task statuses survive restarts.
     *
     * Note: the cache's `expireAfterWrite` clock starts at `create()` (the single
     * cache write), while restore/compaction TTLs are measured from
     * [AgentTaskStatus.finishTime] — see [compactPersistence] and [restoreFromDisk]
     * for the on-disk TTL handling.
     * */
    private val statusCache: Cache<String, AgentTaskStatus> = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(2, TimeUnit.HOURS)
        .recordStats()
        .build()

    /**
     * Serializes agent task execution for this session. The companion agent and its
     * AgentStateManager are shared session resources — concurrent tasks would interleave
     * their contexts and histories, so only one run may be active at a time. Tasks
     * submitted while another is running are queued here.
     */
    private val runMutex = Mutex()

    private val taskSupervisor = SupervisorJob()

    /**
     * Scope for agent task execution jobs. Cancelling the job of a task stops
     * its agent loop (see [cancel]). Cancelling this scope in [close] stops all
     * of them — otherwise tasks kept running against a closed runner (and a
     * possibly closed session) after a rebuild.
     */
    private val taskScope = CoroutineScope(
        Dispatchers.IO + taskSupervisor + CoroutineName("agent-tasks")
    )

    /** Currently queued/running task jobs by task id (for [cancel]). */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    /**
     * Shared per process: all runners write the same global JSONL file, so they
     * must share ONE persistence instance — separate instances would interleave
     * unsynchronized appends and each compaction would drop the other runners'
     * rows when clearing the common file.
     */
    internal val persistence: JsonlPersistence<AgentTaskStatus> = sharedAgentPersistence()

    /** Dedicated dispatcher for cleanup operations. */
    private val cleanupDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val cleanupScope = CoroutineScope(
        cleanupDispatcher + SupervisorJob() + CoroutineName("agent-cleanup")
    )

    /** How long to keep terminal tasks before compacting them out of the JSONL file. */
    @Volatile
    var taskTtlMinutes: Int = 120

    init {
        liveInstances.add(this)
        restoreFromDisk()
        // Periodically compact the JSONL file so evicted entries don't accumulate forever.
        cleanupScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // every 5 minutes
                compactPersistence()
            }
        }
    }

    /**
     * Entries whose finish (or creation for non-terminal rows) is this long ago are
     * eligible for compaction.
     */
    private fun ttlCutoff(): Instant = Instant.now().minusSeconds(taskTtlMinutes * 60L)

    fun restoreFromDisk() {
        val ttlCutoff = ttlCutoff()
        val terminalState = TERMINAL_PROCESS_STATE

        persistence.restore { entry ->
            // Skip terminal entries whose TTL has expired — they were evicted
            // from the cache before shutdown and should not be revived. The TTL
            // is measured from when the task FINISHED (a task created long ago
            // may have finished just before the shutdown); using createdTime
            // here expired recently-finished, long-lived tasks too early.
            if (entry.processState == terminalState &&
                (entry.finishTime ?: entry.createdTime).isBefore(ttlCutoff)
            ) {
                logger.debug(
                    "Skipping expired agent task {} during restore (finishTime={})",
                    entry.id, entry.finishTime
                )
                return@restore
            }
            // Terminal states never regress: when an earlier row already restored
            // a terminal status for this id, a later non-terminal row (append-order
            // glitch in the JSONL) must not overwrite it back to queued/in_progress.
            val cached = statusCache.getIfPresent(entry.id)
            if (cached?.processState == terminalState && entry.processState != terminalState) {
                logger.debug("Skipping non-terminal row for agent task {} — terminal status already restored", entry.id)
                return@restore
            }
            if (entry.processState != terminalState) {
                // A non-terminal row on disk means the process died mid-task (or a queued
                // task was never started): nobody will ever resume it. Restoring it as
                // queued/in_progress produced a zombie status that looked alive for the
                // rest of the cache TTL — restore it as an interrupted failure instead.
                // A task still owned by a live runner in this process is authoritative
                // and must not be clobbered from the file snapshot.
                val liveOwner = liveInstances.firstOrNull { it !== this && it.getStatus(entry.id)?.isDone == false }
                if (liveOwner != null) {
                    statusCache.put(entry.id, entry)
                    return@restore
                }
                entry.failed(ResourceStatus.SC_EXPECTATION_FAILED)
                entry.failureReason = INTERRUPTED_REASON
                entry.message = "Agent task did not complete before the server restarted"
                statusCache.put(entry.id, entry)
                // Persist the correction so subsequent restores see a terminal row.
                persistence.append(entry)
                logger.debug("Restored interrupted agent task {} as failed", entry.id)
                return@restore
            }
            statusCache.put(entry.id, entry)
            logger.debug("Restored agent task {}", entry.id)
        }
    }

    /**
     * Compact the JSONL persistence file: remove stale entries that are past TTL.
     *
     * The rewrite is the union of ALL live runners' statuses — each runner used to
     * clear the shared file and re-append only its own cache, wiping the other
     * runners' rows from the global file.
     */
    internal fun compactPersistence() {
        val ttlCutoff = ttlCutoff()
        val terminalState = TERMINAL_PROCESS_STATE

        val stale = statusCache.asMap().values.filter {
            it.processState == terminalState && (it.finishTime ?: it.createdTime).isBefore(ttlCutoff)
        }
        if (stale.isEmpty()) return

        stale.forEach { statusCache.invalidate(it.id) }
        logger.info("Compacted {} expired agent tasks (TTL: {} min)", stale.size, taskTtlMinutes)

        rewriteSharedPersistence()
    }

    /**
     * Atomically rewrite the shared JSONL file with the union of every live runner's
     * statuses (snapshots detached first: an in-progress status may hold the agent's
     * live history reference, which mutates concurrently with this rewrite).
     */
    private fun rewriteSharedPersistence() {
        val snapshots = sharedStatusesForRewrite().map { detachedSnapshot(it) }
        persistence.rewrite(snapshots)
    }

    /** Union by task id across all live runners, newest `lastModifiedTime` wins. */
    private fun sharedStatusesForRewrite(): Collection<AgentTaskStatus> {
        val union = HashMap<String, AgentTaskStatus>()
        for (runner in liveInstances) {
            for (entry in runner.statusCache.asMap().values) {
                val existing = union[entry.id]
                if (existing == null || newerInstant(entry.lastModifiedTime, existing.lastModifiedTime)) {
                    union[entry.id] = entry
                }
            }
        }
        return union.values
    }

    private fun detachedSnapshot(status: AgentTaskStatus): AgentTaskStatus = status.copy().apply {
        agentHistory = status.agentHistory?.let { AgentHistory(it.states.toMutableList()) }
    }

    fun create(): AgentTaskStatus {
        val status = AgentTaskStatus()
        statusCache.put(status.id, status)
        persistence.append(status)
        status.emitEvent("StatefulAgentRunner.created")
        logger.debug("Created agent task status {} (session={})", status.id, session.uuid)
        return status
    }

    private fun onStatusChanged(status: AgentTaskStatus) {
        persistence.append(status)
    }

    /**
     * Execute a plain command using the agent's run method.
     *
     * This method creates a cached status, executes the agent's run method, and updates
     * the status with the result.
     *
     * @param plainCommand The plain text command for the agent to execute.
     * @return AgentStatus containing the execution result.
     */
    suspend fun execute(
        plainCommand: String,
        noopLimit: Int? = null,
        engine: RunEngine? = null,
    ): AgentTaskStatus {
        val status = create()
        execute(plainCommand, status, noopLimit, engine)
        return status
    }

    /**
     * Submit [plainCommand] for background execution and return the tracked [Job].
     *
     * The job is registered BEFORE it starts ([CoroutineStart.LAZY]), so a cancel
     * racing the scheduler always finds it in [runningJobs]. Cancelling a task that
     * never gets to run (still queued on [runMutex], or cancelled before start)
     * marks its status terminal here or in [execute] — the status can never stay
     * `created` on a dead task.
     */
    fun submit(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        engine: RunEngine? = null,
    ): Job {
        val job = taskScope.launch(start = CoroutineStart.LAZY) {
            execute(plainCommand, status, noopLimit, engine)
        }
        runningJobs[status.id] = job
        job.start()
        job.invokeOnCompletion { cause ->
            runningJobs.remove(status.id, job)
            // A job cancelled before its coroutine ever ran (scope cancelled racing
            // this registration, submit against an already-closed runner) leaves no
            // coroutine behind to mark the status — the completion cause is the last
            // chance. When the coroutine did run, execute()'s own marking already
            // made it terminal before this fires.
            if (cause is CancellationException && !status.isDone) {
                status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
                status.cancelled = true
                status.failureReason = status.failureReason ?: "Task cancelled"
                status.message = status.message ?: "Task cancelled"
                persistence.append(status)
            }
        }
        return job
    }

    /**
     * Cancel a queued/running task by id.
     *
     * @return true when a live job was found and cancelled; false when the task
     *   is unknown, already finished, or its job is no longer tracked.
     */
    fun cancel(id: String): Boolean {
        val job = runningJobs.remove(id) ?: return false
        logger.info("Cancelling agent task {} (session={})", id, session.uuid)
        // Mark the status BEFORE cancelling the job: the coroutine may be waiting on
        // [runMutex] or not yet scheduled, in which case nothing inside it can mark
        // the status terminal. Idempotent with executeSerialized's own marking.
        statusCache.getIfPresent(id)?.takeIf { !it.isDone }?.let { status ->
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            status.cancelled = true
            status.failureReason = "Task cancelled"
            status.message = "Task cancelled"
            persistence.append(status)
        }
        job.cancel()
        return true
    }

    /**
     * Internal method to execute agent command with a pre-created status.
     *
     * Execution is serialized per session through [runMutex] so concurrent tasks cannot
     * interleave the shared companion agent's execution contexts and history.
     *
     * This method creates and wires up ServerSideAgentEventHandlers for event collection,
     * following the pattern from StatefulPageVisitor#doVisit. A [supervisorScope] ensures
     * the event collector is structured within this call — it is launched as a child,
     * cancelled in the finally block, and the scope suspends until it terminates.
     */
    suspend fun execute(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        engine: RunEngine? = null,
    ) {
        try {
            runMutex.withLock {
                executeSerialized(plainCommand, status, noopLimit, engine)
            }
        } catch (e: CancellationException) {
            // A task cancelled while queued never reaches executeSerialized — without
            // this, its status would stay `created` forever and every poll would look
            // queue-healthy (CLI --wait spins until its timeout, agent list shows
            // it alive).
            if (!status.isDone) {
                status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
                status.cancelled = true
                status.failureReason = status.failureReason ?: "Task cancelled"
                status.message = status.message ?: "Task cancelled"
                onStatusChanged(status)
            }
            throw e
        }
    }

    private suspend fun executeSerialized(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        engine: RunEngine? = null,
    ) {
        val agent = session.companionAgent

        // The companion agent keeps ONE cumulative stateHistory across every task
        // executed in the session. Remember where this task's states begin so the
        // published history never leaks states from earlier tasks (a running
        // status must not serialize other tasks' states either — its history is
        // refreshed as a task-scoped snapshot below, never the live list).
        val startIndex = agent.stateHistory.size

        // Set once agent.run() has returned. Events the collector drains after that
        // point are stale: they must not overwrite the final result message or the
        // detached final snapshot taken in the finally block below.
        val runHasReturned = AtomicBoolean(false)

        try {
            status.refresh(ResourceStatus.SC_PROCESSING)

            // Per-task noop tolerance (e.g. `agent run --noop-limit 10`): long coding chains
            // benefit from a higher limit than the default. A null value resets any stale
            // override left over from a previous task.
            (agent as? RobustBrowserAgent)?.let {
                it.noopLimitOverride = noopLimit
                if (noopLimit != null) {
                    logger.info("Agent task {} (session={}): noop limit overridden to {}", status.id, session.uuid, noopLimit)
                }
                it.runEngineOverride = engine
                if (engine != null) {
                    logger.info("Agent task {} (session={}): engine overridden to {}", status.id, session.uuid, engine)
                }
            }

            // Create and wire up ServerSideAgentEventHandlers for this command
            val serverSideAgentEventHandlers = DefaultServerSideAgentEventHandlers()
            status.serverSideAgentEventHandlers = serverSideAgentEventHandlers

            // Start with an empty task-scoped snapshot (never the live cumulative list).
            status.agentHistory = taskScopedHistory(agent.stateHistory, startIndex)

            supervisorScope {
                // Start a child coroutine to collect events and update status
                val eventCollectorJob = launch {
                    try {
                        serverSideAgentEventHandlers.eventFlow.collect { event ->
                            status.emitEvent(event.eventType)
                            if (!runHasReturned.get()) {
                                status.agentHistory = taskScopedHistory(agent.stateHistory, startIndex)
                            }
                            logger.debug("Collected event {} for agent task {} (session={})", event.eventType, status.id, session.uuid)
                        }
                    } catch (e: CancellationException) {
                        logger.debug("Event collector cancelled for agent task {}", status.id)
                        throw e
                    } catch (e: Exception) {
                        logger.error("Error collecting events for agent task {} (session={})", status.id, session.uuid, e)
                    }
                }

                try {
                    // Bind server-side agent event handlers to THIS coroutine so multiple commands can run concurrently.
                    AgentEventBus.withServerSideAgentEventHandlers(serverSideAgentEventHandlers) {
                        if (runDispatcher != null) {
                            withContext(runDispatcher) {
                                executeAgentCommand(plainCommand, status, agent, runHasReturned, startIndex)
                            }
                        } else {
                            executeAgentCommand(plainCommand, status, agent, runHasReturned, startIndex)
                        }
                    }
                } finally {
                    // Cancel event collector when command completes
                    eventCollectorJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            logger.warn("Agent task {} cancelled: {}", status.id, e.message)
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            status.cancelled = true
            status.failureReason = e.message ?: "Task cancelled"
            status.message = e.message ?: "Task cancelled"
            throw e
        } catch (e: Throwable) {
            logger.error("Failed to execute agent command: {} (session={})", plainCommand, session.uuid, e)
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            status.failureReason = "${e.javaClass.simpleName}: ${e.message}"
            status.message = e.message
        } finally {
            // Reset the per-task overrides: the companion agent is shared with callers
            // outside this runner's mutex (chat, direct agent tools), and a stale
            // --noop-limit / runEngine override would silently change those runs too.
            (agent as? RobustBrowserAgent)?.let {
                it.noopLimitOverride = null
                it.runEngineOverride = null
                it.codingMode = false
            }
            status.done()
            onStatusChanged(status)
        }
    }

    /**
     * Ensure the session's bound driver is usable before an agent task runs.
     *
     * [ai.platon.pulsar.skeleton.session.PulsarSession.getOrCreateBoundDriver]
     * returns the existing bound driver without a health check, so a driver whose
     * browser was torn down keeps being reused across tasks. When the health check
     * fails, unbind and close the dead driver (and its browser) so the next
     * [ai.platon.pulsar.skeleton.session.PulsarSession.getOrCreateBoundDriver]
     * launches a fresh browser.
     */
    private suspend fun ensureSessionDriverHealthy() {
        val driver = runCatching { session.boundDriver }.getOrNull() as? AbstractWebDriver
            ?: return // no bound driver yet — a fresh one is created on first use

        val healthy = runCatching { driver.quickCheckHealthy().isOK }.getOrDefault(false)
        if (healthy) {
            return
        }

        logger.warn("Agent task: session driver is unhealthy; resetting bound driver and browser")
        runCatching { session.unbindDriver(driver) }
            .onFailure { logger.warn("Failed to unbind unhealthy driver", it) }
        runCatching { driver.close() }
            .onFailure { logger.warn("Failed to close unhealthy driver", it) }

        val browser = runCatching { session.boundBrowser }.getOrNull()
        if (browser != null) {
            runCatching { session.unbindBrowser(browser) }
                .onFailure { logger.warn("Failed to unbind dead browser", it) }
            runCatching { browser.close() }
                .onFailure { logger.warn("Failed to close dead browser", it) }
        }
    }

    /**
     * Executes the agent command logic.
     *
     * This method is extracted to allow the event handlers to be properly bound
     * to the execution context via PulsarEventBus.withServerSideEventHandlers.
     */
    private suspend fun executeAgentCommand(
        plainCommand: String,
        status: AgentTaskStatus,
        agent: PerceptiveAgent,
        runHasReturned: AtomicBoolean,
        startIndex: Int,
    ) {
        // Record the submitted instruction so callers can verify they're
        // getting results for the right task (prevents cross-talk confusion).
        status.submittedTask = plainCommand

        // Record where this task's tools may write, so a finished task can point its
        // caller at the deliverables instead of leaving them to search app-data.
        status.workspace = (agent as? BasicBrowserAgent)?.workspaceRoot?.toString()

        // Pure coding tasks (file ops / build) don't need a browser page. Switch the
        // agent into coding mode: no driver health check, no search-engine navigation,
        // no screenshots — those page steps were the source of the 30s DOM timeouts
        // that killed coding runs.
        val codingMode = CodingTaskDetector.detect(plainCommand)
        (agent as? RobustBrowserAgent)?.codingMode = codingMode
        if (codingMode) {
            logger.info("Agent task {} (session={}): coding mode — skipping driver health check and page navigation", status.id, session.uuid)
        } else {
            // A session reuses its bound driver across tasks. If a previous task (or a
            // close-all) shut the browser down, the driver points at a dead browser and
            // the agent would fail every step (DOM settle timeout, browserUseState
            // degradation, zero-step runs). Reset the bound driver/browser so a fresh
            // browser is created for this task.
            ensureSessionDriverHealthy()
        }

        // Save the user's current page URL so we can restore it after the agent
        // runs, preventing the agent from polluting the shared browser session.
        val savedUrl = runCatching { session.boundDriver?.currentUrl() }.getOrNull()
        logger.debug("Agent task {}: saved user page URL before agent run: {}", status.id, savedUrl)

        val history = try {
            agent.run(plainCommand).also { runHasReturned.set(true) }
        } finally {
            // Scope the status history to THIS task's execution session and detach it
            // from the agent's live (accumulating/trimming) history list. Without this,
            // a status could expose other tasks' states, and later history trims could
            // retroactively shrink a completed task's history.
            //
            // `lastRunSessionId` is only published once `run()` returns, so a run that
            // fails early leaves it null and `snapshotFor` empty; the history size
            // captured before the run is then the remaining evidence of this task's
            // own states.
            val sessionSnapshot = agent.stateHistory.snapshotFor(agent.lastRunSessionId)
            status.agentHistory = if (sessionSnapshot.isNotEmpty()) sessionSnapshot
            else taskScopedHistory(agent.stateHistory, startIndex)
        }
        status.agentHistory = history

        // The page the task ended on: its HTTP status is the honest value for the
        // record's `pageStatusCode`. Read BEFORE the user page is restored below —
        // afterwards the session is back on the user's page and the status would
        // describe a page the task never touched. Unobservable ⇒ PAGE_STATUS_UNKNOWN,
        // never the model's old 201/"created" default.
        status.pageStatusCode = DocumentHttpStatus.of(session.boundDriver) ?: PAGE_STATUS_UNKNOWN

        // Restore the user's page if the agent navigated away from it
        try {
            if (savedUrl != null && savedUrl.isNotBlank()) {
                val currentUrl = session.boundDriver?.currentUrl()
                if (currentUrl != savedUrl && currentUrl != "about:blank") {
                    logger.info(
                        "Agent task {}: restoring user page from '{}' back to '{}'",
                        status.id, currentUrl, savedUrl
                    )
                    session.boundDriver?.navigateTo(savedUrl)
                }
            }
        } catch (e: Exception) {
            logger.warn("Agent task {}: failed to restore user page: {}", status.id, e.message)
        }

        // status.agentHistory was set above and is not touched again: on success it is
        // `history`, the detached task-scoped snapshot `run()` itself returned, and on
        // failure it is the snapshot the `finally` block took. Reassigning it to the
        // agent's live cumulative `stateHistory` here would undo that scoping.
        val finalState = history.finalResult

        if (finalState == null) {
            // The agent produced no result. Distinguish "empty page" (the agent
            // navigated somewhere with no useful content) from a genuine failure
            // (the agent didn't navigate at all or crashed).
            val lastState = history.states.lastOrNull()
            val hasPageContent = lastState?.currentPageContentSummary != null
            if (!hasPageContent) {
                status.refresh(ResourceStatus.SC_EXPECTATION_FAILED)
                status.message = "Agent produced no results (no page content). " +
                    "The task may not have navigated to a valid page or the agent encountered an error."
                status.failureReason = "Agent produced no results (no page content)"
            } else {
                status.refresh(ResourceStatus.SC_OK)
                status.message = "Agent completed but produced no summary (page content loaded)"
            }
        } else {
            // AgentState has 'summary' for the final result message
            status.message = finalState.summary ?: finalState.description ?: ""
            status.refresh(ResourceStatus.SC_OK)
        }
    }

    fun getStatus(id: String) = statusCache.getIfPresent(id)

    /**
     * Returns the latest agent state for the given task ID, or null if the task
     * is not found.  The returned state may be an intermediate state if the agent
     * is still executing.
     */
    fun getLatestState(id: String) = statusCache.getIfPresent(id)?.agentState

    /**
     * Returns IDs of all statuses that are still in progress.
     */
    fun activeStatusIds(): List<String> = statusCache.asMap()
        .filterValues { !it.isDone }
        .keys.toList()

    /**
     * Return cache statistics for observability.
     * */
    fun cacheStats(): Map<String, Any> {
        val stats = statusCache.stats()
        return mapOf(
            "estimatedSize" to statusCache.estimatedSize(),
            "hitCount" to stats.hitCount(),
            "missCount" to stats.missCount(),
            "hitRate" to "%.2f".format(stats.hitRate()),
            "evictionCount" to stats.evictionCount(),
        )
    }

    override fun close() {
        cleanupScope.cancel()

        // Cancel every task job: agents must not keep running against a closed
        // runner (and possibly a closed session) after a rebuild.
        taskScope.cancel()

        // Jobs that never got to run (queued on the mutex, or cancelled before
        // start) have no coroutine inside to mark their status terminal — do it
        // here so no task dies leaving a healthy-looking `created` status.
        runningJobs.keys.forEach { id ->
            statusCache.getIfPresent(id)?.takeIf { !it.isDone }?.let { status ->
                status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
                status.cancelled = true
                status.failureReason = "Runner closed"
                status.message = "Agent task aborted: runner closed"
                persistence.append(status)
            }
        }
        runningJobs.clear()

        // Best-effort wait for in-flight coroutines to finish marking their
        // statuses; a non-cooperative coroutine cannot block shutdown forever.
        runCatching {
            runBlocking { withTimeoutOrNull(5_000) { taskSupervisor.join() } }
        }

        statusCache.invalidateAll()
        liveInstances.remove(this)
        logger.info("StatefulAgentRunner closed (session={})", session.uuid)
    }

    companion object {
        private const val TERMINAL_PROCESS_STATE = "done"
        private const val INTERRUPTED_REASON = "Interrupted by server restart"

        /**
         * Runners are per session, but the JSONL file is global — keep the list of
         * live runners so a compaction can rewrite the union of everyone's statuses
         * instead of clobbering the other runners' rows.
         */
        private val liveInstances = CopyOnWriteArrayList<StatefulAgentRunner>()

        private val sharedPersistences = ConcurrentHashMap<Path, JsonlPersistence<AgentTaskStatus>>()

        /** The ONE persistence instance for [agentPersistencePath] in this process. */
        fun sharedAgentPersistence(): JsonlPersistence<AgentTaskStatus> = sharedPersistences.computeIfAbsent(agentPersistencePath()) { path ->
            JsonlPersistence(
                file = path,
                clazz = AgentTaskStatus::class,
                objectMapper = pulsarObjectMapper()
            )
        }

        fun agentPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "agent", "agent-tasks.jsonl"
        )

        /**
         * Look up a task status straight from the disk persistence, without
         * constructing a runner (and therefore a session). Used as the last
         * fallback of status lookups after a restart: `getStatus` used to build a
         * full agent runner — a running status poll for an unknown id with an
         * arbitrary session id created session+runner objects as a side effect.
         *
         * Returns the LAST row for the id. A non-terminal row is reported as an
         * interrupted failure: the file cannot tell if the task later completed
         * in another process, but in this one nobody will ever resume it.
         *
         * The whole file is scanned at most once per file version; the rows are
         * kept in a companion-level snapshot keyed by the file's size+mtime
         * fingerprint. Without it every status poll for an unknown id re-parsed
         * the whole JSONL. The fingerprint is taken BEFORE the scan, so a row
         * appended during a scan cannot be swallowed by a fingerprint that
         * already covers it — the next poll sees a different fingerprint and
         * rescans.
         */
        fun statusFromDisk(id: String): AgentTaskStatus? {
            if (id.isBlank()) return null
            val rows = synchronized(diskScanLock) {
                val fingerprint = diskFileFingerprint()
                val cached = diskScan
                if (cached != null && cached.fingerprint == fingerprint) {
                    cached.rows
                } else {
                    val scanned = HashMap<String, AgentTaskStatus>()
                    sharedAgentPersistence().restore { entry -> scanned[entry.id] = entry }
                    // Last row per id wins (the consumer above overwrites).
                    diskScan = DiskScan(fingerprint, scanned)
                    scanned
                }
            }
            return rows[id]?.let { entry ->
                if (entry.processState == TERMINAL_PROCESS_STATE) entry
                else entry.failed(ResourceStatus.SC_EXPECTATION_FAILED).apply {
                    failureReason = INTERRUPTED_REASON
                    message = "Agent task did not complete before the server restarted"
                }
            }
        }

        private data class DiskScan(val fingerprint: String?, val rows: Map<String, AgentTaskStatus>)

        @Volatile
        private var diskScan: DiskScan? = null

        private val diskScanLock = Any()

        private fun diskFileFingerprint(): String? = runCatching {
            val file = agentPersistencePath()
            if (!Files.exists(file)) return@runCatching null
            // The path is part of the fingerprint: several persistence files can be
            // active at once (tests use one per temp dir), and two files with equal
            // size and mtime-to-the-millisecond would otherwise share one snapshot.
            "${file.toAbsolutePath()}:${Files.getLastModifiedTime(file).toMillis()}:${Files.size(file)}"
        }.getOrNull()

        private fun newerInstant(a: Instant?, b: Instant?): Boolean = when {
            a == null -> false
            b == null -> true
            else -> a.isAfter(b)
        }

        /**
         * Returns an independent [AgentHistory] containing only the states produced
         * by the current task.
         *
         * The companion agent's history is cumulative for the whole session and is
         * shared by every task, so handing it out directly leaks prior tasks' steps
         * into a fresh task's status payload. [startIndex] is the history size
         * captured before this task's `agent.run()` call.
         */
        internal fun taskScopedHistory(history: AgentHistory, startIndex: Int): AgentHistory {
            val safeStart = startIndex.coerceIn(0, history.size)
            val states = history.states
                .subList(safeStart, history.size)
                .toMutableList() // detached copy: immune to later appends/trimming
            return AgentHistory(states)
        }
    }
}
