package ai.platon.pulsar.test.mcp

import ai.platon.pulsar.common.getLogger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Duration
import java.time.Instant

/**
 * Reusable utility to wait for the MCP test server to become available.
 *
 * Features:
 *  - Tries the MCP info endpoint first (/mcp/info) then falls back to root / (optional)
 *  - Configurable timeouts & intervals
 *  - Returns true if any probe gets a 2xx/3xx response
 *
 * This logic follows the same pattern as DemoSiteStarter so examples can ensure
 * the MCP server is running before attempting to connect.
 */
class MCPServerStarter : AutoCloseable {
    private val logger = getLogger(this)

    data class Options(
        val timeout: Duration = Duration.ofSeconds(12),
        val interval: Duration = Duration.ofMillis(500),
        val infoPath: String = System.getProperty("mcp.server.infoPath", "/mcp/info"),
        val fallbackRoot: Boolean = true,
        val connectTimeoutMillis: Int = 1200,
        val readTimeoutMillis: Int = 1800,
        val verbose: Boolean = true,
    )

    /**
     * Ensure the MCP server serving the given url is started. Extracts the explicit port from the URL; if absent uses
     * system/env configured port or sensible fallbacks (18088, then 8088) instead of the protocol default (80).
     */
    fun start(url: String) {
        logger.info("Ensure MCP server is running (autoStart always enabled)")
        var ok = wait(url)

        if (!ok) {
            try {
                val u = URI(url).toURL()
                val configuredPort = System.getProperty("mcp.server.port")?.toIntOrNull()
                    ?: System.getenv("MCP_SERVER_PORT")?.toIntOrNull()
                val desiredPort = when {
                    u.port > 0 -> u.port
                    configuredPort != null -> configuredPort
                    else -> 18088 // primary fallback
                }
                val fallbackPorts = listOfNotNull(configuredPort, 18088, 8088).distinct()
                logger.info("Attempting to auto-start MCPServerApplication on port $desiredPort (candidates=$fallbackPorts) ...")
                MCPServerLauncher.start(port = desiredPort, enforcePort = true)
                val ready = MCPServerLauncher.awaitReady(Duration.ofSeconds(10))
                if (!ready && desiredPort != 0 && desiredPort != u.port && configuredPort == null) {
                    // Try next fallback if first failed
                    for (p in fallbackPorts) {
                        if (p == desiredPort) continue
                        logger.warn("Retry auto-start on fallback port $p ...")
                        MCPServerLauncher.start(port = p, enforcePort = true)
                        if (MCPServerLauncher.awaitReady(Duration.ofSeconds(6))) break
                    }
                }
                if (MCPServerLauncher.isRunning()) {
                    logger.info("Auto-start success: ${MCPServerLauncher.baseUrl()}")
                } else {
                    logger.warn("Auto-start attempted but MCP server not ready within timeout")
                }
            } catch (e: Exception) {
                logger.error("Failed to auto-start MCP server: ${e.message}", e)
            }
        }

        ok = wait(url, Options(verbose = false))

        check(ok) { "Failed to start MCP server" }

        Runtime.getRuntime().addShutdownHook(Thread { this.close() })
    }

    /**
     * Wait for the MCP server referred to by a full endpoint URL (any path under host). Only host/port are probed.
     * @param endpointUrl Any URL within the target host (ex: http://localhost:18088/mcp)
     */
    fun wait(endpointUrl: String, options: Options = Options()): Boolean {
        val (infoURL, rootURL) = try {
            val u = URI.create(endpointUrl).toURL()
            val effectivePort = if (u.port != -1) u.port else (System.getProperty("mcp.server.port")?.toIntOrNull()
                ?: System.getenv("MCP_SERVER_PORT")?.toIntOrNull() ?: 18088)
            val hostPort = URL(u.protocol, u.host, effectivePort, "/")
            val info = URL(u.protocol, u.host, effectivePort, options.infoPath)
            info to hostPort
        } catch (e: Exception) {
            if (options.verbose) logger.error("[MCPServerStarter] Invalid URL: $endpointUrl | ${e.message}")
            return false
        }

        val deadline = Instant.now().plus(options.timeout)
        while (Instant.now().isBefore(deadline)) {
            if (probe(infoURL, options) || (options.fallbackRoot && probe(rootURL, options))) {
                if (options.verbose) logger.info("[MCPServerStarter] MCP server is up: $infoURL")
                return true
            }
            Thread.sleep(options.interval.toMillis())
        }
        if (options.verbose) logger.warn("[MCPServerStarter] MCP server not reachable within ${options.timeout.toMillis()}ms: $endpointUrl")
        return false
    }

    fun stop() {
        MCPServerLauncher.stop()
    }

    override fun close() {
        stop()
    }

    private fun probe(url: URL, options: Options): Boolean {
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = options.connectTimeoutMillis
            conn.readTimeout = options.readTimeoutMillis
            conn.requestMethod = "GET"
            conn.inputStream.use { }
            val code = conn.responseCode
            val ok = code in 200..399
            if (ok && options.verbose) logger.info("[MCPServerStarter] Probe success $url -> $code")
            ok
        } catch (_: Exception) {
            false
        }
    }
}
