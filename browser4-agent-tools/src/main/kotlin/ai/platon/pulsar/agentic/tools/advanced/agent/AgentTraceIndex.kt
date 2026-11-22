package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * `taskId → trace run dir` index: the one thing the full run trace was missing.
 *
 * A run's trace lives under `<data>/logs/agent/<time>/<uuid>/`, keyed by the agent's own uuid and
 * start time — never by the task id a caller knows. That is why the audit trail was
 * "on disk, but not reachable": a reviewer with a task id had no way to find the process behind
 * it (issue #652, gap 5).
 *
 * The mapping is written once per task when the run starts, as one line of
 * `<data>/agent/trace-index.jsonl`. A row whose directory has since been pruned (trace retention)
 * is simply not found, so the index can never point a reader at something that is gone.
 *
 * Write failures are logged at debug and dropped: an unreachable trace must not be able to fail a
 * task, and the trace itself stays on disk for a local reader either way.
 *
 * @param file the index file (`<data>/agent/trace-index.jsonl`)
 */
class AgentTraceIndex(private val file: Path = indexPath()) {
    private val logger = getLogger(AgentTraceIndex::class)

    /** One row: which task, which run dir. */
    private data class Row(val taskId: String, val runDir: String, val startedAt: String)

    /**
     * Remember that [taskId]'s trace lives in [runDir].
     *
     * Idempotent per task: a resume or a retried start may record the same task twice, and the
     * last row wins on read.
     */
    fun record(taskId: String, runDir: Path) {
        if (taskId.isBlank()) return
        val row = mapOf(
            "taskId" to taskId,
            "runDir" to runDir.toAbsolutePath().normalize().toString(),
            "startedAt" to java.time.Instant.now().toString(),
        )
        synchronized(this) {
            runCatching {
                Files.createDirectories(file.parent)
                val line = pulsarObjectMapper().writeValueAsString(row) + "\n"
                Files.writeString(
                    file,
                    line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE,
                )
            }.onFailure { logger.debug("could not index the trace of {}: {}", taskId, it.message) }
            compactIfOversized()
        }
    }

    /**
     * The trace dir of [taskId], or `null` when the task has no indexed trace **or** the
     * directory is gone (pruned by retention, or a data dir that moved).
     */
    fun runDirOf(taskId: String): Path? {
        if (taskId.isBlank() || !Files.exists(file)) return null
        val rows = readRows()
        val row = rows.lastOrNull { it.taskId == taskId } ?: return null
        val dir = runCatching { Path.of(row.runDir) }.getOrNull() ?: return null
        return dir.takeIf { Files.isDirectory(it) }
    }

    /** Every indexed task id, newest first (diagnostics only). */
    fun taskIds(): List<String> = readRows().map { it.taskId }.distinct().asReversed()

    /** The index file this instance writes. */
    fun file(): Path = file

    private fun readRows(): List<Row> {
        val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: return emptyList()
        val mapper = pulsarObjectMapper()
        return lines.asSequence()
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val node = mapper.readTree(line)
                    Row(
                        taskId = node.path("taskId").asText(""),
                        runDir = node.path("runDir").asText(""),
                        startedAt = node.path("startedAt").asText(""),
                    )
                }.getOrNull()
            }
            .filter { it.taskId.isNotBlank() && it.runDir.isNotBlank() }
            .toList()
    }

    /**
     * Keep the index bounded.
     *
     * One row per task means the file grows with the number of tasks a backend ever ran. Above
     * [MAX_BYTES] it is rewritten to the newest rows that fit — both a row cap ([MAX_ROWS]) and
     * the byte budget, because a long run dir path makes a row large enough that the row cap
     * alone would still exceed the file cap. The dropped rows point at run dirs that trace
     * retention has already deleted.
     */
    private fun compactIfOversized() {
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (size <= MAX_BYTES) return
        runCatching {
            val budget = (MAX_BYTES * COMPACTION_TARGET_RATIO).toLong()
            val kept = ArrayList<Row>()
            var bytes = 0L
            // Walk from the newest row backwards, stopping at whichever bound is hit first.
            for (row in readRows().takeLast(MAX_ROWS).asReversed()) {
                val line = encode(row)
                if (kept.isNotEmpty() && bytes + line.length > budget) break
                kept += row
                bytes += line.length
            }
            kept.reverse()
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(
                tmp,
                kept.joinToString("") { encode(it) },
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            logger.debug("compacted the trace index to {} row(s) ({} bytes)", kept.size, bytes)
        }.onFailure { logger.debug("compacting the trace index failed: {}", it.message) }
    }

    /** One index line for [row]. */
    private fun encode(row: Row): String = pulsarObjectMapper().writeValueAsString(
        mapOf("taskId" to row.taskId, "runDir" to row.runDir, "startedAt" to row.startedAt)
    ) + "\n"

    companion object {
        /** The index file under the agent data dir, next to the task registry. */
        fun indexPath(): Path = StatefulAgentRunner.agentPersistencePath().resolveSibling("trace-index.jsonl")

        /** An index over the current data dir. Cheap to construct: it holds no state. */
        fun shared(): AgentTraceIndex = AgentTraceIndex(indexPath())

        /** Rows kept when the index is compacted (≈ a month of tasks). */
        const val MAX_ROWS = 2_000

        /** Size above which the index is rewritten (one row is ~150-400 bytes). */
        const val MAX_BYTES = 512L * 1024

        /** How much of [MAX_BYTES] a compacted index targets, leaving room for growth. */
        private const val COMPACTION_TARGET_RATIO = 0.7
    }
}
