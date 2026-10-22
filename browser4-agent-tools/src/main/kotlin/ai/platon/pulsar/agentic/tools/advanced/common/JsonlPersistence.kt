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
 * Used by [ai.platon.pulsar.swarm.service.SwarmService] (browser4-swarm plugin),
 * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner],
 * and [ai.platon.pulsar.rest.api.service.crawl.CrawlService] so that async task
 * statuses survive server restarts.
 */
class JsonlPersistence<T : Any>(
    private val file: Path,
    private val clazz: KClass<T>,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(JsonlPersistence::class.java)

    /**
     * Append a single entry to the persistence file.
     *
     * Synchronized because progress snapshots are written from the event-collector
     * coroutine while the task coroutine writes its terminal row: two concurrent
     * `Files.writeString(APPEND)` calls can interleave and corrupt a line.
     */
    @Synchronized
    fun append(entry: T) {
        try {
            Files.createDirectories(file.parent)
            val line = objectMapper.writeValueAsString(entry)
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            logger.warn("Failed to persist entry to {}: {}", file, e.message)
        }
    }

    /**
     * Current size of the persistence file in bytes (0 when it does not exist).
     *
     * Used by the compaction loop: with periodic progress snapshots the file grows
     * even when no task has expired, so size — not only staleness — has to trigger
     * a rewrite.
     */
    fun sizeInBytes(): Long = try {
        if (Files.exists(file)) Files.size(file) else 0L
    } catch (e: Exception) {
        logger.debug("Failed to read the size of {}: {}", file, e.message)
        0L
    }

    /**
     * Every entry currently in the file, in append order (corrupt lines skipped).
     *
     * Unlike [restore] this is silent: maintenance rewrites run every few minutes
     * and would otherwise log "Restored N entries" each time. Used by compaction
     * to preserve rows written by other owners of the same file (the agent status
     * cache is per session, the JSONL file is global).
     */
    fun readAll(): List<T> {
        if (!Files.exists(file)) return emptyList()
        val entries = mutableListOf<T>()
        try {
            Files.newBufferedReader(file).use { reader ->
                reader.lines().forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        entries += objectMapper.readValue(line, clazz.java)
                    } catch (e: Exception) {
                        logger.debug("Skipping corrupt line in {}: {}", file, e.message)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to read {}: {}", file, e.message)
        }
        return entries
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
