package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.high.crawl.ScrapeResponse
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.ql.h2.udfs.LLMFunctions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.client.expectBody
import kotlin.test.assertNotNull

open class ScrapeAPITests : RestAPITestBase() {

    /**
     * Test [ScrapeController.submitJob]
     * */
    @Test
    @DisplayName("Test extracting product list page with X-SQL sync")
    fun testExtractingProductListPageWithXSqlSync() {
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
    @Disabled("Disabled temporary, failed when run ci.yml")
    @DisplayName("Test extracting product list page with X-SQL")
    fun testExtractingProductListPageWithXSql() {
        val pageType = "productListPage"
        val url = requireNotNull(urls[pageType])
        val sql = requireNotNull(sqlTemplates[pageType]).createSQL("$url -refresh")

        val uuid = client.post().uri("/api/x/s")
            .body(sql)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody
        printlnPro("UUID: $uuid")
        assertNotNull(uuid)

        TODO("Disabled temporary, failed when run ci.yml")

        await(pageType, uuid, url)
    }

    /**
     * Test [ScrapeController.submitJob]
     * Test [LLMFunctions.extract]
     * */
    @Test
    @DisplayName("Test extracting product detail page with LLM + X-SQL")
    fun testExtractingProductDetailPageWithLlmXSql() {
        val pageType = "productDetailPage"
        val url = requireNotNull(urls[pageType])
        val sql = requireNotNull(sqlTemplates[pageType]).createSQL("$url -refresh")

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
                assertThat(response.pageContentBytes).isGreaterThan(1_000) // 2KB
                assertThat(response.pageStatusCode).isEqualTo(200)

                records = response.resultSet
                assertNotNull(records)

                printlnPro("records: $records")

                assertThat(records).isNotEmpty
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

        assertThat(tick).isLessThanOrEqualTo(timeout)
    }
}
