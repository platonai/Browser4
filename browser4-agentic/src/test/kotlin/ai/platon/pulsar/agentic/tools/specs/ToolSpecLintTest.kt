package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.BrowserTabToolExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The registry is the interface contract, so its specs are linted like code.
 *
 * ERROR rules must stay clean for every spec the product advertises; WARNING
 * rules track the documentation work still outstanding (see
 * `docs-dev/copilot/mcp-interface-hardening-plan.md`, Phase 1) and are reported,
 * not enforced.
 */
@DisplayName("ToolSpec lint")
class ToolSpecLintTest {

    @Test
    @DisplayName("specs generated from the WebDriver interface are error-free")
    fun generatedWebDriverSpecsAreClean() {
        ToolSpecGenerator.generateAllOnce()
        val specs = ToolSpecGenerator.webDriverToolSpecs
        assertTrue(specs.isNotEmpty(), "expected generated WebDriver specs")

        val issues = ToolSpecLint.check(specs)
        assertEquals(
            emptyList<String>(), ToolSpecLint.errors(issues).map { "${it.tool}: ${it.message}" },
            "Generated specs must satisfy the interface contract"
        )

        // Visibility into the documentation work that is still open (Phase 1).
        val warnings = ToolSpecLint.warnings(issues)
        println("ToolSpec lint: ${specs.size} generated specs, ${warnings.size} documentation warnings")
    }

    @Test
    @DisplayName("explicitly authored tab specs are error-free")
    fun authoredTabSpecsAreClean() {
        val specs = BrowserTabToolExecutor().getToolSpecs().values
        assertTrue(specs.isNotEmpty(), "expected tab tool specs")

        val issues = ToolSpecLint.check(specs)
        assertEquals(
            emptyList<String>(), ToolSpecLint.errors(issues).map { "${it.tool}: ${it.message}" },
            "Authored specs must satisfy the interface contract"
        )
    }

    @Test
    @DisplayName("a blank description is an error")
    fun blankDescriptionIsAnError() {
        val issues = ToolSpecLint.check(listOf(spec(description = " ")))

        assertTrue(issues.any { it.severity == ToolSpecLint.Severity.ERROR && it.message.contains("description") })
    }

    @Test
    @DisplayName("no advertised signature leaks a data-class dump or an empty default")
    fun noLeakySignatures() {
        ToolSpecGenerator.generateAllOnce()
        val leaky = ToolSpecGenerator.webDriverToolSpecs
            .map { it.expression }
            .filter { it.contains("Arg(") || it.endsWith("= ") }

        assertEquals(
            emptyList<String>(), leaky,
            "Signatures must be callable text (regression guard for the fixed rendering)"
        )
    }

    @Test
    @DisplayName("duplicate signatures and kebab-case cliName are errors, overloads are warnings")
    fun duplicatesAndCliNameAreChecked() {
        val duplicated = listOf(spec(), spec(), spec().copy(cliName = "profile-import"))
        val overloaded = listOf(spec(), spec().copy(arguments = listOf(ToolSpec.Arg("url", "String", null), ToolSpec.Arg("depth", "Int", "1"))))

        val issues = ToolSpecLint.check(duplicated)
        val overloadIssues = ToolSpecLint.check(overloaded)

        assertTrue(issues.any { it.severity == ToolSpecLint.Severity.ERROR && it.message.contains("duplicate") })
        assertTrue(issues.any { it.severity == ToolSpecLint.Severity.ERROR && it.message.contains("spaced form") })
        assertEquals(
            emptyList<ToolSpecLint.Issue>(), ToolSpecLint.errors(overloadIssues),
            "Overloads are legitimate and must not fail the lint: ${ToolSpecLint.report(overloadIssues)}"
        )
    }

    @Test
    @DisplayName("a missing argument description is a warning, not an error")
    fun missingArgumentDescriptionIsAWarning() {
        val issues = ToolSpecLint.check(listOf(spec()))

        assertEquals(emptyList<ToolSpecLint.Issue>(), ToolSpecLint.errors(issues), ToolSpecLint.report(issues))
        assertTrue(issues.any { it.severity == ToolSpecLint.Severity.WARNING && it.tool.endsWith("(url)") })
    }

    private fun spec(description: String = "Do a thing.") = ToolSpec(
        domain = "demo",
        method = "run",
        arguments = listOf(ToolSpec.Arg("url", "String", null)),
        returnType = "String",
        description = description,
        help = "demo.run(url: String)",
    )
}
