package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeResponse
import ai.platon.pulsar.rest.api.entities.SessionResponse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.client.expectBody
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@Tag("E2ETest")
class SwarmControllerE2ETest : RestAPITestBase() {

    /**
     * Test [SwarmController.open]
     * */
    @Test
    @DisplayName("test open returns swarm session response")
    fun testOpenReturnsSwarmSessionResponse() {
        val response = client.post().uri("/api/swarm")
            .body(mapOf("profileMode" to "TEMPORARY"))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<SessionResponse>()
            .returnResult()
            .responseBody
        assertNotNull(response)

        assertEquals(SWARM_SESSION_ID, response.sessionId)
        assertNotNull(response.status)
        assertTrue(response.status!!.isNotBlank())
        assertTrue(response.profileMode in setOf("SEQUENTIAL", "TEMPORARY"))
        assertEquals(SWARM_SESSION_ID, response.capabilities?.get("sessionId"))
        assertEquals(response.profileMode, response.capabilities?.get("profileMode")?.toString())
    }

    /**
     * Test [SwarmController.submit]
     * Test [SwarmController.count]
     * Test [SwarmController.status]
     * Test [SwarmController.getStatus]
     * Test [SwarmController.getResult]
     * */
    @Test
    @DisplayName("test submit URL and query swarm scrape status/result")
    fun testSubmitUrlAndQuerySwarmScrapeStatusResult() {
        val url = requireNotNull(urls["productDetailPage"])
        val totalBefore = countResponses()
        val okBefore = countResponses(200)

        val uuid = submit(url)
        val finalStatus = waitForScrapeCompletion(uuid)

        assertEquals(uuid, finalStatus.id)
        assertTrue(finalStatus.isDone)
        assertEquals(200, finalStatus.statusCode)
        assertEquals(200, finalStatus.pageStatusCode)
        assertNotNull(finalStatus.resultSet)
        assertTrue(finalStatus.resultSet!!.isNotEmpty())
        assertEquals(url, finalStatus.resultSet!!.first()["url"])

        val statusByPath = getStatusByPath(uuid)
        val result = getResult(uuid)
        assertEquals(finalStatus.id, statusByPath.id)
        assertEquals(finalStatus.id, result.id)
        assertEquals(finalStatus.statusCode, statusByPath.statusCode)
        assertEquals(finalStatus.statusCode, result.statusCode)
        assertEquals(finalStatus.resultSet, statusByPath.resultSet)
        assertEquals(finalStatus.resultSet, result.resultSet)

        assertTrue(countResponses() >= totalBefore + 1)
        assertTrue(countResponses(200) >= okBefore + 1)
    }

    /**
     * Test [SwarmController.submit]
     * */
    @Test
    @DisplayName("test submit X-SQL and return async result set")
    fun testSubmitXSqlAndReturnAsyncResultSet() {
        val uuid = submit("select 1 + 1 as sum")
        val finalStatus = waitForScrapeCompletion(uuid)

        assertEquals(uuid, finalStatus.id)
        assertTrue(finalStatus.isDone)
        assertEquals(200, finalStatus.statusCode)
        assertEquals(200, finalStatus.pageStatusCode)
        assertNotNull(finalStatus.resultSet)
        assertEquals(2, finalStatus.resultSet!!.first()["sum"].toString().toInt())
    }

    /**
     * Test [SwarmController.status]
     * Test [SwarmController.getStatus]
     * Test [SwarmController.getResult]
     * */
    @Test
    @DisplayName("test unknown swarm task returns not found response body")
    fun testUnknownSwarmTaskReturnsNotFoundResponseBody() {
        val uuid = "missing-${System.nanoTime()}"

        val status = getStatus(uuid)
        val statusByPath = getStatusByPath(uuid)
        val result = getResult(uuid)

        assertEquals(uuid, status.id)
        assertEquals(404, status.statusCode)
        assertEquals(404, status.pageStatusCode)
        assertFalse(status.isDone)

        assertEquals(status, statusByPath)
        assertEquals(status, result)
    }

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

    private fun countResponses(status: Int? = null): Int {
        val uri = status?.let { "/api/swarm/count?status=$it" } ?: "/api/swarm/count"
        val count = client.get().uri(uri)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<Int>()
            .returnResult()
            .responseBody

        return requireNotNull(count)
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

    private fun getStatusByPath(uuid: String): ScrapeResponse {
        return requireNotNull(
            client.get().uri("/api/swarm/$uuid/status")
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody<ScrapeResponse>()
                .returnResult()
                .responseBody
        )
    }

    private fun getResult(uuid: String): ScrapeResponse {
        return requireNotNull(
            client.get().uri("/api/swarm/$uuid/result")
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
