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
    @DisplayName("truncation never splits a surrogate pair")
    fun truncationKeepsSurrogatePairsWhole() {
        val grin = "\uD83D\uDE00" // U+1F600, an astral character (a surrogate pair)

        // 4 code units: 'a', 'b', high, low. Cutting at 3 would keep the high
        // half only — the `\uD83D` that a strict JSON parser rejects.
        val cut = AgentTaskEventSanitizer.preview("ab${grin}cd", maxChars = 3)!!
        assertEquals("ab…", cut)
        assertNoUnpairedSurrogate(cut)

        // A pair that fits the budget is kept verbatim.
        val kept = AgentTaskEventSanitizer.preview("ab${grin}cd", maxChars = 4)!!
        assertEquals("ab$grin…", kept)
        assertNoUnpairedSurrogate(kept)

        // A preview whose whole budget is one surrogate pair cannot fit it, so
        // the pair is dropped instead of halved.
        val tooSmall = AgentTaskEventSanitizer.preview(grin, maxChars = 1)!!
        assertNoUnpairedSurrogate(tooSmall)
        assertEquals("…", tooSmall)
    }

    @Test
    @DisplayName("unpaired surrogates from the source text are replaced, not escaped")
    fun replacesUnpairedSurrogates() {
        val loneHigh = "a\uD83Db"
        assertEquals("a\uFFFDb", AgentTaskEventSanitizer.preview(loneHigh))
        assertEquals("a\uFFFD", AgentTaskEventSanitizer.preview("a\uD83D"))
        assertEquals("\uFFFD" + "b", AgentTaskEventSanitizer.preview("\uDE00b"))
        // The 240-char default is the case that broke the live view: a long
        // line whose 240th code unit is the high half of an emoji.
        val longLine = "x".repeat(AgentTaskEventSanitizer.MAX_PREVIEW_CHARS - 1) + "\uD83D\uDE00tail"
        assertNoUnpairedSurrogate(AgentTaskEventSanitizer.preview(longLine)!!)
    }

    @Test
    @DisplayName("a truncated arguments preview stays strict-JSON serializable")
    fun argumentsPreviewSerializesWithoutLoneSurrogateEscapes() {
        // A real serialized event: Jackson writes an unpaired high surrogate as
        // the escape `\uD83D`, which strict parsers (the CLI follower's
        // serde_json) reject with "unexpected end of hex escape".
        // `{"content":"` is 12 code units, so 227 filler chars put the high
        // surrogate exactly on the 240-unit truncation boundary.
        val filler = "y".repeat(AgentTaskEventSanitizer.MAX_PREVIEW_CHARS - 13)
        val payload = """{"content":"${filler}\uD83D\uDE00 more"}"""
        val arguments = AgentTaskEventSanitizer.arguments("coding.write", payload)!!
        assertNoUnpairedSurrogate(arguments)

        val event = AgentTaskEvent(type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "coding.write", arguments = arguments)
        val json = String(
            ai.platon.pulsar.common.serialize.json.pulsarObjectMapper().writeValueAsBytes(event),
            Charsets.UTF_8,
        )
        assertFalse(
            Regex("""\\u[dD][89abAB][0-9a-fA-F]{2}""").containsMatchIn(json),
            "an unpaired surrogate escape would break every strict JSON reader: $json",
        )
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

    /** A code unit that is half of a surrogate pair cannot be represented in JSON. */
    private fun assertNoUnpairedSurrogate(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue(
                    i + 1 < text.length && Character.isLowSurrogate(text[i + 1]),
                    "unpaired high surrogate at $i in '$text'",
                )
                i += 2
                continue
            }
            assertFalse(Character.isLowSurrogate(c), "unpaired low surrogate at $i in '$text'")
            i++
        }
    }
}
