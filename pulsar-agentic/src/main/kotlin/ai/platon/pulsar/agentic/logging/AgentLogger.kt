package ai.platon.pulsar.agentic.logging

import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.getLogger
import org.slf4j.Logger
import java.time.Instant

/**
 * Structured logger for agent operations, replacing the addTrace mechanism.
 *
 * Provides structured, consistent logging for agent lifecycle events, tool executions,
 * and state transitions. Logs follow the Browser4 structured format with key-value pairs
 * for easy parsing and analysis.
 *
 * Log Format:
 * ```
 * HH:mm:ss.SSS [thread] LEVEL LoggerName - EventType | field=value field2=value2 | message
 * ```
 *
 * Example:
 * ```
 * 14:53:01.234 [agent-1] INFO AgentLogger - toolExec | step=5 method=click sid=abc123 | ✅ Click executed successfully
 * ```
 *
 * @param logger The underlying SLF4J logger
 */
class AgentLogger(private val logger: Logger) {

    /**
     * Log agent action start event.
     *
     * @param sessionId Session identifier (first 8 chars will be used)
     * @param step Current step number
     * @param action The action/instruction being executed
     */
    fun logActionStart(sessionId: String, step: Int, action: String) {
        logger.info(
            "actionStart | sid={} step={} | action={}",
            sessionId.take(8), step, action.take(100)
        )
    }

    /**
     * Log action timeout event.
     *
     * @param state The current agent state
     * @param timeoutMs Timeout duration in milliseconds
     * @param instruction The instruction that timed out
     */
    fun logActionTimeout(state: AgentState?, timeoutMs: Long, instruction: String) {
        val step = state?.step ?: 0
        logger.warn(
            "actTimeout | step={} timeout={}ms | ⏳ Action timed out: {}",
            step, timeoutMs, instruction.take(100)
        )
    }

    /**
     * Log tool execution success.
     *
     * @param sessionId Session identifier
     * @param step Current step number
     * @param method Tool method name
     * @param description Result description
     */
    fun logToolExecOk(sessionId: String, step: Int, method: String, description: String?) {
        logger.info(
            "toolExecOk | sid={} step={} tool={} | {}",
            sessionId.take(8), step, method, description ?: "✅ Tool executed successfully"
        )
    }

    /**
     * Log tool execution failure.
     *
     * @param sessionId Session identifier
     * @param step Current step number
     * @param method Tool method name
     * @param error Error message or exception
     */
    fun logToolExecFail(sessionId: String, step: Int, method: String, error: String) {
        logger.error(
            "toolExecFail | sid={} step={} tool={} | ❌ {}",
            sessionId.take(8), step, method, error.take(200)
        )
    }

    /**
     * Log action success with optional metadata.
     *
     * @param state The current agent state
     * @param candidateIndex Index of the successful candidate (1-based)
     * @param candidateTotal Total number of candidates tried
     */
    fun logActionSuccess(state: AgentState?, candidateIndex: Int, candidateTotal: Int) {
        val step = state?.step ?: 0
        logger.info(
            "actSuccess | step={} candidate={}/{} | ✅ Action executed successfully",
            step, candidateIndex, candidateTotal
        )
    }

    /**
     * Log all candidates failed.
     *
     * @param state The current agent state
     * @param candidatesCount Number of candidates that failed
     * @param lastError Last error message
     */
    fun logActionAllFailed(state: AgentState?, candidatesCount: Int, lastError: String?) {
        val step = state?.step ?: 0
        logger.warn(
            "actAllFailed | step={} candidates={} | ❌ All candidates failed. Last: {}",
            step, candidatesCount, lastError?.take(100) ?: "unknown"
        )
    }

    /**
     * Log no observation result.
     *
     * @param state The current agent state
     */
    fun logObserveNoAction(state: AgentState?) {
        val step = state?.step ?: 0
        logger.warn(
            "observeNoAction | step={} | ⚠️ No observe result generated",
            step
        )
    }

    /**
     * Log validation failure.
     *
     * @param sessionId Session identifier
     * @param step Current step number
     * @param reason Failure reason
     */
    fun logValidationFailed(sessionId: String, step: Int, reason: String) {
        logger.warn(
            "validationFailed | sid={} step={} | ⚠️ {}",
            sessionId.take(8), step, reason.take(200)
        )
    }

    /**
     * Log task completion.
     *
     * @param sessionId Session identifier
     * @param step Final step number
     * @param isComplete Whether task completed successfully
     */
    fun logComplete(sessionId: String, step: Int, isComplete: Boolean) {
        val symbol = if (isComplete) "✅" else "⚠️"
        logger.info(
            "complete | sid={} step={} complete={} | {} Task finished",
            sessionId.take(8), step, isComplete, symbol
        )
    }

    /**
     * Log no-operation (noop) event.
     *
     * @param state The current agent state
     * @param consecutiveNoOps Number of consecutive no-ops
     * @param maxAllowed Maximum allowed consecutive no-ops
     */
    fun logNoOp(state: AgentState?, consecutiveNoOps: Int, maxAllowed: Int) {
        val step = state?.step ?: 0
        logger.warn(
            "noop | step={} consecutive={} max={} | ⚠️ No-operation detected",
            step, consecutiveNoOps, maxAllowed
        )
    }

    /**
     * Log resolve (multi-step task) start.
     *
     * @param sessionId Session identifier
     * @param instruction The instruction to resolve
     * @param maxSteps Maximum steps allowed
     */
    fun logResolveStart(sessionId: String, instruction: String, maxSteps: Int) {
        logger.info(
            "resolveStart | sid={} maxSteps={} | instruction={}",
            sessionId.take(8), maxSteps, instruction.take(160)
        )
    }

    /**
     * Log resolve completion.
     *
     * @param sessionId Session identifier
     * @param step Final step number
     * @param success Whether resolve succeeded
     * @param durationMs Duration in milliseconds
     * @param result Result message
     */
    fun logResolveDone(
        sessionId: String,
        step: Int,
        success: Boolean,
        durationMs: Long,
        result: String?
    ) {
        val symbol = if (success) "✅" else "❌"
        logger.info(
            "resolveDone | sid={} step={} success={} duration={}ms | {} {}",
            sessionId.take(8), step, success, durationMs, symbol, result?.take(100) ?: ""
        )
    }

    /**
     * Log resolve timeout.
     *
     * @param sessionId Session identifier
     * @param timeoutMs Timeout duration in milliseconds
     * @param instruction The instruction that timed out
     */
    fun logResolveTimeout(sessionId: String, timeoutMs: Long, instruction: String) {
        logger.warn(
            "resolveTimeout | sid={} timeout={}ms | ⏳ Resolve timed out: {}",
            sessionId.take(8), timeoutMs, instruction.take(100)
        )
    }

    /**
     * Log user/external close event.
     *
     * @param state The final agent state
     */
    fun logUserClose(state: AgentState?) {
        val step = state?.step ?: 0
        logger.info("userClose | step={} | 🛑 Agent closed by user", step)
    }

    /**
     * Log final summary generation.
     *
     * @param sessionId Session identifier
     * @param step Current step number
     */
    fun logFinalSummary(sessionId: String, step: Int) {
        logger.info(
            "final | sid={} step={} | Generating final summary",
            sessionId.take(8), step
        )
    }

    /**
     * Log generic event with custom fields.
     *
     * Use this for events not covered by specific methods.
     *
     * @param event Event name
     * @param fields Map of field names to values
     * @param message Optional message
     */
    fun logEvent(event: String, fields: Map<String, Any?>, message: String? = null) {
        val fieldStr = fields.entries.joinToString(" ") { (k, v) -> "$k=${formatValue(v)}" }
        if (message != null) {
            logger.info("{} | {} | {}", event, fieldStr, message)
        } else {
            logger.info("{} | {}", event, fieldStr)
        }
    }

    /**
     * Format a value for logging output.
     */
    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> if (value.length > 50) value.take(50) + "..." else value
            is Number, is Boolean -> value.toString()
            is Instant -> value.toString()
            else -> value.toString().take(50)
        }
    }

    companion object {
        /**
         * Create an AgentLogger for a specific class.
         */
        fun forClass(clazz: Class<*>): AgentLogger {
            return AgentLogger(getLogger(clazz.kotlin))
        }

        /**
         * Create an AgentLogger for a specific target.
         */
        fun forTarget(target: Any): AgentLogger {
            return AgentLogger(getLogger(target))
        }
    }
}
