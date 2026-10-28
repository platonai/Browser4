package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The "progress so far" block an overflow continuation carries.
 *
 * Origin: the 2026-10-27 supervision run overflowed its 40-call round six times and
 * each restart kept only the newest few tool results, so the model re-fetched the
 * same 20 pages four times. The continuation now names the files the run already
 * wrote — bounded, because it ships in every continuation.
 */
@DisplayName("Overflow progress digest")
class OverflowProgressTest {

    @Test
    @DisplayName("a run with no files yet is told to persist before continuing")
    fun blankSummaryAsksToPersist() {
        val digest = progressDigestFromSummary(null)

        assertTrue(digest.contains("no files written yet"), digest)
        assertTrue(digest.contains("persist"), digest)
        assertEquals(digest, progressDigestFromSummary("   "))
    }

    @Test
    @DisplayName("the digest keeps the file lines and drops blank ones")
    fun keepsFileLines() {
        val digest = progressDigestFromSummary("rows.csv (12 lines)\n\n.b4scratch/progress.txt\n")

        assertEquals("rows.csv (12 lines)\n.b4scratch/progress.txt", digest)
    }

    @Test
    @DisplayName("the digest is bounded by line count and characters")
    fun digestIsBounded() {
        val manyLines = (1..50).joinToString("\n") { "file-$it.csv" }
        assertEquals(20, progressDigestFromSummary(manyLines).lines().size, "line cap")

        val longLine = "x".repeat(5_000)
        assertTrue(
            progressDigestFromSummary(longLine).length <= 1_200,
            "character cap: ${progressDigestFromSummary(longLine).length}",
        )
    }
}
