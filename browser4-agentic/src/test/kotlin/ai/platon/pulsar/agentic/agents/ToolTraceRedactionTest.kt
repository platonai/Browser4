package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * `cli-tool-trace.jsonl` is the full audit trail of a run: complete tool arguments, complete
 * tool output. That also made it the one place a credential was stored in plaintext — a password
 * typed into a login form, a token passed to a shell command (issue #652, gap 5).
 *
 * These tests pin the record the tracer writes: same shape, redacted secrets. The tracer's own
 * code is just an append of this map, which is why testing the map is testing the file.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("Tool trace redaction")
class ToolTraceRedactionTest {

    private fun payload(tool: String?, arguments: String? = "{}", result: String? = "ok") =
        toolTracePayload(
            seq = 7,
            tool = tool,
            argumentsJson = arguments,
            resultText = result,
            durationMs = 42,
            timestamp = "2026-11-22T00:00:00Z",
        )

    @Test
    @DisplayName("the record keeps its shape — every existing reader still works")
    fun theRecordShapeIsUnchanged() {
        val record = payload("coding.read", """{"path":"a.kt"}""", "file contents")

        assertEquals(
            listOf("timestamp", "seq", "tool", "arguments", "durationMs", "resultText"),
            record.keys.toList(),
        )
        assertEquals(7, record["seq"])
        assertEquals("coding.read", record["tool"])
        assertEquals(42L, record["durationMs"])
    }

    @Test
    @DisplayName("a credential in the arguments never reaches the file")
    fun argumentsAreRedacted() {
        val record = payload("fill", """{"selector":"#login","password":"hunter2","user":"ada"}""")

        val arguments = record["arguments"] as String
        assertFalse(arguments.contains("hunter2"), arguments)
        assertTrue(arguments.contains("\"password\":\"***\""), arguments)
        assertTrue(arguments.contains("\"user\":\"ada\""), "a non-secret argument stays readable: $arguments")
    }

    @Test
    @DisplayName("a typed credential for a password field never reaches the file")
    fun typedCredentialsAreRedacted() {
        // The key is `text`, so only the selector says this value is a credential.
        val record = payload("fill", """{"selector":"input[type=password]","text":"hunter2"}""")

        assertFalse((record["arguments"] as String).contains("hunter2"), record.toString())
    }

    @Test
    @DisplayName("a token echoed by a tool result never reaches the file, its diagnostics do")
    fun resultTextIsRedacted() {
        val record = payload(
            "coding.shell",
            """{"command":"gh auth status"}""",
            """Tests run: 12, Failures: 0 — uploaded {"access_token":"ghp_SECRETVALUE"}""",
        )

        val result = record["resultText"] as String
        assertFalse(result.contains("ghp_SECRETVALUE"), result)
        assertTrue(result.contains("Tests run: 12, Failures: 0"), result)
    }

    @Test
    @DisplayName("an empty argument blob is still recorded as an object")
    fun emptyArgumentsStayAnObject() {
        // `arguments` is documented as JSON text; a null must not become the string "null".
        assertEquals("{}", payload("system.taskComplete", arguments = null)["arguments"])
        assertEquals(null, payload("system.taskComplete", result = null)["resultText"])
    }
}
