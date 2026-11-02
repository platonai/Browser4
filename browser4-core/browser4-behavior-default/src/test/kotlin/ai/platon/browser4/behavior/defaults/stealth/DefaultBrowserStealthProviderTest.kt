package ai.platon.browser4.behavior.defaults.stealth

import ai.platon.browser4.behavior.BrowserBehaviorEdition
import ai.platon.browser4.behavior.stealth.StealthLaunchContext
import ai.platon.browser4.behavior.stealth.StealthSessionContext
import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.model.ReducedUserAgent
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for the default edition's [DefaultBrowserStealthProvider].
 *
 * The provider is the module seam, so the tests pin both what it applies to a launch and the
 * metadata the registry selects it by: a renamed [DefaultBrowserStealthProvider.ID] or a
 * changed default edition would silently change which policy a deployment runs.
 */
@DisplayName("DefaultBrowserStealthProvider")
class DefaultBrowserStealthProviderTest {

    /** Chrome 153 install layout (`Application/chrome.exe` next to `Application/153.0.8010.53`). */
    private fun fakeChromeInstall(dir: Path, major: Int = 153): Path {
        val application = Files.createDirectories(dir.resolve("Application"))
        Files.createDirectories(application.resolve("$major.0.8010.53"))
        return Files.createFile(application.resolve("chrome.exe"))
    }

    private fun settingsWith(configure: MutableConfig.() -> Unit = {}): BrowserSettings {
        val config = MutableConfig(loadDefaults = true).apply {
            set(CapabilityTypes.BROWSER_LAUNCH_USER_AGENT, "")
            set(CapabilityTypes.BROWSER_LAUNCH_USER_AGENT_STEALTH, "true")
            configure()
        }
        return BrowserSettings(config)
    }

    private fun provider(binary: Path?): DefaultBrowserStealthProvider =
        DefaultBrowserStealthProvider { binary }

    @Test
    @DisplayName("metadata identifies the default edition for registry selection")
    fun metadataIdentifiesTheDefaultEdition() {
        val provider = DefaultBrowserStealthProvider()

        assertEquals("default", provider.id)
        assertEquals(BrowserBehaviorEdition.DEFAULT, provider.edition)
        assertEquals(0, provider.priority, "an edition module installed next to it must outrank it")
    }

    @Test
    @DisplayName("a headless launch gets the reduced user agent and the report says so")
    fun headlessLaunchGetsTheReducedUserAgent(@TempDir dir: Path) {
        val binary = fakeChromeInstall(dir)
        val options = ChromeOptions().apply { headless = true }

        val report = provider(binary).applyLaunchOptions(StealthLaunchContext(options, settingsWith()))

        val userAgent = options.userAgent
        assertNotNull(userAgent, "the provider must put the reduced user agent on the launch options")
        assertFalse(ReducedUserAgent.isHeadless(userAgent!!), "still headless: $userAgent")
        assertEquals(userAgent, report.userAgent, "the report must name the applied user agent")
        assertEquals("default", report.providerId)
        assertEquals(BrowserBehaviorEdition.DEFAULT, report.edition)
        assertTrue(report.modified)
    }

    @Test
    @DisplayName("the environment-purity policy reaches the launch options and the report")
    fun extensionPurityReachesTheLaunchOptions(@TempDir dir: Path) {
        val options = ChromeOptions().apply { headless = true }
        val settings = settingsWith {
            set(B4Constants.EXTENSIONS_INHERIT, "false")
        }

        val report = provider(fakeChromeInstall(dir)).applyLaunchOptions(StealthLaunchContext(options, settings))

        assertTrue(options.disableExtensions, "browser.extensions.inherit=false must disable extensions")
        assertTrue(report.extensionsDisabled)
        assertTrue(report.modified)
    }

    @Test
    @DisplayName("a caller-decided user agent is never overridden or claimed")
    fun explicitUserAgentIsNotClaimed(@TempDir dir: Path) {
        val explicit = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        val options = ChromeOptions().apply {
            headless = true
            userAgent = explicit
        }

        val report = provider(fakeChromeInstall(dir)).applyLaunchOptions(StealthLaunchContext(options, settingsWith()))

        assertEquals(explicit, options.userAgent)
        assertNull(report.userAgent, "the report must only claim what this call changed")
    }

    @Test
    @DisplayName("a headed launch stays untouched (the switch is not free)")
    fun headedLaunchStaysUntouched(@TempDir dir: Path) {
        val options = ChromeOptions().apply { headless = false }

        val report = provider(fakeChromeInstall(dir)).applyLaunchOptions(StealthLaunchContext(options, settingsWith()))

        assertNull(options.userAgent)
        assertNull(report.userAgent)
        assertFalse(report.modified)
    }

    @Test
    @DisplayName("the default edition contributes no extra document-start scripts")
    fun defaultEditionAddsNoDocumentStartScripts(@TempDir dir: Path) {
        val scripts = provider(fakeChromeInstall(dir))
            .documentStartScripts(StealthSessionContext(settingsWith()))

        assertTrue(
            scripts.isEmpty(),
            "the baseline page-world payload is the js/stealth.js resource, not a provider script: $scripts"
        )
    }

    @Test
    @DisplayName("browser.stealth.enabled=false suppresses the user-agent rewrite")
    fun masterStealthSwitchSuppressesTheUserAgent(@TempDir dir: Path) {
        val binary = fakeChromeInstall(dir)
        val options = ChromeOptions().apply { headless = true }
        val settings = settingsWith { set(B4Constants.STEALTH_ENABLED, "false") }

        val report = provider(binary).applyLaunchOptions(StealthLaunchContext(options, settings))

        assertNull(options.userAgent, "the master switch must suppress the derived reduced UA")
        assertNull(report.userAgent)
    }

    @Test
    @DisplayName("a fingerprint-browser channel is never given a Browser4 user agent")
    fun fingerprintChannelIsNeverGivenABrowser4UserAgent(@TempDir dir: Path) {
        val binary = fakeChromeInstall(dir)
        val options = ChromeOptions().apply { headless = true }

        val report = provider(binary).applyLaunchOptions(
            StealthLaunchContext(options, settingsWith(), channel = B4Constants.BROWSER_CHANNEL_EGO_LITE)
        )

        assertNull(options.userAgent, "ego lite owns its user agent and its own anti-detection behaviour")
        assertNull(report.userAgent)
        assertFalse(report.modified)
    }

    @Test
    @DisplayName("the baseline page-world payload is enabled by default and for an ordinary channel")
    fun baselinePayloadIsEnabledByDefault() {
        val provider = DefaultBrowserStealthProvider()

        assertTrue(provider.baselinePageWorldScriptsEnabled(StealthSessionContext(settingsWith())))
        assertTrue(
            provider.baselinePageWorldScriptsEnabled(
                StealthSessionContext(settingsWith { set(B4Constants.BROWSER_CHANNEL, "chrome") })
            )
        )
    }

    @Test
    @DisplayName("the baseline page-world payload is suppressed by the master switch and by a fingerprint channel")
    fun baselinePayloadIsSuppressedForFingerprintSessions() {
        val provider = DefaultBrowserStealthProvider()

        assertFalse(
            provider.baselinePageWorldScriptsEnabled(
                StealthSessionContext(settingsWith { set(B4Constants.STEALTH_ENABLED, "false") })
            )
        )
        assertFalse(
            provider.baselinePageWorldScriptsEnabled(
                StealthSessionContext(
                    settingsWith { set(B4Constants.BROWSER_CHANNEL, B4Constants.BROWSER_CHANNEL_EGO_LITE) }
                )
            )
        )
    }
}
