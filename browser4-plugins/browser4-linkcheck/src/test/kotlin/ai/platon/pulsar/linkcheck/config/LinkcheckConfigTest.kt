package ai.platon.pulsar.linkcheck.config

import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkcheckConfigTest {

    @Test
    @DisplayName("default config enables linkcheck with info log level")
    fun defaultConfigUsesEnabledAndInfoLogLevel() {
        val config = LinkcheckConfig()
        assertTrue(config.enabled)
        assertEquals("info", config.logLevel)
    }

    @Test
    @DisplayName("fromConfig reads custom enabled and logLevel values")
    fun fromConfigReadsCustomValues() {
        val conf = MutableConfig().apply {
            set("linkcheck.enabled", "false")
            set("linkcheck.logLevel", "debug")
        }
        val config = LinkcheckConfig.fromConfig(conf)
        assertFalse(config.enabled)
        assertEquals("debug", config.logLevel)
    }
}
