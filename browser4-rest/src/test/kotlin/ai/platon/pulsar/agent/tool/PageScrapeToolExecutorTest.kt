package ai.platon.pulsar.agent.tool

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

/** A host bridge with no browser behind it; see [PageScrapeServiceTest] for the same shape. */
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

@DisplayName("PageScrapeToolExecutor")
class PageScrapeToolExecutorTest {

    private val runner = StubRunner()

    private fun executor(): PageScrapeToolExecutor =
        PageScrapeToolExecutor(PageScrapeService(FormatStepRunnerFactory { runner }))

    private suspend fun scrape(vararg args: Pair<String, Any?>): Any? =
        executor().callFunctionOn("page", "scrape", mapOf(*args), Any())

    // ---- argument handling --------------------------------------------------

    @Test
    @DisplayName("scrape returns the document as a map, not a description envelope")
    fun scrapeReturnsAMap() = runBlocking {
        val result = scrape("formats" to listOf("markdown"))

        // AbstractToolExecutor wraps anything that is not a Map/Collection/String in a
        // `{type, description}` envelope, which would hand the caller a toString() of
        // the document instead of the document.
        assertTrue(result is Map<*, *>, "expected a map, got ${result?.javaClass?.simpleName}")
        val document = result as Map<*, *>
        assertTrue(document["markdown"].toString().contains("Title"), document.toString())
        assertNotNull(document["metadata"])
    }

    @Test
    @DisplayName("formats accepts a JSON string, a list, and a comma-separated string")
    fun formatsArgumentFormsAreAccepted() = runBlocking {
        for (raw in listOf("""["markdown"]""", listOf("markdown"), "markdown")) {
            val document = scrape("formats" to raw) as Map<*, *>
            assertTrue(document["markdown"].toString().contains("Title"), "formats=$raw")
        }
    }

    @Test
    @DisplayName("an omitted formats argument still defaults to markdown")
    fun formatsDefaultsToMarkdown() = runBlocking {
        val document = scrape() as Map<*, *>

        assertTrue(document["markdown"].toString().contains("Title"), document.toString())
        // Nothing else was produced: "not requested" must not look like "missing".
        assertTrue(!document.containsKey("links"), document.toString())
    }

    @Test
    @DisplayName("an unknown format id is refused before anything is captured")
    fun unknownFormatIsRefusedBeforeCapture() {
        // The caller must learn about a typo without paying for a page load.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { scrape("formats" to listOf("markdwon")) }
        }
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("an illegal combination is refused before anything is captured")
    fun illegalCombinationIsRefusedBeforeCapture() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { scrape("formats" to listOf("json", "deterministicJson", "markdown")) }
        }
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("onlyMainContent is parsed from either a boolean or a string")
    fun onlyMainContentAcceptsBothSpellings() = runBlocking {
        for (raw in listOf<Any>(false, "false")) {
            val document = scrape("formats" to "markdown", "onlyMainContent" to raw) as Map<*, *>
            // With onlyMainContent off, markdown comes from the cleaned export.
            assertTrue(document["markdown"].toString().contains("Title"), "onlyMainContent=$raw")
        }
    }

    @Test
    @DisplayName("an unusable formats argument fails by name instead of being ignored")
    fun badFormatsArgumentFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { scrape("formats" to 42) }
        }
        assertTrue(error.message!!.contains("formats"), error.message)
    }

    @Test
    @DisplayName("an unsupported method is refused by name")
    fun unknownMethodIsRefused() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { executor().callFunctionOn("page", "scrapeAll", emptyMap(), Any()) }
        }
        assertTrue(error.message!!.contains("scrapeAll"), error.message)
    }

    // ---- capability listing -------------------------------------------------

    @Test
    @DisplayName("formats lists every accepted id with its availability")
    fun formatsListsCapabilities() = runBlocking {
        val listing = executor().callFunctionOn("page", "formats", emptyMap(), Any())

        assertTrue(listing is List<*>, "expected a list, got ${listing?.javaClass?.simpleName}")
        val entries = (listing as List<*>).filterIsInstance<Map<*, *>>()
        assertEquals(entries.size, listing.size)
        assertTrue(entries.any { it["id"] == "markdown" && it["available"] == true })
        assertTrue(entries.any { it["id"] == "branding" && it["available"] == false })
    }

    // ---- tool spec ----------------------------------------------------------

    @Test
    @DisplayName("both methods declare a spaced CLI name, so the CLI needs no command table entry")
    fun toolSpecsDeclareCliNames() {
        val specs = executor().getToolSpecs()

        // Spaced on purpose: the CLI resolves a declared command only when the
        // invocation has at least two tokens, so a single-word `cliName` such as
        // "scrape" would never be discovered (`scrape --formats x` has one token
        // before the flags). `page scrape` is the first token pair the resolver sees.
        assertEquals("page scrape", specs.getValue("scrape").cliName)
        assertEquals("page formats", specs.getValue("formats").cliName)
    }

    @Test
    @DisplayName("no scrape argument is required: a null default would make it required")
    fun scrapeTakesNoRequiredArguments() {
        // ToolSpecValidator reports MISSING_REQUIRED_ARG whenever `defaultValue` is
        // null — the `?` in `String?` does not make an argument optional. A live call
        // with only `formats` therefore failed on the required `url`; this locks the
        // convention so it cannot come back.
        val spec = executor().getToolSpecs().getValue("scrape")

        val required = spec.arguments.filter { it.defaultValue == null }.map { it.name }
        assertTrue(required.isEmpty(), "ToolSpecValidator would require these: $required")
    }
}
