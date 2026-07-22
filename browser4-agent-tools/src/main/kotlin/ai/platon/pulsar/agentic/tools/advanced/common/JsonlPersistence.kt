package ai.platon.pulsar.agentic.tools.advanced.common

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.reflect.KClass

/**
 * Simple JSONL (JSON Lines) persistence helper.
 *
 * Each entry is serialised as a single line of JSON and appended to a file.
 * On startup, the file is replayed to restore in-memory state.
 *
 * Used by [ai.platon.pulsar.rest.api.service.SwarmService],
 * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner],
 * and [ai.platon.pulsar.rest.api.service.CrawlService] so that async task
 * statuses survive server restarts.
 */
class JsonlPersistence<T : Any>(
    private val file: Path,
    private val clazz: KClass<T>,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(JsonlPersistence::class.java)

    /** Append a single entry to the persistence file. */
    fun append(entry: T) {
        try {
            Files.createDirectories(file.parent)
            val line = objectMapper.writeValueAsString(entry)
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            logger.warn("Failed to persist entry to {}: {}", file, e.message)
        }
    }

    /** Replay the persistence file, passing each deserialised entry to [consumer]. */
    fun restore(consumer: (T) -> Unit): Int {
        if (!Files.exists(file)) {
            logger.info("No persistence file found at {}", file)
            return 0
        }

        var restored = 0
        try {
            Files.newBufferedReader(file).use { reader ->
                reader.lines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        val entry = objectMapper.readValue(line, clazz.java)
                        consumer(entry)
                        restored++
                    } catch (e: Exception) {
                        logger.warn("Skipping corrupt line in {}: {}", file, e.message)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to restore from {}", file, e)
        }

        logger.info("Restored {} entry/entries from {}", restored, file)
        return restored
    }

    /**
     * Clear the persistence file (e.g. after a bulk clear operation).
     */
    fun clear() {
        try {
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            logger.warn("Failed to clear persistence file {}: {}", file, e.message)
        }
    }
}
