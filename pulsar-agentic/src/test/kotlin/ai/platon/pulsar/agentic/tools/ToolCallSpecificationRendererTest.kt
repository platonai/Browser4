package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.skills.*
import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class ToolCallSpecificationRendererTest {

    private lateinit var registry: CustomToolRegistry
    private lateinit var skillRegistry: SkillRegistry
    private lateinit var skillContext: SkillContext

    @BeforeEach
    fun setUp() = runBlocking {
        registry = CustomToolRegistry.instance
        registry.clear()
        
        skillRegistry = SkillRegistry.instance
        skillContext = SkillContext(sessionId = "test-session")
        skillRegistry.clear(skillContext)
    }

    @AfterEach
    fun tearDown() = runBlocking {
        registry.clear()
        skillRegistry.clear(skillContext)
    }

    @Test
    fun `render should keep ToolSpecification verbatim and append custom tools`() {
        val executor = DbToolExecutor()
        val specs = listOf(
            ToolSpec(
                domain = "db",
                method = "query",
                arguments = listOf(ToolSpec.Arg("sql", "String")),
                returnType = "String",
                description = "Run a SQL query"
            )
        )

        registry.register(executor, specs)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        // Built-in specification should be present verbatim
        assertTrue(rendered.contains("// domain: driver"), rendered)
        assertTrue(rendered.contains("driver.reload()"), rendered)

        // Custom section appended
        assertTrue(rendered.contains("// CustomTool"), rendered)
        assertTrue(rendered.contains("db.query("), rendered)
        assertTrue(rendered.contains("sql:"), rendered)
    }

    @Test
    fun `render should include MCP service tools when registered`() {
        // Simulate MCP tool registration
        val mcpExecutor = MockMCPToolExecutor()
        val mcpSpecs = listOf(
            ToolSpec(
                domain = "mcp.test-server",
                method = "fetch",
                arguments = listOf(
                    ToolSpec.Arg("url", "String"),
                    ToolSpec.Arg("headers", "Map<String, String>", "emptyMap()")
                ),
                returnType = "String",
                description = "Fetch content from a URL"
            ),
            ToolSpec(
                domain = "mcp.test-server",
                method = "store",
                arguments = listOf(
                    ToolSpec.Arg("key", "String"),
                    ToolSpec.Arg("value", "String")
                ),
                returnType = "Boolean",
                description = "Store a key-value pair"
            )
        )

        registry.register(mcpExecutor, mcpSpecs)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        // Built-in tools should be present
        assertTrue(rendered.contains("// domain: driver"), "Should contain built-in driver domain")
        
        // MCP custom tools should be present
        assertTrue(rendered.contains("// CustomTool"), "Should contain CustomTool section")
        assertTrue(rendered.contains("mcp.test-server.fetch("), "Should contain MCP fetch method")
        assertTrue(rendered.contains("url: String"), "Should contain MCP fetch url parameter")
        assertTrue(rendered.contains("mcp.test-server.store("), "Should contain MCP store method")
        assertTrue(rendered.contains("key: String"), "Should contain MCP store key parameter")
    }

    @Test
    fun `render should include SKILL tools when registered`() = runBlocking {
        // Register a skill with toolSpec
        val skill = TestSkillWithToolSpec()
        skillRegistry.register(skill, skillContext)

        // Register SkillToolExecutor which exposes skill tools
        val skillExecutor = SkillToolExecutor(skillRegistry)
        registry.register(skillExecutor)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        // Built-in tools should be present
        assertTrue(rendered.contains("// domain: driver"), "Should contain built-in driver domain")
        
        // Skill tools should be present
        assertTrue(rendered.contains("// CustomTool"), "Should contain CustomTool section")
        assertTrue(rendered.contains("skill.run("), "Should contain skill.run method")
        assertTrue(rendered.contains("id: String"), "Should contain skill.run id parameter")
        
        // Skill-specific toolSpec should be present
        assertTrue(rendered.contains("skill.test."), "Should contain skill-specific domain")
        assertTrue(rendered.contains("process("), "Should contain skill-specific method")
    }

    @Test
    fun `render should include both MCP and SKILL tools when both are registered`() = runBlocking {
        // Register MCP tools
        val mcpExecutor = MockMCPToolExecutor()
        val mcpSpecs = listOf(
            ToolSpec(
                domain = "mcp.api-server",
                method = "call",
                arguments = listOf(ToolSpec.Arg("endpoint", "String")),
                returnType = "Any?",
                description = "Call an API endpoint"
            )
        )
        registry.register(mcpExecutor, mcpSpecs)

        // Register SKILL
        val skill = TestSkillWithToolSpec()
        skillRegistry.register(skill, skillContext)
        val skillExecutor = SkillToolExecutor(skillRegistry)
        registry.register(skillExecutor)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        // Built-in tools should be present
        assertTrue(rendered.contains("// domain: driver"), "Should contain built-in driver domain")
        
        // Both MCP and SKILL tools should be present
        assertTrue(rendered.contains("// CustomTool"), "Should contain CustomTool section")
        
        // MCP tools
        assertTrue(rendered.contains("mcp.api-server.call("), "Should contain MCP call method")
        assertTrue(rendered.contains("endpoint: String"), "Should contain MCP endpoint parameter")
        
        // Skill tools
        assertTrue(rendered.contains("skill.run("), "Should contain skill.run method")
        assertTrue(rendered.contains("skill.test.process("), "Should contain skill-specific method")
    }

    @Test
    fun `render should properly format tool specifications with various parameter types`() {
        val executor = ComplexToolExecutor()
        val specs = listOf(
            ToolSpec(
                domain = "complex",
                method = "process",
                arguments = listOf(
                    ToolSpec.Arg("requiredParam", "String"),
                    ToolSpec.Arg("optionalParam", "Int", "42"),
                    ToolSpec.Arg("listParam", "List<String>", "emptyList()"),
                    ToolSpec.Arg("mapParam", "Map<String, Any?>", "null")
                ),
                returnType = "Result",
                description = "Process complex data"
            )
        )

        registry.register(executor, specs)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        // Verify the rendered format
        assertTrue(rendered.contains("complex.process("), "Should contain method signature")
        assertTrue(rendered.contains("requiredParam: String"), "Should contain required parameter")
        assertTrue(rendered.contains("optionalParam: Int = 42"), "Should contain optional parameter with default")
        assertTrue(rendered.contains("listParam: List<String> = emptyList()"), "Should contain list parameter")
        assertTrue(rendered.contains("mapParam: Map<String, Any?> = null"), "Should contain map parameter")
        assertTrue(rendered.contains("): Result"), "Should contain return type")
    }

    @Test
    fun `render without custom domains should only include built-in tools`() {
        // Register some custom tools
        val executor = DbToolExecutor()
        val specs = listOf(
            ToolSpec(
                domain = "db",
                method = "query",
                arguments = listOf(ToolSpec.Arg("sql", "String")),
                returnType = "String"
            )
        )
        registry.register(executor, specs)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = false)

        // Built-in tools should be present
        assertTrue(rendered.contains("// domain: driver"), "Should contain built-in driver domain")
        
        // Custom tools should NOT be present
        assertFalse(rendered.contains("// CustomTool"), "Should NOT contain CustomTool section")
        assertFalse(rendered.contains("db.query"), "Should NOT contain custom db.query method")
    }

    @Test
    fun `render should filter custom domains based on filter function`() {
        // Register multiple custom tools
        registry.register(DbToolExecutor(), listOf(
            ToolSpec(domain = "db", method = "query", arguments = emptyList(), returnType = "String")
        ))
        registry.register(MockMCPToolExecutor(), listOf(
            ToolSpec(domain = "mcp.test", method = "fetch", arguments = emptyList(), returnType = "String")
        ))

        // Filter to include only MCP domains
        val rendered = ToolCallSpecificationRenderer.render(
            includeCustomDomains = true,
            customDomainFilter = { it.startsWith("mcp.") }
        )

        // Built-in tools should be present
        assertTrue(rendered.contains("// domain: driver"), "Should contain built-in driver domain")
        
        // Only MCP tools should be in custom section
        assertTrue(rendered.contains("// CustomTool"), "Should contain CustomTool section")
        assertTrue(rendered.contains("mcp.test.fetch"), "Should contain MCP fetch method")
        assertFalse(rendered.contains("db.query"), "Should NOT contain filtered db.query method")
    }

    // Helper test classes

    private class DbToolExecutor : AbstractToolExecutor() {
        override val domain: String = "db"
        override val targetClass: KClass<*> = Any::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            target: Any
        ): Any? {
            return null
        }
    }

    private class MockMCPToolExecutor : AbstractToolExecutor() {
        override val domain: String = "mcp.test-server"
        override val targetClass: KClass<*> = Any::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            target: Any
        ): Any? {
            return null
        }
    }

    private class ComplexToolExecutor : AbstractToolExecutor() {
        override val domain: String = "complex"
        override val targetClass: KClass<*> = Any::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            target: Any
        ): Any? {
            return null
        }
    }

    private class TestSkillWithToolSpec : AbstractSkill() {
        override val metadata = SkillMetadata(
            id = "test-skill",
            name = "Test Skill",
            version = "1.0.0",
            description = "A test skill for demonstration"
        )

        override val toolSpec = listOf(
            ToolSpec(
                domain = "skill.test",
                method = "process",
                arguments = listOf(
                    ToolSpec.Arg("input", "String"),
                    ToolSpec.Arg("options", "Map<String, Any?>", "emptyMap()")
                ),
                returnType = "Any?",
                description = "Process input data with options"
            )
        )

        override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
            return SkillResult.success(data = "Test result")
        }
    }
}
