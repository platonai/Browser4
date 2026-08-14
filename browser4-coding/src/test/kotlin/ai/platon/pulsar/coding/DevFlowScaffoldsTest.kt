package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [DevFlowScaffolds] — the development-flow multi-file scaffolds.
 */
class DevFlowScaffoldsTest {

    @Test
    @DisplayName("b4CliCommand generates the four-file footprint")
    fun b4CliCommandGeneratesAllFiles() {
        val files = DevFlowScaffolds.b4CliCommand(
            name = "extract-prices",
            description = "Extract product prices from a page",
            category = "Extract",
            toolName = "extract_prices",
            backendMethod = "extractPrices",
        )
        assertEquals(4, files.size)
        assertTrue(files.containsKey("cli/browser4-cli/src/commands.rs"))
        assertTrue(files.containsKey("browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt"))
        assertTrue(files.containsKey("browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/NewCommandBackend.kt"))
        assertTrue(files.containsKey("browser4-rest/src/test/kotlin/ai/platon/pulsar/rest/mcp/controller/NewCommandTest.kt"))
    }

    @Test
    @DisplayName("b4CliCommand CommandDef uses the canonical structure")
    fun cliCommandDefCanonical() {
        val commands = DevFlowScaffolds.b4CliCommand("mycmd", "Does things", "Swarm", "my_cmd", "doThings")
        val rs = commands["cli/browser4-cli/src/commands.rs"]!!
        assertTrue(rs.contains("name: \"mycmd\""))
        assertTrue(rs.contains("description: \"Does things\""))
        assertTrue(rs.contains("category: Category::Swarm"))
        assertTrue(rs.contains("tool_name_fn: |_| \"my_cmd\".to_string()"))
        assertTrue(rs.contains("tool_params_fn: |args|"))
        assertTrue(rs.contains("e2e_coverage: E2eCoverage::Tested"))
    }

    @Test
    @DisplayName("b4CliCommand cross-file identifiers stay consistent")
    fun crossFileConsistency() {
        val files = DevFlowScaffolds.b4CliCommand("extract-prices", "desc", "Extract", "extract_prices", "extractPrices")
        val alias = files["browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt"]!!
        val backend = files["browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/NewCommandBackend.kt"]!!
        val test = files["browser4-rest/src/test/kotlin/ai/platon/pulsar/rest/mcp/controller/NewCommandTest.kt"]!!
        // The same toolName appears in the alias and the test.
        assertTrue(alias.contains("browser_extract_prices"))
        assertTrue(test.contains("browser_extract_prices"))
        assertTrue(backend.contains("extractPrices"))
    }

    @Test
    @DisplayName("b4CliCommand derives toolName and backendMethod when omitted")
    fun derivesDefaults() {
        val files = DevFlowScaffolds.b4CliCommand("extract-prices", "desc")
        val rs = files["cli/browser4-cli/src/commands.rs"]!!
        val backend = files["browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/NewCommandBackend.kt"]!!
        assertTrue(rs.contains("extract_prices"), "toolName should derive from name")
        assertTrue(backend.contains("extractPrices"), "backendMethod should derive from name")
    }

    @Test
    @DisplayName("agentTool generates executor + auto-config pair")
    fun agentToolGeneratesPair() {
        val files = DevFlowScaffolds.agentTool(
            pluginName = "browser4-weather",
            domain = "weather",
            basePackage = "ai.platon.pulsar.weather",
            toolMethod = "fetchWeather",
            toolDescription = "Fetch weather",
        )
        assertEquals(2, files.size)
        val executor = files["browser4-plugins/browser4-weather/src/main/kotlin/ai/platon/pulsar/weather/tools/WeatherToolExecutor.kt"]!!
        val config = files["browser4-plugins/browser4-weather/src/main/kotlin/ai/platon/pulsar/weather/config/WeatherAutoConfiguration.kt"]!!
        assertTrue(executor.contains("override val domain = \"weather\""))
        assertTrue(executor.contains("toolSpec[\"fetchWeather\"]"))
        assertTrue(executor.contains("receiverClass: KClass<*> = WebDriver::class"))
        assertTrue(config.contains("ToolMount"))
        assertTrue(config.contains("WeatherToolExecutor"))
        // Cross-file: config references the executor class.
        assertTrue(config.contains("listOf(WeatherToolExecutor())"))
    }
}
