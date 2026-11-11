package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.LauncherOptions
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.config.MutableConfig
import com.fasterxml.jackson.databind.ObjectMapper
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
        // resolveBinaryOrNull only honours a configured path that is executable,
        // so make the fixture binary runnable (cross-platform via File API).
        check(fakeEgo.toFile().setExecutable(true)) { "failed to make fixture executable" }
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

    // -- EgoLiteBrowsers.applyWebRtcIpHandlingPolicy --------------------------------

    @Test
    @org.junit.jupiter.api.DisplayName("WebRTC IP policy preference is written into a fresh profile")
    fun webRtcPolicyWrittenIntoFreshProfile() {
        val dir = Files.createTempDirectory("ego-webrtc-fresh")

        EgoLiteBrowsers.applyWebRtcIpHandlingPolicy(dir)

        val preferences = dir.resolve("Default").resolve("Preferences")
        assertTrue(Files.isRegularFile(preferences))
        val root = ObjectMapper().readTree(Files.readString(preferences))
        assertEquals(
            EgoLiteBrowsers.WEBRTC_IP_HANDLING_POLICY_VALUE,
            root.get("webrtc").get("ip_handling_policy").asText()
        )
    }

    @Test
    @org.junit.jupiter.api.DisplayName("WebRTC IP policy merge preserves existing preferences")
    fun webRtcPolicyMergePreservesExistingPreferences() {
        val dir = Files.createTempDirectory("ego-webrtc-merge")
        val preferences = dir.resolve("Default").resolve("Preferences")
        Files.createDirectories(preferences.parent)
        Files.writeString(preferences, """{"homepage": "https://example.com"}""")

        EgoLiteBrowsers.applyWebRtcIpHandlingPolicy(dir)

        val root = ObjectMapper().readTree(Files.readString(preferences))
        assertEquals("https://example.com", root.get("homepage").asText())
        assertEquals(
            EgoLiteBrowsers.WEBRTC_IP_HANDLING_POLICY_VALUE,
            root.get("webrtc").get("ip_handling_policy").asText()
        )
    }

    @Test
    @org.junit.jupiter.api.DisplayName("an existing WebRTC IP policy preference always wins")
    fun webRtcPolicyKeepsExistingPreferenceValue() {
        val dir = Files.createTempDirectory("ego-webrtc-keep")
        val preferences = dir.resolve("Default").resolve("Preferences")
        Files.createDirectories(preferences.parent)
        Files.writeString(preferences, """{"webrtc": {"ip_handling_policy": "default"}}""")

        EgoLiteBrowsers.applyWebRtcIpHandlingPolicy(dir)

        val root = ObjectMapper().readTree(Files.readString(preferences))
        assertEquals("default", root.get("webrtc").get("ip_handling_policy").asText())
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
