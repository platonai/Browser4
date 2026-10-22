package ai.platon.pulsar.agentic.tools.advanced.common

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
 *
 * All methods are synchronized: an instance shared by several callers (e.g. every
 * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner] writes the
 * shared agent-tasks file) serialises its appends instead of interleaving them,
 * and [rewrite] is atomic — the replacement file is built outside the target and
 * moved into place, so a crash never leaves a half-written task history behind.
 */
class JsonlPersistence<T : Any>(
    private val file: Path,
    private val clazz: KClass<T>,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(JsonlPersistence::class.java)

    private val lock = Any()

    /** Append a single entry to the persistence file. */
    fun append(entry: T) = synchronized(lock) {
        try {
            Files.createDirectories(file.parent)
            val line = objectMapper.writeValueAsString(entry)
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            logger.warn("Failed to persist entry to {}: {}", file, e.message)
        }
    }

    /** Replay the persistence file, passing each deserialised entry to [consumer]. */
    fun restore(consumer: (T) -> Unit): Int = synchronized(lock) {
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
     * Replace the whole file with [entries] in a single atomic operation.
     *
     * The replacement is serialised to a sibling temp file first and moved over the
     * target, so a crash mid-rewrite leaves either the previous file or the complete
     * new one — never a truncated history. Used by compaction, which used to
     * `clear()` the live file and re-append entries into it: a concurrent writer or
     * a crash in that window lost entries that no longer exists anywhere.
     */
    fun rewrite(entries: Collection<T>): Boolean = synchronized(lock) {
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        try {
            Files.createDirectories(file.parent)
            Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING).use { writer ->
                for (entry in entries) {
                    writer.write(objectMapper.writeValueAsString(entry))
                    writer.write("\n")
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                // Some filesystems do not support atomic moves — fall back to a
                // best-effort replace rather than losing the new content.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            logger.warn("Failed to rewrite persistence file {}: {}", file, e.message)
            false
        }
    }

    /**
     * Clear the persistence file (e.g. after a bulk clear operation).
     */
    fun clear() = synchronized(lock) {
        try {
            Files.deleteIfExists(file)
        } catch (e: Exception) {
            logger.warn("Failed to clear persistence file {}: {}", file, e.message)
        }
    }
}
