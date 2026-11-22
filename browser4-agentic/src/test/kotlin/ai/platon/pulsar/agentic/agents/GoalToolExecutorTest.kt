package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `goal.plan` — the only tool a run has for recording what it intends to do.
 *
 * There is deliberately no method that changes the goal; the tests assert that the tool
 * surface stays that way, because "the model cannot redefine success" is what the whole
 * feature rests on (`docs-dev/copilot/agent-goal-mode.md` §5).
 */
class GoalToolExecutorTest {

    private val executor = GoalToolExecutor()
    private val mapper = pulsarObjectMapper()

    private fun state(text: String = "extract the price of the product") =
        GoalState(AgentGoal.fromInstruction(text))

    private fun callPlan(state: GoalState, args: Map<String, Any?>): Map<String, Any?> = runBlocking {
        val json = executor.callFunctionOn(
            executor.domain, "plan", args, GoalToolTarget(state),
        ) as String
        mapper.readValue(json, Map::class.java) as Map<String, Any?>
    }

    @Test
    @DisplayName("the tool exposes one method, and none of them can replace the goal")
    fun theSurfaceCannotMoveTheGoal() {
        val specs = executor.getToolSpecs()

        assertEquals(setOf("plan"), specs.keys)
        val arguments = specs.getValue("plan").arguments.map { it.name }
        assertEquals(setOf("steps", "doneWhen"), arguments.toSet())
        assertFalse(arguments.contains("goal"), "a goal-change argument would be the drift itself")
        assertEquals(GoalToolExecutor.DOMAIN, executor.domain)
        assertFalse(executor.requiresReceiver, "a run without goal mode must get a clear message, not a dispatch error")
    }

    @Test
    @DisplayName("plain strings are accepted as pending steps")
    fun plainStringsArePendingSteps() {
        val state = state()

        val answer = callPlan(state, mapOf("steps" to listOf("open the page", "read the price")))

        assertEquals(2, state.goal.steps.size)
        assertTrue(state.goal.steps.all { it.status == GoalStepStatus.PENDING })
        assertEquals(2, answer["pending"])
        assertEquals(0, answer["done"])
        assertEquals(true, answer["ok"])
    }

    @Test
    @DisplayName("step objects carry the status, and a drop carries its reason")
    fun stepObjectsCarryProgress() {
        val state = state()

        val answer = callPlan(
            state,
            mapOf(
                "steps" to listOf(
                    mapOf("text" to "open the page", "status" to "done"),
                    mapOf("text" to "read the price", "status" to "done"),
                    mapOf("text" to "export csv", "status" to "dropped", "reason" to "no csv writer"),
                    "answer with the list",
                ),
                "doneWhen" to listOf("the price is in the answer"),
            ),
        )

        assertEquals(2, state.goal.doneSteps.size)
        assertEquals(1, state.goal.droppedSteps.size)
        assertEquals(listOf("answer with the list"), state.goal.openSteps.map { it.text })
        assertEquals(listOf("the price is in the answer"), state.goal.doneWhen)
        assertEquals(1, answer["pending"])
        assertEquals(2, answer["done"])
        assertEquals(1, answer["dropped"])
        assertEquals(1, answer["planRevision"])
        // The model does not need the plan echoed back: the pinned block carries it next round.
        assertTrue((answer["note"] as String).contains("pending"), answer.toString())
    }

    @Test
    @DisplayName("an accepted update bumps the state the round loop reads")
    fun anUpdateIsVisibleToTheRoundLoop() {
        val state = state()
        callPlan(state, mapOf("steps" to listOf("open the page")))

        callPlan(state, mapOf("steps" to listOf(mapOf("text" to "open the page", "status" to "done"))))

        assertEquals(2, state.acceptedPlans)
        assertEquals(2, state.goal.planRevision)
        assertTrue(state.goal.openSteps.isEmpty())
    }

    @Test
    @DisplayName("omitting doneWhen keeps the criteria the model already declared")
    fun doneWhenIsKeptWhenOmitted() {
        val state = state()
        callPlan(state, mapOf("steps" to listOf("open"), "doneWhen" to listOf("the price is known")))

        callPlan(state, mapOf("steps" to listOf("open", "read")))

        assertEquals(listOf("the price is known"), state.goal.doneWhen)
    }

    @Test
    @DisplayName("a missing steps argument is a loud parameter error, not a silent no-op")
    fun aMissingStepsArgumentIsLoud() {
        val state = state()

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { executor.callFunctionOn(executor.domain, "plan", emptyMap(), GoalToolTarget(state)) }
        }

        assertTrue(error.message.orEmpty().contains("steps"), error.message.orEmpty())
        assertTrue(state.goal.steps.isEmpty())
    }

    @Test
    @DisplayName("an unknown method is refused and names what is supported")
    fun anUnknownMethodIsRefused() {
        val state = state()

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                executor.callFunctionOn(executor.domain, "goal", mapOf("text" to "else"), GoalToolTarget(state))
            }
        }

        assertTrue(error.message.orEmpty().contains("Unsupported goal method"), error.message.orEmpty())
        assertEquals("extract the price of the product", state.goal.text, "the goal must be untouched")
    }

    @Test
    @DisplayName("with no goal in the run the tool says so instead of crashing the dispatch")
    fun noGoalInThisRun() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                executor.callFunctionOn(executor.domain, "plan", mapOf("steps" to listOf("x")), Any())
            }
        }

        assertTrue(error.message.orEmpty().contains("No goal is active in this run"), error.message.orEmpty())
    }
}
