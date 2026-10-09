package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.ToolErrorCode
import ai.platon.pulsar.agentic.tools.advanced.format.FormatFailureException
import ai.platon.pulsar.agentic.tools.advanced.format.FormatSnapshot
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStepRunner
import ai.platon.pulsar.rest.api.service.scrape.FormatStepRunnerFactory
import ai.platon.pulsar.rest.api.service.scrape.PageScrapeService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

private const val KEY = "https://example.com/p"
private const val CLEAN_HTML = "<h1>Title</h1><p>Body</p>"

/** A session must be addressed on every scrape (decision A2: "open first"). */
private const val SESSION = "s1"

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
        val result = scrape("sessionId" to SESSION, "formats" to listOf("markdown"))

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
            val document = scrape("sessionId" to SESSION, "formats" to raw) as Map<*, *>
            assertTrue(document["markdown"].toString().contains("Title"), "formats=$raw")
        }
    }

    @Test
    @DisplayName("an omitted formats argument still defaults to markdown")
    fun formatsDefaultsToMarkdown() = runBlocking {
        val document = scrape("sessionId" to SESSION) as Map<*, *>

        assertTrue(document["markdown"].toString().contains("Title"), document.toString())
        // Nothing else was produced: "not requested" must not look like "missing".
        assertTrue(!document.containsKey("links"), document.toString())
    }

    @Test
    @DisplayName("an unknown format id is refused before anything is captured, session or not")
    fun unknownFormatIsRefusedBeforeCapture() {
        // The caller must learn about a typo without paying for a page load. This also
        // pins the *order* of the two refusals: format validation runs before the
        // session requirement, so a caller who got both wrong hears about the typo
        // first — the cheaper mistake to fix.
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { scrape("formats" to listOf("markdwon")) }
        }
        assertTrue(error.message!!.contains("markdwon"), error.message)
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("a call that names no session is refused before a runner is built")
    fun missingSessionIsRefused() {
        // Decision A2 ("open first"). The message must name both the argument and the
        // fix: "sessionId is required" alone leaves the caller guessing what to do.
        for (blank in listOf<String?>(null, "   ")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { scrape("sessionId" to blank, "formats" to listOf("markdown")) }
            }
            assertTrue(error.message!!.contains("'sessionId' is required"), "sessionId='$blank'")
            assertTrue(error.message!!.contains("open"), "sessionId='$blank'")
        }
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("an illegal combination is refused before anything is captured")
    fun illegalCombinationIsRefusedBeforeCapture() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                scrape("sessionId" to SESSION, "formats" to listOf("json", "deterministicJson", "markdown"))
            }
        }
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("onlyMainContent is parsed from either a boolean or a string")
    fun onlyMainContentAcceptsBothSpellings() = runBlocking {
        for (raw in listOf<Any>(false, "false")) {
            val document = scrape(
                "sessionId" to SESSION, "formats" to "markdown", "onlyMainContent" to raw,
            ) as Map<*, *>
            // With onlyMainContent off, markdown comes from the cleaned export.
            assertTrue(document["markdown"].toString().contains("Title"), "onlyMainContent=$raw")
        }
    }

    @Test
    @DisplayName("strict is parsed from a boolean or a string, and fails the call when set")
    fun strictIsParsed() {
        // Both spellings a client can send: MCP arguments arrive as JSON, and a CLI flag
        // that reaches the backend as text must mean the same thing.
        for (raw in listOf<Any>(true, "true")) {
            val error = assertThrows(FormatFailureException::class.java) {
                runBlocking {
                    scrape("sessionId" to SESSION, "formats" to listOf("audio"), "strict" to raw)
                }
            }
            assertEquals(ToolErrorCode.TARGET_UNAVAILABLE, error.code, "strict=$raw")
        }

        // `false` is the default, so it must behave exactly like omitting it.
        val document = runBlocking {
            scrape("sessionId" to SESSION, "formats" to listOf("audio", "markdown"), "strict" to false)
        } as Map<*, *>
        assertTrue(document["warning"].toString().contains("audio"), document.toString())
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
    @DisplayName("the specs declare no CLI name: the CLI surface is a static command pair")
    fun toolSpecsDeclareNoCliName() {
        val specs = executor().getToolSpecs()

        // `scrape` / `scrape formats` live in commands.rs, not in these specs. The
        // declared-command mechanism can only resolve a *spaced* invocation (the CLI
        // probes when it has two tokens), so a bare `scrape` is unreachable that way;
        // declaring one here as well would give one action two entry points.
        assertNull(specs.getValue("scrape").cliName)
        assertNull(specs.getValue("formats").cliName)
    }

    @Test
    @DisplayName("sessionId is the only argument allowed a null default, and the validator skips it")
    fun sessionIdIsTheOnlyRequiredArgument() {
        // `ToolSpecValidator` reports MISSING_REQUIRED_ARG whenever `defaultValue` is
        // null — the `?` in `String?` does not make an argument optional. A live call
        // carrying only `formats` once failed on exactly that, for the required `url`.
        // So no *payload* argument may spell its default as null.
        //
        // `sessionId` is the deliberate exception, and it is not a loophole: it is a
        // transport argument listed in `ToolSpecValidator.DEFAULT_CONTEXT_ARGS`, and the
        // validator `continue`s past it *before* the required-argument check — so a null
        // default cannot make it required by accident. Every other domain already
        // declares it this way (`HTMLSnapshotToolExecutor`, `WebDbToolExecutor`), and
        // decision A2 made it the truth here too: `PageScrapeService` refuses a blank
        // session regardless of what any spec says.
        val spec = executor().getToolSpecs().getValue("scrape")

        val required = spec.arguments.filter { it.defaultValue == null }.map { it.name }
        assertEquals(listOf("sessionId"), required)
        assertEquals("String", spec.arguments.single { it.name == "sessionId" }.type)
    }
}
