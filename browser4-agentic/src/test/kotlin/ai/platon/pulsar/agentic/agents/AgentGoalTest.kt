package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.common.SecretRedactor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The goal model of goal mode (`docs-dev/copilot/agent-goal-mode.md`).
 *
 * The properties asserted here are the ones the feature rests on: the goal is bounded and
 * re-rendered into every request, a plan may evolve while the goal may only be replaced by
 * an operator, and what a supervisor reads is redacted while what the model reads is not.
 */
class AgentGoalTest {

    @Test
    @DisplayName("a one-shot task's goal is its instruction, verbatim and bounded")
    fun theInstructionIsTheGoal() {
        val goal = AgentGoal.fromInstruction("  extract the price of the product  ")

        assertEquals("extract the price of the product", goal.text)
        assertEquals(GoalConfig.SOURCE_INSTRUCTION, goal.source)
        assertEquals(1, goal.revision)
        assertEquals(0, goal.planRevision)
        assertFalse(goal.hasPlan)
    }

    @Test
    @DisplayName("an over-long instruction is truncated with a marker instead of bloating every request")
    fun aLongInstructionIsBounded() {
        val goal = AgentGoal.fromInstruction("x".repeat(GoalConfig.maxChars() + 500))

        assertEquals(GoalConfig.maxChars(), goal.text.length, "the pinned block must stay bounded")
        assertTrue(goal.text.endsWith(GoalConfig.TRUNCATED), goal.text.takeLast(40))
    }

    @Test
    @DisplayName("the pinned block carries the goal, the plan and the rule that makes it binding")
    fun thePinnedBlockIsSelfExplaining() {
        val goal = AgentGoal.fromInstruction("collect the three cheapest offers")
            .withPlan(
                listOf(
                    GoalStep("open the offers page", GoalStepStatus.DONE),
                    GoalStep("read the prices", GoalStepStatus.DONE),
                    GoalStep("export csv", GoalStepStatus.DROPPED, "no csv writer in the workspace"),
                    GoalStep("answer with the list"),
                ),
                listOf("the three prices are in the answer"),
            )

        val block = goal.pinnedBlock(round = 7)

        assertTrue(block.startsWith("## Goal (pinned by the engine"), block.take(80))
        assertTrue(block.contains("collect the three cheapest offers"), block)
        assertTrue(block.contains("Round 7 · 2/4 done, 1 dropped, 1 pending"), block)
        assertTrue(block.contains("✔ 1. open the offers page"), block)
        assertTrue(block.contains("✖ 3. export csv"), block)
        assertTrue(block.contains("▢ 4. answer with the list"), block)
        assertTrue(block.contains("Done when:"), block)
        assertTrue(block.contains("never substitute an easier goal"), block)
        assertFalse(block.contains("goal.check"), "the re-anchor line is opt-in per round")
    }

    @Test
    @DisplayName("the re-anchor line appears only when the round asks for it")
    fun theReassertLineIsOptIn() {
        val goal = AgentGoal.fromInstruction("collect the offers")

        val block = goal.pinnedBlock(round = 12, reassert = true)

        assertTrue(block.contains("⏱ goal.check (12 rounds in)"), block)
        assertTrue(block.contains("restate this goal in one line"), block)
    }

    @Test
    @DisplayName("the block is bounded by maxChars and says so when it truncates")
    fun theBlockIsBounded() {
        val goal = AgentGoal.fromInstruction("g".repeat(700)).withPlan(
            (1..GoalConfig.maxSteps()).map { GoalStep("step $it " + "s".repeat(120)) }
        )

        val block = goal.pinnedBlock(round = 3, maxChars = 600)

        assertTrue(block.length <= 600, "length=${block.length}")
        assertTrue(block.contains("truncated"), "a bounded block must not end silently: ${block.takeLast(120)}")
    }

    @Test
    @DisplayName("a plan update bumps planRevision, bounds the plan and keeps the goal")
    fun aPlanUpdateIsNotAGoalChange() {
        val goal = AgentGoal.fromInstruction("collect the offers")
        val planned = goal.withPlan(
            (1..(GoalConfig.maxSteps() + 5)).map { GoalStep("step $it") },
            (1..(GoalConfig.MAX_DONE_WHEN + 3)).map { "criterion $it" },
        )

        assertEquals(1, planned.revision, "the plan is not the goal")
        assertEquals(1, planned.planRevision)
        assertEquals(GoalConfig.maxSteps(), planned.steps.size)
        assertEquals(GoalConfig.MAX_DONE_WHEN, planned.doneWhen.size)
        assertEquals("collect the offers", planned.text)
    }

    @Test
    @DisplayName("only an operator amendment replaces the goal, and it keeps the plan")
    fun anAmendmentReplacesTheGoalOnly() {
        val planned = AgentGoal.fromInstruction("collect the offers")
            .withPlan(listOf(GoalStep("open the page", GoalStepStatus.DONE), GoalStep("read the prices")))

        val amended = planned.amended("only the three cheapest offers", "operator: /goal")

        assertEquals("only the three cheapest offers", amended.text)
        assertEquals(GoalConfig.SOURCE_OPERATOR, amended.source)
        assertEquals(2, amended.revision)
        assertEquals(1, amended.planRevision, "the progress in flight survives a goal change")
        assertEquals(planned.steps, amended.steps)
        assertEquals("collect the offers", amended.changedFrom)
        assertEquals("operator: /goal", amended.changedReason)
    }

    @Test
    @DisplayName("the supervised view is redacted and counts what the run still owes")
    fun theStatusViewIsRedactedAndCounted() {
        val goal = AgentGoal.fromInstruction(
            """log in with {"password":"hunter2"} then read the prices"""
        ).withPlan(
            listOf(
                GoalStep("log in", GoalStepStatus.DONE),
                GoalStep("read the prices"),
                GoalStep("export", GoalStepStatus.DROPPED, null),
            )
        )

        val view = goal.statusView(round = 4)

        assertFalse(view.text.contains("hunter2"), "a served goal is a read: ${view.text}")
        assertTrue(view.text.contains(SecretRedactor.REDACTED), view.text)
        assertEquals(3, view.steps.size)
        assertEquals(1, view.done)
        // "dropped without a reason" is still owed — the same rule the finish gate applies.
        assertEquals(2, view.pending)
        assertEquals(1, view.dropped)
        assertEquals(4, view.round)
        assertEquals(listOf("read the prices", "export"), view.openStepTexts())
        assertTrue(view.hasPlan)
        assertTrue(view.hasOpenSteps)
    }

    @Test
    @DisplayName("the plan summary reads like a panel line")
    fun theSummaryReadsLikeAPanelLine() {
        val goal = AgentGoal.fromInstruction("collect the offers")
            .withPlan(listOf(GoalStep("open", GoalStepStatus.DONE), GoalStep("read")))
        val amended = goal.amended("only the cheapest", "operator narrowed it")

        val summary = amended.statusView().summary(120)

        assertTrue(summary.contains("only the cheapest"), summary)
        assertTrue(summary.contains("1/2 done"), summary)
        assertTrue(summary.contains("1 pending"), summary)
        assertTrue(summary.contains("goal changed r2"), summary)
    }

    @Test
    @DisplayName("the goal state is the shared box: a plan update is visible to the round loop")
    fun theGoalStateIsShared() {
        val state = GoalState(AgentGoal.fromInstruction("collect the offers"))

        state.applyPlan(listOf(GoalStep("open"), GoalStep("read")), listOf("prices in the answer"))
        assertEquals(2, state.goal.steps.size)
        assertEquals(1, state.acceptedPlans)
        assertEquals(listOf("prices in the answer"), state.goal.doneWhen)

        state.amend("only the cheapest", "operator: /goal")
        assertEquals(2, state.goal.revision)
        assertEquals(2, state.goal.steps.size, "the plan survives an operator goal change")
    }

    @Test
    @DisplayName("a plan of blank steps is refused instead of silently erasing the plan")
    fun aBlankPlanIsRefused() {
        val state = GoalState(AgentGoal.fromInstruction("collect the offers"))
        state.applyPlan(listOf(GoalStep("open")), null)

        val error = assertThrows(IllegalArgumentException::class.java) {
            state.applyPlan(listOf(GoalStep("   ")), null)
        }

        assertTrue(error.message.orEmpty().contains("non-blank"), error.message.orEmpty())
        assertEquals(1, state.goal.steps.size, "the previous plan must still be there")
    }

    @Test
    @DisplayName("an empty plan list clears the plan on purpose")
    fun anEmptyPlanClearsThePlan() {
        val state = GoalState(AgentGoal.fromInstruction("collect the offers"))
        state.applyPlan(listOf(GoalStep("open")), null)

        state.applyPlan(emptyList(), null)

        assertTrue(state.goal.steps.isEmpty())
        assertEquals(2, state.goal.planRevision)
    }
}
