package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.crawl.CrawlResponse
import ai.platon.pulsar.rest.api.service.crawl.CrawlStatus
import ai.platon.pulsar.test.TestUrls
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import java.time.Instant

/**
 * Acceptance test for the crawl's X-SQL, end to end on a real browser and a real h2 engine.
 *
 * The statement is not run by plain SQL: the engine resolves the url in its FROM clause itself,
 * through `load_and_select()` and `PulsarSession.load()`. The crawl has already fetched that page,
 * so the contract is that the query reads *that* copy — the statement is sealed read-only, and the
 * page is frozen in the page cache immediately before the statement runs.
 *
 * The target server is what makes "no second fetch" observable instead of asserted: every
 * `/__probe/slow` request is counted, so the same crawl is run twice — once with a query and once
 * without — and the counts must match. What that falsifies is a statement that can still force its
 * own load: a `-refresh` surviving into the url, or a page that was never frozen. It does *not*
 * isolate the `-readonly` flag, and that is worth knowing: `LoadComponent.getCachedPageOrNull`
 * consults the page cache without looking at `readonly`, and only `refresh` bypasses it — so what
 * keeps the inner load local is the frozen copy, while `-readonly` is what keeps the query from
 * writing the page back to the cache or the store.
 *
 * Each run uses fresh urls, so neither the page store nor the browser's own cache can answer for
 * the query behind the crawl's back.
 *
 * Tagged [IntegrationTest]: needs a real browser, the driver pool and the mock site, so it runs in
 * main CI + nightly (not PR CI).
 */
@Tag("IntegrationTest")
class CrawlXSqlE2ETest : RestAPITestBase() {

    private val probeBase: String by lazy {
        "${TestUrls.MOCK_CRAWL_BASE.substringBefore("/generated")}/__probe"
    }

    @Test
    @DisplayName("a crawl with an X-SQL extracts from its own page and never fetches it again")
    fun testCrawlWithSqlExtractsWithoutASecondFetch() {
        // Fresh paths on every run, for three reasons: a second crawl of a url already in the page
        // store could be answered from there, a repeat of the same url could be answered from the
        // browser's own HTTP cache, and a previous *non*-read-only run leaves the page persisted —
        // any of which would hide a query that went to the web behind the seal's back.
        val run = System.currentTimeMillis().toString(36)
        val controlId = "crawl-sql-control-$run"
        val seedId = "crawl-sql-seed-$run"
        val controlSeed = "$probeBase/slow/$controlId"
        val seed = "$probeBase/slow/$seedId"
        val sql = "select dom_first_text(dom, '#probe-id') as id from load_and_select(@url, ':root')"

        // `--readonly` keeps this run's own fetch out of the store, so the only thing that can
        // answer the query is the page the round hands it.
        val crawlArgs = "-refresh -readonly"

        // The control run measures what the round's own fetch costs; the run under test adds a
        // query on top of exactly the same crawl.
        resetProbe()
        val withoutSql = crawl(controlSeed, sql = null, args = crawlArgs)
        val requestsWithoutSql = probeStats()["totalRequests"] ?: 0
        assertEquals("OK", withoutSql.status, "the control crawl should complete OK: ${withoutSql.error}")
        assertEquals(
            1, requestsWithoutSql,
            "the control crawl must fetch its seed exactly once, or the comparison below means nothing"
        )

        resetProbe()
        val withSql = crawl(seed, sql = sql, args = crawlArgs)
        val requestsWithSql = probeStats()["totalRequests"] ?: 0

        assertEquals("OK", withSql.status, "crawl should complete OK: error=${withSql.error}")
        val page = withSql.pages?.single() ?: fail("expected exactly one page, got ${withSql.pages}")
        assertNull(
            page.extractionError,
            "the X-SQL must extract from the page the round fetched, not report a reason"
        )
        assertEquals(
            listOf(mapOf("id" to seedId)), page.extracted,
            "the query must read the page under its own url"
        )

        assertEquals(
            requestsWithoutSql, requestsWithSql,
            "the crawl's query loaded its own page from the web: " +
                "$requestsWithSql request(s) with a query against $requestsWithoutSql without one"
        )
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    /**
     * Submit a depth=0 crawl through the real HTTP endpoint and wait for its terminal state.
     *
     * The JSON is assembled field by field — not by serializing the DTO — so the wire contract is
     * what the test pins: the query travels as `sql`, and a rename has to break a test instead of
     * drifting silently.
     * */
    private fun crawl(seed: String, sql: String?, args: String): CrawlResponse {
        val payload = mutableMapOf<String, Any>(
            "url" to seed,
            "args" to args,
            "depth" to 0,
        )
        sql?.let { payload["sql"] = it }
        val rawTaskId = client.post().uri("/api/crawl")
            .contentType(MediaType.APPLICATION_JSON)
            .body(jacksonObjectMapper().writeValueAsString(payload))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        val taskId = requireNotNull(rawTaskId) { "expected a crawl task id" }
            .trim()
            .removeSurrounding("\"")
        check(taskId.isNotBlank()) { "blank crawl task id" }

        val deadline = Instant.now().plus(Duration.ofMinutes(4))
        var last: CrawlResponse? = null
        while (Instant.now().isBefore(deadline)) {
            Thread.sleep(1_000)
            val raw = client.get().uri("/api/crawl/$taskId/result")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody<String>()
                .returnResult()
                .responseBody
            val result = requireNotNull(raw) { "empty crawl result for $taskId" }
                .let { jacksonObjectMapper().registerModule(JavaTimeModule()).readValue(it, CrawlResponse::class.java) }
            last = result
            // Terminal detection defers to CrawlStatus — the one vocabulary definition.
            if (CrawlStatus.isTerminal(result.status)) {
                return result
            }
        }
        error("Crawl $taskId did not reach a terminal state within 4 minutes, last: ${last?.status}")
    }

    private fun resetProbe() {
        client.post().uri("$probeBase/reset")
            .exchange()
            .expectStatus().is2xxSuccessful
    }

    private fun probeStats(): Map<String, Int> {
        val raw = client.get().uri("$probeBase/stats")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        val body = requireNotNull(raw) { "empty /__probe/stats body" }
        return jacksonObjectMapper().readValue(body, Map::class.java)
            .entries.associate { (k, v) -> k.toString() to (v as Number).toInt() }
    }
}
