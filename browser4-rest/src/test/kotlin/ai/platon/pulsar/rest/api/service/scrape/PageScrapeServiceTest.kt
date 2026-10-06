package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.pulsar.agentic.tools.advanced.format.FormatSnapshot
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStepRunner
import ai.platon.pulsar.skeleton.workflow.format.FormatOptionSchema
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributorRegistry
import ai.platon.pulsar.skeleton.workflow.format.PageFormats
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

private const val KEY = "https://example.com/p"
private const val CLEAN_HTML = "<h1>Title</h1><p>Body</p>"

/**
 * A host bridge over a table of answers, so the service can be exercised without a
 * browser — the reason [FormatStepRunnerFactory] exists.
 */
private class FakeRunner(
    private val responses: Map<String, String> = mapOf("html_snapshot.export" to CLEAN_HTML),
    private val supported: Set<String> = setOf("html_snapshot.capture", "html_snapshot.export"),
) : FormatStepRunner {

    /** Every read the engine performed, with the arguments it passed. */
    val reads = mutableListOf<Pair<String, Map<String, Any?>>>()

    override suspend fun acquireSnapshot(expires: Duration): FormatSnapshot =
        FormatSnapshot(KEY, KEY, "2026-10-06T00:00:00Z", "miss")

    override suspend fun readOnSnapshot(
        snapshot: FormatSnapshot, domain: String, method: String, args: Map<String, Any?>,
    ): String {
        reads += "$domain.$method" to args
        require("$domain.$method" in supported) { "Unsupported $domain.$method" }
        return responses["$domain.$method"].orEmpty()
    }

    override suspend fun runOnTab(domain: String, method: String, args: Map<String, Any?>): String =
        responses["$domain.$method"].orEmpty()

    override fun supports(domain: String, method: String): Boolean = "$domain.$method" in supported
}

@DisplayName("PageScrapeService")
class PageScrapeServiceTest {

    private val requestedSessionIds = mutableListOf<String?>()

    /** Never leaks into the next test: the contributor registry is process-wide. */
    @AfterEach
    fun clearContributors() {
        PageFormatContributorRegistry.instance.clear()
    }

    private fun service(runner: FormatStepRunner = FakeRunner()): PageScrapeService =
        PageScrapeService(
            FormatStepRunnerFactory { sessionId ->
                requestedSessionIds += sessionId
                runner
            },
        )

    private fun formats(vararg raw: Any?): List<PageFormat> =
        FormatOptionSchema.parse(raw.toList()).requireValid()

    @Test
    @DisplayName("a request is built from the arguments and executed on one capture")
    fun scrapeBuildsOneRequest() = runBlocking {
        val runner = FakeRunner()

        val document = service(runner).scrape(
            formats = formats("markdown", "html"),
            sessionId = "s1",
        )

        assertEquals(listOf("s1"), requestedSessionIds)
        assertEquals(KEY, document.metadata.captureId)
        // The capture's own address is the only one there is: with no request URL in
        // play, sourceURL must be the captured href rather than something the caller
        // asked for and never got.
        assertEquals(KEY, document.metadata.sourceURL)
        assertTrue(document.markdown!!.contains("Title"), document.markdown)
        assertTrue(document.html!!.contains("Title"), document.html)
        // Both formats read the one capture, and nothing was requested that would
        // make the service capture again.
        assertEquals(listOf("html_snapshot.export"), runner.reads.map { it.first }.distinct())
    }

    @Test
    @DisplayName("a request with no session named still reaches the runner factory")
    fun sessionIdIsOptionalAndForwarded() = runBlocking {
        service().scrape(formats = formats("markdown"))

        assertEquals(listOf<String?>(null), requestedSessionIds)
    }

    @Test
    @DisplayName("a REQUIRED step failure propagates unchanged")
    fun requiredFailurePropagates() {
        // `rawHtml` is REQUIRED, so its failure must reach the caller rather than
        // becoming a document that quietly lost a field. (An *unsupported* tool is a
        // different case: the engine degrades that with a warning, whatever the
        // policy — see PageFormatEngine.unsupportedWarning.)
        val failing = object : FormatStepRunner {
            override suspend fun acquireSnapshot(expires: Duration): FormatSnapshot =
                FormatSnapshot(KEY, KEY, "2026-10-06T00:00:00Z", "miss")

            override suspend fun readOnSnapshot(
                snapshot: FormatSnapshot, domain: String, method: String, args: Map<String, Any?>,
            ): String = throw IllegalStateException("boom $domain.$method")

            override suspend fun runOnTab(domain: String, method: String, args: Map<String, Any?>): String = ""
            override fun supports(domain: String, method: String): Boolean = true
        }

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { service(failing).scrape(formats = formats("rawHtml")) }
        }
        assertTrue(error.message!!.contains("boom html_snapshot.export"), error.message)
    }

    // ---- capability listing -------------------------------------------------

    @Test
    @DisplayName("the capability listing covers every accepted id exactly once")
    fun formatsListingCoversEveryId() {
        val listing = service().formats()

        assertEquals(PageFormats.ALL.size, listing.size)
        assertEquals(PageFormats.ALL.toList(), listing.map { it["id"] })
    }

    @Test
    @DisplayName("an implemented core format is available and says it is core")
    fun implementedCoreFormatIsAvailable() {
        val entry = service().formats().single { it["id"] == "markdown" }

        assertEquals(true, entry["available"])
        assertNull(entry["reason"])
        assertEquals("core", entry["source"])
        assertEquals(emptyList<String>(), entry["requires"])
    }

    @Test
    @DisplayName("a core format this build cannot deliver is unavailable, with the reason")
    fun unimplementedCoreFormatIsUnavailable() {
        // `pdf`, not `screenshot`: screenshot gained a provider in Phase 2, and the
        // capability listing is exactly where that must show up.
        val entry = service().formats().single { it["id"] == "pdf" }

        assertEquals(false, entry["available"])
        assertEquals("not available in this build", entry["reason"])
    }

    @Test
    @DisplayName("a format that gained a provider reports itself available")
    fun screenshotIsAvailable() {
        val entry = service().formats().single { it["id"] == "screenshot" }

        assertEquals(true, entry["available"])
        assertNull(entry["reason"])
        assertEquals("core", entry["source"])
    }

    @Test
    @DisplayName("a contributed format with no plugin installed names the missing contributor")
    fun contributedFormatWithoutPlugin() {
        val entry = service().formats().single { it["id"] == "branding" }

        assertEquals(false, entry["available"])
        assertEquals("no plugin contributor installed", entry["reason"])
        assertEquals("plugin", entry["source"])
    }

    @Test
    @DisplayName("a deprecated format is reported as deprecated rather than merely missing")
    fun deprecatedFormatNamesItsReplacement() {
        val entry = service().formats().single { it["id"] == PageFormats.QUERY }

        assertEquals(false, entry["available"])
        assertEquals("deprecated; use question or highlights", entry["reason"])
    }
}
