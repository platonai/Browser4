package ai.platon.pulsar.agentic.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Every failure path reports one of these codes, so a client can branch on the
 * code (retry, re-open a session, fix an argument) instead of matching prose.
 */
@DisplayName("Tool error codes")
class ToolErrorCodeTest {

    @Test
    @DisplayName("messages the executors already produce map to specific codes")
    fun executorMessagesMapToCodes() {
        assertEquals(
            ToolErrorCode.TARGET_UNAVAILABLE,
            ToolErrorMapper.classifyMessage(
                "Custom domain 'crawl' is registered but no target object is available."
            )
        )
        assertEquals(
            ToolErrorCode.TARGET_UNAVAILABLE,
            ToolErrorMapper.classifyMessage(
                "Command domain 'command' requires a registered CommandRunner target."
            )
        )
        assertEquals(
            ToolErrorCode.MISSING_REQUIRED_ARG,
            ToolErrorMapper.classifyMessage("Missing required parameter: sessionId")
        )
        assertEquals(
            ToolErrorCode.UNKNOWN_TOOL,
            ToolErrorMapper.classifyMessage("Unsupported domain: hello")
        )
        assertEquals(
            ToolErrorCode.UNKNOWN_ARGUMENT,
            ToolErrorMapper.classifyMessage("extraneous parameter 'foo' for bar")
        )
        assertEquals(
            ToolErrorCode.SESSION_NOT_FOUND,
            ToolErrorMapper.classifyMessage("Session not found: missing")
        )
        assertEquals(
            ToolErrorCode.MISSING_REQUIRED_ARG,
            ToolErrorMapper.classifyMessage("navigate requires 'url' or ('rawUrl','pageUrl')")
        )
    }

    @Test
    @DisplayName("exception types are classified before message matching")
    fun exceptionTypesAreClassified() {
        assertEquals(ToolErrorCode.TIMEOUT, ToolErrorMapper.classify(TimeoutException("boom")))
        assertEquals(
            ToolErrorCode.INVALID_ARGUMENT,
            ToolErrorMapper.classify(IllegalArgumentException("bad value"))
        )
        assertEquals(
            ToolErrorCode.TARGET_UNAVAILABLE,
            ToolErrorMapper.classify(UnsupportedOperationException("nope"))
        )
        assertEquals(
            ToolErrorCode.CDP_ERROR,
            ToolErrorMapper.classify(IOException("CDP websocket closed"))
        )
    }

    @Test
    @DisplayName("the cause chain is inspected, not only the outer message")
    fun causeChainIsInspected() {
        val wrapped = IllegalStateException("tool failed", TimeoutException("navigation timed out"))

        assertEquals(ToolErrorCode.TIMEOUT, ToolErrorMapper.classify(wrapped))
    }

    @Test
    @DisplayName("an unknown failure is INTERNAL, never a silent success")
    fun unknownFailureIsInternal() {
        assertEquals(ToolErrorCode.INTERNAL, ToolErrorMapper.classifyMessage("something odd happened"))
        assertEquals(ToolErrorCode.INTERNAL, ToolErrorMapper.classify(RuntimeException("?")))
    }

    @Test
    @DisplayName("codes carry retry and HTTP semantics, and RATE_LIMITED exists for Phase 4")
    fun codesCarrySemantics() {
        assertTrue(ToolErrorCode.RATE_LIMITED.retryable)
        assertEquals(429, ToolErrorCode.RATE_LIMITED.httpStatus)
        assertTrue(ToolErrorCode.SESSION_NOT_FOUND.hint.isNotBlank())
        assertTrue(ToolErrorCode.entries.all { it.hint.isNotBlank() }, "every code needs an actionable hint")
        assertEquals(
            ToolErrorCode.entries.size,
            ToolErrorCode.entries.map { it.wire }.toSet().size,
            "wire codes must be unique"
        )
    }
}
