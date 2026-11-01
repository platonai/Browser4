package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.LauncherOptions
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EgoLiteBrowserLauncherTest {

    // -- EgoLiteBrowsers.isEgoLiteChannel ----------------------------------------

    @Test
    fun isEgoLiteChannelAcceptsAllAliases() {
        for (channel in listOf("ego-lite", "egolite", "ego lite", "ego", "EGO-LITE", " Ego ")) {
            assertTrue(EgoLiteBrowsers.isEgoLiteChannel(channel), "channel: $channel")
        }
    }

    @Test
    fun isEgoLiteChannelRejectsOtherChannels() {
        for (channel in listOf(null, "", "  ", "chrome", "google-chrome", "msedge", "firefox")) {
            assertFalse(EgoLiteBrowsers.isEgoLiteChannel(channel), "channel: $channel")
        }
    }

    // -- EgoLiteBrowsers.resolveBinaryOrNull --------------------------------------

    @Test
    fun resolveBinaryHonoursConfiguredEgoPath(@TempDir tempDir: Path) {
        val fakeEgo = Files.writeString(tempDir.resolve("ego.exe"), "MZ")
        val conf = MutableConfig().apply { set(EgoLiteBrowsers.EGO_PATH_PROPERTY, fakeEgo.toString()) }

        val resolved = EgoLiteBrowsers.resolveBinaryOrNull(conf)

        assertEquals(fakeEgo.toAbsolutePath(), resolved)
    }

    @Test
    fun resolveBinaryReturnsNullForNonExecutableConfiguredPath(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("no-such-ego.exe")
        val conf = MutableConfig().apply { set(EgoLiteBrowsers.EGO_PATH_PROPERTY, missing.toString()) }

        // A configured but unusable path must not fall through to the install-dir
        // candidates — the user named a binary, so silently launching another one
        // would hide the typo.
        assertNull(EgoLiteBrowsers.resolveBinaryOrNull(conf))
    }

    // -- EgoLiteBrowsers.parseBrowserMajorFromVersionJson ---------------------------

    @Test
    fun parseBrowserMajorReadsChromeProduct() {
        val json = """{"Browser":"Chrome/156.0.6829.0","Protocol-Version":"1.3"}"""
        assertEquals(156, EgoLiteBrowsers.parseBrowserMajorFromVersionJson(json))
    }

    @Test
    fun parseBrowserMajorReadsHeadlessChromeProduct() {
        val json = """{"Browser":"HeadlessChrome/156.0.6829.0"}"""
        assertEquals(156, EgoLiteBrowsers.parseBrowserMajorFromVersionJson(json))
    }

    @Test
    fun parseBrowserMajorReturnsNullWithoutBrowserField() {
        assertNull(EgoLiteBrowsers.parseBrowserMajorFromVersionJson("""{"Protocol-Version":"1.3"}"""))
    }

    // -- DefaultBrowserFactory channel dispatch -----------------------------------

    @Test
    fun launchRejectsUnknownBrowserChannel() {
        val conf = MutableConfig().apply { set(B4Constants.BROWSER_CHANNEL, "firefox") }
        val factory = DefaultBrowserFactory(conf)
        val settings = BrowserSettings(conf)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            factory.launch(BrowserId.createDefault(), LauncherOptions(settings), ChromeOptions())
        }

        assertTrue(exception.message!!.contains(B4Constants.BROWSER_CHANNEL), "message: ${exception.message}")
        assertTrue(exception.message!!.contains("firefox"), "message: ${exception.message}")
    }
}
