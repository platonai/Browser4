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
package ai.platon.pulsar.images.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Tests for [ImageProxyResolver]: the documented source precedence, spec
 * parsing (including the Windows registry list form), and the loopback bypass
 * of [ImageProxySelector]. Every source is injected — no registry, no network.
 */
class ImageProxyResolverTest {

    private val noEnv: (String) -> String? = { null }
    private val noProp: (String) -> String? = { null }
    private val noOsProxy: () -> String? = { null }

    // ---- resolve: precedence ----

    @Test
    @DisplayName("explicit image.download.proxy wins over every other source")
    fun testConfigProxyWins() {
        val spec = ImageProxyResolver.resolve(
            configProxy = "config-proxy:8080",
            env = { "env-proxy:1" },
            prop = { "prop-proxy" },
            osProxy = { "os-proxy:2" },
        )

        assertEquals("config-proxy", spec?.host)
        assertEquals(8080, spec?.port)
        assertEquals("image.download.proxy", spec?.source)
    }

    @Test
    @DisplayName("JVM proxy properties are used when no explicit proxy is configured")
    fun testJvmPropertiesAreUsed() {
        val properties = mapOf("https.proxyHost" to "jvm-proxy", "https.proxyPort" to "3128")
        val spec = ImageProxyResolver.resolve("", noEnv, { properties[it] }, noOsProxy)

        assertEquals("jvm-proxy" to 3128, spec?.host to spec?.port)
        assertTrue(spec!!.source.contains("https.proxyHost"))
    }

    @Test
    @DisplayName("HTTPS_PROXY is used when neither config nor JVM properties declare one")
    fun testEnvironmentIsUsed() {
        val env = mapOf("HTTPS_PROXY" to "http://env-proxy:10808")
        val spec = ImageProxyResolver.resolve(null, { env[it] }, noProp, noOsProxy)

        assertEquals("env-proxy" to 10808, spec?.host to spec?.port)
        assertTrue(spec!!.source.contains("HTTPS_PROXY"))
    }

    @Test
    @DisplayName("the system proxy is the last resort, after config, JVM and environment")
    fun testOsProxyIsTheFallback() {
        val spec = ImageProxyResolver.resolve(null, noEnv, noProp) { "127.0.0.1:10808" }

        assertEquals("127.0.0.1" to 10808, spec?.host to spec?.port)
        assertEquals("operating-system proxy", spec?.source)
    }

    @Test
    @DisplayName("resolve returns null when no source declares a proxy")
    fun testNoProxyResolved() {
        assertNull(ImageProxyResolver.resolve(null, noEnv, noProp, noOsProxy))
        assertNull(ImageProxyResolver.resolve("   ", { "  " }, noProp, noOsProxy))
        assertNull(ImageProxyResolver.resolve("not a proxy", noEnv, noProp, noOsProxy))
    }

    @Test
    @DisplayName("a malformed source falls through to the next usable one")
    fun testMalformedSourceFallsThrough() {
        val spec = ImageProxyResolver.resolve("http://", noEnv, noProp) { "os-proxy:8080" }

        assertEquals("os-proxy" to 8080, spec?.host to spec?.port)
    }

    // ---- parse ----

    @Test
    @DisplayName("parse accepts host:port, scheme://host:port and credentials")
    fun testParseAcceptsCommonForms() {
        assertEquals("127.0.0.1" to 10808, ImageProxyResolver.parse("127.0.0.1:10808")?.host to
            ImageProxyResolver.parse("127.0.0.1:10808")?.port)
        assertEquals("127.0.0.1" to 10808, ImageProxyResolver.parse("http://127.0.0.1:10808")?.host to
            ImageProxyResolver.parse("http://127.0.0.1:10808")?.port)
        assertEquals("proxy.example.com" to 3128, ImageProxyResolver.parse("http://user:pw@proxy.example.com:3128")?.host to
            ImageProxyResolver.parse("http://user:pw@proxy.example.com:3128")?.port)
    }

    @Test
    @DisplayName("parse applies scheme defaults when the port is missing")
    fun testParseAppliesDefaultPorts() {
        assertEquals(443, ImageProxyResolver.parse("https://proxy.example.com")?.port)
        assertEquals(80, ImageProxyResolver.parse("http://proxy.example.com")?.port)
        assertEquals(1080, ImageProxyResolver.parse("socks5://proxy.example.com")?.port)
    }

    @Test
    @DisplayName("parse prefers the https entry of a Windows registry proxy list")
    fun testParseWindowsRegistryList() {
        val spec = ImageProxyResolver.parse("http=127.0.0.1:10808;https=127.0.0.1:10809")

        assertEquals("127.0.0.1" to 10809, spec?.host to spec?.port)
        assertEquals(10808, ImageProxyResolver.parse("http=127.0.0.1:10808")?.port)
    }

    @Test
    @DisplayName("parse returns null for a spec without a usable host")
    fun testParseRejectsSpecWithoutHost() {
        assertNull(ImageProxyResolver.parse(null))
        assertNull(ImageProxyResolver.parse(""))
        assertNull(ImageProxyResolver.parse("http:///no-host"))
        assertNull(ImageProxyResolver.parse("https://"))
    }

    // ---- Windows registry parsing ----

    @Test
    @DisplayName("proxyEnabled reads the WinINET ProxyEnable value")
    fun testProxyEnabledParsing() {
        assertTrue(ImageProxyResolver.proxyEnabled("    ProxyEnable    REG_DWORD    0x1"))
        assertFalse(ImageProxyResolver.proxyEnabled("    ProxyEnable    REG_DWORD    0x0"))
        assertFalse(ImageProxyResolver.proxyEnabled("no ProxyEnable here"))
    }

    // ---- ImageProxySelector ----

    @Test
    @DisplayName("loopback hosts bypass the proxy, everything else uses it")
    fun testLoopbackBypassesTheProxy() {
        val spec = ImageProxyResolver.ProxySpec("127.0.0.1", 10808, "test")
        val selector = ImageProxySelector(spec)

        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://localhost:8000/img.png")).single())
        assertEquals(Proxy.NO_PROXY, selector.select(URI("http://127.0.0.1:8000/img.png")).single())

        val remote = selector.select(URI("https://upload.wikimedia.org/flag.png")).single()
        assertEquals(Proxy.Type.HTTP, remote.type())
        assertEquals(InetSocketAddress("127.0.0.1", 10808), remote.address())
    }
}
