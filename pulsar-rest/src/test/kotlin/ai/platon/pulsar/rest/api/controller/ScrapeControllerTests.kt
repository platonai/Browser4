package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.ql.h2.udfs.LLMFunctions
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.rest.api.entities.ScrapeResponse
import org.assertj.core.api.Assumptions
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.client.expectBody
import kotlin.test.assertNotNull

open class ScrapeControllerTests : ScrapeControllerTestBase() {

    /**
     * Test [ScrapeController.submitJob]
     * */
    @Test
    fun `Test extracting product list page with X-SQL sync`() {
        val pageType = "productListPage"
        val url = requireNotNull(urls[pageType])
        val sql = requireNotNull(sqlTemplates[pageType]).createSQL(url)

        val response = client.post().uri("/api/x/e")
            .body(sql)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<ScrapeResponse>()
            .returnResult()
            .responseBody
        printlnPro(response)
        assertNotNull(response)
    }

    /**
     * Test [ScrapeController.submitJob]
     * */
    @Test
    fun `Test extracting product list page with X-SQL`() {
        val pageType = "productListPage"
        val url = requireNotNull(urls[pageType])
        val sql = requireNotNull(sqlTemplates[pageType]).createSQL(url)

        val uuid = client.post().uri("/api/x/s")
            .body(sql)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        printlnPro("UUID: $uuid")
        assertNotNull(uuid)

        await(pageType, uuid, url)
    }

    /**
     * Test [ScrapeController.submitJob]
     * Test [LLMFunctions.extract]
     * */
    @Test
    fun `Test extracting product detail page with LLM + X-SQL`() {
        val pageType = "productDetailPage"
        val url = requireNotNull(urls[pageType])
        val sql = requireNotNull(sqlTemplates[pageType]).createSQL(url)

        val uuid = client.post().uri("/api/x/s")
            .body(sql)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        printlnPro("UUID: $uuid")
        assertNotNull(uuid)

        await(pageType, uuid, url)
    }

    protected fun await(pageType: String, uuid: String, url: String) {
        var records: List<Map<String, Any?>>? = null
        var tick = 0
        val timeout = 60
        while (records == null && ++tick < timeout) {
            sleepSeconds(1)

            val response = client.get().uri("/api/x/status?uuid=$uuid")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody<ScrapeResponse>()
                .returnResult()
                .responseBody
            assertNotNull(response)

            if (tick % 10 == 0) {
                printlnPro(pulsarObjectMapper().writeValueAsString(response))
            }

            if (response.isDone) {
                printlnPro("response: ")
                printlnPro(prettyPulsarObjectMapper().writeValueAsString(response))

                // If the page content bytes is less than 20KB, it means the page is not loaded
                Assumptions.assumeThat(response.pageContentBytes).isGreaterThan(20_000) // 20KB
                Assumptions.assumeThat(response.pageStatusCode).isEqualTo(200)

                records = response.resultSet
                assertNotNull(records)

                printlnPro("records: $records")

                Assumptions.assumeThat(records).isNotEmpty
            }
        }

        // wait for callback
        sleepSeconds(3)

        val response = client.get().uri("/api/x/a/status?uuid=$uuid")
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<ScrapeResponse>()
            .returnResult()
            .responseBody
        assertNotNull(response)

        printlnPro("Final scrape task status: ")
        printlnPro(pulsarObjectMapper().writeValueAsString(response))

        Assumptions.assumeThat(tick).isLessThanOrEqualTo(timeout)
    }
}
