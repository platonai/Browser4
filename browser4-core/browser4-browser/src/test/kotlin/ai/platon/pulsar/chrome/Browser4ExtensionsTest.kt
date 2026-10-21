package ai.platon.pulsar.chrome

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [Browser4Extensions] — the launch-time switch that disables user
 * extensions when `browser.extensions.inherit=false`.
 */
@DisplayName("Browser4Extensions")
class Browser4ExtensionsTest {

    private fun settingsWith(inherit: Boolean): BrowserSettings {
        val config = MutableConfig(loadDefaults = true).apply {
            set(B4Constants.EXTENSIONS_INHERIT, inherit.toString())
        }
        return BrowserSettings(config)
    }

    private fun settingsUnconfigured(): BrowserSettings {
        val config = MutableConfig(loadDefaults = true)
        return BrowserSettings(config)
    }

    // -------------------------------------------------------------------------
    // inherit = true (extension inheritance on — the default)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("inherit=true (default) does not add any extension flag")
    fun inheritTrueAddsNoFlag() {
        val options = ChromeOptions().apply { headless = true }

        val applied = Browser4Extensions.applyTo(options, settingsWith(true))

        assertFalse(applied, "applyTo should return false when inherit is true")
        val flags = options.toList()
        assertTrue(flags.none { it.contains("disable-extensions") },
            "no --disable-extensions flag should be present: $flags")
    }

    // -------------------------------------------------------------------------
    // inherit = false (environment purity on)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("inherit=false adds --disable-extensions to the launch options")
    fun inheritFalseAddsDisableExtensions() {
        val options = ChromeOptions().apply { headless = true }

        val applied = Browser4Extensions.applyTo(options, settingsWith(false))

        assertTrue(applied, "applyTo should return true when inherit is false")
        val flags = options.toList()
        assertTrue(flags.any { it.contains("disable-extensions") },
            "--disable-extensions flag should be present: $flags")
    }

    // -------------------------------------------------------------------------
    // default value regression — pin the backward-compatible default
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("unconfigured setting defaults to inherit=true (backward-compatible)")
    fun unconfiguredDefaultsToInheritTrue() {
        val options = ChromeOptions().apply { headless = true }

        val applied = Browser4Extensions.applyTo(options, settingsUnconfigured())

        assertFalse(applied,
            "the default (unconfigured) path must keep extensions inherited to avoid breaking users' login state")
        val flags = options.toList()
        assertTrue(flags.none { it.contains("disable-extensions") },
            "no --disable-extensions flag on the default path: $flags")
    }
}
