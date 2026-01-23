package ai.platon.pulsar.test.mcp

import ai.platon.pulsar.common.getLogger
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

/**
 * Spring Boot application for the MCP test server.
 *
 * This application provides a lightweight MCP server for testing and examples.
 * It automatically registers the TestMCPServer as a Spring bean, exposing
 * MCP protocol endpoints via REST controllers.
 *
 * The server can be started programmatically via [MCPServerLauncher] or
 * run directly as a standalone application.
 *
 * ## Endpoints:
 * - GET  /mcp/info - Server information
 * - POST /mcp/list_tools - List available tools
 * - POST /mcp/call_tool - Execute a tool
 *
 * ## Default Tools:
 * - echo: Returns the input message
 * - add: Adds two numbers
 * - multiply: Multiplies two numbers
 */
@SpringBootApplication
@ComponentScan(
    basePackages = ["ai.platon.pulsar.test.mcp"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["ai\\.platon\\.pulsar\\.rest\\..*"])
    ]
)
class MCPServerApplication {

    private val logger = getLogger(this)

    /**
     * Creates and configures a TestMCPServer bean.
     *
     * @return A TestMCPServer instance with default configuration.
     */
    @Bean
    fun testMCPServer(): TestMCPServer {
        logger.info("Creating TestMCPServer bean")
        return TestMCPServer(
            serverName = "test-mcp-server",
            serverVersion = "1.0.0"
        )
    }
}

/**
 * Main entry point for running the MCP server as a standalone application.
 *
 * Example:
 * ```
 * ./mvnw spring-boot:run -pl pulsar-tests/pulsar-tests-common
 * ```
 */
fun main(args: Array<String>) {
    org.springframework.boot.runApplication<MCPServerApplication>(*args)
}
