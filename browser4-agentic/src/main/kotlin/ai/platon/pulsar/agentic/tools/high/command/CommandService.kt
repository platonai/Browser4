package ai.platon.pulsar.agentic.tools.high.command

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.high.agent.StatefulAgentRunner
import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest
import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitStatus
import ai.platon.pulsar.agentic.tools.high.crawl.StatefulPageVisitor
import ai.platon.pulsar.agentic.tools.high.crawl.failed
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import ai.platon.pulsar.skeleton.crawl.event.impl.PageEventHandlersFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.time.Instant

/**
 * General-purpose command execution service for page visit and agent commands.
 *
 * This service orchestrates command execution through [StatefulPageVisitor] for page visits
 * and [StatefulAgentRunner] for agent-based commands. It can be used by both REST API
 * and agentic modules.
 *
 * @param session The agentic session for page loading and interaction.
 * @param commandNormalizer Optional normalizer that converts plain text commands into
 *        structured [PageVisitRequest] objects. If not provided, plain text commands
 *        without URLs will be executed as agent commands.
 */
class CommandService(
    val session: AgenticSession,
    private val commandNormalizer: CommandNormalizer? = null,
) : Closeable {
    companion object {
        const val FLOW_POLLING_INTERVAL = 1000L
    }

    private val logger = getLogger(CommandService::class)

    // Create a dedicated dispatcher for long-running command operations
    private val commandDispatcher = Dispatchers.IO.limitedParallelism(10)

    private val commanderScope: CoroutineScope = CoroutineScope(
        commandDispatcher + SupervisorJob() + CoroutineName("commander")
    )

    private val statefulPageVisitor = StatefulPageVisitor(session)
    private val statefulAgentRunner = StatefulAgentRunner(session)

    suspend fun executePageVisitCommandSync(
        request: PageVisitRequest, eventHandlers: PageEventHandlers
    ): CommandStatus {
        return statefulPageVisitor.visit(request, eventHandlers).toCommandStatus()
    }

    fun submitPageVisitCommandAsync(request: PageVisitRequest, eventHandlers: PageEventHandlers): String {
        val status = statefulPageVisitor.create()
        commanderScope.launch { statefulPageVisitor.visit(request, status, eventHandlers) }
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
    suspend fun executePlainCommandSync(plainCommand: String): CommandStatus {
        if (plainCommand.isBlank()) {
            return CommandStatus.failed(ResourceStatus.SC_BAD_REQUEST)
        }

        val request = commandNormalizer?.normalize(plainCommand)
        return if (request != null) {
            // Page visit execution
            val status = statefulPageVisitor.create()
            val eventHandlers = PageEventHandlersFactory.create()
            statefulPageVisitor.visit(request, status, eventHandlers)
            status.toCommandStatus()
        } else {
            // Open task execution
            val agentStatus = statefulAgentRunner.execute(plainCommand)
            agentStatus.toCommandStatus()
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
    suspend fun submitPlainCommandAsync(plainCommand: String): String {
        val command = plainCommand.trim()

        // 1. Bad command
        if (command.isBlank()) {
            val status = statefulPageVisitor.create()
            status.failed(ResourceStatus.SC_BAD_REQUEST)
            return status.id
        }

        // 2. Only one single url with optional parameters
        // it is better to use ScrapeController directly for this kind of commands which supports massive scraping
        if (Strings.isSingleLine(command)) {
            val url = session.normalize(command)
            if (url.isNotNil) {
                val eventHandlers = PageEventHandlersFactory.create()
                val request = PageVisitRequest(url = url.urlSpec, args = url.args)
                submitPageVisitCommandAsync(request, eventHandlers)
            }
        }

        val request = commandNormalizer?.normalize(command)
        return if (request != null) {
            // 3. Structured page visit command
            val eventHandlers = PageEventHandlersFactory.create()
            submitPageVisitCommandAsync(request, eventHandlers)
        } else {
            // 4. Free command
            submitAgentTaskAsync(command)
        }
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
    suspend fun executeAgentCommand(plainCommand: String): CommandStatus {
        val status = statefulAgentRunner.execute(plainCommand)
        return status.toCommandStatus()
    }

    fun submitAgentTaskAsync(plainCommand: String): String {
        val status = statefulAgentRunner.create()
        commanderScope.launch { statefulAgentRunner.execute(plainCommand, status) }
        return status.id
    }

    fun getStatus(id: String): CommandStatus? {
        return statefulPageVisitor.getStatus(id)?.toCommandStatus()
            ?: statefulAgentRunner.getStatus(id)?.toCommandStatus()
    }

    fun getResult(id: String): CommandResult? = getStatus(id)?.commandResult

    fun commandStatusFlow(id: String): Flow<CommandStatus> = flow {
        var lastModifiedTime = Instant.EPOCH
        do {
            delay(FLOW_POLLING_INTERVAL)

            val status = getStatus(id) ?: CommandStatus.notFound(id)
            if (status.refreshed(lastModifiedTime)) {
                emit(status)
                lastModifiedTime = status.lastModifiedTime
            }

            if (status.isDone) {
                // emit a final event, it's OK to emit a duplicate event
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
    suspend fun executePageVisitCommand(request: String): PageVisitStatus {
        if (request.isBlank()) {
            return PageVisitStatus.failed(ResourceStatus.SC_BAD_REQUEST)
        }

        val request2 = commandNormalizer?.normalize(request) ?: return PageVisitStatus.failed(
            ResourceStatus.SC_EXPECTATION_FAILED
        )

        val eventHandlers = PageEventHandlersFactory.create()
        return statefulPageVisitor.visit(request2, eventHandlers)
    }

    suspend fun executePageVisitCommand(request: PageVisitRequest): PageVisitStatus {
        return statefulPageVisitor.visit(request)
    }

    /**
     * Returns the command service's coroutine scope.
     *
     * This is useful for external callers that need to launch coroutines
     * tied to the command service's lifecycle.
     */
    fun launchScope(): CoroutineScope = commanderScope

    override fun close() {
        commanderScope.cancel()
    }
}
