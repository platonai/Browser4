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
 * 1. keep the newest [keep] worktrees by modification time, always;
 * 2. keep anything younger than [maxAgeDays] (so a reviewer is never surprised);
 * 3. never touch a path outside `<repo>/.browser4/worktrees`;
 * 4. remove through `git worktree remove --force` (a plain directory delete would
 *    leave stale metadata in `.git/worktrees` and `git worktree list` broken);
 * 5. patches and the `refs/b4/...` refs are *not* deleted — they are kilobytes and remain
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
    ) {
        val changed: Boolean get() = removed.isNotEmpty()
        fun summary(): String =
            "worktree gc: kept=${kept.size} removed=${removed.size} skipped=${skipped.size}" +
                if (errors.isEmpty()) "" else " errors=${errors.size}"
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
     * Sweep the managed worktrees.
     *
     * @param repoRoot the checkout the worktrees were created from
     * @param keep newest worktrees to always retain
     * @param maxAgeDays retain anything younger than this
     * @param dryRun report only, delete nothing
     * @param exclude the current task's worktree, if any (never swept)
     */
    fun sweep(
        repoRoot: Path,
        keep: Int = keepFromConfig(),
        maxAgeDays: Long = maxAgeDaysFromConfig(),
        dryRun: Boolean = isDryRun(),
        exclude: Path? = null,
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

        // Newest first: index < keep is retained before age is even considered.
        val ordered = entries
            .map { it.toAbsolutePath().normalize() to lastModified(it) }
            .sortedByDescending { it.second }
        val now = Instant.now()

        val kept = mutableListOf<Path>()
        val removed = mutableListOf<Path>()
        val skipped = mutableListOf<Path>()
        val errors = mutableListOf<String>()

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

        if (removed.isNotEmpty()) {
            logger.info(
                "worktree gc under {}: removed=[{}] kept={} skipped={}",
                managed,
                removed.joinToString(", ") { it.fileName.toString() },
                kept.size,
                skipped.size,
            )
        }
        return Report(kept, removed, skipped, errors)
    }

    private fun lastModified(path: Path): Instant =
        runCatching { Files.getLastModifiedTime(path).toInstant() }.getOrDefault(Instant.EPOCH)
}
