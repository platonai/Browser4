package ai.platon.pulsar.common.browser.fingerprint

import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Tests for [FingerprintLoader.loadOrGenerateFingerprint] — the core logic that
 * loads a fingerprint from a `fingerprint.json` config file in the browser's
 * context directory, or falls back to a bare fingerprint when no config exists.
 */
class FingerprintLoaderTest {

    private val mapper = prettyPulsarObjectMapper()

    @TempDir
    lateinit var tempDir: Path

    // -- Load from config file -------------------------------------------------

    @Test
    @DisplayName("loads fingerprint from existing fingerprint.json")
    fun testLoadsFingerprintFromExistingFile() {
        val contextDir = tempDir.resolve("cx.test1")
        Files.createDirectories(contextDir)

        val expected = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        contextDir.resolve("fingerprint.json").writeText(mapper.writeValueAsString(expected))

        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertEquals(BrowserType.PULSAR_CHROME, result.browserType)
        assertEquals(expected.userAgent, result.userAgent)
        assertNotNull(result.source, "source should be set to the config file path")
        assertTrue(result.isLoaded, "isLoaded should be true when loaded from file")
        assertEquals(
            contextDir.resolve("fingerprint.json").toString(),
            result.source,
            "source should record the full path"
        )
    }

    @Test
    @DisplayName("falls back to bare Fingerprint when no fingerprint.json exists")
    fun testFallsBackWhenNoFileExists() {
        val contextDir = tempDir.resolve("cx.empty")
        Files.createDirectories(contextDir)
        // intentionally do NOT create fingerprint.json

        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertEquals(BrowserType.PULSAR_CHROME, result.browserType)
        assertNull(result.source, "source should be null when not loaded from file")
        assertFalse(result.isLoaded, "isLoaded should be false for bare fingerprint")
        assertNull(result.userAgent)
        assertNull(result.proxyURI)
    }

    @Test
    @DisplayName("ensures browserType is set even when file has a different type")
    fun testBrowserTypeIsAlwaysSet() {
        val contextDir = tempDir.resolve("cx.type-override")
        Files.createDirectories(contextDir)

        // Write a fingerprint with a different browser type
        val fileContent = mapper.writeValueAsString(
            Fingerprint(browserType = BrowserType.PLAYWRIGHT_CHROME)
        )
        contextDir.resolve("fingerprint.json").writeText(fileContent)

        // Load with a different browserType — the loader should override
        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertEquals(
            BrowserType.PULSAR_CHROME, result.browserType,
            "browserType should be overridden to match the loader's parameter"
        )
    }

    @Test
    @DisplayName("falls back to bare Fingerprint on malformed JSON")
    fun testFallsBackOnMalformedJson() {
        val contextDir = tempDir.resolve("cx.bad-json")
        Files.createDirectories(contextDir)
        contextDir.resolve("fingerprint.json").writeText("{ this is not valid json }")

        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertEquals(BrowserType.PULSAR_CHROME, result.browserType)
        assertNull(result.source, "source should be null on parse failure")
        assertFalse(result.isLoaded)
    }

    @Test
    @DisplayName("falls back to bare Fingerprint when file content is empty")
    fun testFallsBackOnEmptyFile() {
        val contextDir = tempDir.resolve("cx.empty-file")
        Files.createDirectories(contextDir)
        contextDir.resolve("fingerprint.json").writeText("")

        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertEquals(BrowserType.PULSAR_CHROME, result.browserType)
        assertFalse(result.isLoaded)
    }

    // -- Loading extended parameters -------------------------------------------

    @Test
    @DisplayName("loads all extended fingerprint parameters from JSON")
    fun testLoadsExtendedParametersFromJson() {
        val contextDir = tempDir.resolve("cx.extended")
        Files.createDirectories(contextDir)

        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            proxyURI = java.net.URI("http://proxy.example.com:8080"),
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
            screenParameters = ScreenParameters.DESKTOP_1920X1080,
            viewportParameters = ViewportParameters.DESKTOP,
            geoTimeParameters = GeoTimeParameters.US_EAST,
            hardwareParameters = HardwareParameters.WINDOWS_DESKTOP,
            webGLParameters = WebGLParameters.INTEL_INTEGRATED,
            canvasParameters = CanvasParameters(fingerprintSeed = "test-seed-123"),
            mediaParameters = MediaParameters.DESKTOP,
            miscParameters = MiscParameters.DEFAULT,
            version = 1
        )
        contextDir.resolve("fingerprint.json").writeText(mapper.writeValueAsString(fingerprint))

        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertTrue(result.isLoaded)
        assertEquals("http://proxy.example.com:8080", result.proxyURI.toString())
        assertEquals(fingerprint.userAgent, result.userAgent)

        // Screen parameters
        assertNotNull(result.screenParameters)
        assertEquals(1920, result.screenParameters!!.width)
        assertEquals(1080, result.screenParameters!!.height)
        assertEquals(1.0, result.screenParameters!!.devicePixelRatio)

        // Viewport parameters
        assertNotNull(result.viewportParameters)
        assertEquals(1920, result.viewportParameters!!.width)
        assertFalse(result.viewportParameters!!.isMobile)

        // Geo-time parameters
        assertNotNull(result.geoTimeParameters)
        assertEquals("America/New_York", result.geoTimeParameters!!.timezone)
        assertEquals("en-US", result.geoTimeParameters!!.locale)
        assertEquals(listOf("en-US", "en"), result.geoTimeParameters!!.languages)

        // Hardware parameters
        assertNotNull(result.hardwareParameters)
        assertEquals(8, result.hardwareParameters!!.hardwareConcurrency)
        assertEquals("Win32", result.hardwareParameters!!.platform)

        // WebGL parameters
        assertNotNull(result.webGLParameters)
        assertTrue(result.webGLParameters!!.vendor.contains("Intel"))

        // Canvas parameters
        assertNotNull(result.canvasParameters)
        assertEquals("test-seed-123", result.canvasParameters!!.fingerprintSeed)

        // Media parameters
        assertNotNull(result.mediaParameters)
        assertTrue(result.mediaParameters!!.audioInputDevices.isNotEmpty())

        // Misc parameters
        assertNotNull(result.miscParameters)
        assertTrue(result.miscParameters!!.cookieEnabled)

        // Version
        assertEquals(1, result.version)
    }

    @Test
    @DisplayName("loads fingerprint with proxy only (no extended parameters)")
    fun testLoadsFingerprintWithProxyOnly() {
        val contextDir = tempDir.resolve("cx.proxy-only")
        Files.createDirectories(contextDir)

        val fingerprint = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            proxyURI = java.net.URI("socks5://user:pass@proxy.example.com:1080")
        )
        contextDir.resolve("fingerprint.json").writeText(mapper.writeValueAsString(fingerprint))

        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertTrue(result.isLoaded)
        assertEquals("socks5://user:pass@proxy.example.com:1080", result.proxyURI.toString())
        assertTrue(result.hasProxy())
        assertNotNull(result.proxyEntry)
        assertEquals("user", result.proxyEntry!!.username)
        assertEquals("pass", result.proxyEntry!!.password)

        // Extended parameters should remain null
        assertNull(result.screenParameters)
        assertNull(result.viewportParameters)
        assertNull(result.geoTimeParameters)
    }

    @Test
    @DisplayName("loads fingerprint with website accounts")
    fun testLoadsFingerprintWithWebsiteAccounts() {
        val contextDir = tempDir.resolve("cx.accounts")
        Files.createDirectories(contextDir)

        val fingerprint = Fingerprint(browserType = BrowserType.PULSAR_CHROME)
        fingerprint.websiteAccounts["example.com"] = WebsiteAccount(
            domain = "example.com",
            homeURL = "https://example.com/login",
            loginLinkSelector = "a.login",
            username = "testuser",
            password = "testpass",
            usernameInputSelector = "#username",
            passwordInputSelector = "#password"
        )
        contextDir.resolve("fingerprint.json").writeText(mapper.writeValueAsString(fingerprint))

        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertTrue(result.isLoaded)
        assertEquals(1, result.websiteAccounts.size)
        val account = result.websiteAccounts["example.com"]
        assertNotNull(account)
        assertEquals("testuser", account!!.username)
        assertEquals("testpass", account.password)
        assertEquals("#username", account.usernameInputSelector)
    }

    // -- Different browser types -----------------------------------------------

    @Test
    @DisplayName("supports loading with PLAYWRIGHT_CHROME browser type")
    fun testSupportsPlaywrightChromeBrowserType() {
        val contextDir = tempDir.resolve("cx.playwright")
        Files.createDirectories(contextDir)
        contextDir.resolve("fingerprint.json").writeText(
            mapper.writeValueAsString(Fingerprint(browserType = BrowserType.PLAYWRIGHT_CHROME))
        )

        val result = FingerprintLoader(BrowserType.PLAYWRIGHT_CHROME, contextDir).loadOrGenerateFingerprint()

        assertEquals(BrowserType.PLAYWRIGHT_CHROME, result.browserType)
        assertTrue(result.isLoaded)
    }

    @Test
    @DisplayName("treats missing context directory same as missing file (no fallback dir creation)")
    fun testMissingContextDirTreatedAsMissingFile() {
        val contextDir = tempDir.resolve("cx.nonexistent")
        // intentionally do NOT create the directory

        val result = FingerprintLoader(BrowserType.PULSAR_CHROME, contextDir).loadOrGenerateFingerprint()

        assertEquals(BrowserType.PULSAR_CHROME, result.browserType)
        assertFalse(result.isLoaded)
    }
}
