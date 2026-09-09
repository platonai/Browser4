package ai.platon.pulsar.agentic.agents

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
    @DisplayName("digest shows tool name with the first line of its result")
    fun digestShowsNameAndFirstLine() {
        val digest = cliRoundToolDigest(
            listOf(RoundToolRecord("coding.read", "first line\nsecond line"))
        )

        assertEquals(
            "This round executed 1 tool(s): coding.read -> first line",
            digest,
            "the digest must keep the first line only"
        )
        assertTrue(digest!!.contains("second line").not(), "the digest must not leak the full result")
    }

    @Test
    @DisplayName("digest keeps the newest records and is bounded")
    fun digestKeepsNewestAndBounded() {
        val records = (1..20).map {
            RoundToolRecord("coding.shell", "step $it\n" + "x".repeat(10_000))
        }

        val digest = cliRoundToolDigest(records)

        assertTrue(digest!!.startsWith("This round executed 20 tool(s):"), "count must survive: $digest")
        assertTrue(digest.contains("coding.shell -> step 15"), "the newest 6 records must be kept: $digest")
        assertTrue(digest.contains("coding.shell -> step 20"), "the rest of the newest records must be kept: $digest")
        assertTrue(digest.contains("coding.shell -> step 14").not(), "older records must be dropped: $digest")
        assertTrue(digest.length <= 2_000, "digest must be capped, got ${digest.length}")
    }

    @Test
    @DisplayName("digest truncates an oversized first line to 200 chars")
    fun digestTruncatesOversizedFirstLine() {
        val longLine = "y".repeat(1_000)
        val digest = cliRoundToolDigest(listOf(RoundToolRecord("coding.read", longLine)))

        val rendered = digest!!.substringAfter("coding.read -> ")
        assertEquals(200, rendered.length, "the first line must be capped at 200 chars")
        assertEquals("y".repeat(200), rendered)
    }
}
