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
 * The files a run had already produced in its workspace, for a run that ended
 * **without** reporting them.
 *
 * A token-budget or step-cap abort used to leave `filesChanged` empty, so a task whose
 * CSV was complete on disk was reported as a bare failure and the data looked lost
 * (2026-10-28: 20 rows × 75 fields written, task `failed (417)` with no artifacts).
 *
 * Only top-level regular files count: `.b4scratch/` helper scripts, `.b4inputs/`
 * copies and dot-files are working state, not deliverables — the browsing prompt says
 * so explicitly. The list is bounded and sorted so a pathological workspace cannot
 * bloat a status row and repeated polls stay stable.
 */
internal fun deliverableFilesOf(workspace: String?, maxFiles: Int = MAX_ABORT_DELIVERABLES): List<String> {
    val dir = workspace
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Path.of(it) }.getOrNull() }
        ?.takeIf { Files.isDirectory(it) }
        ?: return emptyList()
    return runCatching {
        Files.newDirectoryStream(dir).use { entries ->
            entries.filter { Files.isRegularFile(it) && !it.fileName.toString().startsWith(".") }
                .map { it.fileName.toString() }
                .sorted()
                .take(maxFiles)
        }
    }.getOrDefault(emptyList())
}

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
