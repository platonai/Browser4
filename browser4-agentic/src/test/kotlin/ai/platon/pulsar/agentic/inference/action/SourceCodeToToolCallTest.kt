package ai.platon.pulsar.agentic.inference.action

import ai.platon.browser4.common.B4LLMUtils
import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SourceCodeToToolCallTest {
    private fun assertSnapshotJsonDeterministic(
        domain: String,
        moduleName: String,
        fileName: String,
        interfaceName: String,
    ) {
        val sourceCode = B4LLMUtils.readSourceFileFromResource(moduleName, fileName)

        val first = ToolSpecGenerator.toSnapshotJson(
            ToolSpecGenerator.extractInterface(domain, sourceCode, interfaceName)
        )
        val second = ToolSpecGenerator.toSnapshotJson(
            ToolSpecGenerator.extractInterface(domain, sourceCode, interfaceName)
        )

        assertEquals(first, second, "Snapshot JSON should be byte-for-byte stable for $interfaceName")
    }

    private fun assertIndentedFieldOrder(json: String, indent: String, vararg fields: String) {
        val actualFields = json.lineSequence()
            .map { it.trimEnd() }
            .filter { it.startsWith("$indent\"") }
            .map { it.substringAfter('"').substringBefore('"') }
            .toList()

        for (field in fields) {
            assertTrue(actualFields.contains(field), "Section should contain field '$field'")
        }

        val actualIndexes = fields.map(actualFields::indexOf)
        assertEquals(actualIndexes.sorted(), actualIndexes, "Fields should appear in order: ${fields.joinToString(", ")}")
    }

    @Test
    @DisplayName("extractInterface keeps argument order from source")
    fun extractInterfaceKeepsArgumentOrderFromSource() {
        val sourceCode = """
            interface Demo {
                @MCP
                fun stableArgs(zeta: Int = 1, alpha: String = "x", beta: Long): Unit
            }
        """.trimIndent()

        val tools = ToolSpecGenerator.extractInterface("tab", sourceCode, "Demo")
        val stableArgs = tools.first { it.method == "stableArgs" }

        assertEquals(listOf("zeta", "alpha", "beta"), stableArgs.arguments.map { it.name })
    }

    @Test
    @DisplayName("extractInterface keeps method order from source")
    fun extractInterfaceKeepsMethodOrderFromSource() {
        val sourceCode = """
            interface Demo {
                @MCP
                fun zebra(): Unit

                @MCP
                fun alpha(): Unit

                @MCP
                fun middle(): Unit
            }
        """.trimIndent()

        val tools = ToolSpecGenerator.extractInterface("agent", sourceCode, "Demo")
        assertEquals(listOf("zebra", "alpha", "middle"), tools.map { it.method })
    }

    @Test
    @DisplayName("extractInterface keeps overloaded methods in source order")
    fun extractInterfaceKeepsOverloadedMethodsInSourceOrder() {
        val sourceCode = """
            interface Demo {
                @MCP
                fun ping(id: String): Unit

                @MCP
                fun ping(id: String, retry: Int = 1): Unit
            }
        """.trimIndent()

        val tools = ToolSpecGenerator.extractInterface("agent", sourceCode, "Demo")
        assertEquals(listOf(1, 2), tools.filter { it.method == "ping" }.map { it.arguments.size })
    }

    @Test
    @DisplayName("ToolSpec serialization keeps expression before cli")
    fun toolSpecSerializationKeepsExpressionBeforeCli() {
        val sourceCode = """
            interface Demo {
                @MCP
                fun run(task: String): Unit
            }
        """.trimIndent()

        val tool = ToolSpecGenerator.extractInterface("agent", sourceCode, "Demo").first()
        val json = prettyPulsarObjectMapper().writeValueAsString(tool)

        val expressionIndex = json.indexOf("\"expression\"")
        val cliIndex = json.indexOf("\"cli\"")
        assertTrue(expressionIndex >= 0, "Serialized ToolSpec should contain expression field")
        assertTrue(cliIndex >= 0, "Serialized ToolSpec should contain cli field")
        assertTrue(expressionIndex < cliIndex, "expression should be serialized before cli")
    }

    @Test
    @DisplayName("ToolSpecGenerator snapshot json keeps deterministic field order")
    fun toolSpecGeneratorSnapshotJsonKeepsDeterministicFieldOrder() {
        val sourceCode = """
            interface Demo {
                /**
                 * Run a task. @mcp
                 *
                 * Extra help for run.
                 */
                @MCP
                fun run(task: String = "x"): Unit
            }
        """.trimIndent()

        val tool = ToolSpecGenerator.extractInterface("agent", sourceCode, "Demo").first()
        val json = ToolSpecGenerator.toSnapshotJson(listOf(tool))

        assertIndentedFieldOrder(json, "    ", "name", "type", "defaultValue", "expression", "cliOptions")
        assertIndentedFieldOrder(json, "  ", "domain", "method", "arguments", "returnType", "description", "help", "expression", "cli")
    }

    @Test
    @DisplayName("ToolSpecGenerator normalizes generated snapshot content to Linux line endings")
    fun toolSpecGeneratorNormalizesGeneratedSnapshotContentToLinuxLineEndings() {
        val normalized = ToolSpecGenerator.normalizeToLinuxLineEndings("first\r\nsecond\rthird\nfourth")

        assertEquals("first\nsecond\nthird\nfourth", normalized)
    }

    @Test
    @DisplayName("WebDriver snapshot json is stable across repeated generation")
    fun webDriverSnapshotJsonIsStableAcrossRepeatedGeneration() {
        assertSnapshotJsonDeterministic("tab", "browser4-browser", "WebDriver.kt", "WebDriver")
    }

    @Test
    @DisplayName("PerceptiveAgent snapshot json is stable across repeated generation")
    fun perceptiveAgentSnapshotJsonIsStableAcrossRepeatedGeneration() {
        assertSnapshotJsonDeterministic("agent", "browser4-agentic", "PerceptiveAgent.kt", "PerceptiveAgent")
    }

    @Test
    @DisplayName("extract methods from WebDriver resource")
    fun extractMethodsFromWebdriverResource() {
        val sourceCode =
            B4LLMUtils.readSourceFileFromResource("browser4-browser", "WebDriver.kt")
        val tools = ToolSpecGenerator.extractInterface("tab", sourceCode, "WebDriver")
        assertTrue(tools.isNotEmpty(), "Tool list should not be empty")
        val click = tools.firstOrNull { it.domain == "tab" && it.method == "click" }
        assertNotNull(click, "Should contain driver.click method")
        assertTrue(click!!.arguments.map { it.name }.contains("selector"), "click should have selector argument")
    }

    @Test
    @DisplayName("extract KDoc full comment as help")
    fun extractKDocFullCommentAsHelp() {
        val sourceCode =
            B4LLMUtils.readSourceFileFromResource("browser4-browser", "WebDriver.kt")
        val tools = ToolSpecGenerator.extractInterface("tab", sourceCode, "WebDriver")
        assertTrue(tools.isNotEmpty(), "Tool list should not be empty")

        val click =
            tools.firstOrNull { it.domain == "tab" && it.method == "click" && it.arguments.any { arg -> arg.name == "count" } }
        assertNotNull(click, "Should contain driver.click method")

        val help = click!!.help
        assertNotNull(help, "Help should not be null")

        // Verify full description content
        assertTrue(
            help!!.contains("Focus on an element with [selector] and click it."),
            "Help should contain main description"
        )
        assertTrue(
            help.contains("If there's no element matching `selector`, nothing to do."),
            "Help should contain secondary description"
        )
        assertTrue(help.contains("driver.click"), "Help should contain code example")

        // Verify annotations are removed
        assertTrue(!help.contains("@param"), "Help should not contain @param annotations")
    }

    @Test
    @DisplayName("extractInterface only includes @MCP methods and uses fallback description")
    fun extractInterfaceOnlyIncludesAnnotatedMethods() {
        val sourceCode = """
            interface Demo {
                /**
                 * Click the current button. @mcp
                 *
                 * Extra context for click.
                 */
                @MCP
                suspend fun clickNow(): Unit

                @MCP
                suspend fun noDocsHere()

                suspend fun internalOnly()
            }
        """.trimIndent()

        val tools = ToolSpecGenerator.extractInterface("tab", sourceCode, "Demo")

        assertTrue(tools.any { it.method == "clickNow" }, "Annotated method with KDoc should be included")
        assertTrue(tools.any { it.method == "noDocsHere" }, "Annotated method without KDoc should be included")
        assertTrue(tools.none { it.method == "internalOnly" }, "Unannotated method should not be included")
        assertTrue(
            tools.first { it.method == "clickNow" }.description!!.contains("Click the current button."),
            "Tagged @mcp paragraph should be used for description"
        )
        assertTrue(
            tools.first { it.method == "noDocsHere" }.description!!.contains("No Docs Here"),
            "Method-name fallback should generate a human readable description"
        )
    }
}
