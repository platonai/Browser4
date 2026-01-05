package ai.platon.pulsar.agentic.ai.todo

import ai.platon.pulsar.agentic.AgentConfig
import ai.platon.pulsar.agentic.ai.agent.detail.StructuredAgentLogger
import ai.platon.pulsar.agentic.common.AgentFileSystem
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.agentic.ObserveElement
import ai.platon.pulsar.agentic.ToolCall
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Manages the todolist.md file for an agent session, tracking task progress, plans, and notes.
 *
 * This manager provides methods to:
 * - Initialize the todo file with a header and sections
 * - Append progress entries for completed actions
 * - Mark plan items as done based on tags
 * - Track progress counters
 * - Read the current plan section
 *
 * The todolist.md file structure:
 * ```
 * # TODO for session {shortId}
 * Instruction: {instruction}
 * Started at: {timestamp}
 * Current URL: {url}
 * Progress: (n/∞)
 *
 * ## Plan
 * - [ ] Step 1: ...
 *
 * ## Progress Log
 * - [OK] HH:mm:ss method "selector" @ url | summary
 *
 * ## Notes
 * ```
 *
 * @param fs The file system to use for reading/writing the todo file.
 * @param config Agent configuration for todo-related settings.
 * @param uuid Unique identifier for the session.
 * @param slogger Optional structured logger for error reporting.
 */
class ToDoManager(
    private val fs: AgentFileSystem,
    private val config: AgentConfig,
    private val uuid: UUID,
    private val slogger: StructuredAgentLogger? = null,
) {
    companion object {
        /** Section header for the plan section. */
        const val SECTION_PLAN = "## Plan"
        /** Section header for the progress log section. */
        const val SECTION_PROGRESS_LOG = "## Progress Log"
        /** Section header for the notes section. */
        const val SECTION_NOTES = "## Notes"
        /** Marker for completed/OK progress lines. */
        const val MARKER_OK = "- [OK]"
        /** Marker for unchecked plan items. */
        const val MARKER_UNCHECKED = "- [ ]"
        /** Marker for checked plan items. */
        const val MARKER_CHECKED = "- [x]"
        /** Regex pattern for matching progress counter in header. */
        private val PROGRESS_COUNTER_REGEX = Regex("""Progress:\s*\((\d+)/(∞|\d+)\)""")
        /** Regex pattern for matching OK markers. */
        private val OK_MARKER_REGEX = Regex("""- \[OK\]""")
    }
    /**
     * Initializes the todolist.md file with header and sections if it's empty.
     *
     * Creates the initial structure including:
     * - Session header with ID, instruction, timestamp, and URL
     * - Progress counter initialized to (0/∞)
     * - Plan section with placeholder steps (if todoPlanWithLLM is enabled)
     * - Progress Log section
     * - Notes section
     *
     * @param instruction The task instruction for this session.
     * @param url The starting URL, or null if unknown.
     */
    suspend fun primeIfEmpty(instruction: String, url: String?) {
        val content = fs.getTodoContents()
        if (content.isNotBlank()) return
        val shortId = uuid.toString().take(8)
        val now = Instant.now().toString()
        val header = buildString {
            appendLine("# TODO for session $shortId")
            appendLine("Instruction: ${Strings.compactInline(instruction, 200)}")
            appendLine("Started at: $now")
            appendLine("Current URL: ${url ?: "(unknown)"}")
            appendLine("Progress: (0/∞)")
            appendLine()
            appendLine(SECTION_PLAN)
            if (config.todoPlanWithLLM) {
                appendLine("$MARKER_UNCHECKED Step 1: TBD  #action:navigateTo")
                appendLine("$MARKER_UNCHECKED Step 2: TBD  #action:click")
            } else {
                appendLine("(Plan TBD — 将在执行过程中逐步完善，并通过标签进行勾选)")
            }
            appendLine()
            appendLine(SECTION_PROGRESS_LOG)
            appendLine()
            appendLine(SECTION_NOTES)
        }
        runCatching { fs.writeString("todolist.md", header) }
            .onFailure { e -> slogger?.logError("todo.prime.write.fail", e, uuid.toString()) }
    }

    /**
     * Appends a progress entry for a completed action.
     *
     * Creates a line in the format: `- [OK] HH:mm:ss method "selector" @ url | summary`
     * and appends it to the todolist.md file.
     *
     * Enforces a maximum number of progress lines as configured in [AgentConfig.todoMaxProgressLines].
     *
     * @param step The current step number.
     * @param toolCall The tool call that was executed, or null if unknown.
     * @param observe The observed element, or null if not applicable.
     * @param url The current URL.
     * @param summary Optional summary of the action result.
     * @return `true` if the progress was appended, `false` if the max lines limit was reached or an error occurred.
     */
    suspend fun appendProgress(
        step: Int,
        toolCall: ToolCall?,
        observe: ObserveElement?,
        url: String,
        summary: String?,
    ): Boolean {
        val time = LocalDateTime.now().toLocalTime().toString().take(8)
        val method = toolCall?.method ?: "(unknown)"
        val selector = selectorSnippet(observe)
        val summaryText = Strings.compactInline(summary ?: "", 120)
        val line = "$MARKER_OK $time $method \"${selector}\" @ ${url} | ${summaryText}\n"

        val existing = fs.getTodoContents()
        // Count any occurrence of the OK marker (don't rely on a previous newline)
        val okLines = OK_MARKER_REGEX.findAll(existing).count()
        if (okLines >= config.todoMaxProgressLines) return false

        val updated = if (existing.endsWith("\n")) existing + line else existing + "\n" + line
        return runCatching {
            fs.writeString("todolist.md", updated)
            true
        }.onFailure { e ->
            slogger?.logError("todo.progress.write.fail", e, uuid.toString())
        }.getOrElse { false }
    }

    /**
     * Increments the progress counter in the header.
     *
     * Looks for a pattern like `Progress: (n/∞)` or `Progress: (n/m)` and increments `n`.
     * Does nothing if the pattern is not found.
     */
    suspend fun updateProgressCounter() {
        val content = fs.getTodoContents()
        val m = PROGRESS_COUNTER_REGEX.find(content) ?: return
        val cur = m.groupValues[1].toIntOrNull() ?: return
        val den = m.groupValues[2]
        val newFrag = "Progress: (${cur + 1}/${den})"
        val updated = content.replaceRange(m.range, newFrag)
        runCatching { fs.writeString("todolist.md", updated) }
            .onFailure { e -> slogger?.logError("todo.progress.counter.fail", e, uuid.toString()) }
    }

    /**
     * Marks the first unchecked plan item containing any of the specified tags as done.
     *
     * Searches for lines starting with `- [ ]` (unchecked) that contain any of the tags
     * (case-insensitive), and replaces the first match with `- [x]` (checked).
     *
     * @param tags Set of tags to search for (e.g., `#action:click`, `#domain:example.com`).
     */
    suspend fun markPlanItemDoneByTags(tags: Set<String>) {
        if (tags.isEmpty()) return
        val content = fs.getTodoContents()
        val lines = content.split("\n").toMutableList()
        var modified = false
        for (i in lines.indices) {
            val line = lines[i]
            val lineLower = line.lowercase()
            // match unchecked plan items and do a case-insensitive tag search
            if (line.trimStart().startsWith(MARKER_UNCHECKED) && tags.any { tag -> lineLower.contains(tag.lowercase()) }) {
                lines[i] = line.replaceFirst(MARKER_UNCHECKED, MARKER_CHECKED)
                modified = true
                break
            }
        }
        if (modified) {
            runCatching { fs.writeString("todolist.md", lines.joinToString("\n")) }
                .onFailure { e -> slogger?.logError("todo.plan.check.fail", e, uuid.toString()) }
        }
    }

    /**
     * Builds a set of tags from a tool call and URL.
     *
     * Creates tags in the format:
     * - `#action:{method}` - from the tool call method name
     * - `#domain:{host}` - from the URL's host component
     *
     * @param toolCall The tool call to extract the action tag from, or null.
     * @param url The URL to extract the domain tag from, or null.
     * @return A set of tags, empty if toolCall is null.
     */
    fun buildTags(toolCall: ToolCall?, url: String?): Set<String> {
        if (toolCall == null) return emptySet()
        val tags = mutableSetOf<String>()
        val method = toolCall.method.trim().lowercase()
        if (method.isNotBlank()) tags.add("#action:$method")
        val host = extractHost(url)
        if (!host.isNullOrBlank()) tags.add("#domain:$host")
        return tags
    }

    /**
     * Records task completion by appending a completion entry and updating the progress counter.
     *
     * @param instruction The original task instruction.
     */
    suspend fun onTaskCompletion(instruction: String) {
        val ts = LocalDateTime.now().toLocalTime().toString().take(8)
        val line = "$MARKER_OK $ts task.complete | $instruction\n"
        val current = fs.getTodoContents()
        val updated = if (current.endsWith("\n")) current + line else current + "\n" + line
        runCatching { fs.writeString("todolist.md", updated) }
            .onFailure { e -> slogger?.logError("todo.complete.write.fail", e, uuid.toString()) }
        updateProgressCounter()
    }

    /**
     * Returns the current progress count from the header.
     *
     * @return The current progress count, or 0 if not found.
     */
    fun getProgressCount(): Int {
        val content = fs.getTodoContents()
        val m = PROGRESS_COUNTER_REGEX.find(content) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    /**
     * Returns the plan section content (lines between "## Plan" and the next section).
     *
     * @return List of plan item lines (including markers like `- [ ]` or `- [x]`), or empty list if not found.
     */
    fun getPlan(): List<String> {
        val content = fs.getTodoContents()
        val lines = content.split("\n")
        val planStart = lines.indexOfFirst { it.trim() == SECTION_PLAN }
        if (planStart == -1) return emptyList()

        val planLines = mutableListOf<String>()
        for (i in (planStart + 1) until lines.size) {
            val line = lines[i]
            // Stop at the next section header
            if (line.trimStart().startsWith("## ")) break
            if (line.isNotBlank()) planLines.add(line)
        }
        return planLines
    }

    /**
     * Removes all checked plan items (lines with `- [x]`).
     *
     * @return The number of items removed.
     */
    suspend fun clearCompletedPlanItems(): Int {
        val content = fs.getTodoContents()
        val lines = content.split("\n")
        val filtered = lines.filterNot { it.trimStart().startsWith(MARKER_CHECKED) }
        val removedCount = lines.size - filtered.size
        if (removedCount > 0) {
            runCatching { fs.writeString("todolist.md", filtered.joinToString("\n")) }
                .onFailure { e -> slogger?.logError("todo.clear.completed.fail", e, uuid.toString()) }
        }
        return removedCount
    }

    /**
     * Extracts the host from a URL string.
     *
     * Handles URLs with or without a scheme prefix.
     *
     * @param url The URL to parse.
     * @return The host in lowercase, or null if extraction fails.
     */
    private fun extractHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val normalizedUrl = if (url.contains("://")) url else "http://$url"
            URI(normalizedUrl).host?.lowercase()
        }.getOrNull()
    }

    private fun selectorSnippet(observe: ObserveElement?): String {
        if (observe == null) return ""
        val s = observe.cssSelector
            ?: observe.locator
            ?: observe.backendNodeId?.let { "backend:$it" }
            ?: ""
        return s.take(80)
    }
}
