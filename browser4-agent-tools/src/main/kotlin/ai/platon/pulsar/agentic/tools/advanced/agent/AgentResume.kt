package ai.platon.pulsar.agentic.tools.advanced.agent

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * What a resumed run needs from the interrupted task.
 *
 * @property taskId the interrupted task's id
 * @property instruction the instruction it ran with (null when the row predates the field)
 * @property workspace its workspace, when it still exists on disk
 * @property lastActivity when it last showed progress
 * @property toolExecutions how many tool calls it had executed
 * @property codingMode whether it was a coding task
 * @property accessPreset the access preset it ran under
 * @property files a bounded listing of the files already in the workspace
 */
internal data class ResumeCheckpoint(
    val taskId: String,
    val instruction: String?,
    val workspace: Path?,
    val lastActivity: Instant,
    val toolExecutions: Int,
    val codingMode: Boolean?,
    val accessPreset: String?,
    val files: List<String>,
)

/** Files listed in a resume block — bounded because the block ships in every prompt of the new run. */
internal const val RESUME_MAX_FILES = 20

/** Heading of the block [resumeInstruction] appends. */
internal const val RESUME_BLOCK_MARKER = "## Resume context"

/**
 * The task text without a resume block a previous resume appended.
 *
 * A resumed run's prompt carries the block, but the *task* is still the original
 * text: storing the block as the submitted task would nest blocks on the next
 * resume and misreport what the user asked for.
 */
internal fun stripResumeBlock(instruction: String): String {
    val index = instruction.indexOf(RESUME_BLOCK_MARKER)
    return (if (index >= 0) instruction.substring(0, index) else instruction).trimEnd()
}

/**
 * Build the checkpoint a resumed run starts from.
 *
 * The workspace listing is best-effort: a task whose workspace was deleted (a
 * different backend, or cleanup) still resumes from its instruction and recorded
 * progress; it just has nothing to inspect.
 */
internal fun resumeCheckpointOf(status: AgentTaskStatus, maxFiles: Int = RESUME_MAX_FILES): ResumeCheckpoint {
    val workspace = status.workspace
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Path.of(it) }.getOrNull() }
        ?.takeIf { Files.isDirectory(it) }
    return ResumeCheckpoint(
        taskId = status.id,
        instruction = status.submittedTask?.takeIf { it.isNotBlank() },
        workspace = workspace,
        lastActivity = status.lastActivity(),
        toolExecutions = status.toolExecutions,
        codingMode = status.codingMode,
        accessPreset = status.accessPreset,
        files = workspace?.let { listWorkspaceFiles(it, maxFiles) }.orEmpty(),
    )
}

/** Top-level files plus `.b4scratch/` entries, bounded and sorted for a stable prompt. */
internal fun listWorkspaceFiles(workspace: Path, maxFiles: Int = RESUME_MAX_FILES): List<String> = runCatching {
    val top = Files.newDirectoryStream(workspace).use { entries ->
        entries.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.sorted()
    }
    val scratchDir = workspace.resolve(".b4scratch")
    val scratch = if (Files.isDirectory(scratchDir)) {
        Files.newDirectoryStream(scratchDir).use { entries ->
            entries.filter { Files.isRegularFile(it) }.map { ".b4scratch/" + it.fileName.toString() }.sorted()
        }
    } else emptyList()
    (top + scratch).take(maxFiles)
}.getOrDefault(emptyList())

/** Cap for the deliverables snapshot of an aborted run. */
internal const val MAX_ABORT_DELIVERABLES = 50

/**
 * The directory a task runs in.
 *
 * Isolation is the DEFAULT: every task gets `<agent base dir>/workspace.<taskId>`, so
 * files from an earlier run are never in this run's workspace (and never in its
 * `filesChanged`). The documents have promised this since agent.md/agent-workspace.md
 * were written; only `--fresh-workspace` actually did it.
 *
 * @param baseDir the agent's data directory
 * @param taskId the task id (also the workspace suffix)
 * @param resumeWorkspace the interrupted task's workspace when resuming — the files it
 *   already wrote are what the new run continues from
 * @param sharedWorkspace `--shared-workspace`: use the shared `<baseDir>/workspace`
 * @return the workspace to use, or `null` for the shared `<baseDir>/workspace`. The runner
 *   rebinds to this workspace before applying the task's access policy, so the policy file is
 *   read from it rather than from the workspace that was active before the task started.
 */
internal fun taskWorkspaceFor(
    baseDir: Path,
    taskId: String,
    resumeWorkspace: Path?,
    sharedWorkspace: Boolean?,
): Path? = when {
    resumeWorkspace != null -> resumeWorkspace
    sharedWorkspace == true -> null
    else -> baseDir.resolve("workspace.$taskId")
}

/**
 * The files a run had already produced in its workspace, for a run that ended
 * **without** reporting them.
 *
 * A token-budget or step-cap abort used to leave `filesChanged` empty, so a task whose
 * CSV was complete on disk was reported as a bare failure and the data looked lost
 * (2026-10-28: 20 rows × 75 fields written, task `failed (417)` with no artifacts).
 *
 * ## Why attribution matters
 *
 * The old version listed **every** top-level regular file in the workspace. On a
 * reused workspace that included files from earlier tasks — the 2026-10-28 baseline
 * reported an old CSV (written 36 minutes before the run) and another agent's
 * `msedge-*.md` report as this run's deliverables. The list is what the finish gate
 * compares a completion report against, so a wrong list is worse than an empty one.
 *
 * Sources, in order of trust:
 * 1. [mutations] — the run's own mutation log (`CodingAgentFileSystem`); exact, and it
 *    already excludes scratch/agent-state paths;
 * 2. files modified at or after [startedAt] — the fallback for a run whose writes went
 *    through something other than the coding file tools (a CLI script);
 * 3. every top-level file — only when the call site knows no start time (legacy
 *    cancel paths).
 *
 * `.b4scratch/`, `.b4inputs/`, `.browser4*` and dot-entries are working state, not
 * deliverables; they are excluded from each source.
 *
 * @param workspace the run's workspace root
 * @param startedAt when the run started, or null when unknown
 * @param mutations the run's mutation paths (workspace-relative)
 * @param maxFiles bound on the returned list
 */
internal fun deliverableFilesOf(
    workspace: String?,
    startedAt: Instant? = null,
    mutations: Collection<String> = emptyList(),
    maxFiles: Int = MAX_ABORT_DELIVERABLES,
): List<String> {
    val dir = workspace
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Path.of(it) }.getOrNull() }
        ?.takeIf { Files.isDirectory(it) }
        ?: return emptyList()

    val fromMutations = mutations.asSequence()
        .map { it.replace('\\', '/').trim() }
        .filter { isDeliverablePath(it) }
        .filter { runCatching { Files.isRegularFile(dir.resolve(it)) }.getOrDefault(false) }
        .distinct()
        .sorted()
        .take(maxFiles)
        .toList()
    if (fromMutations.isNotEmpty()) return fromMutations

    return runCatching {
        Files.newDirectoryStream(dir).use { entries ->
            entries.filter { entry ->
                Files.isRegularFile(entry) &&
                    isDeliverablePath(entry.fileName.toString()) &&
                    (startedAt == null || modifiedAtOrAfter(entry, startedAt))
            }
                .map { it.fileName.toString() }
                .sorted()
                .take(maxFiles)
        }
    }.getOrDefault(emptyList())
}

/** True when a workspace-relative path can be a deliverable (not agent state). */
private fun isDeliverablePath(relative: String): Boolean {
    if (relative.isBlank()) return false
    if (relative.startsWith("/") || relative.contains(":\\") || relative.startsWith("\\\\")) return false
    val parts = relative.split('/')
    if (parts.any { it.isEmpty() || it == ".." || it == "." }) return false
    // Dot-entries cover `.b4scratch/`, `.b4inputs/`, `.browser4/` and editor droppings.
    return !parts.first().startsWith(".")
}

/** True when [path] was modified at or after [startedAt] (a file system race floors at 0). */
private fun modifiedAtOrAfter(path: Path, startedAt: Instant): Boolean = runCatching {
    val modified = Files.getLastModifiedTime(path).toInstant()
    // One-second slack: archives and Windows FAT-style timestamps round down, and a
    // deliverable written in the same second the task started must not be dropped.
    !modified.isBefore(startedAt.minusSeconds(1))
}.getOrDefault(false)

/**
 * The instruction a resumed run starts from.
 *
 * A resumed run is a NEW run: the old conversation is gone with the process, so the
 * only continuity is the old task's instruction, its workspace and the files already
 * in it. The block tells the model exactly that — and forbids the failure mode a
 * "start over" prompt produces (re-fetching pages and rewriting rows that already
 * exist).
 *
 * @param original the task text the caller passed with `--resume` (may be empty)
 * @param checkpoint the interrupted task's checkpoint
 * @return the seeded instruction: the original text (or the checkpoint's own
 *   instruction when the caller passed none) followed by the resume block
 */
internal fun resumeInstruction(original: String, checkpoint: ResumeCheckpoint): String {
    val base = original.trim().ifEmpty { stripResumeBlock(checkpoint.instruction.orEmpty()) }
    return buildString {
        if (base.isNotEmpty()) {
            appendLine(base)
            appendLine()
        }
        appendLine(RESUME_BLOCK_MARKER)
        appendLine()
        appendLine("This run resumes task ${checkpoint.taskId}, which was interrupted before completion")
        appendLine("(last activity ${checkpoint.lastActivity}, ${checkpoint.toolExecutions} tool call(s) executed).")
        if (checkpoint.workspace != null) {
            appendLine("Its workspace is ${checkpoint.workspace} and already contains:")
            if (checkpoint.files.isEmpty()) {
                appendLine("- (no files yet)")
            } else {
                checkpoint.files.forEach { appendLine("- $it") }
            }
        } else {
            appendLine("Its previous workspace is no longer available, so start from what the task describes.")
        }
        appendLine()
        appendLine("Do not start over: inspect those files first, keep what is already correct, and continue")
        appendLine("with the remaining work. Extend an incomplete data file instead of rewriting the rows")
        appendLine("already collected, and report the same deliverable the task asked for.")
    }
}
