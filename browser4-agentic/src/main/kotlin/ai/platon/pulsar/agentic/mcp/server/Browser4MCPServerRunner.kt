package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.getLogger
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Starts the Browser4 MCP server using STDIO transport.
 *
 * STDIO transport is the standard integration method used by Claude Desktop,
 * Cursor, Windsurf, and other MCP-compatible AI clients that launch the server
 * as a local subprocess and communicate via stdin/stdout.
 *
 * ## Usage
 *
 * ### Direct invocation
 * ```bash
 * java -jar Browser4.jar --app=mcp
 * ```
 *
 * ### Claude Desktop configuration (`claude_desktop_config.json`)
 * ```json
 * {
 *   "mcpServers": {
 *     "browser4": {
 *       "command": "java",
 *       "args": ["-jar", "/path/to/Browser4.jar", "--app=mcp"],
 *     }
 *   }
 * }
 * ```
 *
 * ## Behaviour
 * 1. Creates an [ai.platon.pulsar.agentic.AgenticSession] and acquires a companion [BasicBrowserAgent].
 * 2. Wraps the agent's [ai.platon.pulsar.agentic.tools.AgentToolExecutor] in a [Browser4MCPServer],
 *    which discovers and registers all tools from the manager.
 * 3. Creates a [StdioServerTransport] that reads JSON-RPC messages from stdin
 *    and writes responses to stdout.
 * 4. Blocks until the MCP client closes the connection (i.e. EOF on stdin).
 * 5. Shuts down the Pulsar context and closes the browser.
 */
fun runBrowser4MCPServer(args: Array<String> = emptyArray()) {
    require(args.isEmpty()) {
        "MCP mode does not accept application arguments: ${args.joinToString(" ")}"
    }

    val logger = getLogger("Browser4MCPServerRunner")
    logger.info("Starting Browser4 MCP Server (STDIO transport)")

    val session = AgenticContexts.createSession()
    val agent = session.companionAgent as BasicBrowserAgent

    try {
        val mcpServer = Browser4MCPServer(toolManager = agent.toolExtractor)

        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered()
        )

        runBlocking {
            logger.info("Browser4 MCP Server connected — waiting for client requests")
            mcpServer.server.connect(transport)
            logger.info("Browser4 MCP Server STDIO session ended")
        }
    } finally {
        logger.info("Shutting down Browser4 MCP Server")
        AgenticContexts.shutdown()
    }
}

fun main(args: Array<String>) = runBrowser4MCPServer(args)
