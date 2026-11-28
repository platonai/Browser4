package ai.platon.pulsar.agentic.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration

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
    @DisplayName("a credential inside an embedded (escaped) JSON string is redacted too")
    fun redactsEscapedJsonStrings() {
        // This is the shape a trace line has on disk: the tool's own JSON lives inside the line's
        // JSON, so every quote of it is escaped. Without this rule the read-side redaction would
        // match nothing in the one case it exists for.
        val line = """{"seq":1,"arguments":"{\"password\":\"hunter2\",\"user\":\"ada\"}"}"""

        val redacted = SecretRedactor.redactText(line)

        assertFalse(redacted.contains("hunter2"), redacted)
        assertTrue(redacted.contains("""\"password\":\"***\""""), redacted)
        assertTrue(redacted.contains("ada"), "a non-secret value stays readable: $redacted")
        // The line must stay one valid JSON object after the substitution.
        assertNotNull(serdeJsonParse(redacted), "redaction must not break the record: $redacted")
    }

    /** Minimal JSON validity check: the payload must still parse. */
    private fun serdeJsonParse(text: String): Any? = runCatching {
        com.fasterxml.jackson.databind.ObjectMapper().readTree(text)
    }.getOrNull()

    @Test
    @DisplayName("a CLI-style flag is redacted too")
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

    @Test
    @DisplayName("a long quoted value must not overflow the stack")
    fun longQuotedValuesDoNotOverflowTheStack() {
        // Regression: the previous regex was `(?:[^"\\]|\\.)*?` over every quoted string, which
        // the Java engine recurses through per character. ~2 KB of quoted text was enough to
        // throw StackOverflowError on the default 1 MB thread stack; the system prompt of every
        // run is much larger than that, so the first prompt-dump redaction killed the task.
        val longValue = "a".repeat(100_000)
        val raw = "prefix \"$longValue\" {\"password\":\"hunter2\",\"user\":\"ada\"}"

        val redacted = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier { SecretRedactor.redactText(raw) })

        assertFalse(redacted.contains("hunter2"), "the secret is gone")
        assertTrue(redacted.contains("\"password\":\"***\""), "the pair keeps its shape")
        assertTrue(redacted.contains("a".repeat(1000)), "the long, unrelated value survives")
    }

    @Test
    @DisplayName("a long quoted value that contains marker words is still linear")
    fun adversarialLongValuesStayLinear() {
        // A greedy, non-recursive regex would avoid the overflow but backtrack catastrophically
        // on this input (measured 13 s for ~96 KB). A scan must stay linear.
        val noisy = "\"" + "token data ".repeat(20_000) + "\""
        val raw = "prefix $noisy {\"password\":\"hunter2\",\"user\":\"ada\"}"

        val redacted = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier { SecretRedactor.redactText(raw) })

        assertFalse(redacted.contains("hunter2"), "the secret after the noise is still redacted")
        assertTrue(redacted.contains("\"password\":\"***\""), "the pair keeps its shape")
    }

    @Test
    @DisplayName("a long escaped value must not overflow either")
    fun longEscapedValuesDoNotOverflowTheStack() {
        val longValue = "a".repeat(100_000)
        val line = """{"seq":1,"arguments":"{\"note\":\"$longValue\",\"password\":\"hunter2\"}"}"""

        val redacted = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier { SecretRedactor.redactText(line) })

        assertFalse(redacted.contains("hunter2"), "the escaped secret is gone")
        assertTrue(redacted.contains("""\"password\":\"***\""""), "the escaped pair keeps its shape")
        assertTrue(redacted.contains("a".repeat(1000)), "the long, unrelated value survives")
    }

    @Test
    @DisplayName("a long typed value for a credential field is redacted without a regex")
    fun longTypedValuesAreRedacted() {
        val longValue = "a".repeat(100_000)
        val raw = """{"selector":"input[type=password]","text":"$longValue"}"""

        val redacted = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier {
            SecretRedactor.redactForTrace("fill", raw)!!
        })

        assertFalse(redacted.contains(longValue), "the typed value is gone")
        assertTrue(redacted.contains("\"text\":\"***\""), redacted.take(200))
    }

    @Test
    @DisplayName("value shapes keep the behaviour the regex had")
    fun valueShapesKeepTheirBehaviour() {
        // Whitespace around the colon, a single-quoted value, a bare value, and a nested object.
        val raw = """{"password" : 'hunter2', "token": abc123, "user": {"id": 7}, "note": "kept"}"""

        val redacted = SecretRedactor.redactText(raw)

        assertFalse(redacted.contains("hunter2"), redacted)
        assertFalse(redacted.contains("abc123"), redacted)
        assertTrue(redacted.contains("\"password\" : \"***\""), redacted)
        assertTrue(redacted.contains("\"token\": \"***\""), redacted)
        assertTrue(redacted.contains("{\"id\": 7}"), "a non-secret nested object survives: $redacted")
        assertTrue(redacted.contains("\"note\": \"kept\""), redacted)
        // Idempotent: a second pass sees the same placeholders and changes nothing.
        assertEquals(redacted, SecretRedactor.redactText(redacted))
    }

    @Test
    @DisplayName("an unterminated string is left alone instead of throwing")
    fun unterminatedStringsAreLeftAlone() {
        assertEquals("""{"password": "hunter2""", SecretRedactor.redactText("""{"password": "hunter2"""))
        assertEquals("""a "quote""", SecretRedactor.redactText("""a "quote"""))
    }

    @Test
    @DisplayName("prose: YAML / key=value / Chinese credentials are redacted")
    fun redactsProsePairs() {
        assertEquals("password: ***", SecretRedactor.redactProse("password: hunter2"))
        assertEquals("- api_key=***", SecretRedactor.redactProse("- api_key=sk_live_abc123"))
        assertEquals("密码：***", SecretRedactor.redactProse("密码：abc123"))
        assertEquals("密码是***，然后保存", SecretRedactor.redactProse("密码是abc123，然后保存"))
        // a quoted value is always a literal
        assertEquals("token: ***", SecretRedactor.redactProse("token: 'abc'"))
    }

    @Test
    @DisplayName("prose: code that merely mentions a credential name stays readable")
    fun proseKeepsNonLiteralValues() {
        val code = "const token = settings.authToken; password = readFromEnv()"
        assertEquals(code, SecretRedactor.redactProse(code))
        // too short, and without a digit, to look like a secret
        assertEquals("password: abc", SecretRedactor.redactProse("password: abc"))
    }

    @Test
    @DisplayName("prose: Bearer headers, signed URLs and well-known key formats are redacted")
    fun redactsProseTokensAndUrls() {
        assertEquals("Authorization: Bearer ***", SecretRedactor.redactProse("Authorization: Bearer abc.def.ghi"))
        assertEquals(
            "GET https://example.com/v1/items?page=2&access_token=***",
            SecretRedactor.redactProse("GET https://example.com/v1/items?page=2&access_token=sk_live_123"),
        )
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("key ***", SecretRedactor.redactProse("key $jwt"))
        assertEquals("use *** now", SecretRedactor.redactProse("use sk-abcdefghijklmnopqrstuvwxyz now"))
        assertEquals("***", SecretRedactor.redactProse("ghp_${"a".repeat(36)}"))
    }

    @Test
    @DisplayName("prose: redaction stays linear on adversarial input")
    fun proseRedactionStaysLinear() {
        val unterminatedQuote = "password: \"" + "a".repeat(200_000)
        val manyPairs = "password: aaaaaaa1 ".repeat(20_000)

        val redacted = assertTimeout(
            Duration.ofSeconds(5),
            ThrowingSupplier { SecretRedactor.redactProse("$unterminatedQuote $manyPairs") },
        )

        assertFalse(redacted.contains("aaaaaaa1"), "every pair must be redacted")
    }
}
