package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.detail.DefaultServerSideAgentEventHandlers
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.getLogger
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.*
import java.io.Closeable
import java.util.concurrent.TimeUnit

class StatefulAgentRunner(
    val session: AgenticSession
) : Closeable {
    private val logger = getLogger(StatefulAgentRunner::class)

    // Create a dedicated dispatcher for long-running command operations
    private val commandDispatcher = Dispatchers.IO.limitedParallelism(10)
    private val commanderScope: CoroutineScope = CoroutineScope(
        commandDispatcher + SupervisorJob() + CoroutineName("commander")
    )

    /**
     * Size-bounded, time-expiring cache of agent task statuses.
     *
     * - Entries live at most 2 hours after last write.
     * - At most 10 000 entries; Window TinyLFU eviction beyond that.
     * */
    private val statusCache: Cache<String, AgentTaskStatus> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(2, TimeUnit.HOURS)
        .recordStats()
        .build()

    fun create(): AgentTaskStatus {
        val status = AgentTaskStatus()
        statusCache.put(status.id, status)
        status.emitEvent("StatefulAgentRunner.created")
        logger.debug("Created agent task status {}", status.id)
        return status
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
     * This method creates and wires up ServerSideAgentEventHandlers for event collection,
     * following the pattern from StatefulPageVisitor#doVisit. Multiple commands can run
     * concurrently without cross-talk between SSE streams.
     */
    suspend fun execute(plainCommand: String, status: AgentTaskStatus) {
        try {
            status.refresh(ResourceStatus.SC_PROCESSING)

            // Create and wire up ServerSideAgentEventHandlers for this command
            val serverSideAgentEventHandlers = DefaultServerSideAgentEventHandlers()
            status.serverSideAgentEventHandlers = serverSideAgentEventHandlers

            // Start a background job to collect events and update status
            val eventCollectorJob = commanderScope.launch {
                try {
                    serverSideAgentEventHandlers.eventFlow.collect { event ->
                        status.emitEvent(event.eventType)
                        logger.info("Collected event {} for agent task {}", event.eventType, status.id)
                    }
                } catch (e: CancellationException) {
                    logger.debug("Event collector cancelled for agent task {}", status.id)
                    throw e
                } catch (e: Exception) {
                    logger.error("Error collecting events for agent task ${status.id}", e)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to execute agent command: {}", plainCommand, e)
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            status.message = e.message
        } finally {
            status.done()
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

        // Set agent history reference to allow real-time state tracking
        status.agentHistory = agent.stateHistory

        val history = agent.run(plainCommand)

        status.agentHistory = history
        val finalState = history.finalResult

        // AgentState has 'summary' for the final result message
        status.message = finalState?.summary ?: finalState?.description ?: ""
        status.refresh(ResourceStatus.SC_OK)
    }

    fun getStatus(id: String) = statusCache.getIfPresent(id)

    fun getResult(id: String) = statusCache.getIfPresent(id)?.agentHistory?.lastOrNull()

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
        commanderScope.cancel()
        statusCache.invalidateAll()
        logger.info("StatefulAgentRunner closed (session={})", session.uuid)
    }
}
