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
import org.springframework.context.ApplicationContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Tests for the download client built by [ImageAutoConfiguration]: a bounded
 * connect timeout (so an unreachable host fails fast) and proxy wiring.
 */
class ImageAutoConfigurationTest {

    private val configuration = ImageAutoConfiguration(stubApplicationContext())

    @Test
    @DisplayName("the client uses the configured budget with a bounded connect timeout")
    fun testClientTimeoutsComeFromConfig() {
        val client = configuration.imageDownloadClient(
            ImageConfig(downloadDir = "/tmp/images", downloadTimeoutSeconds = 25)
        )

        assertEquals(25_000, client.callTimeoutMillis)
        assertEquals(25_000, client.readTimeoutMillis)
        assertEquals(25_000, client.writeTimeoutMillis)
        // below the caller's 30s tool-call timeout, and bounded so a blocked
        // egress fails fast with the plugin's actionable error
        assertEquals(10_000, client.connectTimeoutMillis)
    }

    @Test
    @DisplayName("a configured proxy is used for remote hosts and bypassed for loopback")
    fun testConfiguredProxyIsWired() {
        val client = configuration.imageDownloadClient(
            ImageConfig(downloadDir = "/tmp/images", proxy = "127.0.0.1:10808")
        )

        val remote = client.proxySelector.select(URI("https://upload.wikimedia.org/flag.png")).single()
        assertEquals(Proxy.Type.HTTP, remote.type())
        assertEquals(InetSocketAddress("127.0.0.1", 10808), remote.address())

        val loopback = client.proxySelector.select(URI("http://127.0.0.1:8000/flag.png")).single()
        assertEquals(Proxy.NO_PROXY, loopback)
    }

    /** The context is never touched by [ImageAutoConfiguration.imageDownloadClient]. */
    private fun stubApplicationContext(): ApplicationContext = java.lang.reflect.Proxy.newProxyInstance(
        ApplicationContext::class.java.classLoader,
        arrayOf(ApplicationContext::class.java),
    ) { _, _, _ -> null } as ApplicationContext
}
