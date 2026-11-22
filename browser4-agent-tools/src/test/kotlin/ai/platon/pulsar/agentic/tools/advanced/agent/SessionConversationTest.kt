package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.pulsar.agentic.agents.AgentRunEvent
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.ResourceStatus
import dev.langchain4j.data.message.UserMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The cross-task conversation of one agent session.
 *
 * The session inherits the ACTUAL conversation of its earlier tasks — instructions,
 * model messages, tool calls and their results, steering lines — not a per-task
 * bullet summary. When the transcript grows past its budget the model compacts it
 * with the session-memory instruction (the "compression instruction").
 */
@DisplayName("agent session conversation")
class SessionConversationTest {

    private fun status(
        instruction: String,
        statusCode: Int = ResourceStatus.SC_OK,
        files: List<String>? = null,
        deliverables: List<String>? = null,
        workspace: String? = null,
        failureReason: String? = null,
        cancelled: Boolean = false,
        elapsedMs: Long? = null,
    ): AgentTaskStatus = AgentTaskStatus(id = "task-${instruction.hashCode().toUInt()}").apply {
        submittedTask = instruction
        this.statusCode = statusCode
        this.cancelled = cancelled
        this.failureReason = failureReason
        this.deliverables = deliverables
        this.workspace = workspace
        val state = AgentState(step = 3, instruction = instruction).apply {
            isComplete = true
            filesChanged = files
        }
        agentHistory = AgentHistory(mutableListOf(state))
        // `done()` stamps finishTime itself, so the synthetic window is set after it.
        done()
        if (elapsedMs != null) {
            startedTime = Instant.ofEpochMilli(1_000_000)
            finishTime = Instant.ofEpochMilli(1_000_000 + elapsedMs)
        }
    }

    private fun model(text: String) = AgentRunEvent(AgentRunEvent.MODEL_TEXT, text = text)

    private fun tool(name: String, args: String = "{}") =
        AgentRunEvent(AgentRunEvent.TOOL_CALL, tool = name, arguments = args)

    private fun result(name: String, ok: Boolean = true, text: String? = null) =
        AgentRunEvent(AgentRunEvent.TOOL_RESULT, tool = name, ok = ok, text = text)

    // ---- the transcript is the real conversation --------------------------------

    @Test
    @DisplayName("a session that finished no task renders no block")
    fun anEmptySessionRendersNothing() {
        val conversation = SessionConversation()

        assertEquals("", conversation.renderForPrompt())
        assertEquals(0, conversation.size)
        assertFalse(conversation.needsCompression())
        assertTrue(conversation.compressionInput().contains("## Transcript to compact"))
    }

    @Test
    @DisplayName("the next task inherits the previous task's whole conversation")
    fun theWholeConversationIsInherited() {
        val conversation = SessionConversation()
        conversation.beginTask("add the CSV export")
        conversation.noteEvent(model("I'll add the export flag to main.rs"))
        conversation.noteEvent(tool("coding.write", """{"path":"src/export.kt"}"""))
        conversation.noteEvent(result("coding.write", text = "wrote 120 bytes"))
        conversation.noteEvent(tool("coding.verify", """{"module":"browser4-cli"}"""))
        conversation.noteEvent(result("coding.verify", ok = false, text = "gate failed: 3 checks"))
        conversation.noteEvent(AgentRunEvent(AgentRunEvent.STEER, text = "no, use CSV not XLSX"))
        conversation.endTask(status("add the CSV export", files = listOf("src/export.kt"), elapsedMs = 133_000))

        val block = conversation.renderForPrompt()

        assertTrue(block.startsWith(SESSION_BLOCK_MARKER), block)
        // The task, the model's own words, the tool calls and their outcomes.
        assertTrue(block.contains("#1 add the CSV export"), block)
        assertTrue(block.contains("model: I'll add the export flag to main.rs"), block)
        assertTrue(block.contains("tool: coding.write({\"path\":\"src/export.kt\"})"), block)
        assertTrue(block.contains("result: coding.write: wrote 120 bytes"), block)
        assertTrue(block.contains("FAILED: gate failed: 3 checks"), block)
        // A steering line is a user instruction and belongs to the conversation.
        assertTrue(block.contains("user: steering: no, use CSV not XLSX"), block)
        // The outcome carries what the next task reuses.
        assertTrue(block.contains("→ done in 2m 13s"), block)
        assertTrue(block.contains("files: src/export.kt"), block)
        // The block states what the transcript is; it gives no instruction about it.
        assertTrue(block.contains("actual conversation"), block)
    }

    @Test
    @DisplayName("two tasks render in order, numbered")
    fun tasksAreNumbered() {
        val conversation = SessionConversation()
        conversation.beginTask("first task")
        conversation.endTask(status("first task"))
        conversation.beginTask("second task")
        conversation.endTask(status("second task"))

        val block = conversation.renderForPrompt()

        assertTrue(block.contains("#1 first task"), block)
        assertTrue(block.contains("#2 second task"), block)
        assertTrue(block.indexOf("#1 first task") < block.indexOf("#2 second task"), block)
        assertEquals(2, conversation.tasks)
    }

    @Test
    @DisplayName("round markers and token usage are statistics, not context")
    fun usageAndRoundsAreNotRemembered() {
        val conversation = SessionConversation()
        conversation.beginTask("do something")
        conversation.noteEvent(AgentRunEvent(AgentRunEvent.ROUND_START, turn = 1))
        conversation.noteEvent(AgentRunEvent(AgentRunEvent.USAGE, totalTokens = 1200))
        conversation.noteEvent(AgentRunEvent(AgentRunEvent.TOOL_CALL, tool = "b4.run", arguments = """{"url":"https://example.com"}"""))

        assertEquals(2, conversation.size)
        assertFalse(conversation.renderForPrompt().contains("run.usage"))
        assertFalse(conversation.renderForPrompt().contains("round.start"))
    }

    // ---- outcome: files and the workspace they live in --------------------------

    @Test
    @DisplayName("the outcome names the workspace the files live in")
    fun theOutcomeCarriesTheWorkspace(@TempDir dir: Path) {
        // Every task of a session runs in its OWN workspace: the relative file names
        // are unusable for the next task without the directory.
        val workspace = Files.createDirectories(dir.resolve("workspace.task-1"))
        val conversation = SessionConversation().apply {
            beginTask("extract prices")
            endTask(status("extract prices", files = listOf("prices.csv"), workspace = workspace.toString()))
        }

        val block = conversation.renderForPrompt()

        assertTrue(block.contains("files: prices.csv"), block)
        assertTrue(block.contains("workspace: $workspace"), block)
    }

    @Test
    @DisplayName("a workspace that no longer exists is not advertised")
    fun aVanishedWorkspaceIsNotAdvertised(@TempDir dir: Path) {
        val conversation = SessionConversation().apply {
            beginTask("fix the build")
            endTask(
                status(
                    "fix the build",
                    files = listOf("src/main.kt"),
                    workspace = dir.resolve("deleted-worktree").toString(),
                )
            )
        }

        val block = conversation.renderForPrompt()

        assertTrue(block.contains("files: src/main.kt"), block)
        assertFalse(block.contains("workspace:"), block)
    }

    @Test
    @DisplayName("a failed or cancelled run is remembered with its reason")
    fun failuresAndCancelsAreRemembered() {
        val conversation = SessionConversation()
        conversation.beginTask("fix the build")
        conversation.endTask(
            status(
                "fix the build",
                statusCode = ResourceStatus.SC_EXPECTATION_FAILED,
                failureReason = "TokenBudgetExceededException: budget exceeded",
            )
        )
        conversation.beginTask("extract prices")
        conversation.endTask(status("extract prices", cancelled = true, deliverables = listOf("prices.csv")))

        val block = conversation.renderForPrompt()

        assertTrue(block.contains("→ failed — TokenBudgetExceededException: budget exceeded"), block)
        assertTrue(block.contains("→ cancelled · files: prices.csv"), block)
    }

    // ---- compression (the compression instruction) ------------------------------

    private fun crowdedConversation(): SessionConversation {
        // The minimum budget is deliberately small enough to be crossed by a handful
        // of turns, so the compress path is exercised without a huge fixture.
        val conversation = SessionConversation(budgetChars = SessionConversation.MIN_BUDGET_CHARS)
        conversation.beginTask("first task")
        repeat(8) { index ->
            conversation.noteEvent(model("model note $index " + "x".repeat(SessionConversation.MODEL_CHARS)))
            conversation.noteEvent(tool("coding.write", """{"path":"file-$index.kt"}"""))
        }
        conversation.endTask(status("first task"))
        conversation.beginTask("second task")
        conversation.noteEvent(model("working on the second task"))
        return conversation
    }

    @Test
    @DisplayName("a transcript over budget asks for compression; a small one does not")
    fun theBudgetDecidesCompression() {
        assertFalse(SessionConversation().needsCompression())

        val conversation = crowdedConversation()

        assertTrue(conversation.needsCompression(), conversation.renderForPrompt())
    }

    @Test
    @DisplayName("compression folds the older tasks and keeps the newest one raw")
    fun compressionKeepsTheNewestTask() {
        val conversation = crowdedConversation()

        val input = conversation.compressionInput()
        assertTrue(input.contains("#1 first task"), input)
        assertTrue(input.contains("## Transcript to compact"), input)
        assertFalse(input.contains("#2 second task"), "the running task must not be folded: $input")

        conversation.adoptCompressed("SESSION MEMORY: the goal is a CSV export")

        val block = conversation.renderForPrompt()
        assertTrue(block.contains(SESSION_MEMORY_MARKER), block)
        assertTrue(block.contains("SESSION MEMORY: the goal is a CSV export"), block)
        assertTrue(block.contains("#2 second task"), block)
        assertTrue(block.contains("model: working on the second task"), block)
        assertFalse(block.contains("#1 first task"), "the folded turns are gone: $block")
        // The memory replaced them, so the transcript is small again.
        assertFalse(conversation.needsCompression(), block)
    }

    @Test
    @DisplayName("a second compression keeps what the first one remembered")
    fun compressionCarriesTheEarlierMemory() {
        val conversation = crowdedConversation()
        conversation.adoptCompressed("SESSION MEMORY: keep the CLI flag")

        val input = conversation.compressionInput()

        assertTrue(input.contains("## Session memory already compressed"), input)
        assertTrue(input.contains("SESSION MEMORY: keep the CLI flag"), input)
    }

    @Test
    @DisplayName("the compression instruction asks for facts and states no goal or priority")
    fun theCompressionInstructionAsksForFactsOnly() {
        val instruction = SessionConversation(memoryChars = 800 * SessionConversation.CHARS_PER_TOKEN)
            .compressionSystemInstruction()

        assertTrue(instruction.contains("factual record"), instruction)
        assertTrue(instruction.contains("State only what the transcript"), instruction)
        assertTrue(instruction.contains("Do not add goals"), instruction)
        assertTrue(instruction.contains("about 800 tokens"), instruction)
        // A session may mix browsing and coding tasks, so the instruction names no mode.
        assertFalse(instruction.contains("browser-automation"), instruction)
        // The harness does not decide which instruction is "the" goal.
        assertFalse(instruction.contains("ACTIVE NOW"), instruction)
        assertFalse(instruction.contains("SUPERSEDED"), instruction)
        assertFalse(instruction.contains("first task's instruction"), instruction)
        assertFalse(instruction.contains("must survive"), instruction)
        assertFalse(instruction.contains("Preserve, in this order"), instruction)
    }

    @Test
    @DisplayName("the block states what the transcript is and gives no guidance")
    fun theBlockStatesFactsWithoutGuidance() {
        val conversation = SessionConversation()
        conversation.beginTask("extract the price of the product")
        conversation.endTask(status("extract the price of the product"))

        val block = conversation.renderForPrompt()

        assertTrue(block.contains("## Session context"), block)
        assertTrue(block.contains("actual conversation"), block)
        assertTrue(block.contains("extract the price of the product"), block)
        // The block used to tell the model how to treat history; the model judges now.
        assertFalse(block.contains("Treat it as your memory"), block)
        assertFalse(block.contains("do not repeat work"), block)
        assertFalse(block.contains("it wins"), block)
        assertFalse(block.contains("reference only"), block)
        assertFalse(block.contains("continue from where an interrupted run stopped"), block)
    }

    @Test
    @DisplayName("a late steering line is remembered as an undelivered user instruction")
    fun undeliveredSteeringIsRemembered() {
        val conversation = SessionConversation()
        conversation.beginTask("do something")
        conversation.noteUserInput(listOf("no, use CSV not XLSX", "  "))

        val block = conversation.renderForPrompt()

        assertTrue(block.contains("not delivered"), block)
        assertTrue(block.contains("no, use CSV not XLSX"), block)
        assertEquals(2, conversation.size)
    }

    // ---- durability across a backend restart -------------------------------------

    private fun storeFor(dir: Path, sessionKey: String = "work", ttlMinutes: Long = 720) =
        requireNotNull(
            SessionConversationStore.open(
                sessionKey,
                ttlMinutes,
                dir.resolve("agent-session-context.jsonl"),
            )
        )

    @Test
    @DisplayName("a backend restart does not lose the session's context")
    fun theConversationSurvivesARestart(@TempDir dir: Path) {
        val first = SessionConversation().apply {
            attach(storeFor(dir))
            beginTask("add the CSV export")
            noteEvent(model("I'll add the export flag"))
            noteEvent(tool("coding.write", """{"path":"src/export.kt"}"""))
            endTask(status("add the CSV export", files = listOf("src/export.kt")))
        }
        assertEquals(4, first.size)

        // The restart: a brand new runner builds a brand new conversation, same id.
        val restarted = SessionConversation().apply { attach(storeFor(dir)) }

        val block = restarted.renderForPrompt()
        assertTrue(block.contains("#1 add the CSV export"), block)
        assertTrue(block.contains("model: I'll add the export flag"), block)
        assertTrue(block.contains("tools: coding.write") || block.contains("tool: coding.write"), block)
        assertEquals(4, restarted.size)
        assertEquals(1, restarted.tasks)
    }

    @Test
    @DisplayName("a compressed memory survives a restart")
    fun theCompressedMemorySurvivesARestart(@TempDir dir: Path) {
        SessionConversation().apply {
            attach(storeFor(dir))
            beginTask("first task")
            endTask(status("first task"))
            adoptCompressed("SESSION MEMORY: the goal")
        }

        val restarted = SessionConversation().apply { attach(storeFor(dir)) }

        assertTrue(restarted.renderForPrompt().contains("SESSION MEMORY: the goal"), restarted.renderForPrompt())
    }

    @Test
    @DisplayName("an expired conversation is not restored")
    fun anExpiredConversationIsNotRestored(@TempDir dir: Path) {
        val store = storeFor(dir, ttlMinutes = 60)
        val savedAt = 1_000_000L
        store.save(
            SessionSnapshot(
                sessionId = "work",
                updatedAt = savedAt,
                taskCount = 1,
                turns = listOf(SessionTurn(SessionConversation.TASK, "#1 old task")),
            )
        )

        // Just inside the TTL it is still the session's memory ...
        assertEquals(1, store.restore(now = savedAt + 59 * 60_000L)?.turns?.size)
        // ... and past it, a reused session id must not resurrect a dead session.
        assertNull(store.restore(now = savedAt + 61 * 60_000L))
    }

    @Test
    @DisplayName("a session restores only its own conversation")
    fun theStoreIsPerSessionId(@TempDir dir: Path) {
        val store = storeFor(dir, sessionKey = "work")
        store.save(
            SessionSnapshot(
                sessionId = "work",
                updatedAt = System.currentTimeMillis(),
                taskCount = 1,
                turns = listOf(SessionTurn(SessionConversation.TASK, "#1 work task")),
            )
        )

        val other = SessionConversation().apply { attach(storeFor(dir, sessionKey = "other")) }

        assertEquals("", other.renderForPrompt())
        assertEquals(1, store.restore()?.turns?.size)
    }

    @Test
    @DisplayName("clearing the conversation is persisted too")
    fun aClearIsPersisted(@TempDir dir: Path) {
        val first = SessionConversation().apply {
            attach(storeFor(dir))
            beginTask("task one")
            endTask(status("task one"))
        }
        assertEquals(2, first.size)

        first.clear()

        val restarted = SessionConversation().apply { attach(storeFor(dir)) }
        assertEquals(0, restarted.size)
        assertEquals("", restarted.renderForPrompt())
    }

    @Test
    @DisplayName("persistence needs a session key and a positive TTL")
    fun persistenceNeedsAKeyAndATtl(@TempDir dir: Path) {
        val file = dir.resolve("agent-session-context.jsonl")

        assertNull(SessionConversationStore.open(null, 720, file))
        assertNull(SessionConversationStore.open("   ", 720, file))
        // `contextTtlMinutes=0` keeps the pre-persistence behaviour: memory only.
        assertNull(SessionConversationStore.open("work", 0, file))
        assertNotNull(SessionConversationStore.open("work", 720, file))
    }

    @Test
    @DisplayName("the anonymous DEFAULT pool is never persisted")
    fun theDefaultSessionHasNoDurableHome(@TempDir dir: Path) {
        // Every session-less `agent run` / MCP caller shares DEFAULT; persisting it
        // would let unrelated one-shot tasks inherit each other across a restart.
        assertNull(SessionConversationStore.open("DEFAULT", 720, dir.resolve("agent-session-context.jsonl")))
        assertNotNull(SessionConversationStore.open("work", 720, dir.resolve("agent-session-context.jsonl")))
    }

    // ---- configuration and the block's lifecycle --------------------------------

    @Test
    @DisplayName("the feature and the transcript budget are configurable")
    fun fromSettingsReadsTheSettings() {
        assertNull(SessionConversation.fromSettings(enabled = false, tokens = 1000))
        // A non-positive budget disables the injection instead of shipping a block
        // that is compressed on arrival.
        assertNull(SessionConversation.fromSettings(enabled = true, tokens = 0))

        // The default budget is comfortably larger than a short session, so it is not
        // compressed on arrival.
        val conversation = requireNotNull(SessionConversation.fromSettings(enabled = true, tokens = null))
        conversation.beginTask("only task")
        repeat(4) { conversation.noteEvent(model("note $it")) }
        assertFalse(conversation.needsCompression())

        // A deliberately small budget compresses the same session.
        val small = requireNotNull(SessionConversation.fromSettings(enabled = true, tokens = 1))
        small.beginTask("task")
        repeat(4) { small.noteEvent(model("x".repeat(SessionConversation.MODEL_CHARS))) }
        assertTrue(small.needsCompression())
    }

    @Test
    @DisplayName("stripSessionBlock removes the session block and keeps the task")
    fun stripSessionBlockKeepsTheTask() {
        val task = "add the CSV export"
        val block = SessionConversation().apply {
            beginTask("previous")
            endTask(status("previous"))
        }.renderForPrompt()
        val prompt = "$task\n\n$block"

        assertEquals(task, stripSessionBlock(prompt))
        assertEquals(task, stripSessionBlock(task))
        assertEquals("", stripSessionBlock(block))
    }

    @Test
    @DisplayName("recording a run keeps the block out of its history")
    fun theHistoryDoesNotKeepTheSessionBlock() {
        val conversation = SessionConversation().apply {
            beginTask("previous")
            endTask(status("previous"))
        }
        val completed = status("add the CSV export")
        val state = completed.agentHistory!!.states.first()
        state.instruction = "${state.instruction}\n\n${conversation.renderForPrompt()}"

        stripSessionBlockFromHistory(completed.agentHistory)

        assertEquals("add the CSV export", state.instruction)
        // A history without a block stays untouched.
        val clean = status("another task")
        stripSessionBlockFromHistory(clean.agentHistory)
        assertEquals("another task", clean.agentHistory!!.states.first().instruction)
        stripSessionBlockFromHistory(null)
    }

    @Test
    @DisplayName("the in-process conversation is carried between tasks and cleared with the session")
    fun theInProcessConversationIsCarried() {
        val conversation = SessionConversation()
        assertFalse(conversation.hasConversationMessages())
        assertTrue(conversation.messageHistory().isEmpty())

        // A run that produced nothing must not erase the session's memory.
        conversation.adoptConversationMessages(null)
        conversation.adoptConversationMessages(emptyList())
        assertFalse(conversation.hasConversationMessages())

        val messages = listOf<UserMessage>(
            UserMessage.from("first task"),
            UserMessage.from("second task"),
        )
        conversation.adoptConversationMessages(messages)
        assertTrue(conversation.hasConversationMessages())
        assertEquals(messages, conversation.messageHistory())

        // A new session must not inherit the old list.
        conversation.clear()
        assertFalse(conversation.hasConversationMessages())
    }

    @Test
    @DisplayName("beginTask checkpoints the conversation so a later task can rewind to it")
    fun beginTaskCheckpointsTheConversation() {
        val conversation = SessionConversation()
        val first = listOf<UserMessage>(UserMessage.from("first task"))
        conversation.adoptConversationMessages(first)

        conversation.beginTask("second task", "task-2")

        // The checkpoint of a task is the conversation as it was BEFORE that task ran.
        assertEquals(first, conversation.rewindStartOf("task-2"))
        assertNull(conversation.rewindStartOf("unknown"))
        // The branch summary states what the branch did; it no longer recommends
        // what the next task must not repeat.
        val branchInstruction = conversation.branchSummaryInstruction()
        assertTrue(branchInstruction.contains("## What this branch tried"), branchInstruction)
        assertFalse(branchInstruction.contains("## What not to repeat"), branchInstruction)
        assertFalse(branchInstruction.contains("must not repeat"), branchInstruction)
        assertFalse(branchInstruction.contains("browser-automation"), branchInstruction)

        // A rewind replaces the conversation even when the checkpoint is empty: the
        // compacted transcript must not re-inject what the user just rewound away from.
        conversation.replaceConversationMessages(emptyList())
        assertTrue(
            conversation.hasConversationMessages(),
            "an empty rewound conversation is still a deliberate conversation",
        )
        assertTrue(conversation.messageHistory().isEmpty())

        conversation.clear()
        assertEquals(0, conversation.rewindCheckpointCount)
    }

    @Test
    @DisplayName("rewind checkpoints are bounded, the oldest dropped first")
    fun rewindCheckpointsAreBounded() {
        val conversation = SessionConversation()
        val last = SessionConversation.MAX_REWIND_CHECKPOINTS + 3
        (1..last).forEach { index -> conversation.beginTask("task $index", "task-$index") }

        assertEquals(SessionConversation.MAX_REWIND_CHECKPOINTS, conversation.rewindCheckpointCount)
        assertNull(conversation.rewindStartOf("task-1"), "the oldest checkpoint is dropped first")
        assertNotNull(conversation.rewindStartOf("task-$last"))
    }

    @Test
    @DisplayName("elapsed times render short enough for a transcript turn")
    fun elapsedFormatting() {
        assertEquals("0s", formatElapsed(900))
        assertEquals("45s", formatElapsed(45_400))
        assertEquals("2m 13s", formatElapsed(133_000))
    }
}
