package ai.platon.pulsar.coding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Result of one `git` invocation (argument array, no shell). */
private data class GitOutcome(val exitCode: Int, val stdout: String, val stderr: String) {
    val output: String get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
}

/**
 * Run git in [dir]. Git is invoked directly with an argument array: no shell, no
 * quoting, and no way for a path to turn into a command.
 */
private fun runGit(dir: Path, args: List<String>): GitOutcome = runCatching {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(dir.toFile())
        .redirectErrorStream(false)
        .start()
    // git is fast; a hung git is a broken environment, not a slow task.
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return GitOutcome(-1, "", "git ${args.joinToString(" ")} timed out")
    }
    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }
    GitOutcome(process.exitValue(), stdout, stderr)
}.getOrElse { GitOutcome(-1, "", it.message ?: it.javaClass.simpleName) }

/**
 * One transactional workspace for a dev task (plan P2.1).
 *
 * A task that edits the repository leaves the developer's working tree dirty
 * even when it fails half-way: a compile error at step 6 does not undo the files
 * written at steps 2–5. `WorkspaceIsolation` runs the task in a **git worktree**
 * instead — a checkout of the same commit in a managed directory — so:
 *
 * - every write lands in the isolated tree,
 * - success merges the change back into the main work tree as a patch,
 * - failure removes the worktree, leaving the main tree byte-identical.
 *
 * The worktree lives under `<repo>/.browser4/worktrees/<runId>/` (git-ignored).
 *
 * Off by default: `AgentConfig.workspaceIsolation` /
 * `-Dbrowser4.agent.workspace.isolation=worktree` selects it, and production
 * wiring stays opt-in until the path has been observed (plan Q3(b)).
 */
class WorkspaceIsolation private constructor(
    /** The main work tree the task must not touch. */
    val repoRoot: Path,
    /** The isolated worktree every write of the task goes to. */
    val isolatedRoot: Path,
    private val runId: String,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(WorkspaceIsolation::class.java)

        /** Directory under the repo root that holds managed worktrees (git-ignored). */
        const val WORKTREE_DIR = ".browser4/worktrees"

        /**
         * Create an isolated worktree of [candidateRoot]'s repository.
         *
         * @throws IllegalStateException when the path is not a git work tree or
         *   `git worktree add` fails — isolation must never silently degrade to
         *   "no isolation"; the caller decides what to do instead.
         */
        suspend fun begin(candidateRoot: Path, runId: String): WorkspaceIsolation {
            val root = resolveRepoRoot(candidateRoot)
                ?: throw IllegalStateException(
                    "Workspace isolation requires a git work tree; '$candidateRoot' is not inside one"
                )
            val isolated = root.resolve(WORKTREE_DIR).resolve(sanitize(runId))
            withContext(Dispatchers.IO) { Files.createDirectories(isolated.parent) }
            val add = runGit(root, listOf("worktree", "add", "--detach", "--force", isolated.toString(), "HEAD"))
            if (add.exitCode != 0) {
                // Retry once after pruning stale metadata: a crashed run leaves
                // an entry that blocks the same path.
                runGit(root, listOf("worktree", "prune"))
                val retry = runGit(root, listOf("worktree", "add", "--detach", "--force", isolated.toString(), "HEAD"))
                if (retry.exitCode != 0) {
                    throw IllegalStateException(
                        "git worktree add failed (exit ${retry.exitCode}): ${retry.output.takeLast(500)}"
                    )
                }
            }
            logger.info("Workspace isolation started: {} -> {}", root, isolated)
            return WorkspaceIsolation(root, isolated, runId)
        }

        /** The enclosing git work tree of [path], or null when there is none. */
        fun resolveRepoRoot(path: Path): Path? {
            val dir = runCatching { path.toAbsolutePath().normalize() }.getOrNull() ?: return null
            val result = runGit(dir, listOf("rev-parse", "--show-toplevel"))
            if (result.exitCode != 0) return null
            val root = result.stdout.trim().lineSequence().lastOrNull()?.trim().orEmpty()
            if (root.isEmpty()) return null
            return runCatching { Path.of(root).toAbsolutePath().normalize() }.getOrNull()
        }

        /** Whether [candidateRoot] can be isolated right now. */
        fun isAvailable(candidateRoot: Path): Boolean =
            resolveRepoRoot(candidateRoot) != null

        /**
         * Re-attach to a worktree created by an earlier (possibly crashed) run,
         * so a resumed task can commit or discard it.
         *
         * @return null when [isolatedRoot] is not a worktree of a repository
         */
        fun reopen(isolatedRoot: Path, runId: String): WorkspaceIsolation? {
            val root = runCatching { isolatedRoot.toAbsolutePath().normalize() }.getOrNull() ?: return null
            if (!Files.isDirectory(root)) return null
            val mainRoot = resolveMainRepoRoot(root) ?: return null
            return WorkspaceIsolation(mainRoot, root, runId)
        }

        /**
         * The MAIN work tree of a linked worktree: `--git-common-dir` points at
         * `<main>/.git`, whose parent is the main work tree.
         */
        internal fun resolveMainRepoRoot(worktree: Path): Path? {
            val common = runGit(worktree, listOf("rev-parse", "--path-format=absolute", "--git-common-dir"))
            if (common.exitCode == 0 && common.stdout.isNotBlank()) {
                val gitDir = runCatching { Path.of(common.stdout.trim().lineSequence().last()) }.getOrNull()
                val parent = gitDir?.parent
                if (parent != null && Files.isDirectory(parent)) return parent.toAbsolutePath().normalize()
            }
            // Fallback for git < 2.31 (no --path-format): the relative form is
            // resolved against the worktree itself.
            val relative = runGit(worktree, listOf("rev-parse", "--git-common-dir"))
            if (relative.exitCode != 0 || relative.stdout.isBlank()) return null
            val gitDir = runCatching { worktree.resolve(relative.stdout.trim()).normalize() }.getOrNull()
            return gitDir?.parent?.toAbsolutePath()?.normalize()
        }

        internal fun sanitize(runId: String): String =
            runId.replace(Regex("[^A-Za-z0-9._-]"), "-").take(64).ifBlank { "task" }
    }

    /** Paths (repo-relative) the task changed inside the isolated worktree. */
    suspend fun changedFiles(): List<String> = withContext(Dispatchers.IO) {
        val status = runGit(isolatedRoot, listOf("status", "--porcelain"))
        if (status.exitCode != 0) return@withContext emptyList()
        status.stdout.lineSequence().mapNotNull { line ->
            if (line.length < 4) null else line.substring(3).trim().trim('"').replace('\\', '/')
        }.filter { it.isNotEmpty() }.toList()
    }

    /**
     * Merge the isolated change into the main work tree.
     *
     * Stages everything in the worktree, then applies the resulting patch to the
     * main tree with `git apply --3way`. A conflict is reported as a failure and
     * the worktree is kept for inspection — git applies the patch atomically or
     * not at all, so the main tree is never left half-merged.
     */
    suspend fun commit(message: String = "b4 isolated task"): Outcome = withContext(Dispatchers.IO) {
        val changed = changedFiles()
        if (changed.isEmpty()) {
            cleanupWorktree()
            return@withContext Outcome(true, emptyList(), "No changes to merge (isolated tree unchanged)")
        }
        val add = runGit(isolatedRoot, listOf("add", "-A"))
        if (add.exitCode != 0) {
            return@withContext Outcome(false, changed, "git add failed: ${add.output.takeLast(400)}")
        }
        val staged = runGit(isolatedRoot, listOf("diff", "--cached", "--binary"))
        if (staged.exitCode != 0 || staged.stdout.isBlank()) {
            return@withContext Outcome(
                false, changed,
                "Could not produce the isolated patch: ${staged.output.takeLast(400)}"
            )
        }
        val patch = repoRoot.resolve(WORKTREE_DIR).resolve("$runId.patch")
        runCatching { Files.createDirectories(patch.parent) }
        runCatching { Files.writeString(patch, staged.stdout) }
        val apply = runGit(
            repoRoot,
            listOf("apply", "--3way", "--whitespace=nowarn", "--allow-empty", patch.toString()),
        )
        runCatching { Files.deleteIfExists(patch) }
        if (apply.exitCode != 0) {
            return@withContext Outcome(
                false, changed,
                "Merge into the main work tree was rejected (conflict with local changes): " +
                    apply.output.takeLast(400) + " — the isolated worktree is kept at $isolatedRoot"
            )
        }
        cleanupWorktree()
        logger.info("Workspace isolation merged {} file(s): {}", changed.size, message)
        Outcome(true, changed, "Merged ${changed.size} changed file(s) back into $repoRoot")
    }

    /**
     * Discard the isolated worktree. The main work tree was never written to, so
     * this is the only rollback step needed (plan P2.1 acceptance).
     */
    suspend fun rollback(): Outcome = withContext(Dispatchers.IO) {
        val changed = changedFiles()
        cleanupWorktree()
        Outcome(true, changed, "Discarded the isolated worktree ($isolatedRoot); the main work tree is untouched")
    }

    private fun cleanupWorktree() {
        runGit(repoRoot, listOf("worktree", "remove", "--force", isolatedRoot.toString()))
        runGit(repoRoot, listOf("worktree", "prune"))
        runCatching { Files.deleteIfExists(repoRoot.resolve(WORKTREE_DIR).resolve("$runId.patch")) }
    }

    /** Result of a merge or rollback. */
    data class Outcome(
        val ok: Boolean,
        val changedFiles: List<String>,
        val message: String,
    )
}
