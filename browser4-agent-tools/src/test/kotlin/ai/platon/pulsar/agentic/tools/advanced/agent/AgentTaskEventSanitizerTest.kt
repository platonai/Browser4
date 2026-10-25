package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("Unit")
@Tag("Fast")
class AgentTaskEventSanitizerTest {

    @Test
    @DisplayName("preview collapses whitespace and truncates with an ellipsis")
    fun previewCollapsesAndTruncates() {
        assertEquals("a b c", AgentTaskEventSanitizer.preview("  a\n\n b\tc  "))
        assertEquals(
            "abcdef",
            AgentTaskEventSanitizer.preview("abcdef", maxChars = 6),
            "text exactly at the limit is not truncated",
        )
        assertEquals("abcde…", AgentTaskEventSanitizer.preview("abcdef", maxChars = 5))
        assertEquals("abc", AgentTaskEventSanitizer.preview("abc", maxChars = 100))
        assertNull(AgentTaskEventSanitizer.preview("   \n  "))
        assertNull(AgentTaskEventSanitizer.preview(null))
    }

    @Test
    @DisplayName("credential argument keys are redacted")
    fun redactsCredentialKeys() {
        val redacted = AgentTaskEventSanitizer.arguments(
            "fill",
            """{"selector":"#login","password":"hunter2"}""",
        )

        assertTrue(redacted!!.contains("\"password\":\"***\""), redacted)
        assertFalse(redacted.contains("hunter2"), redacted)
    }

    @Test
    @DisplayName("credential keys are matched regardless of case and separators")
    fun redactsCredentialKeyVariants() {
        for (key in listOf("apiKey", "api_key", "API-KEY", "access_token", "refreshToken", "Cookie")) {
            val redacted = AgentTaskEventSanitizer.arguments(
                "b4.run",
                """{"$key":"super-secret-value"}""",
            )!!
            assertFalse(
                redacted.contains("super-secret-value"),
                "$key was not redacted: $redacted",
            )
        }
    }

    @Test
    @DisplayName("credentials nested in objects and arrays are redacted")
    fun redactsNestedCredentials() {
        val redacted = AgentTaskEventSanitizer.arguments(
            "coding.write",
            """{"payload":{"auth":{"token":"abc123"}},"list":[{"secret":"nope"}]}""",
        )!!

        assertFalse(redacted.contains("abc123"), redacted)
        assertFalse(redacted.contains("nope"), redacted)
        assertTrue(redacted.contains("***"), redacted)
    }

    @Test
    @DisplayName("typed text is hidden when the selector names a credential field")
    fun redactsTypedTextForCredentialSelectors() {
        val redacted = AgentTaskEventSanitizer.arguments(
            "fill",
            """{"selector":"input[type=password]","text":"hunter2"}""",
        )!!

        assertFalse(redacted.contains("hunter2"), redacted)
        assertTrue(redacted.contains("\"text\":\"***\""), redacted)
    }

    @Test
    @DisplayName("typed text stays visible for an ordinary field")
    fun keepsTypedTextForOrdinarySelectors() {
        val redacted = AgentTaskEventSanitizer.arguments(
            "type",
            """{"selector":"#search","text":"wireless headphones"}""",
        )!!

        assertTrue(redacted.contains("wireless headphones"), redacted)
    }

    @Test
    @DisplayName("a non-JSON argument blob is still redacted by pattern")
    fun redactsNonJsonArguments() {
        val redacted = AgentTaskEventSanitizer.arguments(
            "coding.shell",
            """--header "Authorization": "Bearer xyz" --password "hunter2"""",
        )!!

        assertFalse(redacted.contains("hunter2"), redacted)
        assertFalse(redacted.contains("xyz"), redacted)
    }

    @Test
    @DisplayName("an empty argument object is omitted instead of stored as {}")
    fun omitsEmptyArguments() {
        assertNull(AgentTaskEventSanitizer.arguments("coding.read", "{}"))
        assertNull(AgentTaskEventSanitizer.arguments("coding.read", "   "))
        assertNull(AgentTaskEventSanitizer.arguments("coding.read", null))
    }

    @Test
    @DisplayName("long arguments are truncated after redaction")
    fun truncatesLongArguments() {
        val payload = """{"query":"${"x".repeat(500)}"}"""
        val preview = AgentTaskEventSanitizer.arguments("coding.grep", payload)!!

        assertTrue(preview.length <= AgentTaskEventSanitizer.MAX_PREVIEW_CHARS + 1, "${preview.length}")
        assertTrue(preview.endsWith("…"), preview)
    }

    @Test
    @DisplayName("sensitive key detection normalizes case and separators")
    fun detectsSensitiveKeys() {
        assertTrue(AgentTaskEventSanitizer.isSensitiveKey("password"))
        assertTrue(AgentTaskEventSanitizer.isSensitiveKey("API_KEY"))
        assertTrue(AgentTaskEventSanitizer.isSensitiveKey("access-token"))
        assertFalse(AgentTaskEventSanitizer.isSensitiveKey("text"))
        assertFalse(AgentTaskEventSanitizer.isSensitiveKey("selector"))
    }
}
