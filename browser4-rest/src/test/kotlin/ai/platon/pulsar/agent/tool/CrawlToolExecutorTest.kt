package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.TaskEnvelopes
import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.service.CrawlPageResult
import ai.platon.pulsar.rest.api.service.CrawlResponse
import ai.platon.pulsar.rest.api.service.CrawlService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/**
 * The crawl domain is the reference implementation of the async contract: JSON
 * status envelopes, a cancel tool, and an `outputSchema` that the produced text
 * actually satisfies.
 */
@DisplayName("crawl async contract")
class CrawlToolExecutorTest {

    private lateinit var service: CrawlService
    private lateinit var executor: CrawlToolExecutor
    private val mapper = pulsarObjectMapper()

    @BeforeEach
    fun setUp() {
        service = Mockito.mock(CrawlService::class.java)
        executor = CrawlToolExecutor(service)
    }

    private fun call(method: String, args: Map<String, Any?>): String = runBlocking {
        executor.callFunctionOn("crawl", method, args, service) as String
    }

    private fun json(method: String, args: Map<String, Any?>): Map<*, *> =
        mapper.readValue(call(method, args), Map::class.java)

    @Test
    @DisplayName("status answers with the shared envelope as JSON")
    fun statusIsJsonEnvelope() {
        `when`(service.getResult("t1")).thenReturn(
            CrawlResponse(taskId = "t1", status = "RUNNING", pagesFound = 3, linksDiscovered = 7)
        )

        val envelope = json("status", mapOf("id" to "t1"))

        assertEquals("t1", envelope["taskId"])
        assertEquals("running", envelope["status"])
        assertEquals(3, envelope["processed"])
        assertEquals("crawl_status", envelope["statusTool"])
        assertEquals("crawl_cancel", envelope["cancelTool"])
        assertFalse(envelope.containsKey("pages"), "status must stay small; the payload belongs to result")
    }

    @Test
    @DisplayName("a finished crawl reports done, and result adds the pages")
    fun resultCarriesThePages() {
        `when`(service.getResult("t1")).thenReturn(
            CrawlResponse(
                taskId = "t1",
                status = "OK",
                pagesFound = 1,
                pages = listOf(CrawlPageResult(url = "https://example.com", title = "Example Domain")),
            )
        )

        val status = json("status", mapOf("id" to "t1"))
        val result = json("result", mapOf("id" to "t1"))

        assertEquals("done", status["status"])
        assertFalse(status.containsKey("pages"))
        val pages = result["pages"] as List<*>
        assertEquals(1, pages.size)
    }

    @Test
    @DisplayName("an unknown task id is reported, not thrown")
    fun unknownTaskIsReported() {
        `when`(service.getResult("nope")).thenReturn(CrawlResponse())

        val envelope = json("status", mapOf("id" to "nope"))

        assertEquals("nope", envelope["taskId"])
        assertEquals("failed", envelope["status"])
        assertTrue(envelope["error"].toString().contains("Unknown task id"))
    }

    @Test
    @DisplayName("cancel stops the task and reports the terminal status")
    fun cancelStopsTheTask() {
        `when`(service.cancel("t1")).thenReturn(true)
        `when`(service.getResult("t1")).thenReturn(CrawlResponse(taskId = "t1", status = "CANCELLED"))

        val envelope = json("cancel", mapOf("id" to "t1"))

        assertEquals(true, envelope["cancelled"])
        assertEquals("cancelled", envelope["status"])
        Mockito.verify(service).cancel("t1")
    }

    @Test
    @DisplayName("cancelling a task that is not running is reported, not treated as an error")
    fun cancelOfFinishedTaskIsReported() {
        `when`(service.cancel("t1")).thenReturn(false)
        `when`(service.getResult("t1")).thenReturn(CrawlResponse(taskId = "t1", status = "OK"))

        val envelope = json("cancel", mapOf("id" to "t1"))

        assertEquals(false, envelope["cancelled"])
        assertEquals("done", envelope["status"], "the desired end state already holds")
    }

    @Test
    @DisplayName("the declared outputSchema accepts what the tools produce")
    fun outputSchemaMatchesProduction() {
        `when`(service.getResult("t1")).thenReturn(CrawlResponse(taskId = "t1", status = "OK", pagesFound = 2))
        `when`(service.cancel("t1")).thenReturn(true)

        listOf("status" to mapOf("id" to "t1"), "result" to mapOf("id" to "t1"), "cancel" to mapOf("id" to "t1"))
            .forEach { (method, args) ->
                val spec = executor.getToolSpecs().getValue(method)
                assertNotNull(spec.outputSchema, "$method must declare an outputSchema")
                val issues = ToolResultValidator.validate(spec, ToolResultValidator.parse(call(method, args)))
                assertEquals(
                    emptyList<String>(), issues.map { "${it.path} ${it.message}" },
                    "$method produced a result that violates its own schema",
                )
            }
    }

    @Test
    @DisplayName("submit declares the full task policy, and the schema names a cancelled status")
    fun submitDeclaresTheTaskPolicy() {
        val spec = executor.getToolSpecs().getValue("submit")
        val policy = spec.task

        assertNotNull(policy)
        assertEquals("crawl_status", policy!!.statusTool)
        assertEquals("crawl_result", policy.resultTool)
        assertEquals("crawl_cancel", policy.cancelTool)
        assertTrue(
            spec.outputSchema!!.contains("\"cancelled\""),
            "the submit envelope must admit every status a task can end in",
        )
        assertTrue(TaskEnvelopes.STATUSES.all { spec.outputSchema!!.contains("\"$it\"") })
    }
}
