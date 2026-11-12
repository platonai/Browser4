package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The cross-task conversation of one agent session.
 *
 * Origin: an `agent run -i --coding` session of related tasks behaved like a fresh
 * process per task — task N had no idea what task N-1 had done — and nothing
 * compressed the little context there was.
 */
@DisplayName("agent session conversation")
class SessionConversationTest {

    private fun status(
        instruction: String?,
        statusCode: Int = ResourceStatus.SC_OK,
        summary: String? = null,
        files: List<String>? = null,
        problems: List<String>? = null,
        findings: List<String>? = null,
        deliverables: List<String>? = null,
        failureReason: String? = null,
        cancelled: Boolean = false,
        elapsedMs: Long? = null,
    ): AgentTaskStatus = AgentTaskStatus(id = "task-${instruction.hashCode().toUInt()}").apply {
        submittedTask = instruction
        this.statusCode = statusCode
        this.cancelled = cancelled
        this.failureReason = failureReason
        this.deliverables = deliverables
        val state = AgentState(step = 3, instruction = instruction.orEmpty()).apply {
            this.isComplete = true
            this.summary = summary
            this.keyFindings = findings
            this.filesChanged = files
            this.problems = problems
        }
        agentHistory = AgentHistory(mutableListOf(state))
        // `done()` stamps finishTime itself, so the synthetic window is set after it.
        done()
        if (elapsedMs != null) {
            startedTime = Instant.ofEpochMilli(1_000_000)
            finishTime = Instant.ofEpochMilli(1_000_000 + elapsedMs)
        }
    }

    @Test
    @DisplayName("a session that finished no task renders no block")
    fun anEmptySessionRendersNothing() {
        val conversation = SessionConversation()

        assertEquals("", conversation.renderBlock())
        assertEquals(0, conversation.size)
    }

    @Test
    @DisplayName("a finished task is remembered with its report")
    fun aFinishedTaskIsRemembered() {
        val conversation = SessionConversation()
        conversation.record(
            status(
                instruction = "add the CSV export",
                summary = "Added the export and its CLI flag.",
                files = listOf("browser4-cli/src/main.rs"),
                problems = listOf("the xlsx writer was unavailable"),
                findings = listOf("the flag is --format csv"),
                elapsedMs = 133_000,
            )
        )

        val block = conversation.renderBlock()

        assertTrue(block.startsWith(SESSION_BLOCK_MARKER), block)
        assertTrue(block.contains("1 earlier task(s)"), block)
        assertTrue(block.contains("\"add the CSV export\" — done in 2m 13s"), block)
        assertTrue(block.contains("Added the export and its CLI flag."), block)
        assertTrue(block.contains("files: browser4-cli/src/main.rs"), block)
        assertTrue(block.contains("problems: the xlsx writer was unavailable"), block)
        assertTrue(block.contains("findings: the flag is --format csv"), block)
        // The block must tell the model which instruction is the real task.
        assertTrue(block.contains("The instruction at the top of this"), block)
    }

    @Test
    @DisplayName("a failed task is remembered with its reason, a cancelled one as cancelled")
    fun failuresAndCancelsAreRemembered() {
        val conversation = SessionConversation()
        conversation.record(
            status(
                instruction = "fix the build",
                statusCode = ResourceStatus.SC_EXPECTATION_FAILED,
                failureReason = "TokenBudgetExceededException: budget exceeded",
            )
        )
        conversation.record(
            status(instruction = "extract prices", cancelled = true, deliverables = listOf("prices.csv"))
        )

        val block = conversation.renderBlock()

        assertTrue(block.contains("\"fix the build\" — failed"), block)
        assertTrue(block.contains("TokenBudgetExceededException: budget exceeded"), block)
        assertTrue(block.contains("\"extract prices\" — cancelled"), block)
        // An aborted run's deliverables are still the files the next task builds on.
        assertTrue(block.contains("files: prices.csv"), block)
    }

    @Test
    @DisplayName("a task with no instruction to remember is skipped")
    fun aBlankInstructionIsSkipped() {
        val conversation = SessionConversation()

        conversation.record(status(instruction = null))
        conversation.record(status(instruction = "   "))

        assertEquals(0, conversation.size)
        assertEquals("", conversation.renderBlock())
    }

    @Test
    @DisplayName("only the newest tasks keep their details; older ones roll up into one line")
    fun olderTasksAreCompacted() {
        val conversation = SessionConversation(detailEntries = 2)
        (1..5).forEach { index ->
            conversation.record(status(instruction = "task $index", summary = "summary $index"))
        }

        val block = conversation.renderBlock()

        assertTrue(block.contains("3 earlier task(s), compacted:"), block)
        assertTrue(block.contains("\"task 1\" (done)"), block)
        assertTrue(block.contains("\"task 3\" (done)"), block)
        // The two newest keep their details.
        assertTrue(block.contains("\"task 4\" — done — summary 4"), block)
        assertTrue(block.contains("\"task 5\" — done — summary 5"), block)
        assertFalse(block.contains("summary 1"), "a compacted task shows no details: $block")
    }

    @Test
    @DisplayName("an over-budget block compacts automatically instead of growing")
    fun anOverBudgetBlockCompacts() {
        val budget = 1_200
        val conversation = SessionConversation(budgetChars = budget, detailEntries = 5)
        (1..6).forEach { index ->
            conversation.record(
                status(
                    instruction = "task $index " + "x".repeat(60),
                    summary = "summary $index " + "y".repeat(80),
                    files = listOf("module/file-$index.kt"),
                )
            )
        }

        val block = conversation.renderBlock()

        assertTrue(block.length <= budget, "the block must respect its budget: ${block.length} > $budget\n$block")
        // Compaction is a rollup, never a silent drop of the whole session.
        assertTrue(block.contains("earlier task(s)"), block)
        assertTrue(block.contains("\"task 6"), "the newest task always survives: $block")
        assertTrue(block.contains("do not repeat work"), "the guidance text survives: $block")
    }

    @Test
    @DisplayName("a budget even the digest cannot fit falls back to goal + newest task")
    fun aTinyBudgetDegradesGracefully() {
        val budget = SessionConversation.MIN_BUDGET_CHARS
        val conversation = SessionConversation(budgetChars = budget)
        (1..3).forEach { index ->
            conversation.record(
                status(
                    instruction = "task $index " + "x".repeat(80),
                    summary = "summary $index " + "y".repeat(120),
                )
            )
        }

        val block = conversation.renderBlock()

        assertTrue(block.length <= budget, "got ${block.length} > $budget\n$block")
        assertTrue(block.contains(SESSION_BLOCK_MARKER), block)
        // The last resort must not degrade into "header only": the goal and the newest
        // task are what the next instruction is written against.
        assertTrue(block.contains("Session goal"), block)
        assertTrue(block.contains("\"task 3 "), block)
    }

    // ---- focus: the session must not forget what it set out to do -----------

    /// The failure this guards against: a recency-biased compaction folds the FIRST
    /// task (the session's goal) into "and N more" and then evicts it, so a long
    /// session only remembers its last few sub-steps.
    @Test
    @DisplayName("the session goal survives every compaction step")
    fun theSessionGoalSurvivesCompaction() {
        val conversation = SessionConversation(budgetChars = 900, detailEntries = 2)
        conversation.record(
            status(instruction = "implement the CSV export end to end", summary = "step 1")
        )
        (2..9).forEach { index ->
            conversation.record(
                status(
                    instruction = "sub-step $index " + "x".repeat(50),
                    summary = "did sub-step $index " + "y".repeat(60),
                    problems = if (index == 2) listOf("the xlsx writer was unavailable") else null,
                )
            )
        }

        val block = conversation.renderBlock()

        assertTrue(block.length <= 900, "got ${block.length}\n$block")
        assertTrue(
            block.contains("Session goal (set by the first task"),
            "the founding task must stay labelled as the goal after 9 tasks: $block",
        )
        assertTrue(
            block.contains("implement the CSV export end to end"),
            "the goal must still say WHAT the session is for: $block",
        )
        // The first task's detail bullet is long gone — the goal line is what carries it.
        assertFalse(block.contains("\"implement the CSV export end to end\" — done"), block)
    }

    @Test
    @DisplayName("the goal is anchored once, from the first task only")
    fun theGoalComesFromTheFirstTaskOnly() {
        val conversation = SessionConversation(detailEntries = 1)
        conversation.record(status(instruction = "the original goal"))
        conversation.record(status(instruction = "a later sub-step"))

        assertEquals("the original goal", conversation.goal)
        val block = conversation.renderBlock()
        assertTrue(block.contains("Session goal"), block)
        assertEquals(
            1,
            Regex("Session goal").findAll(block).count(),
            "the goal line is stated once per block: $block",
        )

        // A one-task session has not folded anything: the goal would only repeat the
        // bullet that is already there.
        val single = SessionConversation().apply { record(status(instruction = "only task")) }
        assertFalse(single.renderBlock().contains("Session goal"), single.renderBlock())

        conversation.clear()
        assertNull(conversation.goal)
        assertNull(conversation.renderBlock().ifBlank { null })
    }

    /// A problem reported by an *older* task is exactly what "don't forget" means: the
    /// bullet that carried it is folded away, so it gets its own section.
    @Test
    @DisplayName("open problems from folded tasks stay visible")
    fun openProblemsFromFoldedTasksStayVisible() {
        val conversation = SessionConversation(detailEntries = 1)
        conversation.record(
            status(instruction = "fix the build", problems = listOf("the CI gate was not run"))
        )
        conversation.record(status(instruction = "add the export"))
        conversation.record(status(instruction = "run the tests"))

        val block = conversation.renderBlock()

        assertTrue(
            block.contains("Problems earlier tasks reported as unresolved"),
            block,
        )
        assertTrue(
            block.contains("\"fix the build\": the CI gate was not run"),
            "an unresolved problem must outlive its folded bullet: $block",
        )
        // A task still in detail keeps its own problems in its bullet — no duplication.
        val detailed = SessionConversation(detailEntries = 3)
        detailed.record(status(instruction = "a", problems = listOf("p1")))
        assertFalse(
            detailed.renderBlock().contains("Problems earlier tasks reported"),
            detailed.renderBlock(),
        )
    }

    /// The last resort used to keep the header and stale titles while cutting the one
    /// bullet the next task refers to.
    @Test
    @DisplayName("degradation never keeps stale titles instead of the newest task")
    fun degradationNeverDropsTheNewestTask() {
        val conversation = SessionConversation(budgetChars = SessionConversation.MIN_BUDGET_CHARS)
        (1..6).forEach { index ->
            conversation.record(
                status(
                    instruction = "task $index " + "x".repeat(80),
                    summary = "summary $index " + "y".repeat(200),
                )
            )
        }

        val block = conversation.renderBlock()

        assertTrue(block.length <= SessionConversation.MIN_BUDGET_CHARS, block)
        assertTrue(block.contains("\"task 6 "), "the newest task is the one that matters: $block")
        assertTrue(block.contains("Session goal"), block)
    }

    /// The mirror image of forgetting the goal: a block that keeps insisting on the
    /// founding task fights the instruction the user just typed.
    @Test
    @DisplayName("a mid-session goal correction is recorded and outranks the goal")
    fun aGoalCorrectionOutranksTheGoal() {
        val conversation = SessionConversation(detailEntries = 1)
        conversation.record(status(instruction = "implement the web UI end to end"))
        conversation.record(status(instruction = "add the settings page"))
        conversation.record(status(instruction = "actually, ship the CLI flag first and drop the web UI"))
        conversation.record(status(instruction = "run the CLI tests"))

        val block = conversation.renderBlock()

        assertTrue(block.contains("User corrections (they supersede the goal below where they conflict)"), block)
        assertTrue(
            block.contains("task 3: \"actually, ship the CLI flag first and drop the web UI\""),
            "the redirection must be recorded with where it happened: $block",
        )
        // Both facts stay visible: the original anchor and the newer direction.
        assertTrue(block.contains("Session goal (set by the first task; later corrections supersede it)"), block)
        assertTrue(block.contains("implement the web UI end to end"), block)
        assertTrue(block.contains("if it contradicts anything below, it wins"), block)
        // The correction is rendered ABOVE the goal, so a fold can never hide it first.
        assertTrue(
            block.indexOf("User corrections") < block.indexOf("Session goal"),
            "the correction must precede the goal: $block",
        )
        assertEquals(
            listOf("actually, ship the CLI flag first and drop the web UI"),
            conversation.correctionTexts(),
        )
    }

    @Test
    @DisplayName("an ordinary next task is not mistaken for a correction")
    fun ordinaryTasksAreNotCorrections() {
        val conversation = SessionConversation(detailEntries = 1)
        conversation.record(status(instruction = "implement the web UI"))
        conversation.record(status(instruction = "add the settings page"))
        conversation.record(status(instruction = "write the tests for the settings page"))

        assertEquals(emptyList<String>(), conversation.correctionTexts())
        assertFalse(conversation.renderBlock().contains("User corrections"), conversation.renderBlock())
    }

    /// A line typed into a running task is the most direct statement of intent there is,
    /// and it used to die with the run it steered.
    @Test
    @DisplayName("a correction typed into a running task is recorded")
    fun midTaskSteeringIsRecorded() {
        val conversation = SessionConversation(detailEntries = 1)
        conversation.record(status(instruction = "implement the export"))
        conversation.noteUserInput(listOf("no, use CSV not XLSX"))
        conversation.noteUserInput(listOf("   ", "keep the header row"))

        assertEquals(
            listOf("no, use CSV not XLSX", "keep the header row"),
            conversation.correctionTexts(),
        )
        val block = conversation.renderBlock()
        assertTrue(block.contains("during task 2: \"no, use CSV not XLSX\""), block)
        // Deduplicated: a repeated line is one instruction, not two.
        conversation.noteUserInput(listOf("no, use CSV not XLSX"))
        assertEquals(2, conversation.correctionTexts().size)
    }

    @Test
    @DisplayName("corrections are bounded to the newest few")
    fun correctionsAreBounded() {
        val conversation = SessionConversation(detailEntries = 1)
        conversation.record(status(instruction = "start"))
        (1..(SessionConversation.CORRECTION_ENTRIES + 3)).forEach { index ->
            conversation.noteUserInput(listOf("change of plan $index"))
        }

        val texts = conversation.correctionTexts()
        assertEquals(SessionConversation.CORRECTION_ENTRIES, texts.size)
        assertEquals("change of plan ${SessionConversation.CORRECTION_ENTRIES + 3}", texts.last())
    }

    /// At the smallest size only one of goal/correction fits, and the newer direction is
    /// the one that must survive.
    @Test
    @DisplayName("the smallest block states the newest correction instead of the stale goal")
    fun minimalStatesTheNewestCorrection() {
        val conversation = SessionConversation(budgetChars = SessionConversation.MIN_BUDGET_CHARS)
        conversation.record(status(instruction = "implement the web UI end to end"))
        (2..6).forEach { index -> conversation.record(status(instruction = "sub-step $index " + "x".repeat(60))) }
        conversation.noteUserInput(listOf("actually, drop the web UI"))

        val block = conversation.renderBlock()

        assertTrue(block.length <= SessionConversation.MIN_BUDGET_CHARS, block)
        assertTrue(
            block.contains("Latest user correction (supersedes the original goal)"),
            "the redirect must outlive the goal at the smallest budget: $block",
        )
        assertTrue(block.contains("actually, drop the web UI"), block)
    }

    @Test
    @DisplayName("the correction cues recognize redirections without eating ordinary text")
    fun correctionCuesAreConservative() {
        for (text in listOf(
            "actually, we should ship the CLI first",
            "use the v2 endpoint instead",
            "forget about the settings page",
            "this is no longer needed",
            "改用 CSV 输出",
            "把 web UI 先不做了",
            "重新定义一下这个会话的目标",
        )) {
            assertTrue(looksLikeCorrection(text), text)
        }
        for (text in listOf(
            "add the settings page",
            "write the tests for the settings page",
            "run the browser4-rest tests",
            "修复编译错误",
            "把 CSV 导出的测试补上",
        )) {
            assertFalse(looksLikeCorrection(text), text)
        }
    }

    @Test
    @DisplayName("the remembered task list is bounded")
    fun theRecordListIsBounded() {
        val conversation = SessionConversation()
        (1..(SessionConversation.MAX_RECORDS + 8)).forEach { index ->
            conversation.record(status(instruction = "task $index"))
        }

        assertEquals(SessionConversation.MAX_RECORDS, conversation.size)
        assertEquals("task 9", conversation.snapshot().first().instruction)
        assertEquals(
            "task ${SessionConversation.MAX_RECORDS + 8}",
            conversation.snapshot().last().instruction,
        )
        // Eviction drops the founding RECORD but not the goal.
        assertEquals("task 1", conversation.goal)

        conversation.clear()
        assertEquals(0, conversation.size)
        assertEquals("", conversation.renderBlock())
    }

    @Test
    @DisplayName("fromConf reads the switch and the budget")
    fun fromConfReadsTheSettings() {
        // The defaults: on, with the default budget.
        assertNotNull(SessionConversation.fromConf(ImmutableConfig()))
        assertNull(SessionConversation.fromSettings(enabled = false, tokens = 500))
        assertNull(SessionConversation.fromSettings(enabled = true, tokens = 0))
        assertNull(SessionConversation.fromSettings(enabled = true, tokens = -5))

        val conversation = SessionConversation.fromSettings(enabled = true, tokens = 300)!!
        conversation.record(status(instruction = "task 1", summary = "a long summary ".repeat(100)))
        assertTrue(
            conversation.renderBlock().length <= 300 * SessionConversation.CHARS_PER_TOKEN,
            conversation.renderBlock(),
        )

        // A budget below the floor is raised, not silently honoured: a goal plus one task
        // cannot be said in fewer characters, and a block that dropped them would be
        // worse than no block at all.
        val cramped = SessionConversation.fromSettings(enabled = true, tokens = 20)!!
        (1..5).forEach { index -> cramped.record(status(instruction = "task $index", summary = "s")) }
        assertTrue(
            cramped.renderBlock().length <= SessionConversation.MIN_BUDGET_CHARS,
            cramped.renderBlock(),
        )
        assertTrue(cramped.renderBlock().contains("Session goal"), cramped.renderBlock())
    }

    @Test
    @DisplayName("stripSessionBlock removes the session block and keeps the task")
    fun stripSessionBlockKeepsTheTask() {
        val task = "add the CSV export"
        val block = SessionConversation().apply { record(status(instruction = "previous")) }.renderBlock()
        val prompt = "$task\n\n$block"

        assertEquals(task, stripSessionBlock(prompt))
        assertEquals(task, stripSessionBlock(task))
        assertEquals("", stripSessionBlock(block))
    }

    @Test
    @DisplayName("the resume block and the session block strip in either order")
    fun resumeAndSessionBlocksStripTogether() {
        val conversation = SessionConversation().apply { record(status(instruction = "previous")) }
        val resume = """
            add the CSV export

            ## Resume context

            This run resumes task older, which was interrupted before completion
        """.trimIndent()
        val prompt = "$resume\n\n${conversation.renderBlock()}"

        assertEquals("add the CSV export", stripResumeBlock(stripSessionBlock(prompt)))
        assertEquals("add the CSV export", stripSessionBlock(stripResumeBlock(prompt)))
    }

    @Test
    @DisplayName("recording a run keeps the block out of its history")
    fun theHistoryDoesNotKeepTheSessionBlock() {
        val conversation = SessionConversation().apply { record(status(instruction = "previous")) }
        val completed = status(instruction = "add the CSV export", summary = "done")
        val state = completed.agentHistory!!.states.first()
        state.instruction = "${state.instruction}\n\n${conversation.renderBlock()}"

        stripSessionBlockFromHistory(completed.agentHistory)

        assertEquals("add the CSV export", state.instruction)
        // A history without a block stays untouched.
        val clean = status(instruction = "another task")
        stripSessionBlockFromHistory(clean.agentHistory)
        assertEquals("another task", clean.agentHistory!!.states.first().instruction)
        stripSessionBlockFromHistory(null)
    }

    @Test
    @DisplayName("elapsed times render short enough for a bullet")
    fun elapsedFormatting() {
        assertEquals("0s", formatElapsed(900))
        assertEquals("45s", formatElapsed(45_400))
        assertEquals("2m 13s", formatElapsed(133_000))
    }
}
