package ai.platon.pulsar.skeleton

import ai.platon.browser4.driver.common.InteractSettings
import ai.platon.pulsar.common.browser.InteractLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PulsarSettingsTest {
    @Test
    fun parseAcceptsFatestInteractLevelAlias() {
        val settings = PulsarSettings.parse(
            mapOf(
                "interactLevel" to "FATEST",
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
}

