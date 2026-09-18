package ai.platon.pulsar.swarm.tools

import ai.platon.pulsar.agentic.tools.advanced.crawl.QueryRequest
import ai.platon.pulsar.agentic.tools.advanced.crawl.ScrapeRequest
import ai.platon.pulsar.swarm.service.SwarmService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

/**
 * Tests for the MCP-facing swarm tool surface, focused on batch grouping: the
 * batch id is what lets an agent (or the CLI) treat one submission as a unit.
 *
 * Lives in the plugin module (not `browser4-rest`) because the tool executor
 * moved here with the swarm backend; the REST layer only holds the thin
 * `SwarmController` facade.
 *
 * The matcher helpers below exist because Kotlin checks a non-null parameter
 * before Mockito can consume the (null) matcher result: the matcher is already
 * registered on Mockito's stack, so handing back a throwaway instance keeps the
 * argument check happy without changing matching behaviour.
 */
@Tag("Unit")
@Tag("Fast")
class SwarmToolExecutorTest {

    private fun executor(service: SwarmService) = SwarmToolExecutor(service)

    /** `any()` matcher for the non-null [ScrapeRequest] parameter. */
    private fun anyScrapeRequest(): ScrapeRequest =
        ArgumentMatchers.any(ScrapeRequest::class.java) ?: ScrapeRequest("")

    /** `any()` matcher for the non-null [QueryRequest] parameter. */
    private fun anyQueryRequest(): QueryRequest =
        ArgumentMatchers.any(QueryRequest::class.java) ?: QueryRequest(query = "SELECT 1")

    /** `capture()` matcher for the non-null [ScrapeRequest] parameter. */
    private fun ArgumentCaptor<ScrapeRequest>.captureRequest(): ScrapeRequest =
        capture() ?: ScrapeRequest("")

    /** `capture()` matcher for the non-null [QueryRequest] parameter. */
    private fun ArgumentCaptor<QueryRequest>.captureQuery(): QueryRequest =
        capture() ?: QueryRequest(query = "SELECT 1")

    @Test
    @DisplayName("submit advertises and forwards a batch id")
    fun submitForwardsBatchId() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        Mockito.`when`(service.submit(anyScrapeRequest(), Mockito.any())).thenReturn("task-1")

        val executor = executor(service)
        val result = executor.callFunctionOn(
            "swarm",
            "submit",
            mapOf("payload" to "https://example.com", "batchId" to "batch-9"),
            service
        )

        assertEquals("task-1", result)
        val captor = ArgumentCaptor.forClass(ScrapeRequest::class.java)
        Mockito.verify(service).submit(captor.captureRequest(), ArgumentMatchers.eq("batch-9"))
        assertEquals("batch-9", captor.value.batchId)
    }

    @Test
    @DisplayName("submit without a batch id stays backwards compatible")
    fun submitWithoutBatchId() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        Mockito.`when`(service.submit(anyScrapeRequest(), Mockito.any())).thenReturn("task-1")

        executor(service).callFunctionOn("swarm", "submit", mapOf("payload" to "https://example.com"), service)

        val captor = ArgumentCaptor.forClass(ScrapeRequest::class.java)
        Mockito.verify(service).submit(captor.captureRequest(), ArgumentMatchers.isNull())
        assertNull(captor.value.batchId)
    }

    @Test
    @DisplayName("submit escapes apostrophes in the URL literal")
    fun submitEscapesApostrophes() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        Mockito.`when`(service.submit(anyScrapeRequest(), Mockito.any())).thenReturn("task-1")

        executor(service).callFunctionOn(
            "swarm",
            "submit",
            mapOf("payload" to "https://example.com/o'brien -refresh"),
            service
        )

        val captor = ArgumentCaptor.forClass(ScrapeRequest::class.java)
        Mockito.verify(service).submit(captor.captureRequest(), Mockito.any())
        assertTrue(
            captor.value.sql.contains("o''brien"),
            "apostrophes must be escaped: ${captor.value.sql}"
        )
    }

    @Test
    @DisplayName("query forwards the batch id into QueryRequest")
    fun queryForwardsBatchId() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        Mockito.`when`(service.submit(anyQueryRequest())).thenReturn("task-2")

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

        val captor = ArgumentCaptor.forClass(QueryRequest::class.java)
        Mockito.verify(service).submit(captor.captureQuery())
        assertEquals("batch-9", captor.value.batchId)
        assertEquals("https://example.com", captor.value.url)
    }

    @Test
    @DisplayName("batchStatus delegates to the service aggregate")
    fun batchStatusDelegates() = runBlocking {
        val service = Mockito.mock(SwarmService::class.java)
        val payload = mapOf<String, Any?>("batchId" to "batch-9", "total" to 4)
        Mockito.`when`(service.batchStatus("batch-9")).thenReturn(payload)

        val result = executor(service).callFunctionOn("swarm", "batchStatus", mapOf("batchId" to "batch-9"), service)

        // Verified before the value assertion: an expression-bodied test whose
        // last expression is not Unit is not a valid JUnit 5 test method, and
        // verify() returns the mock's default value.
        Mockito.verify(service).batchStatus("batch-9")
        assertEquals(payload, result)
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
        Mockito.verify(service, Mockito.never()).batchStatus(ArgumentMatchers.anyString())
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
