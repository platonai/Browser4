package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Worktree retention (isolation plan S4): old isolated trees are reclaimed, the
 * newest N and anything recent survive, and removal goes through `git worktree
 * remove` so git's own metadata stays consistent.
 */
class WorktreeRetentionTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var repo: Path

    private fun git(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git ${args.joinToString(" ")} timed out")
        return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
    }

    @BeforeEach
    fun setUp() {
        assumeTrue(
            runCatching { ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS) }
                .getOrDefault(false),
            "git is not available",
        )
        repo = tempDir.resolve("repo")
        Files.createDirectories(repo)
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Worktree Retention Test")
        git("config", "commit.gpgsign", "false")
        Files.writeString(repo.resolve("a.txt"), "original\n")
        git("add", ".")
        git("commit", "-m", "initial")
    }

    /** Create a managed worktree whose directory mtime is [ageDays] days old. */
    private fun addWorktree(id: String, ageDays: Long): Path {
        val path = repo.resolve(WorkspaceIsolation.WORKTREE_DIR).resolve(id)
        Files.createDirectories(path.parent)
        val (exit, output) = git("worktree", "add", "--detach", "--force", path.toString(), "HEAD")
        assertEquals(0, exit, "git worktree add failed: $output")
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(ageDays, ChronoUnit.DAYS)))
        return path
    }

    private fun managedWorktrees(): List<String> = git("worktree", "list", "--porcelain").second
        .lineSequence()
        .filter { it.startsWith("worktree ") }
        .map { it.removePrefix("worktree ").trim().replace('\\', '/') }
        .filter { it.contains("/.browser4/worktrees/") }
        .toList()

    @Test
    @DisplayName("the newest worktrees are kept and stale ones are removed through git")
    fun sweepsStaleWorktrees() {
        val newest = addWorktree("t-newest", ageDays = 0)
        val middle = addWorktree("t-middle", ageDays = 5)
        val oldest = addWorktree("t-oldest", ageDays = 10)
        assertEquals(3, managedWorktrees().size)

        val report = WorktreeRetention.sweep(repo, keep = 1, maxAgeDays = 0, dryRun = false)

        assertEquals(listOf(newest), report.kept)
        assertEquals(setOf(middle, oldest), report.removed.toSet())
        assertTrue(report.changed)
        assertTrue(Files.isDirectory(newest))
        assertFalse(Files.exists(middle), "the 5-day-old worktree must be gone")
        assertFalse(Files.exists(oldest), "the 10-day-old worktree must be gone")
        assertEquals(1, managedWorktrees().size, "git metadata must be pruned: ${managedWorktrees()}")
    }

    @Test
    @DisplayName("dry-run reports nothing removed and deletes nothing")
    fun dryRunKeepsEverything() {
        val a = addWorktree("t-a", ageDays = 10)
        val b = addWorktree("t-b", ageDays = 9)

        val report = WorktreeRetention.sweep(repo, keep = 0, maxAgeDays = 0, dryRun = true)

        assertTrue(report.removed.isEmpty(), "dry-run must not remove: ${report.removed}")
        assertTrue(Files.isDirectory(a))
        assertTrue(Files.isDirectory(b))
        assertEquals(2, managedWorktrees().size)
    }

    @Test
    @DisplayName("worktrees younger than maxAgeDays survive regardless of the keep count")
    fun recentWorktreesSurvive() {
        val a = addWorktree("t-recent-1", ageDays = 1)
        val b = addWorktree("t-recent-2", ageDays = 2)

        val report = WorktreeRetention.sweep(repo, keep = 0, maxAgeDays = 7, dryRun = false)

        assertTrue(report.removed.isEmpty(), "recent worktrees must be kept: ${report.removed}")
        assertTrue(Files.isDirectory(a))
        assertTrue(Files.isDirectory(b))
    }

    @Test
    @DisplayName("the excluded worktree (the current task's) is never swept")
    fun excludedWorktreeSurvives() {
        val current = addWorktree("t-current", ageDays = 30)
        val other = addWorktree("t-other", ageDays = 20)

        val report = WorktreeRetention.sweep(
            repo, keep = 0, maxAgeDays = 0, dryRun = false, exclude = current,
        )

        assertTrue(report.skipped.contains(current), "the current worktree must be reported as skipped")
        assertTrue(Files.isDirectory(current))
        assertFalse(Files.exists(other))
        assertEquals(1, managedWorktrees().size)
    }

    @Test
    @DisplayName("directories outside the managed root are never touched")
    fun unrelatedDirectoriesAreUntouched() {
        val worktree = addWorktree("t-managed", ageDays = 20)
        val unrelated = repo.resolve(".browser4/not-a-worktree")
        Files.createDirectories(unrelated)
        Files.writeString(unrelated.resolve("keep.txt"), "keep\n")

        WorktreeRetention.sweep(repo, keep = 0, maxAgeDays = 0, dryRun = false)

        assertFalse(Files.exists(worktree))
        assertTrue(Files.isRegularFile(unrelated.resolve("keep.txt")), "an unmanaged directory must survive")
    }

    @Test
    @DisplayName("the summary says what the sweep did")
    fun summaryIsMachineReadable() {
        addWorktree("t-summary-1", ageDays = 10)
        addWorktree("t-summary-2", ageDays = 9)

        val report = WorktreeRetention.sweep(repo, keep = 0, maxAgeDays = 0, dryRun = false)

        assertEquals("worktree gc: kept=0 removed=2 skipped=0", report.summary(), report.summary())
    }
}
