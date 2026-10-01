package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlRequest
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any

/**
 * `POST /api/crawl` refuses a seed it could never fetch.
 *
 * Nothing downstream checks a seed: the crawl accepted any string, spent its budget, and then either
 * recorded a NIL page or fetched the fetcher's *default search-engine url* for the page that was
 * asked for (`AbstractPulsarContext.normalize` substitutes `SEARCH_ENGINE_URL` when its input is
 * neither a url nor base64), reporting rows under a URL nobody requested.  The handler below turns
 * the refusal into the 400 the other endpoints already answer.
 */
@DisplayName("CrawlController.startCrawl seed validation")
class CrawlControllerSeedValidationTest {

    private val sessionManager: PulsarSessionManager = Mockito.mock(PulsarSessionManager::class.java)
    private val crawlService: CrawlService = Mockito.mock(CrawlService::class.java)
    private val controller = CrawlController(sessionManager, crawlService)

    private fun startCrawl(request: CrawlRequest): String {
        Mockito.`when`(crawlService.submit(any())).thenReturn("mock-task")
        return controller.startCrawl(request)
    }

    @Test
    @DisplayName("a misspelled seed is refused before a task is created")
    fun malformedSeedIsRefused() {
        val exception = assertThrows<IllegalArgumentException> {
            startCrawl(CrawlRequest(url = "htps://exmple.com", depth = 0))
        }

        assertEquals("Malformed url: <htps://exmple.com>", exception.message)
        verify(crawlService, never()).submit(any())
    }

    @Test
    @DisplayName("every seed is checked, not just the first")
    fun everySeedIsChecked() {
        val exception = assertThrows<IllegalArgumentException> {
            startCrawl(
                CrawlRequest(
                    urls = listOf("https://example.com/a", "https://example.com/b", "not-a-url"),
                    depth = 1
                )
            )
        }

        assertEquals("Malformed url: <not-a-url>", exception.message)
        verify(crawlService, never()).submit(any())
    }

    @Test
    @DisplayName("a seed may carry trailing LoadOptions")
    fun seedWithLoadOptionsIsAccepted() {
        assertEquals("mock-task", startCrawl(CrawlRequest(url = "https://example.com -expires 1d", depth = 0)))

        verify(crawlService).submit(any())
    }

    @Test
    @DisplayName("a standard seed is submitted")
    fun standardSeedIsSubmitted() {
        assertEquals("mock-task", startCrawl(CrawlRequest(url = "https://example.com", depth = 0)))

        verify(crawlService).submit(any())
    }

    @Test
    @DisplayName("the blank check still answers first, with its own message")
    fun blankSeedsKeepTheirMessage() {
        val exception = assertThrows<IllegalArgumentException> {
            startCrawl(CrawlRequest(url = "", depth = 0))
        }

        assertEquals("url or urls must not be blank", exception.message)
    }

    @Test
    @DisplayName("a refused seed answers the 400 body the handler builds")
    fun theHandlerMapsARefusalToBadRequest() {
        val body = controller.handleBadRequest(IllegalArgumentException("Malformed url: <htps://exmple.com>"))

        assertEquals("Bad Request", body["error"])
        assertEquals("Malformed url: <htps://exmple.com>", body["message"])
    }
}
