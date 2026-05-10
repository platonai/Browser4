package ai.platon.pulsar.agentic.tools.high.agent

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.detail.DefaultServerSideAgentEventHandlers
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.*
import java.time.Duration

class StatefulAgentRunner(
    val session: AgenticSession
) {
    private val logger = getLogger(StatefulAgentRunner::class)

    // Create a dedicated dispatcher for long-running command operations
    private val commandDispatcher = Dispatchers.IO.limitedParallelism(10)
    private val commanderScope: CoroutineScope = CoroutineScope(
        commandDispatcher + SupervisorJob() + CoroutineName("commander")
    )
    private val statusCache = ConcurrentExpiringLRUCache<String, AgentTaskStatus>(Duration.ofHours(2))

    fun create(): AgentTaskStatus {
        return createCachedStatus()
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
        val status = createCachedStatus()
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

    fun getStatus(id: String) = statusCache.getDatum(id)

    fun getResult(id: String) = statusCache.getDatum(id)?.agentHistory?.lastOrNull()

    private fun createCachedStatus(): AgentTaskStatus {
        val status = AgentTaskStatus()
        statusCache.putDatum(status.id, status)
        status.emitEvent("StatefulAgentRunner.created")
        return status
    }
}
