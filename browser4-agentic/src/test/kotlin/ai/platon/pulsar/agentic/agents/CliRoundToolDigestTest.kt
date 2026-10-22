package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.inference.chat.ToolResultDigest
import ai.platon.pulsar.coding.SafeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the per-round executed-tool digest ([cliRoundToolDigest]) that the
 * CLI engine feeds back into the next round's continuation message — the
 * inner [ai.platon.pulsar.agentic.inference.chat.AgentToolCallLoop]'s tool
 * calls/results die with its local message copy, so without this digest the
 * model would re-execute tools across outer rounds.
 */
@DisplayName("cliRoundToolDigest")
class CliRoundToolDigestTest {

    @Test
    @DisplayName("empty records produce no digest")
    fun emptyRecordsProduceNull() {
        assertNull(cliRoundToolDigest(emptyList()))
    }

    @Test
    @DisplayName("a short result is reproduced in full")
    fun digestShowsNameAndFullShortResult() {
        val digest = cliRoundToolDigest(
            listOf(RoundToolRecord("coding.read", "first line\nsecond line"))
        )

        assertEquals(
            "This round executed 1 tool(s): coding.read -> first line\nsecond line",
            digest,
            "a result under the excerpt budget must not be truncated"
        )
    }

    @Test
    @DisplayName("a long result keeps its head and its tail, not just the first line")
    fun longResultKeepsHeadAndTail() {
        val result = "Session: devshell-1\nStatus: FAILED\n--- stdout ---\n" +
            "HEAD: compiling browser4-browser\n" +
            "x".repeat(2_000) +
            "\nTAIL: [ERROR] cannot find symbol"

        val digest = cliRoundToolDigest(listOf(RoundToolRecord("coding.shell", result)))!!

        assertTrue(digest.contains("HEAD: compiling browser4-browser"), "the head must survive: $digest")
        assertTrue(digest.contains("TAIL: [ERROR] cannot find symbol"), "the tail must survive: $digest")
        assertTrue(
            digest.contains("Session: devshell-1").not(),
            "the constant shell header used to be the entire excerpt: $digest"
        )
    }

    @Test
    @DisplayName("digest keeps the newest records within the budget")
    fun digestKeepsNewestWithinBudget() {
        val records = (1..20).map {
            RoundToolRecord("coding.shell", "step $it\n" + "x".repeat(10_000))
        }

        val digest = cliRoundToolDigest(records)!!

        assertTrue(digest.startsWith("This round executed 20 tool(s):"), "count must survive: $digest")
        assertTrue(digest.contains("step 20"), "the newest record must survive the budget: $digest")
        assertTrue(digest.contains("step 17"), "the newest records fill the budget: $digest")
        assertTrue(digest.contains("step 16").not(), "older records must be dropped: $digest")
        assertTrue(digest.length <= 2_000, "digest must be capped, got ${digest.length}")
    }

    @Test
    @DisplayName("an oversized result is excerpted to head + tail")
    fun oversizedResultIsExcerpted() {
        val longLine = "y".repeat(1_000)

        val digest = cliRoundToolDigest(listOf(RoundToolRecord("coding.read", longLine)))!!

        val rendered = digest.substringAfter("coding.read -> ")
        assertEquals(
            ToolResultDigest.HEAD_CHARS + ToolResultDigest.TAIL_CHARS + " … ".length,
            rendered.length,
            "an excerpt is head + ellipsis + tail"
        )
        assertTrue(rendered.startsWith("y") && rendered.endsWith("y"), "both ends must be kept: $rendered")
        assertTrue(rendered.contains(" … "))
    }

    @Test
    @DisplayName("digest cuts never leave an unpaired surrogate behind")
    fun digestIsSurrogateSafe() {
        val result = "a".repeat(ToolResultDigest.HEAD_CHARS - 1) + "😀" + "b".repeat(1_000)

        val digest = cliRoundToolDigest(listOf(RoundToolRecord("coding.read", result)))!!

        assertTrue(
            SafeText.sanitize(digest) == digest,
            "digest cut left an unpaired surrogate (it breaks every later UTF-8 write)"
        )
        // Must not throw; assertTrue also keeps the test body's result Unit, or
        // JUnit would refuse to run a test whose block returns a ByteArray.
        assertTrue(digest.toByteArray(Charsets.UTF_8).isNotEmpty(), "digest must stay UTF-8 encodable")
    }
}
