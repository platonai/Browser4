package ai.platon.pulsar.browser.mcp

import ai.platon.browser4.chrome.manage.PulsarBrowserFactory
import ai.platon.pulsar.browser.manage.BasicBrowserManager
import ai.platon.pulsar.common.config.ImmutableConfig

/**
 * Entry point for the standalone MCP web server embedded in browser4-browser.
 *
 * Usage:
 *   java -cp browser4-browser.jar:... ai.platon.pulsar.browser.mcp.MCPBrowserServerRunnerKt
 *
 * Port is configurable via:
 *   - System property: -Dbrowser4.mcp.server.port=9090
 *   - Environment variable: BROWSER4_MCP_PORT=9090
 *   - Default: 8182
 *
 * The server exposes browser automation tools via HTTP, compatible with the
 * browser4-cli protocol (POST /mcp/call-tool, GET /mcp/tools).
 * Each `open_session` call launches an ephemeral Chrome instance.
 */
object MCPBrowserServerRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = MCPBrowserServer.resolvePort()

        // Wire up browser infrastructure
        val config = ImmutableConfig()
        val factory = PulsarBrowserFactory(config)
        val browserManager = BasicBrowserManager(factory, config)

        // Wire up MCP layer
        val sessionManager = MCPSessionManager(browserManager)
        val dispatcher = MCPToolDispatcher(sessionManager)
        val server = MCPBrowserServer(port = port, dispatcher = dispatcher)

        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            println("\nShutting down Browser4 MCP server...")
            try {
                server.stop(0)
            } catch (e: Exception) {
                println("Error stopping server: ${e.message}")
            }
            try {
                sessionManager.close()
            } catch (e: Exception) {
                println("Error closing sessions: ${e.message}")
            }
            println("Shutdown complete.")
        })

        // Start
        server.start()
        println("Browser4 MCP server started on port $port")
        println("Endpoints:")
        println("  POST http://localhost:$port/mcp/call-tool")
        println("  GET  http://localhost:$port/mcp/tools")
        println("Press Ctrl+C to stop")
    }
}

/**
 * Top-level main entry point for Kotlin-friendly invocation.
 */
fun main() = MCPBrowserServerRunner.main(emptyArray())
