package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.skills.*
import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer
import ai.platon.pulsar.agentic.tools.specs.ToolSpecFormat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
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
    @DisplayName("render should keep ToolSpecification verbatim and append custom tools")
    fun renderShouldKeepToolSpecificationVerbatimAndAppendCustomTools() {
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
        assertTrue(rendered.contains("// domain: tab"), rendered)
        assertTrue(rendered.contains("tab.reload()"), rendered)

        // Custom section appended
        assertTrue(rendered.contains("// CustomTool"), rendered)
        assertTrue(rendered.contains("db.query("), rendered)
        assertTrue(rendered.contains("sql:"), rendered)
    }

    @Test
    @DisplayName("render should include registered custom service tools")
    fun renderShouldIncludeRegisteredCustomServiceTools() {
        val serviceExecutor = MockServiceToolExecutor()
        val serviceSpecs = listOf(
            ToolSpec(
                domain = "service.test-server",
                method = "fetch",
                arguments = listOf(
                    ToolSpec.Arg("url", "String"),
                    ToolSpec.Arg("headers", "Map<String, String>", "emptyMap()")
                ),
                returnType = "String",
                description = "Fetch content from a URL"
            ),
            ToolSpec(
                domain = "service.test-server",
                method = "store",
                arguments = listOf(
                    ToolSpec.Arg("key", "String"),
                    ToolSpec.Arg("value", "String")
                ),
                returnType = "Boolean",
                description = "Store a key-value pair"
            )
        )

        registry.register(serviceExecutor, serviceSpecs)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        assertTrue(rendered.contains("// domain: tab"), "Should contain built-in tab domain")
        assertTrue(rendered.contains("// CustomTool"), "Should contain CustomTool section")
        assertTrue(rendered.contains("service.test-server.fetch("), "Should contain fetch method")
        assertTrue(rendered.contains("url: String"), "Should contain fetch url parameter")
        assertTrue(rendered.contains("service.test-server.store("), "Should contain store method")
        assertTrue(rendered.contains("key: String"), "Should contain store key parameter")
    }

    @Test
    @DisplayName("render should include SKILL tools when registered")
    fun renderShouldIncludeSkillToolsWhenRegistered() = runBlocking {
        // Register a skill with toolSpec
        val skill = TestSkillWithToolSpec()
        skillRegistry.register(skill, skillContext)

        // Register SkillToolExecutor which exposes skill tools
        val skillExecutor = SkillToolExecutor(skillRegistry)
        registry.register(skillExecutor)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        // Built-in tools should be present
        assertTrue(rendered.contains("// domain: tab"), "Should contain built-in tab domain")

        // Skill tools should be present
        assertTrue(rendered.contains("// CustomTool"), "Should contain CustomTool section")
        assertTrue(rendered.contains("skill.run("), "Should contain skill.run method")
        assertTrue(rendered.contains("id: String"), "Should contain skill.run id parameter")

        // Skill-specific toolSpec should be present
        assertTrue(rendered.contains("skill.test."), "Should contain skill-specific domain")
        assertTrue(rendered.contains("process("), "Should contain skill-specific method")
    }

    @Test
    @DisplayName("render should include both custom service and SKILL tools when both are registered")
    fun renderShouldIncludeBothCustomServiceAndSkillToolsWhenBothAreRegistered() = runBlocking {
        val serviceExecutor = MockServiceToolExecutor()
        val serviceSpecs = listOf(
            ToolSpec(
                domain = "service.api-server",
                method = "call",
                arguments = listOf(ToolSpec.Arg("endpoint", "String")),
                returnType = "Any?",
                description = "Call an API endpoint"
            )
        )
        registry.register(serviceExecutor, serviceSpecs)

        // Register SKILL
        val skill = TestSkillWithToolSpec()
        skillRegistry.register(skill, skillContext)
        val skillExecutor = SkillToolExecutor(skillRegistry)
        registry.register(skillExecutor)

        val rendered = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        // Built-in tools should be present
        assertTrue(rendered.contains("// domain: tab"), "Should contain built-in tab domain")

        // Both custom service and SKILL tools should be present
        assertTrue(rendered.contains("// CustomTool"), "Should contain CustomTool section")

        // Custom service tools
        assertTrue(rendered.contains("service.api-server.call("), "Should contain service call method")
        assertTrue(rendered.contains("endpoint: String"), "Should contain service endpoint parameter")

        // Skill tools
        assertTrue(rendered.contains("skill.run("), "Should contain skill.run method")
        assertTrue(rendered.contains("skill.test.process("), "Should contain skill-specific method")
    }

    @Test
    @DisplayName("render should properly format tool specifications with various parameter types")
    fun renderShouldProperlyFormatToolSpecificationsWithVariousParameterTypes() {
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
    @DisplayName("render without custom domains should only include built-in tools")
    fun renderWithoutCustomDomainsShouldOnlyIncludeBuiltInTools() {
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
        assertTrue(rendered.contains("// domain: tab"), "Should contain built-in tab domain")

        // Custom tools should NOT be present
        assertFalse(rendered.contains("// CustomTool"), "Should NOT contain CustomTool section")
        assertFalse(rendered.contains("db.query"), "Should NOT contain custom db.query method")
    }

    @Test
    @DisplayName("render should filter custom domains based on filter function")
    fun renderShouldFilterCustomDomainsBasedOnFilterFunction() {
        // Register multiple custom tools
        registry.register(
            DbToolExecutor(), listOf(
                ToolSpec(domain = "db", method = "query", arguments = emptyList(), returnType = "String")
            )
        )
        registry.register(
            MockServiceToolExecutor(), listOf(
                ToolSpec(domain = "service.test", method = "fetch", arguments = emptyList(), returnType = "String")
            )
        )

        // Filter to include only service domains
        val rendered = ToolCallSpecificationRenderer.render(
            includeCustomDomains = true,
            customDomainFilter = { it.startsWith("service.") }
        )

        // Built-in tools should be present
        assertTrue(rendered.contains("// domain: tab"), "Should contain built-in tab domain")

        // Only service tools should be in custom section
        assertTrue(rendered.contains("// CustomTool"), "Should contain CustomTool section")
        assertTrue(rendered.contains("service.test.fetch"), "Should contain service fetch method")
        assertFalse(rendered.contains("db.query"), "Should NOT contain filtered db.query method")
    }

    // ==================== JSON Format Tests ====================

    @Test
    fun testRenderJsonShouldProduceValidJsonStructure() {
        val rendered = ToolCallSpecificationRenderer.renderJson(includeCustomDomains = false)

        // Should be valid JSON structure
        assertTrue(rendered.contains(""""tools":"""), "Should contain tools array")
        assertTrue(rendered.contains(""""domain":"""), "Should contain domain field")
        assertTrue(rendered.contains(""""method":"""), "Should contain method field")
        assertTrue(rendered.contains(""""parameters":"""), "Should contain parameters array")
        assertTrue(rendered.contains(""""returns":"""), "Should contain returns field")
    }

    @Test
    fun testRenderJsonShouldIncludeBuiltInTools() {
        val rendered = ToolCallSpecificationRenderer.renderJson(includeCustomDomains = false)

        // Should include built-in driver tools
        assertTrue(rendered.contains(""""domain": "tab""""), "Should contain driver domain")
        assertTrue(rendered.contains(""""method": "navigate""""), "Should contain navigate method")
        assertTrue(rendered.contains(""""method": "click""""), "Should contain click method")

        // Should include built-in browser tools
        assertTrue(rendered.contains(""""domain": "browser""""), "Should contain browser domain")
        assertTrue(rendered.contains(""""method": "switchTab""""), "Should contain switchTab method")

        // Should include built-in fs tools
        assertTrue(rendered.contains(""""domain": "fs""""), "Should contain fs domain")
        assertTrue(rendered.contains(""""method": "writeString""""), "Should contain writeString method")
    }

    @Test
    fun testRenderJsonShouldIncludeParametersWithTypes() {
        val rendered = ToolCallSpecificationRenderer.renderJson(includeCustomDomains = false)

        // Should include parameter details
        assertTrue(rendered.contains(""""name": "url""""), "Should contain url parameter")
        assertTrue(rendered.contains(""""type": "String""""), "Should contain String type")
    }

    @Test
    fun testRenderJsonShouldIncludeParametersWithDefaults() {
        val rendered = ToolCallSpecificationRenderer.renderJson(includeCustomDomains = false)

        // Should include default values for parameters (e.g., waitForSelector has timeout default)
        assertTrue(rendered.contains(""""default":"""), "Should contain at least one default value")
    }

    @Test
    fun testRenderJsonShouldIncludeCustomTools() {
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

        val rendered = ToolCallSpecificationRenderer.renderJson(includeCustomDomains = true)

        // Should include custom db tool
        assertTrue(rendered.contains(""""domain": "db""""), "Should contain db domain")
        assertTrue(rendered.contains(""""method": "query""""), "Should contain query method")
        assertTrue(rendered.contains(""""description": "Run a SQL query""""), "Should contain description")
    }

    @Test
    fun testRenderWithFormatKotlinShouldMatchOriginalRender() {
        val kotlinFormat = ToolCallSpecificationRenderer.render(
            format = ToolSpecFormat.KOTLIN,
            includeCustomDomains = false
        )
        val originalRender = ToolCallSpecificationRenderer.render(includeCustomDomains = false)

        assertEquals(originalRender, kotlinFormat, "KOTLIN format should match original render")
    }

    @Test
    fun testRenderWithFormatJsonShouldMatchRenderJson() {
        val jsonFormat = ToolCallSpecificationRenderer.render(
            format = ToolSpecFormat.JSON,
            includeCustomDomains = false
        )
        val jsonRender = ToolCallSpecificationRenderer.renderJson(includeCustomDomains = false)

        assertEquals(jsonRender, jsonFormat, "JSON format should match renderJson")
    }

    @Test
    fun testParseBuiltInSpecificationsShouldParseAllTools() {
        val specs = ToolCallSpecificationRenderer.parseBuiltInSpecifications()

        // Should parse multiple tools
        assertTrue(specs.isNotEmpty(), "Should parse built-in specifications")

        // Should include driver domain tools
        val driverTools = specs.filter { it.domain == "tab" }
        assertTrue(driverTools.isNotEmpty(), "Should include driver tools")

        // Should include browser domain tools
        val browserTools = specs.filter { it.domain == "browser" }
        assertTrue(browserTools.isNotEmpty(), "Should include browser tools")

        // Should include fs domain tools
        val fsTools = specs.filter { it.domain == "fs" }
        assertTrue(fsTools.isNotEmpty(), "Should include fs tools")
    }

    @Test
    fun testParseBuiltInSpecificationsShouldParseArgumentsCorrectly() {
        val specs = ToolCallSpecificationRenderer.parseBuiltInSpecifications()

        // Find navigate method which has a url parameter
        val navigate = specs.find { it.domain == "tab" && it.method == "navigate" }
        assertNotNull(navigate, "Should find navigate method")
        assertEquals(1, navigate!!.arguments.size, "navigate should have 1 argument")
        assertEquals("url", navigate.arguments[0].name, "First argument should be url")
        assertEquals("String", navigate.arguments[0].type, "url should be String type")
    }

    @Test
    fun testParseBuiltInSpecificationsShouldParseDefaultValues() {
        val specs = ToolCallSpecificationRenderer.parseBuiltInSpecifications()

        // Find waitForSelector which has a default timeout
        val waitForSelector = specs.find { it.domain == "tab" && it.method == "waitForSelector" }
        assertNotNull(waitForSelector, "Should find waitForSelector method")

        val timeoutArg = waitForSelector!!.arguments.find { it.name == "timeoutMillis" }
        assertNotNull(timeoutArg, "Should have timeoutMillis argument")
        assertEquals("3000", timeoutArg!!.defaultValue, "timeoutMillis should have default 3000")
    }

    @Test
    fun testParseBuiltInSpecificationsShouldParseReturnTypes() {
        val specs = ToolCallSpecificationRenderer.parseBuiltInSpecifications()

        // Find exists method which returns Boolean
        val exists = specs.find { it.domain == "tab" && it.method == "exists" }
        assertNotNull(exists, "Should find exists method")
        assertEquals("Boolean", exists!!.returnType, "exists should return Boolean")

        // Find navigate which returns Unit (no return type specified)
        val navigate = specs.find { it.domain == "tab" && it.method == "navigate" }
        assertNotNull(navigate, "Should find navigate method")
        assertEquals("Unit", navigate!!.returnType, "navigate should return Unit")
    }

    @Test
    fun testRenderAsJsonShouldFormatSpecsCorrectly() {
        val specs = listOf(
            ToolSpec(
                domain = "test",
                method = "doSomething",
                arguments = listOf(
                    ToolSpec.Arg("param1", "String"),
                    ToolSpec.Arg("param2", "Int", "42")
                ),
                returnType = "Result",
                description = "Test method"
            )
        )

        val json = ToolCallSpecificationRenderer.renderAsJson(specs)

        assertTrue(json.contains(""""domain": "test""""), "Should contain domain")
        assertTrue(json.contains(""""method": "doSomething""""), "Should contain method")
        assertTrue(json.contains(""""name": "param1""""), "Should contain param1")
        assertTrue(json.contains(""""type": "Int""""), "Should contain Int type")
        assertTrue(json.contains(""""default": "42""""), "Should contain default value")
        assertTrue(json.contains(""""returns": "Result""""), "Should contain return type")
        assertTrue(json.contains(""""description": "Test method""""), "Should contain description")
    }

    // ==================== Helper test classes ====================

    private class DbToolExecutor : AbstractToolExecutor() {
        override val domain: String = "db"
        override val receiverClass: KClass<*> = Any::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            receiver: Any
        ): Any? {
            return null
        }
    }

    private class MockServiceToolExecutor : AbstractToolExecutor() {
        override val domain: String = "service.test-server"
        override val receiverClass: KClass<*> = Any::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            receiver: Any
        ): Any? {
            return null
        }
    }

    private class ComplexToolExecutor : AbstractToolExecutor() {
        override val domain: String = "complex"
        override val receiverClass: KClass<*> = Any::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            receiver: Any
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
