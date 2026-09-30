package ai.platon.pulsar.agentic.memory

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("MemoryConfig with system-property isolation")
class MemoryConfigIsolationTest {

    @Test
    @DisplayName("reads configuration from system properties and restores them after")
    fun testReadAndRestore() {
        SystemPropertyGuard.withProperties(
            "browser4.agent.memory.enabled" to "false",
            "browser4.agent.memory.index.backend" to "none",
            "browser4.agent.memory.external.enabled" to "true",
            "browser4.agent.memory.external.toolPrefix" to "memo",
        ) {
            assertFalse(MemoryConfig.enabled)
            assertEquals("none", MemoryConfig.indexBackend)
            assertTrue(MemoryConfig.externalEnabled)
            assertEquals("memo", MemoryConfig.externalToolPrefix)
        }
        // Restored to the JVM defaults.
        assertTrue(MemoryConfig.enabled)
        assertEquals("sqlite", MemoryConfig.indexBackend)
        assertFalse(MemoryConfig.externalEnabled)
    }

    @Test
    @DisplayName("clearing a property falls back to the default")
    fun testClearFallsBack() {
        SystemPropertyGuard.withProperties("browser4.agent.memory.enabled" to null) {
            assertTrue(MemoryConfig.enabled, "cleared property must fall back to the default 'true'")
        }
    }

    @Test
    @DisplayName("external defaults are conservative")
    fun testExternalDefaults() {
        assertFalse(MemoryConfig.externalEnabled)
        assertEquals("stdio", MemoryConfig.externalTransport)
        assertEquals("mem", MemoryConfig.externalToolPrefix)
        assertTrue(MemoryConfig.externalToolAllowlist.isEmpty())
    }
}
