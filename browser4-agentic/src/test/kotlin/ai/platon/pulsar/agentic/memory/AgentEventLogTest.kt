package ai.platon.pulsar.agentic.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("AgentEventLog")
class AgentEventLogTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var log: AgentEventLog

    @BeforeEach
    fun setUp() {
        log = AgentEventLog(tempDir)
    }

    @AfterEach
    fun tearDown() {
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    private fun event(seq: Long, taskId: String = "t1", agent: String = "a1") =
        TaskStarted(seq, 1000 + seq, agent, taskId, "instruction $seq", "cli", null)

    @Test
    @DisplayName("appends and reads back in order")
    fun testAppendRead() {
        log.append(event(1))
        log.append(event(2))
        log.append(event(3))

        val events = log.readTask("a1", "t1")
        assertEquals(listOf(1L, 2L, 3L), events.map { it.seq })
    }

    @Test
    @DisplayName("drops a trailing partial line (crash recovery)")
    fun testTailRecovery() {
        log.append(event(1))
        log.append(event(2))
        // Simulate a crash mid-write: append a partial JSON line.
        val file = tempDir.resolve("events").resolve("a1").resolve("t1.jsonl")
        Files.writeString(
            file, """{"type":"task_started","seq":3,"ts":""", StandardCharsets.UTF_8,
            StandardOpenOption.APPEND,
        )

        val events = log.readTask("a1", "t1")
        assertEquals(listOf(1L, 2L), events.map { it.seq })
    }

    @Test
    @DisplayName("reads all events across agents, newest first")
    fun testReadAll() {
        log.append(event(1, "t1", "a1"))
        log.append(event(2, "t2", "a1"))
        log.append(event(3, "t1", "a2"))

        val all = log.readAll(null)
        assertEquals(3, all.size)
        assertEquals(listOf(3L, 2L, 1L), all.map { it.seq }) // newest first
    }

    @Test
    @DisplayName("readSince returns only events above the watermark")
    fun testReadSince() {
        log.append(event(1))
        log.append(event(2))
        log.append(event(3))
        log.append(event(4))

        val since = log.readSince(2)
        assertEquals(listOf(3L, 4L), since.map { it.seq })
    }

    @Test
    @DisplayName("deleteTask removes the task file")
    fun testDeleteTask() {
        log.append(event(1))
        log.append(event(2, "t2"))
        assertTrue(log.deleteTask("t1"))
        assertEquals(emptyList(), log.readTask("a1", "t1"))
        assertEquals(listOf(2L), log.readTask("a1", "t2").map { it.seq })
        assertFalse(log.deleteTask("t1"))
    }

    @Test
    @DisplayName("archiveExpired moves old files to the archive")
    fun testArchiveExpired() {
        log.append(event(1))
        val file = tempDir.resolve("events").resolve("a1").resolve("t1.jsonl")
        // Backdate the file beyond the TTL (TTL of 1 day, file "30 days" old).
        val old = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(old))

        log = AgentEventLog(tempDir, ttlDays = 1)
        val archived = log.archiveExpired()

        assertEquals(1, archived)
        assertTrue(tempDir.resolve(".archive").resolve("a1").resolve("t1.jsonl").exists())
        assertEquals(emptyList(), log.readTask("a1", "t1"))
    }
}
