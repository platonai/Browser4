package ai.platon.pulsar.rest.api.entities

import ai.platon.browser4.common.B4Constants.PAGE_STATUS_UNKNOWN
import ai.platon.pulsar.agentic.tools.TaskEnvelopes
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
 * @property pageStatusCode The HTTP status of the page the command ended on, or
 *   `B4Constants.PAGE_STATUS_UNKNOWN` (`0`) when no page status was observed — it
 *   is never a task-lifecycle code such as `201 / SC_CREATED`.
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

    /**
     * The HTTP status of the PAGE this command ended on, or
     * [PAGE_STATUS_UNKNOWN] when no page status was observed.
     *
     * It describes the page, not the task, so it must never default to a
     * task-lifecycle code: `201 / SC_CREATED` ("created") was exactly that
     * mistake — a command that never observed a page reported
     * `"pageStatusCode": 201` next to a message saying the page had loaded with
     * HTTP 200. Producers set it from an observation (`DocumentHttpStatus`,
     * `StatefulPageVisitor`); the default means "not observed", and a real `201`
     * from a page stays distinguishable from it.
     */
    var pageStatusCode: Int = PAGE_STATUS_UNKNOWN,
    var pageContentBytes: Int = 0,

    var message: String? = null, // human-readable status description

    /**
     * True when the task was cancelled (explicit cancel, runner close, server
     * shutdown) rather than failing on its own. Kept as an explicit flag because
     * the persisted row's HTTP-styled code stays `failed`/417 — see
     * [AgentTaskStatus.cancelled]. [`statusWord`](CommandToolExecutor) maps it
     * onto the shared `cancelled` vocabulary word on subsequent polls.
     */
    var cancelled: Boolean = false,

    /** Internal event trace for debugging — concatenated lifecycle event names. */
    var eventTrace: String? = null,

    var request: CommandRequest? = null,
    var commandResult: CommandResult? = null,
    var instructResults: MutableList<InstructResult> = mutableListOf(),

    /**
     * The directory the agent's tools wrote into (agent-backed tasks only, see
     * [AgentTaskStatus.workspace]). Surfaced through [commandResult] so a caller can
     * reach a deliverable the agent left on disk.
     */
    var workspace: String? = null,
) {
    val status: String get() = ResourceStatus.getStatusText(statusCode)
    var lastModifiedTime: Instant? = null
    var finishTime: Instant? = null
    /**
     * When a worker first picked the task up (agent tasks only; null for page
     * visits and unknown ids).
     *
     * Exposed so the status envelope can report `elapsedMs`: a caller polling a
     * 50-minute agent run previously had no way to tell it apart from a task
     * that had been running for two seconds.
     */
    var startedTime: Instant? = null

    /**
     * Live count of tool calls the agent has executed, reported by the engine's
     * throttled progress events (0 when it reports none).
     *
     * It is the only progress signal available while a CLI tool-loop run is in
     * flight: the step history is written when the run completes, so `processed`
     * used to stay null for the whole (possibly hour-long) run.
     */
    var toolExecutions: Int = 0

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
        /**
         * The status of a task id the backend has no record of.
         *
         * The message is not decoration: the shared envelope drops a blank error, so
         * without it an unknown id was indistinguishable from a genuine task failure
         * (`status=failed` with no reason) and the CLI's friendly not-found message
         * could never fire.
         */
        fun notFound(id: String) = CommandStatus(
            id,
            ResourceStatus.SC_NOT_FOUND,
            processState = "done",
            message = "No task found with id '$id'. It may have expired, been cleared, or the backend may have restarted.",
        )

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
    // A progress event is a status change: refresh the timestamp so
    // commandStatusFlow/SSE observers actually see it.
    lastModifiedTime = Instant.now()
    // Keep the internal event trace for debugging separate from the user-facing message.
    eventTrace = if (eventTrace != null) "$eventTrace,$event" else event
    // Generate a concise human-readable status description; the exact event-name
    // mapping is shared with AgentTaskStatus so the two copies cannot drift apart.
    message = TaskEnvelopes.progressMessage(event, fallback = "Task is in progress")
}

fun CommandStatus.failed(statusCode: Int, pageStatusCode: Int): CommandStatus {
    refresh(statusCode, pageStatusCode, isDone = true)
    return this
}

/**
 * The task's status mapped onto the shared, user-facing vocabulary
 * (`queued|running|done|failed|cancelled`).
 *
 * One implementation for every consumer: the MCP `command_status` envelope and
 * the REST `GET /api/commands/{id}/events` answer must agree, or a follower
 * would stop polling on a word the poller never shows.
 * */
fun CommandStatus.statusWord(): String = when {
    // A cancelled task is done and non-OK, but calling it "failed" turns a
    // user-requested cancel into an unexplained failure on every later poll —
    // the flag (set only by explicit cancel/close paths) wins over the code.
    isDone && cancelled -> "cancelled"
    isDone -> if (statusCode == ResourceStatus.SC_OK) "done" else "failed"
    processState == "created" -> "queued"
    else -> "running"
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
    status.cancelled = this.cancelled
    status.eventTrace = this.eventTrace
    status.lastModifiedTime = this.lastModifiedTime
    status.finishTime = this.finishTime
    status.startedTime = this.startedTime
    status.toolExecutions = this.toolExecutions
    status.workspace = this.workspace
    // The page this task ended on. AgentTaskStatus now carries it (observed from
    // the live page, see DocumentHttpStatus); before that the conversion dropped
    // it and every agent/loop record shipped the model's 201/"created" default.
    status.pageStatusCode = this.pageStatusCode

    // Transfer agent-specific data
    status.agentHistory = this.agentHistory?.toCommandAgentHistory()
    status.agentState = status.agentHistory?.lastOrNull()

    // Transfer instruct results so agent-extracted data (fields/links/pageSummary)
    // flows into commandResult — mirrors PageVisitStatus.toCommandStatus().
    // Without this, `agent result` returned {} even though instructResults held
    // the extracted data (critical data loss).
    this.instructResults.map { it.toInstructResult() }.forEach { result ->
        status.addInstructResult(result)
    }

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

    // The rest of the agent's own completion report. `agent result` used to return a
    // one-sentence summary for a task whose whole point was returning data, leaving
    // the deliverable discoverable only by searching the backend's app-data tree.
    val lastState = status.agentState
    val result = status.ensureCommandResult()
    result.keyFindings = lastState?.keyFindings?.takeIf { it.isNotEmpty() }
    // A run that aborted before `system.taskComplete` (token budget, step cap,
    // cancellation) still wrote its artifacts; the runner snapshots them into
    // `deliverables`. Without this fallback the data looked lost although it was on
    // disk (2026-10-28: a complete 20×75 CSV reported as a bare failure).
    result.filesChanged = lastState?.filesChanged?.takeIf { it.isNotEmpty() }
        ?: this.deliverables?.takeIf { it.isNotEmpty() }
    result.problems = lastState?.problems?.takeIf { it.isNotEmpty() }
    result.workspace = this.workspace

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
