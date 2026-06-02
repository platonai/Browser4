package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitStatus
import ai.platon.pulsar.agentic.tools.advanced.crawl.StatefulPageVisitor
import ai.platon.pulsar.agentic.tools.advanced.crawl.failed
import ai.platon.pulsar.common.PulsarSessionManager
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.rest.api.entities.CommandResult
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.rest.api.entities.refreshed
import ai.platon.pulsar.rest.api.entities.toCommandStatus
import ai.platon.pulsar.rest.config.CommandNormalizer
import ai.platon.pulsar.skeleton.event.PageEventHandlers
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * General-purpose command execution service for page visit and agent commands.
 *
 * This service orchestrates command execution through [StatefulPageVisitor] for page visits
 * and [StatefulAgentRunner] for agent-based commands.  It tracks task ownership so that
 * status lookups hit the correct executor without creating unnecessary objects.
 *
 * @param commandNormalizer Optional normalizer that converts plain text commands into
 *        structured [PageVisitRequest] objects. If not provided, plain text commands
 *        without URLs will be executed as agent commands.
 */
class UserCommandExecutor(
    val sessionManager: PulsarSessionManager,
    private val commandNormalizer: CommandNormalizer? = null,
) : Closeable {
    companion object {
        const val FLOW_POLLING_INTERVAL = 1000L
    }

    private val logger = getLogger(UserCommandExecutor::class)

    // Create a dedicated dispatcher for long-running command operations
    private val commandDispatcher = Dispatchers.IO.limitedParallelism(10)

    private val commanderScope: CoroutineScope = CoroutineScope(
        commandDispatcher + SupervisorJob() + CoroutineName("commander")
    )

    private val pageVisitors = ConcurrentHashMap<String, StatefulPageVisitor>()
    private val agentRunners = ConcurrentHashMap<String, StatefulAgentRunner>()

    /**
     * Tracks which executor owns each task ID so that [getStatus] can route
     * directly to the right executor without probing both maps.
     *
     * Values: "page" or "agent".
     * */
    private val taskOwner = ConcurrentHashMap<String, String>()

    fun ensurePageVisitor(sessionId: String): StatefulPageVisitor =
        pageVisitors.getOrPut(sessionId) {
            StatefulPageVisitor(sessionManager.getOrCreateSession(sessionId).agenticSession)
        }

    fun ensureAgentRunner(sessionId: String): StatefulAgentRunner =
        agentRunners.getOrPut(sessionId) {
            StatefulAgentRunner(sessionManager.getOrCreateSession(sessionId).agenticSession)
        }

    suspend fun executePageVisitCommand(
        sessionId: String,
        request: PageVisitRequest, eventHandlers: PageEventHandlers
    ): CommandStatus {
        return ensurePageVisitor(sessionId).visit(request, eventHandlers).toCommandStatus()
    }

    fun submitPageVisitCommand(
        sessionId: String,
        request: PageVisitRequest, eventHandlers: PageEventHandlers
    ): String {
        val status = ensurePageVisitor(sessionId).create()
        taskOwner[status.id] = "page"
        logger.info("Submitting page visit task {} (session={}, url={})", status.id, sessionId, request.url)
        commanderScope.launch { ensurePageVisitor(sessionId).visit(request, status, eventHandlers) }
        return status.id
    }

    /**
     * Execute a plain command synchronously.
     *
     * If a [CommandNormalizer] is configured and returns a valid PageVisitRequest,
     * it executes the command using the standard command execution flow.
     * Otherwise, it executes the command using the agent's run method.
     *
     * @param plainCommand The plain text command to execute.
     * @return CommandStatus containing the execution result.
     */
    suspend fun executePlainCommand(
        sessionId: String,
        plainCommand: String
    ): CommandStatus {
        if (plainCommand.isBlank()) {
            return CommandStatus.failed(ResourceStatus.SC_BAD_REQUEST)
        }

        val request = commandNormalizer?.normalize(plainCommand)
        return if (request != null) {
            val eventHandlers = PageEventHandlersFactory.create()
            ensurePageVisitor(sessionId).visit(request, eventHandlers).toCommandStatus()
        } else {
            ensureAgentRunner(sessionId).execute(plainCommand).toCommandStatus()
        }
    }

    /**
     * Submit a plain command for asynchronous execution.
     *
     * If a [CommandNormalizer] is configured and returns a valid PageVisitRequest,
     * it submits the command using the standard async command execution flow.
     * Otherwise, it submits the command for agent-based execution.
     *
     * @param plainCommand The plain text command to execute.
     * @return The command status ID for tracking execution progress.
     */
    suspend fun submitPlainCommand(
        sessionId: String,
        plainCommand: String
    ): String {
        val command = plainCommand.trim()

        // 1. Bad command
        if (command.isBlank()) {
            val status = ensurePageVisitor(sessionId).create()
            status.failed(ResourceStatus.SC_BAD_REQUEST)
            taskOwner[status.id] = "page"
            return status.id
        }

        // 2. Single URL with optional parameters — use page visit directly
        // For bulk scraping, use the ScrapeController instead.
        if (isConfiguredUrl(command)) {
            val session = sessionManager.getOrCreateSession(sessionId)
            val url = session.agenticSession.normalize(command)
            if (url.isNotNil) {
                val eventHandlers = PageEventHandlersFactory.create()
                val request = PageVisitRequest(url = url.urlSpec, args = url.args)
                return submitPageVisitCommand(sessionId, request, eventHandlers)
            }
        }

        val request = commandNormalizer?.normalize(command)
        return if (request != null) {
            // 3. Structured page visit command
            val eventHandlers = PageEventHandlersFactory.create()
            submitPageVisitCommand(sessionId, request, eventHandlers)
        } else {
            // 4. Free-form agent command
            submitAgentTask(sessionId, command)
        }
    }

    private fun isConfiguredUrl(s: String): Boolean {
        return Strings.isSingleLine(s) && URLUtils.normalizeOrNull(s) != null
    }

    /**
     * Execute a plain command using the agent's run method.
     *
     * This delegates to [StatefulAgentRunner] so the execution logic (history tracking,
     * error handling, status transitions) is shared across modules.
     *
     * @param plainCommand The plain text command for the agent to execute.
     * @return CommandStatus containing the execution result.
     */
    suspend fun executeAgentTask(
        sessionId: String,
        plainCommand: String
    ): CommandStatus {
        val status = ensureAgentRunner(sessionId).execute(plainCommand)
        return status.toCommandStatus()
    }

    fun submitAgentTask(
        sessionId: String,
        plainCommand: String
    ): String {
        val status = ensureAgentRunner(sessionId).create()
        taskOwner[status.id] = "agent"
        logger.info("Submitting agent task {} (session={})", status.id, sessionId)
        commanderScope.launch { ensureAgentRunner(sessionId).execute(plainCommand, status) }
        return status.id
    }

    /**
     * Look up a command status by task ID.
     *
     * Uses [taskOwner] to route directly to the right executor.  Falls back to
     * probing both page visitor and agent runner if the task isn't tracked
     * (e.g. after a server restart).
     *
     * @return [CommandStatus] or null if the task ID is unknown.
     */
    fun getStatus(sessionId: String, id: String): CommandStatus? {
        return when (taskOwner[id]) {
            "page" -> ensurePageVisitor(sessionId).getStatus(id)?.toCommandStatus()
                ?: fallbackAgentStatus(sessionId, id)
            "agent" -> ensureAgentRunner(sessionId).getStatus(id)?.toCommandStatus()
                ?: fallbackPageStatus(sessionId, id)
            else -> fallbackPageStatus(sessionId, id)
                ?: fallbackAgentStatus(sessionId, id)
        }
    }

    private fun fallbackPageStatus(sessionId: String, id: String): CommandStatus? {
        if (!pageVisitors.containsKey(sessionId)) return null
        return pageVisitors[sessionId]?.getStatus(id)?.toCommandStatus()
    }

    private fun fallbackAgentStatus(sessionId: String, id: String): CommandStatus? {
        if (!agentRunners.containsKey(sessionId)) return null
        return agentRunners[sessionId]?.getStatus(id)?.toCommandStatus()
    }

    fun getResult(sessionId: String, id: String): CommandResult? = getStatus(sessionId, id)?.commandResult

    fun commandStatusFlow(sessionId: String, id: String): Flow<CommandStatus> = flow {
        var lastModifiedTime = Instant.EPOCH
        do {
            delay(FLOW_POLLING_INTERVAL.milliseconds)

            val status = getStatus(sessionId, id)
                ?: CommandStatus.notFound(id).also { lastModifiedTime = Instant.EPOCH }

            if (status.refreshed(lastModifiedTime)) {
                emit(status)
                lastModifiedTime = status.lastModifiedTime ?: Instant.EPOCH
            }

            if (status.isDone) {
                emit(status)
            }
        } while (!status.isDone)
    }

    /**
     * Executes a command based on the provided request string.
     *
     * This method first attempts to convert the request string into a PageVisitRequest object
     * using the configured [CommandNormalizer].
     * If successful, it calls the command method with the PageVisitRequest object.
     * If not, it returns a failed status with a status code indicating a bad request.
     *
     * @param request The request string containing a URL and other parameters.
     * @return A PageVisitStatus object containing the result of the command execution.
     * */
    suspend fun executePageVisitCommand(sessionId: String, request: String): PageVisitStatus {
        if (request.isBlank()) {
            return PageVisitStatus.failed(ResourceStatus.SC_BAD_REQUEST)
        }

        val request2 = commandNormalizer?.normalize(request) ?: return PageVisitStatus.failed(
            ResourceStatus.SC_EXPECTATION_FAILED
        )

        val eventHandlers = PageEventHandlersFactory.create()
        return ensurePageVisitor(sessionId).visit(request2, eventHandlers)
    }

    suspend fun executePageVisitCommand(sessionId: String, request: PageVisitRequest): PageVisitStatus {
        return ensurePageVisitor(sessionId).visit(request)
    }

    /**
     * Returns the command service's coroutine scope.
     *
     * This is useful for external callers that need to launch coroutines
     * tied to the command service's lifecycle.
     */
    fun launchScope(): CoroutineScope = commanderScope

    override fun close() {
        val pageCount = pageVisitors.size
        val agentCount = agentRunners.size
        val taskCount = taskOwner.size
        commanderScope.cancel()
        pageVisitors.values.forEach { runCatching { it.close() } }
        agentRunners.values.forEach { runCatching { it.close() } }
        pageVisitors.clear()
        agentRunners.clear()
        taskOwner.clear()
        logger.info("UserCommandExecutor closed ({} page visitors, {} agent runners, {} tracked tasks)",
            pageCount, agentCount, taskCount)
    }
}
