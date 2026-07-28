package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.inference.AgentStateManager
import ai.platon.pulsar.agentic.inference.detail.CircuitBreaker
import ai.platon.pulsar.agentic.inference.detail.StructuredAgentLogger
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.external.ModelResponse
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persists agent execution transcripts to disk for debugging and audit trails.
 *
 * Dependencies are injected through the constructor rather than accessed from
 * the agent, keeping file I/O separate from the agent's resolution loop.
 */
class TranscriptPersister(
    private val stateManager: AgentStateManager,
    private val stateHistory: AgentHistory,
    private val slogger: StructuredAgentLogger,
    private val agentUuid: UUID,
    private val circuitBreaker: CircuitBreaker,
    private val retryCounter: AtomicInteger,
) {
    /**
     * Writes a full execution transcript to the session log directory.
     *
     * Includes session metadata, the step-by-step execution history, the final
     * LLM summary, and circuit-breaker/retry diagnostics.
     */
    fun persist(instruction: String, finalResp: ModelResponse, context: ExecutionContext) {
        runCatching {
            val ts = Instant.now().toEpochMilli()
            val path = stateManager.resolveSessionLogDir(context.sessionId).resolve("session-${ts}.log")
            slogger.info("🧾💾 Persisting execution transcript", context)
            val sb = StringBuilder()
            sb.appendLine("SESSION_ID: $agentUuid")
            sb.appendLine("TASK_ID: ${context.sessionId}")
            sb.appendLine("TIMESTAMP: ${Instant.now()}")
            sb.appendLine("INSTRUCTION: $instruction")
            sb.appendLine("RESPONSE_STATE: ${finalResp.state}")
            sb.appendLine("EXECUTION_HISTORY:")
            stateHistory.states.forEach { sb.appendLine(it) }
            sb.appendLine()
            sb.appendLine("FINAL_SUMMARY:")
            sb.appendLine(finalResp.content)
            sb.appendLine()
            sb.appendLine("Retry count: ${retryCounter.get()}")
            val failureCounts = circuitBreaker.getFailureCounts()
            sb.appendLine("Circuit breaker - LLM failures: ${failureCounts[CircuitBreaker.FailureType.LLM_FAILURE]}")
            sb.appendLine("Circuit breaker - Validation failures: ${failureCounts[CircuitBreaker.FailureType.VALIDATION_FAILURE]}")
            sb.appendLine("Circuit breaker - Execution failures: ${failureCounts[CircuitBreaker.FailureType.EXECUTION_FAILURE]}")
            Files.writeString(path, sb)
            slogger.info(
                "🧾✅ Transcript persisted successfully",
                context,
                mapOf("lines" to stateHistory.size + 10, "path" to path.toUri())
            )
        }.onFailure { e -> slogger.logError("🧾❌ Failed to persist transcript", e, context.sessionId) }
    }
}
