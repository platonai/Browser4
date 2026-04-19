package ai.platon.pulsar.external

import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMConfigTest {

    @Test
    fun testEnvStyleConfig() {
        System.setProperty("OPENROUTER_API_KEY", "testAPI.key")
        val conf = ImmutableConfig()
        assertEquals("testAPI.key", conf["OPENROUTER_API_KEY"])
        val configured = ChatModelFactory.isModelConfigured(conf)
        assertTrue(configured, "Model should be configured")
    }

    @Test
    fun testSpringStyleConfig() {
        System.setProperty("openrouter.api.key", "testAPI.key")
        val conf = ImmutableConfig()
        assertTrue { ChatModelFactory.isModelConfigured(conf) }
    }
}
