package ai.platon.pulsar.common.browser.fingerprint

import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for the [Fingerprint] data class — equality, comparison, JSON
 * serialization/deserialization, proxy management, and extended parameters.
 */
class FingerprintTest {

    private val mapper = prettyPulsarObjectMapper()

    private val sampleUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // -- Construction ----------------------------------------------------------

    @Test
    @DisplayName("constructs bare Fingerprint with browser type")
    fun testBareConstruction() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        assertEquals(BrowserType.PULSAR_CHROME, fp.browserType)
        assertNull(fp.proxyURI)
        assertNull(fp.userAgent)
        assertNull(fp.source)
        assertFalse(fp.isLoaded)
        assertEquals(1, fp.version)
    }

    @Test
    @DisplayName("constructs Fingerprint with proxy URI string")
    fun testConstructionWithProxyUriString() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME, "socks5://user:pass@proxy:1080")
        assertEquals("socks5://user:pass@proxy:1080", fp.proxyURI.toString())
        assertEquals("user", fp.proxyEntry?.username)
        assertEquals("pass", fp.proxyEntry?.password)
    }

    @Test
    @DisplayName("constructs Fingerprint with proxy string and user agent")
    fun testConstructionWithProxyAndUserAgent() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME, "http://proxy:8080", sampleUserAgent)
        assertEquals("http://proxy:8080", fp.proxyURI.toString())
        assertEquals(sampleUserAgent, fp.userAgent)
    }

    @Test
    @DisplayName("constructs Fingerprint with ProxyEntry")
    fun testConstructionWithProxyEntry() {
        val proxy = ProxyEntry("192.168.1.1", 8080, "user", "pass")
        val fp = Fingerprint(BrowserType.PULSAR_CHROME, proxy)
        assertEquals("192.168.1.1", fp.proxyURI?.host)
        assertEquals(8080, fp.proxyURI?.port)
        assertEquals("user:pass", fp.proxyURI?.userInfo)
    }

    @Test
    @DisplayName("constructs Fingerprint with extended parameters")
    fun testConstructionWithExtendedParameters() {
        val fp = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = sampleUserAgent,
            screenParameters = ScreenParameters.DESKTOP_1920X1080,
            viewportParameters = ViewportParameters.DESKTOP,
            geoTimeParameters = GeoTimeParameters.US_EAST,
            hardwareParameters = HardwareParameters.WINDOWS_DESKTOP,
            webGLParameters = WebGLParameters.INTEL_INTEGRATED,
            canvasParameters = CanvasParameters(fingerprintSeed = "seed"),
            mediaParameters = MediaParameters.DESKTOP,
            miscParameters = MiscParameters.DEFAULT,
            version = 2
        )

        assertEquals(sampleUserAgent, fp.userAgent)
        assertNotNull(fp.screenParameters)
        assertNotNull(fp.viewportParameters)
        assertNotNull(fp.geoTimeParameters)
        assertNotNull(fp.hardwareParameters)
        assertNotNull(fp.webGLParameters)
        assertNotNull(fp.canvasParameters)
        assertNotNull(fp.mediaParameters)
        assertNotNull(fp.miscParameters)
        assertEquals(2, fp.version)

        // Extended parameters start null by default
        val bare = Fingerprint(BrowserType.PULSAR_CHROME)
        assertNull(bare.screenParameters)
        assertNull(bare.viewportParameters)
    }

    // -- isLoaded --------------------------------------------------------------

    @Test
    @DisplayName("isLoaded returns false when source is null")
    fun testIsLoadedFalseWhenSourceIsNull() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        assertNull(fp.source)
        assertFalse(fp.isLoaded)
    }

    @Test
    @DisplayName("isLoaded returns true when source is set")
    fun testIsLoadedTrueWhenSourceIsSet() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        fp.source = "/path/to/fingerprint.json"
        assertTrue(fp.isLoaded)
    }

    // -- Proxy management ----------------------------------------------------

    @Test
    @DisplayName("setProxy with protocol and hostPort")
    fun testSetProxy() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        fp.setProxy("http", "localhost:8080", null, null)
        assertEquals("http://localhost:8080", fp.proxyURI.toString())
        assertTrue(fp.hasProxy())
    }

    @Test
    @DisplayName("setProxy with authentication")
    fun testSetProxyWithAuth() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        fp.setProxy("https", "proxy.example.com:3128", "myuser", "mypass")
        assertEquals("https://myuser:mypass@proxy.example.com:3128", fp.proxyURI.toString())
        assertEquals("myuser", fp.proxyEntry?.username)
        assertEquals("mypass", fp.proxyEntry?.password)
    }

    @Test
    @DisplayName("setProxy with ProxyEntry")
    fun testSetProxyWithProxyEntry() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        val proxy = ProxyEntry("10.0.0.1", 1080, "u", "p")
        fp.setProxy(proxy)
        assertEquals("10.0.0.1", fp.proxyURI?.host)
        assertEquals(1080, fp.proxyURI?.port)
        assertEquals("u:p", fp.proxyURI?.userInfo)
    }

    @Test
    @DisplayName("unsetProxy removes proxy")
    fun testUnsetProxy() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME, "http://proxy:8080")
        assertTrue(fp.hasProxy())
        fp.unsetProxy()
        assertNull(fp.proxyURI)
        assertNull(fp.proxyEntry)
        assertFalse(fp.hasProxy())
    }

    // -- Equality ------------------------------------------------------------

    @Test
    @DisplayName("equal fingerprints have same browser type and proxy")
    fun testEquality() {
        val fp1 = Fingerprint(BrowserType.PULSAR_CHROME)
        val fp2 = Fingerprint(BrowserType.PULSAR_CHROME)
        assertEquals(fp1, fp2)

        // Different proxy makes them unequal
        fp2.proxyURI = URI("http://proxy:8080")
        assertNotEquals(fp1, fp2)

        // Same proxy makes them equal again
        fp1.proxyURI = URI("http://proxy:8080")
        assertEquals(fp1, fp2)
    }

    @Test
    @DisplayName("different browser types are not equal")
    fun testDifferentBrowserTypesNotEqual() {
        val fp1 = Fingerprint(BrowserType.PULSAR_CHROME)
        val fp2 = Fingerprint(BrowserType.PLAYWRIGHT_CHROME)
        assertNotEquals(fp1, fp2)
    }

    @Test
    @DisplayName("user agent does not affect equality")
    fun testUserAgentDoesNotAffectEquality() {
        val fp1 = Fingerprint(BrowserType.PULSAR_CHROME, userAgent = "UA-1")
        val fp2 = Fingerprint(BrowserType.PULSAR_CHROME, userAgent = "UA-2")
        assertEquals(fp1, fp2, "Equality only checks browserType and proxyURI, not userAgent")
    }

    @Test
    @DisplayName("hashCode consistent with equals")
    fun testHashCodeConsistency() {
        val fp1 = Fingerprint(BrowserType.PULSAR_CHROME)
        val fp2 = Fingerprint(BrowserType.PULSAR_CHROME)
        assertEquals(fp1.hashCode(), fp2.hashCode())

        fp2.proxyURI = URI("http://proxy:8080")
        assertNotEquals(fp1.hashCode(), fp2.hashCode())

        fp1.proxyURI = URI("http://proxy:8080")
        assertEquals(fp1.hashCode(), fp2.hashCode())
    }

    // -- Comparison ----------------------------------------------------------

    @Test
    @DisplayName("PLAYWRIGHT_CHROME sorts before PULSAR_CHROME")
    fun testComparisonBrowserType() {
        val fp1 = Fingerprint(BrowserType.PLAYWRIGHT_CHROME)
        val fp2 = Fingerprint(BrowserType.PULSAR_CHROME)
        assertTrue(fp1 < fp2, "PLAYWRIGHT_CHROME should be less than PULSAR_CHROME")
    }

    @Test
    @DisplayName("same browser type with proxy sorts after without proxy")
    fun testComparisonWithProxy() {
        val fp1 = Fingerprint(BrowserType.PULSAR_CHROME)
        val fp2 = Fingerprint(BrowserType.PULSAR_CHROME, "http://proxy:8080")
        assertTrue(fp1 < fp2, "null proxy should sort before non-null proxy")
    }

    @Test
    @DisplayName("proxies compared by string representation")
    fun testComparisonProxies() {
        val fp1 = Fingerprint(BrowserType.PULSAR_CHROME, "127.0.0.1")
        val fp2 = Fingerprint(BrowserType.PULSAR_CHROME, "127.0.0.2")
        assertTrue(fp1 < fp2)
    }

    @Test
    @DisplayName("user agent used as tiebreaker after proxy")
    fun testComparisonUserAgentTiebreaker() {
        val fp1 = Fingerprint(BrowserType.PULSAR_CHROME, "http://proxy:8080", "ua-a")
        val fp2 = Fingerprint(BrowserType.PULSAR_CHROME, "http://proxy:8080", "ua-b")
        assertTrue(fp1 < fp2)
    }

    // -- toString ------------------------------------------------------------

    @Test
    @DisplayName("toString returns browser type and proxy")
    fun testToString() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        assertEquals("PULSAR_CHROME", fp.toString())

        fp.proxyURI = URI("http://proxy:8080")
        assertEquals("PULSAR_CHROME, http://proxy:8080", fp.toString())
    }

    // -- JSON serialization --------------------------------------------------

    @Test
    @DisplayName("basic fingerprint serializes to JSON and back")
    fun testBasicJsonRoundTrip() {
        val fp = Fingerprint(
            BrowserType.PULSAR_CHROME,
            URI("http://sa:sa@localhost:8080"),
            sampleUserAgent
        )
        val json = mapper.writeValueAsString(fp)
        val deserialized = mapper.readValue(json, Fingerprint::class.java)
        assertEquals(fp, deserialized)
        assertEquals(fp.userAgent, deserialized.userAgent)
    }

    @Test
    @DisplayName("bare fingerprint serializes to JSON and back")
    fun testBareJsonRoundTrip() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        val json = mapper.writeValueAsString(fp)
        val deserialized = mapper.readValue(json, Fingerprint::class.java)
        assertEquals(fp, deserialized)
        assertEquals(1, deserialized.version)
    }

    @Test
    @DisplayName("extended fingerprint serializes all parameters")
    fun testExtendedJsonRoundTrip() {
        val fp = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = sampleUserAgent,
            screenParameters = ScreenParameters.DESKTOP_1920X1080,
            viewportParameters = ViewportParameters.DESKTOP,
            geoTimeParameters = GeoTimeParameters.US_EAST,
            hardwareParameters = HardwareParameters.WINDOWS_DESKTOP,
            webGLParameters = WebGLParameters.INTEL_INTEGRATED,
            canvasParameters = CanvasParameters.DEFAULT,
            mediaParameters = MediaParameters.DESKTOP,
            miscParameters = MiscParameters.DEFAULT,
            version = 1
        )

        val json = mapper.writeValueAsString(fp)
        val deserialized = mapper.readValue(json, Fingerprint::class.java)

        assertEquals(fp.browserType, deserialized.browserType)
        assertEquals(fp.userAgent, deserialized.userAgent)
        assertEquals(fp.screenParameters, deserialized.screenParameters)
        assertEquals(fp.viewportParameters, deserialized.viewportParameters)
        assertEquals(fp.geoTimeParameters, deserialized.geoTimeParameters)
        assertEquals(fp.hardwareParameters, deserialized.hardwareParameters)
        assertEquals(fp.webGLParameters, deserialized.webGLParameters)
        assertEquals(fp.version, deserialized.version)
    }

    @Test
    @DisplayName("Fingerprint with null extended params serializes cleanly")
    fun testNullExtendedParamsRoundTrip() {
        val fp = Fingerprint(
            browserType = BrowserType.PULSAR_CHROME,
            userAgent = sampleUserAgent
            // all extended params are null
        )

        val json = mapper.writeValueAsString(fp)
        val deserialized = mapper.readValue(json, Fingerprint::class.java)

        assertEquals(fp.browserType, deserialized.browserType)
        assertNull(deserialized.screenParameters)
        assertNull(deserialized.viewportParameters)
        assertNull(deserialized.geoTimeParameters)
        assertNull(deserialized.hardwareParameters)
        assertNull(deserialized.webGLParameters)
        assertNull(deserialized.canvasParameters)
        assertNull(deserialized.mediaParameters)
        assertNull(deserialized.miscParameters)
    }

    @Test
    @DisplayName("fingerprint JSON preserves website accounts")
    fun testJsonPreservesWebsiteAccounts() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        fp.websiteAccounts["test.com"] = WebsiteAccount(
            domain = "test.com",
            homeURL = "https://test.com",
            loginLinkSelector = "a.login",
            username = "user1",
            password = "pass1",
            usernameInputSelector = "#user",
            passwordInputSelector = "#pass"
        )

        val json = mapper.writeValueAsString(fp)
        val deserialized = mapper.readValue(json, Fingerprint::class.java)

        assertEquals(1, deserialized.websiteAccounts.size)
        val account = deserialized.websiteAccounts["test.com"]
        assertNotNull(account)
        assertEquals("user1", account!!.username)
        assertEquals("#user", account.usernameInputSelector)
    }

    // -- fromJson companion ---------------------------------------------------

    @Test
    @DisplayName("Fingerprint.fromJson parses JSON string")
    fun testFromJson() {
        val json = mapper.writeValueAsString(
            Fingerprint(BrowserType.PULSAR_CHROME, URI("http://proxy:8080"), sampleUserAgent)
        )
        val fp = Fingerprint.fromJson(json)
        assertEquals(BrowserType.PULSAR_CHROME, fp.browserType)
        assertEquals("http://proxy:8080", fp.proxyURI.toString())
        assertEquals(sampleUserAgent, fp.userAgent)
    }

    // -- Companion defaults ---------------------------------------------------

    @Test
    @DisplayName("DEFAULT companion is PULSAR_CHROME with no proxy")
    fun testDefaultCompanion() {
        val fp = Fingerprint.DEFAULT
        assertEquals(BrowserType.PULSAR_CHROME, fp.browserType)
        assertNull(fp.proxyURI)
        assertFalse(fp.isLoaded)
    }

    @Test
    @DisplayName("EXAMPLE companion has proxy and user agent")
    fun testExampleCompanion() {
        val fp = Fingerprint.EXAMPLE
        assertEquals(BrowserType.PULSAR_CHROME, fp.browserType)
        assertEquals("http://localhost:8080", fp.proxyURI.toString())
        assertEquals(Fingerprint.EXAMPLE_USER_AGENT, fp.userAgent)
    }

    // -- ProxyEntry bridge ----------------------------------------------------

    @Test
    @DisplayName("proxyEntry is null when no proxy")
    fun testProxyEntryNullWhenNoProxy() {
        val fp = Fingerprint(BrowserType.PULSAR_CHROME)
        assertNull(fp.proxyEntry)
    }

    @Test
    @DisplayName("proxyEntry extracts from proxy URI")
    fun testProxyEntryFromUri() {
        val fp = Fingerprint(
            BrowserType.PULSAR_CHROME,
            URI("socks5://username:password@proxy-host:1080")
        )
        assertNotNull(fp.proxyEntry)
        assertEquals("username", fp.proxyEntry!!.username)
        assertEquals("password", fp.proxyEntry!!.password)
        assertEquals("proxy-host", fp.proxyEntry!!.host)
        assertEquals(1080, fp.proxyEntry!!.port)
    }
}
