package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

/**
 * Task-start and task-end lifecycle helpers for the agent workspace
 * (coding-run-isolation-design.md §6.1–§6.2).
 *
 * The manifest is the single source of truth for "what did the previous task
 * leave behind?". This class is deliberately non-suspending and side-effect
 * light (file moves + a single `git status` invocation) so it can be called
 * from the runner's `finally` (cancel path) as well as the normal start path.
 */
class WorkspaceTaskLifecycle(
    private val store: WorkspaceManifestStore = WorkspaceManifestStore(),
) {
    private val logger = LoggerFactory.getLogger(WorkspaceTaskLifecycle::class.java)

    /**
     * Work to do at the start of a CLI task.
     *
     * - If the manifest records a previous task whose id differs from this one
     *   **and** this is a coding task: archive that task's deliverables and
     *   render the "inherited workspace state" block.
     * - Always reset the file system's change tracking (archiveDir != null for
     *   coding, null otherwise) so the new task opens with `changeSummary=0`.
     *
     * The archive/inheritance guard intentionally skips the same-taskId case
     * (resume / re-run of a cancelled task) — a resume must not archive the
     * half-finished work it is about to continue.
     */
    fun beginTask(
        workspaceRoot: Path,
        taskId: String,
        instruction: String,
        coding: Boolean,
        fs: CodingAgentFileSystem,
    ): BeginTaskOutcome {
        val manifest = runCatching { store.read(workspaceRoot) }.getOrNull()
        val archiveDir = if (coding) archiveDir(workspaceRoot) else null

        // Reset change tracking first so any mid-archive failure still leaves a
        // clean counter for the new task (§6.2.1 "先归档、后重置" is about file
        // movement order; the counter reset is independent and must not be
        // skipped).
        fs.beginTask(archiveDir)

        val inherited = runCatching {
            if (!coding) null
            else {
                val prev = manifest?.lastTask ?: return@runCatching null
                if (prev.taskId == taskId) return@runCatching null // resume guard
                archivePreviousDeliverables(workspaceRoot, prev)
                renderInheritedSummary(prev)
            }
        }.onFailure { logger.warn("beginTask: archive/inheritance failed", it) }
            .getOrNull()

        return BeginTaskOutcome(
            archiveDir = archiveDir,
            inheritedSummary = inherited,
            previousTask = manifest?.lastTask?.takeIf { it.taskId != taskId },
        )
    }

    /** Archive `<workspace>/.browser4/coding-snapshots` is handled by [CodingAgentFileSystem.beginTask];
     *  this archives the *deliverable files* listed in the previous task's
     *  `filesChanged` so a stale run's changes survive the next task's reset.
     *  Junction/symlink entries are skipped (they'd recurse into the source
     *  repo). Limits: 50 files / 20 MB. */
    private fun archivePreviousDeliverables(workspaceRoot: Path, prev: TaskSnapshot) {
        val dir = archiveDir(workspaceRoot).resolve(prev.taskId)
        runCatching { Files.createDirectories(dir) }.getOrElse { return }
        var copied = 0
        var bytes = 0L
        val cap = 50
        val byteCap = 20L * 1024 * 1024
        val truncated = mutableListOf<String>()
        for (rel in prev.filesChanged) {
            if (copied >= cap || bytes >= byteCap) {
                truncated += rel
                continue
            }
            val src = workspaceRoot.resolve(rel)
            if (!src.exists()) continue
            if (isLink(src)) continue // never recurse into junctions
            runCatching {
                val target = dir.resolve(rel)
                Files.createDirectories(target.parent)
                Files.copy(src, target)
                bytes += Files.size(target)
                copied++
            }.onFailure { logger.warn("archive: failed to copy {}", rel, it) }
        }
        if (truncated.isNotEmpty() || copied > 0) {
            runCatching {
                val meta = mapOf(
                    "taskId" to prev.taskId,
                    "copied" to copied,
                    "truncated" to truncated,
                    "source" to "manifest.filesChanged",
                )
                Files.writeString(dir.resolve("archive.json"), meta.toString())
            }
        }
        logger.info("archived previous task {}: {} file(s) into {}", prev.taskId, copied, dir)
    }

    /** Render the "inherited workspace state" prompt block. Kept to ≤10 lines
     *  and never says "completed" — the previous edits may have been reverted
     *  by a human, so we only state that they *existed*. */
    fun renderInheritedSummary(prev: TaskSnapshot): String {
        val files = prev.filesChanged.take(5).joinToString(", ") +
            if (prev.filesChanged.size > 5) ", …" else ""
        return buildString {
            appendLine("## Inherited workspace state")
            append("This workspace was used by an earlier task (${prev.taskId}, ")
            append("${prev.status?.name?.lowercase() ?: "ended"} ")
            append((prev.endedAt ?: Instant.now()).toString().take(16))
            appendLine("): ${prev.instruction.take(WorkspaceManifest.MAX_INSTRUCTION_LEN)}…")
            appendLine("It changed ${prev.filesChanged.size} file(s): $files.")
            appendLine("Those edits are already in the working tree. Verify them with coding.changeSummary / coding.diff")
            appendLine("before building on them; do not read old build logs or backup copies.")
        }
    }

    /** A link (symlink or Windows junction) is any path whose real location
     *  differs from its absolute path — same trick as
     *  [CodingAgentFileSystem.resolveLinkTarget]. */
    private fun isLink(p: Path): Boolean = runCatching {
        val absolute = p.toAbsolutePath().normalize()
        val real = p.toRealPath()
        absolute != real
    }.getOrDefault(false)

    /** Capture `git status --porcelain` in [sourceRepo] as the pre-task baseline
     *  (one entry per changed file). Returns empty if git is unavailable or the
     *  repo has no working tree. */
    fun captureGitBaseline(sourceRepo: Path?): Set<String> {
        if (sourceRepo == null || !Files.isDirectory(sourceRepo.resolve(".git"))) return emptySet()
        return runCatching {
            val proc = ProcessBuilder("git", "-C", sourceRepo.toString(), "status", "--porcelain")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            out.lineSequence()
                .filter { it.isNotBlank() }
                .map { it.substringAfter(' ').trim() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** filesChanged = (Δgit from baseline) ∪ (mutation log deliverables). */
    fun computeFilesChanged(
        sourceRepo: Path?,
        baseline: Set<String>,
        mutationDeliverables: Collection<String>,
    ): List<String> {
        val delta = if (sourceRepo == null) emptySet() else captureGitBaseline(sourceRepo)
        val changed = (delta - baseline) union mutationDeliverables
        return changed.take(WorkspaceManifest.MAX_FILES_CHANGED).toList()
    }

    companion object {
        fun archiveDir(workspaceRoot: Path): Path =
            workspaceRoot.parent.resolve("workspace.archive")
    }
}

/** Result of [WorkspaceTaskLifecycle.beginTask]. */
data class BeginTaskOutcome(
    val archiveDir: Path?,
    val inheritedSummary: String?,
    val previousTask: TaskSnapshot?,
)
