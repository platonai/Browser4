package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.entities.CommandAgentHistory
import ai.platon.pulsar.rest.api.entities.CommandAgentState
import ai.platon.pulsar.rest.api.entities.CommandStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import java.time.Instant

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
        cancelled: Boolean = false,
    ) = CommandStatus(id = id, statusCode = statusCode, processState = processState, message = message, cancelled = cancelled)

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
    @DisplayName("a running agent task reports elapsed time and its step count")
    fun runningAgentTaskReportsElapsedAndSteps() {
        val started = Instant.now().minusSeconds(90)
        val running = status(
            statusCode = ResourceStatus.SC_PROCESSING,
            processState = "in_progress",
            message = "Agent is working"
        ).apply {
            startedTime = started
            agentHistory = CommandAgentHistory(
                mutableListOf(CommandAgentState(step = 12), CommandAgentState(step = 13))
            )
        }
        `when`(runner.getStatus("s1", "t1")).thenReturn(running)

        val envelope = json("status", mapOf("id" to "t1", "sessionId" to "s1"))

        assertEquals("running", envelope["status"])
        assertEquals(2, envelope["processed"], "the agent's step count is the progress signal")
        val elapsedMs = (envelope["elapsedMs"] as Number).toLong()
        assertTrue(elapsedMs >= 90_000, "elapsedMs must reflect startedTime, got $elapsedMs")
    }

    @Test
    @DisplayName("the live tool-call counter is reported as progress while a CLI run is in flight")
    fun liveToolExecutionsAreReportedAsProgress() {
        // A CLI tool-loop run writes its step history only when it completes, so
        // the tool counter is the only progress signal available mid-run.
        val running = status(
            statusCode = ResourceStatus.SC_PROCESSING,
            processState = "in_progress",
            message = "Agent is working"
        ).apply { toolExecutions = 37 }
        `when`(runner.getStatus("s1", "t1")).thenReturn(running)

        val envelope = json("status", mapOf("id" to "t1", "sessionId" to "s1"))

        assertEquals("running", envelope["status"])
        assertEquals(37, envelope["processed"], "the tool counter is the only live progress signal")
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
        assertEquals("task already finished", tooLate["reason"], "and the answer says which case it was")
    }

    @Test
    @DisplayName("a live page visit says it cannot be cancelled, not that it is unknown")
    fun livePageVisitExplainsWhyItCannotBeCancelled() {
        `when`(runner.cancelAgentTask("p1")).thenReturn(false)
        `when`(runner.isPageVisitTask("p1")).thenReturn(true)
        `when`(runner.getStatus("s1", "p1")).thenReturn(
            status(id = "p1", statusCode = ResourceStatus.SC_CREATED, processState = "in_progress")
        )

        val cancelled = json("cancel", mapOf("id" to "p1", "sessionId" to "s1"))

        assertEquals(false, cancelled["cancelled"])
        assertEquals("running", cancelled["status"], "the task is still running — it is not unknown")
        assertEquals(
            "page visit tasks run to completion and cannot be cancelled",
            cancelled["reason"],
        )
    }

    @Test
    @DisplayName("a running synchronous task explains why nothing was cancelled")
    fun runningSynchronousTaskExplainsWhyNothingWasCancelled() {
        `when`(runner.cancelAgentTask("y1")).thenReturn(false)
        `when`(runner.getStatus("s1", "y1")).thenReturn(
            status(id = "y1", statusCode = ResourceStatus.SC_CREATED, processState = "in_progress")
        )

        val cancelled = json("cancel", mapOf("id" to "y1", "sessionId" to "s1"))

        assertEquals(false, cancelled["cancelled"])
        assertEquals("running", cancelled["status"])
        val reason = cancelled["reason"].toString()
        assertTrue(
            reason.contains("synchronous") && reason.contains("no cancellable job"),
            "the reason must explain the synchronous-run case instead of staying blank: $reason",
        )
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
    @DisplayName("a cancelled task reports the cancelled word on later polls, not failed")
    fun cancelledTaskReportsTheCancelledWord() {
        `when`(runner.getStatus("s1", "t1")).thenReturn(
            status(id = "t1", statusCode = ResourceStatus.SC_EXPECTATION_FAILED, message = "Task cancelled", cancelled = true)
        )

        val envelope = json("status", mapOf("id" to "t1", "sessionId" to "s1"))

        assertEquals("cancelled", envelope["status"], "a user-requested cancel must not look like a task failure")
        assertEquals(ResourceStatus.SC_EXPECTATION_FAILED, envelope["statusCode"])
        assertEquals("Task cancelled", envelope["message"])
        assertFalse(envelope.containsKey("error"), "cancelled is not an error")
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
    @DisplayName("run passes an explicit coding flag to the executor")
    fun runForwardsExplicitCodingFlag() {
        runBlocking {
            `when`(runner.submitPlainCommand("s1", "fix pom.xml", null, true, null, null)).thenReturn("t-coding")

            val taskId = call("run", mapOf("command" to "fix pom.xml", "sessionId" to "s1", "coding" to true))

            assertEquals("t-coding", taskId)
            Mockito.verify(runner).submitPlainCommand("s1", "fix pom.xml", null, true, null, null)
        }
    }

    @Test
    @DisplayName("run without the coding flag submits a browser task (the default, never guessed)")
    fun runWithoutCodingFlagIsABrowserTask() {
        runBlocking {
            `when`(runner.executePlainCommand("s1", "open the page", null, null, null)).thenReturn(status())

            json("run", mapOf("command" to "open the page", "sessionId" to "s1", "async" to false))

            Mockito.verify(runner).executePlainCommand("s1", "open the page", null, null, null)
        }
    }

    @Test
    @DisplayName("run declares the coding argument so clients can discover it")
    fun runDeclaresTheCodingArgument() {
        val coding = executor.getToolSpecs().getValue("run").arguments.first { it.name == "coding" }
        val description = coding.description ?: ""

        assertTrue(description.contains("explicit", ignoreCase = true))
        assertTrue(description.contains("browser", ignoreCase = true))
    }

    @Test
    @DisplayName("run rejects a non-boolean coding flag")
    fun runRejectsNonBooleanCoding() {
        val ex = assertThrows<IllegalArgumentException> {
            call("run", mapOf("command" to "task", "sessionId" to "s1", "coding" to "yes"))
        }

        assertTrue(ex.message!!.contains("coding"), ex.message)
    }

    @Test
    @DisplayName("run accepts the only remaining engine id and forwards nothing")
    fun runAcceptsTheRemainingEngineId() {
        runBlocking {
            `when`(runner.submitPlainCommand("s1", "task", null, null, null, null, null)).thenReturn("t-cli")

            val taskId = call("run", mapOf("command" to "task", "sessionId" to "s1", "engine" to "cli"))

            assertEquals("t-cli", taskId)
            Mockito.verify(runner).submitPlainCommand("s1", "task", null, null, null, null, null)
        }
    }

    @Test
    @DisplayName("run rejects the removed observe-act engine instead of silently running another one")
    fun runRejectsRemovedEngine() {
        val ex = assertThrows<IllegalArgumentException> {
            call("run", mapOf("command" to "task", "sessionId" to "s1", "engine" to "observe-act"))
        }

        assertTrue(ex.message!!.contains("removed"), ex.message)
        assertTrue(ex.message!!.contains("observe-act"), ex.message)
        Mockito.verifyNoInteractions(runner)
    }

    @Test
    @DisplayName("run rejects an unknown engine id loudly")
    fun runRejectsUnknownEngine() {
        val ex = assertThrows<IllegalArgumentException> {
            call("run", mapOf("command" to "task", "sessionId" to "s1", "engine" to "clli"))
        }

        assertTrue(ex.message!!.contains("Unknown engine"), ex.message)
        Mockito.verifyNoInteractions(runner)
    }

    @Test
    @DisplayName("run forwards the access preset so a task can be narrowed to read-build")
    fun runForwardsAccessPreset() {
        runBlocking {
            `when`(
                runner.submitPlainCommand("s1", "report the module", null, null, null, null, AccessPreset.READ_BUILD)
            ).thenReturn("t-access")

            val taskId = call(
                "run",
                mapOf("command" to "report the module", "sessionId" to "s1", "access" to "read-build")
            )

            assertEquals("t-access", taskId)
            Mockito.verify(runner)
                .submitPlainCommand("s1", "report the module", null, null, null, null, AccessPreset.READ_BUILD)
        }
    }

    @Test
    @DisplayName("run rejects an unknown access preset instead of silently granting full")
    fun runRejectsUnknownAccessPreset() {
        val ex = assertThrows<IllegalArgumentException> {
            call("run", mapOf("command" to "task", "sessionId" to "s1", "access" to "readonly"))
        }

        assertTrue(ex.message!!.contains("Unknown access"), ex.message)
        Mockito.verifyNoInteractions(runner)
    }

    @Test
    @DisplayName("run forwards unattended=false for a watched (TTY) task")
    fun runForwardsWatchedTask() {
        runBlocking {
            `when`(
                runner.submitPlainCommand(
                    "s1", "report the module", null, null, null, null,
                    AccessPreset.READ, false, null, null
                )
            ).thenReturn("t-watched")

            val taskId = call(
                "run",
                mapOf(
                    "command" to "report the module",
                    "sessionId" to "s1",
                    "access" to "read",
                    "unattended" to false,
                )
            )

            assertEquals("t-watched", taskId)
            Mockito.verify(runner).submitPlainCommand(
                "s1", "report the module", null, null, null, null,
                AccessPreset.READ, false, null, null
            )
        }
    }

    @Test
    @DisplayName("run treats an omitted unattended flag as an unattended job")
    fun runDefaultsToUnattended() {
        runBlocking {
            `when`(
                runner.submitPlainCommand("s1", "task", null, null, null, null, null, true, null, null)
            ).thenReturn("t-unattended")

            val taskId = call("run", mapOf("command" to "task", "sessionId" to "s1"))

            assertEquals("t-unattended", taskId)
            Mockito.verify(runner).submitPlainCommand(
                "s1", "task", null, null, null, null, null, true, null, null
            )
        }
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
