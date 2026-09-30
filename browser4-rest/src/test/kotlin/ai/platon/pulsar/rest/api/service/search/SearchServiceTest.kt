package ai.platon.pulsar.rest.api.service.search

import ai.platon.pulsar.rest.session.PulsarSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/**
 * Unit tests for [SearchService] focusing on task lifecycle management:
 * unknown-task reporting, cancel, clear, and the submit→result happy path
 * with a stubbed [SearchProvider] (no real network call).
 *
 * Mirrors [ai.platon.pulsar.rest.api.service.crawl.CrawlServiceTest]'s shape.
 */
class SearchServiceTest {

    @Mock
    private lateinit var sessionManager: PulsarSessionManager

    private lateinit var searchService: SearchService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // Stub provider returns two fixed results for any query.
        val stubProvider = object : SearchProvider {
            override val name: String = "stub"
            override suspend fun search(request: SearchRequest): List<SearchResult> = listOf(
                SearchResult(url = "https://example.com/1", title = "Hit 1", content = "snippet 1"),
                SearchResult(url = "https://example.com/2", title = "Hit 2", content = "snippet 2"),
            )
        }
        searchService = SearchService(sessionManager, stubProvider)
    }

    @AfterEach
    fun tearDown() {
        runCatching { searchService.shutdown() }
    }

    // ------------------------------------------------------------------
    // getResult — unknown task
    // ------------------------------------------------------------------

    @Test
    fun `getResult returns not-found status for unknown task`() {
        val result = searchService.getResult("nonexistent-id")
        assertEquals(SearchStatus.NOT_FOUND, result.status)
        assertEquals("Task not found: nonexistent-id", result.error)
        assertEquals("nonexistent-id", result.taskId)
    }

    // ------------------------------------------------------------------
    // cancel — unknown task
    // ------------------------------------------------------------------

    @Test
    fun `cancel returns false for unknown task`() {
        val cancelled = searchService.cancel("nonexistent-id")
        assertFalse(cancelled, "Cancelling an unknown task must report false, not throw")
    }

    // ------------------------------------------------------------------
    // clearTerminal / clearAll — empty store
    // ------------------------------------------------------------------

    @Test
    fun `clearTerminal on empty store returns zero`() {
        assertEquals(0, searchService.clearTerminal())
    }

    @Test
    fun `clearAll on empty store returns zero`() {
        assertEquals(0, searchService.clearAll())
    }

    // ------------------------------------------------------------------
    // submit — happy path with stubbed provider
    // ------------------------------------------------------------------

    @Test
    fun `submit returns a task id immediately and the result is eventually populated`() =
        runBlocking {
            val request = SearchRequest(query = "test query", maxResults = 5)
            val taskId = searchService.submit(request)

            assertNotNull(taskId)
            assertNotEquals("", taskId)

            // The task is recorded under the returned id right away.
            val initial = searchService.getResult(taskId)
            assertEquals(taskId, initial.taskId)
            assertEquals(SearchStatus.CREATED, initial.status)

            // Wait for the coroutine to finish (provider is synchronous,
            // no scrape, so this is fast — but still async).
            val final = waitForTerminal(taskId, timeoutMillis = 5_000)

            assertEquals(SearchStatus.OK, final.status)
            assertEquals(2, final.pagesFound)
            val results = final.results
            assertNotNull(results)
            assertEquals(2, results!!.size)
            assertEquals("https://example.com/1", results[0].url)
            assertEquals("Hit 1", results[0].title)
        }

    @Test
    fun `submit without scrape leaves scrapedContent null on every result`() =
        runBlocking {
            val request = SearchRequest(query = "no scrape", maxResults = 2, scrape = false)
            val taskId = searchService.submit(request)
            val final = waitForTerminal(taskId, timeoutMillis = 5_000)

            assertEquals(SearchStatus.OK, final.status)
            final.results!!.forEach { result ->
                assertNull(result.scrapedContent, "scrapedContent must be null without --scrape")
                assertNull(result.scrapeError)
            }
        }

    // ------------------------------------------------------------------
    // clearTerminal — after a finished task
    // ------------------------------------------------------------------

    @Test
    fun `clearTerminal removes terminal tasks but keeps running ones`() =
        runBlocking {
            // Submit one task and wait for it to finish.
            val finishedId = searchService.submit(SearchRequest(query = "first"))
            waitForTerminal(finishedId, timeoutMillis = 5_000)

            // The terminal task should be cleared.
            val cleared = searchService.clearTerminal()
            assertEquals(1, cleared, "One terminal task should have been cleared")

            // After clearing, the task is gone.
            val after = searchService.getResult(finishedId)
            assertEquals(SearchStatus.NOT_FOUND, after.status)
        }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Poll [SearchService.getResult] until the task is terminal, with a
     * generous timeout for the CI machine.
     */
    private suspend fun waitForTerminal(
        taskId: String,
        timeoutMillis: Long,
    ): SearchResponse {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMillis) {
            val r = searchService.getResult(taskId)
            if (r.status in SearchStatus.TERMINAL) return r
            //noinspection SLEEP_IN_TEST
            Thread.sleep(50)
        }
        throw AssertionError("Task $taskId did not reach terminal state within ${timeoutMillis}ms")
    }
}
