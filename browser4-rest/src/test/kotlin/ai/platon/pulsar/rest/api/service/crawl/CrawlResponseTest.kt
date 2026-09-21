package ai.platon.pulsar.rest.api.service.crawl

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class CrawlResponseTest {

    // -----------------------------------------------------------------
    // Timestamp lifecycle
    // -----------------------------------------------------------------

    @Test
    fun `startedTime is null by default`() {
        val response = CrawlResponse(taskId = "t1")
        assertNull(response.startedTime, "startedTime should be null on creation")
    }

    @Test
    fun `finishTime is null by default`() {
        val response = CrawlResponse(taskId = "t1")
        assertNull(response.finishTime, "finishTime should be null on creation")
    }

    @Test
    fun `createdAt is set on construction`() {
        val before = System.currentTimeMillis()
        val response = CrawlResponse(taskId = "t2")
        val after = System.currentTimeMillis()
        assertTrue(response.createdAt >= before && response.createdAt <= after,
            "createdAt should be set to current time")
    }

    @Test
    fun `startedTime and finishTime can be set on completion`() {
        val now = Instant.now()
        val response = CrawlResponse(
            taskId = "t3",
            status = "OK",
            startedTime = now,
            finishTime = now.plusSeconds(30)
        )
        assertEquals(now, response.startedTime)
        assertEquals(now.plusSeconds(30), response.finishTime)
        assertEquals(CrawlStatus.OK, response.status)
        assertEquals(0, response.pagesFound)
    }

    @Test
    fun `timeout response carries startedTime and finishTime`() {
        val response = CrawlResponse(
            taskId = "t4",
            status = "REQUEST_TIMEOUT",
            error = "Crawl timed out after collecting 5 pages",
            startedTime = Instant.now(),
            finishTime = Instant.now()
        )
        assertNotNull(response.startedTime)
        assertNotNull(response.finishTime)
        assertEquals("REQUEST_TIMEOUT", response.status)
        assertNotNull(response.error)
    }

    @Test
    fun `error response carries startedTime and finishTime`() {
        val response = CrawlResponse(
            taskId = "t5",
            status = "INTERNAL_SERVER_ERROR",
            error = "Something broke",
            startedTime = Instant.now(),
            finishTime = Instant.now()
        )
        assertNotNull(response.startedTime)
        assertNotNull(response.finishTime)
    }

    @Test
    fun `default status is CREATED`() {
        val response = CrawlResponse()
        assertEquals(CrawlStatus.CREATED, response.status)
    }

    // -----------------------------------------------------------------
    // Task ID
    // -----------------------------------------------------------------

    @Test
    fun `taskId is preserved`() {
        val response = CrawlResponse(taskId = "my-task-id-123")
        assertEquals("my-task-id-123", response.taskId)
    }

    @Test
    fun `default taskId is empty`() {
        val response = CrawlResponse()
        assertEquals("", response.taskId)
    }

    // -----------------------------------------------------------------
    // Pages and diagnostics
    // -----------------------------------------------------------------

    @Test
    fun `pagesFound defaults to zero`() {
        val response = CrawlResponse(taskId = "t6")
        assertEquals(0, response.pagesFound)
    }

    @Test
    fun `pages defaults to null`() {
        val response = CrawlResponse(taskId = "t7")
        assertNull(response.pages)
    }

    @Test
    fun `diagnostic is nullable and defaults to null`() {
        val response = CrawlResponse(taskId = "t8")
        assertNull(response.diagnostic)
    }

    @Test
    fun `error is nullable and defaults to null`() {
        val response = CrawlResponse(taskId = "t9")
        assertNull(response.error)
    }

    // -----------------------------------------------------------------
    // Loss accounting (issue #592): a crawl must be able to say which
    // pages it submitted and never got back, and how many it expected.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a crawl that lost nothing reports no failed pages")
    fun lossIsAbsentByDefault() {
        val response = CrawlResponse(taskId = "loss-1", status = "OK", pagesFound = 10, pagesExpected = 10)
        assertNull(response.failedPages, "a complete crawl carries no failed pages")
        assertEquals(10, response.pagesExpected)
    }

    @Test
    @DisplayName("pages found plus failed pages always equals pages expected")
    fun lossAccountingIsExact() {
        val response = CrawlResponse(
            taskId = "loss-2",
            status = "OK",
            pagesFound = 8,
            pagesExpected = 10,
            failedPages = listOf(
                CrawlFailedPage("https://example.com/product/2.html", 1, 1601, "retry budget exhausted"),
                CrawlFailedPage("https://example.com/product/7.html", 2, 408, "gone")
            )
        )
        val failed = requireNotNull(response.failedPages) { "failedPages should be present" }
        assertEquals(
            response.pagesExpected, response.pagesFound + failed.size,
            "every submitted page is either a row or a reported loss"
        )
    }

    @Test
    @DisplayName("failed pages survive a persistence round trip")
    fun failedPagesRoundTripThroughPersistence() {
        val mapper = pulsarObjectMapper()
        val response = CrawlResponse(
            taskId = "loss-3",
            status = "OK",
            pagesFound = 8,
            pagesExpected = 10,
            failedPages = listOf(CrawlFailedPage("https://example.com/x.html", 2, 1601, "refused"))
        )

        val restored = mapper.readValue(mapper.writeValueAsString(response), CrawlResponse::class.java)

        assertEquals(10, restored.pagesExpected)
        val failed = requireNotNull(restored.failedPages) { "failedPages should survive a round trip" }
        assertEquals(1, failed.size)
        assertEquals("https://example.com/x.html", failed.first().url)
        assertEquals(2, failed.first().depth)
        assertEquals(1601, failed.first().protocolStatus)
        assertEquals("refused", failed.first().reason)
    }

    @Test
    @DisplayName("a task row written before loss accounting existed still restores")
    fun legacyTaskJsonStillRestores() {
        val mapper = pulsarObjectMapper()
        // Historical JSONL rows carry neither `failedPages` nor `pagesExpected`;
        // restoring them must not fail (the crawl task file is appended to
        // across upgrades).
        val legacy = """{"taskId":"legacy-1","status":"OK","pagesFound":3,"linksDiscovered":2}"""

        val restored = mapper.readValue(legacy, CrawlResponse::class.java)

        assertEquals("legacy-1", restored.taskId)
        assertEquals(3, restored.pagesFound)
        assertEquals(2, restored.linksDiscovered)
        assertNull(restored.failedPages)
        assertEquals(0, restored.pagesExpected)
    }
}
