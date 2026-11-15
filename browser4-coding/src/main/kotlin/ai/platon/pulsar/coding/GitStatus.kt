package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * One answer to "which files did this task change", shared by the coding and agent surfaces.
 *
 * Every consumer used to run `git status --porcelain` itself and re-implement the parsing, and
 * the line-based form is a trap:
 *
 * - it **quotes** any path that is not plain ASCII — `"na\303\257ve.md"` — so the naive
 *   `trim('"')` yields a name that matches no file on disk (no fingerprint, no module, no
 *   `git reset -- <path>` target);
 * - a rename is `R  old -> new`, so a parser that takes `substring(3)` reports a string with an
 *   arrow in it, and one that takes `substringAfter(' ')` reports the *old* path.
 *
 * `--porcelain=v1 -z` has neither problem: paths are verbatim, NUL-terminated, and a
 * rename/copy carries **two** records with the new path first (the original is skipped — it no
 * longer exists). All consumers of git status go through this object so "which files changed"
 * has exactly one answer.
 *
 * `runGit` drains both pipes; a wide change set is otherwise larger than the OS pipe buffer and
 * the command deadlocks until its timeout.
 */
object GitStatus {

    /** One `git status` entry: the two state characters and the path (renames: the target). */
    data class Entry(val staged: Char, val unstaged: Char, val path: String) {
        /** Deleted in the index or in the work tree (`D` in either column). */
        val deleted: Boolean get() = staged == 'D' || unstaged == 'D'
    }

    /**
     * The entries of [repo], or null when it is not a git work tree / git is unavailable.
     *
     * @param untracked include untracked files
     * @param excludes pathspecs to exclude (e.g.
     *   [WorkspaceIsolation]'s agent-bookkeeping rules)
     */
    fun entries(
        repo: Path,
        untracked: Boolean = true,
        excludes: List<String> = emptyList(),
    ): List<Entry>? {
        if (!Files.isDirectory(repo)) return null
        val args = mutableListOf("status", "--porcelain=v1", "-z")
        // `all` (not the default `normal`): git otherwise collapses a new directory into one
        // `dir/` entry, and the individual files of a task that only ADDED files would be
        // missing from the change set, its fingerprint and its module mapping.
        args += if (untracked) "--untracked-files=all" else "--untracked-files=no"
        args += "--"
        args += "."
        args += excludes
        val outcome = runGit(repo, args)
        if (outcome.exitCode != 0) return null
        return parse(outcome.stdout)
    }

    /** Repository-relative changed paths (a rename is reported by its target), or null. */
    fun changedFiles(
        repo: Path,
        untracked: Boolean = true,
        excludes: List<String> = emptyList(),
    ): List<String>? = entries(repo, untracked, excludes)?.map { it.path }

    /** Repository-relative deleted paths, or null when git is unavailable. */
    fun deletedFiles(repo: Path, untracked: Boolean = true): List<String>? =
        entries(repo, untracked)?.filter { it.deleted }?.map { it.path }

    /** Parse `git status --porcelain=v1 -z` output. */
    internal fun parse(output: String): List<Entry> {
        val records = output.split('\u0000')
        val entries = mutableListOf<Entry>()
        var i = 0
        while (i < records.size) {
            val record = records[i]
            i++
            // "XY <path>" is the shortest entry (4 characters for a one-character path).
            if (record.length < 4) continue
            val path = record.substring(3).replace('\\', '/')
            if (path.isEmpty()) continue
            // An untracked directory is reported as `dir/`. It is not a file, cannot be
            // fingerprinted or resolved, and it appears even when a pathspec excluded all of
            // its contents — keep the change set to actual paths.
            if (path.endsWith("/")) continue
            val staged = record[0]
            val unstaged = record[1]
            entries += Entry(staged, unstaged, path)
            if (staged == 'R' || staged == 'C' || unstaged == 'R' || unstaged == 'C') {
                i++ // the original path of a rename/copy follows, and no longer exists
            }
        }
        return entries
    }
}
