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
 * task status; they are deliberately **not** persisted — the JSONL row set is a
 * task registry, not a run log, and a 1000-event buffer per task would dwarf it.
 *
 * `arguments` and `text` are already truncated and secret-redacted by
 * [AgentTaskEventSanitizer] when the event enters the log.
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
 * @property inputTokens provider-reported input tokens of one model request
 *   ([TYPE_USAGE] only; `null` when the provider reported no usage at all)
 * @property outputTokens provider-reported output tokens of one model request
 * @property totalTokens provider-reported total tokens of one model request
 * @property budgetTokens the run's effective token budget ([TYPE_USAGE] only;
 *   `0` = unlimited, `null` = not reported)
 * @property cumulativeTokens tokens consumed so far, this request included
 *   ([TYPE_USAGE] only) — what a follower needs to show "1.24M / 5.0M (25%)"
 * @property errorCode machine-readable failure reason ([TYPE_FAILED] only):
 *   `TOKEN_BUDGET`, `NOOP_LIMIT`, `CANCELLED`, `TOOL_ERROR`
 * @property resumable whether `agent run --resume <id>` can continue the task
 *   ([TYPE_FAILED] only)
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
    val budgetTokens: Long? = null,
    val cumulativeTokens: Long? = null,
    val errorCode: String? = null,
    val resumable: Boolean? = null,
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
 * @property inputTokens summed provider-reported input tokens
 * @property outputTokens summed provider-reported output tokens
 * @property totalTokens summed provider-reported total tokens; a provider that
 *   omits the total is accounted as input + output
 * @property events every step ever appended (retained plus evicted)
 * @property droppedEvents steps that fell out of the bounded buffer
 * @property firstEpochMillis wall-clock time of the first step
 * @property lastEpochMillis wall-clock time of the newest step
 * @property toolCounts per-tool call counts, most-called first, capped to
 *   [MAX_TOOL_BREAKDOWN] entries
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
    val events: Long = 0,
    val droppedEvents: Long = 0,
    val firstEpochMillis: Long? = null,
    val lastEpochMillis: Long? = null,
    val toolCounts: Map<String, Long> = emptyMap(),
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
    var events = 0L
    var firstEpochMillis: Long? = null
    var lastEpochMillis: Long? = null
    val toolCounts = HashMap<String, Long>()

    fun record(
        type: String,
        tool: String?,
        ok: Boolean?,
        inputTokens: Int?,
        outputTokens: Int?,
        totalTokens: Int?,
        epochMillis: Long,
    ) {
        events++
        if (firstEpochMillis == null) firstEpochMillis = epochMillis
        lastEpochMillis = epochMillis
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
        events = events,
        droppedEvents = dropped,
        firstEpochMillis = firstEpochMillis,
        lastEpochMillis = lastEpochMillis,
        toolCounts = toolCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(AgentTaskRunStats.MAX_TOOL_BREAKDOWN)
            .associateTo(LinkedHashMap()) { it.key to it.value },
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
        budgetTokens: Long? = null,
        cumulativeTokens: Long? = null,
        errorCode: String? = null,
        resumable: Boolean? = null,
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
            budgetTokens = budgetTokens,
            cumulativeTokens = cumulativeTokens,
            errorCode = errorCode,
            resumable = resumable,
        )
        accumulator.record(type, tool, ok, inputTokens, outputTokens, totalTokens, epochMillis)
        events.addLast(event)
        while (events.size > capacity) {
            events.removeFirst()
            dropped++
        }
        return event
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

    /** Number of retained events. */
    @Synchronized
    fun size(): Int = events.size

    /** How many events fell out of the buffer. */
    @Synchronized
    fun droppedCount(): Long = dropped

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
     * Replace every unpaired surrogate in [text] with [REPLACEMENT_CHARACTER].
     *
     * Returns the same instance when the text is already well-formed, so the
     * common path allocates nothing. A well-formed pair is kept verbatim.
     * */
    fun replaceUnpairedSurrogates(text: String): String {
        var i = 0
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
