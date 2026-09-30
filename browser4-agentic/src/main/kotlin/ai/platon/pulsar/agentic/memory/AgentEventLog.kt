package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Append-only JSONL store of [MemoryEvent]s — the L0 fact layer.
 *
 * Layout: `<root>/events/<agentUuid>/<taskId>.jsonl`, one JSON event per line.
 * - Appends are atomic per line (single write with APPEND); a crash can only
 *   leave a partial trailing line, which reads drop silently (tail recovery).
 * - Files older than [ttlDays] move to `<root>/.archive/<agentUuid>/` on
 *   [archiveExpired] (rolling hygiene; archived data stays recoverable).
 * - `agentUuid == null` reads scan all agent directories (shared backend view).
 *
 * This store is authoritative; the FTS search index (M2) is a disposable
 * derived artifact built from it.
 */
class AgentEventLog(
    private val rootDir: Path,
    private val ttlDays: Long = 30,
    private val mapper: ObjectMapper = pulsarObjectMapper(),
) {
    private val logger = getLogger(AgentEventLog::class)
    private val lock = Any()

    private fun eventsDir(): Path = rootDir.resolve("events")
    private fun archiveDir(): Path = rootDir.resolve(".archive")
    private fun agentDir(agentUuid: String): Path = eventsDir().resolve(sanitizeSegment(agentUuid))
    private fun taskFile(agentUuid: String, taskId: String): Path =
        agentDir(agentUuid).resolve(sanitizeSegment(taskId) + ".jsonl")

    /**
     * Append one event. Never throws: memory must not become a failure point
     * of the agent's main path (failures are logged only).
     */
    fun append(event: MemoryEvent) {
        synchronized(lock) {
            runCatching {
                val file = taskFile(event.agentUuid, event.taskId)
                Files.createDirectories(file.parent)
                Files.writeString(
                    file, mapper.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
                )
            }.onFailure { logger.warn("memory.event.append failed: {}", it.message) }
        }
    }

    /** All events of one task, oldest first. Trailing partial lines are dropped. */
    fun readTask(agentUuid: String, taskId: String): List<MemoryEvent> =
        readFile(taskFile(agentUuid, taskId))

    /** Task ids of one agent (or all agents when null). */
    fun listTaskIds(agentUuid: String?): List<String> {
        val root = if (agentUuid != null) agentDir(agentUuid) else eventsDir()
        return listTaskFiles(agentUuid).map { it.fileName.toString().removeSuffix(".jsonl") }
    }

    /** All events of one agent (or all agents when null), newest first, capped at [limit]. */
    fun readAll(agentUuid: String?, limit: Int = Int.MAX_VALUE): List<MemoryEvent> {
        val events = listTaskFiles(agentUuid).flatMap { readFile(it) }
        return events.sortedByDescending { it.ts }.take(limit)
    }

    /**
     * Events with `seq > minSeq` across the whole log (used by the search
     * index alignment state machine to catch up from its watermark).
     *
     * Scaled: files whose LAST line's seq is already ≤ [minSeq] are skipped
     * without reading them (append-only files keep `seq` ascending), so a
     * fully-aligned index pays O(files × tail-read) instead of O(all events).
     * An unparseable tail never skips (the read path recovers partial lines).
     */
    fun readSince(minSeq: Long, limit: Int = 10_000): List<MemoryEvent> {
        val files = listTaskFiles(null).filter { maxSeqOf(it) > minSeq }
        if (files.isEmpty()) return emptyList()
        val events = files.flatMap { readFile(it) }.filter { it.seq > minSeq }
        return events.sortedBy { it.seq }.take(limit)
    }

    /** Delete one task's events (explicit forget / privacy). */
    fun deleteTask(taskId: String): Boolean {
        var deleted = false
        listTaskFiles(null).forEach { file ->
            if (file.fileName.toString() == sanitizeSegment(taskId) + ".jsonl" && Files.deleteIfExists(file)) {
                deleted = true
            }
        }
        return deleted
    }

    /**
     * Move event files whose last-write time is older than [ttlDays] into the
     * archive. Returns the number of archived files.
     */
    fun archiveExpired(now: Instant = Instant.now()): Int {
        val cutoff = now.toEpochMilli() - ttlDays * 24 * 3600 * 1000L
        var archived = 0
        listAgentDirs().forEach { dir ->
            runCatching {
                Files.list(dir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".jsonl") }.forEach { file ->
                        if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                            val target = archiveDir().resolve(dir.fileName).resolve(file.fileName)
                            Files.createDirectories(target.parent)
                            Files.move(file, target)
                            archived++
                        }
                    }
                }
            }.onFailure { logger.warn("memory.event.archive failed for {}: {}", dir, it.message) }
        }
        return archived
    }

    private fun listAgentDirs(): List<Path> {
        if (!Files.isDirectory(eventsDir())) return emptyList()
        return runCatching {
            Files.list(eventsDir()).use { s -> s.filter { Files.isDirectory(it) }.toList() }
        }.getOrElse { emptyList() }
    }

    /** All task event files of one agent (or all agents when null). */
    private fun listTaskFiles(agentUuid: String?): List<Path> {
        val dirs = if (agentUuid != null) listOf(agentDir(agentUuid)) else listAgentDirs()
        return dirs.flatMap { dir ->
            if (!Files.isDirectory(dir)) return@flatMap emptyList()
            runCatching {
                Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".jsonl") }.toList() }
            }.getOrElse { emptyList() }
        }
    }

    private fun readFile(file: Path): List<MemoryEvent> {
        if (!Files.exists(file)) return emptyList()
        return runCatching {
            val events = mutableListOf<MemoryEvent>()
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val event = line.toMemoryEvent(mapper) ?: break // trailing partial line
                    events.add(event)
                }
            }
            events
        }.getOrElse {
            logger.warn("memory.event.read failed for {}: {}", file, it.message)
            emptyList()
        }
    }

    private fun sanitizeSegment(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)

    /**
     * Cheap file-level max-seq probe: read only the file's tail chunk and
     * parse `"seq":N` from its last line. Returns [Long.MAX_VALUE] when the
     * tail cannot be parsed (crash tail) — callers then read the file and the
     * normal partial-line recovery applies.
     */
    private fun maxSeqOf(file: Path): Long = runCatching {
        val size = Files.size(file)
        if (size <= 0) return Long.MIN_VALUE
        val tailLen = minOf(size, 4096L)
        java.nio.channels.FileChannel.open(file).use { ch ->
            ch.position(size - tailLen)
            val buf = java.nio.ByteBuffer.allocate(tailLen.toInt())
            while (buf.hasRemaining() && ch.read(buf) > 0) { /* fill */ }
            val tail = String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8)
            val lastLine = tail.substringAfterLast('\n').trim()
                .ifBlank { tail.trim().substringAfterLast('\n').trim() }
            parseSeq(lastLine) ?: Long.MAX_VALUE
        }
    }.getOrDefault(Long.MAX_VALUE)

    private fun parseSeq(line: String): Long? =
        Regex("\"seq\"\\s*:\\s*(-?\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
}
