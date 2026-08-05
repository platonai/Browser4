package ai.platon.pulsar.skeleton

import ai.platon.pulsar.api.InteractSettings
import ai.platon.pulsar.api.model.DisplayMode
import ai.platon.pulsar.common.browser.InteractLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PulsarSettingsTest {
    @Test
    fun parseAcceptsFastestInteractLevelAlias() {
        val settings = PulsarSettings.parse(
            mapOf(
                "interactLevel" to "FASTEST",
            )
        )

        assertEquals(InteractSettings.create(InteractLevel.FASTEST), settings.interactSettings)
    }

    @Test
    fun parseAcceptsKebabCaseInteractLevelCapability() {
        val settings = PulsarSettings.parse(
            mapOf(
                "interact-level" to "FAST",
            )
        )

        assertEquals(InteractSettings.create(InteractLevel.FAST), settings.interactSettings)
    }

    // -- displayMode / headed -------------------------------------------------

    @Test
    fun parseDisplayModeStringHeadless() {
        val settings = PulsarSettings.parse(mapOf("displayMode" to "HEADLESS"))
        assertEquals(DisplayMode.HEADLESS, settings.displayMode)
    }

    @Test
    fun parseDisplayModeStringGUI() {
        val settings = PulsarSettings.parse(mapOf("displayMode" to "GUI"))
        assertEquals(DisplayMode.GUI, settings.displayMode)
    }

    @Test
    fun parseHeadedBooleanTrue() {
        val settings = PulsarSettings.parse(mapOf("headed" to true))
        assertEquals(DisplayMode.GUI, settings.displayMode)
    }

    @Test
    fun parseHeadedBooleanFalse() {
        val settings = PulsarSettings.parse(mapOf("headed" to false))
        assertEquals(DisplayMode.HEADLESS, settings.displayMode)
    }

    @Test
    fun parseHeadedStringTrue() {
        val settings = PulsarSettings.parse(mapOf("headed" to "true"))
        assertEquals(DisplayMode.GUI, settings.displayMode)
    }

    @Test
    fun parseHeadedStringFalse() {
        val settings = PulsarSettings.parse(mapOf("headed" to "false"))
        assertEquals(DisplayMode.HEADLESS, settings.displayMode)
    }

    @Test
    fun parseDisplayModeTakesPriorityOverHeaded() {
        // displayMode string should win even when headed is also set.
        val settings = PulsarSettings.parse(
            mapOf("displayMode" to "HEADLESS", "headed" to true)
        )
        assertEquals(DisplayMode.HEADLESS, settings.displayMode)
    }

    @Test
    fun parseWithoutDisplayModeOrHeadedReturnsNullDisplayMode() {
        val settings = PulsarSettings.parse(mapOf<String, Any>())
        assertNull(settings.displayMode)
    }

    @Test
    fun parseWithNullCapabilitiesReturnsNullDisplayMode() {
        val settings = PulsarSettings.parse(null)
        assertNull(settings.displayMode)
    }

    @Test
    fun parseWithHeadedInvalidStringReturnsNullDisplayMode() {
        // An unparseable value for "headed" should not blow up; it falls through.
        val settings = PulsarSettings.parse(mapOf("headed" to "not-a-bool"))
        assertNull(settings.displayMode)
    }
}
