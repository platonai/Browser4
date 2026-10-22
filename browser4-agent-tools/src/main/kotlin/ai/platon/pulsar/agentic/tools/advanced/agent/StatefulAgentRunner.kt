package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.browser4.common.B4Constants.PAGE_STATUS_UNKNOWN
import ai.platon.pulsar.agentic.AgenticSession
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
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

open class StatefulAgentRunner(
    val session: AgenticSession
) : Closeable {
    private val logger = getLogger(StatefulAgentRunner::class)

    /**
     * Size-bounded, time-expiring cache of agent task statuses.
     *
     * - Entries live at most 2 hours after their last write. An in-progress task
     *   now refreshes its entry on every progress event ([persistProgress]), so a
     *   run longer than the TTL is no longer evicted while it is still running.
     * - At most [STATUS_CACHE_SIZE] entries; Window TinyLFU eviction beyond that.
     *   The previous 100 was too small for a long-lived server: a single coding
     *   session submitted more than twenty tasks in an hour, so an older
     *   still-running task could be evicted and `agent status` answered
     *   "no such task" mid-run.
     * - Persisted to JSONL so task statuses survive restarts.
     * */
    private val statusCache: Cache<String, AgentTaskStatus> = Caffeine.newBuilder()
        .maximumSize(STATUS_CACHE_SIZE)
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

    /**
     * Scope for agent task execution jobs. Cancelling the job of a task stops
     * its agent loop (see [cancel]).
     */
    private val taskScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("agent-tasks")
    )

    /** Currently queued/running task jobs by task id (for [cancel]). */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    internal val persistence = JsonlPersistence(
        file = agentPersistencePath(),
        clazz = AgentTaskStatus::class,
        objectMapper = pulsarObjectMapper()
    )

    /** Dedicated dispatcher for cleanup operations. */
    private val cleanupDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val cleanupScope = CoroutineScope(
        cleanupDispatcher + SupervisorJob() + CoroutineName("agent-cleanup")
    )

    /** How long to keep terminal tasks before compacting them out of the JSONL file. */
    @Volatile
    var taskTtlMinutes: Int = 120

    /** Wall-clock time of the last in-progress snapshot (see [persistProgress]). */
    @Volatile
    private var lastProgressPersistedAt = 0L

    init {
        restoreFromDisk()
        // Periodically compact the JSONL file so evicted entries don't accumulate forever.
        cleanupScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // every 5 minutes
                compactPersistence()
            }
        }
    }

    fun restoreFromDisk() {
        val now = Instant.now()
        val ttlCutoff = now.minusSeconds(taskTtlMinutes * 60L)
        val terminalState = "done"

        persistence.restore { entry ->
            // TTL is measured from the row's LAST activity, not its creation: a
            // run that has been going for hours keeps writing progress rows, and
            // judging those rows by createdTime threw the newest state away.
            val lastActivity = entry.lastActivity()
            // Skip terminal entries whose TTL has expired — they were evicted
            // from the cache before shutdown and should not be revived.
            if (entry.processState == terminalState &&
                lastActivity.isBefore(ttlCutoff)
            ) {
                logger.debug("Skipping expired agent task {} during restore (lastActivity={})", entry.id, lastActivity)
                return@restore
            }
            // Skip stale non-terminal entries too: created/queued tasks are never
            // auto-resumed after a restart, so restoring them only pollutes the
            // status caches with zombie "queued" records from previous sessions.
            if (entry.processState != terminalState &&
                lastActivity.isBefore(ttlCutoff)
            ) {
                logger.debug("Skipping stale non-terminal agent task {} during restore (lastActivity={})", entry.id, lastActivity)
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
            statusCache.put(entry.id, entry)
            logger.debug("Restored agent task {}", entry.id)
        }
    }

    /**
     * Compact the JSONL persistence file.
     *
     * Two triggers: terminal entries past the TTL, and a file larger than
     * [COMPACTION_SIZE_THRESHOLD_BYTES] — periodic progress snapshots keep
     * appending rows, so staleness alone can no longer be the trigger.
     *
     * The rewrite **merges** the rows already in the file with this runner's
     * cache instead of writing only the cache: the file is shared by every session
     * (one row set under `<data>/agent/`), and rewriting just one session's view
     * dropped tasks created by other sessions after this runner started.
     */
    private fun compactPersistence() {
        val now = Instant.now()
        val ttlCutoff = now.minusSeconds(taskTtlMinutes * 60L)
        val terminalState = "done"

        val stale = statusCache.asMap().entries.filter {
            it.value.processState == terminalState && it.value.lastActivity().isBefore(ttlCutoff)
        }
        val oversized = persistence.sizeInBytes() > COMPACTION_SIZE_THRESHOLD_BYTES
        if (stale.isEmpty() && !oversized) return

        val merged = LinkedHashMap<String, AgentTaskStatus>()
        // Append order: a later row for the same id supersedes the earlier one.
        persistence.readAll().forEach { merged[it.id] = it }
        // The live cache is newer than anything on disk.
        statusCache.asMap().forEach { (id, status) -> merged[id] = status }
        // Drop what restore would drop as well: anything with no activity for TTL.
        val kept = merged.values.filter { !it.lastActivity().isBefore(ttlCutoff) }

        stale.forEach { statusCache.invalidate(it.key) }
        logger.info(
            "Compacting agent task persistence ({} stale, {} rows kept, {} bytes, TTL {} min)",
            stale.size, kept.size, persistence.sizeInBytes(), taskTtlMinutes
        )
        persistence.clear()
        kept.forEach { persistence.append(it.detachedSnapshot()) }
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
     * Persist the running task's latest status (throttled to
     * [PROGRESS_PERSIST_INTERVAL_MS]) and refresh its cache entry.
     *
     * Previously a task only wrote its status when it finished. Two consequences
     * were visible on real runs:
     * - a backend restart revived the submit-time row (`created`, no message, no
     *   startedTime) for a task that had been running for an hour;
     * - the cache entry was written once at submit, and Caffeine's
     *   `expireAfterWrite(2h)` evicted it while the task was still running, so
     *   `agent status` answered "no task found" for a live task.
     *
     * The snapshot drops the agent history (huge for the legacy engine, empty for
     * a running CLI task); the terminal write keeps it.
     */
    private fun persistProgress(status: AgentTaskStatus, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressPersistedAt < PROGRESS_PERSIST_INTERVAL_MS) return
        lastProgressPersistedAt = now
        // put() counts as a write: it resets expireAfterWrite and keeps a long
        // run's entry (and everything restored from disk) alive.
        statusCache.put(status.id, status)
        persistence.append(status.detachedSnapshot(includeHistory = false))
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
     * The job is recorded so [cancel] can stop the task: cancelling the job
     * interrupts the agent loop, releases the [runMutex], and marks the task
     * failed with reason "Task cancelled" (see [executeSerialized]).
     */
    fun submit(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        engine: RunEngine? = null,
    ): Job {
        val job = taskScope.launch { execute(plainCommand, status, noopLimit, engine) }
        runningJobs[status.id] = job
        job.invokeOnCompletion { runningJobs.remove(status.id) }
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
        job.cancel()
        return true
    }

    /**
     * Internal method to execute agent command with a pre-created status.
     *
     * The status is updated with the agent's state history reference, allowing callers
     * to access the latest agent state via [AgentTaskStatus.agentState] during execution.
     *
     * Execution is serialized per session through [runMutex] so concurrent tasks cannot
     * interleave the shared companion agent's execution contexts and history.
     *
     * This method creates and wires up ServerSideAgentEventHandlers for event collection,
     * following the pattern from StatefulPageVisitor#doVisit. A [supervisorScope] ensures
     * the event collector is structured within this call — it is launched as a child,
     * cancelled in the finally block, and the scope suspends until it terminates.
     * Multiple commands can run concurrently without cross-talk between SSE streams.
     */
    suspend fun execute(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        engine: RunEngine? = null,
    ) {
        runMutex.withLock {
            executeSerialized(plainCommand, status, noopLimit, engine)
        }
    }

    private suspend fun executeSerialized(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        engine: RunEngine? = null,
    ) {
        try {
            status.refresh(ResourceStatus.SC_PROCESSING)
            // The submit-time row says "created"; record the transition to
            // "in progress" immediately so a restart cannot revive a task that had
            // already started as if it were still queued.
            persistProgress(status, force = true)

            // Per-task noop tolerance (e.g. `agent run --noop-limit 10`): long coding chains
            // benefit from a higher limit than the default. A null value resets any stale
            // override left over from a previous task.
            (session.companionAgent as? RobustBrowserAgent)?.let {
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

            supervisorScope {
                // Start a child coroutine to collect events and update status
                val eventCollectorJob = launch {
                    try {
                        serverSideAgentEventHandlers.eventFlow.collect { event ->
                            status.emitEvent(event.eventType)
                            // The CLI engine reports its executed-tool count with the
                            // throttled progress events (every 5 calls): surface it as
                            // live progress and persist the snapshot, so a long run is
                            // observable and survivable.
                            (event.metadata["toolExecutions"] as? Number)?.let {
                                status.toolExecutions = it.toInt()
                            }
                            persistProgress(status)
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
                        executeAgentCommand(plainCommand, status)
                    }
                } finally {
                    // Cancel event collector when command completes
                    eventCollectorJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            logger.warn("Agent task {} cancelled: {}", status.id, e.message)
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            // The CancellationException message is the coroutine's own name
            // ("StandaloneCoroutine was cancelled") — internal noise that told
            // the CLI user nothing about why the task ended. Report the fact.
            status.failureReason = CANCELLED_MESSAGE
            status.message = CANCELLED_MESSAGE
            throw e
        } catch (e: Throwable) {
            logger.error("Failed to execute agent command: {} (session={})", plainCommand, session.uuid, e)
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            status.failureReason = "${e.javaClass.simpleName}: ${e.message}"
            status.message = e.message
        } finally {
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
    private suspend fun executeAgentCommand(plainCommand: String, status: AgentTaskStatus) {
        val agent = session.companionAgent

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

        // The companion agent keeps ONE cumulative stateHistory across every task
        // executed in the session. Remember where this task's states begin so the
        // published history never leaks states from earlier tasks (a fresh task's
        // payload must not contain complete histories of previous tasks).
        val startIndex = agent.stateHistory.size

        // Set agent history reference to allow real-time state tracking. This points
        // at the live cumulative history while the task is running; it is replaced
        // by a task-scoped snapshot as soon as `agent.run()` returns (see below).
        status.agentHistory = agent.stateHistory

        // Save the user's current page URL so we can restore it after the agent
        // runs, preventing the agent from polluting the shared browser session.
        val savedUrl = runCatching { session.boundDriver?.currentUrl() }.getOrNull()
        logger.debug("Agent task {}: saved user page URL before agent run: {}", status.id, savedUrl)

        val history = try {
            agent.run(plainCommand)
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
                status.message = finalState?.summary ?: finalState?.description
                    ?: "Agent completed but produced no summary (page content loaded)"
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
        statusCache.invalidateAll()
        logger.info("StatefulAgentRunner closed (session={})", session.uuid)
    }

    companion object {
        /** User-facing reason recorded when a task is cancelled (see [cancel]). */
        const val CANCELLED_MESSAGE = "Task cancelled"

        /** Max cached task statuses per runner (see the status cache's KDoc). */
        const val STATUS_CACHE_SIZE = 1_000L

        /** Minimum spacing between in-progress status snapshots (see [persistProgress]). */
        const val PROGRESS_PERSIST_INTERVAL_MS = 10_000L

        /**
         * Persistence file size above which the 5-minute maintenance pass rewrites
         * it even when no task has expired. Periodic progress snapshots grow the
         * file without producing stale entries, so staleness alone can no longer
         * be the trigger.
         */
        const val COMPACTION_SIZE_THRESHOLD_BYTES = 4L * 1024 * 1024

        fun agentPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "agent", "agent-tasks.jsonl"
        )

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
