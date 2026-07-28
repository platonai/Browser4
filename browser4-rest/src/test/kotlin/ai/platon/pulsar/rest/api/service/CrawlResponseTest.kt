package ai.platon.pulsar.rest.api.service

import org.junit.jupiter.api.Assertions.*
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
        assertEquals("OK", response.status)
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
        assertEquals("CREATED", response.status)
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
}
