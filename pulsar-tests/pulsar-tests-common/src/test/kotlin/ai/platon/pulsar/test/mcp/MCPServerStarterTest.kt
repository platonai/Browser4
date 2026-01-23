package ai.platon.pulsar.test.mcp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for MCPServerStarter and MCPServerLauncher.
 * 
 * This test verifies that the MCPServerStarter can automatically start
 * and wait for the MCP server to be ready, following the same pattern
 * as DemoSiteStarter.
 */
@Tag("mcp")
class MCPServerStarterTest {

    private val starter = MCPServerStarter()

    @AfterEach
    fun tearDown() {
        starter.close()
    }

    @Test
    fun `MCPServerStarter can start and wait for MCP server`() {
        // Use the starter to ensure server is running
        val mcpUrl = "http://localhost:18088/mcp"
        
        starter.start(mcpUrl)
        
        // Verify the launcher shows server is running
        assertTrue(MCPServerLauncher.isRunning(), "MCP server should be running after start()")
        
        // Verify we can get the port
        val port = MCPServerLauncher.port()
        assertNotNull(port, "Port should be available")
        assertTrue(port > 0, "Port should be positive")
        
        // Verify base URL is available
        val baseUrl = MCPServerLauncher.baseUrl()
        assertNotNull(baseUrl, "Base URL should be available")
        assertTrue(baseUrl.contains("localhost"), "Base URL should contain localhost")
    }

    @Test
    fun `MCPServerStarter can wait for already running server`() {
        // Start server first
        val mcpUrl = "http://localhost:18088/mcp"
        starter.start(mcpUrl)
        
        // Create a second starter and verify it can detect the running server
        val starter2 = MCPServerStarter()
        val options = MCPServerStarter.Options(verbose = false)
        val isReady = starter2.wait(mcpUrl, options)
        
        assertTrue(isReady, "Should detect already running server")
        
        starter2.close()
    }

    @Test
    fun `MCPServerLauncher can be stopped and restarted`() {
        // Start server
        MCPServerLauncher.start(port = 18088)
        assertTrue(MCPServerLauncher.isRunning(), "Server should be running")
        
        // Stop server
        MCPServerLauncher.stop()
        assertTrue(!MCPServerLauncher.isRunning(), "Server should be stopped")
        
        // Restart server
        MCPServerLauncher.start(port = 18088)
        assertTrue(MCPServerLauncher.isRunning(), "Server should be running after restart")
    }
}
