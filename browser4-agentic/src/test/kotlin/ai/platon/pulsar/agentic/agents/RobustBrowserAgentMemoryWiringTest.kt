package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.memory.AgentMemory
import ai.platon.pulsar.agentic.memory.MemoryToolTarget
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Engine wiring: touching `agentMemory` must register the memory.* tools
 * (global executor) and bind the per-agent dispatch target, without writing
 * into the real user data directory.
 */
@DisplayName("RobustBrowserAgent memory wiring")
class RobustBrowserAgentMemoryWiringTest {

    @TempDir
    lateinit var tempDir: Path

    private var registered = false

    @AfterEach
    fun tearDown() {
        // If the test registered the memory domain, leave the JVM-global
        // registry clean for other tests.
        if (registered) CustomToolRegistry.instance.unregister("memory")
    }

    @Test
    @DisplayName("agentMemory initialization registers tools and binds the per-agent target")
    fun testAgentMemoryWiring() {
        // Isolate the PEM knowledge store into the temp dir (the default would
        // be cwd-relative "knowledge").
        ai.platon.pulsar.agentic.memory.SystemPropertyGuard.withProperties(
            "knowledge.dir" to tempDir.resolve("knowledge").toString(),
        ) {
            val session = mockk<AgenticSession>(relaxed = true)
            val agent = TestAgent(session)
            agent.agentMemoryRootDirOverride = tempDir
            try {
                val memory = agent.agentMemory
                assertEquals(agent.uuid.toString(), memory.scope.agentUuid)

                // Global executor registered (stateless).
                val executor = CustomToolRegistry.instance.get("memory")
                assertNotNull(executor, "memory.* executor must be registered in CustomToolRegistry")
                assertEquals("memory", executor.domain)
                registered = true

                // Per-agent dispatch target bound.
                assertTrue(agent.agentToolManager.hasCustomTarget("memory"))
                val target = agent.agentToolManager.customTargets["memory"]
                assertTrue(target is MemoryToolTarget)
                assertEquals(memory, (target as MemoryToolTarget).memory)

                // The memory backend is fully usable.
                assertNotNull(memory.queryService)
                assertNotNull(memory.scratchpad)
            } finally {
                agent.close()
            }
        }
    }

    @Test
    @DisplayName("agentMemory is lazy — untouched agents create nothing")
    fun testAgentMemoryLazy() {
        val session = mockk<AgenticSession>(relaxed = true)
        val agent = TestAgent(session)
        try {
            // Never touch agentMemory; nothing must have been created/registered.
            assertTrue(!agent.isAgentMemoryInitialized)
            assertEquals(null, CustomToolRegistry.instance.get("memory"))
        } finally {
            agent.close()
        }
    }

    private class TestAgent(session: AgenticSession) : RobustBrowserAgent(session)
}
