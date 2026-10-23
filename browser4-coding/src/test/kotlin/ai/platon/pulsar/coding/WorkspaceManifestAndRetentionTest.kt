package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

/**
 * Manifest persistence, task-start lifecycle, and workspace retention
 * (coding-run-isolation-design.md §6.1, §6.2, §6.7).
 */
class WorkspaceManifestAndRetentionTest {

    @TempDir
    lateinit var tempDir: Path

    private val store = WorkspaceManifestStore()
    private val lifecycle = WorkspaceTaskLifecycle(store)

    private fun manifest(
        state: WorkspaceState = WorkspaceState.COMPLETE,
        lastTask: TaskSnapshot? = null,
        currentTask: TaskSnapshot? = null,
    ) = WorkspaceManifest(
        workspaceId = "ws-1",
        agentUuid = "agent-1",
        engine = "cli",
        state = state,
        currentTask = currentTask,
        lastTask = lastTask,
    )

    private fun task(
        id: String = "t1",
        status: TaskStatus? = TaskStatus.COMPLETE,
        endedAt: Instant = Instant.now(),
        filesChanged: List<String> = emptyList(),
    ) = TaskSnapshot(
        taskId = id,
        instruction = "do something",
        coding = true,
        startedAt = endedAt.minusSeconds(60),
        status = status,
        endedAt = endedAt,
        filesChanged = filesChanged,
    )

    // -- Store -----------------------------------------------------------------

    @Test
    @DisplayName("write then read round-trips the manifest")
    fun roundTrip() {
        val ws = tempDir.resolve("ws")
        val m = manifest(lastTask = task("t-42"))
        store.write(ws, m)
        val read = store.read(ws)
        assertNotNull(read)
        assertEquals(WorkspaceManifest.SCHEMA_VERSION, read!!.schema)
        assertEquals("t-42", read.lastTask?.taskId)
        assertEquals(TaskStatus.COMPLETE, read.lastTask?.status)
    }

    @Test
    @DisplayName("read returns null for a workspace with no manifest (legacy)")
    fun legacyNull() {
        assertNull(store.read(tempDir.resolve("never-created")))
        assertTrue(store.isLegacy(tempDir.resolve("never-created")))
    }

    @Test
    @DisplayName("read returns null for a corrupt manifest file (legacy fallback)")
    fun corruptNull() {
        val ws = tempDir.resolve("ws")
        Files.createDirectories(ws.resolve(".browser4"))
        Files.writeString(ws.resolve(WorkspaceManifest.FILENAME), "{not json")
        assertNull(store.read(ws))
    }

    @Test
    @DisplayName("v1 manifest (no currentTask) is upgraded on read")
    fun v1Upgrade() {
        val ws = tempDir.resolve("ws")
        Files.createDirectories(ws.resolve(".browser4"))
        val v1 = """
            {"schema":1,"workspaceId":"old","agentUuid":"a","engine":"cli",
             "lastTask":{"taskId":"t-old","instruction":"x","coding":true,"startedAt":"2026-01-01T00:00:00Z","status":"COMPLETE","endedAt":"2026-01-01T00:01:00Z","filesChanged":["a.kt"],"mutations":2}}
        """.trimIndent()
        Files.writeString(ws.resolve(WorkspaceManifest.FILENAME), v1)
        val m = store.read(ws)
        assertNotNull(m)
        assertEquals("t-old", m!!.lastTask?.taskId)
        assertNull(m.currentTask)
        assertEquals(WorkspaceManifest.SCHEMA_VERSION, m.schema)
    }

    @Test
    @DisplayName("write is atomic: tmp file is moved, no leftover .tmp")
    fun atomicWrite() {
        val ws = tempDir.resolve("ws")
        store.write(ws, manifest())
        assertTrue(ws.resolve(WorkspaceManifest.FILENAME).exists())
        assertFalse(ws.resolve(".browser4").resolve("workspace.json.tmp").exists())
    }

    @Test
    @DisplayName("delete removes the manifest")
    fun deleteRemoves() {
        val ws = tempDir.resolve("ws")
        store.write(ws, manifest())
        store.delete(ws)
        assertNull(store.read(ws))
    }

    // -- Lifecycle / beginTask -------------------------------------------------

    @Test
    @DisplayName("beginTask resets the change counter and mutation log")
    fun beginTaskResetsTracking() {
        val ws = tempDir.resolve("ws")
        val fs = CodingAgentFileSystem(ws)
        fs.beginTask(null)
        // After reset, changeSummary reports no tracked changes and the log is empty.
        assertEquals("No changes tracked.", fs.changeSummary())
        assertTrue(fs.mutationsSnapshot().isEmpty())
    }

    @Test
    @DisplayName("beginTask moves the snapshot dir into the archive dir for coding tasks")
    fun beginTaskArchivesSnapshots() {
        val ws = tempDir.resolve("ws")
        val fs = CodingAgentFileSystem(ws)
        // Simulate a persisted snapshot from a previous task.
        val snapDir = ws.resolve(".browser4").resolve("coding-snapshots")
        Files.createDirectories(snapDir)
        Files.writeString(snapDir.resolve("a.kt.snap"), "old content")
        val archive = WorkspaceTaskLifecycle.archiveDir(ws)

        val outcome = lifecycle.beginTask(ws, "t2", "do it", coding = true, fs)

        assertEquals(archive, outcome.archiveDir)
        assertFalse(snapDir.exists(), "snapshot dir should have been moved out")
        assertTrue(archive.resolve("coding-snapshots").resolve("a.kt.snap").exists())
    }

    @Test
    @DisplayName("beginTask skips archive/inheritance for the same taskId (resume)")
    fun beginTaskResumeGuard() {
        val ws = tempDir.resolve("ws")
        store.write(ws, manifest(lastTask = task("same")))
        val fs = CodingAgentFileSystem(ws)
        val outcome = lifecycle.beginTask(ws, "same", "do it", coding = true, fs)
        assertNull(outcome.inheritedSummary, "resume of same task must not inherit")
        assertNull(outcome.previousTask)
    }

    @Test
    @DisplayName("beginTask returns inherited summary for a different previous task")
    fun beginTaskInheritedSummary() {
        val ws = tempDir.resolve("ws")
        store.write(ws, manifest(lastTask = task("t-prev")))
        val fs = CodingAgentFileSystem(ws)
        val outcome = lifecycle.beginTask(ws, "t-next", "do it", coding = true, fs)
        assertNotNull(outcome.inheritedSummary)
        assertTrue(outcome.inheritedSummary!!.contains("Inherited workspace state"))
        // Design §6.2: must not claim the previous task "completed".
        assertFalse(outcome.inheritedSummary.contains("completed"),
            "inherited summary must not say 'completed' (edits may have been reverted)")
        // Bounded to ≤10 lines.
        assertTrue(outcome.inheritedSummary.lineSequence().count() <= 10)
    }

    @Test
    @DisplayName("archivePreviousDeliverables caps at 50 files and writes archive.json")
    fun archiveCap() {
        val ws = tempDir.resolve("ws")
        Files.createDirectories(ws)
        // Create 60 deliverable files in the workspace.
        val files = (1..60).map { ws.resolve("f$it.txt") }
        files.forEach { Files.writeString(it, "x".repeat(100)) }
        val prev = task("t-prev", filesChanged = files.map { it.fileName.toString() })
        store.write(ws, manifest(lastTask = prev))
        val fs = CodingAgentFileSystem(ws)

        lifecycle.beginTask(ws, "t-next", "do it", coding = true, fs)

        val archive = WorkspaceTaskLifecycle.archiveDir(ws).resolve("t-prev")
        val copied = Files.walk(archive).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString() != "archive.json" }.count()
        }
        assertEquals(50, copied, "archive must cap at 50 files")
        assertTrue(archive.resolve("archive.json").exists())
    }

    @Test
    @DisplayName("computeFilesChanged = (Δgit - baseline) ∪ mutation deliverables")
    fun computeFilesChanged() {
        // Without a real git repo, delta is empty; result is mutation deliverables.
        val result = lifecycle.computeFilesChanged(null, emptySet(), listOf("a.kt", "b.kt"))
        assertEquals(listOf("a.kt", "b.kt"), result)
    }

    // -- Retention / GC --------------------------------------------------------

    @Test
    @DisplayName("deleteWorkspaceTree is junction-safe: deletes the link, not its target")
    fun deleteWorkspaceTreeJunctionSafe() {
        val ws = tempDir.resolve("ws")
        val target = tempDir.resolve("real-target")
        Files.createDirectories(ws)
        Files.createDirectories(target)
        Files.writeString(target.resolve("keep-me.txt"), "precious")

        // Create a symlink from workspace into the target (works on all platforms
        // for dirs; Windows needs developer mode but CI runs with it on).
        val link = ws.resolve("junction-to-target")
        runCatching { Files.createSymbolicLink(link, target) }
            .getOrElse { return@deleteWorkspaceTreeJunctionSafe /* skip if unsupported */ }

        WorkspaceRetention().deleteWorkspaceTree(ws)

        // The workspace itself should be gone…
        assertFalse(ws.exists())
        // …but the real target (outside the workspace) must be untouched.
        assertTrue(target.resolve("keep-me.txt").exists())
    }

    @Test
    @DisplayName("sweep keeps the latest `keep` workspaces regardless of age")
    fun sweepKeepsLatest() {
        val root = tempDir.resolve("agent-data")
        val retention = WorkspaceRetention(keep = 2, maxDays = 0)
        val now = Instant.now()
        // Three terminal workspaces; "a" is newest, "c" is oldest.
        val endedAts = mapOf("a" to now, "b" to now.minusSeconds(60), "c" to now.minusSeconds(120))
        endedAts.forEach { (name, ended) ->
            val dir = root.resolve(name)
            Files.createDirectories(dir)
            store.write(dir, manifest(state = WorkspaceState.COMPLETE, lastTask = task(name, endedAt = ended)))
        }
        retention.sweep(root)
        // keep=2 ⇒ newest two (a, b) survive; oldest (c) is deleted.
        assertTrue(root.resolve("a").exists())
        assertTrue(root.resolve("b").exists())
        assertFalse(root.resolve("c").exists())
    }

    @Test
    @DisplayName("sweep skips a workspace with a live lock")
    fun sweepSkipsLiveLock() {
        val root = tempDir.resolve("agent-data")
        val retention = WorkspaceRetention(keep = 0, maxDays = 0)
        val dir = root.resolve("live")
        Files.createDirectories(dir)
        store.write(dir, manifest(state = WorkspaceState.COMPLETE, lastTask = task("t", endedAt = Instant.now().minusSeconds(3600))))
        retention.writeLock(dir)
        retention.sweep(root)
        assertTrue(dir.exists(), "live-locked workspace must be skipped")
    }

    @Test
    @DisplayName("sweep marks ACTIVE-without-lock as stale (kept one cycle)")
    fun sweepMarksActiveStale() {
        val root = tempDir.resolve("agent-data")
        val retention = WorkspaceRetention(keep = 0, maxDays = 0)
        val dir = root.resolve("stale-candidate")
        Files.createDirectories(dir)
        store.write(dir, manifest(state = WorkspaceState.ACTIVE, lastTask = task("t", endedAt = Instant.now().minusSeconds(3600))))
        retention.sweep(root)
        // Not deleted — marked stale and kept this cycle.
        assertTrue(dir.exists())
        assertEquals(WorkspaceState.STALE, store.read(dir)?.state)
    }

    @Test
    @DisplayName("dry-run sweep logs intent but deletes nothing")
    fun sweepDryRun() {
        val root = tempDir.resolve("agent-data")
        val retention = WorkspaceRetention(keep = 0, maxDays = 0, dryRun = true)
        val dir = root.resolve("doomed")
        Files.createDirectories(dir)
        store.write(dir, manifest(state = WorkspaceState.COMPLETE, lastTask = task("t", endedAt = Instant.now().minusSeconds(3600))))
        retention.sweep(root)
        assertTrue(dir.exists(), "dry-run must not delete")
    }
}
