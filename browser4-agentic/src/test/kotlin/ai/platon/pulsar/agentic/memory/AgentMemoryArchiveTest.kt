package ai.platon.pulsar.agentic.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Engine-side TTL hygiene: constructing an [AgentMemory] must archive expired
 * raw event files (the Spring shared backend does this on boot; the per-agent
 * memory must too, otherwise per-agent logs never get cleaned).
 */
@DisplayName("AgentMemory archive-on-construction")
class AgentMemoryArchiveTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    @Test
    @DisplayName("constructing an AgentMemory archives expired event files")
    fun testArchiveOnConstruction() {
        kotlinx.coroutines.runBlocking {
            // Pre-seed an expired event file (31 days old > 30-day TTL).
            val oldFile = tempDir.resolve("events").resolve("a1").resolve("old-task.jsonl")
            Files.createDirectories(oldFile.parent)
            Files.writeString(oldFile, """{"type":"task_started","seq":1,"ts":1,"agentUuid":"a1","taskId":"old-task","instruction":"x","engine":"cli"}""")
            val cutoff = System.currentTimeMillis() - 31L * 24 * 3600 * 1000
            Files.setLastModifiedTime(oldFile, FileTime.fromMillis(cutoff))

            // A fresh event file stays in place.
            val freshFile = tempDir.resolve("events").resolve("a1").resolve("fresh-task.jsonl")
            Files.writeString(freshFile, """{"type":"task_started","seq":2,"ts":2,"agentUuid":"a1","taskId":"fresh-task","instruction":"y","engine":"cli"}""")

            val memory = AgentMemory(MemoryScope(agentUuid = "a1"), rootDir = tempDir, knowledgeDir = tempDir.resolve("knowledge"))
            try {
                assertFalse(oldFile.exists(), "expired event file must be archived away")
                assertTrue(freshFile.exists(), "fresh event file must stay in place")
                assertTrue(tempDir.resolve(".archive").resolve("a1").resolve("old-task.jsonl").exists())
                // The archived task is no longer visible through the query service.
                assertEquals(emptyList(), memory.queryService.traceTask("old-task"))
            } finally {
                memory.close()
            }
        }
    }
}
