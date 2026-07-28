package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitStatus
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.skeleton.event.ServerSideEventHandlers
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.*

/**
 * Command status
 *
 * @property id The unique identifier for the command status.
 * @property statusCode The HTTP status code representing the command status.
 * @property event The last event associated with the command status.
 * @property isDone Indicates whether the command has been completed.
 * @property pageStatusCode The HTTP status code representing the page status.
 * @property pageContentBytes The size of the page content in bytes.
 * @property message An optional message providing additional information about the command status.
 * @property request The original command request associated with this status.
 * @property commandResult The result of the command execution.
 * @property instructResults A list of results from the instructions executed during the command.
 * */
data class CommandStatus(
    /**
     * The unique identifier for the page visit status.
     * */
    val id: String = UUID.randomUUID().toString(),
    /**
     * The status code representing the task status.
     * */
    var statusCode: Int = ResourceStatus.SC_CREATED,
    /**
     * The last event associated with the task.
     * */
    var event: String = "",
    /**
     * The progress state of the agent task. Can be "created", "in_progress", or "done".
     * */
    var processState: String = "created", // created, in_progress, done

    var pageStatusCode: Int = ProtocolStatusCodes.SC_CREATED,
    var pageContentBytes: Int = 0,

    var message: String? = null, // human-readable status description

    /** Internal event trace for debugging — concatenated lifecycle event names. */
    var eventTrace: String? = null,

    var request: CommandRequest? = null,
    var commandResult: CommandResult? = null,
    var instructResults: MutableList<InstructResult> = mutableListOf(),
) {
    val status: String get() = ResourceStatus.getStatusText(statusCode)
    var lastModifiedTime: Instant? = null
    var finishTime: Instant? = null

    /**
     * Indicates whether the task has completed.
     * Serialized as "isDone" in JSON — the Jackson Kotlin module preserves the Kotlin
     * property name directly, unlike the Java Bean convention which would strip "is".
     */
    /** True when the task has reached a terminal state. Accepts the legacy "done" and the
     * user-facing "completed" (mapped by [AgentTaskStatus.toCommandStatus]). */
    val isDone: Boolean get() = processState == "done" || processState == "completed"

    @get:JsonIgnore
    private var agentStateSnapshot: CommandAgentState? = null

    /**
     * Returns the latest agent state from the agent history.
     * When no action state was recorded, a fallback snapshot can still be serialized and deserialized for API clients.
     */
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    var agentState: CommandAgentState?
        get() = agentHistory?.lastOrNull() ?: agentStateSnapshot
        set(value) {
            agentStateSnapshot = value
        }

    /**
     * The agent's state history reference for tracking agent execution progress.
     * This is set when executing agent commands and returned to API clients for agent-backed commands.
     */
    var agentHistory: CommandAgentHistory? = null

    /**
     * The server-side event handlers reference for tracking server-side events during command execution.
     * This is set when executing commands and provides access to the event flow.
     * It is excluded from JSON serialization as it's only used for internal event streaming.
     */
    @get:JsonIgnore
    var serverSideEventHandlers: ServerSideEventHandlers? = null

    companion object {
        fun notFound(id: String) = CommandStatus(id, ResourceStatus.SC_NOT_FOUND, processState = "done")

        fun failed(id: String) = CommandStatus(id, ResourceStatus.SC_EXPECTATION_FAILED, processState = "done")

        fun failed(id: String, statusCode: Int, pageStatusCode: Int = statusCode) =
            CommandStatus(id, statusCode = statusCode, pageStatusCode = pageStatusCode, processState = "done")

        fun failed(statusCode: Int, pageStatusCode: Int = statusCode) = failed("", statusCode, pageStatusCode)

        fun failed(id: String, e: Exception): CommandStatus {
            return CommandStatus(id, statusCode = ResourceStatus.SC_EXPECTATION_FAILED, processState = "done")
        }
    }
}

fun CommandStatus.ensureCommandResult(): CommandResult {
    val r = commandResult ?: CommandResult()
    commandResult = r
    return r
}

fun CommandStatus.refresh(isDone: Boolean = false) {
    lastModifiedTime = Instant.now()
    processState = "done".takeIf { isDone } ?: "in_progress"
}

fun CommandStatus.refresh(statusCode: Int) = refresh(statusCode, this.pageStatusCode, false)

fun CommandStatus.refresh(statusCode: Int, pageStatusCode: Int, isDone: Boolean) {
    lastModifiedTime = Instant.now()
    this.statusCode = statusCode
    this.pageStatusCode = pageStatusCode
    processState = "done".takeIf { isDone } ?: "in_progress"
}

fun CommandStatus.failed(statusCode: Int): CommandStatus {
    // do not change pageStatusCode
    refresh(statusCode, pageStatusCode, isDone = true)
    return this
}

fun CommandStatus.emitEvent(event: String) {
    this.event = event
    // Keep the internal event trace for debugging separate from the user-facing message.
    eventTrace = if (eventTrace != null) "$eventTrace,$event" else event
    // Generate a concise human-readable status description.
    message = when {
        "created" in event -> "Task created, waiting to start"
        "onWillRun" in event -> "Agent starting up"
        "onWillAct" in event -> "Agent planning next action"
        "onDidAct" in event -> "Agent completed an action"
        "onWillGenerate" in event -> "Agent generating response"
        "onDidGenerate" in event -> "Agent response generated"
        "onError" in event -> "Agent encountered an error"
        "onDidRun" in event -> "Agent run completed, finalizing"
        "onWillFail" in event -> "Agent task failing"
        "onDidFail" in event -> "Agent task failed"
        else -> "Task is in progress"
    }
}

fun CommandStatus.failed(statusCode: Int, pageStatusCode: Int): CommandStatus {
    refresh(statusCode, pageStatusCode, isDone = true)
    return this
}

fun CommandStatus.addInstructResult(result: InstructResult) {
    instructResults.add(result)

    val name = result.name
    val commandResult = ensureCommandResult()
    when (name) {
        "pageSummary" -> {
            commandResult.pageSummary = result.result?.toString()
        }

        "fields" -> {
            @Suppress("UNCHECKED_CAST")
            commandResult.fields = result.result as? Map<String, String>?
        }

        "links" -> {
            @Suppress("UNCHECKED_CAST")
            commandResult.links = result.result as? List<String>?
        }
    }
    emitEvent(result.name)
}

fun CommandStatus.done() {
    refresh(isDone = true)
    finishTime = Instant.now()
}

fun CommandStatus.refreshed(lastModifiedTime: Instant): Boolean {
    val modifiedTime = this.lastModifiedTime ?: return false
    return modifiedTime > lastModifiedTime
}

fun AgentTaskStatus.toCommandStatus(): CommandStatus {
    val status = CommandStatus(this.id)
    // Transfer all status fields
    status.statusCode = this.statusCode
    status.event = this.event
    // Map internal "done" processState to user-facing "completed" in the JSON output.
    status.processState = if (this.processState == "done") "completed" else this.processState
    status.message = this.message
    status.eventTrace = this.eventTrace
    status.lastModifiedTime = this.lastModifiedTime
    status.finishTime = this.finishTime

    // Transfer agent-specific data
    status.agentHistory = this.agentHistory?.toCommandAgentHistory()
    status.agentState = status.agentHistory?.lastOrNull()

    // Populate commandResult from available sources so that callers
    // (including `command_result`) always have at least a status message,
    // even when the agent produced no summary.
    //
    // When the task failed, prefer failureReason over message because
    // message may only contain unhelpful event-flow concatenation
    // (e.g. "StatefulAgentRunner.created,PerceptiveAgent.onWillRun,...").
    val failed = this.isDone && this.statusCode != ResourceStatus.SC_OK
    val summary = this.agentHistory?.lastOrNull()?.summary
        ?: if (failed) this.failureReason else null
        ?: this.message
        ?: this.failureReason
    if (!summary.isNullOrBlank()) {
        status.ensureCommandResult().summary = summary
    } else if (failed) {
        status.ensureCommandResult().summary =
            "Task finished with status: ${ResourceStatus.getStatusText(this.statusCode)}"
    }

    if (status.agentState == null) {
        status.agentState = createFallbackAgentState(status)
    }
    if (status.agentHistory == null && status.agentState != null) {
        status.agentHistory = CommandAgentHistory(mutableListOf(requireNotNull(status.agentState)))
    }

    return status
}

fun PageVisitStatus.toCommandStatus(): CommandStatus {
    val status = CommandStatus(
        this.id,
        statusCode = this.statusCode,
        event = this.event,
        processState = this.processState,
        pageStatusCode = this.pageStatusCode,
        pageContentBytes = this.pageContentBytes,
        message = this.message,
        request = this.request
    )

    // Transfer all basic status fields
    status.statusCode = this.statusCode
    status.event = this.event
    // Map internal "done" processState to user-facing "completed" in the JSON output.
    status.processState = if (this.processState == "done") "completed" else this.processState
    status.pageStatusCode = this.pageStatusCode
    status.pageContentBytes = this.pageContentBytes
    status.message = this.message
    status.lastModifiedTime = this.lastModifiedTime
    status.finishTime = this.finishTime

    // Transfer request if present
    status.request = this.request

    // instruct results -> command instruct results
    val commandResults = instructResults.map { it.toInstructResult() }
    instructResults.forEachIndexed { index, _ ->
        val commandResult = commandResults.getOrNull(index)
        if (commandResult != null) {
            status.addInstructResult(commandResult)
        }
    }

    // best-effort summary mapping
    val visitResult = pageVisitResult
    if (visitResult != null) {
        val commandResult = status.ensureCommandResult()
        commandResult.pageSummary = visitResult.pageSummary
        commandResult.fields = visitResult.fields
        commandResult.xsqlResultSet = visitResult.xsqlResultSet
    }

    return status
}

internal fun createFallbackAgentState(status: CommandStatus): CommandAgentState? {
    val summary = status.commandResult?.summary ?: status.message ?: return null
    return CommandAgentState(
        step = 1,
        instruction = summary,
        description = status.message,
        event = status.event.takeIf { it.isNotBlank() },
        isComplete = status.isDone,
        summary = summary,
    )
}
