package ai.platon.pulsar.chrome

import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_LAUNCH_CHROME_ARGS
import ai.platon.pulsar.common.config.VolatileConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [HeadlessUserAgent].
 *
 * The pure helpers are asserted directly; the machine-dependent lookup is asserted through its
 * contract (argument present ⇒ the configuration is extended, argument absent ⇒ it is not), so the
 * test is deterministic on a machine with and without Chrome installed.
 */
@DisplayName("Headless user agent fix")
class HeadlessUserAgentTest {

    private val headlessUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) HeadlessChrome/153.0.0.0 Safari/537.36"

    @Test
    @DisplayName("the headless token is replaced and every other token is kept")
    fun testToNonHeadlessUserAgent() {
        val fixed = HeadlessUserAgent.toNonHeadlessUserAgent(headlessUa)

        assertEquals(
            headlessUa.replace("HeadlessChrome", "Chrome"),
            fixed,
            "only the HeadlessChrome token may change"
        )
        assertFalse(fixed!!.contains(HeadlessUserAgent.HEADLESS_TOKEN))
        assertTrue(fixed.contains("Chrome/153.0.0.0"))
        assertTrue(fixed.contains("Windows NT 10.0; Win64; x64"))
    }

    @Test
    @DisplayName("a User-Agent without the headless token is left alone")
    fun testToNonHeadlessUserAgentWithoutToken() {
        assertNull(HeadlessUserAgent.toNonHeadlessUserAgent(headlessUa.replace("HeadlessChrome", "Chrome")))
        assertNull(HeadlessUserAgent.toNonHeadlessUserAgent(null))
        assertNull(HeadlessUserAgent.toNonHeadlessUserAgent(""))
    }

    @Test
    @DisplayName("the reduced User-Agent carries the major version and the platform tokens")
    fun testBuildReducedUserAgent() {
        val windows = HeadlessUserAgent.buildReducedUserAgent("153.0.8010.52", "Windows 11", "amd64")
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36",
            windows
        )

        val mac = HeadlessUserAgent.buildReducedUserAgent("153.0.8010.52", "Mac OS X", "aarch64")
        assertTrue(mac.contains("Macintosh; Intel Mac OS X 10_15_7"), mac)
        assertTrue(mac.contains("Chrome/153.0.0.0"), mac)

        val linuxArm = HeadlessUserAgent.buildReducedUserAgent("153.0.8010.52", "Linux", "aarch64")
        assertTrue(linuxArm.contains("X11; Linux aarch64"), linuxArm)

        val linuxX64 = HeadlessUserAgent.buildReducedUserAgent("153.0.8010.52", "Linux", "amd64")
        assertTrue(linuxX64.contains("X11; Linux x86_64"), linuxX64)
    }

    @Test
    @DisplayName("the Chrome version is read from the version-named install directory")
    fun testResolveChromeVersionFromInstallDirectory(@TempDir tempDir: Path) {
        val applicationDir = tempDir.resolve("Application")
        Files.createDirectories(applicationDir.resolve("153.0.8010.52"))
        Files.createDirectories(applicationDir.resolve("151.0.7000.10"))
        val binary = applicationDir.resolve("chrome.exe")
        Files.writeString(binary, "stub")

        assertEquals("153.0.8010.52", HeadlessUserAgent.resolveChromeVersion(binary))
    }

    @Test
    @DisplayName("an install directory without a version directory yields no version")
    fun testResolveChromeVersionWithoutVersionDirectory(@TempDir tempDir: Path) {
        val binary = tempDir.resolve("not-a-browser")
        Files.writeString(binary, "stub")

        assertNull(HeadlessUserAgent.resolveChromeVersion(binary))
    }

    @Test
    @DisplayName("an explicitly configured User-Agent always wins")
    fun testFixUserAgentKeepsConfiguredUserAgent() {
        val conf = VolatileConfig()
        val configured = "--user-agent=\"Mozilla/5.0 (X11; Linux x86_64) Chrome/153.0.0.0\""
        conf[BROWSER_LAUNCH_CHROME_ARGS] = configured

        assertFalse(HeadlessUserAgent.fixUserAgent(conf), "a configured User-Agent must not be overridden")
        assertEquals(configured, conf[BROWSER_LAUNCH_CHROME_ARGS])
    }

    @Test
    @DisplayName("the fix can be disabled through its configuration key")
    fun testFixUserAgentCanBeDisabled() {
        val conf = VolatileConfig()
        conf[HeadlessUserAgent.ENABLED_KEY] = "false"

        assertFalse(HeadlessUserAgent.fixUserAgent(conf))
        assertNull(conf[BROWSER_LAUNCH_CHROME_ARGS])
    }

    @Test
    @DisplayName("the launch argument is applied exactly when the local Chrome version is known")
    fun testFixUserAgentContract() {
        val conf = VolatileConfig()
        val argument = HeadlessUserAgent.chromeArgument()

        val changed = HeadlessUserAgent.fixUserAgent(conf)

        if (argument == null) {
            assertFalse(changed, "without a resolvable Chrome version the configuration must stay untouched")
            assertNull(conf[BROWSER_LAUNCH_CHROME_ARGS])
        } else {
            assertTrue(changed, "the resolved argument must be written to the configuration")
            assertEquals(argument, conf[BROWSER_LAUNCH_CHROME_ARGS])
            assertTrue(argument.startsWith("--user-agent=\""), argument)
            assertFalse(argument.contains(HeadlessUserAgent.HEADLESS_TOKEN), argument)
        }
    }

    @Test
    @DisplayName("existing Chrome arguments are preserved when the argument is appended")
    fun testFixUserAgentPreservesExistingArguments() {
        val argument = HeadlessUserAgent.chromeArgument() ?: return
        val conf = VolatileConfig()
        conf[BROWSER_LAUNCH_CHROME_ARGS] = "--disable-features=Translate"

        assertTrue(HeadlessUserAgent.fixUserAgent(conf))
        assertEquals("--disable-features=Translate $argument", conf[BROWSER_LAUNCH_CHROME_ARGS])
    }
}
