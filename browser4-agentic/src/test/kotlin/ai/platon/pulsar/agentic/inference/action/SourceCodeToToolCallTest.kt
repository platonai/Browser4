package ai.platon.pulsar.agentic.inference.action

import ai.platon.browser4.common.B4LLMUtils
import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SourceCodeToToolCallTest {
    @Test
    @DisplayName("extractInterface generates arguments in deterministic order")
    fun extractInterfaceGeneratesArgumentsInDeterministicOrder() {
        val sourceCode = """
            interface Demo {
                @MCP
                fun stableArgs(zeta: Int = 1, alpha: String = "x", beta: Long): Unit
            }
        """.trimIndent()

        val tools = ToolSpecGenerator.extractInterface("tab", sourceCode, "Demo")
        val stableArgs = tools.first { it.method == "stableArgs" }

        assertEquals(listOf("alpha", "beta", "zeta"), stableArgs.arguments.map { it.name })
    }

    @Test
    @DisplayName("extract methods from WebDriver resource")
    fun extractMethodsFromWebdriverResource() {
        val sourceCode =
            B4LLMUtils.readSourceFileFromResource("browser4-core", "WebDriver.kt")
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
            B4LLMUtils.readSourceFileFromResource("browser4-core", "WebDriver.kt")
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
