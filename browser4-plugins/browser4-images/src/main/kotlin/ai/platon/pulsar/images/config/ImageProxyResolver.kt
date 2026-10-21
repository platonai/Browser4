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

import ai.platon.pulsar.common.getLogger
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Resolves the proxy used by the plugin's image download client.
 *
 * Chrome inherits the operating-system proxy (WinINET on Windows, the desktop
 * settings on Linux/macOS) while the JVM only does so when
 * `java.net.useSystemProxies=true` — which the shipped `net.properties` turns
 * off. On a machine where direct egress is blocked that difference makes every
 * plugin download hang while the browser loads the very same URL, so the plugin
 * looks for a proxy itself.
 *
 * Sources are consulted in this order, the first usable one wins:
 *
 * 1. `image.download.proxy` — the explicit configuration
 * 2. standard JVM proxy properties — `https.proxyHost`/`https.proxyPort`,
 *    then `http.proxyHost`/`http.proxyPort`
 * 3. environment — `HTTPS_PROXY`, `https_proxy`, `HTTP_PROXY`, `http_proxy`
 * 4. the operating-system proxy — WinINET on Windows (registry
 *    `HKCU\...\Internet Settings`), otherwise whatever the JVM's default
 *    [ProxySelector] reports (honours `java.net.useSystemProxies`)
 *
 * PAC/auto-config URLs are not supported: they need a JavaScript engine to
 * evaluate, so they are ignored rather than guessed at.
 */
object ImageProxyResolver {
    private val logger = getLogger(ImageProxyResolver::class)

    /** An HTTP proxy endpoint. */
    data class ProxySpec(
        /** Proxy host, e.g. `127.0.0.1`. */
        val host: String,
        /** Proxy port. */
        val port: Int,
        /** Where the value came from, for logs and error messages. */
        val source: String,
    ) {
        /** The endpoint as an OkHttp/JDK [InetSocketAddress]. */
        fun toSocketAddress(): InetSocketAddress = InetSocketAddress(host, port)

        /** Human-readable form, e.g. `127.0.0.1:10808 (Windows system proxy)`. */
        override fun toString(): String = "$host:$port ($source)"
    }

    /** Environment variables consulted, in order. */
    private val PROXY_ENV_VARS = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")

    /** JVM proxy properties consulted, in order. */
    private val PROXY_PROPERTIES = listOf("https.proxyHost" to "https.proxyPort", "http.proxyHost" to "http.proxyPort")

    /**
     * Resolve the proxy for image downloads, or null when none is configured
     * and none can be discovered (direct egress is then attempted).
     */
    fun resolve(configProxy: String? = null): ProxySpec? = resolve(
        configProxy = configProxy,
        env = System::getenv,
        prop = System::getProperty,
        osProxy = ::systemProxy,
    )

    /**
     * [resolve] with injectable sources, so precedence and parsing are testable
     * without touching the real environment.
     *
     * @param configProxy value of `image.download.proxy`
     * @param env environment lookup (name → value)
     * @param prop JVM system-property lookup (name → value)
     * @param osProxy raw operating-system proxy spec (e.g. `127.0.0.1:10808`),
     *   or null when the OS declares none
     */
    internal fun resolve(
        configProxy: String?,
        env: (String) -> String?,
        prop: (String) -> String?,
        osProxy: () -> String?,
    ): ProxySpec? {
        parse(configProxy)?.let { return it.copy(source = "image.download.proxy") }

        for ((hostProp, portProp) in PROXY_PROPERTIES) {
            val host = prop(hostProp)?.trim().orEmpty()
            if (host.isBlank()) continue
            val port = prop(portProp)?.trim()?.toIntOrNull() ?: 80
            parse("$host:$port")?.let { return it.copy(source = "-D$hostProp=$host") }
        }

        for (name in PROXY_ENV_VARS) {
            val value = env(name)?.trim().orEmpty()
            if (value.isBlank()) continue
            parse(value)?.let { return it.copy(source = "$name environment variable") }
        }

        val os = osProxy()?.trim().orEmpty()
        if (os.isNotBlank()) {
            parse(os)?.let { return it.copy(source = "operating-system proxy") }
        }

        return null
    }

    /**
     * Parse a proxy spec. Accepts `host:port`, `scheme://host:port`,
     * `user:pass@host:port` and the Windows registry list form
     * `http=host:port;https=host:port` (the `https`/`http` entry is preferred).
     *
     * @return the parsed spec, or null when [spec] declares no usable host/port
     */
    fun parse(spec: String?): ProxySpec? {
        val trimmed = spec?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        // Windows stores per-scheme entries separated by ';'
        if (trimmed.contains('=')) {
            val entries = trimmed.split(';')
                .mapNotNull { entry ->
                    val name = entry.substringBefore('=', "").trim().lowercase()
                    val value = entry.substringAfter('=', "").trim()
                    if (value.isBlank()) null else name to value
                }
            val preferred = entries.firstOrNull { it.first == "https" }?.second
                ?: entries.firstOrNull { it.first == "http" }?.second
                ?: entries.firstOrNull()?.second
            return parse(preferred)
        }

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        return try {
            val uri = URI(withScheme)
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val port = if (uri.port > 0) uri.port else defaultPort(uri.scheme)
            ProxySpec(host, port, "proxy")
        } catch (e: Exception) {
            logger.warn("Invalid image download proxy spec '{}': {}", spec, e.message)
            null
        }
    }

    /** The port assumed when a proxy spec omits one. */
    private fun defaultPort(scheme: String?): Int = when (scheme?.lowercase()) {
        "https" -> 443
        "socks", "socks5" -> 1080
        else -> 80
    }

    /**
     * The operating-system proxy: the WinINET registry settings on Windows,
     * otherwise the JVM's default [ProxySelector] (which reports a proxy when
     * `java.net.useSystemProxies=true` and the desktop environment declares one).
     */
    private fun systemProxy(): String? = windowsSystemProxy() ?: defaultProxySelectorProxy()

    /**
     * Read `ProxyEnable`/`ProxyServer` from the WinINET registry key. `reg.exe`
     * is the only registry access available to the JVM without JNI, so the query
     * is spawned once per client creation — never on the download path.
     */
    private fun windowsSystemProxy(): String? {
        val os = System.getProperty("os.name").orEmpty()
        if (!os.startsWith("Windows", ignoreCase = true)) return null

        val output = try {
            val process = ProcessBuilder(
                "reg", "query", WININET_KEY, "/v", "ProxyEnable"
            ).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            text
        } catch (e: Exception) {
            logger.debug("Could not read the Windows system proxy: {}", e.message)
            return null
        }

        if (!proxyEnabled(output)) return null

        val server = try {
            val process = ProcessBuilder(
                "reg", "query", WININET_KEY, "/v", "ProxyServer"
            ).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) null else text
        } catch (e: Exception) {
            logger.debug("Could not read the Windows system proxy server: {}", e.message)
            null
        } ?: return null

        return server.lineSequence()
            .firstOrNull { it.contains("ProxyServer") }
            ?.substringAfter("REG_SZ", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    /** Whether `reg query ... /v ProxyEnable` output says the proxy is on. */
    internal fun proxyEnabled(regOutput: String): Boolean {
        val line = regOutput.lineSequence().firstOrNull { it.contains("ProxyEnable") } ?: return false
        val value = line.substringAfter("REG_DWORD", "").trim()
        val enabled = when {
            value.startsWith("0x", ignoreCase = true) -> value.substring(2).toIntOrNull(16) ?: 0
            else -> value.toIntOrNull() ?: 0
        }
        return enabled != 0
    }

    /** Ask the JVM's default [ProxySelector]; NO_PROXY means "nothing to reuse". */
    private fun defaultProxySelectorProxy(): String? = try {
        val selector = ProxySelector.getDefault() ?: return null
        selector.select(URI("https://example.com"))
            .firstOrNull { it?.type() == Proxy.Type.HTTP }
            ?.address()
            ?.let { it as? InetSocketAddress }
            ?.let { address ->
                val host = address.hostString
                if (host.isNullOrBlank()) null else "$host:${address.port}"
            }
    } catch (e: Exception) {
        logger.debug("Default ProxySelector lookup failed: {}", e.message)
        null
    }

    private const val WININET_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
}

/**
 * Routes downloads through a fixed proxy, except to loopback hosts: a locally
 * served image must never depend on a proxy that the OS happens to declare.
 */
class ImageProxySelector(private val proxy: ImageProxyResolver.ProxySpec) : ProxySelector() {
    private val logger = getLogger(ImageProxySelector::class)

    override fun select(uri: URI): List<Proxy> {
        val host = uri.host.orEmpty()
        val loopback = host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host == "[::1]" ||
            host.endsWith(".localhost", ignoreCase = true)
        return if (loopback) {
            listOf(Proxy.NO_PROXY)
        } else {
            listOf(Proxy(Proxy.Type.HTTP, proxy.toSocketAddress()))
        }
    }

    override fun connectFailed(uri: URI, sa: java.net.SocketAddress, ioe: java.io.IOException) {
        logger.warn("Image download proxy connection failed for {} ({}): {}", uri, sa, ioe.message)
    }
}
