package ai.platon.pulsar.linkcheck.service

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LinkcheckServiceTest {

    private val service = LinkcheckService()

    @Test
    @DisplayName("parseCounts parses a complete JSON object")
    fun parseCountsParsesCompleteJson() {
        val result = service.parseCounts("""{"total":10,"external":3,"internal":7}""")
        assertEquals(LinkCountResult(total = 10, external = 3, internal = 7), result)
    }

    @Test
    @DisplayName("parseCounts defaults missing fields to zero")
    fun parseCountsDefaultsMissingFieldsToZero() {
        val result = service.parseCounts("""{"total":5}""")
        assertEquals(LinkCountResult(total = 5, external = 0, internal = 0), result)
    }

    @Test
    @DisplayName("parseCounts returns zeros for empty and blank input")
    fun parseCountsReturnsZerosForBlankInput() {
        assertEquals(LinkCountResult(0, 0, 0), service.parseCounts(""))
        assertEquals(LinkCountResult(0, 0, 0), service.parseCounts("   "))
    }

    @Test
    @DisplayName("parseCounts returns zeros for invalid JSON")
    fun parseCountsReturnsZerosForInvalidJson() {
        assertEquals(LinkCountResult(0, 0, 0), service.parseCounts("abc"))
    }

    @Test
    @DisplayName("summarize formats total external and internal counts")
    fun summarizeFormatsCounts() {
        assertEquals("total=10 external=3 internal=7", service.summarize(LinkCountResult(10, 3, 7)))
    }
}
