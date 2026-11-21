package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.agentic.agents.AgentRunEvent
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Heading of the block [SessionConversation.renderForPrompt] produces. */
internal const val SESSION_BLOCK_MARKER = "## Session context"

/** Heading of the model-written memory that replaced compressed-away turns. */
internal const val SESSION_MEMORY_MARKER = "### Session memory (compressed earlier context)"

/**
 * One turn of a session's conversation, as the next task reads it.
 *
 * The session keeps the ACTUAL conversation of its earlier tasks instead of a
 * per-task summary: what the user asked (and every steering line typed into it), what
 * the model said, which tools it called and what they answered, and how each task
 * ended. That is what the next task receives, so it can pick up the work instead of
 * being told about it.
 *
 * @property role one of [SessionConversation.TASK] / [MODEL] / [TOOL] / [RESULT] /
 *   [USER] / [OUTCOME]
 * @property text the turn, already collapsed to one bounded line
 */
internal data class SessionTurn(
    val role: String,
    val text: String,
)

/**
 * The persisted form of one session's conversation.
 *
 * One row is appended per task start/end and per steering line; [SessionConversationStore]
 * folds the file to the LAST row per [sessionId] on read, so the row set is a log
 * (cheap appends, no read-modify-write race) and the newest row is the truth.
 *
 * @property sessionId the transport session id this conversation belongs to
 * @property updatedAt epoch millis of the write, the TTL's clock
 * @property taskCount how many tasks this session already began (for turn numbering)
 * @property currentTaskStart index of the newest task's first turn in [turns]
 * @property compressed the model-written memory of the turns folded away so far
 * @property turns the remembered conversation turns, oldest first
 */
internal data class SessionSnapshot(
    val sessionId: String = "",
    val updatedAt: Long = 0L,
    val taskCount: Int = 0,
    val currentTaskStart: Int = 0,
    val compressed: String? = null,
    val turns: List<SessionTurn> = emptyList(),
)

/**
 * The cross-task conversation of one agent session.
 *
 * ## What is inherited, and why it is the whole conversation
 *
 * Every `agent run` task used to start from zero: the runner executed task after task
 * on the same companion agent, but `agent.run(instruction)` builds a fresh
 * conversation, so task N had no idea what task N-1 had done. The first fix summarized
 * each finished task into a one-line bullet; a bullet cannot answer "which selector did
 * we settle on", "what did the tool actually print", or "which file did that run
 * write". The session therefore keeps the **actual transcript** — task instructions,
 * the model's own messages, every tool call with its result, every steering line — and
 * appends it to the next task's instruction, i.e. the next run really does inherit the
 * previous runs' context.
 *
 * ## Compression (the "compression instruction")
 *
 * The transcript rides in every prompt of the next run, so it must stay bounded no
 * matter how long the session runs. [needsCompression] fires when the rendered
 * transcript crosses `browser4.agent.session.contextTokens`, and the runner then asks
 * the model to compact it using [compressionSystemInstruction] — a dedicated
 * instruction that states what must survive the fold (the session goal and every
 * correction, decisions and facts later work depends on, the files and their workspace,
 * open problems) and what may be dropped (page snapshots, repeated chatter, dead-end
 * reasoning). The result is stored as [compressed] and the folded turns are dropped;
 * [adoptCompressed] keeps the newest task's turns raw, because that is the task the next
 * instruction most likely refers to.
 *
 * If the model call fails the runner sends the transcript uncompressed (bounded by the
 * caller's model window, not by this class): losing the context is worse than sending a
 * long prompt, and the failure is logged.
 *
 * A session that never ran a task renders nothing, so a one-shot `agent run` is
 * completely unaffected.
 *
 * ## Threading
 *
 * Not thread-safe, and does not need to be: the runner executes one task at a time
 * ([StatefulAgentRunner]'s `runMutex`), so [beginTask]/[endTask]/[renderForPrompt] never
 * overlap. The runner rebuilds the conversation when it rebuilds the session.
 *
 * ## Durability
 *
 * The conversation is written to [SessionConversationStore] (one row per task
 * start/end and per steering line, last row per session wins, with a TTL), so a
 * backend restart no longer silently wipes the context of a live `agent run -i`
 * session: the CLI keeps submitting to the same session id, and the next task re-reads
 * what the session already did. Only named sessions are persisted — the backend's
 * anonymous DEFAULT pool stays in memory ([SessionConversationStore.open] has no
 * durable home for it), because unrelated session-less callers must not inherit each
 * other's context across restarts.
 *
 * @param budgetChars hard cap on the rendered transcript, in characters (floored at
 *   [MIN_BUDGET_CHARS]); the runner compresses when it is crossed
 * @param memoryChars target size of the compressed memory block, in characters; it is
 *   stated in the compression instruction
 */
internal class SessionConversation(
    budgetChars: Int = DEFAULT_CONTEXT_TOKENS * CHARS_PER_TOKEN,
    private val memoryChars: Int = DEFAULT_MEMORY_TOKENS * CHARS_PER_TOKEN,
) {
    private val budgetChars = budgetChars.coerceAtLeast(MIN_BUDGET_CHARS)

    /**
     * The durable home of this session's conversation, or `null` to stay in memory.
     *
     * Attached once at construction ([SessionConversationStore.open]); every later
     * change ([beginTask], [endTask], [noteUserInput], [clear]) is written through it.
     */
    private var store: SessionConversationStore? = null

    private val turns = mutableListOf<SessionTurn>()

    /**
     * The session's live message list (pi's continuous conversation), empty after a
     * backend restart.
     *
     * This is what the next task actually inherits when it exists: the runner hands it
     * to the agent, which appends the new task to it, so the model sees the real prior
     * messages (tool calls and their results included) instead of a text rendering.
     * It is deliberately not persisted: a restored session falls back to the compacted
     * `turns` transcript, which is what survives a restart.
     */
    private var conversationMessages: List<ChatMessage> = emptyList()

    /**
     * True once `agent run --rewind` replaced the conversation, even with an EMPTY list.
     *
     * Rewinding to the start of the first task legitimately yields "no messages"; the
     * transcript fallback must not then re-inject what the user just rewound away from.
     */
    private var rewoundConversation = false

    /** True when this session still carries the in-process conversation. */
    fun hasConversationMessages(): Boolean = conversationMessages.isNotEmpty() || rewoundConversation

    /** The in-process conversation the next task should continue. */
    fun messageHistory(): List<ChatMessage> = conversationMessages

    /**
     * Adopt the conversation a run produced; `null`/empty leaves the previous one in
     * place (a run that produced nothing must not erase the session's memory).
     */
    fun adoptConversationMessages(messages: List<ChatMessage>?) {
        if (!messages.isNullOrEmpty()) {
            conversationMessages = messages
        }
    }

    /**
     * Replace the conversation unconditionally — the rewind path.
     *
     * An empty list is meaningful here ("back to before the first task"), which is why
     * this is separate from [adoptConversationMessages].
     */
    fun replaceConversationMessages(messages: List<ChatMessage>) {
        conversationMessages = messages
        rewoundConversation = true
    }

    /**
     * Conversation checkpoints of this session, keyed by the task they started.
     *
     * A checkpoint is the session's message list exactly as it was when that task
     * began, which is what `agent run --rewind <task-id>` restores — pi's
     * `navigateTree`: the user goes back to an earlier point of the session and
     * continues from there, instead of starting a new session or re-running the
     * aborted branch. In-memory only (like [conversationMessages]): after a backend
     * restart the checkpoints are gone and rewind fails loudly.
     *
     * Bounded to [MAX_REWIND_CHECKPOINTS]; the oldest checkpoint is dropped first.
     */
    private val rewindCheckpoints = LinkedHashMap<String, List<ChatMessage>>()

    /** The conversation as it was when [taskId] began, or `null` when unknown. */
    fun rewindStartOf(taskId: String): List<ChatMessage>? = rewindCheckpoints[taskId]

    /** Take (and clear) the checkpoint of [taskId] — for diagnostics and tests. */
    fun removeRewindCheckpoint(taskId: String): List<ChatMessage>? = rewindCheckpoints.remove(taskId)

    /** How many rewind checkpoints this session currently holds. */
    val rewindCheckpointCount: Int get() = rewindCheckpoints.size

    /**
     * The branch-summary instruction for `agent run --rewind <task-id>`.
     *
     * The user goes back to an earlier point of the session; the branch being left is not
     * lost, it is folded into this summary and attached to the rewound conversation
     * (pi's `BRANCH_SUMMARY_PROMPT`), so the next task knows what the branch tried and
     * what not to repeat.
     */
    fun branchSummaryInstruction(): String = """
        You summarize ONE abandoned branch of a long browser-automation session.
        The user rewound the session to an earlier point, and the transcript below is what
        the branch tried AFTER that point: attempts, tool calls and their results. The next
        task continues from the rewound point and must not repeat this branch blindly.

        Keep it short and factual. Preserve exact paths, commands, URLs, selectors, error
        strings and ids. Output only these sections, "(none)" for an empty one:

        ## What this branch tried
        ## What it learned (facts later work can reuse)
        ## Why it may have been abandoned (only what the conversation states)
        ## What not to repeat
    """.trimIndent()

    /**
     * Render a conversation (or a tail of one) as text for the branch summary.
     *
     * This is the input of [compressionSystemInstruction]-style summarization: the
     * abandoned branch is folded into one user message so the rewound conversation
     * keeps what the branch learned without carrying its tool traffic.
     */
    fun renderMessagesForSummary(messages: List<ChatMessage>): String = messages.mapNotNull { message ->
        val role = when (message) {
            is UserMessage -> "user"
            is AiMessage -> "assistant"
            is ToolExecutionResultMessage -> "tool"
            else -> "system"
        }
        val text = when (message) {
            is UserMessage -> message.singleText() ?: ""
            is AiMessage -> buildString {
                append(message.text() ?: "")
                message.toolExecutionRequests()?.forEach { request ->
                    append(" [tool ").append(request.name()).append(' ')
                    append((request.arguments() ?: "{}").take(ARGS_CHARS)).append(']')
                }
            }

            is ToolExecutionResultMessage -> message.text()
            else -> message.toString()
        }.trim()
        if (text.isEmpty()) null else "[$role] ${text.take(SUMMARY_MESSAGE_CHARS)}"
    }.joinToString("\n")

    /** How many tasks this session has begun (turn labels are `#1`, `#2`, …). */
    private var taskCount = 0

    /**
     * Index of the first turn of the newest task.
     *
     * [compressionInput] folds everything before it and [adoptCompressed] keeps the
     * whole newest task raw — the fold must never cut away the task the next
     * instruction refers to.
     */
    private var currentTaskStart = 0

    /** The model-written memory of the turns folded away so far, or `null`. */
    var compressed: String? = null
        private set

    /** The remembered turns, oldest first — for tests and diagnostics. */
    fun snapshot(): List<SessionTurn> = turns.toList()

    /** Number of remembered turns. */
    val size: Int get() = turns.size

    /** How many tasks this conversation remembers having begun. */
    val tasks: Int get() = taskCount

    fun clear() {
        turns.clear()
        compressed = null
        taskCount = 0
        currentTaskStart = 0
        conversationMessages = emptyList()
        rewoundConversation = false
        rewindCheckpoints.clear()
        persist()
    }

    /**
     * Attach the durable store and adopt what it remembers for this session.
     *
     * Called once, right after construction ([SessionConversationStore.open]); the
     * restored rows are the ones this session id wrote before a backend restart.
     * Expired rows never reach this point ([SessionConversationStore.restore] drops
     * them), so a stale session id cannot resurrect an unrelated session's work.
     */
    internal fun attach(store: SessionConversationStore) {
        this.store = store
        val restored = store.restore() ?: return
        compressed = restored.compressed
        taskCount = restored.taskCount
        currentTaskStart = restored.currentTaskStart.coerceIn(0, restored.turns.size)
        turns += restored.turns
    }

    /** Write the current state through the store; a no-op without one. */
    private fun persist() {
        val store = store ?: return
        store.save(snapshotOf(store.sessionId))
    }

    private fun snapshotOf(sessionId: String) = SessionSnapshot(
        sessionId = sessionId,
        updatedAt = System.currentTimeMillis(),
        taskCount = taskCount,
        currentTaskStart = currentTaskStart,
        compressed = compressed,
        turns = turns.toList(),
    )

    /**
     * Start a task: label it and make everything before it foldable.
     *
     * The instruction is the user's own text (never the `## Session context` block the
     * runner appends), so the transcript carries what was actually asked.
     */
    fun beginTask(instruction: String, taskId: String? = null) {
        // Checkpoint the conversation as it is BEFORE this task: `agent run --rewind
        // <taskId>` restores exactly this state and continues from there (pi's
        // navigateTree). Bounded, oldest first; the in-memory list is the only copy.
        if (!taskId.isNullOrBlank()) {
            rewindCheckpoints[taskId] = conversationMessages
            while (rewindCheckpoints.size > MAX_REWIND_CHECKPOINTS) {
                rewindCheckpoints.remove(rewindCheckpoints.keys.first())
            }
        }
        currentTaskStart = turns.size
        taskCount++
        // Same redaction and line-collapsing as the live event log: this text is
        // persisted and re-sent to the model, so a credential that reaches a tool
        // argument must not survive in it.
        val text = AgentTaskEventSanitizer.preview(instruction, INSTRUCTION_CHARS) ?: "(no instruction)"
        turns += SessionTurn(TASK, "#$taskCount $text")
        persist()
    }

    /**
     * Remember one live step of the running task.
     *
     * The runner feeds every event of the run through here, so the transcript is the
     * run's real conversation and not a post-hoc summary: the model's messages, the
     * tools it called with their arguments, what the tools answered, and steering lines.
     * Usage and round markers are deliberately dropped — they are statistics, not
     * context. Every field goes through [AgentTaskEventSanitizer], exactly like the live
     * event log, because the transcript is persisted and sent back to the model.
     */
    fun noteEvent(event: AgentRunEvent) {
        when (event.type) {
            AgentRunEvent.MODEL_TEXT -> AgentTaskEventSanitizer.preview(event.text, MODEL_CHARS)
                ?.let { turns += SessionTurn(MODEL, it) }

            AgentRunEvent.TOOL_CALL -> {
                val tool = event.tool?.takeIf { it.isNotBlank() } ?: "tool"
                val arguments = AgentTaskEventSanitizer.arguments(event.tool, event.arguments) ?: "{}"
                turns += SessionTurn(TOOL, "$tool(${arguments.take(ARGS_CHARS)})")
            }

            AgentRunEvent.TOOL_RESULT -> {
                val tool = event.tool?.takeIf { it.isNotBlank() } ?: "tool"
                val detail = AgentTaskEventSanitizer.preview(event.text, RESULT_CHARS)
                val outcome = when {
                    event.ok == false -> detail?.let { "FAILED: $it" } ?: "FAILED"
                    else -> detail ?: "ok"
                }
                val duration = event.durationMs?.takeIf { it >= 1_000 }?.let { " in ${formatElapsed(it)}" }.orEmpty()
                turns += SessionTurn(RESULT, "$tool: $outcome$duration")
            }

            AgentRunEvent.STEER -> AgentTaskEventSanitizer.preview(event.text, STEER_CHARS)
                ?.let { turns += SessionTurn(USER, "steering: $it") }
        }
    }

    /**
     * Remember a steering line that arrived after the last model round.
     *
     * It was never delivered to the model, so it is not part of the transcript as a
     * steering turn would be; it is kept here as an explicit user instruction so the
     * next task still honors it (and the live view reports it, see
     * [AgentTaskEvent.TYPE_STEER_UNDELIVERED]).
     *
     * @param kind what was typed — `steering` (mid-run) or `follow-up` (at the stop
     *   point); the transcript names it so the next task can tell them apart
     */
    fun noteUserInput(messages: List<String>, kind: String = "steering") {
        var changed = false
        messages.forEach { message ->
            val text = AgentTaskEventSanitizer.preview(message, STEER_CHARS) ?: return@forEach
            turns += SessionTurn(USER, "$kind (not delivered — the task ended first): $text")
            changed = true
        }
        if (changed) persist()
    }

    /**
     * Close the running task with its outcome.
     *
     * The turn carries what the next task needs to reuse the work: the terminal word,
     * the duration, the files the run changed and — when it still exists — the
     * workspace directory they live in. Every task of a session runs in its OWN
     * workspace, so a bare relative name would point at an empty directory.
     */
    fun endTask(status: AgentTaskStatus) {
        turns += SessionTurn(OUTCOME, describeOutcome(status))
        persist()
    }

    private fun describeOutcome(status: AgentTaskStatus): String {
        val finalState = status.agentHistory?.finalResult
        val elapsed = status.startedTime?.let { started ->
            status.finishTime?.let { finished ->
                java.time.Duration.between(started, finished).toMillis()
            }
        }
        val files = (finalState?.filesChanged?.takeIf { it.isNotEmpty() } ?: status.deliverables)
            .orEmpty()
            .filter { it.isNotBlank() }
            .take(MAX_FILES)
            .map { if (it.length > FILE_CHARS) it.take(FILE_CHARS) + "…" else it }
        val workspace = workspaceStillThere(status.workspace)
        return buildString {
            append(statusWordOf(status))
            elapsed?.takeIf { it >= 1_000 }?.let { append(" in ").append(formatElapsed(it)) }
            // A failed/cancelled run has no model closing report worth relying on, so
            // the reason is what the next task must know (redacted like the event log).
            val reason = if (status.statusCode == ResourceStatus.SC_OK) null
            else AgentTaskEventSanitizer.preview(status.failureReason ?: status.message, OUTCOME_REASON_CHARS)
            reason?.let { append(" — ").append(it) }
            if (files.isNotEmpty()) append(" · files: ").append(files.joinToString(", "))
            workspace?.let { append(" · workspace: ").append(it) }
        }.take(OUTCOME_CHARS)
    }

    /**
     * [path] when it is still a readable directory, else `null`.
     *
     * A merged coding worktree is gone by the time the next task starts; a dead path
     * is worse than none.
     */
    private fun workspaceStillThere(path: String?): String? = path
        ?.takeIf { it.isNotBlank() }
        ?.takeIf { runCatching { Files.isDirectory(Path.of(it)) }.getOrDefault(false) }
        ?.let { if (it.length > WORKSPACE_CHARS) it.take(WORKSPACE_CHARS) + "…" else it }

    /** True when the rendered transcript crossed the configured budget. */
    fun needsCompression(): Boolean = renderTranscript().length > budgetChars

    /**
     * The transcription the model must compact — everything foldable plus the memory
     * already produced by earlier folds, so a second compression never loses what the
     * first one kept.
     */
    fun compressionInput(): String = buildString {
        compressed?.takeIf { it.isNotBlank() }?.let {
            appendLine("## Session memory already compressed")
            appendLine()
            appendLine(it)
            appendLine()
        }
        appendLine("## Transcript to compact")
        appendLine()
        append(renderFoldable())
    }.trimEnd()

    /** The compression instruction (the "compression prompt"). */
    fun compressionSystemInstruction(): String = """
        You compact the working memory of one long browser-automation session for the
        agent that continues it. The input is the actual transcript of the session's
        earlier tasks: what the user asked, the model's own messages, the tool calls with
        their results, and every steering line the user typed.

        Rewrite it into the smallest memory block that still lets the NEXT task continue
        correctly. Preserve, in this order, quoting user intent verbatim where it matters:

        1. the session goal — the first task's instruction — and every later correction or
           steering line that revised it;
        2. decisions already taken and the facts later work depends on (URLs, selectors,
           ids, formats, accounts — never secrets);
        3. files created or changed, and the workspace directory they live in;
        4. tool outcomes that constrain later work, and open problems or unfinished steps.

        Drop page snapshots, repeated tool chatter, and reasoning that led nowhere. Write
        terse factual notes addressed to the next task, at most about ${(memoryChars / CHARS_PER_TOKEN).coerceAtLeast(200)} tokens in total.

        Output only the memory block: no preamble, no thanks, no headings other than plain
        lines.
    """.trimIndent()

    /**
     * Replace the folded turns with [memory].
     *
     * The newest task's turns are kept raw: they are what the next instruction most
     * likely refers to. When the session has only one task (nothing began after it),
     * everything is foldable and only the memory remains.
     */
    fun adoptCompressed(memory: String) {
        val kept = if (currentTaskStart in 1 until turns.size) turns.drop(currentTaskStart) else emptyList()
        compressed = memory.trim().takeIf { it.isNotBlank() }
        turns.clear()
        turns += kept
        currentTaskStart = 0
        persist()
    }

    /**
     * The `## Session context` block for the next task's instruction, or `""` when this
     * session has nothing to remember yet.
     */
    fun renderForPrompt(): String {
        if (turns.isEmpty() && compressed.isNullOrBlank()) return ""
        return buildString {
            appendLine(SESSION_BLOCK_MARKER)
            appendLine()
            appendLine(
                "This session already ran $taskCount task(s). The transcript below is the actual " +
                    "conversation of those runs — what was asked, your own messages, the tool calls " +
                    "and their results, and the corrections typed into them. Treat it as your memory " +
                    "of the session: do not repeat work that is already done, build on the files it " +
                    "changed, and continue from where an interrupted run stopped. The instruction at " +
                    "the top of this message is the task to perform NOW — if it contradicts anything " +
                    "below, it wins."
            )
            compressed?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(SESSION_MEMORY_MARKER)
                appendLine()
                appendLine(it.trim())
            }
            if (turns.isNotEmpty()) {
                appendLine()
                appendLine("### Transcript")
                appendLine()
                append(renderTurns(turns))
            }
        }.trimEnd()
    }

    /** The turns that a compression would fold (everything before the newest task). */
    private fun renderFoldable(): String {
        val foldable = if (currentTaskStart in 1..turns.size) turns.take(currentTaskStart) else turns.toList()
        return renderTurns(foldable)
    }

    private fun renderTranscript(): String = buildString {
        compressed?.takeIf { it.isNotBlank() }?.let { appendLine(it); appendLine() }
        append(renderTurns(turns))
    }

    private fun renderTurns(items: List<SessionTurn>): String = items.joinToString("\n") { turn ->
        when (turn.role) {
            TASK -> turn.text
            OUTCOME -> "  → ${turn.text}"
            MODEL -> "  model: ${turn.text}"
            TOOL -> "  tool: ${turn.text}"
            RESULT -> "  result: ${turn.text}"
            USER -> "  user: ${turn.text}"
            else -> "  ${turn.role}: ${turn.text}"
        }
    }

    companion object {
        /** Process-wide switch: `browser4.agent.session.contextEnabled` (default true). */
        const val ENABLED_KEY = "browser4.agent.session.contextEnabled"

        /**
         * Transcript budget in estimated tokens:
         * `browser4.agent.session.contextTokens` (default 24000).
         *
         * Over budget the runner compacts the older turns with
         * [compressionSystemInstruction] instead of dropping them.
         */
        const val TOKENS_KEY = "browser4.agent.session.contextTokens"

        /**
         * Size of the compressed memory block in estimated tokens:
         * `browser4.agent.session.memoryTokens` (default 2000).
         */
        const val MEMORY_TOKENS_KEY = "browser4.agent.session.memoryTokens"

        /**
         * How long a persisted conversation stays readable, in minutes:
         * `browser4.agent.session.contextTtlMinutes` (default 720).
         *
         * A restart is what the persistence is for, and that is seconds to minutes
         * away from the last task. Without an expiry, reusing a session id next week
         * (a `-s work` the user types again) would inherit a completely unrelated
         * session's tasks. `0` (or a negative value) keeps the conversation in memory
         * only.
         */
        const val TTL_KEY = "browser4.agent.session.contextTtlMinutes"

        /** The conventional chars-per-token estimate used everywhere in this engine. */
        const val CHARS_PER_TOKEN = 4

        /** Default transcript budget (~96 KB of prompt). */
        const val DEFAULT_CONTEXT_TOKENS = 24_000

        /** Default compressed-memory budget (~8 KB). */
        const val DEFAULT_MEMORY_TOKENS = 2_000

        /** Default lifetime of a persisted conversation (12 hours). */
        const val DEFAULT_TTL_MINUTES = 720L

        /**
         * Smallest transcript budget the injection honours (≈500 tokens).
         *
         * Below this a single task's transcript would immediately be compressed on
         * every turn, which costs more than it saves.
         */
        const val MIN_BUDGET_CHARS = 2_000

        /** Bound on the remembered instruction of one task. */
        internal const val INSTRUCTION_CHARS = 200

        /** Bound on one remembered model message. */
        internal const val MODEL_CHARS = 600

        /** Bound on one remembered tool-call argument JSON. */
        internal const val ARGS_CHARS = 300

        /** Bound on one message line inside a branch summary's input. */
        internal const val SUMMARY_MESSAGE_CHARS = 800

        /**
         * How many rewind checkpoints a session keeps (oldest dropped first).
         *
         * Each checkpoint is a reference to that task's starting message list, not a
         * copy, so the cost is a map entry plus whatever lists the session already
         * holds. A handful is enough to go back a few tasks; an unbounded map would keep
         * every intermediate conversation alive for the whole session.
         */
        const val MAX_REWIND_CHECKPOINTS = 8

        /** Bound on one remembered tool result. */
        internal const val RESULT_CHARS = 300

        /** Bound on one remembered steering line. */
        internal const val STEER_CHARS = 300

        /** Bound on the outcome turn. */
        internal const val OUTCOME_CHARS = 400

        /** Bound on the failure reason inside the outcome turn. */
        internal const val OUTCOME_REASON_CHARS = 200

        internal const val MAX_FILES = 8
        internal const val FILE_CHARS = 120

        /** Bound on the rendered workspace path. */
        internal const val WORKSPACE_CHARS = 200

        internal const val TASK = "task"
        internal const val MODEL = "model"
        internal const val TOOL = "tool"
        internal const val RESULT = "result"
        internal const val USER = "user"
        internal const val OUTCOME = "outcome"

        /**
         * The conversation a session should keep, or `null` when the feature is
         * switched off (`browser4.agent.session.contextEnabled=false`).
         *
         * @param sessionKey the transport session id this runner serves (the CLI's
         *   `-s` name / the interactive session's own id). When it is non-blank the
         *   conversation is persisted under it; `null` — or `contextTtlMinutes<=0` —
         *   keeps it in memory only.
         */
        fun fromConf(conf: ImmutableConfig, sessionKey: String? = null): SessionConversation? {
            val conversation = fromSettings(
                enabled = conf.getBoolean(ENABLED_KEY, true),
                tokens = conf.get(TOKENS_KEY)?.trim()?.toIntOrNull(),
                memoryTokens = conf.get(MEMORY_TOKENS_KEY)?.trim()?.toIntOrNull(),
            ) ?: return null
            val ttl = conf.get(TTL_KEY)?.trim()?.toLongOrNull() ?: DEFAULT_TTL_MINUTES
            SessionConversationStore.open(sessionKey, ttl)?.let { conversation.attach(it) }
            return conversation
        }

        /**
         * [fromConf] as a pure function of the settings, so the switch and the budgets
         * can be unit-tested without an [ImmutableConfig].
         *
         * A non-positive transcript budget disables the injection instead of shipping a
         * block that is compressed on arrival.
         */
        fun fromSettings(enabled: Boolean, tokens: Int?, memoryTokens: Int? = null): SessionConversation? {
            if (!enabled) return null
            val resolved = tokens ?: DEFAULT_CONTEXT_TOKENS
            if (resolved <= 0) return null
            val memory = memoryTokens ?: DEFAULT_MEMORY_TOKENS
            return SessionConversation(
                budgetChars = resolved * CHARS_PER_TOKEN,
                memoryChars = memory.coerceAtLeast(0) * CHARS_PER_TOKEN,
            )
        }
    }
}

/**
 * The instruction without the `## Session context` block this session appended.
 *
 * The block is appended after the task text (and after a resume block), so it is
 * always a suffix: cutting at its heading recovers what the user actually asked
 * for. Storing the block as the submitted task would nest blocks on a later resume
 * and misreport the task in `agent status`.
 */
internal fun stripSessionBlock(instruction: String): String {
    val index = instruction.indexOf("\n$SESSION_BLOCK_MARKER")
    if (index >= 0) return instruction.substring(0, index).trimEnd()
    return if (instruction.startsWith(SESSION_BLOCK_MARKER)) "" else instruction
}

/**
 * Strip the session block from every recorded instruction of [history].
 *
 * The prompt that RAN carried the block; the record must not. It is a per-run
 * prelude of up to a few tens of KB, and left in place it multiplies into every
 * published history snapshot — `command_result.agentHistory`, the JSONL task rows and
 * the next run's rendered Execution History — while `status.submittedTask` already
 * holds the real task text.
 */
internal fun stripSessionBlockFromHistory(history: AgentHistory?) {
    history?.states?.forEach { state ->
        if (state.instruction.contains(SESSION_BLOCK_MARKER)) {
            state.instruction = stripSessionBlock(state.instruction)
        }
    }
}

/**
 * Durable home of one session's conversation, so a backend restart no longer wipes
 * the context of a live session.
 *
 * ## Why a file
 *
 * The conversation used to live only in the runner's memory. The CLI keeps submitting
 * to the same session id after a restart, so the second task of an `agent run -i`
 * session silently started from zero while the user believed the session remembered.
 * Rows are written to the same `agent` data directory as the task registry and are
 * bounded before they are stored (the transcript itself is compressed by the model
 * when it grows), so a row stays in the tens of KB at worst.
 *
 * ## Expiry and compaction
 *
 * [restore] drops rows older than the configured TTL, so a reused session id cannot
 * resurrect a dead session. Once the file crosses [COMPACTION_SIZE_THRESHOLD_BYTES] a
 * restore folds it to one row per session id (the same maintenance idea as the task
 * registry's compaction).
 */
internal class SessionConversationStore(
    val sessionId: String,
    private val persistence: JsonlPersistence<SessionSnapshot>,
    private val ttlMinutes: Long,
) {
    private val ttlMillis: Long get() = ttlMinutes * 60_000L

    /**
     * The newest non-expired snapshot for [sessionId], or `null`.
     *
     * Never throws to the caller: a corrupt row is skipped by [JsonlPersistence] and
     * an unreadable file means "nothing to restore", so the conversation starts empty
     * instead of failing the run.
     */
    fun restore(now: Long = System.currentTimeMillis()): SessionSnapshot? {
        var latest: SessionSnapshot? = null
        for (row in persistence.readAll()) {
            if (row.sessionId != sessionId) continue
            if (now - row.updatedAt > ttlMillis) continue
            latest = row
        }
        compactIfNeeded(now)
        return latest
    }

    /** Append the current state; appends are serialized by [JsonlPersistence]. */
    fun save(snapshot: SessionSnapshot) {
        persistence.append(snapshot)
    }

    /**
     * Fold the log to one row per session id when it grew too big.
     *
     * Runs inside the persistence's own lock ([JsonlPersistence.rewrite] with a
     * supplier), so a row appended by a peer session is re-read and kept instead of
     * vanishing between the scan and the replacement.
     */
    private fun compactIfNeeded(now: Long) {
        if (persistence.sizeInBytes() <= COMPACTION_SIZE_THRESHOLD_BYTES) return
        persistence.rewrite {
            val folded = LinkedHashMap<String, SessionSnapshot>()
            persistence.readAll().forEach { row ->
                if (now - row.updatedAt > ttlMillis) return@forEach
                folded[row.sessionId] = row
            }
            folded.values
        }
    }

    companion object {
        /** Above this size a restore folds the file (see [compactIfNeeded]). */
        const val COMPACTION_SIZE_THRESHOLD_BYTES = 4L * 1024 * 1024

        private val sharedPersistences = ConcurrentHashMap<Path, JsonlPersistence<SessionSnapshot>>()

        /** `agent-session-context.jsonl`, beside the task registry. */
        fun defaultPath(): Path = StatefulAgentRunner.agentPersistencePath()
            .resolveSibling("agent-session-context.jsonl")

        /** The one persistence instance per file in this process. */
        fun shared(persistencePath: Path = defaultPath()): JsonlPersistence<SessionSnapshot> =
            sharedPersistences.computeIfAbsent(persistencePath) { path ->
                JsonlPersistence(
                    file = path,
                    clazz = SessionSnapshot::class,
                    objectMapper = pulsarObjectMapper(),
                )
            }

        /**
         * The store for [sessionKey], or `null` when there is nothing to persist to.
         *
         * `null` for a blank key, for a non-positive TTL (persistence switched off) and
         * for the backend's anonymous DEFAULT session: every session-less caller
         * (`agent run --coding`, an MCP client that sends no `sessionId`) shares that
         * pool, so persisting it would make unrelated one-shot tasks inherit each
         * other's context across restarts — the exact surprise the per-session context
         * exists to avoid. It keeps its in-memory conversation as before.
         *
         * @param persistencePath overridable so tests never touch the real data dir
         */
        fun open(
            sessionKey: String?,
            ttlMinutes: Long,
            persistencePath: Path = defaultPath(),
        ): SessionConversationStore? {
            val key = sessionKey
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != DEFAULT_SESSION_ID }
                ?: return null
            if (ttlMinutes <= 0) return null
            return SessionConversationStore(key, shared(persistencePath), ttlMinutes)
        }
    }
}

/** How a terminal status is remembered in the transcript. */
private fun statusWordOf(status: AgentTaskStatus): String = when {
    status.cancelled -> "cancelled"
    status.statusCode == ResourceStatus.SC_OK -> "done"
    else -> "failed"
}

/** `2m 13s` / `45s` — short, because it rides in a transcript turn. */
internal fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
