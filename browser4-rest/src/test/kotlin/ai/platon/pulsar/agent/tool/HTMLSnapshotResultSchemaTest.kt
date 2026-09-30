package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.mcp.controller.inspectDocument
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.skeleton.workflow.parse.html.ReadabilityExtractor
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * `html_snapshot` answers JSON for `query`, `scrape_all` and `inspect`, so those three
 * declare a result contract and are checked against what they really produce.
 *
 * `query` runs through `ScrapeService` (mocked here — the shape of its response is the
 * contract, not the X-SQL engine); `inspect` delegates to the pure `inspectDocument`,
 * which the existing `InspectDocumentTest` already drives without a browser.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("html_snapshot result contracts")
class HTMLSnapshotResultSchemaTest {

    private val mapper = pulsarObjectMapper()

    private fun executor(service: ScrapeService? = null) =
        HTMLSnapshotToolExecutor(mock<PulsarSessionManager>(), service)

    private fun spec(method: String) = executor().getToolSpecs().getValue(method)

    private fun issues(spec: ai.platon.pulsar.agentic.model.ToolSpec, payload: String) =
        ToolResultValidator.validate(spec, ToolResultValidator.parse(payload)).map { "${it.path} ${it.message}" }

    @Test
    @DisplayName("query returns a response object that satisfies its schema")
    fun queryMatchesItsSchema() = runBlocking {
        val response = ScrapeResponse(
            id = "task-1",
            statusCode = 200,
            pageStatusCode = 200,
            pageContentBytes = 4096,
            isDone = true,
            resultSet = listOf(mapOf("title" to "Widget Alpha")),
            event = "completed",
        )
        val service = mock<ScrapeService> { on { it.executeQuery(org.mockito.kotlin.any()) } doReturn response }

        // An explicit `url` makes the query run session-less, which is exactly the
        // offline-corpus path a client uses when it does not want a live page.
        val payload = executor(service).callFunctionOn(
            "html_snapshot", "query",
            mapOf("sql" to "select dom_first_text(dom, 'h1') as title", "url" to "https://example.com"),
            Unit,
        ) as String

        val spec = spec("query")
        assertNotNull(spec.outputSchema, "html_snapshot.query must declare its result contract")
        assertEquals(emptyList<String>(), issues(spec, payload), "query violated its own schema: $payload")
        assertEquals(true, mapper.readTree(payload)["isDone"].asBoolean())
    }

    @Test
    @DisplayName("inspect returns the analysed document shape its schema promises")
    fun inspectMatchesItsSchema() {
        val html = """
            <html><body>
            <div class="product-card"><h2 class="title">Alpha</h2><span class="price">$19.99</span></div>
            <div class="product-card"><h2 class="title">Beta</h2><span class="price">$24.99</span></div>
            <div class="product-card"><h2 class="title">Gamma</h2><span class="price">$39.99</span></div>
            </body></html>
        """.trimIndent()

        val payload = inspectDocument(FeaturedDocument(Jsoup.parse(html)), ".product-card", 20, 5)

        val spec = spec("inspect")
        assertNotNull(spec.outputSchema, "html_snapshot.inspect must declare its result contract")
        assertEquals(emptyList<String>(), issues(spec, payload), "inspect violated its own schema: $payload")

        // The three fields the schema requires are the ones every path writes.
        val json = mapper.readTree(payload)
        assertEquals(".product-card", json["selector"].asText())
        assertEquals(3, json["matchCount"].asInt())
    }

    @Test
    @DisplayName("readability returns the nine-field article object its schema promises")
    fun readabilityMatchesItsSchema() {
        val html = """
            <html><head>
              <title>How Rust Conquered the Kernel</title>
              <meta name="author" content="Ada Lovelace">
              <meta property="og:site_name" content="Systems Weekly">
            </head><body>
              <nav id="main-nav"><a href="/">Home</a> <a href="/news">News</a> <a href="/about">About</a></nav>
              <div id="content"><article class="post">
                <h1>How Rust Conquered the Kernel</h1>
                <p>Rust brings memory safety to the Linux kernel without sacrificing performance. This
                   article explores the history, the technical design and the community effort behind the
                   largest incremental rewrite in kernel history.</p>
                <p>The first Rust code landed in Linux 6.1, guarded by a strict configuration flag. Since
                   then, device drivers, filesystems and networking components have begun migrating to safe
                   abstractions that eliminate entire classes of vulnerabilities.</p>
                <p>Critics point to the learning curve and the difficulty of auditing unsafe blocks.
                   Proponents counter that the ecosystem tooling catches whole bug families at compile time
                   rather than at runtime.</p>
              </article></div>
              <footer><a href="/privacy">Privacy</a> <a href="/terms">Terms</a></footer>
            </body></html>
        """.trimIndent()

        val result = ReadabilityExtractor().extract(Jsoup.parse(html))
        assertNotNull(result, "the fixture must be readable, otherwise this test proves nothing")

        val payload = executor().readabilityPayload(result!!, "https://example.com/fallback").toString()

        val spec = spec("readability")
        assertNotNull(spec.outputSchema, "html_snapshot.readability must declare its result contract")
        assertEquals(emptyList<String>(), issues(spec, payload), "readability violated its own schema: $payload")
        assertEquals(true, mapper.readTree(payload)["length"].asInt() > 0)
    }

    @Test
    @DisplayName("scrape_all answers a JSON array of strings")
    fun scrapeAllSchemaIsAnArrayOfStrings() {
        val spec = spec("scrape_all")
        assertNotNull(spec.outputSchema, "html_snapshot.scrape_all must declare its result contract")

        assertEquals(emptyList<String>(), issues(spec, """["Alpha","Beta","Gamma"]"""))
        assertEquals(
            true,
            issues(spec, """[{"title":"Alpha"}]""").isNotEmpty(),
            "an object element must be reported as a violation",
        )
    }

    @Test
    @DisplayName("the query schema has teeth: a mistyped isDone is a violation")
    fun schemasHaveTeeth() {
        val spec = spec("query")
        val broken = """{"statusCode":200,"pageStatusCode":200,"pageContentBytes":10,"isDone":"yes","event":"x"}"""
        assertEquals(true, issues(spec, broken).isNotEmpty(), "'isDone' as a string must be rejected")

        val missing = """{"statusCode":200,"pageStatusCode":200,"pageContentBytes":10,"event":"x"}"""
        assertEquals(true, issues(spec, missing).isNotEmpty(), "a missing isDone must be rejected")
    }
}
