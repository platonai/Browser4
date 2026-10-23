package ai.platon.pulsar.coding

import java.time.Instant

/**
 * The lifecycle state of a [WorkspaceManifest].
 *
 * - [ACTIVE]: a task is currently running in this workspace.
 * - terminal states ([COMPLETE], [CANCELLED], [FAILED]): the last task ended and
 *   the workspace is idle.
 * - [STALE]: marked by the retention sweeper when it found an [ACTIVE] manifest
 *   without a live lock (process crash). The workspace is kept for one GC cycle
 *   and then becomes eligible for deletion.
 */
enum class WorkspaceState {
    ACTIVE, COMPLETE, CANCELLED, FAILED, STALE,
    ;

    fun isTerminal(): Boolean = this == COMPLETE || this == CANCELLED || this == FAILED
}

/** How a task ended. Mirrors [ai.platon.pulsar.agentic.agents.task.AgentTaskStatus]
 *  statuses we care about for manifest bookkeeping; kept independent here so the
 *  coding module does not depend on the agentic module. */
enum class TaskStatus {
    COMPLETE, CANCELLED, FAILED,
    ;
}

/**
 * A snapshot of one task's run, stored in [WorkspaceManifest.currentTask] while
 * running and rotated to [WorkspaceManifest.lastTask] on completion.
 *
 * [filesChanged] is the deliverable set — the union of (Δgit from baseline) and
 * the mutation log's deliverable paths (see `WorkspaceTaskLifecycle`). It is
 * bounded to 200 entries; the archive step only takes the first 50.
 */
data class TaskSnapshot(
    val taskId: String,
    val instruction: String,
    val coding: Boolean,
    val startedAt: Instant,
    val status: TaskStatus? = null,
    val endedAt: Instant? = null,
    val filesChanged: List<String> = emptyList(),
    val mutations: Int = 0,
    val baselineCaptured: Boolean = false,
) {
    /** A compact one-liner for the inherited-workspace-state prompt block. */
    fun summaryLine(): String {
        val end = endedAt ?: Instant.now()
        return "$taskId ($status ${end})"
    }
}

/**
 * Identity and lifecycle record for one agent workspace.
 *
 * Stored at `<workspace>/.browser4/workspace.json`. The v0.3 schema (2) splits
 * the v0.1/v0.2 single `lastTask` field into `currentTask` + `lastTask` so that
 * the rotate on completion and the archive check on next start cannot race
 * (see `coding-run-isolation-design.md` §6.1 R13).
 */
data class WorkspaceManifest(
    val schema: Int = SCHEMA_VERSION,
    val workspaceId: String,
    val agentUuid: String,
    val engine: String,
    val sourceRepo: String? = null,
    val createdAt: Instant = Instant.now(),
    val state: WorkspaceState = WorkspaceState.COMPLETE,
    val currentTask: TaskSnapshot? = null,
    val lastTask: TaskSnapshot? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 2
        const val FILENAME = ".browser4/workspace.json"
        const val MAX_FILES_CHANGED = 200
        const val MAX_INSTRUCTION_LEN = 200
    }

    /** Whether the workspace has a terminal state and no running task — the
     *  retention sweeper uses this as the "safe to delete" precondition. */
    fun isIdleTerminal(): Boolean = state.isTerminal() && currentTask == null
}
