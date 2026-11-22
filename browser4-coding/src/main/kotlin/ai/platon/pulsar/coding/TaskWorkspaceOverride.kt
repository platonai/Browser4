package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * The directory a task runs in, named by the caller — `agent run --workspace <dir>` on the
 * CLI, `command.run(workspace = "<dir>")` on the backend.
 *
 * ## Semantics: in place
 *
 * The named directory **is** the task workspace. The agent reads and writes there directly,
 * and nothing is copied or isolated first:
 *
 * - without `--coding` it simply replaces the per-task `<agent data>/workspace.<task-id>`
 *   (or `--shared-workspace`) directory;
 * - with `--coding` the task still runs *in place*: no git worktree, no worktree merge.
 *   `--coding` keeps its meaning (no browser page, coding tool set), but a coding task in a
 *   caller-named tree has no task-owned copy to merge back from, and the tree is exactly the
 *   one the caller asked to be edited. This is also the only semantics that works for a
 *   non-git directory (a data corpus), which is a legitimate `--coding` target.
 *
 * The backend's own coding checkout is the one directory refused for `--coding`: running a
 * task in place *there* would bypass the self-development worktree + verify-gated merge, so
 * the caller has to choose between the checked-out isolation (drop `--workspace`) and the
 * operator-level opt-out (`-Dbrowser4.agent.workspace.isolation=off`).
 *
 * ## Why validate here
 *
 * The CLI resolves its value against the shell directory it was typed in, expands a leading
 * `~` and only forwards the resulting absolute path (the backend runs in the runtime bundle,
 * so a relative path would name a different directory there). A raw API caller can still send
 * anything, so this is the single place that decides what a valid workspace is — and it fails
 * loudly instead of starting a task in a directory nobody named:
 *
 * - the path must be absolute;
 * - it must be an existing **directory**: an override is never created implicitly, because a
 *   typo would then silently become an empty tree the run writes into (the same rule
 *   [AdditionalDirs] applies to `--add-dir`);
 * - the OS credential stores ([ReadablePaths.ALWAYS_REFUSED]) are refused, lexically and
 *   through a symlink/junction, because a workspace "that happens to be" `~/.ssh` must not
 *   become the run's writable root;
 * - for a coding task, the backend's coding checkout is refused (see above).
 *
 * The directory itself is **not** cleaned, archived or swept: unlike the per-task workspaces,
 * it belongs to the caller, so retention never touches it. A task that reuses a directory
 * therefore starts from whatever the previous run (or the user) left there — which is the
 * point of naming it.
 */
object TaskWorkspaceOverride {

    /**
     * Resolves one `--workspace` value.
     *
     * @param raw the caller's value, already shell-resolved by the CLI or as sent by an API caller.
     * @param coding whether this is a `--coding` task: the backend coding checkout is then refused,
     *   because an in-place run there would bypass the isolated worktree and its merge.
     * @return the absolute, normalized directory the task must run in.
     * @throws IllegalArgumentException with an actionable message when [raw] is blank, not
     *   absolute, not an existing directory, a credential location, or the coding checkout of a
     *   coding task.
     */
    fun resolve(raw: String, coding: Boolean = false): Path {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) {
            "The --workspace directory must be non-blank"
        }
        // A native command never sees the shell's tilde expansion when the value is quoted
        // (`--workspace "~/corpus"`), so expand it here exactly like `--add-dir` does.
        val expanded = AdditionalDirs.expandHome(trimmed)
        val path = runCatching { Path.of(expanded) }.getOrElse {
            throw IllegalArgumentException("--workspace '$raw' is not a valid path on this machine: ${it.message}")
        }
        require(path.isAbsolute) {
            "--workspace '$raw' must be an absolute path. The CLI resolves it against the directory " +
                "it was typed in; a caller sending the raw argument has to do the same (~ is expanded " +
                "on the backend, a relative path is not)"
        }
        val normalized = path.toAbsolutePath().normalize()
        // Credentials are checked before existence so a credential location reports the real
        // reason ("stays refused") instead of the misleading "does not exist". The real path is
        // checked too: a link to `~/.ssh` passes the lexical test.
        require(!refusesCredentials(normalized)) {
            "--workspace '$raw' names an OS credential store, which stays refused whatever the task " +
                "asks for (${ReadablePaths.ALWAYS_REFUSED.joinToString(", ")})"
        }
        require(Files.isDirectory(normalized)) {
            "--workspace '$raw' is not an existing directory on the backend machine ($normalized). " +
                "Create the folder first — a workspace is never created implicitly, because a typo " +
                "would then become an empty directory the run writes into."
        }
        if (coding) refuseCodingCheckout(normalized, raw)
        return normalized
    }

    /** Whether [candidate] (or the location it links to) is an OS credential store. */
    private fun refusesCredentials(candidate: Path): Boolean {
        if (ReadablePaths.isAlwaysRefused(candidate)) return true
        val real = runCatching { candidate.toRealPath() }.getOrNull() ?: return false
        return ReadablePaths.isAlwaysRefused(real)
    }

    /**
     * Refuse `--workspace` combined with a flag that already names the task's workspace.
     *
     * Written as an explicit refusal (not "one of them wins"): silently ignoring `--workspace`
     * would run the task somewhere the caller did not name, and silently ignoring `--resume` would
     * throw away the checkpoint — its files and instruction — the caller asked to continue from.
     *
     * Called at submission (so the caller sees the error immediately) and again by the task runner
     * (so a direct runner call cannot bypass it).
     *
     * @param explicitWorkspace the resolved `--workspace` directory, or null when the flag was absent
     * @param resumeTaskId the raw `--resume` value, or null
     * @param sharedWorkspace the raw `--shared-workspace` value, or null
     * @throws IllegalArgumentException naming the conflicting pair and the fix
     */
    fun requireCompatibleWorkspaceFlags(
        explicitWorkspace: Path?,
        resumeTaskId: String?,
        sharedWorkspace: Boolean?,
    ) {
        if (explicitWorkspace == null) return
        require(resumeTaskId.isNullOrBlank()) {
            "--workspace cannot be combined with --resume: a resume continues the interrupted task in " +
                "its own workspace, so the directory you named would be ignored. Drop --workspace to " +
                "continue that task, or drop --resume to start a new task in this directory."
        }
        require(sharedWorkspace != true) {
            "--workspace cannot be combined with --shared-workspace: both name the task's workspace. " +
                "Keep --workspace <dir> to use your directory, or --shared-workspace for the agent's " +
                "shared one."
        }
    }

    /**
     * Refuse the backend's own coding checkout for an in-place coding task.
     *
     * Writing the checkout directly is what `-Dbrowser4.agent.workspace.isolation=off` already
     * means; letting `--workspace <checkout>` do it silently would make the worktree, the
     * verify-gated merge and their rollback disappear from a run that never said it opted out.
     */
    private fun refuseCodingCheckout(directory: Path, raw: String) {
        val checkout = runCatching { CodingWorkspace.workspaceRoot }.getOrNull() ?: return
        if (!isSameDirectory(directory, checkout)) return
        throw IllegalArgumentException(
            "--workspace '$raw' is the backend's coding checkout, and this task is a coding task: " +
                "an in-place run there would bypass the isolated worktree and the verify-gated " +
                "merge. Drop --workspace (the default gives the task an isolated worktree of that " +
                "checkout), or, if editing the checkout in place is really intended, have the " +
                "operator set -Dbrowser4.agent.workspace.isolation=off and drop --workspace."
        )
    }

    /** Path equality that treats a symlink and its target as the same directory. */
    private fun isSameDirectory(a: Path, b: Path): Boolean {
        val left = runCatching { a.toRealPath() }.getOrElse { a.toAbsolutePath().normalize() }
        val right = runCatching { b.toRealPath() }.getOrElse { b.toAbsolutePath().normalize() }
        return left == right
    }

    /** One-line description for logs and the permission report; `default` when no override was given. */
    fun describe(directory: Path?): String = directory?.toString() ?: "default"
}
