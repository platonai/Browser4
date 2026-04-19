package ai.platon.pulsar.agentic.inference.detail

import ai.platon.pulsar.agentic.agents.AgentConfig
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.common.StructuredLogger
import org.slf4j.Logger
import java.time.Instant

/**
 * Provides structured JSON logging for agent operations.
 *
 * This helper class formats log messages as proper JSON when structured
 * logging is enabled, improving observability and log analysis.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class StructuredAgentLogger(
    ownerLogger: Logger,
    private val config: AgentConfig
) : StructuredLogger(ownerLogger = ownerLogger, enableStructuredLogging = config.enableStructuredLogging) {
    /**
     * Log a structured message with context and additional data.
     *
     * @param message The log message
     * @param context Execution context containing session ID, step number, etc.
     * @param additionalData Additional data to include in the log
     */
    fun info(
        message: String,
        context: ExecutionContext,
        additionalData: Map<String, Any> = emptyMap()
    ) {
        // Build proper JSON log data
        val logData = buildMap {
            put("event", context.event)
            put("step", context.step)
            put("message", message)
            put("sessionId", context.sessionId)
            put("timestamp", context.stepStartTime.toString())
            putAll(additionalData)
        }.toMutableMap()

        // Create proper JSON string

        if (config.enableStructuredLogging) {
            val jsonLog = formatAsJson(logData)
            logger.info("{}", jsonLog)
        } else {
            logData.remove("sessionId")
            logData.remove("timestamp")
            logData.remove("message")
            val log = logData.entries.joinToString(", ") { (key, value) -> "$key:$value" }
            logger.info("{} | {}", message, log)
        }
    }

    /**
     * Log an error with context and exception details.
     *
     * @param message Error message
     * @param error The exception that occurred
     * @param sessionId Session identifier
     */
    fun logError(message: String, error: Throwable, sessionId: String) {
        val errorData = mapOf(
            "sessionId" to sessionId,
            "errorType" to error.javaClass.simpleName,
            "errorMessage" to (error.message ?: "Unknown error"),
            "timestamp" to Instant.now().toString()
        )

        if (config.enableStructuredLogging) {
            val jsonLog = formatAsJson(errorData)
            logger.error("PerceptiveAgent Error: {} - {}", message, jsonLog, error)
        } else {
            logger.error("PerceptiveAgent Error: {} - {}", message, errorData, error)
        }
    }

}
