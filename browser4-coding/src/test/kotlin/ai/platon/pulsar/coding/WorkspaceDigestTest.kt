package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [WorkspaceDigest] — the fingerprint that binds a verdict to the tree it
 * verified (isolation gate freshness).
 */
class WorkspaceDigestTest {

    @Test
    @DisplayName("the digest follows the content, and path order does not matter")
    fun digestFollowsContent(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.kt"), "one\n")
        Files.writeString(tmp.resolve("b.kt"), "two\n")
        val changed = listOf("a.kt", "b.kt")

        val first = WorkspaceDigest.of(tmp, changed)
        assertNotNull(first)
        assertEquals(first, WorkspaceDigest.of(tmp, listOf("b.kt", "a.kt")), "path order must not matter")
        assertEquals(first, WorkspaceDigest.of(tmp, listOf("./a.kt", "b.kt")), "./ prefix must not matter")

        Files.writeString(tmp.resolve("a.kt"), "one!\n")
        assertNotEquals(first, WorkspaceDigest.of(tmp, changed), "content must change the digest")
    }

    @Test
    @DisplayName("a deletion is part of the change set")
    fun deletionChangesDigest(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.kt"), "one\n")
        Files.writeString(tmp.resolve("b.kt"), "two\n")
        val changed = listOf("a.kt", "b.kt")
        val beforeDelete = WorkspaceDigest.of(tmp, changed)

        Files.delete(tmp.resolve("b.kt"))

        assertNotEquals(beforeDelete, WorkspaceDigest.of(tmp, changed))
    }

    @Test
    @DisplayName("paths that escape the workspace are not fingerprinted")
    fun escapingPathsRejected(@TempDir tmp: Path) {
        val root = tmp.resolve("root")
        Files.createDirectories(root)
        Files.writeString(tmp.resolve("outside.txt"), "x")

        assertNull(WorkspaceDigest.of(root, listOf("../outside.txt")))
        assertNull(WorkspaceDigest.of(root, listOf(tmp.resolve("outside.txt").toString())))
    }

    @Test
    @DisplayName("an unknown root or an empty change set has no digest")
    fun missingInputsYieldNull(@TempDir tmp: Path) {
        assertNull(WorkspaceDigest.of(null, listOf("a.kt")))
        assertNull(WorkspaceDigest.of(tmp, emptyList()))
    }
}
