package ai.platon.pulsar.coding

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for [CodingWorkspace] property resolution.
 */
class CodingWorkspaceTest {

    @AfterEach
    fun clearProperties() {
        System.clearProperty("browser4.agent.workspace")
        System.clearProperty("browser4.agent.allowExternalAccess")
        System.clearProperty("browser4.agent.allowDestructive")
    }

    @Test
    @DisplayName("workspaceRoot defaults to user.dir (or the enclosing repo root)")
    fun testWorkspaceRootDefaults() {
        // When no workspace is configured, the workspace root is user.dir —
        // unless user.dir sits inside a Browser4 checkout, in which case the
        // repository root (ROOT.md + pom.xml) is used automatically.
        val expected = CodingWorkspace.findRepoRootFrom(
            Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        ) ?: Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        assertEquals(expected, CodingWorkspace.workspaceRoot)
    }

    @Test
    @DisplayName("workspaceRoot honors browser4.agent.workspace")
    fun testWorkspaceRootConfigured() {
        System.setProperty("browser4.agent.workspace", "C:/dev/repo")
        assertEquals(
            Path.of("C:/dev/repo").toAbsolutePath().normalize(),
            CodingWorkspace.workspaceRoot
        )
    }

    @Test
    @DisplayName("findRepoRootFrom walks up to a directory with ROOT.md and pom.xml")
    fun testFindRepoRootFromWalksUp() {
        val tempDir = java.nio.file.Files.createTempDirectory("coding-workspace-test")
        try {
            val root = tempDir.resolve("browser4-repo")
            java.nio.file.Files.createDirectories(root.resolve("browser4-apps/browser4-bundle/target"))
            java.nio.file.Files.writeString(root.resolve("ROOT.md"), "# Browser4\n")
            java.nio.file.Files.writeString(root.resolve("pom.xml"), "<project/>\n")

            val deep = root.resolve("browser4-apps/browser4-bundle/target")
            assertEquals(root, CodingWorkspace.findRepoRootFrom(deep))
            assertEquals(root, CodingWorkspace.findRepoRootFrom(root))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("findRepoRootFrom returns null outside a repository")
    fun testFindRepoRootFromOutsideRepo() {
        val tempDir = java.nio.file.Files.createTempDirectory("coding-workspace-outside")
        try {
            java.nio.file.Files.writeString(tempDir.resolve("pom.xml"), "<project/>\n")
            // pom.xml without ROOT.md is NOT a Browser4 repo root.
            assertEquals(null, CodingWorkspace.findRepoRootFrom(tempDir))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("allowExternalAccess defaults to false and honors the property")
    fun testAllowExternalAccess() {
        assertFalse(CodingWorkspace.allowExternalAccess)
        System.setProperty("browser4.agent.allowExternalAccess", "true")
        assertTrue(CodingWorkspace.allowExternalAccess)
    }

    @Test
    @DisplayName("allowDestructive defaults to true and honors the property")
    fun testAllowDestructive() {
        assertTrue(CodingWorkspace.allowDestructive)
        System.setProperty("browser4.agent.allowDestructive", "false")
        assertFalse(CodingWorkspace.allowDestructive)
    }
}
