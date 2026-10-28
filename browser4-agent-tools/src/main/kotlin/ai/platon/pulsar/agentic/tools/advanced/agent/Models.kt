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

    /**
     * Live count of tool calls the current run has executed.
     *
     * Fed from the CLI engine's throttled progress events
     * (`onToolExecuted` → `metadata["toolExecutions"]`, every
     * `CLI_TOOL_PROGRESS_EVENT_INTERVAL` calls). The step history is only
     * written when a run completes, so without this a running CLI task reported
     * no progress at all and `agent status` could not tell a 50-minute run from
     * a hung one.
     */
    var toolExecutions: Int = 0

    /**
     * When the task last showed activity: [lastModifiedTime] once it started,
     * [createdTime] before that.
     *
     * The TTL/compaction decisions used `createdTime`, so a long-running task's
     * latest status row looked stale simply because the task had been created
     * hours earlier.
     */
    fun lastActivity(): Instant = lastModifiedTime ?: createdTime

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

    /**
     * Files the run had already produced in [workspace] when it ended **without**
     * reporting them — a token-budget abort, the step cap, a cancellation.
     *
     * A run that wrote its CSV and then hit the budget was reported as a bare failure
     * with `filesChanged` empty, so the data looked lost although it was on disk
     * (2026-10-28: 20 rows × 75 fields, task `failed (417)`). [toCommandStatus] falls
     * back to this list when the agent left no completion report.
     */
    var deliverables: List<String>? = null

    /**
     * Coding mode this task ran with (null for rows written before the field
     * existed). Needed by `agent run --resume <id>`: a resumed coding task must
     * stay a coding task.
     */
    var codingMode: Boolean? = null

    /** Access preset id this task ran under (null when unknown), reused on resume. */
    var accessPreset: String? = null

    /** The interrupted task this run resumes, when started with `agent run --resume <id>`. */
    var resumedFrom: String? = null

    /**
     * The token budget this run actually enforced (`0` = unlimited, `null` = never
     * reported by the engine).
     *
     * A budget abort used to surface as a bare `TokenBudgetExceededException` with no
     * number attached, so a caller could not tell whether the configured 5M default
     * was too small or the run was runaway. Filled from the agent's own budget at the
     * end of the run (see `RobustBrowserAgent.lastRunTokenBudget`).
     */
    var effectiveTokenBudget: Long? = null

    /**
     * Machine-readable failure reason (`TOKEN_BUDGET`, `NOOP_LIMIT`, `CANCELLED`,
     * `TOOL_ERROR`), derived from [failureReason] by [failureCodeOf].
     *
     * Clients branch on this instead of matching prose: `agent result` uses it for a
     * resume hint, and the CLI maps it onto `task.failed.errorCode`.
     */
    var errorCode: String? = null

    /**
     * Whether this failed/cancelled task can be continued with
     * `agent run --resume <id>`: a budget/step/no-op abort is resumable (the
     * workspace and its files survive), an infrastructure failure may not be.
     */
    var resumable: Boolean? = null

    companion object {
        /** Machine-readable failure codes carried on a failed/cancelled task. */
        const val ERROR_TOKEN_BUDGET = "TOKEN_BUDGET"
        const val ERROR_NOOP_LIMIT = "NOOP_LIMIT"
        const val ERROR_CANCELLED = "CANCELLED"
        const val ERROR_TOOL_ERROR = "TOOL_ERROR"
        const val ERROR_INFRASTRUCTURE = "INFRASTRUCTURE"

        /** Failure reasons produced by the token-budget guard. */
        private val TOKEN_BUDGET_PATTERN = Regex("""(?i)token ?budget|tokenbudget""")

        /** Failure reasons produced by the consecutive no-op guard. */
        private val NOOP_PATTERN = Regex("""(?i)\bno[- ]?op|nooplimit""")

        /** Failure reasons that mean the runtime, not the task, was unusable. */
        private val INFRA_PATTERN = Regex(
            """(?i)server restart|runner closed|backend|unreachable|no checkpoint"""
        )

        /**
         * Derive the machine-readable code of a failed run from its reason.
         *
         * Codes, not prose: the CLI's `task.failed` line, `agent result` and any
         * supervisory script must agree on WHY a run stopped. A budget abort used to
         * surface only as `TokenBudgetExceededException: …`, which no client could
         * branch on (2026-10-28 baseline).
         */
        fun errorCodeOf(failureReason: String?, cancelled: Boolean): String? = when {
            cancelled -> ERROR_CANCELLED
            failureReason.isNullOrBlank() -> null
            TOKEN_BUDGET_PATTERN.containsMatchIn(failureReason) -> ERROR_TOKEN_BUDGET
            NOOP_PATTERN.containsMatchIn(failureReason) -> ERROR_NOOP_LIMIT
            INFRA_PATTERN.containsMatchIn(failureReason) -> ERROR_INFRASTRUCTURE
            else -> ERROR_TOOL_ERROR
        }

        /**
         * Whether a failed task can be continued with `agent run --resume <id>`.
         *
         * Everything the runner can resume has an instruction and a workspace; only a
         * failure whose cause is the runtime (no live backend, no checkpoint) cannot
         * be resumed in place. `null` for an unknown code.
         */
        fun isResumable(errorCode: String?): Boolean? = when (errorCode) {
            null -> null
            ERROR_INFRASTRUCTURE -> false
            else -> true
        }

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
    // Progress events are status changes: without this bump the SSE status
    // stream (CommandStatus.refreshed) stayed silent for a whole CLI-agent run —
    // the message changed but lastModifiedTime did not.
    lastModifiedTime = Instant.now()
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

/**
 * A detached copy of this status, safe to serialize while the run keeps mutating
 * the original.
 *
 * `data class copy()` only carries the CONSTRUCTOR properties, so the body-declared
 * timestamps, submitted task, failure reason and workspace were silently reset by
 * the plain `status.copy()` the compactor used — a compacted task lost its
 * `startedTime` (and therefore its elapsed time) and its `createdTime` jumped to
 * "now", extending its TTL. They are copied explicitly here.
 *
 * @param includeHistory false for the periodic in-progress snapshots: they only
 *   need the message/step/counter, and the CLI engine's history is empty until
 *   completion anyway. The terminal snapshot keeps the history.
 */
fun AgentTaskStatus.detachedSnapshot(includeHistory: Boolean = true): AgentTaskStatus {
    val snapshot = copy()
    snapshot.agentHistory = if (includeHistory) {
        agentHistory?.let { AgentHistory(it.states.toMutableList()) }
    } else {
        null
    }
    snapshot.createdTime = createdTime
    snapshot.startedTime = startedTime
    snapshot.lastModifiedTime = lastModifiedTime
    snapshot.finishTime = finishTime
    snapshot.submittedTask = submittedTask
    snapshot.failureReason = failureReason
    snapshot.workspace = workspace
    snapshot.toolExecutions = toolExecutions
    // Body-declared fields the resume/finish paths read back from disk: `copy()`
    // resets every one of them, so a progress row used to lose the flags a later
    // `agent run --resume` inherits and the artifacts an aborted run produced.
    snapshot.codingMode = codingMode
    snapshot.accessPreset = accessPreset
    snapshot.resumedFrom = resumedFrom
    snapshot.deliverables = deliverables
    snapshot.effectiveTokenBudget = effectiveTokenBudget
    snapshot.errorCode = errorCode
    snapshot.resumable = resumable
    return snapshot
}
