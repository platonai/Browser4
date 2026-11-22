package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.common.SecretRedactor
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration

/**
 * `cli-prompt/<ts>.<seq>.request.json` is the exact prompt the model saw — the richest forensic
 * artifact of a run, and the one that carries every credential a run touched: the user's task and
 * steering lines, the model's tool requests, and every tool result in full.
 *
 * These tests pin the dumped record: same shape, redacted secrets. The API serves these files, so
 * the write-side rule is the only thing between a password and an HTTP response.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("Prompt dump redaction")
class PromptTraceRedactionTest {

    @Test
    @DisplayName("a user task that carries a credential is redacted")
    fun userMessagesAreRedacted() {
        val payload = promptMessagePayload(
            UserMessage.from("""Deploy with {"api_key":"sk_live_123"} and --token abc123""")
        )

        assertEquals("user", payload["type"])
        val text = payload["text"] as String
        assertFalse(text.contains("sk_live_123"), text)
        assertFalse(text.contains("abc123"), text)
        assertTrue(text.contains("Deploy with"), "the instruction stays readable: $text")
    }

    @Test
    @DisplayName("a tool request keeps its name and loses the credential in its arguments")
    fun toolRequestsAreRedacted() {
        val payload = promptMessagePayload(
            AiMessage.from(
                listOf(
                    ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("coding.shell")
                        .arguments("""{"command":"login --password hunter2"}""")
                        .build()
                )
            )
        )

        assertEquals("ai", payload["type"])
        val requests = payload["toolExecutionRequests"] as List<*>
        val request = requests.single() as Map<*, *>
        assertEquals("coding.shell", request["name"], "the tool name is not sensitive")
        assertFalse((request["arguments"] as String).contains("hunter2"), request.toString())
    }

    @Test
    @DisplayName("a typed credential for a password field is hidden by its selector")
    fun typedCredentialsAreRedacted() {
        val payload = promptMessagePayload(
            AiMessage.from(
                listOf(
                    ToolExecutionRequest.builder()
                        .id("call-2")
                        .name("fill")
                        .arguments("""{"selector":"input[name=passwd]","text":"hunter2"}""")
                        .build()
                )
            )
        )

        assertFalse(payload.toString().contains("hunter2"), payload.toString())
    }

    @Test
    @DisplayName("a tool result keeps its output and loses an embedded token")
    fun toolResultsAreRedacted() {
        val payload = promptMessagePayload(
            ToolExecutionResultMessage.from(
                "call-3",
                "coding.shell",
                """exit 0 — Tests run: 12 {"access_token":"ghp_SECRET"}""",
            )
        )

        assertEquals("tool_result", payload["type"])
        assertEquals("coding.shell", payload["toolName"])
        val text = payload["text"] as String
        assertFalse(text.contains("ghp_SECRET"), text)
        assertTrue(text.contains("Tests run: 12"), "the diagnostics survive: $text")
    }

    @Test
    @DisplayName("the system prompt is redacted without changing the record shape")
    fun systemMessagesKeepTheirShape() {
        val system = promptMessagePayload(SystemMessage.from("""credentials: {"secret":"s3cr3t"}"""))

        assertEquals(setOf("type", "text"), system.keys)
        assertFalse((system["text"] as String).contains("s3cr3t"), system.toString())
        assertTrue((system["text"] as String).contains("credentials"), "the rest of the prompt survives")
    }

    @Test
    @DisplayName("a clean message is dumped unchanged — redaction is not a rewrite")
    fun cleanMessagesAreUnchanged() {
        val payload = promptMessagePayload(UserMessage.from("list the files in src/main"))

        assertEquals("""list the files in src/main""", payload["text"])
        assertEquals(SecretRedactor.redactText("list the files in src/main"), payload["text"])
    }

    @Test
    @DisplayName("a large system prompt is dumped without overflowing the stack")
    fun largeSystemPromptsAreDumped() {
        // Regression: the prompt-dump redactor used a recursive regex; a quoted string of a few
        // kilobytes in the system prompt threw StackOverflowError before the first model request,
        // which failed every traced run on the default JVM stack.
        val longValue = "a".repeat(100_000)
        val text = "skills \"$longValue\" credentials: {\"secret\":\"s3cr3t\"}"

        val payload = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier {
            promptMessagePayload(SystemMessage.from(text))
        })

        val dumped = payload["text"] as String
        assertFalse(dumped.contains("s3cr3t"), "the secret is still redacted")
        assertTrue(dumped.contains("skills"), "the rest of the prompt survives")
        assertTrue(dumped.contains("a".repeat(1000)), "the large quoted value survives")
    }
}
