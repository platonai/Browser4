package ai.platon.pulsar.rest.mcp.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The failure text a tool call returns must carry the **cause**, not just the driver's
 * phase name.
 *
 * Origin: every navigation of a live Amazon run failed with the bare word `createDevTools`
 * — the driver wraps the real failure in `WebDriverException("createDevTools", cause)`, and
 * only `e.message` was logged and returned, so neither the model nor the operator could see
 * why the browser was unusable (2026-10-28).
 */
@DisplayName("MCP tool error chain")
class McpToolErrorChainTest {

    @Test
    @DisplayName("a phase-named wrapper keeps the underlying reason")
    fun phaseWrapperKeepsCause() {
        val root = IllegalStateException("DevTools websocket closed (403)")
        val wrapper = RuntimeException("createDevTools", root)

        assertEquals("createDevTools ← DevTools websocket closed (403)", exceptionChainMessage(wrapper))
    }

    @Test
    @DisplayName("a repeated message is rendered once")
    fun duplicateMessagesCollapse() {
        val wrapper = RuntimeException("boom", IllegalStateException("boom"))

        assertEquals("boom", exceptionChainMessage(wrapper))
    }

    @Test
    @DisplayName("a cause without a message is skipped, not rendered as 'null'")
    fun nullMessagesAreSkipped() {
        val wrapper = RuntimeException("outer", IllegalStateException())

        assertEquals("outer", exceptionChainMessage(wrapper))
    }

    @Test
    @DisplayName("an exception with no message renders empty, so the caller can fall back")
    fun messagelessExceptionRendersEmpty() {
        assertEquals("", exceptionChainMessage(IllegalStateException()))
    }
}
