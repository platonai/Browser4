package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AcceptanceSummaryTest {

    @Test
    @DisplayName("summarize renders an empty bracket pair for an empty list")
    fun summarizeEmptyList() {
        assertEquals("Acceptance: []", AcceptanceSummary.summarize(emptyList()))
    }

    @Test
    @DisplayName("summarize joins a two-element list with a comma and a space")
    fun summarizeTwoElements() {
        assertEquals("Acceptance: [a, b]", AcceptanceSummary.summarize(listOf("a", "b")))
    }
}
