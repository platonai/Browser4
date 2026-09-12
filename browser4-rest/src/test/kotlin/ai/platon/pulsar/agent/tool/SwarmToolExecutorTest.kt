package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.advanced.crawl.QueryRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.rest.api.service.SwarmService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq

/**
 * Tests for the MCP-facing swarm tool surface, focused on batch grouping: the
 * batch id is what lets an agent (or the CLI) treat one submission as a unit.
 */
@Tag("Unit")
@Tag("Fast")
class SwarmToolExecutorTest {

    private fun executor(service: SwarmService) = SwarmToolExecutor(service)

    @Test
    @DisplayName("submit advertises and forwards a batch id")
    fun submitForwardsBatchId() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        Mockito.`when`(service.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("task-1")

        val executor = executor(service)
        val result = executor.callFunctionOn(
            "swarm",
            "submit",
            mapOf("payload" to "https://example.com", "batchId" to "batch-9"),
            service
        )

        assertEquals("task-1", result)
        val captor = argumentCaptor<ScrapeRequest>()
        Mockito.verify(service).submit(captor.capture(), eq("batch-9"))
        assertEquals("batch-9", captor.firstValue.batchId)
    }

    @Test
    @DisplayName("submit without a batch id stays backwards compatible")
    fun submitWithoutBatchId() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        Mockito.`when`(service.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("task-1")

        executor(service).callFunctionOn("swarm", "submit", mapOf("payload" to "https://example.com"), service)

        val captor = argumentCaptor<ScrapeRequest>()
        Mockito.verify(service).submit(captor.capture(), eq(null))
        assertNull(captor.firstValue.batchId)
    }

    @Test
    @DisplayName("submit escapes apostrophes in the URL literal")
    fun submitEscapesApostrophes() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        Mockito.`when`(service.submit(any<ScrapeRequest>(), anyOrNull())).thenReturn("task-1")

        executor(service).callFunctionOn(
            "swarm",
            "submit",
            mapOf("payload" to "https://example.com/o'brien -refresh"),
            service
        )

        val captor = argumentCaptor<ScrapeRequest>()
        Mockito.verify(service).submit(captor.capture(), anyOrNull())
        assertTrue(
            captor.firstValue.sql.contains("o''brien"),
            "apostrophes must be escaped: ${captor.firstValue.sql}"
        )
    }

    @Test
    @DisplayName("query forwards the batch id into QueryRequest")
    fun queryForwardsBatchId() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        Mockito.`when`(service.submit(any<QueryRequest>())).thenReturn("task-2")

        executor(service).callFunctionOn(
            "swarm",
            "query",
            mapOf(
                "url" to "https://example.com",
                "query" to "SELECT 1",
                "args" to "-parse",
                "batchId" to "batch-9",
            ),
            service
        )

        val captor = argumentCaptor<QueryRequest>()
        Mockito.verify(service).submit(captor.capture())
        assertEquals("batch-9", captor.firstValue.batchId)
        assertEquals("https://example.com", captor.firstValue.url)
    }

    @Test
    @DisplayName("batchStatus delegates to the service aggregate")
    fun batchStatusDelegates() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        val payload = mapOf<String, Any?>("batchId" to "batch-9", "total" to 4)
        Mockito.`when`(service.batchStatus("batch-9")).thenReturn(payload)

        val result = executor(service).callFunctionOn("swarm", "batchStatus", mapOf("batchId" to "batch-9"), service)

        assertEquals(payload, result)
        Mockito.verify(service).batchStatus("batch-9")
    }

    @Test
    @DisplayName("batchStatus rejects a blank batch id")
    fun batchStatusRejectsBlank() {
        val service = Mockito.mock(SwarmService::class.java)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                executor(service).callFunctionOn("swarm", "batchStatus", mapOf("batchId" to "  "), service)
            }
        }
        Mockito.verify(service, Mockito.never()).batchStatus(Mockito.anyString())
    }

    @Test
    @DisplayName("the tool spec exposes the batch arguments to MCP clients")
    fun toolSpecExposesBatchArguments() {
        val executor = executor(Mockito.mock(SwarmService::class.java))
        val specs = executor.getToolSpecs()

        val submitArgs = specs["submit"]!!.arguments.map { it.name }
        assertTrue(submitArgs.contains("batchId"), "submit should advertise batchId: $submitArgs")

        val queryArgs = specs["query"]!!.arguments.map { it.name }
        assertTrue(queryArgs.contains("batchId"), "query should advertise batchId: $queryArgs")

        val batchSpec = specs["batchStatus"]
        assertNotNull(batchSpec, "batchStatus tool must be registered")
        assertEquals(listOf("batchId"), batchSpec!!.arguments.map { it.name })
    }
}
