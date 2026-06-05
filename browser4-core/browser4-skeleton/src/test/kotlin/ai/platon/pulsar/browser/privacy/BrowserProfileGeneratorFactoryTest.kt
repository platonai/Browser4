package ai.platon.pulsar.browser.privacy

import ai.platon.pulsar.browser.privacy.BrowserProfileGeneratorFactory.Companion.BROWSER_CONTEXT_MODE_TO_AGENTS
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.PulsarSettings
import org.junit.jupiter.api.Assertions
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserProfileGeneratorFactoryTest {
    @Test
    fun testOverrideBrowserContextMode() {
        System.setProperty("browser.profile.mode", "prototype")

        val conf = ImmutableConfig()
        val factory = BrowserProfileGeneratorFactory(conf)
        val generator = factory.generator
        assertEquals(PrototypeBrowserProfileGenerator::class, generator::class)
        Assertions.assertTrue(generator is PrototypeBrowserProfileGenerator)

        // cached
        val generator2 = factory.generator
        Assertions.assertTrue { generator === generator2 }


        // BrowserProfileGeneratorFactory.generators is a companion, and the conf from the last test case is used
        // might be a bug
        // assertTrue { generator2.conf === conf }
    }

    @Test
    fun testOverridePulsarSettings() {
        val factory = BrowserProfileGeneratorFactory(ImmutableConfig())

        PulsarSettings.withSystemDefaultBrowser()

        val generator = factory.generator
        assertEquals(SystemDefaultBrowserProfileGenerator::class, generator::class)
        Assertions.assertTrue(generator is SystemDefaultBrowserProfileGenerator)
    }

    @Test
    fun testOverrideBrowserContextModeMatrix() {
        val conf = ImmutableConfig()
        val factory = BrowserProfileGeneratorFactory(conf)

        for ((modeValue, expectedClass) in BROWSER_CONTEXT_MODE_TO_AGENTS.entries) {
            System.setProperty("browser.profile.mode", modeValue.name)

            val generator = factory.generator
            Assertions.assertTrue(generator::class.java.isAssignableFrom(expectedClass.java)) {
                "Expected ${expectedClass.simpleName}, but got ${generator::class.java.simpleName}"
            }

            // Verify caching: the same instance should be returned
            val generator2 = factory.generator
            Assertions.assertTrue(generator === generator2) {
                "Instance was not cached for mode '$modeValue'"
            }

            // BrowserProfileGeneratorFactory.generators is a companion, and the conf from the last test case is used
            // might be a bug
            // assertTrue { generator2.conf === conf }
        }
    }
}
