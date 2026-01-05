package ai.platon.pulsar.agentic.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.assertNull

/**
 * Tests for MCPToolRegistry.
 */
class MCPToolRegistryTest {

    private lateinit var registry: MCPToolRegistry

    @BeforeEach
    fun setup() {
        registry = MCPToolRegistry.instance
        registry.reset()  // Reset to initial state with built-in tools
    }

    @Test
    fun `test registry has built-in tools`() {
        val tools = registry.getAllTools()

        assertTrue(tools.isNotEmpty())
        assertTrue(registry.hasTool("driver.click"))
        assertTrue(registry.hasTool("driver.navigateTo"))
    }

    @Test
    fun `test register custom tool`() {
        val tool = MCPToolDefinition(
            name = "custom.test",
            description = "Custom test tool",
            inputSchema = MCPInputSchema()
        )

        registry.registerTool(tool)

        assertTrue(registry.hasTool("custom.test"))
        assertEquals(tool, registry.getTool("custom.test"))
    }

    @Test
    fun `test register duplicate tool throws exception`() {
        val tool = MCPToolDefinition(
            name = "custom.duplicate",
            description = "First",
            inputSchema = MCPInputSchema()
        )

        registry.registerTool(tool)

        val duplicate = MCPToolDefinition(
            name = "custom.duplicate",
            description = "Second",
            inputSchema = MCPInputSchema()
        )

        val exception = assertThrows<IllegalArgumentException> {
            registry.registerTool(duplicate)
        }
        assertTrue(exception.message!!.contains("already registered"))
    }

    @Test
    fun `test unregister tool`() {
        val tool = MCPToolDefinition(
            name = "custom.toremove",
            description = "To be removed",
            inputSchema = MCPInputSchema()
        )

        registry.registerTool(tool)
        assertTrue(registry.hasTool("custom.toremove"))

        val removed = registry.unregisterTool("custom.toremove")

        assertTrue(removed)
        assertFalse(registry.hasTool("custom.toremove"))
    }

    @Test
    fun `test unregister non-existent tool returns false`() {
        val removed = registry.unregisterTool("nonexistent.tool")
        assertFalse(removed)
    }

    @Test
    fun `test get tools by domain`() {
        val driverTools = registry.getToolsByDomain("driver")

        assertTrue(driverTools.isNotEmpty())
        assertTrue(driverTools.all { it.name.startsWith("driver.") })
    }

    @Test
    fun `test register resource`() {
        val resource = MCPResource(
            uri = "file:///test.txt",
            name = "Test File",
            mimeType = "text/plain"
        )

        registry.registerResource(resource)

        val retrieved = registry.getResource("file:///test.txt")
        assertEquals(resource, retrieved)
    }

    @Test
    fun `test register resource with provider`() = runBlocking {
        val resource = MCPResource(
            uri = "memory://data",
            name = "Memory Data"
        )

        val contents = MCPResourceContents(
            uri = "memory://data",
            text = "Hello from memory"
        )

        registry.registerResource(resource) { uri ->
            contents
        }

        val result = registry.readResource("memory://data")
        assertEquals("Hello from memory", result?.text)
    }

    @Test
    fun `test unregister resource`() {
        val resource = MCPResource(
            uri = "file:///toremove.txt",
            name = "To Remove"
        )

        registry.registerResource(resource)
        assertTrue(registry.getResource("file:///toremove.txt") != null)

        val removed = registry.unregisterResource("file:///toremove.txt")

        assertTrue(removed)
        assertNull(registry.getResource("file:///toremove.txt"))
    }

    @Test
    fun `test get all resources`() {
        val resource1 = MCPResource(uri = "test://1", name = "One")
        val resource2 = MCPResource(uri = "test://2", name = "Two")

        registry.registerResource(resource1)
        registry.registerResource(resource2)

        val resources = registry.getAllResources()

        assertTrue(resources.any { it.uri == "test://1" })
        assertTrue(resources.any { it.uri == "test://2" })
    }

    @Test
    fun `test register prompt`() {
        val prompt = MCPPrompt(
            name = "test-prompt",
            description = "A test prompt"
        )

        registry.registerPrompt(prompt)

        val retrieved = registry.getPrompt("test-prompt")
        assertEquals(prompt, retrieved)
    }

    @Test
    fun `test unregister prompt`() {
        val prompt = MCPPrompt(
            name = "to-remove-prompt",
            description = "Will be removed"
        )

        registry.registerPrompt(prompt)
        assertTrue(registry.getPrompt("to-remove-prompt") != null)

        val removed = registry.unregisterPrompt("to-remove-prompt")

        assertTrue(removed)
        assertNull(registry.getPrompt("to-remove-prompt"))
    }

    @Test
    fun `test get all prompts`() {
        val prompt1 = MCPPrompt(name = "prompt1", description = "First")
        val prompt2 = MCPPrompt(name = "prompt2", description = "Second")

        registry.registerPrompt(prompt1)
        registry.registerPrompt(prompt2)

        val prompts = registry.getAllPrompts()

        assertTrue(prompts.any { it.name == "prompt1" })
        assertTrue(prompts.any { it.name == "prompt2" })
    }

    @Test
    fun `test clear registry`() {
        val tool = MCPToolDefinition(
            name = "custom.clear",
            description = "Clear test",
            inputSchema = MCPInputSchema()
        )
        val resource = MCPResource(uri = "test://clear", name = "Clear")
        val prompt = MCPPrompt(name = "clear-prompt")

        registry.registerTool(tool)
        registry.registerResource(resource)
        registry.registerPrompt(prompt)

        registry.clear()

        assertFalse(registry.hasTool("custom.clear"))
        assertNull(registry.getResource("test://clear"))
        assertNull(registry.getPrompt("clear-prompt"))
        assertTrue(registry.getAllTools().isEmpty())
    }

    @Test
    fun `test reset registry`() {
        val tool = MCPToolDefinition(
            name = "custom.reset",
            description = "Reset test",
            inputSchema = MCPInputSchema()
        )

        registry.registerTool(tool)

        registry.reset()

        // Custom tool should be gone
        assertFalse(registry.hasTool("custom.reset"))
        // Built-in tools should be back
        assertTrue(registry.hasTool("driver.click"))
    }

    @Test
    fun `test get stats`() {
        val stats = registry.getStats()

        assertTrue(stats.containsKey("tools"))
        assertTrue(stats.containsKey("resources"))
        assertTrue(stats.containsKey("prompts"))
        assertTrue(stats["tools"]!! > 0)  // Should have built-in tools
    }

    @Test
    fun `test register tool with blank name fails`() {
        val tool = MCPToolDefinition(
            name = "",
            description = "Blank name",
            inputSchema = MCPInputSchema()
        )

        assertThrows<IllegalArgumentException> {
            registry.registerTool(tool)
        }
    }

    @Test
    fun `test register resource with blank uri fails`() {
        val resource = MCPResource(
            uri = "",
            name = "Blank URI"
        )

        assertThrows<IllegalArgumentException> {
            registry.registerResource(resource)
        }
    }

    @Test
    fun `test register prompt with blank name fails`() {
        val prompt = MCPPrompt(name = "")

        assertThrows<IllegalArgumentException> {
            registry.registerPrompt(prompt)
        }
    }
}
