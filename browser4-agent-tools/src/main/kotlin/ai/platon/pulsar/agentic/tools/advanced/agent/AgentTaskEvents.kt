package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * One observable step of an agent task.
 *
 * The task status envelope ([AgentTaskStatus]) only carries `processState` and a
 * throttled tool counter, which is far too coarse for a live view: a CLI-engine
 * run can execute a hundred tools inside a single step. Every step an
 * `agent run --follow` (or the interactive `agent run -i`) shows is one of
 * these, served by `GET /api/commands/{id}/events`.
 *
 * Events are kept in a bounded in-memory log ([AgentTaskEventLog]) next to the
 * task status, and the same steps are written to a per-task JSONL shard by
 * [AgentTaskEventStore] — write-behind, on a daemon thread, so a run pays nothing for
 * it, and a backend restart can restore the PROCESS instead of only the status row.
 * The JSONL row set stays a task registry; the shards live next to it under
 * `<data>/agent/events/` and age out with the same TTL.
 *
 * `arguments` and `text` are already truncated and secret-redacted by
 * [AgentTaskEventSanitizer] when the event enters the log — which is also the point
 * where it is persisted, so the on-disk copy is as safe as the served one.
 *
 * @property seq monotonic per-task sequence number, the cursor for `?after=`
 * @property type one of the `AgentTaskEvent.TYPE_*` constants
 * @property epochMillis wall-clock time the step happened (epoch millis, so the
 *   wire format needs no date parsing)
 * @property tool tool name for `tool.call` / `tool.result`
 * @property arguments redacted, truncated JSON argument preview
 * @property ok tool outcome (`null` for non-tool events)
 * @property durationMs tool execution time
 * @property text model text, result preview, or lifecycle message
 * @property turn 1-based outer agent round the step belongs to
 * @property inputTokens prompt tokens of one model request, prompt-cache reads
 *   and writes included ([TYPE_USAGE] only; `null` when the provider reported no
 *   usage at all).  Normalized across provider protocols: OpenAI-compatible
 *   providers already count cache hits inside `prompt_tokens`, Anthropic-style
 *   ones report them separately — both arrive here cache-inclusive, so
 *   `cachedInputTokens / inputTokens` is a hit ratio for either.
 * @property outputTokens provider-reported output tokens of one model request
 * @property totalTokens the request's accounted total: [inputTokens] + output
 *   tokens
 * @property cachedInputTokens prompt tokens served from the provider's prompt
 *   cache for this request ([TYPE_USAGE] only; `null` — not `0` — when the
 *   provider reported no cache details)
 * @property cacheWriteInputTokens prompt tokens this request wrote into the
 *   provider's prompt cache (Anthropic-style APIs; `null` when unreported)
 * @property budgetTokens the run's effective token budget ([TYPE_USAGE] only;
 *   `0` = unlimited, `null` = not reported)
 * @property cumulativeTokens charged tokens consumed so far, this request included
 *   ([TYPE_USAGE] only) — cache-miss prompt tokens + output, what the budget
 *   enforces; a follower needs it to show "1.24M / 5.0M (25%)"
 * @property errorCode machine-readable failure reason ([TYPE_FAILED] only):
 *   `TOKEN_BUDGET`, `NOOP_LIMIT`, `CANCELLED`, `TOOL_ERROR`
 * @property resumable whether `agent run --resume <id>` can continue the task
 *   ([TYPE_FAILED] only)
 * @property callId stable id of one tool invocation, shared by its [TYPE_TOOL_CALL]
 *   and [TYPE_TOOL_RESULT]; `null` on every non-tool event. Consumers must pair
 *   call/result by this id, never by tool name (parallel calls of the same tool
 *   are indistinguishable by name, and the CLI renderer's name heuristic was
 *   documented as a workaround for exactly this gap).
 * @property detail bounded structured payload of a decision event
 *   ([TYPE_VERIFY_VERDICT], [TYPE_GATE_EVALUATED], [TYPE_REPAIR_ROUND], …).
 *   Rendered prose stays in [text]; this is what a machine reads. Strings inside
 *   are previewed and credential-redacted by [AgentTaskEventSanitizer.detail], and
 *   an oversized payload is trimmed with `detail["truncated"] = true` — a
 *   supervisor must be able to tell "the payload was cut" from "the payload ended".
 * @property schemaVersion version of the event model that produced this row
 *   ([SCHEMA_VERSION]); absent on rows written before 2026-11-22. A consumer
 *   branches on it before interpreting [detail].
 * @property mutations cumulative file mutations of the run as of this step: a
 *   **gauge**, not a delta ([TYPE_ROUND] carries it). Together with
 *   [readOnlyRounds] it answers "N tool calls / 0 files changed" — the earliest
 *   reliable signal of a run that will not converge — without reading the trace.
 * @property readOnlyRounds consecutive coding rounds that ran tools but recorded
 *   no file mutation ([TYPE_ROUND] only; gauge). This is the number the engine's
 *   own "stop surveying, start editing" nudge fires on, so a supervisor sees the
 *   course correction before the model does.
 * */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentTaskEvent(
    val seq: Long = 0L,
    val type: String = "",
    val epochMillis: Long = System.currentTimeMillis(),
    val tool: String? = null,
    val arguments: String? = null,
    val ok: Boolean? = null,
    val durationMs: Long? = null,
    val text: String? = null,
    val turn: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Prompt tokens served from the provider cache ([TYPE_USAGE] only; `null` = unreported). */
    val cachedInputTokens: Int? = null,
    /** Prompt tokens written to the provider cache ([TYPE_USAGE] only; `null` = unreported). */
    val cacheWriteInputTokens: Int? = null,
    val budgetTokens: Long? = null,
    val cumulativeTokens: Long? = null,
    val errorCode: String? = null,
    val resumable: Boolean? = null,
    /** Stable id of one tool invocation, shared by its `tool.call` and `tool.result`. */
    val callId: String? = null,
    /** Bounded structured payload of a decision event (see the class KDoc). */
    val detail: Map<String, Any?>? = null,
    /** Version of the event model; `null` on rows written before 2026-11-22. */
    val schemaVersion: Int? = null,
    /** Cumulative file mutations of the run ([TYPE_ROUND] only; gauge). */
    val mutations: Long? = null,
    /** Consecutive coding rounds without a mutation ([TYPE_ROUND] only; gauge). */
    val readOnlyRounds: Int? = null,
) {
    companion object {
        /** The task row was created (queued). */
        const val TYPE_CREATED = "task.created"

        /** The runner picked the task up and began executing the instruction. */
        const val TYPE_STARTED = "task.started"

        /** A new outer agent round started (a model request is about to happen). */
        const val TYPE_ROUND = "round.start"

        /** The model requested a tool, before it executes. */
        const val TYPE_TOOL_CALL = "tool.call"

        /** A tool finished, with its outcome and duration. */
        const val TYPE_TOOL_RESULT = "tool.result"

        /** The model answered with plain text instead of a tool call. */
        const val TYPE_MODEL_TEXT = "model.text"

        /** A user steering message was injected into the run. */
        const val TYPE_STEER = "steer"

        /**
         * A steering message arrived after the task's LAST model round.
         *
         * It can never be delivered: the loop is over. The runner drains it at the
         * terminal transition, keeps it as a session correction for the next task and
         * says so here — the CLI answered "steering queued" when the user typed it, so
         * silence would report success for a line the agent never saw.
         */
        const val TYPE_STEER_UNDELIVERED = "steering.undelivered"

        /**
         * A queued FOLLOW-UP message was delivered.
         *
         * Unlike [TYPE_STEER] (injected mid-run, at the next model round), a follow-up is
         * delivered when the agent would otherwise stop: it is appended as a real user
         * message to the same conversation and the loop continues, so the task does not
         * end between the user's line and the model reading it.
         */
        const val TYPE_FOLLOW_UP = "follow_up"

        /** A follow-up arrived after the task ended and could not be delivered. */
        const val TYPE_FOLLOW_UP_UNDELIVERED = "follow_up.undelivered"

        /**
         * One successful model request: the tokens the provider reported for it.
         *
         * Emitted once per LLM API call, so the count of these steps is the
         * honest "how many LLM calls did this run make" — more than the number of
         * outer rounds whenever a tool-calling round needed several requests.
         * The token fields may all be `null` (a provider that reports no usage,
         * a local model): the request still happened, so it is still counted.
         */
        const val TYPE_USAGE = "run.usage"

        /** The task finished successfully. */
        const val TYPE_DONE = "task.done"

        /** The task failed; `text` carries the reason. */
        const val TYPE_FAILED = "task.failed"

        /** The task was cancelled (explicit cancel, runner close, shutdown). */
        const val TYPE_CANCELLED = "task.cancelled"

        // ── Decision events (issue #652, P0-A) ───────────────────────────────
        //
        // The signals the engine itself uses for course correction, exported so an
        // external supervisor can judge "is this run approaching the goal, and is it
        // about to be judged failed" while it is still running — instead of reading
        // the backend log after the tokens are spent.
        //
        // All of them carry a bounded `detail` map; none of them change the meaning of
        // the lifecycle/round/tool/model events above, and an existing consumer ignores
        // an unknown type.

        /**
         * The structured verdict of one `coding.verify` run (`detail`:
         * `passed`, `module`, `checks[{name, target, ran, ok, required, exitCode}]`).
         *
         * This is the engine's own "is the tree actually done" answer, recorded when
         * the tool result is parsed rather than when the model claims completion.
         */
        const val TYPE_VERIFY_VERDICT = "verify.verdict"

        /**
         * One completion-report evaluation (`detail`: `passed`, `strict`,
         * `violations[]`, `unlistedFiles[]`, `requiredGates[]`).
         *
         * Emitted for a pass as well as a rejection: a supervisor needs to see the
         * gate was actually consulted, not infer it from the absence of a failure.
         */
        const val TYPE_GATE_EVALUATED = "gate.evaluated"

        /** A verification failure was fed back for another round (`detail`: `round`, `maxRounds`, `reason`). */
        const val TYPE_REPAIR_ROUND = "repair.round"

        /** The repair budget is spent while verification still fails (`detail`: `roundsUsed`, `verdict`). */
        const val TYPE_REPAIR_EXHAUSTED = "repair.exhausted"

        /** A completion report omitted files the run wrote, and one repair round was asked for (`detail`: `round`, `missing[]`). */
        const val TYPE_FINISH_REPAIR = "finish.repair"

        /** The report's `filesChanged` was completed from the run's mutation trace (`detail`: `added[]`, `source`). */
        const val TYPE_FINISH_AMEND = "finish.amend"

        /**
         * The engine injected a course-correction instruction into the run
         * (`detail`: `kind` = `coding` (with `readOnlyRounds`) or `budget` (with
         * `percent`, `consumed`, `limit`)).
         */
        const val TYPE_NUDGE = "nudge"

        /**
         * The isolation gate's merge decision (`detail`: `decision` = `applied|refused`,
         * `changedFiles`, `reason`). It used to be visible only after the task ended,
         * via `AgentTaskStatus.isolationMerge`.
         */
        const val TYPE_ISOLATION_MERGE = "isolation.merge"

        /**
         * A `coding.requestAccess` call is waiting for an operator (`detail`: `requestId`,
         * `effect`, `target`, `reason`, `requestedBy`).
         *
         * The queue was observable only by polling `GET /api/agent/{taskId}/access`, so an
         * unattended supervisor could not tell that the agent was BLOCKED on an approval
         * rather than working (issue #652, P1-6).
         */
        const val TYPE_ACCESS_REQUESTED = "access.requested"

        /** A pending access request was approved or denied (`detail`: `requestId`, `decision`, `reason`). */
        const val TYPE_ACCESS_RESOLVED = "access.resolved"

        /**
         * Version stamped on every event this build writes (see
         * [AgentTaskEvent.schemaVersion]).
         *
         * Bumped when the meaning of an existing field or `detail` payload changes; a
         * new event type alone does not need a bump, because unknown types are ignored
         * by construction.
         */
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Aggregate counters of one agent task's run — what the interactive session's
 * `/stats` command (and `GET /api/commands/{id}/stats`) reports.
 *
 * The counters live next to the bounded event log instead of being derived from
 * its contents: [AgentTaskEventLog] retains only the newest
 * [AgentTaskEventLog.DEFAULT_CAPACITY] steps, so a client counting the steps it
 * received from a long coding run would silently undercount every step that fell
 * out of the buffer. These counters are monotonic for the life of the log.
 *
 * Only the CLI agent engine reports [rounds], [modelRequests] and the token
 * counters; the deprecated observe-act engine emits lifecycle steps only, so
 * those numbers stay 0 for it.
 *
 * @property rounds outer agent rounds: one `round.start` step per round, each of
 *   which may issue several model requests (a tool-calling round loops)
 * @property modelRequests successful model requests (`run.usage` steps), i.e.
 *   how many LLM API calls the run made — context-compaction calls included
 * @property toolCalls tool calls the model requested (`tool.call` steps, meta
 *   discovery tools included)
 * @property toolFailures tool calls that reported failure (`tool.result` with
 *   `ok=false`)
 * @property steerMessages user steering messages injected into the run
 * @property inputTokens summed prompt tokens of the run, prompt-cache reads and
 *   writes included
 * @property outputTokens summed provider-reported output tokens
 * @property totalTokens summed accounted totals ([inputTokens] + output); a
 *   provider that omits the total is accounted as input + output
 * @property cachedInputTokens summed prompt tokens served from the provider cache
 * @property cacheWriteInputTokens summed prompt tokens written to the provider cache
 * @property cacheReported whether any model request reported prompt-cache details;
 *   `false` means the cache counters are *unknown*, not zero
 * @property events every step ever appended (retained plus evicted)
 * @property droppedEvents steps that fell out of the bounded buffer
 * @property firstEpochMillis wall-clock time of the first step
 * @property lastEpochMillis wall-clock time of the newest step
 * @property toolCounts per-tool call counts, most-called first, capped to
 *   [MAX_TOOL_BREAKDOWN] entries
 * @property mutations the run's newest reported file-mutation count (a gauge, not a
 *   sum; `null` until the engine reported one — a browsing task never does)
 * @property readOnlyRounds the newest reported count of consecutive coding rounds
 *   without a file mutation (`null` when the engine never reported it)
 * */
data class AgentTaskRunStats(
    val rounds: Long = 0,
    val modelRequests: Long = 0,
    val toolCalls: Long = 0,
    val toolFailures: Long = 0,
    val steerMessages: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    /** Summed prompt tokens served from the provider cache ([cacheReported] gates it). */
    val cachedInputTokens: Long = 0,
    /** Summed prompt tokens written to the provider cache (Anthropic-style APIs). */
    val cacheWriteInputTokens: Long = 0,
    /** True when at least one model request reported prompt-cache details. */
    val cacheReported: Boolean = false,
    val events: Long = 0,
    val droppedEvents: Long = 0,
    val firstEpochMillis: Long? = null,
    val lastEpochMillis: Long? = null,
    val toolCounts: Map<String, Long> = emptyMap(),
    /**
     * The run's newest file-mutation count (gauge), or `null` when the engine never
     * reported one.
     *
     * The pair "tool calls vs files changed" is the question `/stats` has to answer
     * **while the run is in flight**: 175 tool calls and 0 mutations is a run that
     * will not converge, and it used to be visible only by comparing workspace
     * mtimes by hand.
     */
    val mutations: Long? = null,
    /** Newest count of consecutive coding rounds without a mutation (gauge). */
    val readOnlyRounds: Int? = null,
) {
    /**
     * True when any token counter is non-zero.
     *
     * A provider may report no usage at all (a local model, a gateway that drops
     * the usage block): the run then made [modelRequests] calls but the token
     * numbers are *unknown*, not zero, and a report must not claim "0 tokens".
     * */
    val tokensReported: Boolean
        get() = inputTokens > 0 || outputTokens > 0 || totalTokens > 0

    /**
     * Share of the run's prompt tokens served from the provider cache, `0.0..1.0`.
     *
     * `null` when no request reported cache details (an unreported cache is not a
     * 0% hit rate) or when the run processed no prompt tokens at all.
     * */
    val cacheHitRatio: Double?
        get() = if (cacheReported && inputTokens > 0) {
            cachedInputTokens.toDouble() / inputTokens
        } else {
            null
        }

    companion object {
        /** How many distinct tools [toolCounts] keeps (the most-called ones). */
        const val MAX_TOOL_BREAKDOWN = 20
    }
}

/**
 * Mutable accumulator behind [AgentTaskRunStats].
 *
 * Kept private to the log so a snapshot can never be mutated by a later step.
 * */
private class RunStatsAccumulator {
    var rounds = 0L
    var modelRequests = 0L
    var toolCalls = 0L
    var toolFailures = 0L
    var steerMessages = 0L
    var inputTokens = 0L
    var outputTokens = 0L
    var totalTokens = 0L
    var cachedInputTokens = 0L
    var cacheWriteInputTokens = 0L
    var cacheReported = false
    var events = 0L
    var firstEpochMillis: Long? = null
    var lastEpochMillis: Long? = null
    var mutations: Long? = null
    var readOnlyRounds: Int? = null
    val toolCounts = HashMap<String, Long>()

    fun record(
        type: String,
        tool: String?,
        ok: Boolean?,
        inputTokens: Int?,
        outputTokens: Int?,
        totalTokens: Int?,
        cachedInputTokens: Int?,
        cacheWriteInputTokens: Int?,
        mutations: Long?,
        readOnlyRounds: Int?,
        epochMillis: Long,
    ) {
        events++
        if (firstEpochMillis == null) firstEpochMillis = epochMillis
        lastEpochMillis = epochMillis
        // Progress gauges: a `round.start` reports the run's CURRENT cumulative
        // mutation count and read-only streak, so the newest reported value wins.
        // Summing them would report 1+2+3 files for a run that changed three.
        mutations?.let { this.mutations = it }
        readOnlyRounds?.let { this.readOnlyRounds = it }
        when (type) {
            AgentTaskEvent.TYPE_ROUND -> rounds++
            AgentTaskEvent.TYPE_TOOL_CALL -> {
                toolCalls++
                val name = tool?.takeIf { it.isNotBlank() } ?: "unknown"
                toolCounts[name] = (toolCounts[name] ?: 0L) + 1L
            }

            AgentTaskEvent.TYPE_TOOL_RESULT -> if (ok == false) toolFailures++
            AgentTaskEvent.TYPE_STEER -> steerMessages++
            AgentTaskEvent.TYPE_USAGE -> {
                modelRequests++
                val input = (inputTokens ?: 0).coerceAtLeast(0)
                val output = (outputTokens ?: 0).coerceAtLeast(0)
                this.inputTokens += input
                this.outputTokens += output
                // A provider that reports only input/output must still be
                // accounted: `total` is input + output, never "-1"/absent.
                this.totalTokens += (totalTokens ?: (input + output)).coerceAtLeast(0)
                // Cache counters are only meaningful when the provider reported
                // them: a null on every request must stay "unknown" (0% would be a
                // lie), so a single reported value flips the flag for the run.
                cachedInputTokens?.let {
                    this.cachedInputTokens += it.coerceAtLeast(0)
                    cacheReported = true
                }
                cacheWriteInputTokens?.let {
                    this.cacheWriteInputTokens += it.coerceAtLeast(0)
                    cacheReported = true
                }
            }
        }
    }

    fun snapshot(dropped: Long): AgentTaskRunStats = AgentTaskRunStats(
        rounds = rounds,
        modelRequests = modelRequests,
        toolCalls = toolCalls,
        toolFailures = toolFailures,
        steerMessages = steerMessages,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        cachedInputTokens = cachedInputTokens,
        cacheWriteInputTokens = cacheWriteInputTokens,
        cacheReported = cacheReported,
        events = events,
        droppedEvents = dropped,
        firstEpochMillis = firstEpochMillis,
        lastEpochMillis = lastEpochMillis,
        toolCounts = toolCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(AgentTaskRunStats.MAX_TOOL_BREAKDOWN)
            .associateTo(LinkedHashMap()) { it.key to it.value },
        mutations = mutations,
        readOnlyRounds = readOnlyRounds,
    )
}

/**
 * Bounded, thread-safe ring buffer of the events of ONE agent task.
 *
 * Capacity-bounded because a coding run can emit thousands of tool events and
 * nobody replays more than the recent tail: when the buffer is full the oldest
 * event is dropped ([droppedCount] records how many), while [lastSeq] keeps
 * growing so a cursor never stalls.
 *
 * The run's aggregate counters ([stats]) are maintained as events are appended,
 * so they stay exact even after their steps have been evicted.
 *
 * The log itself is memory-only, but it is not the last copy: [onAdopt] lets the task
 * runner mirror every step to [AgentTaskEventStore], and [restore] adopts what a previous
 * process wrote — with the original sequence numbers, so a client's cursor survives the
 * restart. A restored log's [droppedCount] starts at 0: the events evicted before the
 * restart are already gone from the shard (it is rotated to the same window).
 *
 * @param capacity maximum number of retained events (default [DEFAULT_CAPACITY])
 * */
class AgentTaskEventLog(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "AgentTaskEventLog capacity must be positive: $capacity" }
    }

    private val events = ArrayDeque<AgentTaskEvent>(minOf(capacity, 256))
    private var nextSeq: Long = 1L
    private var dropped: Long = 0L
    private val accumulator = RunStatsAccumulator()

    /**
     * Called with every event this log adopts, after it is stored.
     *
     * The runner installs a persister here ([AgentTaskEventStore]) so a restart can restore the
     * process, not only the status row. Kept as a listener rather than a constructor argument:
     * the log is usable (and cheap) without any sink, and tests exercise it that way.
     */
    @Volatile
    var onAdopt: ((AgentTaskEvent) -> Unit)? = null

    /**
     * Append one event, assigning it the next sequence number.
     *
     * @return the event as stored (with its assigned `seq` and timestamp)
     * */
    @Synchronized
    fun append(
        type: String,
        tool: String? = null,
        arguments: String? = null,
        ok: Boolean? = null,
        durationMs: Long? = null,
        text: String? = null,
        turn: Int? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        totalTokens: Int? = null,
        cachedInputTokens: Int? = null,
        cacheWriteInputTokens: Int? = null,
        budgetTokens: Long? = null,
        cumulativeTokens: Long? = null,
        errorCode: String? = null,
        resumable: Boolean? = null,
        callId: String? = null,
        detail: Map<String, Any?>? = null,
        mutations: Long? = null,
        readOnlyRounds: Int? = null,
        schemaVersion: Int? = AgentTaskEvent.SCHEMA_VERSION,
        epochMillis: Long = System.currentTimeMillis(),
    ): AgentTaskEvent {
        // The log is the last point before serialization, so the "no unpaired
        // surrogate" invariant is enforced here for every string field: a strict
        // JSON follower must be able to parse the page whichever caller wrote
        // which field (see AgentTaskEventSanitizer's KDoc). The repair returns the
        // same instance for well-formed text, so the common path is unchanged.
        val event = AgentTaskEvent(
            seq = nextSeq++,
            type = AgentTaskEventSanitizer.replaceUnpairedSurrogates(type),
            epochMillis = epochMillis,
            tool = tool?.let(AgentTaskEventSanitizer::replaceUnpairedSurrogates),
            arguments = arguments?.let(AgentTaskEventSanitizer::replaceUnpairedSurrogates),
            ok = ok,
            durationMs = durationMs,
            text = text?.let(AgentTaskEventSanitizer::replaceUnpairedSurrogates),
            turn = turn,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
            cachedInputTokens = cachedInputTokens,
            cacheWriteInputTokens = cacheWriteInputTokens,
            budgetTokens = budgetTokens,
            cumulativeTokens = cumulativeTokens,
            errorCode = errorCode,
            resumable = resumable,
            callId = callId?.let(AgentTaskEventSanitizer::replaceUnpairedSurrogates),
            // The decision payload is attacker-adjacent in the same way arguments
            // are (tool text flows into it), so it is redacted and bounded here —
            // the one place every producer goes through.
            detail = AgentTaskEventSanitizer.detail(detail),
            schemaVersion = schemaVersion,
            mutations = mutations,
            readOnlyRounds = readOnlyRounds,
        )
        accumulator.record(
            type, tool, ok, inputTokens, outputTokens, totalTokens,
            cachedInputTokens, cacheWriteInputTokens, mutations, readOnlyRounds, epochMillis,
        )
        events.addLast(event)
        while (events.size > capacity) {
            events.removeFirst()
            dropped++
        }
        // Best effort: a persister failure must never fail the run it observes.
        onAdopt?.let { sink -> runCatching { sink(event) } }
        return event
    }

    /**
     * Adopt events read back from disk, preserving their sequence numbers.
     *
     * Used when this task's log is (re)built for a task that ran before a backend restart: the
     * restored steps keep their `seq`, so a client's cursor still means what it meant, and the
     * aggregate counters ([stats]) are recomputed from them.
     *
     * Restored events are **not** re-serialized through [append]: they were sanitized when they
     * were first written, and re-previewing a preview would truncate the payload twice.
     *
     * @param restored the persisted events, oldest first (already ordered by `seq`)
     * @return how many events were adopted
     */
    @Synchronized
    fun restore(restored: List<AgentTaskEvent>): Int {
        if (restored.isEmpty()) return 0
        var adopted = 0
        for (event in restored.sortedBy { it.seq }) {
            // A shard can overlap what this log already holds (a reader restored it while the
            // task was still appending): only strictly newer steps are adopted.
            if (event.seq < nextSeq) continue
            nextSeq = event.seq + 1
            accumulator.record(
                event.type, event.tool, event.ok, event.inputTokens, event.outputTokens,
                event.totalTokens, event.cachedInputTokens, event.cacheWriteInputTokens,
                event.mutations, event.readOnlyRounds, event.epochMillis,
            )
            events.addLast(event)
            while (events.size > capacity) {
                events.removeFirst()
                // An eviction here is the same loss a live log has: the log is bounded, and a
                // reader that fell behind is told about it through `dropped`.
                dropped++
            }
            adopted++
        }
        return adopted
    }

    /**
     * The retained events with `seq` greater than [afterSeq], oldest first.
     *
     * A cursor older than the buffer's tail simply gets what is still retained:
     * the gap is [droppedCount], never an error (a client that fell behind must
     * still be able to render the recent tail).
     * */
    @Synchronized
    fun eventsAfter(afterSeq: Long, limit: Int = DEFAULT_PAGE_LIMIT): List<AgentTaskEvent> {
        if (limit <= 0) return emptyList()
        val page = ArrayList<AgentTaskEvent>(minOf(limit, events.size))
        for (event in events) {
            if (event.seq <= afterSeq) continue
            page += event
            if (page.size >= limit) break
        }
        return page
    }

    /** Sequence number of the newest event (0 when nothing was appended yet). */
    @Synchronized
    fun lastSeq(): Long = nextSeq - 1

    /**
     * Sequence number of the OLDEST retained event, or 0 when the log is empty.
     *
     * A follower compares its cursor with this to learn that it fell behind the
     * retained window: without it, "you missed 300 steps" and "nothing new happened"
     * arrive as the same empty page.
     */
    @Synchronized
    fun firstSeq(): Long = events.firstOrNull()?.seq ?: 0L

    /** Number of retained events. */
    @Synchronized
    fun size(): Int = events.size

    /** How many events fell out of the buffer. */
    @Synchronized
    fun droppedCount(): Long = dropped

    /**
     * The newest retained event of [type], or `null` when none is retained.
     *
     * Used at the terminal transition to assemble a failed task's structured evidence
     * ([ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus.failureDetail])
     * from the decision events that were recorded moments earlier — the log is the
     * channel both the agent and the runner write to, so no cross-layer plumbing is
     * needed. Scans from the tail and stops at the first match (n ≤ capacity).
     */
    @Synchronized
    fun lastOfType(type: String): AgentTaskEvent? = events.lastOrNull { it.type == type }

    /**
     * One page of this log plus the cursor-honesty facts.
     *
     * `resynced` is true when [afterSeq] fell behind the retained window: the page is
     * the retained tail, not a continuation of what the caller has already seen. An
     * empty page still returns the caller's own cursor as
     * [AgentTaskEventPage.nextSeq] so a poll is idempotent.
     *
     * @param afterSeq cursor: return events with a strictly greater sequence
     * @param limit page size
     */
    @Synchronized
    fun page(afterSeq: Long, limit: Int = DEFAULT_PAGE_LIMIT): AgentTaskEventPage {
        val retained = eventsAfter(afterSeq, limit)
        val first = events.firstOrNull()?.seq ?: 0L
        return AgentTaskEventPage(
            events = retained,
            nextSeq = retained.lastOrNull()?.seq ?: afterSeq,
            firstRetainedSeq = first,
            lastSeq = nextSeq - 1,
            dropped = dropped,
            // A cursor of 0 means "from the beginning": the caller never claimed to
            // have seen anything, so nothing can be missing relative to it.
            resynced = afterSeq > 0 && first > 0 && afterSeq < first - 1,
        )
    }

    /**
     * The run's aggregate counters as of now.
     *
     * Exact even for steps the buffer already evicted ([AgentTaskRunStats.events]
     * counts every append, [AgentTaskRunStats.droppedEvents] the evicted ones).
     * */
    @Synchronized
    fun stats(): AgentTaskRunStats = accumulator.snapshot(dropped)

    companion object {
        /** Maximum retained events per task (240-char previews ⇒ ≈1 MB worst case). */
        const val DEFAULT_CAPACITY = 1000

        /** Default page size of `GET /api/commands/{id}/events`. */
        const val DEFAULT_PAGE_LIMIT = 200
    }
}

/**
 * One page of an agent task's step log, with the facts a follower needs to know
 * whether it is still following a continuous stream.
 *
 * The cursor contract used to be unable to express "you fell behind": when `after`
 * pointed before the retained window the endpoint returned an empty page with the
 * cursor unchanged — indistinguishable from "no new steps". A supervisor that slept
 * past the 1000-event ring silently lost steps and could conclude the agent stalled.
 *
 * @property events the retained steps after the requested cursor, oldest first
 * @property nextSeq cursor to pass as `after` next time; the requested cursor on an
 *   empty page, so polling is idempotent
 * @property firstRetainedSeq sequence number of the oldest retained step, `0` when
 *   the log is empty
 * @property lastSeq sequence number of the newest step ever appended
 * @property dropped steps evicted from the bounded buffer over the log's lifetime
 * @property resynced true when the cursor had fallen behind the retained window: the
 *   page is the retained tail, not a continuation (the caller must re-read, not append)
 * */
data class AgentTaskEventPage(
    val events: List<AgentTaskEvent> = emptyList(),
    val nextSeq: Long = 0L,
    val firstRetainedSeq: Long = 0L,
    val lastSeq: Long = 0L,
    val dropped: Long = 0L,
    val resynced: Boolean = false,
)

/**
 * Truncation and secret redaction for [AgentTaskEvent] payloads.
 *
 * Tool arguments are attacker-adjacent data: a `fill` call carries whatever the
 * user typed into the page, and a coding task may pass a token to a shell
 * command. The event stream is rendered into terminals, written to CI logs and
 * (for `--ndjson`) piped to other agents, so it must not echo credentials.
 *
 * Two rules:
 * 1. arguments whose **key** names a credential are replaced with `***`,
 *    including values nested in objects/arrays;
 * 2. `fill`/`type` text is redacted when its selector names a credential field
 *    (`input[type=password]`, `#api_token`, …) — the value cannot be classified
 *    from its key alone, and the selector is a reliable hint.
 *
 * Everything is then collapsed to one line and truncated, so a 200 KB page
 * snapshot cannot bloat the buffer.
 *
 * ## Why a preview must stay valid JSON
 *
 * The preview is served over `GET /api/commands/{id}/events` and parsed by
 * followers with a strict JSON parser (the Rust CLI's `serde_json`). Jackson
 * serializes an **unpaired** UTF-16 surrogate as the escape `\uD83D`, and a
 * strict parser rejects the whole page with "unexpected end of hex escape" —
 * one truncated emoji used to poison the response, stall the follower for its
 * whole retry window and end the live step view of a running task
 * ([issue #620](https://github.com/platonai/Browser4/issues/620)).
 *
 * So no preview may contain an unpaired surrogate, which means:
 * - [preview] never cuts a code point in half (it truncates **between** code
 *   points, so a surrogate pair survives or is not written at all);
 * - unpaired surrogates that arrive in the source text (a provider, CDP) are
 *   replaced with `U+FFFD` rather than escaped into the event stream.
 * */
object AgentTaskEventSanitizer {

    /** Preview length of `arguments` / `text` in one event. */
    const val MAX_PREVIEW_CHARS = 240

    /** Serialized size ceiling of one event's `detail` payload. */
    const val MAX_DETAIL_BYTES = 4096

    /** Maximum keys of one `detail` map (the outermost or a nested one). */
    const val MAX_DETAIL_KEYS = 12

    /** Maximum items of one array inside `detail`. */
    const val MAX_DETAIL_ITEMS = 20

    /** Maximum nesting level of collections inside `detail`. */
    const val MAX_DETAIL_DEPTH = 2

    /** Marker key set when a `detail` payload had to be trimmed. */
    const val DETAIL_TRUNCATED = "truncated"

    /** Placeholder for a redacted value. */
    const val REDACTED = "***"

    /** Tools whose values are what the user typed into the page. */
    private val TEXT_INPUT_TOOLS = setOf("fill", "type", "insertText", "insert_text", "press")

    /** Argument keys that hold a free-text value for a text-input tool. */
    private val TEXT_INPUT_KEYS = setOf("text", "value")

    /**
     * Argument keys that name a credential, compared lower-cased with `_`/`-`
     * stripped, so `apiKey`, `api_key` and `api-key` all match.
     * */
    private val SENSITIVE_KEYS = setOf(
        "password", "passwd", "pwd", "secret", "token",
        "apikey", "accesstoken", "refreshtoken", "authorization",
        "cookie", "setcookie", "creditcard", "cardnumber", "cvv", "securitycode",
    )

    /**
     * Selector hints for a credential field. A text-input tool targeting one of
     * these has its typed text redacted instead of previewed.
     * */
    private val SENSITIVE_SELECTOR_HINTS = listOf(
        "password", "passwd", "pwd", "secret", "token", "api-key", "api_key",
        "apikey", "cvv", "credit-card", "cardnumber",
    )

    /** Fallback redaction for argument blobs that are not valid JSON. */
    private val INLINE_SECRET = Regex(
        """(?i)("(?:[^"]*?(?:password|passwd|pwd|secret|token|api_?key|authorization|cookie)[^"]*?)"\s*:\s*)"(?:[^"\\]|\\.)*""""
    )

    /**
     * Fallback redaction for CLI-style flags — a `coding.shell` command such as
     * `cli --password hunter2 --token abc` is as sensitive as a JSON body, and
     * it never matches [INLINE_SECRET].
     * */
    private val INLINE_FLAG_SECRET = Regex(
        """(?i)(--?(?:password|passwd|pwd|secret|token|api[-_]?key|authorization|cookie)[=\s]+)("[^"]*"|'[^']*'|\S+)"""
    )

    private val WHITESPACE = Regex("""\s+""")

    /** Replacement for a surrogate that has no partner. */
    const val REPLACEMENT_CHARACTER = "\uFFFD"

    /**
     * Collapse [text] to a single trimmed line no longer than [maxChars].
     *
     * Newlines are collapsed because the event stream is line-oriented: a
     * multi-line tool result would break `--follow` rendering and NDJSON
     * framing alike. `null`/blank input returns `null` rather than `""`, so the
     * JSON payload omits the field instead of carrying an empty string.
     *
     * The result is always serializable as strict JSON: unpaired surrogates are
     * replaced with [REPLACEMENT_CHARACTER] (see the class KDoc), and the
     * truncation lands on a code-point boundary so a surrogate pair is never
     * cut in half. [maxChars] still counts UTF-16 code units, so a preview is
     * never longer than `maxChars + 1`; backing off one unit can only shorten
     * it.
     * */
    fun preview(text: String?, maxChars: Int = MAX_PREVIEW_CHARS): String? {
        val collapsed = text?.replace(WHITESPACE, " ")?.trim() ?: return null
        if (collapsed.isEmpty()) return null
        val whole = replaceUnpairedSurrogates(collapsed)
        if (whole.length <= maxChars) return whole
        // Cutting between the halves of a surrogate pair would leave an unpaired
        // high surrogate behind — the `\uD83D` that breaks a strict JSON parser.
        val end = maxChars.coerceIn(0, whole.length).let { limit ->
            if (limit > 0 && Character.isHighSurrogate(whole[limit - 1])) limit - 1 else limit
        }
        val trimmed = whole.substring(0, end).trimEnd()
        return if (trimmed.isEmpty()) "…" else trimmed + "…"
    }

    /**
     * Preview that keeps the **end** of a long text as well as its beginning.
     *
     * Tool output is read for its verdict, not its preamble: a compiler error, a
     * `Tests run: 12, Failures: 1 …` line and a shell's exit message all sit at the
     * tail, while the head is usually a session banner (`Session: devshell-3`). The
     * plain [preview] keeps the head only, so the one line a supervisor needed was
     * exactly the part cut away (issue #652, P1-1: "the 240-char preview cuts off the
     * two things supervision needs most").
     *
     * The result is `head…tail`: still one line, still within the budget, still free of
     * unpaired surrogates (both cuts land on code-point boundaries).
     *
     * @param maxChars total budget in UTF-16 code units (the head and tail share it)
     * @param tailShare fraction of the budget the tail gets (`0.45` = the failing lines
     *   survive while the first ~130 characters of context stay readable)
     */
    fun previewKeepingTail(
        text: String?,
        maxChars: Int = MAX_PREVIEW_CHARS,
        tailShare: Double = 0.45,
    ): String? {
        val collapsed = text?.replace(WHITESPACE, " ")?.trim() ?: return null
        if (collapsed.isEmpty()) return null
        val whole = replaceUnpairedSurrogates(collapsed)
        if (whole.length <= maxChars) return whole

        val share = tailShare.coerceIn(0.1, 0.8)
        val tailBudget = (maxChars * share).toInt().coerceIn(1, maxChars)
        val headBudget = (maxChars - tailBudget - 1).coerceAtLeast(1)

        val head = cutHead(whole, headBudget)
        val tail = cutTail(whole, tailBudget)
        return if (head.isEmpty()) "…$tail" else "$head…$tail"
    }

    /** The first [budget] code units of [text], without splitting a surrogate pair. */
    private fun cutHead(text: String, budget: Int): String {
        if (budget <= 0) return ""
        var end = budget.coerceAtMost(text.length)
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end -= 1
        return text.substring(0, end).trimEnd()
    }

    /** The last [budget] code units of [text], without starting on a low surrogate. */
    private fun cutTail(text: String, budget: Int): String {
        if (budget <= 0) return ""
        var start = (text.length - budget).coerceAtLeast(0)
        if (start > 0 && start < text.length && Character.isLowSurrogate(text[start])) start += 1
        return text.substring(start).trimStart()
    }

    /**
     * Replace every unpaired surrogate in [text] with [REPLACEMENT_CHARACTER].
     *
     * Returns the same instance when the text is already well-formed, so the
     * common path allocates nothing. A well-formed pair is kept verbatim.
     * */
    fun replaceUnpairedSurrogates(text: String): String {        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                i += 2
                continue
            }
            if (Character.isSurrogate(c)) return repairSurrogates(text, i)
            i++
        }
        return text
    }

    /** Rebuild [text], replacing every unpaired surrogate from [from] onwards. */
    private fun repairSurrogates(text: String, from: Int): String {
        val builder = StringBuilder(text.length)
        builder.append(text, 0, from)
        var index = from
        while (index < text.length) {
            val c = text[index]
            when {
                Character.isHighSurrogate(c) -> {
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        builder.append(c).append(text[index + 1])
                        index += 2
                    } else {
                        builder.append(REPLACEMENT_CHARACTER)
                        index++
                    }
                }

                Character.isLowSurrogate(c) -> {
                    builder.append(REPLACEMENT_CHARACTER)
                    index++
                }

                else -> {
                    builder.append(c)
                    index++
                }
            }
        }
        return builder.toString()
    }

    /**
     * Redact, preview and bound a decision event's structured payload.
     *
     * The payload is machine-read, so it stays a JSON object — but it is produced from
     * the same attacker-adjacent data as tool arguments (a shell command, a tool
     * result), so the same rules apply:
     *
     * 1. a value whose **key** names a credential becomes [REDACTED];
     * 2. every string is passed through [preview] (one line, ≤ [MAX_PREVIEW_CHARS]);
     * 3. collections are bounded — ≤ [MAX_DETAIL_KEYS] keys, ≤ [MAX_DETAIL_ITEMS]
     *    items, ≤ [MAX_DETAIL_DEPTH] levels — and the serialized result must fit
     *    [MAX_DETAIL_BYTES];
     * 4. whenever anything was cut, [DETAIL_TRUNCATED] is set to `true`, so a reader
     *    can tell "the payload ended" from "the payload was trimmed".
     *
     * @return a sanitized copy, or `null` when [detail] is null/empty
     * */
    fun detail(detail: Map<*, *>?): Map<String, Any?>? {
        if (detail.isNullOrEmpty()) return null
        var truncated = false
        val sanitized = sanitizeMap(detail, 0) { truncated = true } ?: return null
        if (sanitized.isEmpty()) return null
        val bounded = boundBySerializedSize(sanitized) { truncated = true }
        return if (truncated) LinkedHashMap(bounded).apply { put(DETAIL_TRUNCATED, true) } else bounded
    }

    /** One level of [detail], with nested values sanitized recursively. */
    private fun sanitizeMap(
        map: Map<*, *>,
        depth: Int,
        markTruncated: () -> Unit,
    ): Map<String, Any?>? {
        val result = LinkedHashMap<String, Any?>(minOf(map.size, MAX_DETAIL_KEYS))
        for ((rawKey, rawValue) in map) {
            val key = rawKey?.toString()?.takeIf { it.isNotBlank() } ?: continue
            if (result.size >= MAX_DETAIL_KEYS) {
                markTruncated()
                break
            }
            if (isSensitiveKey(key)) {
                result[key] = REDACTED
                continue
            }
            result[key] = sanitizeValue(rawValue, depth + 1, markTruncated)
        }
        return result
    }

    private fun sanitizeValue(value: Any?, depth: Int, markTruncated: () -> Unit): Any? = when (value) {
        null -> null
        is Boolean -> value
        // Numbers are bounded by their own representation; no preview needed.
        is Int, is Long, is Short, is Byte, is Double, is Float -> value
        is CharSequence -> redactRaw(value.toString()).let { preview(it) }
        is Map<*, *> -> if (depth > MAX_DETAIL_DEPTH) {
            markTruncated()
            null
        } else {
            sanitizeMap(value, depth, markTruncated)
        }

        is Iterable<*> -> if (depth > MAX_DETAIL_DEPTH) {
            markTruncated()
            null
        } else {
            val items = ArrayList<Any?>(minOf(MAX_DETAIL_ITEMS, 4))
            for (item in value) {
                if (items.size >= MAX_DETAIL_ITEMS) {
                    markTruncated()
                    break
                }
                items += sanitizeValue(item, depth + 1, markTruncated)
            }
            items
        }

        is Array<*> -> sanitizeValue(value.asList(), depth, markTruncated)
        else -> redactRaw(value.toString()).let { preview(it) }
    }

    /**
     * Shrink a structurally-valid payload until it serializes within [MAX_DETAIL_BYTES].
     *
     * The structural limits bound the common case; this one bounds the worst case (12
     * keys × 20 items × 240 chars each ≈ 58 KB), which a single decision event must never
     * push into a 1000-event ring.
     *
     * Degradation order is "keep the meaning, drop the volume": the long arrays are
     * halved first (a verdict's check list is still readable with fewer entries), and only
     * trailing top-level keys are dropped when even that is not enough.
     */
    private fun boundBySerializedSize(
        map: Map<String, Any?>,
        markTruncated: () -> Unit,
    ): Map<String, Any?> {
        if (serializedSize(map) <= MAX_DETAIL_BYTES) return map
        markTruncated()

        var current: Map<String, Any?> = map
        var itemCap = MAX_DETAIL_ITEMS / 2
        while (itemCap >= 1 && serializedSize(current) > MAX_DETAIL_BYTES) {
            current = trimArrays(current, itemCap)
            itemCap /= 2
        }

        val trimmed = LinkedHashMap(current)
        while (trimmed.size > 1 && serializedSize(trimmed) > MAX_DETAIL_BYTES) {
            trimmed.remove(trimmed.keys.last())
        }
        return trimmed
    }

    /** Every list in [map] (nested maps included) cut to [cap] items. */
    private fun trimArrays(map: Map<String, Any?>, cap: Int): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(map.size)
        map.forEach { (key, value) ->
            result[key] = when (value) {
                is List<*> -> value.take(cap)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    trimArrays(value as Map<String, Any?>, cap)
                }

                else -> value
            }
        }
        return result
    }

    private fun serializedSize(map: Map<String, Any?>): Int = runCatching {
        pulsarObjectMapper().writeValueAsBytes(map).size
    }.getOrDefault(0)

    /**
     * Redact and truncate a tool's JSON arguments for the event stream.
     *
     * Invalid JSON is not rejected: the CLI tool arguments are JSON in practice,
     * but a preview must never fail the tool call, so a regex fallback redacts
     * `"password": "…"`-shaped pairs in whatever text arrived.
     * */
    fun arguments(tool: String?, argumentsJson: String?): String? {
        val raw = argumentsJson?.trim() ?: return null
        if (raw.isEmpty() || raw == "{}") return null
        val redacted = redactJson(tool, raw) ?: redactRaw(raw)
        return preview(redacted)
    }

    /** True when [key] names a credential (case/`_`/`-` insensitive). */
    fun isSensitiveKey(key: String): Boolean =
        // A nested key may arrive dotted (`credentials.token`): the last segment
        // is the part that names the value.
        normalizeKey(key.substringAfterLast('.')) in SENSITIVE_KEYS

    /** True when the argument value of a text-input tool must be hidden. */
    fun isSensitiveTextInput(tool: String?, key: String, selector: String?): Boolean {
        if (tool !in TEXT_INPUT_TOOLS) return false
        if (normalizeKey(key) !in TEXT_INPUT_KEYS) return false
        val hint = selector?.lowercase() ?: return false
        return SENSITIVE_SELECTOR_HINTS.any { hint.contains(it) }
    }

    private fun normalizeKey(key: String): String =
        key.lowercase().replace("_", "").replace("-", "")

    private fun redactJson(tool: String?, raw: String): String? = runCatching {
        val mapper = pulsarObjectMapper()
        val node = mapper.readTree(raw) ?: return null
        val selector = (node as? ObjectNode)?.let { root ->
            listOf("selector", "ref", "element")
                .firstNotNullOfOrNull { key -> root.get(key)?.takeIf { it.isTextual }?.asText() }
        }
        redactNode(node, tool, selector)
        mapper.writeValueAsString(node)
    }.getOrNull()

    private fun redactNode(node: JsonNode?, tool: String?, selector: String?) {
        when {
            node == null -> return
            node.isObject -> {
                // Collect the names before mutating: rewriting a value while
                // iterating `fields()` happens to be safe in today's Jackson, but
                // it is exactly the kind of assumption that breaks on upgrade.
                val objectNode = node as ObjectNode
                val names = objectNode.fieldNames().asSequence().toList()
                for (name in names) {
                    val value = objectNode.get(name) ?: continue
                    when {
                        isSensitiveKey(name) -> objectNode.put(name, REDACTED)
                        isSensitiveTextInput(tool, name, selector) -> objectNode.put(name, REDACTED)
                        else -> redactNode(value, tool, selector)
                    }
                }
            }

            node.isArray -> node.forEach { child -> redactNode(child, tool, selector) }
        }
    }

    private fun redactRaw(raw: String): String {
        val jsonish = INLINE_SECRET.replace(raw) { match ->
            "${match.groupValues[1]}\"$REDACTED\""
        }
        return INLINE_FLAG_SECRET.replace(jsonish) { match ->
            "${match.groupValues[1]}$REDACTED"
        }
    }
}
