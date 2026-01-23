package ai.platon.pulsar.test.mcp

import ai.platon.pulsar.common.getLogger
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Utility launcher to programmatically start / stop the MCP test server.
 *
 * This follows the same pattern as MockSiteLauncher but for the MCP server.
 * It launches a Spring Boot application that provides the TestMCPServer endpoints.
 */
object MCPServerLauncher : Closeable {
    private val logger = getLogger(this)

    @Volatile
    private var context: ConfigurableApplicationContext? = null

    @Volatile
    private var boundPort: Int? = null

    private val starting = AtomicBoolean(false)

    /** Whether the MCP server is currently running. */
    fun isRunning(): Boolean = context?.isActive == true

    /** The port the server actually bound to (null if not started). */
    fun port(): Int? = boundPort

    /** Base URL (null if not started). */
    fun baseUrl(): String? = boundPort?.let { "http://localhost:$it/" }

    /**
     * Start the MCP server if not already started or restart if running on a different port and [enforcePort] is true.
     *
     * @param port Desired port (0 for random). Will override application*.properties via command-line arg.
     * @param enforcePort If true and already running on a different port, the server is restarted on the requested port.
     */
    @Synchronized
    fun start(
        port: Int = 18088,
        properties: Map<String, Any> = emptyMap(),
        profiles: Array<String> = emptyArray(),
        headless: Boolean = true,
        enforcePort: Boolean = true,
    ): ConfigurableApplicationContext {
        if (isRunning()) {
            val current = boundPort
            if (enforcePort && current != null && current != port) {
                logger.warn("MCPServerApplication already running on port $current, requested $port -> restarting to enforce port")
                stop()
            } else {
                logger.info("MCPServerApplication already running on port $current, reuse context")
                return context!!
            }
        }
        if (!starting.compareAndSet(false, true)) {
            // Another thread is starting; spin until available
            while (!isRunning()) Thread.sleep(50)
            return context!!
        }
        try {
            // Keep only non-port defaults here; port will be injected as a command-line arg to guarantee precedence.
            val defaultProps = (properties + mapOf(
                "logging.level.root" to (properties["logging.level.root"] ?: "INFO"),
                // Disable DevTools restart and its default property post-processor for programmatic launch
                "spring.devtools.restart.enabled" to "false",
                "spring.devtools.add-properties" to "false",
            )).toMutableMap()

            // Also set JVM system properties to ensure DevTools restart is disabled as early as possible
            System.setProperty("spring.devtools.restart.enabled", "false")
            System.setProperty("spring.devtools.add-properties", "false")

            val builder = SpringApplicationBuilder(MCPServerApplication::class.java)
                .properties(defaultProps)
                .profiles(*profiles)
                .headless(headless)

            val args = mutableListOf<String>()
            // Always force desired port via command line arg for highest precedence (over application.properties).
            args += "--server.port=$port"
            if (headless) args += "--java.awt.headless=true"

            logger.info("Starting MCPServerApplication | requestedPort=$port enforcePort=$enforcePort args=$args props(no-port)=${defaultProps - "server.port"}")
            val ctx = builder.run(*args.toTypedArray())
            context = ctx

            val resolvedPort = ctx.environment.getProperty("local.server.port")?.toInt()
                ?: ctx.environment.getProperty("server.port")?.toInt()
                ?: port
            boundPort = resolvedPort
            if (resolvedPort != port && port != 0) {
                logger.warn("Requested port $port but server bound to $resolvedPort. (If unexpected, check other overrides / port in use)")
            } else {
                logger.info("MCPServerApplication started on port $resolvedPort")
            }
            return ctx
        } catch (e: Exception) {
            starting.set(false)
            throw IllegalStateException("Failed to start MCPServerApplication: ${e.message}", e)
        } finally {
            starting.set(false)
        }
    }

    /** Restart the server: stops if running, then starts again. */
    @Synchronized
    fun restart(port: Int = boundPort ?: 18088, properties: Map<String, Any> = emptyMap()): ConfigurableApplicationContext {
        stop()
        return start(port = port, properties = properties)
    }

    /**
     * Wait until the MCP info endpoint responds with a 2xx/3xx status or timeout reached.
     * @return true if ready, false if timed out.
     */
    fun awaitReady(
        timeout: Duration = Duration.ofSeconds(10),
        interval: Duration = Duration.ofMillis(300),
        infoPath: String = "/mcp/info"
    ): Boolean {
        val port = boundPort ?: return false
        val infoUrl = URI.create("http://localhost:$port$infoPath").toURL()
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            if (probe(infoUrl)) return true
            Thread.sleep(interval.toMillis())
        }
        return false
    }

    private fun probe(url: URL): Boolean {
        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1000
                readTimeout = 1500
                requestMethod = "GET"
            }
            conn.inputStream.use { }
            val code = conn.responseCode
            code in 200..399
        } catch (_: Exception) {
            false
        }
    }

    /** Stop the server if running. */
    @Synchronized
    fun stop() {
        try {
            context?.close()
        } finally {
            context = null
            boundPort = null
        }
    }

    override fun close() = stop()
}
