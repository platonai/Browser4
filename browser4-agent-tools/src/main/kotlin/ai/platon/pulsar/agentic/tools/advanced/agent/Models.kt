package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.browser4.common.B4Constants.PAGE_STATUS_UNKNOWN
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.tools.TaskEnvelopes
import ai.platon.pulsar.agentic.tools.advanced.crawl.PGInstructResult
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.skeleton.event.ServerSideEventHandlers
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

    var message: String? = null, // human-readable status description

    /** Internal event trace for debugging — concatenated lifecycle event names. */
    var eventTrace: String? = null,

    var request: PageVisitRequest? = null,
    var instructResults: MutableList<PGInstructResult> = mutableListOf(),

    /**
     * The HTTP status of the page this task ended on, as
     * [DocumentHttpStatus] observed it, or [PAGE_STATUS_UNKNOWN] when no page
     * status was observed (a coding task, a task that never loaded a page, a
     * browser that does not report navigation timing).
     *
     * It describes the PAGE, not the task, so it must never default to a
     * task-lifecycle code: this field's absence used to surface as
     * `201 / SC_CREATED` — "created" — in every agent and loop record, while the
     * same record's message said the page had loaded with HTTP 200.
     */
    var pageStatusCode: Int = PAGE_STATUS_UNKNOWN,
) {
    val status: String get() = ResourceStatus.getStatusText(statusCode)

    /** Set when the task is first created. */
    var createdTime: Instant = Instant.now()

    /** Set when a worker first picks up the task (first refresh away from CREATED). */
    var startedTime: Instant? = null

    /** Set on every status change. */
    var lastModifiedTime: Instant? = null

    /** Set when the task reaches a terminal state (done or failed). */
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
     * The agent's state history for tracking agent execution progress.
     * This is set when executing agent commands and provides access to the latest agent state.
     *
     * It is serialized to the task persistence JSONL so task histories survive server
     * restarts (restored via [ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence]).
     * [AgentState] only serializes lightweight fields — browser snapshots, action
     * descriptions and tool call results are JsonIgnore'd.
     */
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

    /**
     * The original submitted task/command that triggered this agent execution.
     * Recorded so callers can verify they're getting results for the right task.
     */
    var submittedTask: String? = null

    /**
     * The reason for failure when the agent task does not complete successfully.
     */
    var failureReason: String? = null

    /**
     * True when the task was cancelled (by an explicit user cancel, a runner
     * close, or server shutdown) rather than failing on its own.
     *
     * [ResourceStatus] has no HTTP-styled code for "cancelled", so the persisted
     * row keeps the `failed`/417 shape — this flag is what lets [toCommandStatus]
     * and the CLI's status word report `cancelled` on later polls, instead of
     * showing a user-requested cancel as an unexplained task failure.
     */
    var cancelled: Boolean = false

    /**
     * The directory this task's tools wrote into (the agent's isolated workspace).
     *
     * Recorded when the task starts so a finished task can hand its caller the place
     * its deliverables actually live — the extracted data of a "return X" task is
     * often a file, not a field of the summary.
     */
    var workspace: String? = null

    companion object {
        /**
         * The internal event trace is a debugging aid but ends up in the JSONL
         * persistence rows and in every status poll response; a long agent run
         * emits hundreds of events, so it is capped (kept from the tail — the
         * most recent events are at the end).
         */
        const val EVENT_TRACE_MAX_LENGTH = 512

        fun notFound(id: String) = AgentTaskStatus(
            id,
            ResourceStatus.SC_NOT_FOUND,
            processState = "done",
            message = "No task found with id '$id'. It may have expired, been cleared, or the backend may have restarted.",
        )

        fun failed(id: String) = AgentTaskStatus(id, ResourceStatus.SC_EXPECTATION_FAILED, processState = "done")

        fun failed(id: String, e: Exception): AgentTaskStatus {
            return AgentTaskStatus(
                id,
                statusCode = ResourceStatus.SC_EXPECTATION_FAILED,
                processState = "done",
                message = e.message,
            )
        }
    }
}

fun AgentTaskStatus.refresh(isDone: Boolean = false) {
    val now = Instant.now()
    lastModifiedTime = now
    if (startedTime == null) { startedTime = now }
    processState = "done".takeIf { isDone } ?: "in_progress"
}

fun AgentTaskStatus.refresh(statusCode: Int) = refresh(statusCode, false)

fun AgentTaskStatus.refresh(statusCode: Int, isDone: Boolean) {
    val now = Instant.now()
    lastModifiedTime = now
    this.statusCode = statusCode
    if (startedTime == null) { startedTime = now }
    processState = "done".takeIf { isDone } ?: "in_progress"
}

fun AgentTaskStatus.failed(statusCode: Int): AgentTaskStatus {
    refresh(statusCode, isDone = true)
    return this
}

fun AgentTaskStatus.emitEvent(event: String) {
    this.event = event
    // Keep the internal event trace for debugging separate from the user-facing message,
    // capped so a long run cannot grow the JSONL rows and poll payloads without bound.
    val newTrace = if (eventTrace != null) "$eventTrace,$event" else event
    eventTrace = if (newTrace.length > AgentTaskStatus.EVENT_TRACE_MAX_LENGTH) {
        "...(truncated)" + newTrace.takeLast(AgentTaskStatus.EVENT_TRACE_MAX_LENGTH)
    } else {
        newTrace
    }
    // Generate a concise human-readable status description instead of the raw event chain:
    // exact event-name lookup, shared with CommandStatus so the two copies cannot drift.
    message = TaskEnvelopes.progressMessage(event)
}

fun AgentTaskStatus.done() {
    val now = Instant.now()
    if (startedTime == null) { startedTime = now }
    refresh(isDone = true)
    finishTime = now
}

fun AgentTaskStatus.refreshed(lastModifiedTime: Instant): Boolean {
    val modifiedTime = this.lastModifiedTime ?: return false
    return modifiedTime > lastModifiedTime
}
