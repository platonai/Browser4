package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified write path of the agent memory system (L0): engine observation
 * points → [MemoryEvent] → live buffer + append-only log.
 *
 * - [completed] / [failed] are idempotent per task (first terminal event wins).
 * - Every text field is sanitized at this boundary (see [Sanitizer]).
 * - Writes never throw: memory must not become a failure point of the agent.
 */
class AgentMemorySink(
    private val eventLog: AgentEventLog,
    private val buffer: EventBuffer,
    private val enabled: Boolean = true,
) {
    private val logger = getLogger(AgentMemorySink::class)

    /**
     * JVM-global monotonic counter: sinks of different agents (and the shared
     * backend) coexist in one process, and the search-index alignment state
     * machine relies on a strictly increasing global `seq` so that
     * "events with seq > watermark" never skips another agent's events.
     */
    private val seq = GLOBAL_SEQ

    private val finishedTasks = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private val GLOBAL_SEQ = AtomicLong(0)
    }

    fun taskStarted(
        taskId: String, agentUuid: String, instruction: String,
        engine: String, urlCandidate: String? = null,
    ) = emit(TaskStarted(seq.incrementAndGet(), now(), agentUuid, taskId, instruction, engine, urlCandidate))

    fun toolExecuted(
        taskId: String, agentUuid: String, tool: String, argsJson: String,
        ok: Boolean, resultText: String?, durationMs: Long, callId: String = "",
    ) = emit(ToolExecuted(
        seq.incrementAndGet(), now(), agentUuid, taskId, tool,
        Sanitizer.sanitizeArgsJson(argsJson), ok, Sanitizer.brief(resultText, 200), durationMs, callId,
    ))

    fun pageViewed(
        taskId: String, agentUuid: String, url: String, title: String,
        viewType: String, fingerprint: String,
    ) = emit(PageViewed(
        seq.incrementAndGet(), now(), agentUuid, taskId, url.take(300), title.take(200), viewType, fingerprint.take(64),
    ))

    fun textEmitted(taskId: String, agentUuid: String, kind: String, text: String?) =
        emit(TextEmitted(seq.incrementAndGet(), now(), agentUuid, taskId, kind, Sanitizer.brief(text, 300)))

    fun noteWritten(taskId: String, agentUuid: String, key: String, value: String) =
        emit(NoteWritten(seq.incrementAndGet(), now(), agentUuid, taskId, key, Sanitizer.brief(value, 200)))

    /** Mark the task completed (outcome `success`); idempotent per task. */
    fun completed(
        taskId: String, agentUuid: String, summary: String,
        keyFindings: List<String>? = null, filesChanged: List<String>? = null,
        problems: List<String>? = null, durationMs: Long = 0,
    ) {
        if (!finishedTasks.add(taskId)) return
        emit(Completed(
            seq.incrementAndGet(), now(), agentUuid, taskId,
            Sanitizer.brief(summary, 500), keyFindings?.take(10)?.map { Sanitizer.brief(it, 200) },
            filesChanged?.take(20), problems?.take(10)?.map { Sanitizer.brief(it, 200) },
            outcome = "success", durationMs = durationMs,
        ))
    }

    /** Mark the task failed (loop guards / exceptions; NOT user cancellation). */
    fun failed(taskId: String, agentUuid: String, errorBrief: String, failureCategory: String? = null, step: Int = 0) {
        if (!finishedTasks.add(taskId)) return
        emit(Failed(
            seq.incrementAndGet(), now(), agentUuid, taskId,
            Sanitizer.brief(errorBrief, 300), failureCategory, step,
        ))
    }

    private fun emit(event: MemoryEvent) {
        if (!enabled) return
        buffer.add(event)
        eventLog.append(event)
    }

    private fun now(): Long = System.currentTimeMillis()
}
