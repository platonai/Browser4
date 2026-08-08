package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.test.TestUrls
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import java.time.Instant

/**
 * Verifies swarm can scrape pages served from non-/ec/ paths.
 *
 * The existing [SwarmControllerE2ETest] only uses /ec/ URLs whose protocol
 * handlers are pre-registered.  This test exercises /generated/crawl/ URLs
 * — the path that exposed the [spring.main.lazy-initialization=true] race
 * condition where [StartupWarmer] is missing critical beans, causing
 * "Protocol not found (1600)" errors and stuck swarm worker pools.
 *
 * Tagged [IntegrationTest] so it runs in main CI + nightly (not PR CI).
 */
@Tag("IntegrationTest")
class SwarmCrawlFixtureTest : RestAPITestBase() {

    private val crawlProductUrl get() = TestUrls.MOCK_CRAWL_PRODUCT_DETAIL_URL
    private val crawlHubUrl get() = TestUrls.MOCK_CRAWL_HUB_URL

    @Test
    @DisplayName("test submit /generated/crawl/ product URL through swarm")
    fun testSubmitGeneratedCrawlProductUrl() {
        val url = crawlProductUrl
        val uuid = submit(url)
        val finalStatus = waitForScrapeCompletion(uuid)

        assertEquals(uuid, finalStatus.id)
        assertTrue({ finalStatus.isDone },
            "swarm task should be done, last status: ${finalStatus}")
        // statusCode == 200 confirms no "Protocol not found (1600)" error.
        assertEquals(200, finalStatus.statusCode,
            "expected HTTP 200, got ${finalStatus.statusCode} — " +
            "may indicate Protocol not found (1600) or other fetch failure")
        assertEquals(200, finalStatus.pageStatusCode,
            "expected page-level 200, got ${finalStatus.pageStatusCode}")
        assertNotNull(finalStatus.resultSet)
        assertTrue(finalStatus.resultSet!!.isNotEmpty(),
            "resultSet should contain at least one row")
        assertEquals(url, finalStatus.resultSet!!.first()["url"])
    }

    @Test
    @DisplayName("test X-SQL base URI extraction from /generated/crawl/ product page")
    fun testSubmitGeneratedCrawlContentQuery() {
        // When a raw URL is submitted, SwarmController wraps it as:
        //   select dom_base_uri(dom) as url from load_and_select('<url>', ':root')
        // The dom_base_uri function works because it reads document metadata
        // (page.url) rather than parsed DOM content.  Content extraction via
        // dom_first_text(dom, '#productTitle') depends on page.content being
        // populated, which requires a protocol handler registered for the URL
        // path prefix — the generic HTTP handler that serves /generated/crawl/
        // pages does not populate page.content.
        //
        // This test verifies the page is reachable and parseable through the
        // swarm pipeline (confirming no "Protocol not found (1600)").  The
        // preceding test already confirms the full fetch + status path.
        val sql = """
            select dom_base_uri(dom) as url
            from load_and_select('$crawlProductUrl', ':root')
        """.trimIndent()

        val uuid = submit(sql)
        val finalStatus = waitForScrapeCompletion(uuid)

        assertTrue({ finalStatus.isDone },
            "swarm task should be done, last status: ${finalStatus}")
        assertEquals(200, finalStatus.statusCode,
            "expected HTTP 200, got ${finalStatus.statusCode}")
        assertNotNull(finalStatus.resultSet, "resultSet must not be null")
        assertTrue(finalStatus.resultSet!!.isNotEmpty(),
            "resultSet should contain at least one row")

        val row = finalStatus.resultSet!!.first()
        assertEquals(crawlProductUrl, row["url"],
            "should extract the page's base URI via dom_base_uri")
    }

    // -----------------------------------------------------------------
    // Private helpers — same pattern as SwarmControllerE2ETest
    // -----------------------------------------------------------------

    private fun submit(payload: String): String {
        val rawBody = client.post().uri("/api/swarm/submit")
            .body(payload)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody

        val body = rawBody?.trim()
        check(!body.isNullOrBlank()) { "Expected non-blank swarm task id body" }

        return body.removeSurrounding("\"").trim().also {
            check(it.isNotBlank()) { "Expected non-blank swarm task id but got: $body" }
        }
    }

    private fun getStatus(uuid: String): ScrapeResponse {
        return requireNotNull(
            client.get().uri("/api/swarm/status?uuid=$uuid")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody<ScrapeResponse>()
                .returnResult()
                .responseBody
        )
    }

    private fun waitForScrapeCompletion(uuid: String): ScrapeResponse {
        val deadline = Instant.now().plus(Duration.ofMinutes(2))
        var lastStatus = getStatus(uuid)

        while (!lastStatus.isDone && Instant.now().isBefore(deadline)) {
            Thread.sleep(Duration.ofSeconds(1).toMillis())
            lastStatus = getStatus(uuid)
        }

        return lastStatus
    }
}
