package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.service.crawl.CrawlResumeResult
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.verify

/**
 * The REST surface of crawl resume.
 *
 * The service owns the semantics (see `CrawlServicePersistenceTest`); what is pinned
 * here is the wire contract the CLI depends on: the endpoint delegates with the
 * options it was given, a rejection is an *answer* the caller can act on, and the one
 * genuine conflict — a task that still has a live worker — is a `409` rather than a
 * `500`.
 */
class CrawlControllerResumeTest {

    private val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
    private val crawlService = Mockito.mock(CrawlService::class.java)
    private val controller = CrawlController(sessionManager, crawlService)

    @Test
    @DisplayName("resume delegates to the service with its options and returns what it did")
    fun resumeDelegatesToTheService() {
        val expected = CrawlResumeResult(
            taskId = "task-1",
            resumed = true,
            status = "Created",
            message = "resumed run 2: 42 already-fetched URL(s) restored from the checkpoint, 3 URL(s) left to fetch",
            remaining = 3,
            skippedAlreadyFetched = 42,
            resumeCount = 1
        )
        Mockito.`when`(crawlService.resume("task-1", force = true, retryFailed = true)).thenReturn(expected)

        val result = controller.resumeCrawl("task-1", force = true, retryFailed = true)

        assertEquals(expected, result)
        verify(crawlService).resume("task-1", force = true, retryFailed = true)
    }

    @Test
    @DisplayName("a resume the service declined is reported as an answer, not an error")
    fun aDeclinedResumeIsAnAnswer() {
        val declined = CrawlResumeResult(
            taskId = "task-2",
            resumed = false,
            status = "OK",
            message = "Crawl task-2 already completed successfully; pass --force --retry-failed to fetch the URLs it lost",
            remaining = 0,
            skippedAlreadyFetched = 12,
            resumeCount = 0
        )
        Mockito.`when`(crawlService.resume("task-2", force = false, retryFailed = false)).thenReturn(declined)

        val result = controller.resumeCrawl("task-2", force = false, retryFailed = false)

        assertFalse(result.resumed)
        assertTrue(result.message.contains("--force"), result.message)
        assertEquals(12, result.skippedAlreadyFetched)
    }

    @Test
    @DisplayName("a blank task id is a bad request")
    fun blankIdIsRejected() {
        assertThrows<IllegalArgumentException> { controller.resumeCrawl("", force = false, retryFailed = false) }
    }

    @Test
    @DisplayName("a task whose worker is alive is reported as a conflict, not a server error")
    fun aRunningTaskIsAConflict() {
        val conflict = IllegalStateException(
            "Crawl task-3 is still running; a task cannot be resumed while its worker is alive"
        )

        val body = controller.handleConflict(conflict)

        assertEquals("Conflict", body["error"])
        assertTrue((body["message"] as String).contains("still running"))
    }

    @Test
    @DisplayName("the resume answer survives the wire, so the CLI can report what was skipped and what is left")
    fun theAnswerRoundTrips() {
        val mapper = pulsarObjectMapper()
        val result = CrawlResumeResult(
            taskId = "task-4",
            resumed = true,
            status = "Interrupted",
            message = "resumed run 3",
            remaining = 7,
            skippedAlreadyFetched = 100,
            resumeCount = 2
        )

        val json = mapper.writeValueAsString(result)
        val restored = mapper.readValue(json, CrawlResumeResult::class.java)

        assertEquals(result, restored, "every field the CLI prints must be on the wire: $json")
        assertTrue(json.contains("\"skippedAlreadyFetched\""), json)
    }
}
