package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.entities.CommandStatus
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
 * The command domain must speak the same async contract as crawl: the shared
 * status envelope, the same vocabulary, and a cancel tool that reports rather
 * than pretends.
 */
@DisplayName("command async contract")
class CommandToolExecutorTest {

    private lateinit var runner: UserCommandExecutor
    private lateinit var executor: CommandToolExecutor
    private val mapper = pulsarObjectMapper()

    @BeforeEach
    fun setUp() {
        runner = Mockito.mock(UserCommandExecutor::class.java)
        executor = CommandToolExecutor(runner)
    }

    private fun call(method: String, args: Map<String, Any?>): String = runBlocking {
        executor.callFunctionOn("command", method, args, runner) as String
    }

    private fun json(method: String, args: Map<String, Any?>): Map<*, *> =
        mapper.readValue(call(method, args), Map::class.java)

    private fun status(
        id: String = "t1",
        statusCode: Int = ResourceStatus.SC_OK,
        processState: String = "done",
        message: String? = null,
    ) = CommandStatus(id = id, statusCode = statusCode, processState = processState, message = message)

    @Test
    @DisplayName("a finished command reports done, with no invented progress")
    fun doneCommandReportsDone() {
        `when`(runner.getStatus("s1", "t1")).thenReturn(status())

        val envelope = json("status", mapOf("id" to "t1", "sessionId" to "s1"))

        assertEquals("t1", envelope["taskId"])
        assertEquals("done", envelope["status"])
        assertEquals("command_status", envelope["statusTool"])
        assertEquals("command_cancel", envelope["cancelTool"])
        assertFalse(envelope.containsKey("processed"), "no steps recorded means no progress claim")
    }

    @Test
    @DisplayName("the process state maps onto queued / running / failed")
    fun processStatesMapOntoTheVocabulary() {
        `when`(runner.getStatus("s1", "queued")).thenReturn(status(id = "queued", statusCode = ResourceStatus.SC_CREATED, processState = "created"))
        `when`(runner.getStatus("s1", "running")).thenReturn(status(id = "running", statusCode = ResourceStatus.SC_CREATED, processState = "in_progress"))
        `when`(runner.getStatus("s1", "failed")).thenReturn(
            status(id = "failed", statusCode = ResourceStatus.SC_INTERNAL_SERVER_ERROR, processState = "done", message = "boom")
        )

        assertEquals("queued", json("status", mapOf("id" to "queued", "sessionId" to "s1"))["status"])
        assertEquals("running", json("status", mapOf("id" to "running", "sessionId" to "s1"))["status"])
        val failed = json("status", mapOf("id" to "failed", "sessionId" to "s1"))
        assertEquals("failed", failed["status"])
        assertEquals("boom", failed["error"], "the message explains the failure instead of being dropped")
    }

    @Test
    @DisplayName("an unknown command id reports failed, so a client stops polling")
    fun unknownCommandIsTerminal() {
        `when`(runner.getStatus("s1", "nope")).thenReturn(null)

        val envelope = json("status", mapOf("id" to "nope", "sessionId" to "s1"))

        assertEquals("nope", envelope["taskId"])
        assertEquals("failed", envelope["status"])
    }

    @Test
    @DisplayName("cancel stops the runner and reports the outcome")
    fun cancelReportsTheOutcome() {
        `when`(runner.cancelAgentTask("t1")).thenReturn(true)
        `when`(runner.getStatus("s1", "t1")).thenReturn(status(processState = "in_progress", statusCode = ResourceStatus.SC_CREATED))

        val cancelled = json("cancel", mapOf("id" to "t1", "sessionId" to "s1"))
        assertEquals(true, cancelled["cancelled"])
        assertEquals("cancelled", cancelled["status"])

        Mockito.reset(runner)
        `when`(runner.cancelAgentTask("t2")).thenReturn(false)
        `when`(runner.getStatus("s1", "t2")).thenReturn(status(id = "t2"))

        val tooLate = json("cancel", mapOf("id" to "t2", "sessionId" to "s1"))
        assertEquals(false, tooLate["cancelled"], "a task that already finished is reported, not pretended")
        assertEquals("done", tooLate["status"])
    }

    @Test
    @DisplayName("the declared outputSchema accepts what the tools produce")
    fun outputSchemaMatchesProduction() {
        `when`(runner.getStatus("s1", "t1")).thenReturn(status())
        `when`(runner.getResult("s1", "t1")).thenReturn(null)
        `when`(runner.cancelAgentTask("t1")).thenReturn(true)

        listOf("status", "result", "cancel").forEach { method ->
            val spec = executor.getToolSpecs().getValue(method)
            assertNotNull(spec.outputSchema, "$method must declare an outputSchema")
            val issues = ToolResultValidator.validate(
                spec,
                ToolResultValidator.parse(call(method, mapOf("id" to "t1", "sessionId" to "s1"))),
            )
            assertEquals(
                emptyList<String>(), issues.map { "${it.path} ${it.message}" },
                "$method produced a result that violates its own schema",
            )
        }
    }

    @Test
    @DisplayName("run declares the full task policy")
    fun runDeclaresTheTaskPolicy() {
        val policy = executor.getToolSpecs().getValue("run").task

        assertNotNull(policy)
        assertEquals("command_status", policy!!.statusTool)
        assertEquals("command_result", policy.resultTool)
        assertEquals("command_cancel", policy.cancelTool)
    }

    @Test
    @DisplayName("every advertised command tool has documentation and a result contract")
    fun specsAreComplete() {
        val specs = executor.getToolSpecs()

        assertTrue(specs.keys.containsAll(listOf("run", "status", "result", "cancel")))
        specs.values.forEach { spec ->
            assertFalse(spec.description.isNullOrBlank(), "${spec.expression} has no description")
            assertFalse(spec.help.isNullOrBlank(), "${spec.expression} has no help")
        }
    }
}
