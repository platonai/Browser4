package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A run's trace is keyed by the agent's own uuid and start time, so a reviewer holding a task id
 * had no way to find it — the audit trail was "on disk, but unreachable" (issue #652, gap 5).
 * This index is that mapping, and its two promises are: the LAST row for a task wins, and a row
 * whose directory is gone resolves to nothing (retention deletes traces, not index rows).
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("Trace index (task id -> run dir)")
class AgentTraceIndexTest {

    private fun runDir(root: Path, agentUuid: String): Path =
        root.resolve("logs").resolve("agent").resolve("2026-11-22").resolve(agentUuid)
            .also { Files.createDirectories(it) }

    @Test
    @DisplayName("a recorded task resolves to its run dir")
    fun recordThenResolve(@TempDir root: Path) {
        val index = AgentTraceIndex(root.resolve("agent/trace-index.jsonl"))
        val dir = runDir(root, "aaaa")

        index.record("task-1", dir)

        assertEquals(dir.toAbsolutePath().normalize(), index.runDirOf("task-1"))
        assertNull(index.runDirOf("task-2"), "an unknown id resolves to nothing")
    }

    @Test
    @DisplayName("the last row for a task wins — a resume records the same id twice")
    fun theLastRowWins(@TempDir root: Path) {
        val index = AgentTraceIndex(root.resolve("agent/trace-index.jsonl"))
        val first = runDir(root, "first-agent")
        val resumed = runDir(root, "resumed-agent")

        index.record("task-1", first)
        index.record("task-1", resumed)

        assertEquals(resumed.toAbsolutePath().normalize(), index.runDirOf("task-1"))
    }

    @Test
    @DisplayName("a row whose run dir was pruned resolves to nothing")
    fun aPrunedRunDirResolvesToNothing(@TempDir root: Path) {
        val index = AgentTraceIndex(root.resolve("agent/trace-index.jsonl"))
        val dir = runDir(root, "gone")
        index.record("task-1", dir)

        // Trace retention deletes the directory, never the index row.
        Files.delete(dir)

        assertNull(index.runDirOf("task-1"), "the index must not point at something that is gone")
        assertTrue(index.taskIds().contains("task-1"), "the row is still there, it just resolves to nothing")
    }

    @Test
    @DisplayName("the index stays bounded once it grows past its row cap")
    fun theIndexIsCompacted(@TempDir root: Path) {
        val index = AgentTraceIndex(root.resolve("agent/trace-index.jsonl"))
        // Paths are long enough to fill the file quickly and need not exist: `record` only writes
        // the mapping, and the dir-existence rule is covered by its own test.
        val prefix = "x".repeat(180)
        repeat(AgentTraceIndex.MAX_ROWS + 100) { number ->
            index.record("task-$number", root.resolve("$prefix-$number"))
        }

        val size = Files.size(index.file())
        val ids = index.taskIds()

        assertTrue(size <= AgentTraceIndex.MAX_BYTES, "the index must not grow without bound: $size bytes")
        assertTrue(ids.isNotEmpty(), "compaction keeps the newest rows, it does not empty the index")
        assertTrue(
            ids.size <= AgentTraceIndex.MAX_ROWS,
            "compaction keeps at most MAX_ROWS rows, got ${ids.size}",
        )
        assertTrue("task-0" !in ids, "the oldest rows are the ones dropped")
        assertTrue("task-${AgentTraceIndex.MAX_ROWS + 99}" in ids, "the newest row is kept")
    }

    @Test
    @DisplayName("a corrupt index line is skipped instead of losing the whole mapping")
    fun aCorruptLineIsSkipped(@TempDir root: Path) {
        val index = AgentTraceIndex(root.resolve("agent/trace-index.jsonl"))
        val dir = runDir(root, "good")
        index.record("task-1", dir)
        Files.writeString(index.file(), """{"taskId": "broken", "runDir": """" + "\n", java.nio.file.StandardOpenOption.APPEND)

        assertEquals(dir.toAbsolutePath().normalize(), index.runDirOf("task-1"))
        assertNull(index.runDirOf("broken"), "an incomplete row names no dir")
    }

    @Test
    @DisplayName("a missing index file is not an error")
    fun aMissingIndexResolvesToNothing(@TempDir root: Path) {
        val index = AgentTraceIndex(root.resolve("agent/trace-index.jsonl"))

        assertNull(index.runDirOf("task-1"))
        assertTrue(index.taskIds().isEmpty())
    }

    @Test
    @DisplayName("a blank task id is never indexed")
    fun aBlankTaskIdIsIgnored(@TempDir root: Path) {
        val index = AgentTraceIndex(root.resolve("agent/trace-index.jsonl"))

        index.record("  ", runDir(root, "x"))

        assertFalse(Files.exists(index.file()), "nothing to index, nothing written")
    }

    private fun assertFalse(condition: Boolean, message: String) = assertTrue(!condition, message)
}
