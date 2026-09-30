package ai.platon.pulsar.agentic.memory

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.time.Instant

/**
 * Unified machine-readable event schema of the generic agent memory system (L0).
 *
 * Every executed agent produces an append-only stream of these events — the
 * "what actually happened" fact layer that search, recall, and (M3) knowledge
 * consolidation read from. All text fields are sanitized at the write path
 * (see [Sanitizer]); timestamps are epoch milliseconds.
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§5.1).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = TaskStarted::class, name = "task_started"),
    JsonSubTypes.Type(value = ToolExecuted::class, name = "tool_executed"),
    JsonSubTypes.Type(value = PageViewed::class, name = "page_viewed"),
    JsonSubTypes.Type(value = TextEmitted::class, name = "text_emitted"),
    JsonSubTypes.Type(value = NoteWritten::class, name = "note_written"),
    JsonSubTypes.Type(value = Completed::class, name = "completed"),
    JsonSubTypes.Type(value = Failed::class, name = "failed"),
)
sealed class MemoryEvent {
    /** Monotonic sequence within the producing [AgentMemorySink] (per backend instance). */
    abstract val seq: Long

    /** Epoch milliseconds. */
    abstract val ts: Long

    /** The agent that produced this event. */
    abstract val agentUuid: String

    /** The execution session id this event belongs to. */
    abstract val taskId: String

    val timestamp: Instant get() = Instant.ofEpochMilli(ts)
}

/** A task (agent run) started. */
data class TaskStarted(
    override val seq: Long,
    override val ts: Long,
    override val agentUuid: String,
    override val taskId: String,
    val instruction: String,
    val engine: String,
    val urlCandidate: String? = null,
) : MemoryEvent()

/** One tool executed by the agent. */
data class ToolExecuted(
    override val seq: Long,
    override val ts: Long,
    override val agentUuid: String,
    override val taskId: String,
    val tool: String,
    val argsBrief: String,
    val ok: Boolean,
    val resultBrief: String,
    val durationMs: Long,
    val callId: String = "",
) : MemoryEvent()

/** A page view (full / reference / diff), best-effort url + title. */
data class PageViewed(
    override val seq: Long,
    override val ts: Long,
    override val agentUuid: String,
    override val taskId: String,
    val url: String,
    val title: String,
    val viewType: String,
    val fingerprint: String,
) : MemoryEvent()

/** A text message the model emitted (reasoning / report / nudge). */
data class TextEmitted(
    override val seq: Long,
    override val ts: Long,
    override val agentUuid: String,
    override val taskId: String,
    val kind: String,
    val textBrief: String,
) : MemoryEvent()

/** A scratchpad note written via memory.note / system scratchpad. */
data class NoteWritten(
    override val seq: Long,
    override val ts: Long,
    override val agentUuid: String,
    override val taskId: String,
    val key: String,
    val valueBrief: String,
) : MemoryEvent()

/** The task completed (outcome `success` by default; failure uses [Failed]). */
data class Completed(
    override val seq: Long,
    override val ts: Long,
    override val agentUuid: String,
    override val taskId: String,
    val summary: String,
    val keyFindings: List<String>? = null,
    val filesChanged: List<String>? = null,
    val problems: List<String>? = null,
    val outcome: String = "success",
    val durationMs: Long = 0,
) : MemoryEvent()

/** The task failed / was aborted by the loop guards (never user cancellation). */
data class Failed(
    override val seq: Long,
    override val ts: Long,
    override val agentUuid: String,
    override val taskId: String,
    val errorBrief: String,
    val failureCategory: String? = null,
    val step: Int = 0,
) : MemoryEvent()

fun MemoryEvent.toJson(mapper: ObjectMapper = pulsarObjectMapper()): String = mapper.writeValueAsString(this)

fun String.toMemoryEvent(mapper: ObjectMapper = pulsarObjectMapper()): MemoryEvent? =
    runCatching { mapper.readValue(this, MemoryEvent::class.java) }.getOrNull()
