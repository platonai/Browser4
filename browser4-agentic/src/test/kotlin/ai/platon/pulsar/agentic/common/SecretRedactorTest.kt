package ai.platon.pulsar.agentic.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * One rule set guards two sinks: the event stream (rendered into terminals and CI logs) and the
 * run trace (the full payloads on the backend disk). These tests pin the rules themselves, so a
 * new sink only has to call them (issue #652, gap 5).
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("Shared secret redaction")
class SecretRedactorTest {

    @Test
    @DisplayName("credential keys are recognised regardless of case, separators and nesting")
    fun recognisesCredentialKeys() {
        for (key in listOf("password", "PASSWORD", "api_key", "api-key", "apiKey", "accessToken", "Cookie")) {
            assertTrue(SecretRedactor.isSensitiveKey(key), key)
        }
        assertTrue(
            SecretRedactor.isSensitiveKey("credentials.token"),
            "a dotted key names the value in its last segment",
        )
        for (key in listOf("text", "selector", "url", "path", "command")) {
            assertFalse(SecretRedactor.isSensitiveKey(key), key)
        }
    }

    @Test
    @DisplayName("JSON-shaped payloads keep their shape but lose the secret")
    fun redactsJsonShapedText() {
        val raw = """{"selector":"#login","password":"hunter2","user":"ada"}"""

        val redacted = SecretRedactor.redactText(raw)

        assertFalse(redacted.contains("hunter2"), redacted)
        assertTrue(redacted.contains("\"password\":\"***\""), redacted)
        assertTrue(redacted.contains("\"user\":\"ada\""), "a non-secret value stays readable: $redacted")
    }

    @Test
    @DisplayName("CLI-style flags are redacted too")
    fun redactsCliFlags() {
        val raw = """cli login --user ada --password "hunter2" --token abc123"""

        val redacted = SecretRedactor.redactText(raw)

        assertFalse(redacted.contains("hunter2"), redacted)
        assertFalse(redacted.contains("abc123"), redacted)
        assertTrue(redacted.contains("--password ***"), redacted)
        assertTrue(redacted.contains("--user ada"), redacted)
    }

    @Test
    @DisplayName("typed text is redacted only when the selector names a credential field")
    fun redactsTypedTextForCredentialSelectorsOnly() {
        val passwordFill = """{"selector":"input[type=password]","text":"hunter2"}"""
        val searchFill = """{"selector":"#search","text":"wireless headphones"}"""

        val redactedPassword = SecretRedactor.redactForTrace("fill", passwordFill)!!
        assertFalse(redactedPassword.contains("hunter2"), redactedPassword)
        assertTrue(redactedPassword.contains("\"text\":\"***\""), redactedPassword)

        val redactedSearch = SecretRedactor.redactForTrace("fill", searchFill)!!
        assertTrue(redactedSearch.contains("wireless headphones"), redactedSearch)
    }

    @Test
    @DisplayName("a tool result keeps its diagnostics but loses an embedded token")
    fun redactsToolResults() {
        // A tool result is prose *around* machine-shaped payloads; the diagnostics must survive
        // while the credential inside either shape goes away.
        val result = """Tests run: 12, Failures: 0 — response {"access_token":"ghp_SECRETVALUE"} """ +
            """uploaded with --token ghp_OTHER"""

        val redacted = SecretRedactor.redactForTrace("coding.shell", result)!!

        assertFalse(redacted.contains("ghp_SECRETVALUE"), redacted)
        assertFalse(redacted.contains("ghp_OTHER"), redacted)
        assertTrue(redacted.contains("Tests run: 12, Failures: 0"), "the diagnostics survive: $redacted")
        assertTrue(redacted.contains("\"access_token\":\"***\""), redacted)
    }

    @Test
    @DisplayName("a URL keeps its identity and loses only the credential parameters")
    fun redactsCredentialQueryParameters() {
        val url = "https://api.example.com/v1/items?page=2&access_token=sk_live_123&sig=abc#frag"

        val redacted = SecretRedactor.redactUrl(url)!!

        assertFalse(redacted.contains("sk_live_123"), redacted)
        assertFalse(redacted.contains("abc#frag"), redacted)
        assertTrue(redacted.contains("page=2"), "a normal parameter stays: $redacted")
        assertTrue(redacted.contains("access_token=***"), redacted)
        assertTrue(redacted.endsWith("#frag"), "the fragment is kept: $redacted")
        assertEquals("https://example.com/a", SecretRedactor.redactUrl("https://example.com/a"))
        assertEquals(null, SecretRedactor.redactUrl(null))
    }

    @Test
    @DisplayName("redaction never throws and never mangles what it cannot classify")
    fun redactionIsBestEffort() {
        val unclassifiable = """{not json at all: "but quoted"}"""
        assertEquals(SecretRedactor.redactText(unclassifiable), SecretRedactor.redactText(unclassifiable))
        assertEquals("", SecretRedactor.redactText(""))
        assertEquals(null, SecretRedactor.redactForTrace("fill", null))
        assertEquals("", SecretRedactor.redactForTrace("fill", ""))
        assertEquals("https://example.com/?q=1", SecretRedactor.redactUrl("https://example.com/?q=1"))
    }
}
