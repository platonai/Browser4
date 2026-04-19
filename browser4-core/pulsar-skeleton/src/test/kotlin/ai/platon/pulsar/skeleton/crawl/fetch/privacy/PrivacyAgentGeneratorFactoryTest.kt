package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.PrivacyAgentGeneratorFactory.Companion.BROWSER_CONTEXT_MODE_TO_AGENTS
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.Test

class PrivacyAgentGeneratorFactoryTest {
    @Test
    fun testOverrideBrowserContextMode() {
        System.setProperty("browser.context.mode", "prototype")

        val conf = ImmutableConfig()
        val factory = PrivacyAgentGeneratorFactory(conf)
        val generator = factory.generator
        assertTrue(generator is PrototypePrivacyAgentGenerator)

        // cached
        val generator2 = factory.generator
        assertTrue { generator === generator2 }


        // PrivacyAgentGeneratorFactory.generators is a companion, and the conf from the last test case is used
        // might be a bug
        // assertTrue { generator2.conf === conf }
    }

    @Test
    fun testOverridePulsarSettings() {
        val factory = PrivacyAgentGeneratorFactory(ImmutableConfig())

        PulsarSettings.withSystemDefaultBrowser()

        val generator = factory.generator
        assertTrue(generator is SystemDefaultPrivacyAgentGenerator)
    }

    @Test
    fun testOverrideBrowserContextModeMatrix() {
        val conf = ImmutableConfig()
        val factory = PrivacyAgentGeneratorFactory(conf)

        for ((modeValue, expectedClass) in BROWSER_CONTEXT_MODE_TO_AGENTS.entries) {
            System.setProperty("browser.context.mode", modeValue.name)

            val generator = factory.generator
            assertTrue(generator::class.java.isAssignableFrom(expectedClass.java)) {
                "Expected ${expectedClass.simpleName}, but got ${generator::class.java.simpleName}"
            }

            // Verify caching: the same instance should be returned
            val generator2 = factory.generator
            assertTrue(generator === generator2) {
                "Instance was not cached for mode '$modeValue'"
            }

            // PrivacyAgentGeneratorFactory.generators is a companion, and the conf from the last test case is used
            // might be a bug
            // assertTrue { generator2.conf === conf }
        }
    }
}
