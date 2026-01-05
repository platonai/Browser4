package ai.platon.pulsar.agentic.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for MCPProtocolHandler.
 */
class MCPProtocolHandlerTest {

    private lateinit var handler: MCPProtocolHandler

    @BeforeEach
    fun setup() {
        MCPToolRegistry.instance.reset()
        handler = MCPProtocolHandler()
    }

    @Test
    fun `test handle initialize request`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {
                        "name": "TestClient",
                        "version": "1.0"
                    }
                }
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"result\""))
        assertTrue(responseJson.contains("\"protocolVersion\""))
        assertTrue(responseJson.contains("\"capabilities\""))
        assertTrue(responseJson.contains("\"serverInfo\""))
    }

    @Test
    fun `test handle tools-list request`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/list"
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"result\""))
        assertTrue(responseJson.contains("\"tools\""))
        assertTrue(responseJson.contains("driver.click"))
    }

    @Test
    fun `test handle tools-call request`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {
                    "name": "driver.click",
                    "arguments": {
                        "selector": "#button"
                    }
                }
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"result\""))
        assertTrue(responseJson.contains("\"content\""))
    }

    @Test
    fun `test handle resources-list request`() = runBlocking {
        // Register a test resource
        MCPToolRegistry.instance.registerResource(
            MCPResource(uri = "test://resource", name = "Test")
        )

        val request = """
            {
                "jsonrpc": "2.0",
                "id": 4,
                "method": "resources/list"
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"result\""))
        assertTrue(responseJson.contains("\"resources\""))
    }

    @Test
    fun `test handle prompts-list request`() = runBlocking {
        // Register a test prompt
        MCPToolRegistry.instance.registerPrompt(
            MCPPrompt(name = "test-prompt", description = "Test")
        )

        val request = """
            {
                "jsonrpc": "2.0",
                "id": 5,
                "method": "prompts/list"
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"result\""))
        assertTrue(responseJson.contains("\"prompts\""))
    }

    @Test
    fun `test handle ping request`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 6,
                "method": "ping"
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"result\""))
        assertFalse(responseJson.contains("\"error\""))
    }

    @Test
    fun `test handle method not found`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 7,
                "method": "unknown/method"
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"error\""))
        assertTrue(responseJson.contains("-32601"))  // Method not found code
    }

    @Test
    fun `test handle invalid JSON`() = runBlocking {
        val invalidJson = "{ this is not valid JSON }"

        val responseJson = handler.handleRequest(invalidJson)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"error\""))
        assertTrue(responseJson.contains("-32700"))  // Parse error code
    }

    @Test
    fun `test handle tools-call with unknown tool`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 8,
                "method": "tools/call",
                "params": {
                    "name": "nonexistent.tool",
                    "arguments": {}
                }
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"error\""))
        assertTrue(responseJson.contains("Tool not found"))
    }

    @Test
    fun `test handle initialized notification returns null`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "method": "notifications/initialized"
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        // Notifications don't get responses
        assertNull(responseJson)
    }

    @Test
    fun `test create notification`() {
        val notification = handler.createNotification(
            method = "notifications/tools/list_changed"
        )

        assertTrue(notification.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(notification.contains("\"method\":\"notifications/tools/list_changed\""))
        assertFalse(notification.contains("\"id\""))
    }

    @Test
    fun `test create notification with params`() {
        val notification = handler.createNotification(
            method = "notifications/message",
            params = mapOf("level" to "info", "data" to "Test message")
        )

        assertTrue(notification.contains("\"method\":\"notifications/message\""))
        assertTrue(notification.contains("\"params\""))
    }

    @Test
    fun `test protocol version`() {
        assertEquals("2024-11-05", handler.protocolVersion)
    }

    @Test
    fun `test is initialized initially false`() {
        assertFalse(handler.isInitialized())
    }

    @Test
    fun `test handle resources-read with provider`() = runBlocking {
        val registry = MCPToolRegistry.instance
        registry.registerResource(
            MCPResource(uri = "test://read", name = "Readable")
        ) { uri ->
            MCPResourceContents(uri = uri, text = "Content here")
        }

        val request = """
            {
                "jsonrpc": "2.0",
                "id": 9,
                "method": "resources/read",
                "params": {
                    "uri": "test://read"
                }
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"contents\""))
        assertTrue(responseJson.contains("Content here"))
    }

    @Test
    fun `test handle prompts-get`() = runBlocking {
        MCPToolRegistry.instance.registerPrompt(
            MCPPrompt(
                name = "get-test",
                description = "Get test prompt"
            )
        )

        val request = """
            {
                "jsonrpc": "2.0",
                "id": 10,
                "method": "prompts/get",
                "params": {
                    "name": "get-test"
                }
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"description\""))
        assertTrue(responseJson.contains("\"messages\""))
    }

    @Test
    fun `test handle resources-templates-list`() = runBlocking {
        val request = """
            {
                "jsonrpc": "2.0",
                "id": 11,
                "method": "resources/templates/list"
            }
        """.trimIndent()

        val responseJson = handler.handleRequest(request)

        assertNotNull(responseJson)
        assertTrue(responseJson!!.contains("\"resourceTemplates\""))
    }
}
