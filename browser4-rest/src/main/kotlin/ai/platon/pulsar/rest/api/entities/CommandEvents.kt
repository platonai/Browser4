package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEventPage
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskRunStats

/**
 * One page of an agent task's live step log: `GET /api/commands/{id}/events`.
 *
 * The cursor model (rather than SSE) is deliberate — the CLI polls with
 * `?after=<nextSeq>`, which reconnects naturally, survives a proxy that buffers
 * responses, and needs no stream parsing on the client.
 *
 * @property taskId the task the events belong to
 * @property status the task's status word (`queued|running|done|failed|cancelled`)
 * @property done true when the task reached a terminal state; a follower stops
 *   polling (and reads the result) once it sees this
 * @property nextSeq cursor to pass as `after` on the next call: the sequence
 *   number of the last returned event, or the requested cursor when the page is
 *   empty
 * @property events the steps after the requested cursor, oldest first
 * @property firstRetainedSeq sequence number of the oldest retained step (`0` when
 *   the log is empty/unknown) — compare it with your cursor to see the window move
 * @property dropped steps evicted from the bounded buffer over the log's lifetime;
 *   a follower that starts at `after=0` can tell it began inside the window from it.
 *   A log **restored** after a backend restart starts this counter at 0: the steps
 *   evicted before the restart are gone from both the buffer and its on-disk shard, so
 *   `firstRetainedSeq` is what tells a follower where the remaining window begins.
 * @property resynced true when `after` had fallen behind the retained window: the
 *   page is the retained tail, not a continuation of what the caller already saw.
 *   Without this field the endpoint answered "you missed 300 steps" and "nothing new
 *   happened" with the same empty page (issue #652, P0-D)
 * */
data class CommandEventsResponse(
    val taskId: String,
    val status: String,
    val done: Boolean,
    val nextSeq: Long,
    val events: List<AgentTaskEvent> = emptyList(),
    val firstRetainedSeq: Long = 0L,
    val dropped: Long = 0L,
    val resynced: Boolean = false,
)

/**
 * Builds the `events` answer from one page of a task's log.
 *
 * Shared by the REST endpoint and the MCP `command_events` tool so an MCP-only client
 * and a REST client cannot disagree about `status` / `done` / the cursor.
 */
object CommandEvents {

    /** Event types that mean "this task will not produce anything else". */
    val TERMINAL_EVENT_TYPES: Set<String> = setOf(
        AgentTaskEvent.TYPE_DONE,
        AgentTaskEvent.TYPE_FAILED,
        AgentTaskEvent.TYPE_CANCELLED,
    )

    /** Status word for an id whose event log exists but whose status row is gone. */
    const val STATUS_UNKNOWN = "unknown"

    /**
     * Compose the response.
     *
     * The terminal state is derived from the last terminal event when the status row is
     * gone: the status cache expires 2h after its last WRITE while the event log expires
     * after its last READ, so a followed task can keep its log and lose its row.
     * Reporting `done=false` forever there would make an uncapped follower poll
     * indefinitely.
     *
     * @param taskId the task id
     * @param status the status row, when this backend still has one
     * @param eventsPage the page from the runner's event log, or `null` when no log exists
     * @param after the cursor the caller sent (an empty page must not move it)
     */
    fun page(
        taskId: String,
        status: CommandStatus?,
        eventsPage: AgentTaskEventPage?,
        after: Long,
    ): CommandEventsResponse {
        val events = eventsPage?.events.orEmpty()
        val terminal = events.lastOrNull { it.type in TERMINAL_EVENT_TYPES }
        val word = status?.statusWord() ?: when (terminal?.type) {
            AgentTaskEvent.TYPE_DONE -> "done"
            AgentTaskEvent.TYPE_FAILED -> "failed"
            AgentTaskEvent.TYPE_CANCELLED -> "cancelled"
            else -> STATUS_UNKNOWN
        }
        return CommandEventsResponse(
            taskId = taskId,
            status = word,
            done = status?.isDone ?: (terminal != null),
            // An empty page must not move the cursor backwards: report the one the
            // caller asked with, so the next poll is idempotent.
            nextSeq = eventsPage?.nextSeq ?: after,
            events = events,
            firstRetainedSeq = eventsPage?.firstRetainedSeq ?: 0L,
            dropped = eventsPage?.dropped ?: 0L,
            resynced = eventsPage?.resynced ?: false,
        )
    }
}

/**
 * The aggregate run statistics of an agent task:
 * `GET /api/commands/{id}/stats`.
 *
 * The question this answers is "what did this run cost me": how many LLM calls,
 * how many tool calls (and how many failed), and how many tokens the provider
 * billed. Counters come from the task runner's event log, which counts every
 * step as it is appended — some steps may already have been evicted from the
 * bounded buffer, so a client cannot reconstruct these numbers by counting the
 * events it received.
 *
 * @property taskId the task the statistics belong to
 * @property status the task's status word (`queued|running|done|failed|cancelled`,
 *   or `unknown` when the status row expired while the counters survived)
 * @property processState raw process state (`created|in_progress|done`)
 * @property isDone true when the task reached a terminal state
 * @property elapsedMs wall-clock run time so far (or the final duration); `null`
 *   when the task never started
 * @property stats the counters
 * */
data class CommandStatsResponse(
    val taskId: String,
    val status: String,
    val processState: String,
    val isDone: Boolean,
    val elapsedMs: Long? = null,
    val stats: AgentTaskRunStats = AgentTaskRunStats(),
)

/**
 * Body of `POST /api/commands/{id}/input`: a user message typed while the task runs.
 *
 * @property text what the user wants the agent to do from now on
 * @property deliverAs `steer` (default) injects it at the agent's next model
 *   round-trip; `followUp` delivers it when the agent would stop, continuing the same
 *   conversation with the new instruction instead of starting a second task
 * */
data class CommandInputRequest(
    val text: String = "",
    val deliverAs: String = "steer",
)

/**
 * The full run trace of an agent task: `GET /api/commands/{id}/trace`.
 *
 * The step log answers "what did it do, in order"; the trace is the audit trail behind it —
 * the exact prompt the model saw, one line per executed tool with its complete arguments and
 * complete output, the provider's usage per request, the page timeline. It lives on the backend
 * disk under `logs/agent/<time>/<uuid>/`, which a client cannot resolve on its own: this
 * response is the mapping, and [CommandTraceFile] entries are what
 * `GET /api/commands/{id}/trace/{file}` serves (issue #652, gap 5).
 *
 * Only the `*.jsonl` audit streams are listed — the raw prompt dumps are kept local. They carry
 * the whole conversation, whose credentials are redacted at the tool layer but not per message
 * yet, so serving them would move an unredacted copy over HTTP.
 *
 * @property taskId the task the trace belongs to
 * @property runDir absolute path of the run's trace directory (informational: a local operator
 *   can open the files directly, and the path is what the retention policy ages out)
 * @property files the audit streams, newest-modified first
 * */
data class CommandTraceResponse(
    val taskId: String,
    val runDir: String,
    val files: List<CommandTraceFile> = emptyList(),
)

/**
 * One audit stream inside a run's trace directory.
 *
 * @property name file name (`cli-tool-trace.jsonl`, `cli-usage.jsonl`, `page-timeline.jsonl`, …)
 * @property bytes file size
 * @property modifiedAt last write, ISO-8601 (a reader can tell a finished run from a live one)
 * */
data class CommandTraceFile(
    val name: String,
    val bytes: Long = 0,
    val modifiedAt: String? = null,
)

/**
 * One page of one trace stream: `GET /api/commands/{id}/trace/{file}?offset=&limit=`.
 *
 * Traces are line-oriented JSONL, and a long run's prompt or tool stream is far too big to
 * return whole, so the endpoint pages by line. Every line is redacted again on the way out: the
 * current build redacts when it writes, and this covers a trace written by an older build in the
 * same data dir.
 *
 * @property taskId the task the trace belongs to
 * @property file the stream name that was read
 * @property offset the first line returned (0-based; a client pages with `offset + limit`)
 * @property limit the page size that was requested after clamping
 * @property totalLines how many lines the file holds, so a client knows when it is done
 * @property lines the raw lines of the page, oldest first
 * */
data class CommandTraceFileResponse(
    val taskId: String,
    val file: String,
    val offset: Int,
    val limit: Int,
    val totalLines: Int,
    val lines: List<String> = emptyList(),
)

/**
 * Answer of `POST /api/commands/{id}/input`.
 *
 * `accepted=false` is not an HTTP error: "the task already finished" is a
 * normal race for a human typing into a terminal, and the CLI reports it inline
 * instead of failing the session.
 *
 * @property taskId the task the message was aimed at
 * @property accepted true when the message was queued for a live task
 * @property message why it was or was not accepted
 * @property inputId correlation id of the queued message (`null` when it was not
 *   accepted). The delivered step repeats it in `detail.inputIds` (`steer` at the next
 *   model round-trip, `follow_up` at the stop point), so `accepted=true` is no longer
 *   the only thing a caller can prove — it can recognise its own message in the stream
 *   (issue #652, P1-5)
 * */
data class CommandInputResponse(
    val taskId: String,
    val accepted: Boolean,
    val message: String? = null,
    val inputId: String? = null,
)
