package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Tests for [GitStatus] — the one parser behind "which files did this task change".
 *
 * The line-based `git status --porcelain` form quotes non-ASCII paths and writes a rename as
 * `old -> new`, which is how a consumer ends up reporting a file name that exists nowhere.
 */
class GitStatusTest {

    @TempDir
    lateinit var repo: Path

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
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Git Status Test")
        git("config", "commit.gpgsign", "false")
        git("config", "core.autocrlf", "false")
        Files.writeString(repo.resolve("a.txt"), "a\n")
        Files.writeString(repo.resolve("b.txt"), "b\n")
        git("add", ".")
        git("commit", "-m", "initial")
    }

    @Test
    @DisplayName("a non-ASCII or spaced name is reported verbatim, not quoted and escaped")
    fun nonAsciiNamesSurvive() {
        Files.writeString(repo.resolve("naïve 中文.kt"), "class X\n")
        Files.writeString(repo.resolve("with space.md"), "x\n")

        val changed = GitStatus.changedFiles(repo)

        assertEquals(
            setOf("naïve 中文.kt", "with space.md"),
            changed?.toSet(),
            "the names must match the files on disk: $changed",
        )
        assertTrue(
            changed.orEmpty().all { Files.exists(repo.resolve(it)) },
            "every reported path must resolve: $changed",
        )
        val entry = GitStatus.entries(repo)?.first { it.path == "naïve 中文.kt" }
        assertEquals('?', entry?.staged, "an untracked file is reported as ??")
    }

    @Test
    @DisplayName("a rename is reported by its destination, and the vanished original is not listed")
    fun renamesReportTheDestination() {
        val (exit, output) = git("mv", "a.txt", "renamed file.txt")
        assertEquals(0, exit, output)

        val changed = GitStatus.changedFiles(repo)

        assertTrue(changed.orEmpty().contains("renamed file.txt"), "changed: $changed")
        assertFalse(changed.orEmpty().contains("a.txt"), "the old path no longer exists: $changed")
    }

    @Test
    @DisplayName("a deleted file is reported as changed and as deleted")
    fun deletionsAreReported() {
        Files.delete(repo.resolve("b.txt"))

        assertEquals(setOf("b.txt"), GitStatus.deletedFiles(repo)?.toSet())
        assertTrue(GitStatus.changedFiles(repo).orEmpty().contains("b.txt"), "a deletion is a change too")
    }

    @Test
    @DisplayName("untracked files can be excluded")
    fun untrackedCanBeExcluded() {
        Files.writeString(repo.resolve("new.txt"), "x\n")

        assertEquals(emptyList<String>(), GitStatus.changedFiles(repo, untracked = false))
        assertEquals(listOf("new.txt"), GitStatus.changedFiles(repo))
    }

    @Test
    @DisplayName("excluded pathspecs are honoured (agent bookkeeping is not a change)")
    fun excludesAreHonoured() {
        val scratch = Files.createDirectories(repo.resolve(".b4scratch"))
        Files.writeString(scratch.resolve("helper.js"), "x\n")

        // The two spellings the merge uses: a nested scratch dir and a top-level one.
        val excludes = listOf(":(exclude).b4scratch/**", ":(exclude)**/.b4scratch/**")

        assertEquals(
            emptyList<String>(),
            GitStatus.changedFiles(repo, excludes = excludes),
            "agent bookkeeping must never reach the change set",
        )
        assertEquals(listOf(".b4scratch/helper.js"), GitStatus.changedFiles(repo))
    }

    @Test
    @DisplayName("a directory that is not a git work tree yields null")
    fun nonGitDirectoryYieldsNull() {
        val notARepo = Files.createTempDirectory("b4-plain-git-status-")
        try {
            assertNull(GitStatus.changedFiles(notARepo))
            assertNull(GitStatus.entries(notARepo))
        } finally {
            Files.deleteIfExists(notARepo)
        }
    }

    @Test
    @DisplayName("the record parser drops a rename's original path and keeps the target")
    fun parserHandlesRenameRecords() {
        val output = "A  naïve 中文.kt\u0000" +
            "R  new name.txt\u0000old name.txt\u0000" +
            " M dir/x.kt\u0000" +
            " D gone.txt\u0000"

        val entries = GitStatus.parse(output)

        assertEquals(
            listOf("naïve 中文.kt", "new name.txt", "dir/x.kt", "gone.txt"),
            entries.map { it.path },
        )
        assertTrue(entries.last().deleted, "a work-tree deletion is a deleted entry")
        assertFalse(entries.first().deleted)
    }
}
