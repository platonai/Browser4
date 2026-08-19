package ai.platon.pulsar.forms.config

import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class FormsConfigTest {

    @Test
    @DisplayName("default config has expected defaults")
    fun testDefaultConfig() {
        val config = FormsConfig()
        assertEquals(20, config.maxFieldDetails)
        assertTrue(config.includePerFormDetails)
        assertEquals(50, config.maxFormDetails)
    }

    @Test
    @DisplayName("fromConfig returns defaults when keys are absent")
    fun testFromConfigDefaults() {
        assertEquals(FormsConfig(), FormsConfig.fromConfig(ImmutableConfig()))
    }

    @Test
    @DisplayName("FormsConfig is a data class with correct equals/hashCode")
    fun testEquality() {
        val c1 = FormsConfig(maxFieldDetails = 5)
        val c2 = FormsConfig(maxFieldDetails = 5)
        val c3 = FormsConfig(maxFieldDetails = 99)
        assertEquals(c1, c2)
        assertNotEquals(c1, c3)
        assertEquals(c1.hashCode(), c2.hashCode())
    }
}
