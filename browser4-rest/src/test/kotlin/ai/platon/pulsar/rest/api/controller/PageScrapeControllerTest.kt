package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.format.FormatSnapshot
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStepRunner
import ai.platon.pulsar.rest.api.service.scrape.FormatStepRunnerFactory
import ai.platon.pulsar.rest.api.service.scrape.PageScrapeService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

private const val KEY = "https://example.com/p"
private const val CLEAN_HTML = "<h1>Title</h1><p>Body</p>"

private class StubRunner : FormatStepRunner {
    var captures = 0
        private set

    override suspend fun acquireSnapshot(expires: Duration): FormatSnapshot {
        captures++
        return FormatSnapshot(KEY, KEY, "2026-10-06T00:00:00Z", "miss")
    }

    override suspend fun readOnSnapshot(
        snapshot: FormatSnapshot, domain: String, method: String, args: Map<String, Any?>,
    ): String = if (method == "export") CLEAN_HTML else ""

    override suspend fun runOnTab(domain: String, method: String, args: Map<String, Any?>): String = ""
    override fun supports(domain: String, method: String): Boolean = true
}

/**
 * The controller is exercised directly rather than through MockMvc on purpose: it
 * is a translator — body in, service call out, envelope back — and every decision
 * worth asserting (validation before capture, the envelope shape, the
 * `IllegalArgumentException` → 400 mapping) is visible without a servlet
 * container. The live HTTP route is verified against a running backend instead.
 */
@DisplayName("PageScrapeController")
class PageScrapeControllerTest {

    private val runner = StubRunner()

    private fun controller(): PageScrapeController =
        PageScrapeController(PageScrapeService(FormatStepRunnerFactory { runner }))

    @Test
    @DisplayName("a scrape answers in the {success, data} envelope")
    fun scrapeReturnsTheEnvelope() = runBlocking {
        val response = controller().scrape(PageScrapeRequestBody(formats = listOf("markdown")))

        assertEquals(true, response["success"])
        val data = response["data"] as Map<*, *>
        assertTrue(data["markdown"].toString().contains("Title"), data.toString())
        // The capture metadata travels inside `data`, not beside it — a caller reads
        // formatsDelivered to tell "not requested" from "requested but unavailable".
        assertNotNull(data["metadata"])
    }

    @Test
    @DisplayName("an omitted formats list means markdown, and it is carried through")
    fun formatsDefaultToMarkdown() = runBlocking {
        val response = controller().scrape(PageScrapeRequestBody())

        val data = response["data"] as Map<*, *>
        assertTrue(data["markdown"].toString().contains("Title"), data.toString())
        assertTrue(!data.containsKey("links"), data.toString())
    }

    @Test
    @DisplayName("an unknown format id is refused before anything is captured")
    fun unknownFormatIsRefusedBeforeCapture() {
        // 400 territory: a typo must be visible without the caller paying for a page
        // load, and the exception must be the one the @ExceptionHandler maps to 400.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { controller().scrape(PageScrapeRequestBody(formats = listOf("markdwon"))) }
        }
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("an illegal combination is refused before anything is captured")
    fun illegalCombinationIsRefusedBeforeCapture() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                controller().scrape(
                    PageScrapeRequestBody(formats = listOf("json", "deterministicJson", "markdown")),
                )
            }
        }
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("a request-level url is refused by name, not silently ignored")
    fun urlIsRefusedByName() {
        // The reads target the session's *active* page, so honouring `url` used to
        // return a document about the wrong page while metadata.sourceURL claimed
        // otherwise. The field stays in the DTO purely so this refusal can name it:
        // Jackson ignores unknown properties here, so dropping the field would put the
        // silent behaviour back.
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                controller().scrape(
                    PageScrapeRequestBody(
                        url = "https://other.example/page",
                        formats = listOf("markdown"),
                    ),
                )
            }
        }
        assertTrue(error.message!!.contains("'url' is not supported"), error.message)
        assertEquals(0, runner.captures, "a refused request must not capture anything")
    }

    @Test
    @DisplayName("a per-format options object is accepted alongside plain names")
    fun formatObjectsAreAccepted() = runBlocking {
        val response = controller().scrape(
            PageScrapeRequestBody(formats = listOf(mapOf("type" to "markdown"))),
        )

        val data = response["data"] as Map<*, *>
        assertTrue(data["markdown"].toString().contains("Title"), data.toString())
    }

    @Test
    @DisplayName("the capability listing is wrapped in the same envelope")
    fun formatsReturnsTheEnvelope() {
        val response = controller().formats()

        assertEquals(true, response["success"])
        val entries = (response["data"] as List<*>).filterIsInstance<Map<*, *>>()
        assertTrue(entries.any { it["id"] == "markdown" && it["available"] == true })
        assertTrue(entries.any { it["id"] == "branding" && it["available"] == false })
    }

    @Test
    @DisplayName("the 400 handler reports the message rather than swallowing it")
    fun badRequestHandlerKeepsTheMessage() {
        val body = controller().handleBadRequest(IllegalArgumentException("Unknown format 'markdwon'"))

        assertEquals(false, body["success"])
        assertEquals("Bad Request", body["error"])
        assertTrue(body["message"].toString().contains("markdwon"), body.toString())
    }
}
