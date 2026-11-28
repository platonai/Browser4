package ai.platon.pulsar.protocol.browser.resolver

import ai.platon.pulsar.common.config.MutableConfig
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BrowserBinaryResolverRegistryTest {

    // -- default resolver selection --------------------------------------------

    @Test
    fun selectNullReturnsChromeResolver() {
        assertSame(BrowserBinaryResolverRegistry.chromeResolver, BrowserBinaryResolverRegistry.select(null))
    }

    @Test
    fun selectBlankReturnsChromeResolver() {
        assertSame(BrowserBinaryResolverRegistry.chromeResolver, BrowserBinaryResolverRegistry.select("  "))
    }

    @Test
    fun selectChromeAliasesReturnChromeResolver() {
        for (alias in listOf("chrome", "Chrome", "CHROME", "google-chrome", "Google Chrome")) {
            assertSame(BrowserBinaryResolverRegistry.chromeResolver, BrowserBinaryResolverRegistry.select(alias), "alias: $alias")
        }
    }

    @Test
    fun selectEgoLiteAliasesReturnEgoResolver() {
        for (alias in listOf("ego-lite", "egolite", "ego lite", "ego", "EGO-LITE", " Ego ")) {
            assertSame(BrowserBinaryResolverRegistry.egoLiteResolver, BrowserBinaryResolverRegistry.select(alias), "alias: $alias")
        }
    }

    @Test
    fun selectRejectsUnknownChannel() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            BrowserBinaryResolverRegistry.select("firefox")
        }
        assertTrue(exception.message!!.contains("firefox"), "message: ${exception.message}")
        assertTrue(exception.message!!.contains("Supported channels"), "message: ${exception.message}")
    }

    // -- stealth delegation ---------------------------------------------------

    @Test
    fun stealthAppliesToChrome() {
        assertTrue(BrowserBinaryResolverRegistry.stealthAppliesTo(null))
        assertTrue(BrowserBinaryResolverRegistry.stealthAppliesTo("chrome"))
    }

    @Test
    fun stealthDoesNotApplyToEgoLite() {
        assertFalse(BrowserBinaryResolverRegistry.stealthAppliesTo("ego-lite"))
        assertFalse(BrowserBinaryResolverRegistry.stealthAppliesTo("ego"))
    }

    // -- default resolver properties -------------------------------------------

    @Test
    fun chromeResolverStealthInjectable() {
        assertTrue(BrowserBinaryResolverRegistry.chromeResolver.stealthInjectable)
        assertNull(BrowserBinaryResolverRegistry.chromeResolver.resolveBinary(null))
    }

    @Test
    fun egoLiteResolverNotStealthInjectable() {
        assertFalse(BrowserBinaryResolverRegistry.egoLiteResolver.stealthInjectable)
    }
}

class EgoLiteBinaryResolverTest {

    private val resolver = BrowserBinaryResolverRegistry.egoLiteResolver as EgoLiteBinaryResolver

    // -- resolveBinary priority -----------------------------------------------

    @Test
    fun resolveBinaryHonoursConfiguredEgoPath(@TempDir tempDir: Path) {
        val fakeEgo = Files.writeString(tempDir.resolve("ego.exe"), "MZ")
        check(fakeEgo.toFile().setExecutable(true)) { "failed to make fixture executable" }
        val conf = MutableConfig().apply { set(EgoLiteBinaryResolver.EGO_PATH_PROPERTY, fakeEgo.toString()) }

        val resolved = resolver.resolveBinary(conf)

        assertEquals(fakeEgo.toAbsolutePath(), resolved)
    }

    @Test
    fun resolveBinaryReturnsNullForNonExecutableConfiguredPath(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("no-such-ego.exe")
        val conf = MutableConfig().apply { set(EgoLiteBinaryResolver.EGO_PATH_PROPERTY, missing.toString()) }

        assertNull(resolver.resolveBinary(conf))
    }

    // -- postLaunchHook WebRTC policy -----------------------------------------

    @Test
    @org.junit.jupiter.api.DisplayName("WebRTC IP policy preference is written into a fresh profile")
    fun webRtcPolicyWrittenIntoFreshProfile(@TempDir tempDir: Path) {
        resolver.postLaunchHook(tempDir)

        val preferences = tempDir.resolve("Default").resolve("Preferences")
        assertTrue(Files.isRegularFile(preferences))
        val root = ObjectMapper().readTree(Files.readString(preferences))
        assertEquals(
            EgoLiteBinaryResolver.WEBRTC_IP_HANDLING_POLICY_VALUE,
            root.get("webrtc").get("ip_handling_policy").asText()
        )
    }

    @Test
    @org.junit.jupiter.api.DisplayName("WebRTC IP policy merge preserves existing preferences")
    fun webRtcPolicyMergePreservesExistingPreferences(@TempDir tempDir: Path) {
        val preferences = tempDir.resolve("Default").resolve("Preferences")
        Files.createDirectories(preferences.parent)
        Files.writeString(preferences, """{"homepage": "https://example.com"}""")

        resolver.postLaunchHook(tempDir)

        val root = ObjectMapper().readTree(Files.readString(preferences))
        assertEquals("https://example.com", root.get("homepage").asText())
        assertEquals(
            EgoLiteBinaryResolver.WEBRTC_IP_HANDLING_POLICY_VALUE,
            root.get("webrtc").get("ip_handling_policy").asText()
        )
    }

    @Test
    @org.junit.jupiter.api.DisplayName("an existing WebRTC IP policy preference always wins")
    fun webRtcPolicyKeepsExistingPreferenceValue(@TempDir tempDir: Path) {
        val preferences = tempDir.resolve("Default").resolve("Preferences")
        Files.createDirectories(preferences.parent)
        Files.writeString(preferences, """{"webrtc": {"ip_handling_policy": "default"}}""")

        resolver.postLaunchHook(tempDir)

        val root = ObjectMapper().readTree(Files.readString(preferences))
        assertEquals("default", root.get("webrtc").get("ip_handling_policy").asText())
    }
}

// ---- PlaceholderExpander tests ---------------------------------------------

class PlaceholderExpanderTest {

    @Test
    fun expandsConfigValue() {
        val conf = MutableConfig().apply { set("my.binary", "C:\\bin\\edge.exe") }
        assertEquals("C:\\bin\\edge.exe", PlaceholderExpander.expand("\${my.binary}", conf))
    }

    @Test
    fun expandsSystemProperty() {
        try {
            System.setProperty("pulsar.test.var", "hello")
            val conf = MutableConfig()
            assertEquals("hello", PlaceholderExpander.expand("\${pulsar.test.var}", conf))
        } finally {
            System.clearProperty("pulsar.test.var")
        }
    }

    @Test
    fun expandsEnvironmentVariableUppercase() {
        // PATH 肯定存在于任何系统环境
        val result = PlaceholderExpander.expand("\${PATH}", MutableConfig())
        assertTrue(result.isNotEmpty())
        assertTrue(result.length > 10)
    }

    @Test
    fun configTakesPrecedenceOverSystemProperty() {
        try {
            System.setProperty("pulsar.prio", "sys")
            val conf = MutableConfig().apply { set("pulsar.prio", "config") }
            assertEquals("config", PlaceholderExpander.expand("\${pulsar.prio}", conf))
        } finally {
            System.clearProperty("pulsar.prio")
        }
    }

    @Test
    fun defaultValueUsedWhenNothingResolves() {
        assertEquals("fallback", PlaceholderExpander.expand("\${no.such.key:-fallback}", MutableConfig()))
    }

    @Test
    fun unresolvedPlaceholderStaysWhenNoDefault() {
        val input = "\${no.such.key}"
        assertEquals(input, PlaceholderExpander.expand(input, MutableConfig()))
    }

    @Test
    fun expandListTrimsAndFiltersBlanks() {
        val conf = MutableConfig()
        val list = PlaceholderExpander.expandList("chrome,  google-chrome  ,,  edge", conf)
        assertEquals(listOf("chrome", "google-chrome", "edge"), list)
    }

    @Test
    fun expandListWithDefault() {
        val conf = MutableConfig()
        val list = PlaceholderExpander.expandList("\${no.such.key:-edge}, brave", conf)
        assertEquals(listOf("edge", "brave"), list)
    }
}

// ---- ConfigDrivenBrowserResolver tests -------------------------------------

class ConfigDrivenBrowserResolverTest {

    @Test
    fun readsChannelAliasesFromConfig() {
        val conf = MutableConfig().apply {
            set("browser.channel.edge.aliases", "edge, msedge, microsoft-edge")
            set("browser.channel.edge.stealth.injectable", "true")
        }
        val resolver = ConfigDrivenBrowserResolver("edge", conf)
        assertEquals(setOf("edge", "msedge", "microsoft-edge"), resolver.channelAliases)
    }

    @Test
    fun defaultsAliasesToChannelNameWhenMissing() {
        val conf = MutableConfig().apply {
            set("browser.channel.edge.stealth.injectable", "true")
        }
        val resolver = ConfigDrivenBrowserResolver("edge", conf)
        assertEquals(setOf("edge"), resolver.channelAliases)
    }

    @Test
    fun readsStealthInjectable() {
        val conf = MutableConfig().apply {
            set("browser.channel.fp.aliases", "fp")
            set("browser.channel.fp.stealth.injectable", "false")
        }
        val resolver = ConfigDrivenBrowserResolver("fp", conf)
        assertFalse(resolver.stealthInjectable)
    }

    @Test
    fun stealthInjectableDefaultsToTrue() {
        val conf = MutableConfig().apply { set("browser.channel.x.aliases", "x") }
        val resolver = ConfigDrivenBrowserResolver("x", conf)
        assertTrue(resolver.stealthInjectable)
    }

    @Test
    fun resolveBinaryUsesConfiguredPath(@TempDir tempDir: Path) {
        val fake = Files.writeString(tempDir.resolve("msedge.exe"), "MZ")
        check(fake.toFile().setExecutable(true))
        val conf = MutableConfig().apply {
            set("browser.channel.edge.aliases", "edge")
            set("browser.channel.edge.binary.path", fake.toString())
        }
        val resolver = ConfigDrivenBrowserResolver("edge", conf)
        assertEquals(fake.toAbsolutePath(), resolver.resolveBinary(conf))
    }

    @Test
    fun resolveBinaryFromInstallDirTemplate(@TempDir tempDir: Path) {
        val fake = Files.writeString(tempDir.resolve("edge.exe"), "MZ")
        check(fake.toFile().setExecutable(true))
        val conf = MutableConfig().apply {
            set("browser.channel.edge.aliases", "edge")
            set("browser.channel.edge.binary.install-dirs", fake.toString())
        }
        val resolver = ConfigDrivenBrowserResolver("edge", conf)
        assertEquals(fake.toAbsolutePath(), resolver.resolveBinary(conf))
    }

    @Test
    fun resolveBinaryNullWhenNoPathConfigured() {
        val conf = MutableConfig().apply {
            set("browser.channel.chrome-like.aliases", "chrome-like")
        }
        val resolver = ConfigDrivenBrowserResolver("chrome-like", conf)
        assertNull(resolver.resolveBinary(conf))
    }

    @Test
    fun webrtcPolicyPostLaunchHook(@TempDir tempDir: Path) {
        val conf = MutableConfig().apply {
            set("browser.channel.ego2.aliases", "ego2")
            set("browser.channel.ego2.post-launch.action", "webrtc_policy")
            set("browser.channel.ego2.post-launch.webrtc.policy", "disable_non_proxied_udp")
        }
        val resolver = ConfigDrivenBrowserResolver("ego2", conf)
        resolver.postLaunchHook(tempDir)

        val preferences = tempDir.resolve("Default").resolve("Preferences")
        assertTrue(Files.isRegularFile(preferences))
        val root = ObjectMapper().readTree(Files.readString(preferences))
        assertEquals("disable_non_proxied_udp", root.get("webrtc").get("ip_handling_policy").asText())
    }

    @Test
    fun writeJsonKeyPostLaunchHook(@TempDir tempDir: Path) {
        val conf = MutableConfig().apply {
            set("browser.channel.x.aliases", "x")
            set("browser.channel.x.post-launch.action", "write_json_key")
            set("browser.channel.x.post-launch.json-path", "profile.default_content_setting_values.notifications")
            set("browser.channel.x.post-launch.json-value", "2")
        }
        val resolver = ConfigDrivenBrowserResolver("x", conf)
        resolver.postLaunchHook(tempDir)

        val root = ObjectMapper().readTree(
            Files.readString(tempDir.resolve("Default").resolve("Preferences"))
        )
        assertEquals(2, root.get("profile").get("default_content_setting_values").get("notifications").asInt())
    }

    @Test
    fun nonePostLaunchHookDoesNothing(@TempDir tempDir: Path) {
        val conf = MutableConfig().apply {
            set("browser.channel.y.aliases", "y")
            set("browser.channel.y.post-launch.action", "none")
        }
        val resolver = ConfigDrivenBrowserResolver("y", conf)
        resolver.postLaunchHook(tempDir)
        assertFalse(Files.exists(tempDir.resolve("Default")))
    }
}

// ---- Registry 配置驱动路径测试 ----------------------------------------------

class BrowserBinaryResolverRegistryConfigTest {

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        BrowserBinaryResolverRegistry.resetForTest()
    }

    @Test
    fun reloadFromConfigInjectsConfigDrivenResolver() {
        val conf = MutableConfig().apply {
            set("browser.channels", "edge")
            set("browser.channel.edge.aliases", "edge, msedge")
            set("browser.channel.edge.stealth.injectable", "true")
        }
        BrowserBinaryResolverRegistry.reloadFromConfig(conf)

        val edge = BrowserBinaryResolverRegistry.select("edge")
        assertEquals("edge", edge.id)
        assertEquals(setOf("edge", "msedge"), edge.channelAliases)
        assertTrue(edge.stealthInjectable)
    }

    @Test
    fun mergeConfigOverridesBuiltInById() {
        val conf = MutableConfig().apply {
            // 声明 chrome，但只给 aliases —— stealth 默认 true（和 built-in 一样），
            // 验证最终确实是 ConfigDrivenResolver 而非 DefaultChromiumBinaryResolver
            set("browser.channels", "chrome")
            set("browser.channel.chrome.aliases", "chrome")
        }
        BrowserBinaryResolverRegistry.reloadFromConfig(conf)

        val chrome = BrowserBinaryResolverRegistry.select("chrome")
        assertTrue(chrome is ConfigDrivenBrowserResolver)
    }

    @Test
    fun mergeFallsBackToBuiltInWhenChannelListHasNoAliasBlock() {
        // browser.channels 声明了 edge，但没有任何 browser.channel.edge.aliases
        // 所以 discoverFromConfig 跳过 edge，merge 里也没有，最终只有 built-in resolvers
        val conf = MutableConfig().apply {
            set("browser.channels", "edge, chrome, ego-lite")
            // 不写任何 browser.channel.* —— 全部跳过
        }
        BrowserBinaryResolverRegistry.reloadFromConfig(conf)

        // built-in 的 chrome 和 ego-lite 还在
        assertSame(BrowserBinaryResolverRegistry.chromeResolver, BrowserBinaryResolverRegistry.select(null))
        assertSame(BrowserBinaryResolverRegistry.egoLiteResolver, BrowserBinaryResolverRegistry.select("ego"))

        // edge 没有 built-in fallback → 未知 channel
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BrowserBinaryResolverRegistry.select("edge")
        }
    }

    @Test
    fun configDrivenResolverCanReplaceBuiltInEgoLite() {
        val conf = MutableConfig().apply {
            set("browser.channels", "ego-lite")
            set("browser.channel.ego-lite.aliases", "ego-lite, ego, egolite, ego lite")
            set("browser.channel.ego-lite.stealth.injectable", "false")
            set("browser.channel.ego-lite.post-launch.action", "webrtc_policy")
            set("browser.channel.ego-lite.post-launch.webrtc.policy", "disable_non_proxied_udp")
        }
        BrowserBinaryResolverRegistry.reloadFromConfig(conf)

        val ego = BrowserBinaryResolverRegistry.select("ego-lite")
        assertTrue(ego is ConfigDrivenBrowserResolver)
        assertFalse(ego.stealthInjectable)
    }
}
