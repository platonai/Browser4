package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * Central resolution of the coding workspace for Browser4's self-coding
 * capability (the `coding_*` tools and the CLI `code` command family).
 *
 * The workspace root defaults to the backend JVM working directory
 * (`user.dir`), which is the runtime bundle directory when the server is
 * launched by the CLI daemon — usually NOT where the user's code lives.
 * Point it at a real repository with:
 *
 * ```
 * -Dbrowser4.agent.workspace=<path>                              (JVM system property)
 * BROWSER4_SERVER_OPTS=-Dbrowser4.agent.workspace=<path> ...     (CLI daemon env var)
 * ```
 *
 * When no explicit workspace is configured and the JVM working directory
 * (or any of its parents) sits inside a Browser4 source checkout — a
 * directory containing both `ROOT.md` and `pom.xml`, the same marker the
 * CLI daemon uses — the repository root is used automatically.  This makes
 * the `coding_*` tools (and the `browser4 code` CLI family) operate on the
 * user's code instead of the runtime bundle directory, so repo-relative
 * paths like `VERSION`, `browser4-plugins/...`, and Maven module paths
 * resolve correctly during self-development.
 *
 * Related switches:
 * - `browser4.agent.allowExternalAccess=true` — permit absolute paths
 *   outside the workspace root (off by default).
 * - `browser4.agent.allowDestructive=false` — deny rm/del/mv/cp/kill
 *   style operations (on by default).
 */
object CodingWorkspace {

    /**
     * The workspace root for all coding file operations.
     *
     * Computed on every access (not cached) so tests and runtime
     * reconfiguration can switch the property.
     */
    val workspaceRoot: Path
        get() {
            val configured = System.getProperty("browser4.agent.workspace")
            if (!configured.isNullOrBlank()) {
                return Path.of(configured).toAbsolutePath().normalize()
            }
            val userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            return findRepoRootFrom(userDir) ?: userDir
        }

    /**
     * Walk up from [start] looking for a Browser4 repository root — a
     * directory containing both `ROOT.md` and `pom.xml` (the same marker
     * the CLI daemon's `find_browser4_root` uses).  Returns `null` when no
     * repository root is found (e.g. a globally installed runtime).
     */
    fun findRepoRootFrom(start: Path): Path? {
        var current: Path? = start
        while (current != null) {
            if (Files.isRegularFile(current.resolve("ROOT.md")) &&
                Files.isRegularFile(current.resolve("pom.xml"))
            ) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /**
     * Whether absolute paths outside the workspace root are permitted.
     *
     * Deprecated entry point: this is now a read-only projection of
     * [AgentAccessPolicy.serverDefault], which is the single source of truth. Prefer
     * `browser4.agent.access=<preset>` over the legacy property.
     */
    @Deprecated("Use AgentAccessPolicy.serverDefault.allowExternalAccess")
    val allowExternalAccess: Boolean
        get() = AgentAccessPolicy.serverDefault.allowExternalAccess

    /**
     * Whether destructive file operations (delete/move/copy) are permitted.
     *
     * Deprecated entry point: this is now a read-only projection of
     * [AgentAccessPolicy.serverDefault]. Note this switch is **not** a read-only mode.
     */
    @Deprecated("Use AgentAccessPolicy.serverDefault.allowDestructive")
    val allowDestructive: Boolean
        get() = AgentAccessPolicy.serverDefault.allowDestructive

    /**
     * Transactional workspace mode: `worktree` (default) runs a coding task in an
     * isolated git worktree and merges the change back on success; `off` runs it
     * directly in the workspace (the pre-2026-11 behaviour, kept as a rollback).
     *
     * The default changed with the self-development isolation plan
     * (`docs-dev/copilot/20261113-self-dev-worktree-isolation-plan.md` §D1): a
     * worktree makes every write disposable, restores `git` as the audit surface
     * and stops a failed task from leaving the user's checkout dirty. Non-git
     * workspaces are unaffected — the caller falls back to running in place.
     */
    val isolationMode: String
        get() = System.getProperty("browser4.agent.workspace.isolation", "worktree").trim().lowercase()

    /** Whether [isolationMode] asks for an isolated worktree. */
    fun isIsolationEnabled(): Boolean = isolationMode in setOf("worktree", "on", "true", "isolated")

    /**
     * Worktree reuse scope: `task` (default) gives every task its own checkout;
     * `session` makes the tasks of one agent session share
     * `<repo>/.browser4/worktrees/session-<id>`, reset to the current baseline
     * between tasks (plan S4-3).
     *
     * Reuse is only taken when the previous task **merged**; a refused or
     * interrupted task keeps its tree and the next task gets a fresh one.
     */
    val isolationReuse: String
        get() = System.getProperty("browser4.agent.isolation.reuse", "task").trim().lowercase()

    /** Whether [isolationReuse] asks for a session-scoped worktree. */
    fun isSessionReuseEnabled(): Boolean = isolationReuse in setOf("session", "on", "true", "reuse")
}
