package ai.platon.pulsar.external.logging

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.DateTimes.PATH_SAFE_FORMATTER_11
import ai.platon.pulsar.common.MultiSinkMessageWriter
import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache
import ai.platon.pulsar.external.ModelResponse
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

class ChatModelLogger : AutoCloseable {
    companion object {
        val LOG_BASE_DIR = AppPaths.detectAuxiliaryLogDir().resolve("agent").resolve("chat")
    }

    private val logger = LoggerFactory.getLogger(ChatModelLogger::class.java)
    private val counter = AtomicInteger(0)
    private val requestResponseMap =
        ConcurrentExpiringLRUCache<Int, ConversationRecord>(ttl = Duration.ofMinutes(10))

    private val auxRunLogDir by lazy { getRunLogDir0() }
    private val dataTimeStr = AppPaths.fromNow()
    private val auxLogger by lazy { MultiSinkMessageWriter(auxRunLogDir) }

    fun logRequest(systemMessage: String, userMessage: String, category: String? = null) =
        logRequestSmUm(systemMessage, userMessage, category)

    fun logRequestSmUm(systemMessage: String, userMessage: String, category: String? = null) =
        logRequestUmSm(userMessage, systemMessage, category)

    /**
     * 记录请求
     * @return 请求ID
     */
    fun logRequestUmSm(userMessage: String, systemMessage: String, category: String? = null): Int {
        val requestId = counter.incrementAndGet()
        val timestamp = LocalDateTime.now()
        val request = ConversationRecord(requestId, timestamp, userMessage, systemMessage, category = category)
        requestResponseMap.putDatum(requestId, request)

        return requestId
    }

    fun logResponse(requestId: Int, response: ModelResponse, category: String? = null) {
        val pair = requestResponseMap.getDatum(requestId) ?: return
        pair.response = response
        pair.responseTimestamp = LocalDateTime.now()
        pair.category = category

        writeToFile(pair)
    }

    private fun writeToFile(pair: ConversationRecord) {
        try {
            val sb = StringBuilder()
            sb.appendLine("--------------------------------------------------------------------")
            sb.append(";;REQUEST ID: ${pair.id}\n")
            sb.append(";;TIMESTAMP: ${pair.timestamp}\n")

            val category1 = pair.category ?: "chat"
            if (pair.id <= 2) {
                val path = auxRunLogDir.resolve("chat-$dataTimeStr.$category1.sys.log")
                auxLogger.writeTo(pair.systemMessage, path = path)
            }

            sb.append(";;USER MESSAGE:\n${pair.userMessage}\n")
            sb.append(";;RESPONSE TIMESTAMP: ${pair.responseTimestamp}\n")
            sb.append(";;RESPONSE STATE: ${pair.response?.state}\n")
            sb.append(";;TOKEN USAGE: ${pair.response?.tokenUsage ?: "N/A"}\n")
            sb.append(";;RESPONSE CONTENT:\n${pair.response?.content ?: "No response"}")

            val path = auxRunLogDir.resolve("chat-$dataTimeStr.$category1.user.log")
            auxLogger.writeTo(sb.toString(), path = path)

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
                auxLogger.flush()
            } catch (_: Exception) {
            }
            try {
                auxLogger.close()
            } catch (_: Exception) {
            }
        }
    }

    data class ConversationRecord constructor(
        val id: Int,
        val timestamp: LocalDateTime,
        val userMessage: String,
        val systemMessage: String,
        var response: ModelResponse? = null,
        var responseTimestamp: LocalDateTime? = null,
        var persistedToFile: Boolean = false,
        var category: String? = null
    )

    private fun getRunLogDir0(): Path {
        val auxLogDir = AppPaths.detectAuxiliaryLogDir().resolve("agent").resolve("chat")
        val day = LocalDateTime.now().format(DateTimes.PATH_SAFE_FORMATTER_1)
        return auxLogDir.resolve(day)
    }
}
