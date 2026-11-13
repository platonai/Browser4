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
 * One transactional workspace for a coding task: a git worktree of the user's
 * checkout, a patch-based merge back, and a reference to undo that merge.
 *
 * A task that edits the repository leaves the developer's working tree dirty
 * even when it fails half-way: a compile error at step 6 does not undo the files
 * written at steps 2–5. This class runs the task in a **git worktree** instead —
 * a checkout of a baseline commit in a managed directory — so:
 *
 * - every write lands in the isolated tree,
 * - success applies the change to the main work tree as a patch,
 * - failure removes the worktree (or keeps it for a resume), leaving the main
 *   tree byte-identical.
 *
 * The worktree lives under `<repo>/.browser4/worktrees/<runId>/` (git-ignored).
 *
 * ## Baseline and merge (plan S1)
 *
 * The worktree is created from a **baseline commit**, not blindly from `HEAD`:
 * when the user's tree is dirty, `git stash create` captures the tracked
 * modifications as a commit (without touching the working tree or the stash
 * list), and the worktree is checked out from it. The diff at merge time is
 * therefore "what the task changed relative to what the user already had", not
 * "relative to the last commit".
 *
 * Merge applies a `git diff --binary` patch to the main work tree, records
 * `refs/b4/pre/<taskId>` (pre-merge `HEAD`) and keeps the patch at
 * `.browser4/patches/<taskId>.patch`, so `b4w recover` can reverse exactly one
 * task. Merge never commits.
 *
 * Selected by `browser4.agent.workspace.isolation` (default `worktree`); `off`
 * restores in-place execution (the pre-2026-11 behaviour, kept as a rollback).
 */
class WorkspaceIsolation private constructor(
    /** The main work tree the task must not touch. */
    val repoRoot: Path,
    /** The isolated worktree every write of the task goes to. */
    val isolatedRoot: Path,
    private val runId: String,
    /** The commit the worktree was checked out from (dirty trees included). */
    baselineCommit: String = "",
    /** `refs/b4/baseline/<runId>` when this process created it. */
    baselineRef: String? = null,
) {
    /** The commit the worktree was checked out from (dirty trees included). */
    var baselineCommit: String = baselineCommit
        private set

    /** `refs/b4/baseline/<runId>` when this process created it. */
    var baselineRef: String? = baselineRef
        private set

    companion object {
        private val logger = LoggerFactory.getLogger(WorkspaceIsolation::class.java)

        /** Directory under the repo root that holds managed worktrees (git-ignored). */
        const val WORKTREE_DIR = ".browser4/worktrees"

        /** Patches applied to the main tree, kept for `b4w recover`. */
        const val PATCH_DIR = ".browser4/patches"

        /** Refs that make "undo this task" and "these are my changes" durable. */
        const val REF_PREFIX = "refs/b4"

        /**
         * Pathspecs excluded from every changed-file / patch computation.
         *
         * Agent bookkeeping must never be treated as a deliverable: `.b4scratch`
         * holds helper scripts, `.b4inputs` holds copies of the user's inputs and
         * `.browser4` holds snapshots/manifests. `.browser4` and `target/` are
         * git-ignored too; stating the exclusions here keeps the merge correct even
         * if the ignore file changes.
         */
        private val EXCLUDES = listOf(
            ":(exclude).b4scratch/**",
            ":(exclude)**/.b4scratch/**",
            ":(exclude).b4inputs/**",
            ":(exclude)**/.b4inputs/**",
            ":(exclude).browser4/**",
            ":(exclude)**/.browser4/**",
        )

        /**
         * Create an isolated worktree of [candidateRoot]'s repository.
         *
         * @throws IllegalStateException when the path is not a git work tree, when
         *   it is already a linked worktree (nesting would silently redirect every
         *   write to another task's tree), or when `git worktree add` fails —
         *   isolation must never silently degrade to "no isolation"; the caller
         *   decides what to do instead.
         */
        suspend fun begin(candidateRoot: Path, runId: String): WorkspaceIsolation {
            val root = resolveRepoRoot(candidateRoot)
                ?: throw IllegalStateException(
                    "Workspace isolation requires a git work tree; '$candidateRoot' is not inside one"
                )
            requireNotLinkedWorktree(root)
            val isolated = root.resolve(WORKTREE_DIR).resolve(sanitize(runId))
            val baseline = withContext(Dispatchers.IO) { captureBaseline(root, runId) }
            withContext(Dispatchers.IO) { Files.createDirectories(isolated.parent) }
            val add = runGit(
                root,
                listOf("worktree", "add", "--detach", "--force", isolated.toString(), baseline.commit),
            )
            if (add.exitCode != 0) {
                // Retry once after pruning stale metadata: a crashed run leaves
                // an entry that blocks the same path.
                runGit(root, listOf("worktree", "prune"))
                val retry = runGit(
                    root,
                    listOf("worktree", "add", "--detach", "--force", isolated.toString(), baseline.commit),
                )
                if (retry.exitCode != 0) {
                    throw IllegalStateException(
                        "git worktree add failed (exit ${retry.exitCode}): ${retry.output.takeLast(500)}"
                    )
                }
            }
            logger.info(
                "Workspace isolation started: {} -> {} (baseline {}{})",
                root, isolated, baseline.commit.take(12), if (baseline.fromDirtyTree) ", dirty tree" else "",
            )
            return WorkspaceIsolation(root, isolated, runId, baseline.commit, baseline.ref)
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
            val baseline = runGit(root, listOf("rev-parse", "HEAD")).stdout.trim()
            return WorkspaceIsolation(mainRoot, root, runId, baseline, null)
        }

        /**
         * The MAIN work tree of a linked worktree: `--git-common-dir` points at
         * `<main>/.git`, whose parent is the main work tree.
         *
         * Public so tooling outside this module (`coding.permissions`, the result
         * envelope) can say where an isolated change will land.
         */
        fun resolveMainRepoRoot(worktree: Path): Path? {
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

        /** `refs/b4/pre/<runId>` — the main-tree `HEAD` captured before a merge. */
        fun preRef(runId: String): String = "$REF_PREFIX/pre/${sanitize(runId)}"

        /** `refs/b4/baseline/<runId>` — the commit a worktree was checked out from. */
        fun baselineRef(runId: String): String = "$REF_PREFIX/baseline/${sanitize(runId)}"

        /**
         * Remove a managed worktree through git.
         *
         * A plain recursive delete would leave `.git/worktrees/<id>` behind, making
         * `git worktree list` and the next `worktree add` for that path misbehave.
         * Refuses paths outside `<repo>/.browser4/worktrees`.
         *
         * @return true when git removed the worktree (metadata pruned as well)
         */
        fun removeWorktree(repoRoot: Path, worktree: Path): Boolean {
            val root = repoRoot.toAbsolutePath().normalize()
            val managed = root.resolve(WORKTREE_DIR)
            val target = worktree.toAbsolutePath().normalize()
            if (!target.startsWith(managed)) {
                logger.warn("refusing to remove a worktree outside {}: {}", managed, target)
                return false
            }
            val remove = runGit(root, listOf("worktree", "remove", "--force", target.toString()))
            if (remove.exitCode != 0) {
                logger.warn("could not remove worktree {}: {}", target, remove.output.takeLast(300))
                return false
            }
            runGit(root, listOf("worktree", "prune"))
            return true
        }

        /**
         * Reject a linked worktree as isolation root.
         *
         * Running `git worktree add` from inside a worktree would nest one managed
         * tree inside another: every write lands in the *first* task's tree, and a
         * merge would apply to the wrong checkout. Fail loudly instead.
         */
        private fun requireNotLinkedWorktree(root: Path) {
            val gitDir = runGit(root, listOf("rev-parse", "--absolute-git-dir"))
            val commonDir = runGit(root, listOf("rev-parse", "--git-common-dir"))
            if (gitDir.exitCode != 0 || commonDir.exitCode != 0) return
            val git = gitDir.stdout.trim()
            val common = runCatching { root.resolve(commonDir.stdout.trim()).normalize() }.getOrNull()
                ?: return
            if (git.isNotEmpty() && !samePath(Path.of(git), common)) {
                throw IllegalStateException(
                    "Workspace isolation cannot start inside a linked worktree ($root): " +
                        "the isolation root must be the main checkout (${common.parent})"
                )
            }
        }

        /**
         * Capture the commit a worktree starts from.
         *
         * Clean tree → `HEAD`. Dirty tree → `git stash create`, which records the
         * tracked modifications in a commit **without** touching the working tree
         * or the stash list; untracked files are not included (documented limit).
         * The commit is pinned under `refs/b4/baseline/<runId>` so gc cannot drop it
         * while the task runs.
         */
        private fun captureBaseline(root: Path, runId: String): Baseline {
            val head = runGit(root, listOf("rev-parse", "HEAD")).stdout.trim()
            // Untracked files cannot be captured by `git stash create`, so only tracked
            // modifications make the tree "dirty" here; a tree with only untracked files
            // still starts from HEAD (they are simply not part of the worktree).
            val dirty = runGit(root, listOf("status", "--porcelain", "--untracked-files=no", "--", "."))
                .stdout.isNotBlank()
            val captured = if (dirty) runGit(root, listOf("stash", "create")).stdout.trim() else ""
            val commit = captured.takeIf { it.isNotEmpty() } ?: head
            if (commit.isEmpty()) throw IllegalStateException("cannot determine a baseline commit in $root")
            val ref = baselineRef(runId)
            val update = runGit(root, listOf("update-ref", ref, commit))
            if (update.exitCode != 0 && update.output.isNotBlank()) {
                logger.debug("could not pin baseline ref {}: {}", ref, update.output.trim())
            }
            return Baseline(commit, ref, dirty)
        }

        private fun samePath(a: Path, b: Path): Boolean {
            val win = System.getProperty("os.name").lowercase().contains("win")
            return if (win) a.toString().equals(b.toString(), ignoreCase = true) else a == b
        }

        private data class Baseline(val commit: String, val ref: String?, val fromDirtyTree: Boolean)
    }

    /** Paths (repo-relative) the task changed inside the isolated worktree. */
    suspend fun changedFiles(): List<String> = withContext(Dispatchers.IO) {
        val status = runGit(isolatedRoot, listOf("status", "--porcelain", "--", ".") + EXCLUDES)
        if (status.exitCode != 0) return@withContext emptyList()
        status.stdout.lineSequence().mapNotNull { line ->
            if (line.length < 4) null else line.substring(3).trim().trim('"').replace('\\', '/')
        }.filter { it.isNotEmpty() }.toList()
    }

    /**
     * Move a reused worktree to the current baseline of the main checkout
     * (plan S4-3).
     *
     * Resets tracked files to a freshly captured baseline and removes untracked
     * files, so the next task starts from exactly what the checkout looks like now.
     * Ignored build output (`target/`) survives — that is the point of reuse.
     *
     * Only call this when the previous task's change was already merged: the reset
     * discards whatever the worktree still holds.
     *
     * @return false when the reset failed (the caller must start a new worktree)
     */
    suspend fun rebase(): Boolean = withContext(Dispatchers.IO) {
        val baseline = captureBaseline(repoRoot, runId)
        val reset = runGit(isolatedRoot, listOf("reset", "--hard", baseline.commit))
        if (reset.exitCode != 0) {
            logger.warn(
                "could not rebase the reused worktree {}: {}",
                isolatedRoot, reset.output.takeLast(300),
            )
            return@withContext false
        }
        runGit(isolatedRoot, listOf("clean", "-fd"))
        baselineCommit = baseline.commit
        baselineRef = baseline.ref
        logger.info("reused worktree {} reset to baseline {}", isolatedRoot, baseline.commit.take(12))
        true
    }

    /**
     * Apply the isolated change to the main work tree (plan S1 merge protocol).
     *
     * Steps, all-or-nothing for the main tree:
     * 1. stage the isolated tree (agent bookkeeping excluded),
     * 2. produce `git diff --cached --binary <baseline>`,
     * 3. keep it at `<repo>/.browser4/patches/<taskId>.patch` and record
     *    `refs/b4/pre/<taskId>`,
     * 4. `git apply --check` then `git apply` — `--3way` first (it survives an
     *    unrelated dirty tree), plain context application as the fallback (a
     *    Windows line-ending mismatch makes `--3way` refuse unrelated files).
     *
     * The worktree is **kept** by default: the caller points `agent result` at the
     * main repository, and the worktree is what a resume or a review inspects.
     *
     * @param taskId task identity used for the patch name and the pre-merge ref
     * @param keepWorktree false merges like the legacy `commit()` and removes the tree
     */
    suspend fun applyToMain(taskId: String, keepWorktree: Boolean = true): Outcome = withContext(Dispatchers.IO) {
        val changed = changedFiles()
        if (changed.isEmpty()) {
            if (!keepWorktree) cleanupWorktree()
            return@withContext Outcome(true, emptyList(), "No changes to merge (isolated tree unchanged)")
        }
        val add = runGit(isolatedRoot, listOf("add", "-A", "--", ".") + EXCLUDES)
        if (add.exitCode != 0) {
            return@withContext Outcome(false, changed, "git add failed: ${add.output.takeLast(400)}")
        }
        val staged = runGit(
            isolatedRoot,
            listOf("diff", "--cached", "--binary").let { base ->
                if (baselineCommit.isNotBlank()) base + baselineCommit else base
            },
        )
        if (staged.exitCode != 0 || staged.stdout.isBlank()) {
            return@withContext Outcome(false, changed, "Could not produce the isolated patch: ${staged.output.takeLast(400)}")
        }
        val patch = repoRoot.resolve(PATCH_DIR).resolve("${sanitize(taskId)}.patch")
        runCatching { Files.createDirectories(patch.parent) }
        runCatching { Files.writeString(patch, staged.stdout) }
        val preRef = preRef(taskId)
        val head = runGit(repoRoot, listOf("rev-parse", "HEAD")).stdout.trim()
        if (head.isNotEmpty()) runGit(repoRoot, listOf("update-ref", preRef, head))

        // Idempotency: a resumed task whose patch already reached the main tree (the
        // previous attempt merged, then crashed before the manifest was written) must
        // report "applied", not "refused". The reverse check is the cheapest proof.
        if (runGit(repoRoot, listOf("apply", "--check", "-R", patch.toString())).exitCode == 0) {
            logger.info("Workspace isolation: the patch for {} is already applied to {}", taskId, repoRoot)
            return@withContext Outcome(
                true,
                changed,
                "Already merged into $repoRoot (patch: $patch)",
                patch.toString(),
                preRef,
                keepWorktree,
            )
        }

        // Plain application first: it uses the working tree's context and, unlike
        // `--3way`, never touches the index — the change must land **unstaged** so the
        // user reviews it with `git diff`. `--3way` is the fallback for a path the
        // user edited after the baseline; when it is used the patched paths are
        // unstaged again afterwards.
        val applyMode = when {
            runGit(repoRoot, listOf("apply", "--check", patch.toString())).exitCode == 0 -> "plain"
            runGit(repoRoot, listOf("apply", "--check", "--3way", patch.toString())).exitCode == 0 -> "--3way"
            else -> ""
        }
        if (applyMode.isEmpty()) {
            return@withContext Outcome(
                false,
                changed,
                "Merge into the main work tree was rejected (the checkout changed while the task ran). " +
                    "Patch kept at $patch; the isolated worktree is kept at $isolatedRoot",
                patch.toString(),
                preRef,
            )
        }
        val applyArgs = if (applyMode == "--3way") {
            listOf("apply", "--3way", "--whitespace=nowarn", patch.toString())
        } else {
            listOf("apply", "--whitespace=nowarn", patch.toString())
        }
        val apply = runGit(repoRoot, applyArgs)
        if (apply.exitCode != 0) {
            return@withContext Outcome(
                false,
                changed,
                "Merge into the main work tree failed ($applyMode): ${apply.output.takeLast(400)} — " +
                    "patch kept at $patch; the isolated worktree is kept at $isolatedRoot",
                patch.toString(),
                preRef,
            )
        }
        if (applyMode == "--3way") {
            // `--3way` stages what it merges; restore the pre-merge index entries for
            // exactly the patched paths so the result is indistinguishable from a plain
            // apply (worktree change, index untouched).
            runGit(repoRoot, listOf("reset", "-q", "--") + changed)
        }
        if (!keepWorktree) cleanupWorktree()
        logger.info("Workspace isolation merged {} file(s) into {} via {}", changed.size, repoRoot, applyMode)
        Outcome(
            true,
            changed,
            "Merged ${changed.size} changed file(s) into $repoRoot ($applyMode; patch: $patch)",
            patch.toString(),
            preRef,
            keepWorktree,
        )
    }

    /**
     * Merge the isolated change into the main work tree and remove the worktree.
     *
     * Legacy `coding.devTask` behaviour: success merges and cleans up, failure
     * keeps the tree for inspection.
     */
    suspend fun commit(message: String = "b4 isolated task"): Outcome =
        applyToMain(runId, keepWorktree = false)

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
        // The patch is deliberately NOT deleted: it is the revert handle for
        // `b4w recover --revert <taskId>`.
    }

    /** Result of a merge or rollback. */
    data class Outcome(
        val ok: Boolean,
        val changedFiles: List<String>,
        val message: String,
        /** Applied patch, kept for `b4w recover --revert`. */
        val patchPath: String? = null,
        /** `refs/b4/pre/<taskId>` recorded before the merge. */
        val preRef: String? = null,
        /** Whether the isolated worktree was left in place. */
        val worktreeKept: Boolean = false,
    )
}
