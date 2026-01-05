package ai.platon.pulsar.agentic.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for MCP Tool Executor.
 */
class MCPToolExecutorTest {

    private lateinit var executor: MCPToolExecutor

    @BeforeEach
    fun setup() {
        MCPToolRegistry.instance.reset()
        executor = MCPToolExecutor()
    }

    // ========================================================================
    // Execute Tests
    // ========================================================================

    @Test
    fun `test execute known tool without manager`() = runBlocking {
        val toolCall = MCPToolCall(
            name = "driver.click",
            arguments = mapOf("selector" to "#button")
        )

        val result = executor.execute(toolCall)

        assertFalse(result.isError)
        assertNotNull(result.content)
        assertTrue(result.content.isNotEmpty())

        val textContent = result.content.first()
        assertEquals("text", textContent.type)
        assertTrue(textContent.text?.contains("driver.click") == true)
    }

    @Test
    fun `test execute unknown tool returns error`() = runBlocking {
        val toolCall = MCPToolCall(
            name = "unknown.tool",
            arguments = emptyMap()
        )

        val result = executor.execute(toolCall)

        assertTrue(result.isError)
        assertTrue(result.content.first().text?.contains("not found") == true)
    }

    @Test
    fun `test execute tool with multiple arguments`() = runBlocking {
        val toolCall = MCPToolCall(
            name = "driver.type",
            arguments = mapOf(
                "selector" to "#input",
                "text" to "hello world"
            )
        )

        val result = executor.execute(toolCall)

        assertFalse(result.isError)
    }

    // ========================================================================
    // Validate Tests
    // ========================================================================

    @Test
    fun `test validate known tool`() {
        val toolCall = MCPToolCall(
            name = "driver.click",
            arguments = mapOf("selector" to "#button")
        )

        val result = executor.validate(toolCall)

        assertTrue(result.valid, "Expected valid=true but got error: ${result.error}")
        assertEquals(null, result.error)
    }

    @Test
    fun `test validate unknown tool`() {
        val toolCall = MCPToolCall(
            name = "unknown.tool",
            arguments = emptyMap()
        )

        val result = executor.validate(toolCall)

        assertFalse(result.valid)
        assertTrue(result.error?.contains("not found") == true)
    }

    @Test
    fun `test validate tool missing required params`() {
        // First, register a tool with required parameters
        val tool = MCPToolDefinition(
            name = "test.required",
            description = "Test tool with required params",
            inputSchema = MCPInputSchema(
                properties = mapOf(
                    "required_param" to MCPPropertySchema(type = "string"),
                    "optional_param" to MCPPropertySchema(type = "string")
                ),
                required = listOf("required_param")
            )
        )
        MCPToolRegistry.instance.registerTool(tool)

        val toolCall = MCPToolCall(
            name = "test.required",
            arguments = mapOf("optional_param" to "value")
        )

        val result = executor.validate(toolCall)

        assertFalse(result.valid)
        assertTrue(result.error?.contains("required_param") == true)
    }

    @Test
    fun `test validate tool with all required params`() {
        val tool = MCPToolDefinition(
            name = "test.allrequired",
            description = "Test tool",
            inputSchema = MCPInputSchema(
                properties = mapOf(
                    "param1" to MCPPropertySchema(type = "string"),
                    "param2" to MCPPropertySchema(type = "string")
                ),
                required = listOf("param1", "param2")
            )
        )
        MCPToolRegistry.instance.registerTool(tool)

        val toolCall = MCPToolCall(
            name = "test.allrequired",
            arguments = mapOf(
                "param1" to "value1",
                "param2" to "value2"
            )
        )

        val result = executor.validate(toolCall)

        assertTrue(result.valid)
    }

    // ========================================================================
    // Help Tests
    // ========================================================================

    @Test
    fun `test get help for known tool`() {
        val help = executor.getHelp("driver.click")

        assertNotNull(help)
        assertTrue(help.contains("driver.click"))
        assertTrue(help.contains("Parameters:"))
    }

    @Test
    fun `test get help for unknown tool`() {
        val help = executor.getHelp("unknown.tool")

        assertEquals(null, help)
    }

    @Test
    fun `test help includes parameter details`() {
        val tool = MCPToolDefinition(
            name = "test.help",
            description = "A test tool for help",
            inputSchema = MCPInputSchema(
                properties = mapOf(
                    "selector" to MCPPropertySchema(
                        type = "string",
                        description = "CSS selector",
                        default = null
                    ),
                    "modifier" to MCPPropertySchema(
                        type = "string",
                        description = "Modifier key",
                        enumValues = listOf("Ctrl", "Shift", "Alt")
                    )
                ),
                required = listOf("selector")
            )
        )
        MCPToolRegistry.instance.registerTool(tool)

        val help = executor.getHelp("test.help")

        assertNotNull(help)
        assertTrue(help.contains("test.help"))
        assertTrue(help.contains("A test tool for help"))
        assertTrue(help.contains("selector"))
        assertTrue(help.contains("(required)"))
        assertTrue(help.contains("CSS selector"))
        assertTrue(help.contains("modifier"))
        assertTrue(help.contains("Allowed values"))
    }

    // ========================================================================
    // MCP Tool Call Parsing Tests
    // ========================================================================

    @Test
    fun `test MCPToolCall domain and method extraction`() {
        val toolCall = MCPToolCall(
            name = "driver.click",
            arguments = mapOf("selector" to "#button")
        )

        assertEquals("driver", toolCall.domain)
        assertEquals("click", toolCall.method)
    }

    @Test
    fun `test MCPToolCall with nested domain`() {
        val toolCall = MCPToolCall(
            name = "browser.tabs.switch",
            arguments = emptyMap()
        )

        // With nested domain, first part is domain, rest is method
        assertEquals("browser", toolCall.domain)
        assertEquals("tabs.switch", toolCall.method)
    }
}
