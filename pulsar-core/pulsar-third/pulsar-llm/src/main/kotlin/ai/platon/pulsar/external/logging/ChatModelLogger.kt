package ai.platon.pulsar.external.logging

import ai.platon.pulsar.common.MessageWriter
import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache
import ai.platon.pulsar.external.ModelResponse
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

class ChatModelLogger : AutoCloseable {
    companion object {
        const val LOG_BASE_DIR = "logs/agent/chat-model"
    }

    private val logger = LoggerFactory.getLogger(ChatModelLogger::class.java)
    private val counter = AtomicInteger(0)
    private val requestResponseMap =
        ConcurrentExpiringLRUCache<Int, RequestResponsePair>(ttl = Duration.ofMinutes(10))

    private var logBaseDir = LOG_BASE_DIR
    private var enableSystemMessages = false
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss.SSS")
    private val writer: MessageWriter

    fun configure(logDirectory: String = LOG_BASE_DIR, enableSystemMessages: Boolean = false) {
        this.logBaseDir = logDirectory
        this.enableSystemMessages = enableSystemMessages
    }

    init {
        File(logBaseDir).mkdirs()
        val dateStr = LocalDateTime.now().format(dateFormat)
        writer = MessageWriter(Paths.get(logBaseDir).resolve("chat_$dateStr.log"))
    }

    fun logRequest(systemMessage: String, userMessage: String) = logRequestSmUm(systemMessage, userMessage)

    fun logRequestSmUm(systemMessage: String, userMessage: String) = logRequestUmSm(userMessage, systemMessage)

    /**
     * 记录请求
     * @return 请求ID
     */
    fun logRequestUmSm(userMessage: String, systemMessage: String): Int {
        val requestId = counter.incrementAndGet()
        val timestamp = LocalDateTime.now()
        val request = RequestResponsePair(requestId, timestamp, userMessage, systemMessage)
        requestResponseMap.putDatum(requestId, request)

        return requestId
    }

    fun logResponse(requestId: Int, response: ModelResponse) {
        val pair = requestResponseMap.getDatum(requestId) ?: return
        pair.response = response
        pair.responseTimestamp = LocalDateTime.now()

        writeToFile(pair)
    }

    private fun writeToFile(pair: RequestResponsePair) {
        try {
            val sb = StringBuilder()
            sb.appendLine("--------------------------------------------------------------------")
            sb.append(";;REQUEST ID: ${pair.id}\n")
            sb.append(";;TIMESTAMP: ${pair.timestamp}\n")

            if (enableSystemMessages || pair.id <= 2) {
                sb.append(";;SYSTEM MESSAGE:\n${pair.systemMessage}\n")
            }

            sb.append(";;USER MESSAGE:\n${pair.userMessage}\n")
            sb.append(";;RESPONSE TIMESTAMP: ${pair.responseTimestamp}\n")
            sb.append(";;RESPONSE STATE: ${pair.response?.state}\n")
            sb.append(";;TOKEN USAGE: ${pair.response?.tokenUsage?.totalTokenCount ?: "N/A"}\n")
            sb.append(";;RESPONSE CONTENT:\n${pair.response?.content ?: "No response"}")

            writer.write(sb.toString())

            // Mark persisted only after successful write
            pair.persistedToFile = true
        } catch (e: Exception) {
            logger.error("Failed to write chat log to file", e)
        }
    }

    override fun close() {
        try {
            // Drain cache and persist any unflushed pairs
            while (true) {
                val item = requestResponseMap.remove() ?: break
                val pair = item.datum
                if (!pair.persistedToFile) {
                    writeToFile(pair)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to flush chat log pairs on close", e)
        } finally {
            try {
                writer.flush()
            } catch (_: Exception) { }
            try {
                writer.close()
            } catch (_: Exception) { }
        }
    }

    /**
     * 请求响应对象，用于存储一次对话的请求和响应
     */
    data class RequestResponsePair constructor(
        val id: Int,
        val timestamp: LocalDateTime,
        val userMessage: String,
        val systemMessage: String,
        var response: ModelResponse? = null,
        var responseTimestamp: LocalDateTime? = null,
        var persistedToFile: Boolean = false
    )
}
