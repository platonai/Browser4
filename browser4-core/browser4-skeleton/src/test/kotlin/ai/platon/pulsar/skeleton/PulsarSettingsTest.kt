package ai.platon.pulsar.skeleton

import ai.platon.pulsar.browser.InteractSettings
import ai.platon.pulsar.common.browser.InteractLevel
import org.junit.jupiter.api.Assertions.assertEquals
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
}

