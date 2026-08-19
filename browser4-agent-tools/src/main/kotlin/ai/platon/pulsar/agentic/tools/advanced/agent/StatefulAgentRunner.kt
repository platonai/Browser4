package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.AgenticSession
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
import java.util.concurrent.TimeUnit

open class StatefulAgentRunner(
    val session: AgenticSession
) : Closeable {
    private val logger = getLogger(StatefulAgentRunner::class)

    /**
     * Size-bounded, time-expiring cache of agent task statuses.
     *
     * - Entries live at most 2 hours after last write.
     * - At most 10 000 entries; Window TinyLFU eviction beyond that.
     * - Persisted to JSONL so task statuses survive restarts.
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
            // Skip terminal entries whose TTL has expired — they were evicted
            // from the cache before shutdown and should not be revived.
            if (entry.processState == terminalState &&
                entry.createdTime.isBefore(ttlCutoff)
            ) {
                logger.debug("Skipping expired agent task {} during restore (created={})", entry.id, entry.createdTime)
                return@restore
            }
            statusCache.put(entry.id, entry)
            logger.debug("Restored agent task {}", entry.id)
        }
    }

    /** Compact the JSONL persistence file: remove stale entries that are past TTL. */
    private fun compactPersistence() {
        val now = Instant.now()
        val ttlCutoff = now.minusSeconds(taskTtlMinutes * 60L)
        val terminalState = "done"

        val stale = statusCache.asMap().entries.filter {
            it.value.processState == terminalState && it.value.createdTime.isBefore(ttlCutoff)
        }
        if (stale.isEmpty()) return

        stale.forEach { statusCache.invalidate(it.key) }
        logger.info("Compacted {} expired agent tasks (TTL: {} min)", stale.size, taskTtlMinutes)

        // Rewrite the persistence file so compacted tasks don't revive on restart.
        // Serialize detached snapshots: an in-progress status may hold the agent's live
        // history reference, which can grow/trim concurrently with this rewrite.
        persistence.clear()
        statusCache.asMap().values.forEach { status ->
            val snapshot = status.copy().apply {
                agentHistory = status.agentHistory?.let { AgentHistory(it.states.toMutableList()) }
            }
            persistence.append(snapshot)
        }
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
    suspend fun execute(plainCommand: String): AgentTaskStatus {
        val status = create()
        execute(plainCommand, status)
        return status
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
    suspend fun execute(plainCommand: String, status: AgentTaskStatus) {
        runMutex.withLock {
            executeSerialized(plainCommand, status)
        }
    }

    private suspend fun executeSerialized(plainCommand: String, status: AgentTaskStatus) {
        try {
            status.refresh(ResourceStatus.SC_PROCESSING)

            // Create and wire up ServerSideAgentEventHandlers for this command
            val serverSideAgentEventHandlers = DefaultServerSideAgentEventHandlers()
            status.serverSideAgentEventHandlers = serverSideAgentEventHandlers

            supervisorScope {
                // Start a child coroutine to collect events and update status
                val eventCollectorJob = launch {
                    try {
                        serverSideAgentEventHandlers.eventFlow.collect { event ->
                            status.emitEvent(event.eventType)
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
            status.failureReason = e.message ?: "Task cancelled"
            status.message = e.message
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

        // A session reuses its bound driver across tasks. If a previous task (or a
        // close-all) shut the browser down, the driver points at a dead browser and
        // the agent would fail every step (DOM settle timeout, browserUseState
        // degradation, zero-step runs). Reset the bound driver/browser so a fresh
        // browser is created for this task.
        ensureSessionDriverHealthy()

        // Set agent history reference to allow real-time state tracking
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
            status.agentHistory = agent.stateHistory.snapshotFor(agent.lastRunSessionId)
        }
        status.agentHistory = history

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

        status.agentHistory = history
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
        fun agentPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "agent", "agent-tasks.jsonl"
        )
    }
}
