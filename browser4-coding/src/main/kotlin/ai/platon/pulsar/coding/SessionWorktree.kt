package ai.platon.pulsar.coding

import java.nio.file.Path

/**
 * Session-scoped worktree reuse (isolation plan S4-3).
 *
 * Every coding task normally gets its own worktree. That is the safest default,
 * but a session that runs several planned tasks pays a full checkout per task.
 * With `browser4.agent.isolation.reuse=session` the tasks of one session share
 * `<repo>/.browser4/worktrees/session-<id>`, which is reset to the current
 * baseline between tasks.
 *
 * Reuse is only safe when the previous task **merged**: its changes are then in
 * the main checkout, so discarding the worktree copies loses nothing. A refused
 * or interrupted task keeps its tree untouched and the next task gets a fresh
 * one — the unmerged work must stay inspectable.
 */
object SessionWorktree {

    /** Directory name prefix shared by the retained tree and its baseline ref. */
    private const val PREFIX = "session-"

    /** The session's worktree under [repoRoot]. */
    fun dirFor(repoRoot: Path, sessionId: String): Path =
        repoRoot.resolve(WorkspaceIsolation.WORKTREE_DIR).resolve(runIdFor(sessionId))

    /** The run id used for the session worktree (also its directory name). */
    fun runIdFor(sessionId: String): String =
        PREFIX + WorkspaceIsolation.sanitize(sessionId).take(24)

    /**
     * Whether [manifest] describes a tree that can be reset and reused.
     *
     * Requires a finished task whose merge reached the main checkout. A missing
     * `merge` field means the task ran before isolation recorded it; that tree was
     * never merged by this design, so it is not reused.
     */
    fun isReusable(manifest: WorkspaceManifest?): Boolean {
        if (manifest == null) return false
        if (manifest.state != WorkspaceState.COMPLETE) return false
        if (manifest.currentTask != null) return false
        val last = manifest.lastTask ?: return false
        return last.status == TaskStatus.COMPLETE && last.merge == "applied"
    }
}
