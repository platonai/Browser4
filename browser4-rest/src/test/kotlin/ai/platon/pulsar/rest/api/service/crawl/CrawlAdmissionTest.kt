package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.agentic.tools.ToolErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CrawlAdmission] — the queue bound behind requirement 9.3.
 */
class CrawlAdmissionTest {

    @Test
    @DisplayName("a submission below the bound is admitted")
    fun aSubmissionBelowTheBoundIsAdmitted() {
        assertNull(CrawlAdmission.refusal("crawl.submit", 63, 64))
        assertNull(CrawlAdmission.refusal("crawl.submit", 0, 64))
    }

    @Test
    @DisplayName("a submission at or above the bound is refused with the depth and limit")
    fun aSubmissionAtTheBoundIsRefused() {
        val message = CrawlAdmission.refusal("crawl.submit", 64, 64)
        assertTrue(message != null, "64 of 64 must be refused")
        assertTrue(message!!.contains("crawl.submit"), message)
        assertTrue(message.contains("64 task(s) running"), message)
        assertTrue(message.contains("limit 64"), message)
        assertTrue(message.contains("retry after ${CrawlAdmission.RETRY_AFTER_MS} ms"), message)

        // Above the bound the refusal still happens, and reports the real depth.
        val deeper = CrawlAdmission.refusal("crawl.submit", 65, 64)
        assertTrue(deeper != null, "65 of 64 must be refused")
        assertTrue(deeper!!.contains("65 task(s) running"), deeper)
    }

    @Test
    @DisplayName("a non-positive bound disables the check")
    fun aNonPositiveBoundDisablesTheCheck() {
        assertNull(CrawlAdmission.refusal("crawl.submit", Int.MAX_VALUE, 0))
        assertNull(CrawlAdmission.refusal("crawl.submit", Int.MAX_VALUE, -1))
    }

    @Test
    @DisplayName("a refused submission reports RATE_LIMITED so both channels agree")
    fun aRefusedSubmissionReportsRateLimited() {
        val exception = CrawlQueueFullException(CrawlAdmission.refusal("crawl.submit", 64, 64)!!)
        assertEquals(ToolErrorCode.RATE_LIMITED, exception.code)
        assertTrue(exception.code.retryable, "a saturated queue is a retryable condition")
    }
}
