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

        /** The task finished successfully. */
        const val TYPE_DONE = "task.done"

        /** The task failed; `text` carries the reason. */
        const val TYPE_FAILED = "task.failed"

        /** The task was cancelled (explicit cancel, runner close, shutdown). */
        const val TYPE_CANCELLED = "task.cancelled"
    }
}

/**
 * Bounded, thread-safe ring buffer of the events of ONE agent task.
 *
 * Capacity-bounded because a coding run can emit thousands of tool events and
 * nobody replays more than the recent tail: when the buffer is full the oldest
 * event is dropped ([droppedCount] records how many), while [lastSeq] keeps
 * growing so a cursor never stalls.
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
        epochMillis: Long = System.currentTimeMillis(),
    ): AgentTaskEvent {
        val event = AgentTaskEvent(
            seq = nextSeq++,
            type = type,
            epochMillis = epochMillis,
            tool = tool,
            arguments = arguments,
            ok = ok,
            durationMs = durationMs,
            text = text,
            turn = turn,
        )
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

    /**
     * Collapse [text] to a single trimmed line no longer than [maxChars].
     *
     * Newlines are collapsed because the event stream is line-oriented: a
     * multi-line tool result would break `--follow` rendering and NDJSON
     * framing alike. `null`/blank input returns `null` rather than `""`, so the
     * JSON payload omits the field instead of carrying an empty string.
     * */
    fun preview(text: String?, maxChars: Int = MAX_PREVIEW_CHARS): String? {
        val collapsed = text?.replace(WHITESPACE, " ")?.trim() ?: return null
        if (collapsed.isEmpty()) return null
        if (collapsed.length <= maxChars) return collapsed
        return collapsed.take(maxChars).trimEnd() + "…"
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
