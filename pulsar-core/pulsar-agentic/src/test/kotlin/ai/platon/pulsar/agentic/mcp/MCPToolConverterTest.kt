package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.agentic.ToolCallSpec
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.assertNotNull

/**
 * Tests for MCPToolConverter.
 */
class MCPToolConverterTest {

    @Test
    fun `test convert simple ToolCallSpec to MCP`() {
        val spec = ToolCallSpec(
            domain = "driver",
            method = "click",
            arguments = listOf(
                ToolCallSpec.Arg("selector", "String")
            ),
            returnType = "Unit",
            description = "Click on an element"
        )

        val mcpTool = MCPToolConverter.toMCPToolDefinition(spec)

        assertEquals("driver.click", mcpTool.name)
        assertEquals("Click on an element", mcpTool.description)
        assertEquals("object", mcpTool.inputSchema.type)
        assertEquals(1, mcpTool.inputSchema.properties.size)
        assertEquals("string", mcpTool.inputSchema.properties["selector"]?.type)
        assertTrue(mcpTool.inputSchema.required.contains("selector"))
    }

    @Test
    fun `test convert ToolCallSpec with default value`() {
        val spec = ToolCallSpec(
            domain = "driver",
            method = "waitForSelector",
            arguments = listOf(
                ToolCallSpec.Arg("selector", "String"),
                ToolCallSpec.Arg("timeoutMillis", "Long", "3000")
            ),
            returnType = "Unit"
        )

        val mcpTool = MCPToolConverter.toMCPToolDefinition(spec)

        assertEquals(2, mcpTool.inputSchema.properties.size)
        assertEquals("integer", mcpTool.inputSchema.properties["timeoutMillis"]?.type)
        assertEquals(3000L, mcpTool.inputSchema.properties["timeoutMillis"]?.default)
        // Only selector should be required (timeoutMillis has default)
        assertEquals(1, mcpTool.inputSchema.required.size)
        assertTrue(mcpTool.inputSchema.required.contains("selector"))
        assertFalse(mcpTool.inputSchema.required.contains("timeoutMillis"))
    }

    @Test
    fun `test convert ToolCallSpec with various types`() {
        val spec = ToolCallSpec(
            domain = "test",
            method = "example",
            arguments = listOf(
                ToolCallSpec.Arg("stringArg", "String"),
                ToolCallSpec.Arg("intArg", "Int"),
                ToolCallSpec.Arg("longArg", "Long"),
                ToolCallSpec.Arg("doubleArg", "Double"),
                ToolCallSpec.Arg("boolArg", "Boolean")
            )
        )

        val mcpTool = MCPToolConverter.toMCPToolDefinition(spec)

        assertEquals("string", mcpTool.inputSchema.properties["stringArg"]?.type)
        assertEquals("integer", mcpTool.inputSchema.properties["intArg"]?.type)
        assertEquals("integer", mcpTool.inputSchema.properties["longArg"]?.type)
        assertEquals("number", mcpTool.inputSchema.properties["doubleArg"]?.type)
        assertEquals("boolean", mcpTool.inputSchema.properties["boolArg"]?.type)
    }

    @Test
    fun `test convert MCP to ToolCallSpec`() {
        val mcpTool = MCPToolDefinition(
            name = "browser.switchTab",
            description = "Switch to a different tab",
            inputSchema = MCPInputSchema(
                properties = mapOf(
                    "tabId" to MCPPropertySchema(type = "string", description = "Tab ID")
                ),
                required = listOf("tabId")
            )
        )

        val spec = MCPToolConverter.toToolCallSpec(mcpTool)

        assertEquals("browser", spec.domain)
        assertEquals("switchTab", spec.method)
        assertEquals("Switch to a different tab", spec.description)
        assertEquals(1, spec.arguments.size)
        assertEquals("tabId", spec.arguments[0].name)
        assertEquals("String", spec.arguments[0].type)
    }

    @Test
    fun `test parse built-in tool specs`() {
        val specs = MCPToolConverter.parseBuiltInToolSpecs()

        assertTrue(specs.isNotEmpty())

        // Check for some expected tools
        val clickSpec = specs.find { it.domain == "driver" && it.method == "click" }
        assertNotNull(clickSpec)
        assertTrue(clickSpec!!.arguments.any { it.name == "selector" })

        val navigateSpec = specs.find { it.domain == "driver" && it.method == "navigateTo" }
        assertNotNull(navigateSpec)
        assertTrue(navigateSpec!!.arguments.any { it.name == "url" })
    }

    @Test
    fun `test get all built-in MCP tools`() {
        val mcpTools = MCPToolConverter.getAllBuiltInMCPTools()

        assertTrue(mcpTools.isNotEmpty())

        // Check for expected tools
        val clickTool = mcpTools.find { it.name == "driver.click" }
        assertNotNull(clickTool)

        val navigateTool = mcpTools.find { it.name == "driver.navigateTo" }
        assertNotNull(navigateTool)
    }

    @Test
    fun `test convert multiple specs`() {
        val specs = listOf(
            ToolCallSpec("driver", "click", listOf(ToolCallSpec.Arg("selector", "String"))),
            ToolCallSpec("driver", "type", listOf(
                ToolCallSpec.Arg("selector", "String"),
                ToolCallSpec.Arg("text", "String")
            ))
        )

        val mcpTools = MCPToolConverter.toMCPToolDefinitions(specs)

        assertEquals(2, mcpTools.size)
        assertEquals("driver.click", mcpTools[0].name)
        assertEquals("driver.type", mcpTools[1].name)
    }

    @Test
    fun `test nullable type conversion`() {
        val spec = ToolCallSpec(
            domain = "driver",
            method = "selectFirstTextOrNull",
            arguments = listOf(ToolCallSpec.Arg("selector", "String")),
            returnType = "String?"
        )

        val mcpTool = MCPToolConverter.toMCPToolDefinition(spec)

        // Should still convert properly
        assertEquals("driver.selectFirstTextOrNull", mcpTool.name)
    }

    @Test
    fun `test empty arguments`() {
        val spec = ToolCallSpec(
            domain = "driver",
            method = "reload",
            arguments = emptyList()
        )

        val mcpTool = MCPToolConverter.toMCPToolDefinition(spec)

        assertTrue(mcpTool.inputSchema.properties.isEmpty())
        assertTrue(mcpTool.inputSchema.required.isEmpty())
    }
}
