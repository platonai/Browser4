/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.pptx.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Tests for [PptxProxyResolver] and [PptxProxySelector].
 *
 * All lookups are injected, so no test depends on this machine's environment.
 */
class PptxProxyResolverTest {

    private fun lookups(
        env: Map<String, String> = emptyMap(),
        properties: Map<String, String> = emptyMap(),
        osProxy: PptxProxyResolver.ProxyResolution? = null,
    ) = PptxProxyResolver.Lookups(
        env = { env[it] },
        property = { properties[it] },
        osProxy = { osProxy },
    )

    private fun osResolution() = PptxProxyResolver.ProxyResolution(
        InetSocketAddress("10.9.9.9", 4444), "os"
    )

    @Test
    @DisplayName("parseProxyAddress accepts host:port, bare host and proxy URLs")
    fun testParseProxyAddress() {
        assertEquals(InetSocketAddress("127.0.0.1", 10808), PptxProxyResolver.parseProxyAddress("127.0.0.1:10808"))
        assertEquals(
            InetSocketAddress("proxy.local", 3128),
            PptxProxyResolver.parseProxyAddress("http://proxy.local:3128")
        )
        assertEquals(
            InetSocketAddress("proxy.local", 3128),
            PptxProxyResolver.parseProxyAddress("  https://user:pw@proxy.local:3128/  ")
        )
        assertEquals(InetSocketAddress("proxy.local", 80), PptxProxyResolver.parseProxyAddress("proxy.local"))
    }

    @Test
    @DisplayName("parseProxyAddress rejects blank, hostless and malformed specs")
    fun testParseProxyAddressRejectsInvalidSpecs() {
        assertNull(PptxProxyResolver.parseProxyAddress(""))
        assertNull(PptxProxyResolver.parseProxyAddress("   "))
        assertNull(PptxProxyResolver.parseProxyAddress("http://"))
        assertNull(PptxProxyResolver.parseProxyAddress("http://host:notaport"))
    }

    @Test
    @DisplayName("fromEnvironment prefers HTTPS_PROXY over HTTP_PROXY")
    fun testFromEnvironmentPrecedence() {
        val both = PptxProxyResolver.fromEnvironment(
            mapOf("HTTPS_PROXY" to "10.0.0.1:1111", "HTTP_PROXY" to "10.0.0.2:2222")::get
        )
        assertEquals("10.0.0.1:1111", both?.endpoint)
        assertEquals("HTTPS_PROXY", both?.source)

        val lowercase = PptxProxyResolver.fromEnvironment(mapOf("https_proxy" to "10.0.0.3:3333")::get)
        assertEquals("10.0.0.3:3333", lowercase?.endpoint)
        assertEquals("https_proxy", lowercase?.source)

        assertNull(PptxProxyResolver.fromEnvironment(mapOf("HTTPS_PROXY" to "not a proxy")::get))
    }

    @Test
    @DisplayName("fromSystemProperties reads https then http proxy properties")
    fun testFromSystemProperties() {
        val https = PptxProxyResolver.fromSystemProperties(
            mapOf("https.proxyHost" to "10.1.1.1", "https.proxyPort" to "8443", "http.proxyHost" to "10.2.2.2")::get
        )
        assertEquals("10.1.1.1:8443", https?.endpoint)

        val http = PptxProxyResolver.fromSystemProperties(
            mapOf("http.proxyHost" to "10.2.2.2", "http.nonProxyHosts" to "*.internal|localhost")::get
        )
        assertEquals("10.2.2.2:80", http?.endpoint)
        assertEquals(listOf("*.internal", "localhost"), http?.bypassPatterns)

        assertNull(PptxProxyResolver.fromSystemProperties(emptyMap<String, String>()::get))
    }

    @Test
    @DisplayName("parseProxyServer reads plain and per-protocol WinINET values")
    fun testParseProxyServer() {
        assertEquals(
            InetSocketAddress("127.0.0.1", 10808),
            PptxProxyResolver.parseProxyServer("127.0.0.1:10808")
        )
        assertEquals(
            InetSocketAddress("10.0.0.2", 443),
            PptxProxyResolver.parseProxyServer("http=10.0.0.1:80;https=10.0.0.2:443")
        )
        assertEquals(
            InetSocketAddress("10.0.0.1", 80),
            PptxProxyResolver.parseProxyServer("https=;http=10.0.0.1:80")
        )
        assertNull(PptxProxyResolver.parseProxyServer("socks=10.0.0.3:1080"), "SOCKS is not supported")
        assertNull(PptxProxyResolver.parseProxyServer(""))
        assertNull(PptxProxyResolver.parseProxyServer(null))
    }

    @Test
    @DisplayName("parseRegistryValue extracts the value column of reg query output")
    fun testParseRegistryValue() {
        val output = """
            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Internet Settings
                ProxyEnable    REG_DWORD    0x1
                ProxyServer    REG_SZ    127.0.0.1:10808
                ProxyOverride    REG_SZ    *.local;<local>

        """.trimIndent()

        assertEquals("0x1", PptxProxyResolver.parseRegistryValue(output, "ProxyEnable"))
        assertEquals("127.0.0.1:10808", PptxProxyResolver.parseRegistryValue(output, "ProxyServer"))
        assertEquals("*.local;<local>", PptxProxyResolver.parseRegistryValue(output, "ProxyOverride"))
        assertNull(PptxProxyResolver.parseRegistryValue(output, "AutoConfigURL"))
    }

    @Test
    @DisplayName("shouldBypass always bypasses loopback and honours wildcard patterns")
    fun testShouldBypass() {
        assertTrue(PptxProxyResolver.shouldBypass("localhost", emptyList()))
        assertTrue(PptxProxyResolver.shouldBypass("127.0.0.1", emptyList()))
        assertTrue(PptxProxyResolver.shouldBypass("::1", emptyList()))

        assertFalse(PptxProxyResolver.shouldBypass("example.com", emptyList()))
        assertTrue(PptxProxyResolver.shouldBypass("api.internal", listOf("*.internal")))
        assertTrue(PptxProxyResolver.shouldBypass("example.com", listOf("example.com")))

        // `<local>` means "host names without a dot"
        assertTrue(PptxProxyResolver.shouldBypass("printer", listOf("<local>")))
        assertFalse(PptxProxyResolver.shouldBypass("example.com", listOf("<local>")))
    }

    @Test
    @DisplayName("resolve honours config, env, JVM properties and the OS proxy in order")
    fun testResolvePrecedence() {
        val os = osResolution()

        val fromConfig = PptxProxyResolver.resolve(
            PptxConfig(proxy = "10.0.0.10:1010"),
            lookups(env = mapOf("HTTPS_PROXY" to "10.0.0.11:1111"), osProxy = os)
        )
        assertEquals("10.0.0.10:1010", fromConfig?.endpoint)
        assertEquals("pptx.download.proxy", fromConfig?.source)

        val fromEnv = PptxProxyResolver.resolve(
            PptxConfig(),
            lookups(
                env = mapOf("HTTPS_PROXY" to "10.0.0.11:1111"),
                properties = mapOf("https.proxyHost" to "10.0.0.12"),
                osProxy = os,
            )
        )
        assertEquals("10.0.0.11:1111", fromEnv?.endpoint)

        val fromProperties = PptxProxyResolver.resolve(
            PptxConfig(),
            lookups(properties = mapOf("https.proxyHost" to "10.0.0.12", "https.proxyPort" to "1212"), osProxy = os)
        )
        assertEquals("10.0.0.12:1212", fromProperties?.endpoint)

        val fromOs = PptxProxyResolver.resolve(PptxConfig(), lookups(osProxy = os))
        assertEquals("10.9.9.9:4444", fromOs?.endpoint)
        assertEquals("os", fromOs?.source)

        assertNull(PptxProxyResolver.resolve(PptxConfig(), lookups()))
    }

    @Test
    @DisplayName("resolve skips the OS proxy when use-system-proxy is disabled")
    fun testResolveHonoursDisabledSystemProxy() {
        val resolution = PptxProxyResolver.resolve(
            PptxConfig(useSystemProxy = false),
            lookups(osProxy = osResolution())
        )

        assertNull(resolution)
    }

    @Test
    @DisplayName("PptxProxySelector bypasses loopback and the configured hosts only")
    fun testProxySelector() {
        val selector = PptxProxySelector(InetSocketAddress("10.0.0.1", 8080))

        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://localhost:8080/page")).single())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://127.0.0.1/page")).single())
        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://en.wikipedia.org/wiki/Moon")).single().type())

        val bypassing = PptxProxySelector(InetSocketAddress("10.0.0.1", 8080), listOf("*.internal"))
        assertEquals(Proxy.NO_PROXY, bypassing.select(URI("https://api.internal/x")).single())
        assertEquals(Proxy.Type.HTTP, bypassing.select(URI("https://example.com/x")).single().type())
    }
}
