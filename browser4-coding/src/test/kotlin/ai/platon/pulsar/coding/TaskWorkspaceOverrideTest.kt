package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `agent run --workspace <dir>` validation: the backend's single decision point for a
 * caller-named task workspace.
 */
class TaskWorkspaceOverrideTest {

    @Test
    @DisplayName("an absolute existing directory is the workspace, normalized")
    fun resolvesAnExistingDirectory(@TempDir tempDir: Path) {
        val corpus = Files.createDirectories(tempDir.resolve("corpus"))

        assertEquals(
            corpus.toAbsolutePath().normalize(),
            TaskWorkspaceOverride.resolve(corpus.toString()),
        )
        assertEquals(
            corpus.toAbsolutePath().normalize(),
            TaskWorkspaceOverride.resolve((tempDir.resolve("a").resolve("..").resolve("corpus")).toString()),
            "`.`/`..` segments must not leak into the boundary root",
        )
    }

    @Test
    @DisplayName("a leading ~ expands to the home directory")
    fun expandsTheHomeShorthand() {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

        assertEquals(home, TaskWorkspaceOverride.resolve("~"))
    }

    @Test
    @DisplayName("a blank or relative value is rejected: the back end cannot guess a directory")
    fun rejectsBlankAndRelativeValues() {
        val blank = assertThrows(IllegalArgumentException::class.java) {
            TaskWorkspaceOverride.resolve("   ")
        }
        assertTrue(blank.message!!.contains("non-blank"), blank.message!!)

        val relative = assertThrows(IllegalArgumentException::class.java) {
            TaskWorkspaceOverride.resolve("corpus")
        }
        assertTrue(relative.message!!.contains("absolute path"), relative.message!!)
    }

    @Test
    @DisplayName("a missing folder is rejected instead of being created")
    fun rejectsAMissingDirectory(@TempDir tempDir: Path) {
        val missing = tempDir.resolve("not-created")

        val error = assertThrows(IllegalArgumentException::class.java) {
            TaskWorkspaceOverride.resolve(missing.toString())
        }
        assertTrue(error.message!!.contains("not an existing directory"), error.message!!)
        assertTrue(error.message!!.contains("never created implicitly"), error.message!!)
    }

    @Test
    @DisplayName("a regular file is not a workspace")
    fun rejectsAFile(@TempDir tempDir: Path) {
        val file = Files.writeString(tempDir.resolve("rows.csv"), "a,b")

        val error = assertThrows(IllegalArgumentException::class.java) {
            TaskWorkspaceOverride.resolve(file.toString())
        }
        assertTrue(error.message!!.contains("not an existing directory"), error.message!!)
    }

    @Test
    @DisplayName("an OS credential store is refused before its existence or type is checked")
    fun refusesCredentialStores(@TempDir tempDir: Path) {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

        // ~/.ssh may not exist on this machine: the refusal must not depend on that.
        val error = assertThrows(IllegalArgumentException::class.java) {
            TaskWorkspaceOverride.resolve(home.resolve(".ssh").toString())
        }
        assertTrue(error.message!!.contains("credential store"), error.message!!)
        assertTrue(error.message!!.contains("~/.ssh"), error.message!!)
    }

    @Test
    @DisplayName("a coding task may not run in place in the backend's own checkout")
    fun codingTaskRefusesTheBackendCheckout() {
        val checkout = CodingWorkspace.workspaceRoot.toAbsolutePath().normalize()

        val error = assertThrows(IllegalArgumentException::class.java) {
            TaskWorkspaceOverride.resolve(checkout.toString(), coding = true)
        }
        assertTrue(error.message!!.contains("coding checkout"), error.message!!)
        assertTrue(error.message!!.contains("worktree"), error.message!!)
        assertTrue(error.message!!.contains("--workspace"), error.message!!)
        assertTrue(error.message!!.contains("workspace.isolation=off"), error.message!!)

        // A browsing task is not refused: no verify-gated merge exists to bypass, and the
        // directory is still an explicit operator choice.
        assertEquals(
            checkout,
            TaskWorkspaceOverride.resolve(checkout.toString(), coding = false),
        )
    }

    @Test
    @DisplayName("describe names the override, or `default` when none was given")
    fun describesTheOverride(@TempDir tempDir: Path) {
        assertEquals("default", TaskWorkspaceOverride.describe(null))
        assertEquals(tempDir.toString(), TaskWorkspaceOverride.describe(tempDir))
    }
}
