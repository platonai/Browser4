package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.memory.MemoryToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [MemoryToolMountConfiguration] Spring bean wiring.
 *
 * `app.data.dir` and `knowledge.dir` are pointed at the temp dir so the
 * shared backend never touches the real user data directory.
 */
@DisplayName("MemoryToolMountConfiguration")
class MemoryToolMountConfigurationTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        System.setProperty("app.data.dir", tempDir.resolve("data").toString())
        System.setProperty("knowledge.dir", tempDir.resolve("knowledge").toString())
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("app.data.dir")
        System.clearProperty("knowledge.dir")
        try { tempDir.toFile().deleteRecursively() } catch (_: Exception) {}
    }

    @Nested
    @DisplayName("bean creation")
    inner class BeanCreation {
        @Test
        @DisplayName("agentMemory bean is created and usable")
        fun testAgentMemoryBean() {
            val config = MemoryToolMountConfiguration()
            val memory = config.agentMemory()
            try {
                assertNotNull(memory)
                // The backend is scoped to all agents (null agentUuid).
                assertEquals(null, memory.scope.agentUuid)
                // Sanity: the shared backend can answer a task listing.
                assertTrue(runBlocking { memory.queryService.listTasks(memory.scope).isEmpty() })
            } finally {
                memory.close()
            }
        }

        @Test
        @DisplayName("memoryToolExecutor bean is created with the memory backend")
        fun testMemoryToolExecutorBean() {
            val config = MemoryToolMountConfiguration()
            val memory = config.agentMemory()
            val executor = config.memoryToolExecutor(memory)

            assertNotNull(executor)
            assertEquals("memory", executor.domain)
            // Verify all four tool specs are registered
            val specs = executor.getToolSpecs()
            assertTrue(specs.containsKey("search"), "search tool spec should be registered")
            assertTrue(specs.containsKey("read"), "read tool spec should be registered")
            assertTrue(specs.containsKey("note"), "note tool spec should be registered")
            assertTrue(specs.containsKey("forget"), "forget tool spec should be registered")
            memory.close()
        }

        @Test
        @DisplayName("ToolMount.getToolExecutors returns the executor")
        fun testGetToolExecutors() {
            val config = MemoryToolMountConfiguration()
            val executors = config.getToolExecutors()

            assertNotNull(executors)
            assertEquals(1, executors.size)
            assertEquals("memory", executors[0].domain)
            assertTrue(executors[0] is MemoryToolExecutor)
            // Close the shared backend the executor owns (SQLite index lock).
            (executors[0] as MemoryToolExecutor).close()
        }
    }

    @Nested
    @DisplayName("ToolMount interface contract")
    inner class ToolMountContract {
        @Test
        @DisplayName("implements ToolMount interface")
        fun testImplementsToolMount() {
            val config = MemoryToolMountConfiguration()
            assertTrue(config is ToolMount, "Should implement ToolMount interface")
        }
    }
}
