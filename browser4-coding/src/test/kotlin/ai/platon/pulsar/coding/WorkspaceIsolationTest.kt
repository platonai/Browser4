package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Tests for the transactional workspace (plan P2.1): a task's writes land in an
 * isolated git worktree, a successful task merges back, and a failed task leaves
 * the main work tree byte-identical.
 *
 * Every test runs against a throw-away git repository in a temp directory — this
 * suite must never touch the real checkout.
 */
class WorkspaceIsolationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun git(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(tempDir.toFile())
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git ${args.joinToString(" ")} timed out")
        return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
    }

    @BeforeEach
    fun initRepo() {
        assumeTrue(
            runCatching {
                ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS)
            }.getOrDefault(false),
            "git is not available",
        )
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Workspace Isolation Test")
        git("config", "commit.gpgsign", "false")
        // Keep the checkout byte-identical on hosts whose global config rewrites
        // line endings (`core.autocrlf=true` on Windows).
        git("config", "core.autocrlf", "false")
        git("config", "core.eol", "lf")
        // The management directory is git-ignored in the real repository; mirror
        // that so "main tree stays clean" means exactly that.
        Files.writeString(tempDir.resolve(".gitignore"), ".browser4/\n")
        Files.writeString(tempDir.resolve("a.txt"), "original\n")
        git("add", ".")
        git("commit", "-m", "initial")
    }

    private fun status(): String = git("status", "--porcelain").second.trim()

    private fun read(path: Path): String =
        Files.readString(path).replace("\r\n", "\n")

    // ==================== creation ====================

    @Test
    @DisplayName("begin creates a worktree checkout of HEAD outside the main tree")
    fun beginCreatesWorktree() = runBlocking {
        val isolation = WorkspaceIsolation.begin(tempDir, "run-1")
        try {
            assertEquals(tempDir.toRealPath(), isolation.repoRoot.toRealPath())
            assertTrue(Files.exists(isolation.isolatedRoot), "isolated root must exist")
            assertEquals("original\n", read(isolation.isolatedRoot.resolve("a.txt")))
            assertTrue(Files.exists(isolation.isolatedRoot.resolve(".git")), "must be a real worktree")
            // Committed worktrees live under the ignored management directory.
            assertTrue(
                isolation.isolatedRoot.toString().replace('\\', '/').contains(WorkspaceIsolation.WORKTREE_DIR),
                "isolated root: ${isolation.isolatedRoot}",
            )
        } finally {
            isolation.rollback()
        }
        assertFalse(Files.exists(isolation.isolatedRoot), "rollback must remove the worktree")
    }

    @Test
    @DisplayName("begin fails loudly outside a git work tree")
    fun beginFailsOutsideRepo() {
        // A directory under the system temp root — outside this test's repo.
        val nonRepo = Files.createTempDirectory("b4-non-git")
        try {
            assumeTrue(WorkspaceIsolation.resolveRepoRoot(nonRepo) == null, "temp dir is inside a git repo")
            assertThrows(IllegalStateException::class.java) {
                runBlocking { WorkspaceIsolation.begin(nonRepo, "run-x") }
            }
        } finally {
            nonRepo.toFile().deleteRecursively()
        }
    }

    // ==================== isolation ====================

    @Test
    @DisplayName("writes land in the isolated tree, never in the main tree")
    fun writesStayIsolated() = runBlocking {
        val isolation = WorkspaceIsolation.begin(tempDir, "run-2")
        try {
            Files.writeString(isolation.isolatedRoot.resolve("a.txt"), "changed\n")
            Files.writeString(isolation.isolatedRoot.resolve("b.txt"), "new file\n")

            assertEquals("original\n", read(tempDir.resolve("a.txt")), "main tree must be untouched")
            assertFalse(Files.exists(tempDir.resolve("b.txt")), "new file must not leak into the main tree")
            assertEquals("", status(), "main tree must stay clean")

            val changed = isolation.changedFiles().sorted()
            assertEquals(listOf("a.txt", "b.txt"), changed)
        } finally {
            isolation.rollback()
        }
    }

    // ==================== merge ====================

    @Test
    @DisplayName("commit merges the isolated change into the main work tree")
    fun commitMerges() = runBlocking {
        val isolation = WorkspaceIsolation.begin(tempDir, "run-3")
        Files.writeString(isolation.isolatedRoot.resolve("a.txt"), "changed\n")
        Files.writeString(isolation.isolatedRoot.resolve("b.txt"), "new file\n")

        val outcome = isolation.commit("test merge")
        assertTrue(outcome.ok, "merge failed: ${outcome.message}")
        assertEquals(listOf("a.txt", "b.txt"), outcome.changedFiles.sorted())
        assertEquals("changed\n", read(tempDir.resolve("a.txt")))
        assertEquals("new file\n", read(tempDir.resolve("b.txt")))
        assertFalse(Files.exists(isolation.isolatedRoot), "the worktree must be cleaned up after a merge")
    }

    @Test
    @DisplayName("a task that changed nothing merges cleanly")
    fun commitWithoutChanges() = runBlocking {
        val isolation = WorkspaceIsolation.begin(tempDir, "run-4")
        val outcome = isolation.commit()
        assertTrue(outcome.ok, outcome.message)
        assertTrue(outcome.changedFiles.isEmpty())
        assertTrue(outcome.message.contains("No changes"), outcome.message)
        assertFalse(Files.exists(isolation.isolatedRoot))
    }

    // ==================== rollback ====================

    @Test
    @DisplayName("rollback leaves the main work tree clean (plan P2.1 acceptance)")
    fun rollbackLeavesMainClean() = runBlocking {
        val isolation = WorkspaceIsolation.begin(tempDir, "run-5")
        Files.writeString(isolation.isolatedRoot.resolve("a.txt"), "broken edit\n")
        Files.writeString(isolation.isolatedRoot.resolve("generated.txt"), "junk\n")

        val outcome = isolation.rollback()
        assertTrue(outcome.ok, outcome.message)
        assertEquals(listOf("a.txt", "generated.txt"), outcome.changedFiles.sorted())
        assertEquals("original\n", read(tempDir.resolve("a.txt")))
        assertFalse(Files.exists(tempDir.resolve("generated.txt")))
        assertEquals("", status(), "the main work tree must be byte-identical after a rollback")
    }

    @Test
    @DisplayName("two sequential isolations do not collide")
    fun sequentialIsolations() = runBlocking {
        val first = WorkspaceIsolation.begin(tempDir, "run-6a")
        Files.writeString(first.isolatedRoot.resolve("first.txt"), "one\n")
        assertTrue(first.commit().ok)
        val second = WorkspaceIsolation.begin(tempDir, "run-6b")
        Files.writeString(second.isolatedRoot.resolve("second.txt"), "two\n")
        assertTrue(second.commit().ok)
        assertTrue(Files.exists(tempDir.resolve("first.txt")))
        assertTrue(Files.exists(tempDir.resolve("second.txt")))
    }

    // ==================== S1: merge protocol ====================

    @Test
    @DisplayName("agent bookkeeping directories are never part of the change set")
    fun bookkeepingExcludedFromChanges() = runBlocking {
        val isolation = WorkspaceIsolation.begin(tempDir, "run-excl")
        try {
            Files.writeString(isolation.isolatedRoot.resolve("a.txt"), "changed\n")
            Files.createDirectories(isolation.isolatedRoot.resolve(".b4scratch"))
            Files.writeString(isolation.isolatedRoot.resolve(".b4scratch/helper.py"), "print(1)\n")
            Files.createDirectories(isolation.isolatedRoot.resolve(".b4inputs"))
            Files.writeString(isolation.isolatedRoot.resolve(".b4inputs/spec.csv"), "a,b\n")
            Files.createDirectories(isolation.isolatedRoot.resolve(".browser4"))
            Files.writeString(isolation.isolatedRoot.resolve(".browser4/workspace.json"), "{}\n")

            assertEquals(listOf("a.txt"), isolation.changedFiles())
        } finally {
            isolation.rollback()
        }
    }

    @Test
    @DisplayName("applyToMain merges, keeps the worktree, keeps the patch and records the pre-merge ref")
    fun applyToMainKeepsRecoveryHandles() = runBlocking {
        val isolation = WorkspaceIsolation.begin(tempDir, "run-merge")
        Files.writeString(isolation.isolatedRoot.resolve("b.txt"), "new file\n")

        val outcome = isolation.applyToMain("task-merge")
        try {
            assertTrue(outcome.ok, "merge failed: ${outcome.message}")
            assertEquals(listOf("b.txt"), outcome.changedFiles)
            assertEquals("new file\n", read(tempDir.resolve("b.txt")))
            assertTrue(Files.exists(isolation.isolatedRoot), "the worktree must be kept for review/resume")
            assertTrue(outcome.worktreeKept)
            assertNotNull(outcome.patchPath)
            assertTrue(Files.exists(Path.of(outcome.patchPath!!)), "the patch must be kept for recover")
            assertEquals("refs/b4/pre/task-merge", outcome.preRef)
            val r = git("rev-parse", "--verify", outcome.preRef!!)
            assertEquals(0, r.first, "pre-merge ref must exist: ${r.second}")
        } finally {
            isolation.rollback()
        }
    }

    @Test
    @DisplayName("a dirty main tree is captured as the baseline and preserved by the merge")
    fun dirtyBaselineIsPreserved() = runBlocking {
        Files.writeString(tempDir.resolve("a.txt"), "user-dirty\n")
        val isolation = WorkspaceIsolation.begin(tempDir, "run-dirty")
        try {
            assertEquals("user-dirty\n", read(isolation.isolatedRoot.resolve("a.txt")), "task must see the user's uncommitted edit")

            Files.writeString(isolation.isolatedRoot.resolve("b.txt"), "agent\n")
            val outcome = isolation.applyToMain("task-dirty")
            assertTrue(outcome.ok, "merge failed: ${outcome.message}")
            assertEquals("user-dirty\n", read(tempDir.resolve("a.txt")), "the user's edit must survive")
            assertEquals("agent\n", read(tempDir.resolve("b.txt")))
        } finally {
            isolation.rollback()
        }
    }

    @Test
    @DisplayName("re-applying an already merged patch is reported as merged, not refused")
    fun mergeIsIdempotent() {
        runBlocking {
            val isolation = WorkspaceIsolation.begin(tempDir, "run-idem")
            try {
                Files.writeString(isolation.isolatedRoot.resolve("b.txt"), "agent\n")
                val first = isolation.applyToMain("task-idem")
                assertTrue(first.ok, "first merge failed: ${first.message}")

                // A resume can reach the merge point twice (merged, then crashed before
                // the terminal manifest was written); it must not fail.
                val second = isolation.applyToMain("task-idem")
                assertTrue(second.ok, "second merge failed: ${second.message}")
                assertTrue(second.message.contains("Already merged"), second.message)
                assertEquals("agent\n", read(tempDir.resolve("b.txt")))
            } finally {
                isolation.rollback()
            }
        }
    }

    @Test
    @DisplayName("a merge leaves the change unstaged, exactly like a manual edit")
    fun mergeLeavesChangeUnstaged() {
        runBlocking {
            val isolation = WorkspaceIsolation.begin(tempDir, "run-unstaged")
            try {
                Files.writeString(isolation.isolatedRoot.resolve("b.txt"), "agent\n")
                val outcome = isolation.applyToMain("task-unstaged")
                assertTrue(outcome.ok, "merge failed: ${outcome.message}")
                // `git apply --3way` stages what it merges; the merge must not. A staged
                // file would read "A  b.txt" instead of "?? b.txt".
                assertEquals("?? b.txt", status(), "the merge must not touch the index")
            } finally {
                isolation.rollback()
            }
        }
    }

    @Test
    @DisplayName("the kept patch reverses exactly one task")
    fun patchReversesOneTask() {
        runBlocking {
            val isolation = WorkspaceIsolation.begin(tempDir, "run-revert")
            Files.writeString(isolation.isolatedRoot.resolve("b.txt"), "agent\n")
            val outcome = isolation.applyToMain("task-revert")
            assertTrue(outcome.ok, "merge failed: ${outcome.message}")
            assertEquals("agent\n", read(tempDir.resolve("b.txt")))

            val revert = git("apply", "-R", outcome.patchPath!!)
            assertEquals(0, revert.first, "reverse apply failed: ${revert.second}")
            assertFalse(Files.exists(tempDir.resolve("b.txt")), "the task's file must be gone after the revert")
            assertEquals("", status(), "the main tree must be clean again")
            isolation.rollback()
        }
    }

    @Test
    @DisplayName("begin refuses to nest isolation inside a linked worktree")
    fun nestedWorktreeRejected() = runBlocking {
        val isolation = WorkspaceIsolation.begin(tempDir, "run-parent")
        try {
            val nested = assertThrows(IllegalStateException::class.java) {
                runBlocking { WorkspaceIsolation.begin(isolation.isolatedRoot, "run-child") }
            }
            assertTrue(nested.message!!.contains("linked worktree"), nested.message!!)
        } finally {
            isolation.rollback()
        }
    }

    @Test
    @DisplayName("rebase resets a reused worktree to the checkout's current baseline")
    fun rebaseResetsReusedWorktree() {
        runBlocking {
            val isolation = WorkspaceIsolation.begin(tempDir, "session-x")
            try {
                Files.writeString(isolation.isolatedRoot.resolve("a.txt"), "merged change\n")
                val merged = isolation.applyToMain("task-1")
                assertTrue(merged.ok, "merge failed: ${merged.message}")
                assertEquals("merged change\n", read(tempDir.resolve("a.txt")))

                // A previous run may leave untracked helpers behind; reuse must clear them.
                Files.writeString(isolation.isolatedRoot.resolve("leftover.txt"), "junk\n")

                assertTrue(isolation.rebase(), "rebase must succeed on a clean checkout")

                assertEquals(
                    "merged change\n",
                    read(isolation.isolatedRoot.resolve("a.txt")),
                    "the merged change is part of the new baseline",
                )
                assertFalse(
                    Files.exists(isolation.isolatedRoot.resolve("leftover.txt")),
                    "untracked leftovers are cleaned by rebase",
                )
                assertTrue(isolation.changedFiles().isEmpty(), "a reused tree starts clean: ${isolation.changedFiles()}")
            } finally {
                isolation.rollback()
            }
        }
    }

    // ==================== helpers ====================

    @Test
    @DisplayName("run ids are sanitized into safe directory names")
    fun runIdSanitized() {
        assertEquals("a-b-c", WorkspaceIsolation.sanitize("a:b/c"))
        assertFalse(WorkspaceIsolation.sanitize("x".repeat(200)).length > 64)
        assertEquals("task", WorkspaceIsolation.sanitize(""))
    }

    @Test
    @DisplayName("isAvailable reflects the presence of a git work tree")
    fun availability() {
        assertTrue(WorkspaceIsolation.isAvailable(tempDir))
        assertNotNull(WorkspaceIsolation.resolveRepoRoot(tempDir))
    }
}
