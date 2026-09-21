package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.boot.autoconfigure.Browser4AutoConfiguration
import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.rest.api.common.MockEcServerTestBase
import ai.platon.pulsar.rest.api.config.MockEcServerConfiguration
import ai.platon.pulsar.test.server.MockServerPorts
import ai.platon.pulsar.rest.api.entities.ScrapeStatusRequest
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@Import(MockEcServerConfiguration::class, Browser4AutoConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ScrapeServiceTests : MockEcServerTestBase() {

    /** The cheap page: ~3 s per fetch against the list page's ~32 s (see the class KDoc). */
    private val productDetailURL get() = "${MockServerPorts.baseUrl()}/ec/dp/B0E000001"

    @Autowired
    private lateinit var config: ImmutableConfig

    @Autowired
    private lateinit var service: ScrapeService

    /**
     * No `@BeforeEach` preparation hook here on purpose.
     *
     * The class used to declare one as a `suspend fun`, which JUnit cannot invoke
     * (the compiled signature takes a `Continuation`), so it silently never ran —
     * and had it run it would have re-fetched the mock pages before every test.
     * That matters because the two mock pages differ by an order of magnitude on a
     * loaded machine: the 101-product `/ec/b?node=...` list page takes ~32 s per
     * fetch (the browser runtime is re-injected into an isolated world on every
     * load) while `/ec/dp/B0E000001` takes ~3 s.  The scrapes below therefore target
     * the detail page and load exactly what they assert on;
     * [MockEcServerTestBase.setup] still verifies the mock server before each test.
     */

    /**
     * Execute a normal SQL
     * */
    @Test
    @DisplayName("When perform 1+1 then the result is 2")
    fun whenPerform11ThenTheResultIs2() {
        val sql = "select 1+1 as sum"
        val request = ScrapeRequest(sql)

        val response = service.executeQuery(request)
        val records = response.resultSet
        printlnPro(records.toString())
        assertNotNull(records)

        assertTrue { records.isNotEmpty() }
        assertEquals(2, records[0]["sum"].toString().toInt())
    }

    /**
     * Test [ai.platon.pulsar.ql.h2.udfs.DomFunctionTables.loadAndSelect]
     * Test [ScrapeService.executeQuery]
     * */
    @Test
    @DisplayName("When scraping with load_and_select then the result returns synchronously")
    fun whenScrapingWithLoadAndSelectThenTheResultReturnsSynchronously() {
        val startTime = Instant.now()

        val sql = "select dom_base_uri(dom) as uri from load_and_select('$productDetailURL -i 10d', ':root')"
        val request = ScrapeRequest(sql)

        val response = executeWithRetry(request)
        val records = response.resultSet
        assertNotNull(records)

        assertTrue { records.isNotEmpty() }
        val actualUrl = records[0]["uri"].toString()
        assertTrue { actualUrl == productDetailURL }

        printlnPro("Done scraping with load_and_select, used " + DateTimes.elapsedTime(startTime))
    }

    /** Retry executeQuery up to 3 times when the WebDB cache is cold (417). */
    private fun executeWithRetry(request: ScrapeRequest): ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse {
        var last: ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse? = null
        repeat(3) { attempt ->
            val resp = service.executeQuery(request)
            if (resp.statusCode != 417) return resp
            last = resp
            if (attempt < 2) Thread.sleep(1000L * (attempt + 1))
        }
        return last!!
    }

    @Test
    @DisplayName("When scrape amazon then the base uri returns asynchronously")
    fun whenScrapeAmazonThenTheBaseUriReturnsAsynchronously() {
        val sql = "select dom_base_uri(dom) as uri from load_and_select('$productDetailURL', ':root')"
        val request = ScrapeRequest(sql)

        val uuid = service.submitJob(request)

        assertTrue { uuid.isNotEmpty() }
        printlnPro(uuid)

        val scrapeStatusRequest = ScrapeStatusRequest(uuid)
        var status = awaitScrapeJob(scrapeStatusRequest)

        // Retry once if the WebDB cache was cold (417) — e.g. after
        // kill_all_sessions in a prior test closed the browser.
        if (status.statusCode == 417) {
            sleepSeconds(2)
            val retryUuid = service.submitJob(request)
            status = awaitScrapeJob(ScrapeStatusRequest(retryUuid))
        }
        printlnPro(pulsarObjectMapper().writeValueAsString(status).toString())
        assertEquals(200, status.statusCode)
    }

    /**
     * Poll a submitted job until it reports done.
     *
     * The wait is bounded by a deadline rather than a fixed count of one-second
     * sleeps, so a job that finishes in a few hundred milliseconds does not cost a
     * full second — the old loop spent a second per poll on a page whose actual
     * scrape takes ~1.5 s.
     */
    private fun awaitScrapeJob(
        request: ScrapeStatusRequest,
        timeout: Duration = Duration.ofMinutes(2)
    ): ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse {
        val deadline = Instant.now().plus(timeout)
        var status = service.getStatus(request)
        while (Instant.now().isBefore(deadline) && !status.isDone) {
            Thread.sleep(250)
            status = service.getStatus(request)
        }
        return status
    }

    @Test
    @DisplayName("When scraping with X-SQL then the result returns synchronously")
    fun whenScrapingWithXSqlThenTheResultReturnsSynchronously() {
        Assumptions.assumeTrue(ChatModelFactory.isModelConfigured(config))

        val startTime = Instant.now()

        val sql = """
            select
              dom_base_uri(dom) as url,
              dom_first_text(dom, '#productTitle') as title,
              dom_first_slim_html(dom, 'img:expr(width > 400)') as img
            from load_and_select('$productDetailURL', 'body');
        """.trimIndent()
        val request = ScrapeRequest(sql)

        val response = executeWithRetry(request)
        val records = response.resultSet
        assertNotNull(records)

        printlnPro(prettyPulsarObjectMapper().writeValueAsString(response).toString())

        assertTrue { records.isNotEmpty() }
        val actualUrl = records[0]["url"].toString()
        assertTrue("URL not expected \nExpected: $productDetailURL\nActual: $actualUrl") { actualUrl == productDetailURL }

        // Verify no duplicate columns caused by H2 case-normalization mismatch.
        // Before the fix, each row had both an uppercase-null and lowercase-value
        // key (e.g. "URL": null AND "url": "<value>") because the column-order
        // list used ResultSetMetaData.getColumnName (uppercase) while the row
        // keys from ResultSetUtils are lowercased.
        for ((idx, row) in records.withIndex()) {
            val keys = row.keys.toList()
            val keysLower = keys.map { it.lowercase() }.toSet()
            assertEquals(
                keys.size, keysLower.size,
                "Row $idx has duplicate columns (case-insensitive): $keys. " +
                "Each column should appear exactly once."
            )
        }

        printlnPro("Done scraping with load_and_select, used " + DateTimes.elapsedTime(startTime))
    }
}
