package ai.platon.pulsar.chrome

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
 * Unit tests for [Browser4UserAgent] — the launch-time user agent that keeps a headless
 * Chrome from advertising `HeadlessChrome`.
 *
 * The Chrome install is faked as a directory layout (`<dir>/chrome.exe` plus a
 * `<dir>/<version>/` sibling), which is exactly what `ReducedUserAgent` reads, so the tests
 * need no real browser and are deterministic across machines.
 */
@DisplayName("Browser4UserAgent")
class Browser4UserAgentTest {

    /** Chrome 153 install layout: the launcher stub next to a version-named directory. */
    private fun fakeChromeInstall(dir: Path, major: Int = 153): Path {
        val application = Files.createDirectories(dir.resolve("Application"))
        Files.createDirectories(application.resolve("$major.0.8010.53"))
        return Files.createFile(application.resolve("chrome.exe"))
    }

    private fun settingsWith(configure: MutableConfig.() -> Unit = {}): BrowserSettings {
        val config = MutableConfig(loadDefaults = true).apply {
            // Pin the two keys the resolution depends on so a developer's private
            // application-private.properties cannot change the outcome of these tests.
            set(CapabilityTypes.BROWSER_LAUNCH_USER_AGENT, "")
            set(CapabilityTypes.BROWSER_LAUNCH_USER_AGENT_STEALTH, "true")
            configure()
        }
        return BrowserSettings(config)
    }

    // -------------------------------------------------------------------------
    // reducedUserAgentOrNull
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("derives the reduced user agent from the installed Chrome layout")
    fun derivesReducedUserAgentFromInstallLayout(@TempDir dir: Path) {
        val userAgent = Browser4UserAgent.reducedUserAgentOrNull(fakeChromeInstall(dir))

        assertNotNull(userAgent)
        assertTrue(userAgent!!.endsWith("Chrome/153${ReducedUserAgent.REDUCED_VERSION_SUFFIX} Safari/537.36"), userAgent)
        assertTrue(userAgent.startsWith("Mozilla/5.0 ("), userAgent)
    }

    @Test
    @DisplayName("the reduced user agent never carries the headless token")
    fun reducedUserAgentNeverCarriesTheHeadlessToken(@TempDir dir: Path) {
        val userAgent = Browser4UserAgent.reducedUserAgentOrNull(fakeChromeInstall(dir))

        assertNotNull(userAgent)
        assertFalse(ReducedUserAgent.isHeadless(userAgent), "still headless: $userAgent")
    }

    @Test
    @DisplayName("an unresolvable Chrome binary yields no user agent")
    fun unresolvableBinaryYieldsNoUserAgent() {
        assertNull(Browser4UserAgent.reducedUserAgentOrNull(null))
    }

    // -------------------------------------------------------------------------
    // resolveOrNull
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the default configuration resolves a headless-free user agent")
    fun defaultConfigurationResolvesAHeadlessFreeUserAgent(@TempDir dir: Path) {
        val userAgent = Browser4UserAgent.resolveOrNull(settingsWith(), fakeChromeInstall(dir))

        // This is the value `BrowserSettings.resolveUserAgent()` fails to produce: its
        // default path forwards an explicit null binary and short-circuits the lookup.
        assertNotNull(userAgent, "the default configuration must still produce a user agent")
        assertFalse(ReducedUserAgent.isHeadless(userAgent))
    }

    @Test
    @DisplayName("browser.launch.user.agent.stealth=false leaves the browser alone")
    fun stealthCanBeDisabled(@TempDir dir: Path) {
        val settings = settingsWith { set(CapabilityTypes.BROWSER_LAUNCH_USER_AGENT_STEALTH, "false") }

        assertNull(Browser4UserAgent.resolveOrNull(settings, fakeChromeInstall(dir)))
    }

    @Test
    @DisplayName("a configured user agent wins and has its headless token repaired")
    fun configuredUserAgentWinsAndIsRepaired(@TempDir dir: Path) {
        val configured = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) HeadlessChrome/153.0.0.0 Safari/537.36"
        val settings = settingsWith { set(CapabilityTypes.BROWSER_LAUNCH_USER_AGENT, configured) }

        val userAgent = Browser4UserAgent.resolveOrNull(settings, fakeChromeInstall(dir))

        assertEquals(configured.replace("HeadlessChrome/", "Chrome/"), userAgent)
    }

    // -------------------------------------------------------------------------
    // applyTo
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("applyTo sets --user-agent on an undecided launch")
    fun applyToSetsTheOption(@TempDir dir: Path) {
        val options = ChromeOptions().apply { headless = true }

        val applied = Browser4UserAgent.applyTo(options, settingsWith(), fakeChromeInstall(dir))

        assertTrue(applied)
        assertNotNull(options.userAgent)
        val rendered = options.toList()
        val userAgentArg = rendered.single { it.startsWith("--user-agent=") }
        assertFalse(
            ReducedUserAgent.isHeadless(userAgentArg.removePrefix("--user-agent=")),
            "the switch must not carry the headless token: $userAgentArg"
        )
        // The `--headless` switch itself is expected and must not be mistaken for a
        // headless user agent (the check above is case-insensitive on purpose).
        assertTrue(rendered.contains("--headless"), rendered.toString())
    }

    @Test
    @DisplayName("applyTo never overrides a user agent the caller already chose")
    fun applyToKeepsAnExplicitUserAgent(@TempDir dir: Path) {
        val explicit = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        val options = ChromeOptions().apply { userAgent = explicit }

        val applied = Browser4UserAgent.applyTo(options, settingsWith(), fakeChromeInstall(dir))

        assertFalse(applied)
        assertEquals(explicit, options.userAgent)
    }

    @Test
    @DisplayName("applyTo is a no-op when stealth is disabled")
    fun applyToIsANoOpWhenStealthIsDisabled(@TempDir dir: Path) {
        val options = ChromeOptions().apply { headless = true }
        val settings = settingsWith { set(CapabilityTypes.BROWSER_LAUNCH_USER_AGENT_STEALTH, "false") }

        val applied = Browser4UserAgent.applyTo(options, settings, fakeChromeInstall(dir))

        assertFalse(applied)
        assertNull(options.userAgent)
    }

    @Test
    @DisplayName("applyTo is a no-op when the Chrome version cannot be determined")
    fun applyToIsANoOpWithoutAChromeVersion() {
        val options = ChromeOptions().apply { headless = true }

        val applied = Browser4UserAgent.applyTo(options, settingsWith(), null)

        assertFalse(applied)
        assertNull(options.userAgent)
    }
}
