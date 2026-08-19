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
    @DisplayName("workspaceRoot defaults to user.dir")
    fun testWorkspaceRootDefaults() {
        assertEquals(
            Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
            CodingWorkspace.workspaceRoot
        )
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
