package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration
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
    @DisplayName("upper-case credential keys in a non-JSON blob are redacted")
    fun redactsUpperCaseKeysInNonJsonBlobs() {
        for (key in listOf("APIKEY", "API_KEY", "AUTHORIZATION", "COOKIE")) {
            val redacted = AgentTaskEventSanitizer.arguments(
                "coding.shell",
                "not json {\"$key\":\"super-secret-value\"}",
            )!!
            assertFalse(redacted.contains("super-secret-value"), "$key was not redacted: $redacted")
            assertTrue(
                redacted.contains("\"$key\":\"***\""),
                "$key was not replaced by the marker: $redacted",
            )
        }
    }

    @Test
    @DisplayName("a half-escaped credential pair is still redacted")
    fun redactsHalfEscapedCredentialPair() {
        // The old regex attempted a match at every `"`, so a credential pair whose key opening
        // quote is escaped was still redacted. Skipping escaped opening quotes would leak it.
        val redacted = AgentTaskEventSanitizer.arguments(
            "coding.shell",
            """not json \"password": "hunter2"""",
        )!!
        assertFalse(redacted.contains("hunter2"), redacted)
        assertTrue(redacted.contains("\"***\""), "the value was not replaced by the marker: $redacted")
    }

    @Test
    @DisplayName("an escape-heavy unterminated blob stays linear")
    fun unterminatedEscapedBlobStaysLinear() {
        // Regression: advancing one character when no closing quote exists rescanned the whole tail
        // per quote, making this shape quadratic — ~210 ms at 32k repeats, ~8 s at 200k. The size
        // below is chosen so a quadratic scan exceeds the timeout while a linear one takes ~10 ms.
        val payload = "not json " + "\"\\".repeat(200_000)

        val redacted = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier {
            AgentTaskEventSanitizer.arguments("coding.shell", payload)!!
        })

        assertTrue(redacted.isNotEmpty())
    }

    @Test
    @DisplayName("a multi-kilobyte credential value must not overflow the sanitizer")
    fun longCredentialValuesDoNotOverflow() {
        // Regression: the old inline rule's value body `(?:[^"\\]|\\.)*` makes Java's
        // regex engine recurse once per character. A credential with a ~2 KB quoted
        // value was enough to throw StackOverflowError on the default 1 MB stack
        // while sanitizing a non-JSON argument blob.
        val longValue = "a".repeat(100_000)
        val payload = "not json " + """{"password":"$longValue","user":"ada"}"""

        val redacted = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier {
            AgentTaskEventSanitizer.arguments("coding.shell", payload)!!
        })

        assertFalse(redacted.contains(longValue), "the long value is gone")
        assertTrue(redacted.contains("\"password\":\"***\""), redacted)
        assertTrue(redacted.contains("\"user\":\"ada\""), "the rest of the blob survives: $redacted")
    }

    @Test
    @DisplayName("a long escaped credential value is redacted without overflowing")
    fun longEscapedCredentialValuesDoNotOverflow() {
        val longValue = "a\\\"b" + "c".repeat(100_000) // the raw text contains `\"`
        val payload = "not json {\"password\":\"$longValue\"}"

        val redacted = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier {
            AgentTaskEventSanitizer.arguments("coding.shell", payload)!!
        })

        assertFalse(redacted.contains(longValue), "the escaped value is gone")
        assertTrue(redacted.contains("\"password\":\"***\""), redacted)
    }

    @Test
    @DisplayName("a marker-heavy long value stays linear")
    fun adversarialLongValuesStayLinear() {
        // A greedy, non-recursive regex would avoid the overflow but backtrack
        // catastrophically here; the scan must stay linear.
        val noisy = "\"" + "token data ".repeat(20_000) + "\""
        val payload = """not json {"password":"hunter2"} """ + noisy

        val redacted = assertTimeout(Duration.ofSeconds(5), ThrowingSupplier {
            AgentTaskEventSanitizer.arguments("coding.shell", payload)!!
        })

        assertFalse(redacted.contains("hunter2"), "the credential before the noise is redacted")
        assertTrue(redacted.contains("\"password\":\"***\""), redacted)
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

    // ---- decision payloads (`detail`, issue #652) ----------------------------

    @Test
    @DisplayName("a decision payload keeps primitives and nested check records")
    fun detailKeepsStructuredPayload() {
        val detail = AgentTaskEventSanitizer.detail(
            mapOf(
                "passed" to false,
                "module" to "browser4-browser",
                "exitCode" to 1,
                "checks" to listOf(
                    mapOf("name" to "compile", "ran" to true, "ok" to false, "exitCode" to 1),
                    mapOf("name" to "test", "ran" to true, "ok" to true, "exitCode" to 0),
                ),
            ),
        )!!

        assertEquals(false, detail["passed"])
        assertEquals("browser4-browser", detail["module"])
        assertEquals(1, detail["exitCode"], "numbers stay numbers for a machine reader")
        @Suppress("UNCHECKED_CAST")
        val checks = detail["checks"] as List<Map<String, Any?>>
        assertEquals(listOf("compile", "test"), checks.map { it["name"] })
        assertEquals(false, checks[0]["ok"])
        assertFalse(detail.containsKey(AgentTaskEventSanitizer.DETAIL_TRUNCATED), "nothing was cut")
    }

    @Test
    @DisplayName("credential keys inside a decision payload are redacted")
    fun detailRedactsCredentialKeys() {
        val detail = AgentTaskEventSanitizer.detail(
            mapOf("reason" to "auth failed", "apiKey" to "sk-live-123", "nested" to mapOf("token" to "abc")),
        )!!

        assertEquals(AgentTaskEventSanitizer.REDACTED, detail["apiKey"])
        @Suppress("UNCHECKED_CAST")
        val nested = detail["nested"] as Map<String, Any?>
        assertEquals(AgentTaskEventSanitizer.REDACTED, nested["token"])
    }

    @Test
    @DisplayName("decision payload strings are collapsed and truncated like every other preview")
    fun detailPreviewsStrings() {
        val long = "line one\nline two " + "z".repeat(500)
        val detail = AgentTaskEventSanitizer.detail(mapOf("detail" to long))!!

        val preview = detail["detail"] as String
        assertTrue(preview.length <= AgentTaskEventSanitizer.MAX_PREVIEW_CHARS + 1, "${preview.length}")
        assertTrue(preview.endsWith("…"), preview)
        assertFalse(preview.contains("\n"), "a payload string must stay one line: $preview")
    }

    @Test
    @DisplayName("an oversized decision payload is trimmed and says so")
    fun detailIsBoundedAndMarkedTruncated() {
        // 20 items x ~240-char previews is the worst structural case (~5 KB): it fits the
        // per-collection limits and still blows the 4 KB wire budget, so the size bound
        // has to degrade the payload — halving the array rather than discarding it.
        val detail = AgentTaskEventSanitizer.detail(
            mapOf("violations" to (1..AgentTaskEventSanitizer.MAX_DETAIL_ITEMS).map { "v".repeat(300) + " #$it" }),
        )!!

        assertEquals(true, detail[AgentTaskEventSanitizer.DETAIL_TRUNCATED], "a cut must be visible")
        @Suppress("UNCHECKED_CAST")
        val kept = detail["violations"] as List<Any?>
        assertTrue(kept.isNotEmpty(), "degradation drops volume, not the whole payload")
        assertTrue(kept.size < AgentTaskEventSanitizer.MAX_DETAIL_ITEMS, "the array had to shrink: ${kept.size}")
        val size = ai.platon.pulsar.common.serialize.json.pulsarObjectMapper()
            .writeValueAsBytes(detail).size
        assertTrue(
            size <= AgentTaskEventSanitizer.MAX_DETAIL_BYTES,
            "a decision payload must stay bounded, got $size bytes",
        )
    }

    @Test
    @DisplayName("more keys/items than the ceiling are cut, and the cut is marked")
    fun detailDropsTheTailOfOversizedCollections() {
        val keys = (1..AgentTaskEventSanitizer.MAX_DETAIL_KEYS + 5).associate { "k$it" to "v" }
        val items = (1..AgentTaskEventSanitizer.MAX_DETAIL_ITEMS + 5).map { "item$it" }

        val byKeys = AgentTaskEventSanitizer.detail(keys)!!
        assertEquals(
            AgentTaskEventSanitizer.MAX_DETAIL_KEYS + 1,
            byKeys.size,
            "12 keys plus the truncation marker",
        )
        assertEquals(true, byKeys[AgentTaskEventSanitizer.DETAIL_TRUNCATED])

        val byItems = AgentTaskEventSanitizer.detail(mapOf("items" to items))!!
        @Suppress("UNCHECKED_CAST")
        val kept = byItems["items"] as List<Any?>
        assertEquals(AgentTaskEventSanitizer.MAX_DETAIL_ITEMS, kept.size)
        assertEquals(true, byItems[AgentTaskEventSanitizer.DETAIL_TRUNCATED])
    }

    @Test
    @DisplayName("an empty or nested-too-deep payload never crashes the log")
    fun detailHandlesEmptyAndDeepPayloads() {
        assertNull(AgentTaskEventSanitizer.detail(null))
        assertNull(AgentTaskEventSanitizer.detail(emptyMap<String, Any?>()))

        // Nested beyond the depth ceiling: the deepest collection is dropped and the cut
        // is reported instead of throwing or serializing an unbounded tree.
        val deep = mapOf(
            "a" to mapOf(
                "b" to mapOf(
                    "c" to mapOf("d" to "too deep"),
                ),
            ),
        )
        val detail = AgentTaskEventSanitizer.detail(deep)!!
        assertEquals(true, detail[AgentTaskEventSanitizer.DETAIL_TRUNCATED])
    }

    @Test
    @DisplayName("a tail-preserving preview keeps the failing lines at the end")
    fun previewKeepingTailKeepsTheVerdict() {
        // A coding tool result is read for its verdict: the compiler error and the
        // `Tests run:` line are at the END, the head is a session banner. The head-only
        // preview cut away exactly what a supervisor needed (issue #652, P1-1).
        val banner = "Session: devshell-3 · cwd=/w · " + "x".repeat(400)
        val diagnostic = "[ERROR] Foo.kt:42: unresolved reference: bar"
        val summary = "Tests run: 12, Failures: 1"

        val preview = AgentTaskEventSanitizer.previewKeepingTail("$banner $diagnostic $summary")!!

        assertTrue(preview.startsWith("Session: devshell-3"), "the head still gives context: $preview")
        assertTrue(preview.contains("…"), "the cut is visible: $preview")
        assertTrue(
            preview.endsWith(summary),
            "the verdict line at the tail must survive: $preview",
        )
        assertTrue(
            preview.length <= AgentTaskEventSanitizer.MAX_PREVIEW_CHARS,
            "the budget is shared, not exceeded: ${preview.length}",
        )
    }

    @Test
    @DisplayName("a tail-preserving preview never splits a surrogate pair and leaves short text alone")
    fun previewKeepingTailHandlesEdges() {
        assertEquals("a b c", AgentTaskEventSanitizer.previewKeepingTail("  a\n\n b\tc  "))
        assertNull(AgentTaskEventSanitizer.previewKeepingTail("   "))
        assertNull(AgentTaskEventSanitizer.previewKeepingTail(null))

        val grin = "\uD83D\uDE00"
        val long = "x".repeat(200) + grin + "y".repeat(200)
        val preview = AgentTaskEventSanitizer.previewKeepingTail(long, maxChars = 100)!!
        assertNoUnpairedSurrogate(preview)
        assertTrue(preview.length <= 100, "${preview.length}")
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
