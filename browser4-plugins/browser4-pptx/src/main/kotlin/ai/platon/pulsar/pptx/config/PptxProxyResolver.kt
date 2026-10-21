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

import ai.platon.pulsar.common.getLogger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Resolves the HTTP proxy used for PPTX image downloads.
 *
 * Chrome uses the OS system proxy (WinINET on Windows, platform settings on macOS)
 * while the JVM only honours `-Djava.net.useSystemProxies=true`, so image downloads
 * silently bypass the only working egress route on machines where direct access is
 * blocked. This resolver therefore looks at every configuration a user can reach,
 * in precedence order:
 *
 * 1. `pptx.download.proxy` (the plugin's own key)
 * 2. `HTTPS_PROXY` / `https_proxy` / `HTTP_PROXY` / `http_proxy`
 * 3. the JVM proxy properties (`https.proxyHost`/`https.proxyPort`, then
 *    `http.proxyHost`/`http.proxyPort`)
 * 4. the OS system proxy — the WinINET registry settings on Windows
 *    (`ProxyEnable`/`ProxyServer`), then the JVM's default [ProxySelector]
 *
 * Only HTTP proxies are supported: OkHttp cannot express a SOCKS proxy through an
 * [InetSocketAddress], so a `socks=` WinINET entry (or a SOCKS default selector) is
 * ignored with a warning instead of silently producing a broken client.
 */
object PptxProxyResolver {
    private val logger = getLogger(PptxProxyResolver::class)

    /** Windows registry key holding the WinINET internet settings. */
    const val WINDOWS_INTERNET_SETTINGS_KEY =
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    /** Environment variables consulted, in precedence order. */
    val PROXY_ENV_VARS = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")

    /** JVM properties consulted, in precedence order: host property to port property. */
    val PROXY_SYSTEM_PROPERTIES = listOf(
        "https.proxyHost" to "https.proxyPort",
        "http.proxyHost" to "http.proxyPort",
    )

    /** Timeout for reading a value from the Windows registry. */
    private const val REGISTRY_TIMEOUT_SECONDS = 3L

    private val WHITESPACE = Regex("\\s+")

    /**
     * A resolved proxy endpoint.
     *
     * @property address the proxy to connect through
     * @property source where the setting came from, for logs and diagnostics
     * @property bypassPatterns host patterns that must be contacted directly
     *           (`*` wildcards, plus the special `<local>` marker meaning
     *           "host names without a dot")
     */
    data class ProxyResolution(
        val address: InetSocketAddress,
        val source: String,
        val bypassPatterns: List<String> = emptyList(),
    ) {
        /** `host:port` rendering used by logs and diagnostics. */
        val endpoint: String get() = "${address.hostString}:${address.port}"
    }

    /**
     * Environment lookups, injectable so the resolution order can be tested
     * without touching the real process environment.
     *
     * @property env reads an environment variable
     * @property property reads a JVM system property
     * @property osProxy resolves the OS system proxy
     */
    data class Lookups(
        val env: (String) -> String?,
        val property: (String) -> String?,
        val osProxy: () -> ProxyResolution?,
    ) {
        companion object {
            /** Lookups backed by the running JVM and operating system. */
            val SYSTEM: Lookups = Lookups(System::getenv, System::getProperty) { fromOperatingSystem() }
        }
    }

    /**
     * Resolve the proxy for PPTX image downloads from the real environment.
     *
     * @param config the plugin configuration
     * @return the resolved proxy, or null when every request should be direct
     *         (in which case OkHttp's own default proxy selector still applies)
     */
    fun resolve(config: PptxConfig): ProxyResolution? = resolve(config, Lookups.SYSTEM)

    /**
     * Resolve the proxy for PPTX image downloads using explicit [lookups].
     *
     * @param config the plugin configuration
     * @param lookups environment accessors, injectable for tests
     * @return the resolved proxy, or null when no configuration was found
     */
    fun resolve(config: PptxConfig, lookups: Lookups): ProxyResolution? {
        fromSpec(config.proxy, "pptx.download.proxy")?.let { return it }
        fromEnvironment(lookups.env)?.let { return it }
        fromSystemProperties(lookups.property)?.let { return it }

        if (!config.useSystemProxy) {
            logger.info("PPTX image downloads: OS system proxy lookup is disabled (pptx.download.use-system-proxy=false)")
            return null
        }

        return lookups.osProxy()
    }

    /**
     * Parse an explicit proxy specification.
     *
     * @param spec `host:port` or a full proxy URL
     * @param source description of where [spec] came from, used in warnings
     * @return the parsed proxy, or null when [spec] is blank or invalid
     */
    fun fromSpec(spec: String?, source: String): ProxyResolution? {
        if (spec.isNullOrBlank()) return null

        val address = parseProxyAddress(spec)
        if (address == null) {
            logger.warn(
                "Invalid PPTX image download proxy '{}' from {}: expected host:port or a proxy URL",
                spec, source
            )
            return null
        }
        return ProxyResolution(address, source)
    }

    /**
     * Read the proxy from the well-known proxy environment variables.
     *
     * @param env environment variable accessor
     * @return the first usable setting, or null
     */
    fun fromEnvironment(env: (String) -> String?): ProxyResolution? {
        for (name in PROXY_ENV_VARS) {
            val value = env(name)
            if (value.isNullOrBlank()) continue

            val address = parseProxyAddress(value)
            if (address != null) return ProxyResolution(address, name)
            logger.warn("Ignoring invalid {} value '{}': expected host:port or a proxy URL", name, value)
        }
        return null
    }

    /**
     * Read the proxy from the JVM proxy system properties.
     *
     * @param property system property accessor
     * @return the first usable setting, or null
     */
    fun fromSystemProperties(property: (String) -> String?): ProxyResolution? {
        val bypass = parseNonProxyHosts(property("http.nonProxyHosts"))

        for ((hostKey, portKey) in PROXY_SYSTEM_PROPERTIES) {
            val host = property(hostKey)?.trim().orEmpty()
            if (host.isEmpty()) continue

            val port = property(portKey)?.trim()?.toIntOrNull() ?: DEFAULT_PROXY_PORT
            if (port !in 1..MAX_PORT) {
                logger.warn("Ignoring invalid -D{}={} (out of range)", portKey, port)
                continue
            }
            return ProxyResolution(InetSocketAddress(host, port), "-D$hostKey=$host:$port", bypass)
        }
        return null
    }

    /**
     * Resolve the OS system proxy: WinINET on Windows, the JVM default selector elsewhere.
     *
     * @return the resolved proxy, or null when the OS exposes none (or exposes an
     *         unsupported SOCKS proxy)
     */
    fun fromOperatingSystem(): ProxyResolution? {
        if (isWindows()) {
            fromWindowsRegistry()?.let { return it }
        }
        return fromDefaultProxySelector()
    }

    /**
     * Parse a proxy address specification.
     *
     * @param spec `host:port`, `host`, or a proxy URL such as `http://host:port`
     * @return the address, or null when [spec] has no usable host
     */
    fun parseProxyAddress(spec: String): InetSocketAddress? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return null

        val uri = try {
            URI(if (trimmed.contains("://")) trimmed else "http://$trimmed")
        } catch (e: Exception) {
            return null
        }

        val host = uri.host
        if (host.isNullOrBlank()) return null

        val port = if (uri.port > 0) uri.port else DEFAULT_PROXY_PORT
        if (port !in 1..MAX_PORT) return null
        return InetSocketAddress(host, port)
    }

    /**
     * Parse the WinINET `ProxyServer` value.
     *
     * Accepts both the plain form (`127.0.0.1:10808`) and the per-protocol form
     * (`http=proxy:80;https=proxy:443;socks=proxy:1080`), preferring `https` so
     * image hosts served over TLS use the right route.
     *
     * @param raw the registry value
     * @return the parsed address, or null when nothing usable was found
     */
    fun parseProxyServer(raw: String?): InetSocketAddress? {
        if (raw.isNullOrBlank()) return null

        val trimmed = raw.trim()
        if (!trimmed.contains('=')) return parseProxyAddress(trimmed)

        val byScheme = linkedMapOf<String, String>()
        for (entry in trimmed.split(';')) {
            val separator = entry.indexOf('=')
            if (separator <= 0) continue
            val scheme = entry.substring(0, separator).trim().lowercase()
            val value = entry.substring(separator + 1).trim()
            if (value.isNotEmpty()) byScheme[scheme] = value
        }

        val spec = byScheme["https"] ?: byScheme["http"]
        if (spec == null) {
            if (byScheme.containsKey("socks")) {
                logger.warn(
                    "Ignoring SOCKS system proxy '{}': only HTTP proxies are supported for image downloads",
                    byScheme["socks"]
                )
            }
            return null
        }
        return parseProxyAddress(spec)
    }

    /**
     * Extract a value from `reg query` output.
     *
     * @param output the command output (lines of the form `    Name    REG_SZ    value`)
     * @param name the registry value name to look for
     * @return the value, or null when the name is absent or has no value
     */
    fun parseRegistryValue(output: String, name: String): String? {
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith(name, ignoreCase = true)) continue
            if (trimmed.length > name.length && !trimmed[name.length].isWhitespace()) continue

            val remainder = trimmed.substring(name.length).trim()
            val tokens = remainder.split(WHITESPACE, limit = 2)
            if (tokens.size < 2) return null

            return tokens[1].trim().ifEmpty { null }
        }
        return null
    }

    /**
     * Whether [host] must be contacted directly instead of through the proxy.
     *
     * Loopback hosts are always bypassed so locally served pages keep working.
     *
     * @param host the target host name
     * @param bypassPatterns wildcard patterns plus the `<local>` marker
     * @return true when the host must bypass the proxy
     */
    fun shouldBypass(host: String, bypassPatterns: List<String>): Boolean {
        if (host.isBlank()) return true
        if (isLoopback(host)) return true

        for (pattern in bypassPatterns) {
            when {
                pattern.equals("<local>", ignoreCase = true) -> if (!host.contains('.')) return true
                pattern.isBlank() -> Unit
                globMatches(host, pattern) -> return true
            }
        }
        return false
    }

    /** Split a `http.nonProxyHosts` (pipe separated) value into bypass patterns. */
    fun parseNonProxyHosts(raw: String?): List<String> =
        raw?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Split a WinINET `ProxyOverride` (semicolon separated) value into bypass patterns. */
    fun parseProxyOverride(raw: String?): List<String> =
        raw?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun fromWindowsRegistry(): ProxyResolution? {
        val enabled = readRegistryValue("ProxyEnable")
        if (enabled?.trim()?.let { it == "1" || it.equals("0x1", ignoreCase = true) } != true) return null

        val server = readRegistryValue("ProxyServer")
        val address = parseProxyServer(server)
        if (address == null) {
            logger.warn("Windows system proxy is enabled but ProxyServer '{}' is unusable", server)
            return null
        }
        return ProxyResolution(
            address,
            "Windows WinINET proxy (ProxyServer=$server)",
            parseProxyOverride(readRegistryValue("ProxyOverride"))
        )
    }

    private fun fromDefaultProxySelector(): ProxyResolution? {
        val selector = try {
            ProxySelector.getDefault()
        } catch (e: Exception) {
            logger.debug("Cannot access the JVM default ProxySelector: {}", e.message)
            return null
        } ?: return null

        val proxies = try {
            selector.select(URI(PROBE_URI))
        } catch (e: Exception) {
            logger.debug("JVM default ProxySelector failed: {}", e.message)
            return null
        }

        for (proxy in proxies) {
            if (proxy.type() != Proxy.Type.HTTP) continue
            val address = proxy.address() as? InetSocketAddress ?: continue
            if (address.isUnresolved && address.hostString.isBlank()) continue
            return ProxyResolution(
                address,
                "JVM default ProxySelector (${address.hostString}:${address.port})",
                parseNonProxyHosts(System.getProperty("http.nonProxyHosts"))
            )
        }
        return null
    }

    private fun readRegistryValue(name: String): String? {
        return try {
            val process = ProcessBuilder("reg", "query", WINDOWS_INTERNET_SETTINGS_KEY, "/v", name)
                .redirectErrorStream(true)
                .start()

            if (!process.waitFor(REGISTRY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.debug("Timed out reading the Windows proxy registry value '{}'", name)
                return null
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() != 0) return null

            parseRegistryValue(output, name)
        } catch (e: Exception) {
            logger.debug("Cannot read the Windows proxy registry value '{}': {}", name, e.message)
            null
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    private fun isLoopback(host: String): Boolean {
        return host.equals("localhost", ignoreCase = true)
                || host == "::1" || host == "[::1]"
                || host.startsWith("127.")
    }

    private fun globMatches(host: String, pattern: String): Boolean {
        val regex = StringBuilder("(?i)")
        for (ch in pattern) {
            when (ch) {
                '*' -> regex.append(".*")
                in GLOB_LITERALS -> regex.append('\\').append(ch)
                else -> regex.append(ch)
            }
        }
        return try {
            Regex(regex.toString()).matches(host)
        } catch (e: Exception) {
            false
        }
    }

    /** Default port for a proxy spec that omits one. */
    private const val DEFAULT_PROXY_PORT = 80

    /** Highest valid TCP port. */
    private const val MAX_PORT = 65535

    /** URI probed when asking the JVM's default selector for a proxy. */
    private const val PROBE_URI = "https://example.com/"

    /** Characters that must be escaped when a glob is turned into a regex. */
    private val GLOB_LITERALS = charArrayOf('.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '?')
}

/**
 * A [ProxySelector] that routes every request through [address] except hosts
 * matching one of [bypassPatterns] (loopback is always direct).
 *
 * OkHttp needs a selector rather than a bare [Proxy] so that locally served pages
 * and `nonProxyHosts`/`ProxyOverride` entries keep working.
 */
class PptxProxySelector(
    address: InetSocketAddress,
    private val bypassPatterns: List<String> = emptyList(),
) : ProxySelector() {
    private val logger = getLogger(PptxProxySelector::class)

    private val proxy: Proxy = Proxy(Proxy.Type.HTTP, address)

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host
        if (host.isNullOrBlank() || PptxProxyResolver.shouldBypass(host, bypassPatterns)) {
            return listOf(Proxy.NO_PROXY)
        }
        return listOf(proxy)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // Debug level: one line per failed image would drown the aggregate
        // "N/52 image downloads failed" warning reported by PptxImageDownloader.
        logger.debug("PPTX image download proxy {} failed for {}: {}", proxy.address(), uri, ioe?.message)
    }

    override fun toString(): String = "PptxProxySelector($proxy, bypass=$bypassPatterns)"
}
