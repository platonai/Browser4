package ai.platon.pulsar.agentic.mcp

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.assertNull

/**
 * Tests for MCP data types.
 */
class MCPTypesTest {

    @Test
    fun `test MCPToolDefinition creation`() {
        val tool = MCPToolDefinition(
            name = "driver.click",
            description = "Click on an element",
            inputSchema = MCPInputSchema(
                type = "object",
                properties = mapOf(
                    "selector" to MCPPropertySchema(
                        type = "string",
                        description = "CSS selector"
                    )
                ),
                required = listOf("selector")
            )
        )

        assertEquals("driver.click", tool.name)
        assertEquals("Click on an element", tool.description)
        assertEquals("object", tool.inputSchema.type)
        assertEquals(1, tool.inputSchema.properties.size)
        assertEquals("string", tool.inputSchema.properties["selector"]?.type)
        assertTrue(tool.inputSchema.required.contains("selector"))
    }

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
    fun `test MCPToolCall with single name`() {
        val toolCall = MCPToolCall(
            name = "click",
            arguments = emptyMap()
        )

        assertEquals("click", toolCall.domain)
        assertEquals("click", toolCall.method)
    }

    @Test
    fun `test MCPResource creation`() {
        val resource = MCPResource(
            uri = "file:///path/to/file.txt",
            name = "Sample File",
            description = "A sample text file",
            mimeType = "text/plain"
        )

        assertEquals("file:///path/to/file.txt", resource.uri)
        assertEquals("Sample File", resource.name)
        assertEquals("text/plain", resource.mimeType)
    }

    @Test
    fun `test MCPResourceContents with text`() {
        val contents = MCPResourceContents(
            uri = "file:///test.txt",
            mimeType = "text/plain",
            text = "Hello, World!"
        )

        assertEquals("Hello, World!", contents.text)
        assertNull(contents.blob)
    }

    @Test
    fun `test MCPResourceContents with blob`() {
        val contents = MCPResourceContents(
            uri = "file:///image.png",
            mimeType = "image/png",
            blob = "base64encodeddata=="
        )

        assertEquals("base64encodeddata==", contents.blob)
        assertNull(contents.text)
    }

    @Test
    fun `test MCPPrompt creation`() {
        val prompt = MCPPrompt(
            name = "summarize",
            description = "Summarize content",
            arguments = listOf(
                MCPPromptArgument(
                    name = "text",
                    description = "Text to summarize",
                    required = true
                )
            )
        )

        assertEquals("summarize", prompt.name)
        assertEquals(1, prompt.arguments.size)
        assertTrue(prompt.arguments[0].required)
    }

    @Test
    fun `test MCPToolResult with text content`() {
        val result = MCPToolResult(
            content = listOf(
                MCPToolResultContent(
                    type = "text",
                    text = "Success"
                )
            ),
            isError = false
        )

        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("Success", result.content[0].text)
        assertFalse(result.isError)
    }

    @Test
    fun `test MCPToolResult with error`() {
        val result = MCPToolResult(
            content = listOf(
                MCPToolResultContent(
                    type = "text",
                    text = "Error occurred"
                )
            ),
            isError = true
        )

        assertTrue(result.isError)
    }

    @Test
    fun `test MCPServerCapabilities`() {
        val capabilities = MCPServerCapabilities(
            tools = MCPToolCapabilities(listChanged = true),
            resources = MCPResourceCapabilities(
                listChanged = true,
                subscribe = false
            ),
            prompts = MCPPromptCapabilities(listChanged = true)
        )

        assertTrue(capabilities.tools?.listChanged == true)
        assertTrue(capabilities.resources?.listChanged == true)
        assertFalse(capabilities.resources?.subscribe == true)
    }

    @Test
    fun `test MCPPropertySchema with enum`() {
        val schema = MCPPropertySchema(
            type = "string",
            description = "Modifier key",
            enumValues = listOf("Ctrl", "Shift", "Alt", "Meta")
        )

        assertEquals(4, schema.enumValues?.size)
        assertTrue(schema.enumValues?.contains("Ctrl") == true)
    }

    @Test
    fun `test MCPPropertySchema with default value`() {
        val schema = MCPPropertySchema(
            type = "integer",
            description = "Timeout in milliseconds",
            default = 3000
        )

        assertEquals(3000, schema.default)
    }

    @Test
    fun `test nested MCPPropertySchema for array`() {
        val schema = MCPPropertySchema(
            type = "array",
            description = "List of selectors",
            items = MCPPropertySchema(
                type = "string",
                description = "CSS selector"
            )
        )

        assertEquals("array", schema.type)
        assertEquals("string", schema.items?.type)
    }

    @Test
    fun `test nested MCPPropertySchema for object`() {
        val schema = MCPPropertySchema(
            type = "object",
            description = "Options object",
            properties = mapOf(
                "timeout" to MCPPropertySchema(type = "integer"),
                "visible" to MCPPropertySchema(type = "boolean")
            ),
            required = listOf("timeout")
        )

        assertEquals("object", schema.type)
        assertEquals(2, schema.properties?.size)
        assertEquals(1, schema.required?.size)
    }
}
