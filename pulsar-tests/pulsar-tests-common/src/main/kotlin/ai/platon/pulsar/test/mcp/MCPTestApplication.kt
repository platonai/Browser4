package ai.platon.pulsar.test.mcp

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

/**
 * Spring Boot test application for MCP server.
 *
 * This application automatically starts the TestMCPServer as a Spring bean,
 * making it available for testing and examples.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = ["ai.platon.pulsar.test.mcp"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["ai\\.platon\\.pulsar\\.rest\\..*"])
    ]
)
class MCPTestApplication {

    /**
     * Creates and configures a TestMCPServer bean.
     *
     * @return A TestMCPServer instance with default configuration.
     */
    @Bean
    fun testMCPServer(): TestMCPServer {
        return TestMCPServer(
            serverName = "test-mcp-server",
            serverVersion = "1.0.0"
        )
    }
}

fun main() {
    runApplication<MCPTestApplication> {
        setAdditionalProfiles("test")
    }
}
