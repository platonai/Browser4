package ai.platon.pulsar.agentic.mcp

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Tests for MCPToolRenderer.
 */
class MCPToolRendererTest {

    @Test
    fun `test render tool to JSON`() {
        val tool = MCPToolDefinition(
            name = "driver.click",
            description = "Click on an element",
            inputSchema = MCPInputSchema(
                properties = mapOf(
                    "selector" to MCPPropertySchema(type = "string", description = "CSS selector")
                ),
                required = listOf("selector")
            )
        )

        val json = MCPToolRenderer.renderTool(tool)

        assertTrue(json.contains("\"name\" : \"driver.click\""))
        assertTrue(json.contains("\"description\" : \"Click on an element\""))
        assertTrue(json.contains("\"type\" : \"object\""))
        assertTrue(json.contains("\"selector\""))
    }

    @Test
    fun `test render tool compact JSON`() {
        val tool = MCPToolDefinition(
            name = "driver.click",
            description = "Click on an element",
            inputSchema = MCPInputSchema(
                properties = mapOf(
                    "selector" to MCPPropertySchema(type = "string")
                ),
                required = listOf("selector")
            )
        )

        val json = MCPToolRenderer.renderTool(tool, pretty = false)

        assertFalse(json.contains("\n"))
        assertTrue(json.contains("\"name\":\"driver.click\""))
    }

    @Test
    fun `test render tools list`() {
        val tools = listOf(
            MCPToolDefinition(
                name = "driver.click",
                description = "Click",
                inputSchema = MCPInputSchema()
            ),
            MCPToolDefinition(
                name = "driver.type",
                description = "Type",
                inputSchema = MCPInputSchema()
            )
        )

        val json = MCPToolRenderer.renderTools(tools)

        assertTrue(json.startsWith("["))
        assertTrue(json.contains("driver.click"))
        assertTrue(json.contains("driver.type"))
    }

    @Test
    fun `test render tools list response`() {
        val tools = listOf(
            MCPToolDefinition(
                name = "test.tool",
                description = "Test",
                inputSchema = MCPInputSchema()
            )
        )

        val json = MCPToolRenderer.renderToolsListResponse(tools)

        assertTrue(json.contains("\"tools\""))
        assertTrue(json.contains("test.tool"))
    }

    @Test
    fun `test render tools list response with cursor`() {
        val tools = listOf(
            MCPToolDefinition(
                name = "test.tool",
                description = "Test",
                inputSchema = MCPInputSchema()
            )
        )

        val json = MCPToolRenderer.renderToolsListResponse(tools, cursor = "next_page")

        assertTrue(json.contains("\"nextCursor\""))
        assertTrue(json.contains("next_page"))
    }

    @Test
    fun `test create text result`() {
        val result = MCPToolRenderer.createTextResult("Success!")

        assertEquals(1, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("Success!", result.content[0].text)
        assertFalse(result.isError)
    }

    @Test
    fun `test create error result`() {
        val result = MCPToolRenderer.createErrorResult("Something went wrong")

        assertEquals(1, result.content.size)
        assertEquals("Something went wrong", result.content[0].text)
        assertTrue(result.isError)
    }

    @Test
    fun `test create image result`() {
        val result = MCPToolRenderer.createImageResult("base64data==", "image/jpeg")

        assertEquals(1, result.content.size)
        assertEquals("image", result.content[0].type)
        assertEquals("base64data==", result.content[0].data)
        assertEquals("image/jpeg", result.content[0].mimeType)
        assertFalse(result.isError)
    }

    @Test
    fun `test create mixed result`() {
        val contents = listOf(
            MCPToolResultContent(type = "text", text = "Description"),
            MCPToolResultContent(type = "image", data = "base64==", mimeType = "image/png")
        )

        val result = MCPToolRenderer.createMixedResult(contents)

        assertEquals(2, result.content.size)
        assertEquals("text", result.content[0].type)
        assertEquals("image", result.content[1].type)
    }

    @Test
    fun `test render tool result`() {
        val result = MCPToolRenderer.createTextResult("Done")
        val json = MCPToolRenderer.renderToolResult(result)

        assertTrue(json.contains("\"content\""))
        assertTrue(json.contains("\"text\""))
        assertTrue(json.contains("Done"))
    }

    @Test
    fun `test render resources list response`() {
        val resources = listOf(
            MCPResource(
                uri = "file:///test.txt",
                name = "Test File",
                mimeType = "text/plain"
            )
        )

        val json = MCPToolRenderer.renderResourcesListResponse(resources)

        assertTrue(json.contains("\"resources\""))
        assertTrue(json.contains("file:///test.txt"))
    }

    @Test
    fun `test render resource contents response`() {
        val contents = listOf(
            MCPResourceContents(
                uri = "file:///test.txt",
                mimeType = "text/plain",
                text = "Hello World"
            )
        )

        val json = MCPToolRenderer.renderResourceContentsResponse(contents)

        assertTrue(json.contains("\"contents\""))
        assertTrue(json.contains("Hello World"))
    }

    @Test
    fun `test render prompts list response`() {
        val prompts = listOf(
            MCPPrompt(
                name = "summarize",
                description = "Summarize text"
            )
        )

        val json = MCPToolRenderer.renderPromptsListResponse(prompts)

        assertTrue(json.contains("\"prompts\""))
        assertTrue(json.contains("summarize"))
    }

    @Test
    fun `test render prompt get response`() {
        val messages = listOf(
            MCPPromptMessage(
                role = "user",
                content = MCPMessageContent(type = "text", text = "Summarize this")
            )
        )

        val json = MCPToolRenderer.renderPromptGetResponse("Summary prompt", messages)

        assertTrue(json.contains("\"description\""))
        assertTrue(json.contains("\"messages\""))
        assertTrue(json.contains("Summarize this"))
    }

    @Test
    fun `test render capabilities`() {
        val capabilities = MCPServerCapabilities(
            tools = MCPToolCapabilities(listChanged = true),
            resources = MCPResourceCapabilities(listChanged = true, subscribe = false)
        )

        val json = MCPToolRenderer.renderCapabilities(capabilities)

        assertTrue(json.contains("\"tools\""))
        assertTrue(json.contains("\"resources\""))
        assertTrue(json.contains("\"listChanged\""))
    }

    @Test
    fun `test null values are excluded`() {
        val tool = MCPToolDefinition(
            name = "test",
            description = "Test",
            inputSchema = MCPInputSchema(
                properties = mapOf(
                    "arg" to MCPPropertySchema(
                        type = "string",
                        description = null,  // Should be excluded
                        default = null       // Should be excluded
                    )
                )
            )
        )

        val json = MCPToolRenderer.renderTool(tool, pretty = false)

        // null fields should not appear in JSON
        assertFalse(json.contains("\"description\":null"))
        assertFalse(json.contains("\"default\":null"))
    }
}
