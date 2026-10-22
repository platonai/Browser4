package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Deterministic progress enforcement for coding runs.
 *
 * Live evidence: three refactor runs (100-175 tool calls each) built outlines,
 * member maps and reference indexes across several rounds and never changed a
 * file. The prompt asked for the first edit as soon as the shape of the code was
 * known; the model ignored it. This counter is the enforceable version.
 */
@DisplayName("coding progress enforcement")
class CodingProgressNudgeTest {

    @Test
    @DisplayName("no directive while the run is still inside its read-only budget")
    fun noNudgeInsideBudget() {
        assertNull(codingProgressNudge(0))
        assertNull(codingProgressNudge(1))
        assertNull(codingProgressNudge(2, limit = 3))
    }

    @Test
    @DisplayName("the directive names the edit tools and the counter")
    fun nudgeNamesTheNextAction() {
        val nudge = codingProgressNudge(2)!!
        assertTrue(nudge.contains("2 consecutive rounds"), nudge)
        assertTrue(nudge.contains("coding.replace"), nudge)
        assertTrue(nudge.contains("coding.verify"), nudge)
        assertTrue(nudge.contains("Stop surveying"), nudge)
    }

    @Test
    @DisplayName("a round that records a mutation resets the counter, read-only rounds accumulate")
    fun counterArithmetic() {
        // Mirrors the loop: progress is measured from the mutation log — the same
        // source of truth the finish gate uses. A round that changes a file resets
        // the counter; a tool round with no mutation increments it.
        var counter = 0
        var mutationsSeen = 0
        val rounds = listOf(
            0 to 3, // read-only
            0 to 3, // read-only → nudge
            1 to 2, // one file changed → reset
            1 to 3, // read-only after the edit
        )
        val nudges = mutableListOf<Int>()
        for ((mutations, executed) in rounds) {
            codingProgressNudge(counter)?.let {
                nudges += counter
                counter = 0
            }
            if (executed > 0) {
                if (mutations > mutationsSeen) {
                    mutationsSeen = mutations
                    counter = 0
                } else {
                    counter++
                }
            }
        }

        assertEquals(listOf(2), nudges, "one nudge after two mutation-free rounds, then reset by the edit")
        assertEquals(1, counter, "the mutation-free round after the edit increments again")
    }
}
