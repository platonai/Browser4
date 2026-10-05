package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.rest.api.service.crawl.CrawlRequest
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor

/**
 * Tests for the MCP-facing crawl tool surface.
 *
 * The REST controller and the MCP tool are two entry points into the same
 * [CrawlService]; these tests guard the parallel/timeout arguments so that path
 * cannot silently drop them again (the CLI used to echo the requested values
 * while the crawl ran with server defaults).
 */
@Tag("Unit")
@Tag("Fast")
class CrawlToolExecutorTest {

    private fun executor(service: CrawlService) = CrawlToolExecutor(service)

    @Test
    @DisplayName("submit forwards parallelTabs and taskTimeoutMillis to the crawl request")
    fun submitForwardsParallelAndTimeout() = runBlocking {
        val service = Mockito.mock(CrawlService::class.java)
        Mockito.`when`(service.submit(any<CrawlRequest>())).thenReturn("task-1")

        executor(service).callFunctionOn(
            "crawl",
            "submit",
            mapOf(
                "url" to "https://example.com",
                "depth" to 0,
                "parallelTabs" to 2,
                "taskTimeoutMillis" to 120000L,
            ),
            service
        )

        val captor = argumentCaptor<CrawlRequest>()
        Mockito.verify(service).submit(captor.capture())
        val request = captor.firstValue
        assertEquals(2, request.parallelTabs)
        assertEquals(120000L, request.taskTimeoutMillis)
    }

    @Test
    @DisplayName("a missing or non-positive parallel/timeout falls back to the server default (null)")
    fun submitTreatsNonPositiveAsNoPreference() = runBlocking {
        val service = Mockito.mock(CrawlService::class.java)
        Mockito.`when`(service.submit(any<CrawlRequest>())).thenReturn("task-1")

        executor(service).callFunctionOn(
            "crawl",
            "submit",
            mapOf(
                "url" to "https://example.com",
                "parallelTabs" to 0,
                "taskTimeoutMillis" to -5,
            ),
            service
        )

        val captor = argumentCaptor<CrawlRequest>()
        Mockito.verify(service).submit(captor.capture())
        val request = captor.firstValue
        assertNull(request.parallelTabs, "0 must mean 'no preference', not sequential-by-force")
        assertNull(request.taskTimeoutMillis)
    }

    @Test
    @DisplayName("the submit tool spec advertises parallelTabs and taskTimeoutMillis")
    fun toolSpecExposesParallelAndTimeout() {
        val specs = executor(Mockito.mock(CrawlService::class.java)).getToolSpecs()
        val submitArgs = specs["submit"]!!.arguments.map { it.name }
        assertTrue(submitArgs.contains("parallelTabs"), "submit should advertise parallelTabs: $submitArgs")
        assertTrue(submitArgs.contains("taskTimeoutMillis"), "submit should advertise taskTimeoutMillis: $submitArgs")
        assertNotNull(specs["status"])
        assertNotNull(specs["result"])
    }
}
