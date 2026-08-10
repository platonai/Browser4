package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.getLogger
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.util.concurrent.CountDownLatch

/**
 * Starts the Browser4 MCP server.
 *
 * Two transports are supported:
 *
 * - **STDIO** (default) — standard integration used by Claude Desktop, Cursor,
 *   Windsurf, and other MCP-compatible AI clients that launch the server as a
 *   local subprocess and communicate via stdin/stdout.
 * - **HTTP** (SSE) — for remote MCP clients that connect over HTTP.  The server
 *   listens on a configurable port and exposes `/mcp/sse` (GET, SSE stream) and
 *   `/mcp/message` (POST, JSON-RPC).
 *
 * ## Usage
 *
 * ```bash
 * # STDIO (default)
 * java -jar Browser4.jar --app mcp
 *
 * # HTTP (SSE transport)
 * java -jar Browser4.jar --app mcp --transport http
 * java -jar Browser4.jar --app mcp --transport http --port 8088
 *
 * # Enable headless mode (either transport)
 * java -jar Browser4.jar --app mcp --headless
 * ```
 *
 * ## Claude Desktop configuration (`claude_desktop_config.json`)
 *
 * ### STDIO transport
 * ```json
 * {
 *   "mcpServers": {
 *     "browser4": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/Browser4.jar", "--app", "mcp"]
 *     }
 *   }
 * }
 * ```
 *
 * ### HTTP transport
 * ```json
 * {
 *   "mcpServers": {
 *     "browser4": {
 *       "type": "streamableHttp",
 *       "url": "http://localhost:8088/mcp/sse"
 *     }
 *   }
 * }
 * ```
 */
fun runBrowser4MCPServer(args: Array<String> = emptyArray()) {
    val logger = getLogger("Browser4MCPServerRunner")

    // Parse options
    var transport = "stdio"
    var port = McpHttpServer.DEFAULT_MCP_HTTP_PORT
    var headless = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--transport" -> {
                if (i + 1 < args.size) {
                    transport = args[++i].lowercase()
                }
            }
            "--port" -> {
                if (i + 1 < args.size) {
                    port = args[++i].toIntOrNull() ?: McpHttpServer.DEFAULT_MCP_HTTP_PORT
                }
            }
            "--headless" -> headless = true
            "--help", "-h" -> {
                printUsage()
                return
            }
            else -> {
                System.err.println("Unknown option: ${args[i]}")
                printUsage()
                return
            }
        }
        i++
    }

    require(transport in setOf("stdio", "http")) {
        "Unknown transport: $transport (expected 'stdio' or 'http')"
    }

    // Create session and acquire the agent
    val session = AgenticContexts.createSession(headless = headless)
    val agent = session.companionAgent as? BasicBrowserAgent
        ?: throw IllegalStateException(
            "MCP server requires a BasicBrowserAgent, but companion agent is ${session.companionAgent::class.simpleName}"
        )

    try {
        when (transport) {
            "stdio" -> runStdioServer(logger, agent)
            "http" -> runHttpServer(logger, agent, port)
        }
    } finally {
        logger.info("Shutting down Browser4 MCP Server")
        AgenticContexts.shutdown()
    }
}

// ---------------------------------------------------------------------------
// Transport implementations
// ---------------------------------------------------------------------------

private fun runStdioServer(logger: org.slf4j.Logger, agent: BasicBrowserAgent) {
    logger.info("Starting Browser4 MCP Server (STDIO transport)")

    val mcpServer = Browser4MCPServer(toolManager = agent.agentToolManager)
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )

    runBlocking {
        logger.info("Browser4 MCP Server connected — waiting for client requests")
        mcpServer.server.connect(transport)
        logger.info("Browser4 MCP Server STDIO session ended")
    }
}

private fun runHttpServer(logger: org.slf4j.Logger, agent: BasicBrowserAgent, port: Int) {
    logger.info("Starting Browser4 MCP Server (HTTP/SSE transport on port {})", port)

    val server = McpHttpServer(
        toolManager = agent.agentToolManager,
        port = port,
    )
    server.start()

    logger.info(
        "Browser4 MCP HTTP Server listening on http://localhost:{}/mcp/sse",
        port,
    )

    // Block until the JVM is terminated (SIGTERM / Ctrl+C).
    // The CountDownLatch never counts down — the JVM exit will interrupt it.
    try {
        CountDownLatch(1).await()
    } catch (_: InterruptedException) {
        // Shutdown signal received.
    } finally {
        server.stop()
    }

    logger.info("Browser4 MCP HTTP Server session ended")
}

// ---------------------------------------------------------------------------
// Help
// ---------------------------------------------------------------------------

private fun printUsage() {
    System.err.println(
        """
        |Browser4 MCP Server
        |
        |Usage: java -jar Browser4.jar --app mcp [options]
        |
        |Transport options:
        |  --transport stdio    STDIO transport (default) — for local MCP clients
        |  --transport http     HTTP/SSE transport — for remote MCP clients
        |
        |HTTP options (only with --transport http):
        |  --port <n>           Listen port (default: 8088)
        |
        |General options:
        |  --headless           Run Chrome in headless mode
        |  --help, -h           Print this help
        |
        |Examples:
        |  java -jar Browser4.jar --app mcp
        |  java -jar Browser4.jar --app mcp --transport http --port 8088
        |  java -jar Browser4.jar --app mcp --headless
        """.trimMargin()
    )
}

fun main(args: Array<String>) = runBrowser4MCPServer(args)
