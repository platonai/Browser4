package ai.platon.pulsar.agentic.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
        assertEquals(
            ToolErrorCode.MISSING_REQUIRED_ARG,
            ToolErrorMapper.classifyMessage("eval requires 'expression' or ('expression','selector')"),
            "a missing alternative argument must not fall through to INTERNAL"
        )
        assertEquals(
            ToolErrorCode.MISSING_REQUIRED_ARG,
            ToolErrorMapper.classifyMessage("click requires 'selector' plus optionally 'count', 'modifier', or 'button'")
        )
        assertEquals(
            ToolErrorCode.CDP_ERROR,
            ToolErrorMapper.classifyMessage("browser_click failed: No node with given id found"),
            "a stale DOM node reference is retryable, not an internal error",
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
    @DisplayName("a target lookup miss is a permanent not-found, never a retryable timeout")
    fun targetLookupMissIsNotATimeout() {
        val refMiss = "browser_evaluate failed: Element not found for ref backend:999. " +
            "Refs expire after page changes — re-run `snapshot` to get fresh refs."

        assertEquals(ToolErrorCode.TARGET_NOT_FOUND, ToolErrorMapper.classifyMessage(refMiss))
        assertEquals(ToolErrorCode.TARGET_NOT_FOUND, ToolErrorMapper.classifyMessage("No element matches selector \"#nope\"."))
        assertEquals(ToolErrorCode.TARGET_NOT_FOUND, ToolErrorMapper.classifyMessage("Frame not found: #no-such-frame."))
        assertEquals(
            ToolErrorCode.TARGET_NOT_FOUND,
            ToolErrorMapper.classifyMessage(
                "The selected frame 'payframe' is not reachable: the page navigated."
            )
        )
        assertEquals(
            ToolErrorCode.TARGET_NOT_FOUND,
            ToolErrorMapper.classifyMessage("Option target not found: #size")
        )
        assertFalse(
            ToolErrorCode.TARGET_NOT_FOUND.retryable,
            "a stale ref never resolves by retrying the same call"
        )
    }

    @Test
    @DisplayName("a parameter named waitTimeout cannot make a failure look like a timeout")
    fun parameterNamesDoNotDecideTheCode() {
        // Regression: the dispatcher appends the tool description to an error,
        // and the evaluate tool documents a `waitTimeout` parameter — the bare
        // "timeout" substring match turned a stale-ref failure into a retryable
        // TIMEOUT (HTTP 504, "retry with a longer timeout").
        val composed = "browser_evaluate failed: Element not found for ref backend:999. " +
            "Refs expire after page changes — re-run `snapshot` to get fresh refs. " +
            "help: Evaluate JavaScript in the page. Arguments: waitTimeout: How long to wait for the selector."

        assertEquals(ToolErrorCode.TARGET_NOT_FOUND, ToolErrorMapper.classifyMessage(composed))
        assertNotEquals(ToolErrorCode.TIMEOUT, ToolErrorMapper.classifyMessage("waitTimeout is not a number"))

        // A real timeout report still classifies as one — the word, not the
        // substring, is what counts.
        assertEquals(ToolErrorCode.TIMEOUT, ToolErrorMapper.classifyMessage("navigation timed out after 30s"))
        assertEquals(ToolErrorCode.TIMEOUT, ToolErrorMapper.classifyMessage("timeout while waiting for the page"))
    }

    @Test
    @DisplayName("a pending native dialog is its own non-retryable state")
    fun dialogPendingIsItsOwnCode() {
        val parkedClick = "browser_click failed: The click opened a native confirm dialog: \"Confirm action\" " +
            "and is paused until the dialog is handled. Run 'dialog-accept' (or 'dialog-dismiss') to let " +
            "the click finish; do not re-run the click."

        assertEquals(ToolErrorCode.DIALOG_PENDING, ToolErrorMapper.classifyMessage(parkedClick))
        assertEquals(
            ToolErrorCode.DIALOG_PENDING,
            ToolErrorMapper.classifyMessage("Page is blocked by a native confirm dialog: \"Confirm action\".")
        )
        assertFalse(ToolErrorCode.DIALOG_PENDING.retryable, "re-running the action would duplicate its effect")
    }

    @Test
    @DisplayName("an X-SQL statement rejection is a client-input error, not a server fault")
    fun rejectedXSqlIsAClientError() {
        assertEquals(
            ToolErrorCode.INVALID_ARGUMENT,
            ToolErrorMapper.classifyMessage("Only select statements are supported")
        )
        assertEquals(
            ToolErrorCode.INVALID_ARGUMENT,
            ToolErrorMapper.classifyMessage("Only one statement is supported")
        )
        assertEquals(400, ToolErrorCode.INVALID_ARGUMENT.httpStatus)
    }

    @Test
    @DisplayName("a coded failure keeps its own code through a wrapper")
    fun codedNotFoundKeepsItsCode() {
        val failure = TargetNotFoundException("Element not found for ref e9999.")

        assertEquals(ToolErrorCode.TARGET_NOT_FOUND, ToolErrorMapper.classify(failure))
        assertEquals(
            ToolErrorCode.TARGET_NOT_FOUND,
            ToolErrorMapper.classify(IllegalStateException("wrapped", failure)),
            "a wrapper must not lose the classification of its cause"
        )
        assertTrue(
            failure !is IllegalArgumentException,
            "an IllegalArgumentException is read as a usage error and gets the tool signature dump"
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
    @DisplayName("an explicit code wins over every inference, including the message")
    fun explicitCodeWins() {
        // A layer that has already classified the failure must not have to word its message
        // so the inference guesses right. The `formats` layer needs this: "this build cannot
        // deliver the format" and "the tool ran and failed" are the same shape of exception
        // with two very different answers (503 vs 502), and both read like ordinary prose.
        val unavailable = coded(ToolErrorCode.TARGET_UNAVAILABLE, "zebra: unavailable in this build")
        val failed = coded(ToolErrorCode.UPSTREAM_ERROR, "zebra: something went wrong")

        assertEquals(ToolErrorCode.TARGET_UNAVAILABLE, ToolErrorMapper.classify(unavailable))
        assertEquals(ToolErrorCode.UPSTREAM_ERROR, ToolErrorMapper.classify(failed))

        // Even when the wording points elsewhere: "timed out" alone would be 504, and an
        // explicit 503 must survive it.
        assertEquals(
            ToolErrorCode.TARGET_UNAVAILABLE,
            ToolErrorMapper.classify(coded(ToolErrorCode.TARGET_UNAVAILABLE, "the call timed out")),
        )
        // And through a cause chain, so a wrapper cannot lose the classification.
        assertEquals(
            ToolErrorCode.UPSTREAM_ERROR,
            ToolErrorMapper.classify(IllegalStateException("outer", failed)),
        )
    }

    /** A failure that carries its own code; the shape a classifying layer produces. */
    private fun coded(code: ToolErrorCode, message: String): Throwable =
        object : RuntimeException(message), CodedFailure {
            override val code: ToolErrorCode = code
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

    @Test
    @DisplayName("a refused attach endpoint is not a retryable CDP error")
    fun refusedEndpointIsNotACdpError() {
        // The exact message the attach path renders. Every attach message names a
        // "CDP endpoint", so the broad `"cdp" in text` rule used to win first and
        // answer CDP_ERROR ("retrying may help") for a connection that nothing is
        // listening on — advice that can never work.
        val unreachable = "attach_browser failed: CDP endpoint http://127.0.0.1:19999 is not reachable: " +
            "nothing is listening on that address (connection refused). " +
            "Start the target browser with --remote-debugging-port and retry attach."

        assertEquals(ToolErrorCode.ENDPOINT_UNREACHABLE, ToolErrorMapper.classifyMessage(unreachable))
        assertFalse(
            ToolErrorCode.ENDPOINT_UNREACHABLE.retryable,
            "repeating the same attach against a dead endpoint cannot succeed"
        )

        // A refused connection is still an upstream transport failure elsewhere —
        // the point is that the specific rule is reached before the generic one.
        assertEquals(
            ToolErrorCode.UPSTREAM_ERROR,
            ToolErrorMapper.classifyMessage("fetch failed: connection refused to the origin server")
        )
    }

    @Test
    @DisplayName("a non-CDP endpoint and a page-less browser get their own codes")
    fun otherEndpointConditionsAreDistinct() {
        assertEquals(
            ToolErrorCode.ENDPOINT_NOT_CDP,
            ToolErrorMapper.classifyMessage(
                "attach_browser failed: CDP endpoint http://127.0.0.1:19888 is reachable but is not a " +
                    "Chrome DevTools discovery endpoint (GET /json/version → HTTP 404)."
            )
        )
        assertEquals(
            ToolErrorCode.ENDPOINT_NO_PAGE_TARGETS,
            ToolErrorMapper.classifyMessage(
                "attach_browser failed: CDP endpoint http://127.0.0.1:19889 is reachable but has no page " +
                    "targets (browser=Chrome/999.0.0.0 pages=0). Open a tab in the target browser, then retry attach"
            )
        )
        // The three endpoint codes are distinct states, so a machine consumer can
        // branch without string-matching the prose.
        assertEquals(
            3,
            setOf(
                ToolErrorCode.ENDPOINT_UNREACHABLE,
                ToolErrorCode.ENDPOINT_NOT_CDP,
                ToolErrorCode.ENDPOINT_NO_PAGE_TARGETS,
            ).size
        )
    }

    @Test
    @DisplayName("an attach failure carries its observed code through a wrapper")
    fun attachFailureKeepsItsCode() {
        val failure = AttachFailureException(
            "CDP endpoint http://127.0.0.1:19999 is not reachable: nothing is listening on that address.",
            ToolErrorCode.ENDPOINT_UNREACHABLE,
        )

        assertEquals(ToolErrorCode.ENDPOINT_UNREACHABLE, ToolErrorMapper.classify(failure))
        assertEquals(
            ToolErrorCode.ENDPOINT_UNREACHABLE,
            ToolErrorMapper.classify(IllegalStateException("attach_browser failed", failure)),
            "a wrapper must not lose the classification of its cause"
        )
        assertFalse(
            failure is IllegalArgumentException,
            "an attach failure is not a usage error: the call's shape was valid, so it must not get the signature dump"
        )
    }
}
