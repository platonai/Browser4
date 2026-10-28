package ai.platon.pulsar.agentic.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ToolOutcome] — the bounded model-facing envelope of one tool
 * execution (feedback-loop overhaul design §2).
 */
class ToolOutcomeTest {

    private fun result(value: String? = null, error: Exception? = null): ToolCallResult {
        val evaluate = TcEvaluate(
            value = value,
            exception = error?.let { TcException("coding.read(path=\"x\")", it) }
        )
        return ToolCallResult(evaluate)
    }

    @Test
    @DisplayName("envelope reports ok and first-line summary for a string result")
    fun testOkSummaryFromStringValue() {
        val outcome = ToolOutcome.from(result("✓ Written 1204 bytes to src/Foo.kt\nrest of output"))
        assertTrue(outcome.ok)
        assertEquals("unknown.unknown", "${outcome.domain}.${outcome.method}")
        assertEquals("✓ Written 1204 bytes to src/Foo.kt", outcome.summary)
        // The body is content: it must keep its newline (a CSV read back as one
        // space-separated line is unusable — see testBodyKeepsLineStructure).
        assertEquals("✓ Written 1204 bytes to src/Foo.kt\nrest of output", outcome.body)
    }

    @Test
    @DisplayName("the body keeps line breaks so rows stay distinguishable")
    fun testBodyKeepsLineStructure() {
        val csv = "asin,title,price\nB001,Microwave A,99.99\nB002,Microwave B,129.00\n"

        val outcome = ToolOutcome.from(result(csv), domain = "coding", method = "read")

        val body = outcome.body ?: ""
        assertTrue(body.contains("\n"), "line breaks must survive the envelope: $body")
        assertEquals(3, body.lines().count { it.isNotBlank() }, body)
        assertTrue(body.contains("B002,Microwave B,129.00"), body)
    }

    @Test
    @DisplayName("an over-budget body is cut in the middle and keeps a marker")
    fun testBodyTruncationKeepsMarker() {
        val long = (1..400).joinToString("\n") { "row $it,value $it" }

        val outcome = ToolOutcome.from(result(long), domain = "coding", method = "read")

        val body = outcome.body ?: ""
        assertTrue(body.length <= 1_600 + 64, "coding.read body should stay bounded, got ${body.length}")
        assertTrue(body.contains("…[truncated"), body.takeLast(120))
        assertTrue(body.startsWith("row 1,value 1"), body.take(80))
        assertTrue(body.trimEnd().endsWith("row 400,value 400"), body.takeLast(80))
    }

    @Test
    @DisplayName("explicit domain/method override the unknown fallback when the result has no actionDescription")
    fun testExplicitDomainMethodOverrideUnknown() {
        // Mirrors the native tool-calling loop: AgentToolManager.execute returns
        // a ToolCallResult without an ActionDescription, but the coordinator has
        // already resolved (domain, method) — the header must not say
        // "unknown.unknown".
        val outcome = ToolOutcome.from(
            result("b4.run -> ok"),
            domain = "b4", method = "run",
        )
        assertEquals("b4.run", "${outcome.domain}.${outcome.method}")
        assertTrue(outcome.render().startsWith("b4.run [ok]"), outcome.render())
        assertFalse(outcome.render().contains("unknown.unknown"), outcome.render())
    }

    @Test
    @DisplayName("envelope reports failure with bounded error chain")
    fun testFailureCapturesErrors() {
        val cause = IllegalStateException("Unresolved reference 'pdk'".repeat(60))
        val outcome = ToolOutcome.from(result(error = cause))
        assertFalse(outcome.ok)
        assertTrue(outcome.summary.startsWith("failed: "), "summary should start with failed: ${outcome.summary}")
        assertTrue(outcome.errors.isNotEmpty(), "errors should be captured")
        assertTrue(outcome.errors.first().length <= 350, "error entries should be bounded")
    }

    @Test
    @DisplayName("body is truncated to the per-tool budget")
    fun testBodyBudgetForVerboseTools() {
        val longOutput = (1..400).joinToString("\n") { "diagnostic line $it" }
        val evaluate = TcEvaluate(value = longOutput)
        val toolCall = ToolCall(
            domain = "coding", method = "mvnBuild",
            arguments = mutableMapOf("module" to "browser4-agentic")
        )
        val observeElement = ObserveElement(toolCall = toolCall)
        val actionDescription = ActionDescription(
            instruction = "t",
            observeElements = listOf(observeElement)
        )
        val outcome = ToolOutcome.from(ToolCallResult(evaluate, actionDescription = actionDescription))
        assertEquals("coding", outcome.domain)
        assertEquals("mvnBuild", outcome.method)
        assertTrue(outcome.body!!.length <= 3000 + 50, "mvnBuild body should be capped, got ${outcome.body!!.length}")
    }

    @Test
    @DisplayName("render emits header, body and errors in bounded block")
    fun testRenderFormat() {
        val outcome = ToolOutcome(
            domain = "coding", method = "write", ok = true,
            summary = "written", body = "content line",
            errors = emptyList(), workspaceDelta = "files +0, lines +2/-0"
        )
        val rendered = outcome.render()
        assertTrue(rendered.contains("coding.write [ok] written"))
        assertTrue(rendered.contains("content line"))
        assertTrue(rendered.contains("Δ files +0, lines +2/-0"))
    }

    @Test
    @DisplayName("resultPreview is bounded and whitespace-collapsed")
    fun testResultPreviewOnAgentState() {
        val evaluate = TcEvaluate(value = "a\nb".repeat(400))
        val state = AgentState(
            step = 1, instruction = "t",
            browserUseState = ai.platon.pulsar.api.model.BrowserUseState.DUMMY,
            toolCallResult = ToolCallResult(evaluate)
        )
        val preview = state.resultPreview
        assertTrue(preview!!.length <= 600, "preview should be capped at 600 chars")
        assertFalse(preview.contains("\n"), "preview should be single-line")
        assertNull(AgentState(step = 1, instruction = "t").resultPreview)
    }

    @Test
    @DisplayName("a permission refusal is rendered as a failure, not [ok]")
    fun testPermissionRefusalIsFailure() {
        // PermissionAudit returns refusals as values (never throws by design). Reporting
        // them as [ok] hid every refusal from `--follow` (which prints [fail] results
        // only) and let the model re-phrase the same denied path four times in a live run.
        val refusal = "Error: PERMISSION_DENIED | code=PATH_DENIED | tool=coding.fs " +
            "| detail=path 'D:\\workspace\\x.xlsx' is outside the workspace " +
            "| hint=use a workspace-relative path"

        val outcome = ToolOutcome.from(result(refusal), domain = "coding", method = "stat")

        assertFalse(outcome.ok, "a refusal must not be reported as success")
        val rendered = outcome.render()
        assertTrue(rendered.startsWith("coding.stat [fail]"), rendered)
        assertTrue(rendered.contains("PATH_DENIED"), "the refusal reason must survive: $rendered")
        assertTrue(rendered.contains("hint="), "the allowed route must survive: $rendered")
    }

    @Test
    @DisplayName("the refusal marker inside a result body does not fail a successful call")
    fun testRefusalMarkerInBodyStaysOk() {
        val outcome = ToolOutcome.from(result("README.md\nnote: PERMISSION_DENIED | code=PATH_DENIED in a doc"))

        assertTrue(outcome.ok, "only the first line decides: ${outcome.render()}")
    }

    @Test
    @DisplayName("JSON that embeds earlier refusals stays ok (memory.read)")
    fun testEmbeddedRefusalInJsonStaysOk() {
        // memory.read returns the task's own event log, which quotes earlier refusal
        // results. The marker is *inside* the JSON, not the result's verdict.
        val json = """{"taskId":"cf268579","events":[{"type":"tool_executed","text":"PARSE DENIED"},""" +
            """"PERMISSION_DENIED | code=PATH_DENIED | tool=coding.fs"}]}"""

        val outcome = ToolOutcome.from(result(json), domain = "memory", method = "read")

        assertTrue(outcome.ok, "an embedded refusal must not fail the read: ${outcome.render()}")
    }
}
