package ai.platon.pulsar.agentic.tools.high.command

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.tools.high.agent.AgentTaskStatus
import ai.platon.pulsar.agentic.tools.high.crawl.PGInstructResult
import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest
import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitStatus
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.skeleton.crawl.ServerSideEventHandlers
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.*

typealias CommandRequest = PageVisitRequest

/**
 * Command result
 *
 * @property pageSummary The summary of the page.
 * @property fields The extracted fields from the page.
 * @property links The extracted links from the page.
 * @property xsqlResultSet The result set from the X-SQL query.
 */
data class CommandResult(
    var summary: String? = null,
    var pageSummary: String? = null,
    var fields: Map<String, String>? = null,
    var links: List<String>? = null,
    var xsqlResultSet: List<Map<String, Any?>>? = null,
)

data class CommandAgentState(
    var step: Int = 0,
    var instruction: String? = null,
    var description: String? = null,
    var event: String? = null,
    var summary: String? = null,
    var isComplete: Boolean? = null,
)

data class CommandAgentHistory(
    var states: MutableList<CommandAgentState> = mutableListOf(),
) {
    fun lastOrNull(): CommandAgentState? = states.lastOrNull()
}

/**
 * Instruct result
 *
 * @property name The name of the instruction.
 * @property statusCode The status code of the instruction result.
 * @property result The result of the instruction.
 * @property resultType The json type of the result, e.g. "string", "number", "boolean", "array", "object".
 * @property instruct The instruction text.
 * */
data class InstructResult @JsonCreator constructor(
    @param:JsonProperty("name") var name: String,
    @param:JsonProperty("statusCode") var statusCode: Int = ResourceStatus.SC_CREATED,
    @param:JsonProperty("result") var result: Any? = null,
    @param:JsonProperty("resultType") var resultType: String? = null,
    @param:JsonProperty("instruct") var instruct: String? = null,
) {
    companion object {

        fun ok(name: String, result: Any, resultType: String = "string"): InstructResult {
            return InstructResult(name, ResourceStatus.SC_OK, result = result, resultType = resultType)
        }

        fun failed(name: String, statusCode: Int = ResourceStatus.SC_EXPECTATION_FAILED): InstructResult {
            return InstructResult(name, statusCode)
        }
    }
}

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

    var message: String? = null, // additional message, e.g. the action flow

    var request: CommandRequest? = null,
    var commandResult: CommandResult? = null,
    var instructResults: MutableList<InstructResult> = mutableListOf(),
) {
    val status: String get() = ResourceStatus.getStatusText(statusCode)
    var lastModifiedTime: Instant? = null
    var finishTime: Instant? = null

    val isDone: Boolean get() = processState == "done"

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
    message = if (message != null) "$message,$event" else event
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
    status.processState = this.processState
    status.message = this.message
    status.lastModifiedTime = this.lastModifiedTime
    status.finishTime = this.finishTime

    // Transfer agent-specific data
    status.agentHistory = this.agentHistory?.toCommandAgentHistory()
    status.agentState = status.agentHistory?.lastOrNull()
    if (this.agentHistory != null) {
        val summary = this.agentHistory?.lastOrNull()?.summary ?: ""
        if (summary.isNotBlank()) {
            status.ensureCommandResult().summary = summary
        }
    }
    if (status.agentState == null) {
        status.agentState = status.createFallbackAgentState()
    }
    if (status.agentHistory == null && status.agentState != null) {
        status.agentHistory = CommandAgentHistory(mutableListOf(requireNotNull(status.agentState)))
    }

    return status
}

fun PageVisitStatus.toCommandStatus(): CommandStatus {
    val status = CommandStatus(this.id)

    // Transfer all basic status fields
    status.statusCode = this.statusCode
    status.event = this.event
    status.processState = this.processState
    status.pageStatusCode = this.pageStatusCode
    status.pageContentBytes = this.pageContentBytes
    status.message = this.message
    status.lastModifiedTime = this.lastModifiedTime
    status.finishTime = this.finishTime

    // Transfer request if present
    status.request = this.request

    // instruct results -> command instruct results
    @Suppress("UNCHECKED_CAST")
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

fun PGInstructResult.toInstructResult(): InstructResult {
    return InstructResult(name, statusCode, result, resultType, instruct)
}

private fun AgentHistory.toCommandAgentHistory(): CommandAgentHistory {
    return CommandAgentHistory(states.map { it.toCommandAgentState() }.toMutableList())
}

private fun AgentState.toCommandAgentState(): CommandAgentState {
    return CommandAgentState(
        step = step,
        instruction = instruction,
        description = description,
        event = event,
        summary = summary,
        isComplete = isComplete,
    )
}

private fun CommandStatus.createFallbackAgentState(): CommandAgentState? {
    val summary = commandResult?.summary ?: message ?: return null
    return CommandAgentState(
        step = 1,
        instruction = summary,
        description = message,
        event = event.takeIf { it.isNotBlank() },
        isComplete = isDone,
        summary = summary,
    )
}
