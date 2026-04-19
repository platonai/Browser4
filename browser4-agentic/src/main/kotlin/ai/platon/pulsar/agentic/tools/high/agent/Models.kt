package ai.platon.pulsar.agentic.tools.high.agent

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.tools.high.crawl.PGInstructResult
import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.skeleton.crawl.ServerSideEventHandlers
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import java.util.*

/**
 * Command status
 *
 * @property id The unique identifier for the command status.
 * @property statusCode The HTTP status code representing the command status.
 * @property event The last event associated with the command status.
 * @property isDone Indicates whether the command has been completed.
 * @property message An optional message providing additional information about the command status.
 * @property request The original command request associated with this status.
 * @property instructResults A list of results from the instructions executed during the command.
 * */
data class AgentTaskStatus(
    val id: String = UUID.randomUUID().toString(),
    /**
     * The status code representing the agent task status.
     * */
    var statusCode: Int = ResourceStatus.SC_CREATED,
    /**
     * The last event associated with the agent task.
     * */
    var event: String = "",
    /**
     * The progress state of the agent task. Can be "created", "in_progress", or "done".
     * */
    var processState: String = "created",

    var message: String? = null, // additional message, e.g. the action flow

    var request: PageVisitRequest? = null,
    var instructResults: MutableList<PGInstructResult> = mutableListOf()
) {
    val status: String get() = ResourceStatus.getStatusText(statusCode)
    var lastModifiedTime: Instant? = null
    var finishTime: Instant? = null

    val isDone: Boolean get() = processState == "done"

    /**
     * Returns the latest agent state from the agent history.
     * This provides real-time access to the agent's execution state during async operations.
     */
    val agentState: AgentState?
        get() = agentHistory?.lastOrNull()

    val summary get() = agentState?.summary

    /**
     * The agent's state history reference for tracking agent execution progress.
     * This is set when executing agent commands and provides access to the latest agent state.
     * It is excluded from JSON serialization as it's only used for internal tracking.
     */
    @get:JsonIgnore
    var agentHistory: AgentHistory? = null

    /**
     * The server-side event handlers reference for tracking server-side events during command execution.
     * This is set when executing commands and provides access to the event flow.
     * It is excluded from JSON serialization as it's only used for internal event streaming.
     */
    @get:JsonIgnore
    var serverSideEventHandlers: ServerSideEventHandlers? = null

    /**
     * The server-side agent event handlers reference for tracking agent events during agent execution.
     * This is set when executing agent commands and provides access to the agent event flow.
     * It is excluded from JSON serialization as it's only used for internal event streaming.
     */
    @get:JsonIgnore
    var serverSideAgentEventHandlers: ai.platon.pulsar.agentic.event.ServerSideAgentEventHandlers? = null

    companion object {
        fun notFound(id: String) = AgentTaskStatus(id, ResourceStatus.SC_NOT_FOUND, processState = "done")

        fun failed(id: String) = AgentTaskStatus(id, ResourceStatus.SC_EXPECTATION_FAILED, processState = "done")

        fun failed(id: String, e: Exception): AgentTaskStatus {
            return AgentTaskStatus(id, statusCode = ResourceStatus.SC_EXPECTATION_FAILED, processState = "done")
        }
    }
}

fun AgentTaskStatus.refresh(isDone: Boolean = false) {
    lastModifiedTime = Instant.now()
    processState = "done".takeIf { isDone } ?: "in_progress"
}

fun AgentTaskStatus.refresh(statusCode: Int) = refresh(statusCode, false)

fun AgentTaskStatus.refresh(statusCode: Int, isDone: Boolean) {
    lastModifiedTime = Instant.now()
    this.statusCode = statusCode
    processState = "done".takeIf { isDone } ?: "in_progress"
}

fun AgentTaskStatus.failed(statusCode: Int): AgentTaskStatus {
    // do not change pageStatusCode
    refresh(statusCode, isDone = true)
    return this
}

fun AgentTaskStatus.emitEvent(event: String) {
    this.event = event
    message = if (message != null) "$message,$event" else event
}

fun AgentTaskStatus.done() {
    refresh(isDone = true)
    finishTime = Instant.now()
}

fun AgentTaskStatus.refreshed(lastModifiedTime: Instant): Boolean {
    val modifiedTime = this.lastModifiedTime ?: return false
    return modifiedTime > lastModifiedTime
}
