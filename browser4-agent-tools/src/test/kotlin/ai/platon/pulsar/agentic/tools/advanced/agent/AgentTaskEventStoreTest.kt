package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persisting a task's step log is what makes a run survive a backend restart: the status row
 * was always durable, the PROCESS was not (issue #652 §4). These tests pin the three promises
 * the store makes — write-behind, a bounded shard, and a tolerant restore — because each of
 * them is an explicit trade-off rather than a happy path.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("Persisted agent step log")
class AgentTaskEventStoreTest {

    private fun event(seq: Long, type: String = AgentTaskEvent.TYPE_TOOL_CALL): AgentTaskEvent =
        AgentTaskEvent(seq = seq, type = type, tool = "coding.read", text = "step $seq")

    @Test
    @DisplayName("written steps come back with their sequence numbers and payloads")
    fun appendThenLoadRoundTrips(@TempDir dir: Path) {
        val store = AgentTaskEventStore(dir)
        try {
            store.append(
                "task-1",
                AgentTaskEvent(
                    seq = 7,
                    type = AgentTaskEvent.TYPE_GATE_EVALUATED,
                    detail = mapOf("passed" to false, "violations" to listOf("a file git does not show")),
                    mutations = 3,
                    schemaVersion = AgentTaskEvent.SCHEMA_VERSION,
                ),
            )
            store.append("task-1", event(8))

            val loaded = store.load("task-1")

            assertEquals(listOf(7L, 8L), loaded.map { it.seq })
            assertEquals(AgentTaskEvent.TYPE_GATE_EVALUATED, loaded[0].type)
            assertEquals(false, loaded[0].detail!!["passed"], "the decision payload survives the round trip")
            @Suppress("UNCHECKED_CAST")
            val violations = loaded[0].detail!!["violations"] as List<String>
            assertEquals(listOf("a file git does not show"), violations)
            assertEquals(3L, loaded[0].mutations)
            assertEquals(AgentTaskEvent.SCHEMA_VERSION, loaded[0].schemaVersion)
        } finally {
            store.close()
        }
    }

    @Test
    @DisplayName("a task with no shard loads nothing and reports no shard")
    fun missingShardIsNotAnError(@TempDir dir: Path) {
        val store = AgentTaskEventStore(dir)
        try {
            assertFalse(store.hasShard("ghost"))
            assertTrue(store.load("ghost").isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    @DisplayName("a half-written last line is skipped instead of losing the whole log")
    fun aTruncatedLastLineIsTolerated(@TempDir dir: Path) {
        // A crash between write() calls leaves an incomplete JSON line; the steps before it are
        // still the answer to "how did this run get here".
        val store = AgentTaskEventStore(dir)
        try {
            store.append("task-1", event(1))
            // Flush first: the first line is queued, and the file has to exist before the
            // crash simulation below appends to it.
            store.flush()
            Files.writeString(
                dir.resolve(AgentTaskEventStore.fileNameOf("task-1")),
                """{"seq":2,"type":"tool.result","tool":"coding.rea""" + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
            )
            store.append("task-1", event(3))

            val loaded = store.load("task-1")

            assertEquals(listOf(1L, 3L), loaded.map { it.seq }, "the unparseable line is skipped, not fatal")
        } finally {
            store.close()
        }
    }

    @Test
    @DisplayName("an oversized shard is rotated down to the newest steps")
    fun oversizedShardKeepsTheNewestSteps(@TempDir dir: Path) {
        // 40 events of ~120 bytes: well past the 1 KB threshold, so rotation must kick in and
        // keep the newest window (the same shape as the in-memory ring).
        val store = AgentTaskEventStore(dir, retainedEvents = 10, maxShardBytes = 1_024)
        try {
            (1..40).forEach { seq -> store.append("task-1", event(seq.toLong())) }
            store.flush()

            val loaded = store.load("task-1", limit = 100)

            assertEquals(10, loaded.size, "the shard is bounded to retainedEvents=${10}")
            assertEquals((31L..40L).toList(), loaded.map { it.seq }, "the newest steps are the ones kept")
            val size = Files.size(dir.resolve(AgentTaskEventStore.fileNameOf("task-1")))
            assertTrue(size <= 1_024, "the shard must shrink under the threshold, got $size bytes")
        } finally {
            store.close()
        }
    }

    @Test
    @DisplayName("pruning drops shards older than the task TTL and keeps the rest")
    fun pruneDropsOnlyExpiredShards(@TempDir dir: Path) {
        val store = AgentTaskEventStore(dir)
        try {
            store.append("old-task", event(1))
            store.append("new-task", event(1))
            store.flush()
            val old = dir.resolve(AgentTaskEventStore.fileNameOf("old-task"))
            Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(0))

            val removed = store.prune(Instant.now().minusSeconds(60))

            assertEquals(1, removed)
            assertFalse(Files.exists(old), "an expired shard is removed")
            assertTrue(store.hasShard("new-task"), "a live shard stays")
        } finally {
            store.close()
        }
    }

    @Test
    @DisplayName("appends after close are ignored instead of resurrecting a shard")
    fun appendsAfterCloseAreIgnored(@TempDir dir: Path) {
        val store = AgentTaskEventStore(dir)
        store.append("task-1", event(1))
        store.close()

        store.append("task-1", event(2))

        assertTrue(store.isClosed)
        val reopened = AgentTaskEventStore(dir)
        try {
            assertEquals(listOf(1L), reopened.load("task-1").map { it.seq })
        } finally {
            reopened.close()
        }
    }

    @Test
    @DisplayName("a task id that is not a safe path segment still gets its own shard")
    fun taskIdIsSanitizedForTheFileSystem(@TempDir dir: Path) {
        assertEquals("a_b_c.jsonl", AgentTaskEventStore.fileNameOf("a/b:c"))
        assertEquals("task-1.jsonl", AgentTaskEventStore.fileNameOf("task-1"))
        // The suffix is never doubled.
        assertEquals("task-1.jsonl", AgentTaskEventStore.fileNameOf("task-1.jsonl"))

        val store = AgentTaskEventStore(dir)
        try {
            store.append("a/b:c", event(1))
            store.flush()
            assertTrue(Files.exists(dir.resolve("a_b_c.jsonl")), "no directory traversal, one flat shard")
            assertNull(Files.list(dir).use { stream -> stream.filter { Files.isDirectory(it) }.findAny().orElse(null) })
        } finally {
            store.close()
        }
    }

    @Test
    @DisplayName("many tasks share the store without mixing their steps")
    fun shardsAreIsolatedPerTask(@TempDir dir: Path) {
        val store = AgentTaskEventStore(dir)
        try {
            repeat(5) { index ->
                store.append("task-a", event(index + 1L))
                store.append("task-b", event(index + 100L))
            }

            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.load("task-a").map { it.seq })
            assertEquals(listOf(100L, 101L, 102L, 103L, 104L), store.load("task-b").map { it.seq })
        } finally {
            store.close()
        }
    }
}
