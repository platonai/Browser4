package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Reclaims isolated coding worktrees.
 *
 * Every `agent run --coding` creates a full checkout under
 * `<repo>/.browser4/worktrees/<taskId>` and deliberately keeps it after the run
 * (review, `--resume`, `b4w recover`). Without a sweeper that directory grows one
 * checkout per task — the same unbounded-growth problem the per-task workspace
 * model already had.
 *
 * Rules:
 * 1. keep the newest [keep] worktrees by last activity — the task ledger's end/start
 *    time, falling back to the directory mtime;
 * 2. keep anything younger than [maxAgeDays] (so a reviewer is never surprised);
 * 3. **never reclaim a tree that still holds the only copy of a change**: a tree
 *    whose manifest says the task did not merge (refused, failed, cancelled,
 *    crashed) — or that has no manifest to prove otherwise — is retained
 *    ([Report.unmerged] / [Report.unknown]). This is the rule that makes the
 *    "failure never loses work" promise hold: a refused merge keeps its files only
 *    here, and a run that never reached a merge attempt has no patch either. An
 *    operator who really wants the disk back passes [includeUnmerged] (`b4w recover
 *    --prune --force`);
 * 4. never touch a path outside `<repo>/.browser4/worktrees`;
 * 5. remove through `git worktree remove --force` (a plain directory delete would
 *    leave stale metadata in `.git/worktrees` and `git worktree list` broken);
 * 6. patches and the `refs/b4/...` refs are *not* deleted — they are kilobytes and remain
 *    valid revert handles after the worktree is gone.
 *
 * It runs at the start of a coding task, after the checkout lock is acquired, so
 * no other task can be using a worktree being swept.
 */
object WorktreeRetention {

    private val logger = LoggerFactory.getLogger(WorktreeRetention::class.java)

    const val KEEP_PROPERTY = "browser4.agent.isolation.keep"
    const val MAX_AGE_DAYS_PROPERTY = "browser4.agent.isolation.maxAgeDays"
    const val ENABLED_PROPERTY = "browser4.agent.isolation.gc"
    const val DRY_RUN_PROPERTY = "browser4.agent.isolation.gc.dryRun"

    /** Outcome of one sweep. */
    data class Report(
        val kept: List<Path> = emptyList(),
        val removed: List<Path> = emptyList(),
        val skipped: List<Path> = emptyList(),
        val errors: List<String> = emptyList(),
        /** Retained: the task's change never reached the main checkout (running,
         *  refused, failed, cancelled or crashed). */
        val unmerged: List<Path> = emptyList(),
        /** Retained: no manifest, so the tree cannot be proven disposable. */
        val unknown: List<Path> = emptyList(),
    ) {
        val changed: Boolean get() = removed.isNotEmpty()

        fun summary(): String = buildString {
            append("worktree gc: kept=${kept.size} removed=${removed.size} skipped=${skipped.size}")
            if (errors.isNotEmpty()) append(" errors=${errors.size}")
            if (unmerged.isNotEmpty()) append(" unmerged=${unmerged.size}")
            if (unknown.isNotEmpty()) append(" unknown=${unknown.size}")
        }
    }

    fun keepFromConfig(): Int =
        System.getProperty(KEEP_PROPERTY, "3").trim().toIntOrNull()?.coerceAtLeast(0) ?: 3

    fun maxAgeDaysFromConfig(): Long =
        System.getProperty(MAX_AGE_DAYS_PROPERTY, "7").trim().toLongOrNull()?.coerceAtLeast(0) ?: 7

    fun isEnabled(): Boolean =
        !System.getProperty(ENABLED_PROPERTY, "on").trim().equals("off", ignoreCase = true)

    fun isDryRun(): Boolean =
        System.getProperty(DRY_RUN_PROPERTY, "false").trim().equals("true", ignoreCase = true)

    /**
     * Whether [worktree]'s manifest proves the tree is disposable.
     *
     * `true` — a finished task whose change reached the main checkout (the same
     * predicate that makes a session worktree reusable), `false` — a tree whose task
     * has not fully merged (still running, refused, failed, cancelled, crashed), so it
     * may hold the only copy of that work, `null` — no readable manifest, so it cannot
     * be proven either way and is retained as well.
     */
    internal fun isDisposable(worktree: Path): Boolean? {
        val manifest = runCatching { WorkspaceManifestStore().read(worktree) }.getOrNull() ?: return null
        return SessionWorktree.isReusable(manifest)
    }

    /**
     * Sweep the managed worktrees.
     *
     * @param repoRoot the checkout the worktrees were created from
     * @param keep newest worktrees to always retain
     * @param maxAgeDays retain anything younger than this
     * @param dryRun report only, delete nothing
     * @param exclude the current task's worktree, if any (never swept)
     * @param includeUnmerged reclaim trees whose change never merged, and trees with
     *   no manifest. Off by default: those trees are the only copy of that work.
     */
    fun sweep(
        repoRoot: Path,
        keep: Int = keepFromConfig(),
        maxAgeDays: Long = maxAgeDaysFromConfig(),
        dryRun: Boolean = isDryRun(),
        exclude: Path? = null,
        includeUnmerged: Boolean = false,
    ): Report {
        val root = repoRoot.toAbsolutePath().normalize()
        val managed = root.resolve(WorkspaceIsolation.WORKTREE_DIR)
        if (!Files.isDirectory(managed)) return Report()

        val entries = runCatching {
            Files.list(managed).use { stream ->
                stream.filter { Files.isDirectory(it) }.toList()
            }
        }.getOrElse { e ->
            logger.warn("worktree gc: cannot list {}: {}", managed, e.message)
            return Report(errors = listOf("cannot list $managed: ${e.message}"))
        }
        val excluded = exclude?.toAbsolutePath()?.normalize()

        // Newest first: index < keep is retained before age is even considered. The task
        // ledger is the clock, not the directory mtime: a checkout's directory stops
        // changing as soon as it is created, so a long task that only edited existing
        // files would look older than an idle tree it is in fact newer than — and the
        // age rule below would evict it first.
        val ordered = entries
            .map { it.toAbsolutePath().normalize() to lastActivity(it) }
            .sortedByDescending { it.second }
        val now = Instant.now()

        val kept = mutableListOf<Path>()
        val removed = mutableListOf<Path>()
        val skipped = mutableListOf<Path>()
        val errors = mutableListOf<String>()
        val unmerged = mutableListOf<Path>()
        val unknown = mutableListOf<Path>()

        ordered.forEachIndexed { index, (path, modified) ->
            if (excluded != null && path == excluded) {
                skipped.add(path)
                return@forEachIndexed
            }
            if (index < keep) {
                kept.add(path)
                return@forEachIndexed
            }
            if (Duration.between(modified, now).toDays() < maxAgeDays) {
                kept.add(path)
                return@forEachIndexed
            }
            if (!includeUnmerged) {
                // The tree may be the only copy of a change that never reached the
                // checkout: keep it and say why, instead of reclaiming the disk at
                // the cost of the work.
                when (isDisposable(path)) {
                    true -> Unit
                    false -> {
                        unmerged.add(path)
                        return@forEachIndexed
                    }
                    null -> {
                        unknown.add(path)
                        return@forEachIndexed
                    }
                }
            }
            if (!path.startsWith(managed)) {
                // Defensive: a symlinked entry could resolve elsewhere.
                skipped.add(path)
                return@forEachIndexed
            }
            if (dryRun) {
                logger.info("[dry-run] worktree gc would remove {}", path)
                kept.add(path)
                return@forEachIndexed
            }
            if (WorkspaceIsolation.removeWorktree(root, path)) {
                removed.add(path)
            } else {
                errors.add("could not remove $path")
            }
        }

        if (removed.isNotEmpty() || unmerged.isNotEmpty() || unknown.isNotEmpty()) {
            logger.info(
                "worktree gc under {}: removed=[{}] kept={} skipped={} unmerged=[{}] unknown=[{}]",
                managed,
                removed.joinToString(", ") { it.fileName.toString() },
                kept.size,
                skipped.size,
                unmerged.joinToString(", ") { it.fileName.toString() },
                unknown.joinToString(", ") { it.fileName.toString() },
            )
        }
        return Report(kept, removed, skipped, errors, unmerged, unknown)
    }

    /**
     * When the tree was last worked in: the task's end (or its start, while it still
     * runs) from the manifest, falling back to the directory mtime for a tree without a
     * readable ledger.
     */
    private fun lastActivity(worktree: Path): Instant {
        val manifest = runCatching { WorkspaceManifestStore().read(worktree) }.getOrNull()
        val fromLedger = manifest?.let { m ->
            m.lastTask?.endedAt ?: m.lastTask?.startedAt ?: m.currentTask?.startedAt
        }
        return fromLedger ?: lastModified(worktree)
    }

    private fun lastModified(path: Path): Instant =
        runCatching { Files.getLastModifiedTime(path).toInstant() }.getOrDefault(Instant.EPOCH)
}
