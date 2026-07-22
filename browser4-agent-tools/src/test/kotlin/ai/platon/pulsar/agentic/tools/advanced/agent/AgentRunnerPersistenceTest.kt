package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path

class AgentRunnerPersistenceTest {

    private val objectMapper = pulsarObjectMapper()

    // -----------------------------------------------------------------
    // Persistence restore
    // -----------------------------------------------------------------

    @Test
    fun `restoreFromDisk loads tasks from JSONL file`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("agent-tasks.jsonl")
        val task1 = AgentTaskStatus(id = "a1", statusCode = 201).apply {
            startedTime = null; lastModifiedTime = null; finishTime = null
        }
        val task2 = AgentTaskStatus(id = "a2", statusCode = 200, processState = "done").apply {
            startedTime = null; lastModifiedTime = null; finishTime = null
        }
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            objectMapper.writeValueAsString(task1) + "\n" +
            objectMapper.writeValueAsString(task2) + "\n"
        )

        val runner = TestableAgentRunner(tempDir)
        runner.restoreFromDisk()

        assertEquals(2, runner.cacheSize())
        assertNotNull(runner.getStatus("a1"))
        assertEquals("a2", runner.getStatus("a2")!!.id)
        assertEquals("done", runner.getStatus("a2")!!.processState)
    }

    @Test
    fun `restoreFromDisk handles missing file gracefully`(@TempDir tempDir: Path) {
        val runner = TestableAgentRunner(tempDir)
        runner.restoreFromDisk()
        assertEquals(0, runner.cacheSize())
    }

    @Test
    fun `restoreFromDisk skips corrupt lines`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("agent-tasks.jsonl")
        val task = AgentTaskStatus(id = "good", statusCode = 200).apply {
            startedTime = null; lastModifiedTime = null; finishTime = null
        }
        Files.createDirectories(tempDir)
        Files.writeString(
            jsonlPath,
            "{this is not valid json}\n" +
            "\n" +
            objectMapper.writeValueAsString(task) + "\n"
        )

        val runner = TestableAgentRunner(tempDir)
        runner.restoreFromDisk()
        assertEquals(1, runner.cacheSize())
        assertNotNull(runner.getStatus("good"))
    }

    @Test
    fun `restoreFromDisk empty file returns zero tasks`(@TempDir tempDir: Path) {
        val jsonlPath = tempDir.resolve("agent-tasks.jsonl")
        Files.createDirectories(tempDir)
        Files.writeString(jsonlPath, "")

        val runner = TestableAgentRunner(tempDir)
        runner.restoreFromDisk()
        assertEquals(0, runner.cacheSize())
    }

    // -----------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------

    private class TestableAgentRunner(tempDir: Path) : StatefulAgentRunner(
        Mockito.mock(AgenticSession::class.java)
    ) {
        init {
            // Point persistence to temp dir so we don't touch real data.
            val fileField = persistence.javaClass.getDeclaredField("file")
            fileField.isAccessible = true
            fileField.set(persistence, tempDir.resolve("agent-tasks.jsonl"))
        }

        fun cacheSize(): Int {
            val field = StatefulAgentRunner::class.java.getDeclaredField("statusCache")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val cache = field.get(this) as com.github.benmanes.caffeine.cache.Cache<*, *>
            return cache.estimatedSize().toInt()
        }
    }
}
