package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.ToolErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Validation happens before dispatch, with the same rules on both MCP channels,
 * so a malformed call is rejected identically and never reaches the browser.
 */
@DisplayName("Tool spec validation")
class ToolSpecValidatorTest {

    private val validator = ToolSpecValidator()

    private val crawlSubmit = ToolSpec(
        domain = "crawl",
        method = "submit",
        arguments = listOf(
            ToolSpec.Arg("url", "String", null, "Seed URL"),
            ToolSpec.Arg("depth", "Int", "1", "Link levels to follow"),
            ToolSpec.Arg("args", "String", "", "Extra CLI-style arguments"),
        ),
        returnType = "String",
        description = "Submit a crawl task.",
    )

    @Test
    @DisplayName("a valid call produces no violation")
    fun validCallPasses() {
        val violations = validator.validate(
            crawlSubmit,
            mapOf("url" to "https://example.com", "depth" to 2),
        )

        assertEquals(emptyList<ToolSpecValidator.Violation>(), violations)
    }

    @Test
    @DisplayName("a missing required argument is reported with the signature")
    fun missingRequiredArgumentIsReported() {
        val violations = validator.validate(crawlSubmit, mapOf("depth" to 1))

        assertEquals(ToolErrorCode.MISSING_REQUIRED_ARG, violations.single().code)
        assertTrue(
            violations.single().message.contains("crawl.submit(url: String, depth: Int = 1, args: String = \"\")"),
            "the message must show the expected signature: ${violations.single().message}"
        )
    }

    @Test
    @DisplayName("a blank value does not satisfy a required argument")
    fun blankRequiredValueIsReported() {
        val violations = validator.validate(crawlSubmit, mapOf("url" to "  "))

        assertEquals(ToolErrorCode.MISSING_REQUIRED_ARG, violations.single().code)
    }

    @Test
    @DisplayName("a wrongly typed value is invalid, not silently coerced")
    fun wrongTypeIsInvalid() {
        val violations = validator.validate(crawlSubmit, mapOf("url" to "https://example.com", "depth" to "abc"))

        assertEquals(ToolErrorCode.INVALID_ARGUMENT, violations.single().code)
        assertTrue(violations.single().message.contains("depth"), violations.single().message)
    }

    @Test
    @DisplayName("undeclared arguments are allowed by default and rejected in strict mode")
    fun unknownArgumentsFollowTheStrictFlag() {
        // crawl.submit's executor also reads `sql`/`urls`, which the spec does not
        // declare — rejecting them by default would break working calls.
        val args = mapOf("url" to "https://example.com", "sql" to "select 1")

        assertEquals(emptyList<ToolSpecValidator.Violation>(), validator.validate(crawlSubmit, args))

        val strict = ToolSpecValidator(strictUnknownArgs = true).validate(crawlSubmit, args)
        assertEquals(ToolErrorCode.UNKNOWN_ARGUMENT, strict.single().code)
        assertTrue(strict.single().message.contains("sql"), strict.single().message)
    }

    @Test
    @DisplayName("the transport-owned sessionId is never an unknown argument")
    fun sessionIdIsContext() {
        val strict = ToolSpecValidator(strictUnknownArgs = true)

        assertEquals(
            emptyList<ToolSpecValidator.Violation>(),
            strict.validate(crawlSubmit, mapOf("url" to "https://example.com", "sessionId" to "s1"))
        )
    }

    @Test
    @DisplayName("validation can be disabled for a deployment")
    fun validationCanBeDisabled() {
        System.setProperty("mcp.validateArgs", "false")
        try {
            assertTrue(!ToolSpecValidator.validationEnabled())
        } finally {
            System.clearProperty("mcp.validateArgs")
        }
        assertTrue(ToolSpecValidator.validationEnabled(), "default is on")
        assertNull(null as String?, "sanity")
    }
}
