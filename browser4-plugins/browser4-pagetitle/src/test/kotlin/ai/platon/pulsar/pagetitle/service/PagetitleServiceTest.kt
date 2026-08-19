package ai.platon.pulsar.pagetitle.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PagetitleServiceTest {

    private val service = PagetitleService()

    @Test
    @DisplayName("summarize returns empty string when title is empty")
    fun summarizeReturnsEmptyStringWhenTitleIsEmpty() {
        assertEquals("", service.summarize(PageInfo(title = null), 200))
        assertEquals("", service.summarize(PageInfo(title = ""), 200))
    }

    @Test
    @DisplayName("summarize returns short text unchanged")
    fun summarizeReturnsShortTextUnchanged() {
        assertEquals("Short title", service.summarize(PageInfo(title = "Short title"), 200))
    }

    @Test
    @DisplayName("summarize truncates over-length title with ellipsis")
    fun summarizeTruncatesOverLengthTitleWithEllipsis() {
        val longTitle = "a".repeat(205)
        assertEquals("a".repeat(200) + "...", service.summarize(PageInfo(title = longTitle), 200))
    }
}
