package ai.platon.pulsar.agentic.tools

/**
 * The async contract every long-running domain shares (requirement 11).
 *
 * A submit tool returns the **submit envelope** immediately; the client then polls
 * the `statusTool` from it and reads the payload from the `resultTool`, or stops
 * the work through the `cancelTool`. The status tools answer with the **status
 * envelope**, which is the same shape for every domain so a generic client needs
 * no per-domain knowledge:
 *
 * ```json
 * {"taskId": "…", "status": "running", "progress": null,
 *  "processed": 3, "elapsedMs": 1204, "statusTool": "crawl_status"}
 * ```
 *
 * `progress` is only present when the total is known; otherwise `processed` is the
 * monotonic counter a client can watch. Both vocabularies live here rather than in
 * each executor, because the whole point of the contract is that it is shared —
 * the specs and their `outputSchema`s are generated from these strings.
 */
object TaskEnvelopes {

    /** The closed status vocabulary. `queued` covers "submitted, not started yet". */
    val STATUSES = listOf("queued", "running", "done", "failed", "cancelled")

    /** Statuses after which a client must stop polling. */
    val TERMINAL_STATUSES = setOf("done", "failed", "cancelled")

    /**
     * The enum body shared by both schemas.
     *
     * Built in its own property rather than inline in the schema templates: a
     * `joinToString` lambda inside a raw-string `${…}` template is easy to get
     * wrong (an unbalanced brace silently truncates the schema), and a truncated
     * schema disables validation instead of failing loudly.
     */
    private val STATUS_ENUM: String = STATUSES.joinToString(", ") { "\"$it\"" }

    /** JSON Schema of the envelope a submit tool returns; also its text contract. */
    val SUBMIT_SCHEMA = """
        {
          "type": "object",
          "required": ["taskId", "status", "statusTool"],
          "properties": {
            "taskId": {"type": "string"},
            "status": {"type": "string", "enum": [$STATUS_ENUM]},
            "pollAfterMs": {"type": "integer"},
            "statusTool": {"type": "string"},
            "resultTool": {"type": "string"},
            "cancelTool": {"type": "string"}
          }
        }
    """.trimIndent()

    /** JSON Schema of the envelope a status/result/cancel tool returns. */
    val STATUS_SCHEMA = """
        {
          "type": "object",
          "required": ["taskId", "status"],
          "properties": {
            "taskId": {"type": "string"},
            "status": {"type": "string", "enum": [$STATUS_ENUM]},
            "progress": {"type": "number"},
            "processed": {"type": "integer"},
            "total": {"type": "integer"},
            "elapsedMs": {"type": "integer"},
            "error": {"type": "string"},
            "statusTool": {"type": "string"},
            "resultTool": {"type": "string"},
            "cancelTool": {"type": "string"}
          }
        }
    """.trimIndent()

    /** Whether [status] means "do not poll again". */
    fun isTerminal(status: String?): Boolean = status?.lowercase() in TERMINAL_STATUSES

    /**
     * The status envelope as an ordered map.
     *
     * Keys with a `null` value are dropped before serialisation, so a client never
     * has to distinguish "unknown" from a placeholder zero — and a `progress` of
     * `0` (which is meaningful) is never confused with "unknown".
     *
     * @param status one of [STATUSES]; unknown values are reported as `failed`
     *   rather than silently passed through, so a new backend state cannot look
     *   like a healthy one
     */
    fun of(
        taskId: String,
        status: String,
        progress: Double? = null,
        processed: Int? = null,
        total: Int? = null,
        elapsedMs: Long? = null,
        error: String? = null,
        statusTool: String? = null,
        resultTool: String? = null,
        cancelTool: String? = null,
    ): Map<String, Any> {
        val envelope = linkedMapOf<String, Any>(
            "taskId" to taskId,
            "status" to normalise(status),
        )
        progress?.let { envelope["progress"] = it.coerceIn(0.0, 1.0) }
        processed?.let { envelope["processed"] = it }
        total?.let { envelope["total"] = it }
        elapsedMs?.let { envelope["elapsedMs"] = it }
        error?.takeIf { it.isNotBlank() }?.let { envelope["error"] = it }
        statusTool?.let { envelope["statusTool"] = it }
        resultTool?.let { envelope["resultTool"] = it }
        cancelTool?.let { envelope["cancelTool"] = it }
        return envelope
    }

    /** Maps a domain-specific status word onto the shared vocabulary. */
    fun normalise(status: String?): String {
        val value = status?.trim()?.lowercase().orEmpty()
        return when (value) {
            "queued", "created", "pending", "submitted" -> "queued"
            "running", "started", "in_progress", "in-progress", "processing", "accepted" -> "running"
            "done", "ok", "success", "succeeded", "finished", "completed" -> "done"
            "cancelled", "canceled", "aborted" -> "cancelled"
            "failed", "error", "timeout", "timed_out", "timedout", "" -> "failed"
            else -> "failed"
        }
    }
}
