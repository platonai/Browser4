package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.rest.api.service.ScrapeService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor

/**
 * `POST /api/x/submit`, the sibling of `/api/swarm/submit`.
 *
 * Both turn a bare URL payload into the same `load_and_select('<url>', ':root')` statement, and they
 * used to disagree about how: the escaping and the url validation existed only on the swarm side, so
 * this endpoint interpolated the payload raw and handed out a task id for a url that could never be
 * fetched.  The two are pinned together here.
 */
@DisplayName("ScrapeController.submit")
class ScrapeControllerTest {

    private val scrapeService: ScrapeService = Mockito.mock(ScrapeService::class.java)
    private val controller = ScrapeController(scrapeService)

    /** Submit [payload] and return the statement the service was asked to run. */
    private fun submittedSql(payload: String): String {
        Mockito.`when`(scrapeService.submitJob(any())).thenReturn("mock-uuid")

        assertEquals("mock-uuid", controller.submit(payload))

        val captor = argumentCaptor<ScrapeRequest>()
        verify(scrapeService).submitJob(captor.capture())
        return captor.firstValue.sql
    }

    @Test
    @DisplayName("a url payload becomes a load_and_select statement")
    fun urlPayloadBecomesLoadAndSelect() {
        assertEquals(
            "select dom_base_uri(dom) as url from load_and_select('https://example.com', ':root')",
            submittedSql("https://example.com")
        )
    }

    @Test
    @DisplayName("the payload is trimmed before it is embedded")
    fun payloadIsTrimmed() {
        assertEquals(
            "select dom_base_uri(dom) as url from load_and_select('https://example.com', ':root')",
            submittedSql("  https://example.com  ")
        )
    }

    @Test
    @DisplayName("an apostrophe in the url is escaped instead of interpolated raw")
    fun apostropheIsEscaped() {
        // A possessive in an entry-page href is normal.  Interpolating it raw broke the statement —
        // reported to the caller as "Invalid URL or X-SQL" about a perfectly good url — and let the
        // url text escape the literal.
        assertEquals(
            "select dom_base_uri(dom) as url from load_and_select(" +
                "'https://example.com/o''brien?q=it''s -refresh', ':root')",
            submittedSql("https://example.com/o'brien?q=it's -refresh")
        )
    }

    @Test
    @DisplayName("LoadOptions are carried through the literal")
    fun loadOptionsAreCarriedThrough() {
        assertEquals(
            "select dom_base_uri(dom) as url from load_and_select(" +
                "'https://example.com/p/1 -refresh -requireNotBlank #title -nMaxRetry 3', ':root')",
            submittedSql("https://example.com/p/1 -refresh -requireNotBlank #title -nMaxRetry 3")
        )
    }

    @Test
    @DisplayName("a malformed url is refused before a task id is handed out")
    fun malformedUrlIsRefusedUpfront() {
        // `checkSql` only checks *syntax*, and a url is syntactically valid inside
        // `load_and_select('...')` — so the "empty host" family used to come back as a uuid and then
        // fail in the job, or fetch the fetcher's default search-engine url for the page asked for.
        listOf("http://", "https://", "http://:8080/p").forEach { payload ->
            val exception = assertThrows<IllegalArgumentException> { controller.submit(payload) }

            assertEquals("Malformed url: <$payload>", exception.message)
        }
        verify(scrapeService, never()).submitJob(any())
    }

    @Test
    @DisplayName("an X-SQL payload is passed through untouched")
    fun sqlPayloadIsPassedThrough() {
        val sql = "select dom_base_uri(dom) as url from load_and_select('https://example.com', ':root')"

        assertEquals(sql, submittedSql(sql))
    }

    @Test
    @DisplayName("a forbidden statement is refused")
    fun forbiddenStatementIsRefused() {
        assertThrows<IllegalArgumentException> { controller.submit("DROP TABLE users") }
        verify(scrapeService, never()).submitJob(any())
    }
}
