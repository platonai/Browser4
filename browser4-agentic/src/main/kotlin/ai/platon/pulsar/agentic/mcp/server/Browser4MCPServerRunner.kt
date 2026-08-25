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
 * This is the standalone entry point (`java -jar Browser4.jar --app mcp`,
 * or `main` in this file). It runs outside Spring Boot and manages its own
 * agentic context via [AgenticContexts].
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
 * ## Display mode (headless by default)
 *
 * The browser opens **headless by default**; use `--headed` to opt into a
 * visible window. This mirrors the CLI (`open --headless` is the default,
 * `open --headed` opts in) and the shipped server-wide default
 * `browser.display.mode=HEADLESS`.
 *
 * Why the default matters here:
 *
 * 1. This runner has **no Spring-wired server configuration**, so the shipped
 *    `browser.display.mode=HEADLESS` default never reaches the launch — the
 *    session's own settings are the only source of truth for the display mode.
 * 2. [ai.platon.pulsar.api.model.BrowserSettings] falls back to
 *    `DisplayMode.GUI` whenever its configuration does not carry
 *    `browser.display.mode`. Without an explicit headless preference, every
 *    server start would therefore pop up a **visible browser window** — even
 *    though the process runs in the background for an AI client that never
 *    asked for one.
 *
 * How the flag reaches Chrome: `--headless`/`--headed` is turned into
 * `PulsarSettings(displayMode = DisplayMode.HEADLESS / null)` and passed to
 * [AgenticContexts.createSession]. The display mode lands in the session
 * config (`browser.display.mode`), and
 * `AbstractPulsarSession.createBoundDriver` launches the browser with the
 * session-level settings. With `--headless` (or no flag) the session config
 * carries `HEADLESS` explicitly; with `--headed` the session has no explicit
 * mode and the launch falls back to the runner's own (non-Spring)
 * configuration, which resolves to GUI on machines with a display.
 *
 * Notes:
 * - If both flags are passed, the **last one wins** (same convention as the
 *   CLI's `--headless`/`--headed` handling).
 * - In environments without GUI support (`Runtimes.hasOnlyHeadlessBrowser()`,
 *   e.g. headless CI or Docker), `BrowserSettings` forces headless regardless,
 *   so `--headed` degrades gracefully instead of failing.
 *
 * ## Options
 *
 * | Option | Meaning |
 * |---|---|
 * | `--transport stdio` | STDIO transport (default) — for local MCP clients |
 * | `--transport http` | HTTP/SSE transport — for remote MCP clients |
 * | `--port <n>` | HTTP listen port (default: 8088; only with `--transport http`) |
 * | `--headless` | Run Chrome in headless mode (default) |
 * | `--headed` | Run Chrome in headed (GUI) mode — a visible window |
 * | `--help`, `-h` | Print usage and exit |
 *
 * Unknown options print the usage and exit; the transport must be `stdio` or
 * `http` or the runner fails fast with an error.
 *
 * ## Session lifecycle
 *
 * The session is created once at startup via [AgenticContexts.createSession]
 * (a fresh non-Spring context, since no Spring application context exists
 * here), and the companion [BasicBrowserAgent] wraps its
 * [ai.platon.pulsar.agentic.tools.AgentToolManager] into a
 * [Browser4MCPServer]. On shutdown (SIGTERM / Ctrl+C / stdin EOF),
 * [AgenticContexts.shutdown] closes the context and its browsers.
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
 * # Headless is the default; use --headed for a visible window (either transport)
 * java -jar Browser4.jar --app mcp --headed
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
/**
 * Options parsed from the command line for the Browser4 MCP server.
 */
internal data class McpServerOptions(
    val transport: String = "stdio",
    val port: Int = McpHttpServer.DEFAULT_MCP_HTTP_PORT,
    val headless: Boolean = true,
)

/**
 * Parses the command line options of the Browser4 MCP server.
 *
 * Headless is the default: the MCP server runs in the background for AI
 * clients, and [ai.platon.pulsar.api.model.BrowserSettings] falls back to
 * GUI when the configuration does not carry `browser.display.mode` (this
 * standalone runner has no Spring-wired server configuration, so the shipped
 * HEADLESS default would not reach the launch otherwise). `--headed` opts
 * into a visible window, mirroring the CLI's `open --headed`.
 *
 * @param args the raw command line arguments
 * @return the parsed options, or `null` when `--help`/`-h` or an unknown
 *         option was encountered (the caller prints the usage and exits)
 */
internal fun parseMcpServerOptions(args: Array<String>): McpServerOptions? {
    var transport = "stdio"
    var port = McpHttpServer.DEFAULT_MCP_HTTP_PORT
    var headless = true

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
            "--headed" -> headless = false
            "--help", "-h" -> return null
            else -> {
                System.err.println("Unknown option: ${args[i]}")
                return null
            }
        }
        i++
    }

    return McpServerOptions(transport, port, headless)
}

fun runBrowser4MCPServer(args: Array<String> = emptyArray()) {
    val logger = getLogger("Browser4MCPServerRunner")

    val options = parseMcpServerOptions(args)
    if (options == null) {
        printUsage()
        return
    }
    val transport = options.transport
    val port = options.port
    val headless = options.headless

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
        |  --headless           Run Chrome in headless mode (default)
        |  --headed             Run Chrome in headed (GUI) mode — a visible window
        |  --help, -h           Print this help
        |
        |Examples:
        |  java -jar Browser4.jar --app mcp
        |  java -jar Browser4.jar --app mcp --transport http --port 8088
        |  java -jar Browser4.jar --app mcp --headless
        |  java -jar Browser4.jar --app mcp --headed
        """.trimMargin()
    )
}

fun main(args: Array<String>) = runBrowser4MCPServer(args)
