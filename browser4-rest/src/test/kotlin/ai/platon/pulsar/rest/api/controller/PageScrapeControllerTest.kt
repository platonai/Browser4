package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.format.FormatSnapshot
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStepRunner
import ai.platon.pulsar.rest.api.service.scrape.ArtifactStore
import ai.platon.pulsar.rest.api.service.scrape.FormatStepRunnerFactory
import ai.platon.pulsar.rest.api.service.scrape.PageScrapeService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.nio.file.Files
import java.time.Duration

private const val KEY = "https://example.com/p"
private const val CLEAN_HTML = "<h1>Title</h1><p>Body</p>"

/** A session must be addressed on every scrape (decision A2: "open first"). */
private const val SESSION = "s1"

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
        val response = controller().scrape(
            PageScrapeRequestBody(sessionId = SESSION, formats = listOf("markdown")),
        )

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
        val response = controller().scrape(PageScrapeRequestBody(sessionId = SESSION))

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
            runBlocking {
                controller().scrape(
                    PageScrapeRequestBody(sessionId = SESSION, formats = listOf("markdwon")),
                )
            }
        }
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("an illegal combination is refused before anything is captured")
    fun illegalCombinationIsRefusedBeforeCapture() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                controller().scrape(
                    PageScrapeRequestBody(
                        sessionId = SESSION,
                        formats = listOf("json", "deterministicJson", "markdown"),
                    ),
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
                        sessionId = SESSION,
                        formats = listOf("markdown"),
                    ),
                )
            }
        }
        assertTrue(error.message!!.contains("'url' is not supported"), error.message)
        assertEquals(0, runner.captures, "a refused request must not capture anything")
    }

    @Test
    @DisplayName("a request naming no session is refused by name, before anything is captured")
    fun sessionIdIsRefusedByName() {
        // Decision A2 ("open first"): the reads target the page a session is already on,
        // so there is no honest answer without one — and picking a session here would
        // read a different caller's page. The *type* matters as much as the message:
        // IllegalArgumentException is exactly what the handler above maps to 400, so a
        // missing session is a caller mistake, not a 500 worth retrying.
        for (blank in listOf<String?>(null, "   ")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    controller().scrape(
                        PageScrapeRequestBody(sessionId = blank, formats = listOf("markdown")),
                    )
                }
            }
            assertTrue(error.message!!.contains("'sessionId' is required"), "sessionId='$blank'")
            assertTrue(error.message!!.contains("open"), "sessionId='$blank'")
        }
        assertEquals(0, runner.captures, "a refused request must not capture anything")
    }

    @Test
    @DisplayName("a per-format options object is accepted alongside plain names")
    fun formatObjectsAreAccepted() = runBlocking {
        val response = controller().scrape(
            PageScrapeRequestBody(sessionId = SESSION, formats = listOf(mapOf("type" to "markdown"))),
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

    // ---- artifact download --------------------------------------------------

    @Test
    @DisplayName("an artifact a scrape returned can be fetched back by its name")
    fun mediaServesAnArtifact() {
        // Written through the same store the controller resolves with, into the real
        // AppPaths directory: that pair is what the endpoint exists for, and a fake
        // directory would not prove the wiring between writer and reader.
        val store = ArtifactStore()
        val payload = "%PDF-1.4 test".toByteArray()
        val path = store.write("pdf", payload, "pdf")

        try {
            val name = path.fileName.toString()
            val response = controller().media(name)

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals("application/pdf", response.headers.contentType.toString())
            // The name travels back in the header, so a browser saves the artifact under
            // the identity the caller used rather than some generated default.
            assertEquals(
                "attachment; filename=\"$name\"",
                response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION),
            )
            assertArrayEquals(payload, response.body!!)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    @DisplayName("a well-formed name that is not there is 404, in the usual envelope")
    fun mediaAnswers404ForAMissingArtifact() {
        val response = controller().media("pdf-20261007-010203-456-abcdef01.pdf")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("application/json", response.headers.contentType.toString())
        assertTrue(String(response.body!!).contains("no artifact named"), String(response.body!!))
    }

    @Test
    @DisplayName("a name that is really a path is a bad request, not a lookup")
    fun mediaRefusesAPath() {
        // 400 rather than 404: the caller sent something that cannot be an artifact name
        // at all, and nothing is resolved — so no reply can leak whether some file
        // outside the artifact directories exists. `\` matters as much as `/`: a URL path
        // does not treat it as a separator, so it arrives intact.
        for (name in listOf("../secret.pdf", "..\\secret.pdf", "a/b.pdf", "a\\b.pdf", ".hidden.pdf")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                controller().media(name)
            }
            assertTrue(
                error.message!!.contains("path separator") ||
                    error.message!!.contains("starts with a letter or a digit"),
                "name='$name' gave: ${error.message}",
            )
        }
    }
}
