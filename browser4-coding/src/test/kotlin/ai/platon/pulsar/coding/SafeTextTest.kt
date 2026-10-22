package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [SafeText] — the code-point-safe truncation used by every
 * shell-output / tool-result / digest cut.
 *
 * Regression: plain `take(800)` / `takeLast(400)` split an emoji surrogate pair,
 * and the lone surrogate left behind is not encodable as UTF-8 — it made every
 * later `Files.writeString` of the prompt dump fail with
 * `UnmappableCharacterException: Input length = 1` (39 consecutive rounds in the
 * observed run).
 */
@DisplayName("SafeText surrogate-safe truncation")
class SafeTextTest {

    /** A single code point encoded as a surrogate pair. */
    private val emoji = "😀"

    @Test
    @DisplayName("take never splits a surrogate pair")
    fun takeKeepsPairsIntact() {
        val text = "ab${emoji}cd" // a b [high] [low] c d

        assertEquals("ab", SafeText.take(text, 3), "cutting at the pair must drop both halves")
        assertEquals("ab$emoji", SafeText.take(text, 4))
        assertEquals(text, SafeText.take(text, 100))
        assertWellFormed(SafeText.take(text, 3))
        assertWellFormed(SafeText.take(text, 4))
    }

    @Test
    @DisplayName("takeLast never splits a surrogate pair")
    fun takeLastKeepsPairsIntact() {
        val text = "ab${emoji}cd"

        assertEquals("cd", SafeText.takeLast(text, 3), "cutting at the pair must drop both halves")
        assertEquals("${emoji}cd", SafeText.takeLast(text, 4))
        assertEquals(text, SafeText.takeLast(text, 100))
        assertWellFormed(SafeText.takeLast(text, 3))
        assertWellFormed(SafeText.takeLast(text, 4))
    }

    @Test
    @DisplayName("degenerate budgets stay empty instead of throwing")
    fun degenerateBudgets() {
        assertEquals("", SafeText.take("abc", 0))
        assertEquals("", SafeText.take("abc", -5))
        assertEquals("", SafeText.takeLast("abc", 0))
        assertEquals("", SafeText.takeLast("abc", -5))
    }

    @Test
    @DisplayName("sanitize repairs lone high and low surrogates")
    fun sanitizeRepairsUnpairedSurrogates() {
        val loneHigh = "\uD83D"
        val loneLow = "\uDE00"

        assertEquals("a${SafeText.REPLACEMENT}b", SafeText.sanitize("a${loneHigh}b"))
        assertEquals("a${SafeText.REPLACEMENT}b", SafeText.sanitize("a${loneLow}b"))
        assertEquals(
            "${SafeText.REPLACEMENT}x${SafeText.REPLACEMENT}",
            SafeText.sanitize("${loneHigh}x${loneLow}")
        )
    }

    @Test
    @DisplayName("sanitize keeps well-formed text as the same instance")
    fun sanitizeKeepsWellFormedText() {
        val text = "plain ${emoji} text \u4E2D\u6587"

        assertSame(text, SafeText.sanitize(text), "the common case must not copy the string")
        assertSame("", SafeText.sanitize(""))
    }

    @Test
    @DisplayName("sanitize is idempotent")
    fun sanitizeIsIdempotent() {
        val once = SafeText.sanitize("x\uD83Dy")
        assertEquals(once, SafeText.sanitize(once))
    }

    @Test
    @DisplayName("truncation output stays encodable as UTF-8")
    fun truncatedTextIsUtf8Encodable() {
        // 160 is the digest head budget; 800 / 400 are the production prune budgets.
        val text = "a".repeat(160) + emoji + "b".repeat(1_000)

        assertWellFormed(SafeText.take(text, 800))
        assertWellFormed(SafeText.takeLast(text, 400))
        // The whole point: a strict UTF-8 encoder must accept it.
        SafeText.take(text, 800).toByteArray(Charsets.UTF_8)
    }

    private fun assertWellFormed(text: String) {
        assertTrue(
            SafeText.sanitize(text) == text,
            "truncation left an unpaired surrogate behind: ${text.takeLast(20)}"
        )
    }
}
