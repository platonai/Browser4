package ai.platon.pulsar.pageinfo.config

import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [PageInfoConfig] data class and [PageInfoConfig.fromConfig] factory method.
 */
class PageInfoConfigTest {

    @Test
    @DisplayName("default config has expected defaults")
    fun testDefaultConfig() {
        val config = PageInfoConfig()

        assertEquals(2000, config.maxMetaLength)
        assertTrue(config.includeHeadings)
        assertFalse(config.includeLinks)
    }

    @Test
    @DisplayName("config accepts custom values")
    fun testCustomConfig() {
        val config = PageInfoConfig(
            maxMetaLength = 500,
            includeHeadings = false,
            includeLinks = true,
        )

        assertEquals(500, config.maxMetaLength)
        assertFalse(config.includeHeadings)
        assertTrue(config.includeLinks)
    }

    @Test
    @DisplayName("fromConfig returns defaults when keys are absent")
    fun testFromConfigDefaults() {
        val config = PageInfoConfig.fromConfig(ImmutableConfig())
        assertEquals(PageInfoConfig(), config)
    }

    @Test
    @DisplayName("PageInfoConfig is a data class with correct equals/hashCode")
    fun testEquality() {
        val config1 = PageInfoConfig(maxMetaLength = 100)
        val config2 = PageInfoConfig(maxMetaLength = 100)
        val config3 = PageInfoConfig(maxMetaLength = 500)

        assertEquals(config1, config2)
        assertNotEquals(config1, config3)
        assertEquals(config1.hashCode(), config2.hashCode())
    }
}
