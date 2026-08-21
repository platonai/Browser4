package ai.platon.pulsar.linkstats.config

import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the [LinkstatsConfig] data class and its [LinkstatsConfig.fromConfig] factory.
 */
class LinkstatsConfigTest {

    @Test
    @DisplayName("default config is enabled with 50 minimum links")
    fun defaultConfigHasExpectedDefaults() {
        val config = LinkstatsConfig()

        assertTrue(config.enabled)
        assertEquals(50, config.minLinks)
    }

    @Test
    @DisplayName("fromConfig reads linkstats.enabled and linkstats.minLinks")
    fun fromConfigReadsPluginProperties() {
        // Note: ai.platon.pulsar.common.config.Config does not exist on this
        // repo's classpath (javap confirms), so build config via MutableConfig.
        val conf = MutableConfig()
        conf.setBoolean("linkstats.enabled", false)
        conf.setInt("linkstats.minLinks", 25)

        val config = LinkstatsConfig.fromConfig(conf)

        assertFalse(config.enabled)
        assertEquals(25, config.minLinks)
    }

    @Test
    @DisplayName("fromConfig falls back to defaults when keys are absent")
    fun fromConfigFallsBackToDefaults() {
        val config = LinkstatsConfig.fromConfig(MutableConfig())

        assertEquals(LinkstatsConfig(), config)
    }
}