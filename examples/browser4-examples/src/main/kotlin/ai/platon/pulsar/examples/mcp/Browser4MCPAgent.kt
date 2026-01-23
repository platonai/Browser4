package ai.platon.pulsar.examples.mcp

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.mcp.MCPBootstrap
import ai.platon.pulsar.agentic.mcp.MCPConfig
import ai.platon.pulsar.agentic.mcp.MCPPluginRegistry
import ai.platon.pulsar.agentic.mcp.MCPTransportType
import ai.platon.pulsar.test.mcp.MCPServerStarter

/**
 * # Browser4 MCP Agent Example
 *
 * This example demonstrates how to integrate MCP (Model Context Protocol) servers
 * with Browser4's agentic framework. MCP allows external tools and services to be
 * seamlessly integrated into the agent's capabilities.
 *
 * ## What is MCP?
 * MCP (Model Context Protocol) is a standard protocol for connecting AI agents
 * with external tools and data sources. It enables:
 * - Dynamic tool discovery
 * - Standard tool execution interface
 * - Multiple transport protocols (STDIO, SSE, WebSocket)
 * - Easy integration with third-party services
 *
 * ## Example Overview:
 * This example shows how to integrate MCP tools with the Browser4 agent,
 * demonstrating:
 *
 * 1. **MCP Server Configuration** - How to configure connections to MCP servers
 * 2. **Tool Registration** - How to register MCP tools with the agent
 * 3. **Agent Integration** - How the agent uses MCP tools in natural language tasks
 *
 * ## Prerequisites:
 * - Browser4 with pulsar-agentic module
 * - MCP server running locally or remotely
 * - LLM API key configured in application.properties
 *
 * ## Configuration Examples:
 *
 * ### STDIO Transport (Local Process)
 * For MCP servers that run as local processes:
 * ```kotlin
 * val config = MCPConfig(
 *     serverName = "my-mcp-server",
 *     transportType = MCPTransportType.STDIO,
 *     command = "node",
 *     args = listOf("path/to/server.js")
 * )
 * ```
 *
 * ### SSE Transport (HTTP Streaming)
 * For MCP servers accessible via HTTP:
 * ```kotlin
 * val config = MCPConfig(
 *     serverName = "remote-server",
 *     transportType = MCPTransportType.SSE,
 *     url = "http://localhost:8080/mcp"
 * )
 * ```
 *
 * ### WebSocket Transport
 * For MCP servers using WebSocket protocol:
 * ```kotlin
 * val config = MCPConfig(
 *     serverName = "ws-server",
 *     transportType = MCPTransportType.WEBSOCKET,
 *     url = "ws://localhost:8080/ws"
 * )
 * ```
 *
 * ## Real-World MCP Servers:
 *
 * You can connect to various MCP servers:
 * - **Weather Services** - Get weather information
 * - **Database Tools** - Query and manage databases
 * - **File System Tools** - File operations and management
 * - **API Integrations** - Connect to third-party APIs
 * - **Custom Business Logic** - Your own domain-specific tools
 *
 * @see MCPConfig Configuration for MCP server connections
 * @see MCPBootstrap Helper for registering MCP servers
 * @see MCPPluginRegistry Central registry for managing MCP servers
 */

/**
 * Example: Connecting to an MCP Server
 *
 * This example shows how to configure and register an MCP server.
 * The tools from the MCP server will be automatically discovered and
 * made available to the Browser4 agent.
 */
suspend fun connectToMCPServer() {
    println("\n=== Connecting to MCP Server ===\n")

    try {
        // Use local test MCP server for reliable, deterministic examples
        val mcpUrl = "http://localhost:18088/mcp"
        
        // Start the MCP server if not already running (similar to DemoSiteStarter pattern)
        println("Starting MCP server...")
        val starter = MCPServerStarter()
        starter.start(mcpUrl)
        
        println("✓ MCP server started successfully!")
        
        // Connect to the local test server (SSE transport)
        val config = MCPConfig(
            serverName = "test-mcp-server",
            transportType = MCPTransportType.SSE,
            url = mcpUrl,
            enabled = true
        )
        
        // Register the server with auto-registration of tools
        // autoRegisterTools = true means tools will be available to the agent
        println("Registering MCP server...")
        MCPBootstrap.register(config, autoRegisterTools = true)
        
        println("✓ MCP server registered successfully!")
        println("  The agent can now use tools from '${config.serverName}'")
        println("  Available tools: echo, add, multiply")
        
        // Clean up on shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            starter.close()
        })
        
    } catch (e: Exception) {
        println("✗ Failed to register MCP server: ${e.message}")
        println("\nCommon issues:")
        println("  - MCP server is not running or not reachable")
        println("  - Incorrect command or URL configuration")
        println("  - Missing dependencies for the transport type")
        e.printStackTrace()
    }
}

/**
 * Example: Using Multiple MCP Servers
 *
 * This example demonstrates how to connect to multiple MCP servers
 * simultaneously, combining tools from different sources.
 */
suspend fun connectToMultipleMCPServers() {
    println("\n=== Connecting to Multiple MCP Servers ===\n")

    try {
        // Start the test MCP server
        val url = "http://localhost:18182/mcp/info"
        val starter = MCPServerStarter()
        starter.start(url)
        
        // Configure multiple MCP servers
        val configs = listOf(
            MCPConfig(
                serverName = "test-mcp-server",
                transportType = MCPTransportType.SSE,
                url = "http://localhost:18182/mcp",
                enabled = true
            ),
            // Example configurations for other MCP servers (disabled by default)
            MCPConfig(
                serverName = "weather-service",
                transportType = MCPTransportType.STDIO,
                command = "python",
                args = listOf("weather_mcp_server.py"),
                enabled = false  // Set to true if you have this server
            ),
            MCPConfig(
                serverName = "database-tools",
                transportType = MCPTransportType.SSE,
                url = "http://localhost:8080/mcp",
                enabled = false  // Set to true if you have this server
            ),
            MCPConfig(
                serverName = "file-operations",
                transportType = MCPTransportType.WEBSOCKET,
                url = "ws://localhost:8081/ws",
                enabled = false  // Set to true if you have this server
            )
        )

        // Register all enabled servers at once
        println("Registering multiple MCP servers...")
        val errors = MCPBootstrap.registerAll(configs, autoRegisterTools = true)

        if (errors.isEmpty()) {
            println("✓ All enabled servers registered successfully!")
        } else {
            println("⚠ Some servers failed to register:")
            errors.forEach { (name, exception) ->
                println("  ✗ $name: ${exception.message}")
            }
        }

        // List successfully registered servers
        val registeredServers = MCPPluginRegistry.instance.getRegisteredServers()
        println("\n✓ Registered servers (${registeredServers.size}):")
        registeredServers.forEach { serverName ->
            println("  - $serverName")
        }

    } catch (e: Exception) {
        println("✗ Failed to register MCP servers: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * Example: Agent Using MCP Tools
 *
 * This example demonstrates how the Browser4 agent can use MCP tools
 * in natural language tasks. Once MCP servers are registered, their
 * tools become available to the agent automatically.
 */
suspend fun agentWithMCPTools() {
    println("\n=== Agent Using MCP Tools ===\n")

    try {
        // Use local test MCP server for reliable, deterministic examples
        val mcpUrl = "http://localhost:18088/mcp"
        
        // Start the MCP server if not already running
        println("Starting MCP server...")
        val starter = MCPServerStarter()
        starter.start(mcpUrl)
        
        // Register MCP server with the agent
        val config = MCPConfig(
            serverName = "test-mcp-server",
            transportType = MCPTransportType.SSE,
            url = mcpUrl,
            enabled = true
        )

        println("Registering MCP server with agent...")
        MCPBootstrap.register(config, autoRegisterTools = true)
        
        // Create or get the Browser4 agent session
        val session = AgenticContexts.getOrCreateSession()
        session.registerClosable(starter)
        
        val agent = session.companionAgent
        
        // The agent can now use MCP tools in natural language
        // The test MCP server provides: echo, add, multiply tools
        val task = """
            Using the MCP tools available, please:
            1. Add 123 and 456
            2. Multiply the result by 2
            3. Tell me the final answer
        """.trimIndent()

        println("\n--- Agent Task ---")
        println(task)
        println("\n--- Agent Execution ---")

        try {
            val history = agent.run(task)
            println("\n--- Agent Result ---")
            println(history.finalResult)
        } catch (e: Exception) {
            println("✗ Agent execution failed: ${e.message}")
            println("\nNote: This requires:")
            println("  1. LLM API key configured in application.properties")
            println("  2. MCP server running and accessible")
            println("\nConfigure your LLM provider:")
            println("  llm.provider=openai")
            println("  llm.apiKey=your-api-key-here")
        }
        
        // Clean up
        session.context.close()
        
    } catch (e: Exception) {
        println("✗ Error: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * Example: Manual MCP Tool Discovery
 *
 * This example shows how to manually discover and inspect tools from
 * an MCP server without running them through the agent.
 */
suspend fun inspectMCPTools() {
    println("\n=== Inspecting MCP Tools ===\n")

    try {
        // Use local test MCP server for reliable, deterministic examples
        val mcpUrl = "http://localhost:18088/mcp"
        
        // Start the MCP server if not already running
        println("Starting MCP server...")
        val starter = MCPServerStarter()
        starter.start(mcpUrl)
        
        // Register an MCP server (without auto-registering tools to the agent)
        val config = MCPConfig(
            serverName = "test-mcp-server",
            transportType = MCPTransportType.SSE,
            url = mcpUrl,
            enabled = true
        )

        println("Connecting to MCP server...")
        MCPPluginRegistry.instance.registerMCPServer(config, autoRegisterTools = false)

        // Get the tool executor for this server
        val toolExecutor = MCPPluginRegistry.instance.getToolExecutor("test-mcp-server")
        
        if (toolExecutor != null) {
            // Display available tools and their descriptions
            println("\n--- Available Tools from '${config.serverName}' ---")
            println(toolExecutor.help())

            println("\n✓ Tools discovered and ready to use")
        } else {
            println("✗ Tool executor not found for '${config.serverName}'")
        }
        
        // Clean up
        MCPPluginRegistry.instance.close()
        starter.close()
        
    } catch (e: Exception) {
        println("✗ Failed to inspect MCP tools: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * Main entry point - demonstrates MCP integration patterns
 *
 * This example shows various ways to integrate MCP with Browser4.
 *
 * ## Running the Examples:
 *
 * 1. The TestMCPServer will be automatically started
 * 2. Configure LLM API key in application.properties (for agent examples)
 * 3. Run this file
 *
 * ## Learn More:
 *
 * - MCP Documentation: /pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/mcp/README.md
 * - Example MCP Plugin: /pulsar-agentic/src/test/kotlin/ai/platon/pulsar/agentic/mcp/examples/MCPPluginExample.kt
 * - Test MCP Server: /pulsar-tests/pulsar-tests-common/src/main/kotlin/ai/platon/pulsar/test/mcp/TestMCPServer.kt
 */
suspend fun main() {
    println("╔════════════════════════════════════════════════════════════════╗")
    println("║         Browser4 MCP (Model Context Protocol) Examples        ║")
    println("╚════════════════════════════════════════════════════════════════╝")

    println("""

        This example demonstrates MCP integration with Browser4.

        MCP (Model Context Protocol) allows you to extend the Browser4 agent
        with external tools and services. Tools from MCP servers become
        available to the agent automatically through natural language.

        Examples included:
        1. Connecting to a single MCP server
        2. Connecting to multiple MCP servers
        3. Using MCP tools with the Browser4 agent (requires LLM API key)
        4. Inspecting available MCP tools
        
        The examples now use a local test MCP server that starts automatically,
        following the same pattern as FusedActs with DemoSiteStarter.
        
    """.trimIndent())

    println("\n" + "─".repeat(66))
    println("Running examples:")
    println("─".repeat(66))

    try {
        // Example 1: Connect to a single MCP server (auto-started)
        // Uncomment to run:
        connectToMCPServer()
        
        // Example 2: Connect to multiple MCP servers
        connectToMultipleMCPServers()
        
        println("\n" + "─".repeat(66))
        
        // Example 4: Inspect MCP tools manually
        inspectMCPTools()
        
        println("\n" + "─".repeat(66))
        
    } catch (e: Exception) {
        println("\n✗ Unexpected error: ${e.message}")
        e.printStackTrace()
    }

    println("\n╔════════════════════════════════════════════════════════════════╗")
    println("║                   MCP Examples Completed                       ║")
    println("╚════════════════════════════════════════════════════════════════╝")
}
