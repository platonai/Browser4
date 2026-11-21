package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Known gap G5, pinned as an invariant: **the sandbox must not be able to write a linked worktree's
 * git metadata.**
 *
 * `--coding` runs in a linked worktree, whose `.git` is a *file* pointing at
 * `<main>/.git/worktrees/<name>` — outside the workspace, so the boundary refuses the write. That is
 * a feature, not the bug: letting a Low-integrity child write `.git` would also let it plant
 * `.git/hooks/…`, which the **operator's own** Medium process executes on the next
 * `git commit`/`merge` — a privilege path from the sandbox to the operator. The price is that an
 * agent cannot commit from `coding.shell`; the product's patch/merge runs outside the sandbox, so
 * the main flow is unaffected, and gap G5 records the follow-up (a product-level `git` channel, not
 * a widened ACL).
 *
 * The test therefore asserts both halves: reads work, writes are refused, and the operator can still
 * stage the change from outside the boundary.
 */
@DisplayName("worktree git metadata stays outside the sandbox")
class WorktreeGitBoundaryTest {

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    private fun git(directory: Path, vararg args: String): Pair<Int, String> {
        val command = listOf("git", "-c", "user.email=b4@example.com", "-c", "user.name=b4") + args
        val process = ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return process.exitValue() to output
    }

    private fun gitAvailable(): Boolean = runCatching {
        ProcessBuilder("git", "--version").redirectErrorStream(true).start().apply { waitFor() }.exitValue() == 0
    }.getOrDefault(false)

    @Test
    @DisplayName("a sandboxed git reads the worktree but cannot create its index.lock")
    fun testSandboxedGitCannotWriteWorktreeMetadata() {
        assumeTrue(isWindows, "the low-integrity token only exists on Windows")
        assumeTrue(gitAvailable(), "git is not installed")
        // WRITE_OWNER is needed to lower the label, so the tree must be user-owned (not %TEMP%).
        val root = Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".browser4", "tmp", "git-wt-${System.nanoTime()}"),
        )
        val repo = Files.createDirectories(root.resolve("repo"))
        val worktree = root.resolve("wt")
        val sandbox = WindowsLowIntegritySandbox(
            caches = { emptyList() },
            protectCredentialStores = false,
            markerDir = null,
        )
        try {
            assertEquals(0, git(repo, "init", "-q", "-b", "main").first)
            Files.writeString(repo.resolve("a.txt"), "one\n")
            assertEquals(0, git(repo, "add", "-A").first)
            assertEquals(0, git(repo, "commit", "-q", "-m", "init").first)
            assertEquals(0, git(repo, "worktree", "add", "-q", worktree.toString(), "-b", "b4-probe").first)

            // The metadata really is outside the workspace: `.git` is a pointer file, not a directory.
            val pointer = worktree.resolve(".git")
            assertTrue(Files.isRegularFile(pointer), "a linked worktree has a .git FILE")
            val metadata = Path.of(Files.readString(pointer).trim().removePrefix("gitdir:").trim())
            assertTrue(metadata.startsWith(root), "the probe expects the metadata under the fixture root: $metadata")
            assertTrue(!metadata.startsWith(worktree), "the metadata must be outside the workspace: $metadata")

            sandbox.prepare(
                ProcessSpec(listOf("cmd.exe", "/c", "echo hi"), worktree, mapOf("PATH" to "C:\\Windows\\System32")),
                worktree,
                allowNetwork = false,
            )
            Files.writeString(worktree.resolve("b.txt"), "two\n")

            val status = sandbox.runAsLowWithOutput(worktree, "git -C $worktree status --porcelain")
            assertEquals(0, status.first, "reading a worktree must keep working: ${status.second}")
            assertTrue(status.second.contains("b.txt"), "the new file must be visible: ${status.second}")

            val add = sandbox.runAsLowWithOutput(worktree, "git -C $worktree add -A")
            assertTrue(
                add.first != 0,
                "a sandboxed git must NOT write the worktree metadata (that is what keeps .git/hooks " +
                    "out of a Low-integrity child's reach): ${add.second}",
            )
            assertTrue(
                add.second.contains("index.lock") || add.second.contains("Permission denied", ignoreCase = true),
                "the refusal must name the real cause: ${add.second}",
            )

            // The operator's own (Medium) process still stages the change — the product's patch/merge
            // path runs outside the boundary, which is why G5 does not break the coding flow.
            assertEquals(0, git(worktree, "add", "-A").first, "staging from outside the sandbox must work")
        } finally {
            SystemSandboxCommandRunner.run(
                listOf("icacls", root.toString(), "/setintegritylevel", "(OI)(CI)Medium"),
                30,
            )
            runCatching { root.toFile().deleteRecursively() }
        }
    }
}
