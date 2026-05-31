package ai.platon.pulsar.test.server

import ai.platon.pulsar.common.getLogger
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Duration
import java.time.Instant

/**
 * Reusable utility to wait for a demo/mock site to become available.
 *
 * Features:
 *  - Tries a health endpoint first (default /actuator/health) then falls back to root / (optional)
 *  - Configurable timeouts & intervals
 *  - Returns true if any probe gets a 2xx/3xx response
 *
 * This logic was extracted from SessionInstructionsExample so other demos/examples can share it.
 */
class MockSiteStarter: AutoCloseable {
    private val logger = getLogger(this)

    data class Options(
        val timeout: Duration = Duration.ofSeconds(12),
        val interval: Duration = Duration.ofMillis(500),
        val healthPath: String = System.getProperty("mock.site.healthPath", "/actuator/health"),
        val fallbackRoot: Boolean = true,
        val connectTimeoutMillis: Int = 1200,
        val readTimeoutMillis: Int = 1800,
        val verbose: Boolean = true,
    )

    /**
     * Ensure the mock site serving the given url is started. Extracts the explicit port from the URL; if absent uses
     * system/env configured port or sensible fallbacks (8182, then 8080) instead of the protocol default (80).
     */
    fun start(url: String) {
        logger.info("Ensure mock site is running (autoStart always enabled)")
        var ok = wait(url)

        if (!ok) {
            try {
                val u = URI(url).toURL()
                val configuredPort = System.getProperty("mock.site.port")?.toIntOrNull()
                    ?: System.getenv("MOCK_SITE_PORT")?.toIntOrNull()
                val desiredPort = when {
                    u.port > 0 -> u.port
                    configuredPort != null -> configuredPort
                    else -> 8182 // primary fallback
                }
                val fallbackPorts = listOfNotNull(configuredPort, 8182, 8080).distinct()
                logger.info("Attempting to auto-start MockSiteApplication on port $desiredPort (candidates=$fallbackPorts) ...")
                MockSiteLauncher.start(port = desiredPort, enforcePort = true)
                val ready = MockSiteLauncher.awaitReady(Duration.ofSeconds(10))
                if (!ready && desiredPort != 0 && desiredPort != u.port && configuredPort == null) {
                    // Try next fallback if first failed
                    for (p in fallbackPorts) {
                        if (p == desiredPort) continue
                        logger.warn("Retry auto-start on fallback port $p ...")
                        MockSiteLauncher.start(port = p, enforcePort = true)
                        if (MockSiteLauncher.awaitReady(Duration.ofSeconds(6))) break
                    }
                }
                if (MockSiteLauncher.isRunning()) {
                    logger.info("Auto-start success: ${MockSiteLauncher.baseUrl()}")
                } else {
                    logger.warn("Auto-start attempted but site not ready within timeout")
                }
            } catch (e: Exception) {
                logger.error("Failed to auto-start mock site: ${e.message}", e)
            }
        }

        ok = wait(url, Options(verbose = false))

        check(ok) { "Failed to start mock site" }

        Runtime.getRuntime().addShutdownHook(Thread { this.close() })
    }

    /**
     * Wait for the site referred to by a full page URL (any path under host). Only host/port are probed.
     * @param pageUrl Any URL within the target host (ex: http://localhost:8182/generated/tta/instructions/instructions-demo.html)
     */
    fun wait(pageUrl: String, options: Options = Options()): Boolean {
        val (healthURL, rootURL) = try {
            val u = URI.create(pageUrl).toURL()
            val effectivePort = if (u.port != -1) u.port else (System.getProperty("mock.site.port")?.toIntOrNull()
                ?: System.getenv("MOCK_SITE_PORT")?.toIntOrNull() ?: 8182)
            // val hostPort = URL(u.protocol, u.host, effectivePort, "/")
            val hostPort = URI(
                u.protocol,
                null,
                u.host,
                effectivePort,
                "/",
                null,
                null
            ).toURL()
            val health = URI(u.protocol, null, u.host, effectivePort, options.healthPath, null, null).toURL()
            health to hostPort
        } catch (e: Exception) {
            if (options.verbose) logger.error("[DemoSiteStarter] Invalid URL: $pageUrl | ${e.message}")
            return false
        }

        val deadline = Instant.now().plus(options.timeout)
        while (Instant.now().isBefore(deadline)) {
            if (probe(healthURL, options) || (options.fallbackRoot && probe(rootURL, options))) {
                if (options.verbose) logger.info("[DemoSiteStarter] Site is up: $healthURL")
                return true
            }
            Thread.sleep(options.interval.toMillis())
        }
        if (options.verbose) logger.warn("[DemoSiteStarter] Site not reachable within ${options.timeout.toMillis()}ms: $pageUrl")
        return false
    }

    fun stop() {
        MockSiteLauncher.stop()
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
            if (ok && options.verbose) logger.info("[DemoSiteStarter] Probe success $url -> $code")
            ok
        } catch (_: Exception) {
            false
        }
    }
}
