package ai.platon.pulsar.common

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Provides structured JSON logging.
 *
 * This helper class formats log messages as proper JSON when structured
 * logging is enabled, improving observability and log analysis.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
open class StructuredLogger(
    private val ownerLogger: Logger? = null,
    private val enableStructuredLogging: Boolean = false,
    private val target: Any? = null,
) {
    private val targetClass = target?.javaClass ?: this.javaClass

    val logger = ownerLogger ?: getFlexibleLogger()

    fun trace(format: String, vararg args: Any?) = logger.trace(format, *args)

    fun debug(format: String, vararg args: Any?) = logger.debug(format, *args)

    fun info(format: String, vararg args: Any?) = logger.info(format, *args)

    fun warn(format: String, vararg args: Any?) = logger.warn(format, *args)

    fun error(format: String, vararg args: Any?) = logger.error(format, *args)

    /**
     * Log a structured message with context and additional data.
     *
     * @param message The log message
     * @param context Execution context containing session ID, step number, etc.
     * @param additionalData Additional data to include in the log
     */
    fun info(
        message: String,
        additionalData: Map<String, Any> = emptyMap()
    ) {
        // Build proper JSON log data
        val logData = buildMap {
            put("message", message)
            putAll(additionalData)
        }.toMutableMap()

        // Create proper JSON string

        if (enableStructuredLogging) {
            logData["timestamp"] = Instant.now().toString()
            logData["target"] = readableClassName(targetClass)

            val jsonLog = formatAsJson(logData)
            info("{}", jsonLog)
        } else {
            val log = logData.entries.joinToString(", ") { (key, value) -> "$key:$value" }
            info("{} | {}", message, log)
        }
    }

    /**
     * Format a map as a proper JSON string.
     *
     * @param data Map to format as JSON
     * @return JSON string
     */
    protected fun formatAsJson(data: Map<String, Any>): String {
        return try {
            return pulsarObjectMapper().writeValueAsString(data)
        } catch (_: Exception) {
            """{"error": "Failed to serialize object."}"""
        }
    }

    /**
     * Format a value for JSON output.
     *
     * @param value The value to format
     * @return JSON-formatted string representation
     */
    protected fun formatJsonValue(value: Any): String {
        return when (value) {
            is String -> "\"${value.replace("\"", "\\\"").replace("\n", "\\n")}\""
            is Number, is Boolean -> value.toString()
            is Collection<*> -> value.joinToString(",", "[", "]") { formatJsonValue(it ?: "null") }
            is Map<*, *> -> formatAsJson(value.mapKeys { it.key.toString() }.mapValues { it.value ?: "null" })
            else -> "\"$value\""
        }
    }

    protected fun getFlexibleLogger(): Logger {
        if (enableStructuredLogging) {
            setupStructuredLogbackPatternLayout()
        }

        return getLogger(targetClass)
    }

    protected fun setupStructuredLogbackPatternLayout() {
        // 获取 Root Logger
        val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger

        // 创建 PatternLayout
        val layout = PatternLayout().apply {
            pattern = "%msg%n"
            context = rootLogger.loggerContext
            start() // ⚠️ 必须 start() 否则无效
        }

        // 创建 ConsoleAppender
        val consoleAppender = ConsoleAppender<ILoggingEvent>().apply {
            this.context = rootLogger.loggerContext
            this.setLayout(layout)
            start()
        }

        // 清除原有 appender，添加新的
        rootLogger.detachAndStopAllAppenders()
        rootLogger.addAppender(consoleAppender)
    }
}
