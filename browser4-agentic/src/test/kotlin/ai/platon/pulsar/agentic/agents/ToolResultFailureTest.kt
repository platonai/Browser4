package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [isFailedToolResult] decides the `ok` flag of the live `agent run --follow`
 * view and of the agent-memory `ToolExecuted` events.
 *
 * It missed the bounded [ai.platon.pulsar.agentic.model.ToolOutcome] envelope for a
 * while: the header is `domain.method [fail] summary`, not the coordinator's raw
 * `[fail] …`, so every policy refusal — rendered `[fail]` for the model — was still
 * counted as ok and never printed to the operator (20 refusals in the 2026-10-28
 * supervision run).
 */
@DisplayName("tool result failure detection")
class ToolResultFailureTest {

    @Test
    @DisplayName("the raw coordinator [fail] prefix counts as a failure")
    fun rawFailPrefix() {
        assertTrue(isFailedToolResult("[fail] tool execution failed"))
        assertTrue(isFailedToolResult("\n  [fail] boom"))
    }

    @Test
    @DisplayName("a ToolOutcome envelope header counts as a failure")
    fun envelopeFailHeader() {
        assertTrue(
            isFailedToolResult(
                "coding.read [fail] Error: PERMISSION_DENIED | code=PATH_DENIED | tool=coding.fs"
            )
        )
        assertTrue(isFailedToolResult("b4.run [fail] failed: timed out after 49035ms"))
    }

    @Test
    @DisplayName("successful results and bodies that merely mention [fail] stay ok")
    fun successfulResults() {
        assertFalse(isFailedToolResult("coding.read [ok] 412 bytes"))
        assertFalse(isFailedToolResult("coding.read [ok] file\nline with [fail] inside"))
        assertFalse(isFailedToolResult(""))
    }
}
