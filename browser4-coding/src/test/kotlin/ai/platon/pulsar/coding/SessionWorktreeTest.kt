package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class SessionWorktreeTest {

    @TempDir
    lateinit var tempDir: Path

    private fun manifest(
        state: WorkspaceState = WorkspaceState.COMPLETE,
        currentTask: TaskSnapshot? = null,
        lastTask: TaskSnapshot? = null,
    ) = WorkspaceManifest(
        workspaceId = "ws",
        agentUuid = "agent",
        engine = "cli",
        state = state,
        currentTask = currentTask,
        lastTask = lastTask,
    )

    private fun task(
        status: TaskStatus? = TaskStatus.COMPLETE,
        merge: String? = "applied",
    ) = TaskSnapshot(
        taskId = "task-1",
        instruction = "do it",
        coding = true,
        startedAt = Instant.now(),
        status = status,
        endedAt = Instant.now(),
        merge = merge,
    )

    @Test
    @DisplayName("the session worktree lives under the managed directory and is namespaced")
    fun directoryLayout() {
        val dir = SessionWorktree.dirFor(tempDir, "a:b/c")
        assertEquals(
            tempDir.resolve(WorkspaceIsolation.WORKTREE_DIR).resolve(SessionWorktree.runIdFor("a:b/c")),
            dir,
        )
        assertTrue(dir.fileName.toString().startsWith("session-"), dir.toString())
        assertFalse(dir.fileName.toString().contains(':'), "the id must be sanitized: $dir")
        assertTrue(SessionWorktree.runIdFor("x".repeat(200)).length <= 40)
    }

    @Test
    @DisplayName("a tree is reusable only after a task that fully merged")
    fun reusableOnlyAfterMergedTask() {
        assertTrue(SessionWorktree.isReusable(manifest(lastTask = task(merge = "applied"))))
        assertFalse(SessionWorktree.isReusable(null), "no manifest, no reuse")
        assertFalse(
            SessionWorktree.isReusable(manifest(lastTask = task(merge = "refused"))),
            "an unmerged change must stay inspectable",
        )
        assertFalse(
            SessionWorktree.isReusable(manifest(lastTask = task(merge = null))),
            "a tree with no recorded merge was never merged by this design",
        )
        assertFalse(
            SessionWorktree.isReusable(manifest(lastTask = task(status = TaskStatus.FAILED))),
            "a failed task's leftovers are not disposable",
        )
        assertFalse(
            SessionWorktree.isReusable(manifest(currentTask = task())),
            "a running task owns the tree",
        )
        assertFalse(
            SessionWorktree.isReusable(manifest(state = WorkspaceState.ACTIVE, lastTask = task())),
            "an active workspace is not idle",
        )
    }
}
