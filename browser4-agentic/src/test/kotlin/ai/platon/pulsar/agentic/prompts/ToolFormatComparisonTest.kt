package ai.platon.pulsar.agentic.prompts

import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer
import ai.platon.pulsar.agentic.tools.specs.ToolSpecFormat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for comparing JSON vs Kotlin format for Agent Tool descriptions.
 *
 * This test generates sample output files that can be used to compare the two formats.
 * Output files are saved to the project root's docs-dev/data/tool-format-comparison directory.
 *
 * The output directory can be overridden via the `tool.format.comparison.output.dir` system property.
 */
class ToolFormatComparisonTest {

    companion object {
        private const val OUTPUT_DIR_PROPERTY = "tool.format.comparison.output.dir"
        private const val DEFAULT_OUTPUT_SUBDIR = "docs-dev/data/tool-format-comparison"
    }

    /**
     * Resolves the output directory for generated files.
     *
     * Uses the following resolution order:
     * 1. System property `tool.format.comparison.output.dir` if set
     * 2. Searches up from current working directory for project root (contains pom.xml)
     * 3. Falls back to relative path from current directory
     */
    private val outputDir: File by lazy {
        // Check system property first
        System.getProperty(OUTPUT_DIR_PROPERTY)?.let {
            return@lazy File(it).also { it.mkdirs() }
        }

        // Try to find project root by searching for pom.xml
        var current = File(System.getProperty("user.dir"))
        while (current.parentFile != null) {
            val pomFile = File(current, "pom.xml")
            if (pomFile.exists() && pomFile.readText().contains("<artifactId>pulsar</artifactId>")) {
                return@lazy File(current, DEFAULT_OUTPUT_SUBDIR).also { it.mkdirs() }
            }
            // Check parent's pom.xml (for when running from module directory)
            val parentPom = File(current.parentFile, "pom.xml")
            if (parentPom.exists() && parentPom.readText().contains("<artifactId>pulsar</artifactId>")) {
                return@lazy File(current.parentFile, DEFAULT_OUTPUT_SUBDIR).also { it.mkdirs() }
            }
            current = current.parentFile
        }

        // Fallback to relative path
        File(DEFAULT_OUTPUT_SUBDIR).also { it.mkdirs() }
    }

    @Test
    @DisplayName("Generate Kotlin format tool specification")
    fun testGenerateKotlinFormat() {
        val kotlinFormat = ToolCallSpecificationRenderer.render(
            format = ToolSpecFormat.KOTLIN,
            includeCustomDomains = false
        )

        File(outputDir, "kotlin-format-tools.txt").writeText(kotlinFormat)
        println("=== Kotlin Format Sample ===")
        println(kotlinFormat)
        println("\nKotlin format lines: ${kotlinFormat.lines().size}")
        println("Kotlin format chars: ${kotlinFormat.length}")
    }

    @Test
    @DisplayName("Generate JSON format tool specification")
    fun testGenerateJsonFormat() {
        val jsonFormat = ToolCallSpecificationRenderer.render(
            format = ToolSpecFormat.JSON,
            includeCustomDomains = false
        )

        File(outputDir, "json-format-tools.json").writeText(jsonFormat)
        println("=== JSON Format Sample ===")
        println(jsonFormat)
        println("\nJSON format lines: ${jsonFormat.lines().size}")
        println("JSON format chars: ${jsonFormat.length}")
    }

    @Test
    @DisplayName("Generate system prompt with Kotlin format")
    fun testGenerateSystemPromptWithKotlinFormat() {
        val kotlinPrompt = buildMainSystemPromptV1(ToolSpecFormat.KOTLIN)
        File(outputDir, "system-prompt-kotlin.md").writeText(kotlinPrompt)
        println("Generated system-prompt-kotlin.md")
        println("Kotlin system prompt lines: ${kotlinPrompt.lines().size}")
        println("Kotlin system prompt chars: ${kotlinPrompt.length}")
    }

    @Test
    @DisplayName("Generate system prompt with JSON format")
    fun testGenerateSystemPromptWithJsonFormat() {
        val jsonPrompt = buildMainSystemPromptV1(ToolSpecFormat.JSON)
        File(outputDir, "system-prompt-json.md").writeText(jsonPrompt)
        println("Generated system-prompt-json.md")
        println("JSON system prompt lines: ${jsonPrompt.lines().size}")
        println("JSON system prompt chars: ${jsonPrompt.length}")
    }

    @Test
    @DisplayName("Compare Kotlin and JSON formats")
    fun testCompareFormats() {
        val kotlinFormat = ToolCallSpecificationRenderer.render(
            format = ToolSpecFormat.KOTLIN,
            includeCustomDomains = false
        )
        val jsonFormat = ToolCallSpecificationRenderer.render(
            format = ToolSpecFormat.JSON,
            includeCustomDomains = false
        )

        val kotlinPrompt = buildMainSystemPromptV1(ToolSpecFormat.KOTLIN)
        val jsonPrompt = buildMainSystemPromptV1(ToolSpecFormat.JSON)

        println("=== Format Comparison ===")
        println("Tool Specification:")
        println("  Kotlin format: ${kotlinFormat.lines().size} lines, ${kotlinFormat.length} chars")
        println("  JSON format: ${jsonFormat.lines().size} lines, ${jsonFormat.length} chars")
        println("")
        println("Full System Prompt:")
        println("  Kotlin format: ${kotlinPrompt.lines().size} lines, ${kotlinPrompt.length} chars")
        println("  JSON format: ${jsonPrompt.lines().size} lines, ${jsonPrompt.length} chars")

        // Write comparison summary
        val summary = buildString {
            appendLine("# Tool Format Comparison")
            appendLine()
            appendLine("This document compares two formats for describing Agent Tools in the system prompt:")
            appendLine("1. **Kotlin-like format** - Current approach using Kotlin function signatures")
            appendLine("2. **JSON format** - Structured JSON array with tool definitions")
            appendLine()
            appendLine("## Tool Specification Size")
            appendLine("| Format | Lines | Characters |")
            appendLine("|--------|-------|------------|")
            appendLine("| Kotlin | ${kotlinFormat.lines().size} | ${kotlinFormat.length} |")
            appendLine("| JSON | ${jsonFormat.lines().size} | ${jsonFormat.length} |")
            appendLine()
            appendLine("## Full System Prompt Size")
            appendLine("| Format | Lines | Characters |")
            appendLine("|--------|-------|------------|")
            appendLine("| Kotlin | ${kotlinPrompt.lines().size} | ${kotlinPrompt.length} |")
            appendLine("| JSON | ${jsonPrompt.lines().size} | ${jsonPrompt.length} |")
            appendLine()
            appendLine("## Key Differences")
            appendLine()
            appendLine("### Kotlin Format Advantages")
            appendLine("- **Compact**: ~${100 - (kotlinFormat.length * 100 / jsonFormat.length)}% smaller than JSON")
            appendLine("- **Familiar**: Natural syntax for Kotlin/Java developers")
            appendLine("- **Token efficient**: Fewer tokens = lower API costs")
            appendLine("- **Human readable**: Easy to scan and understand quickly")
            appendLine()
            appendLine("Example:")
            appendLine("```kotlin")
            appendLine("driver.click(selector: String)")
            appendLine("driver.fill(selector: String, text: String)")
            appendLine("```")
            appendLine()
            appendLine("### JSON Format Advantages")
            appendLine("- **Machine readable**: Standard JSON structure")
            appendLine("- **Self-documenting**: Explicit field names")
            appendLine("- **Tool calling compatible**: Matches OpenAI function calling format")
            appendLine("- **Extensible**: Easy to add metadata")
            appendLine()
            appendLine("Example:")
            appendLine("```json")
            appendLine("{")
            appendLine("  \"domain\": \"driver\",")
            appendLine("  \"method\": \"click\",")
            appendLine("  \"parameters\": [{\"name\": \"selector\", \"type\": \"String\"}],")
            appendLine("  \"returns\": \"Unit\"")
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("## Generated Files")
            appendLine("- `kotlin-format-tools.txt` - Raw Kotlin format tool specifications")
            appendLine("- `json-format-tools.json` - Raw JSON format tool specifications")
            appendLine("- `system-prompt-kotlin.md` - Full system prompt with Kotlin format")
            appendLine("- `system-prompt-json.md` - Full system prompt with JSON format")
            appendLine()
            appendLine("## Recommendation")
            appendLine()
            appendLine("For token-efficient LLM interactions, the **Kotlin format** is preferred as it uses")
            appendLine("${100 - (kotlinPrompt.length * 100 / jsonPrompt.length)}% fewer characters in the system prompt.")
            appendLine()
            appendLine("For tool/function calling APIs that require JSON schemas, the **JSON format**")
            appendLine("provides better compatibility with OpenAI, Anthropic, and other providers' APIs.")
        }

        File(outputDir, "comparison-summary.md").writeText(summary)
        println("\nComparison summary written to ${File(outputDir, "comparison-summary.md").absolutePath}")
    }
}
