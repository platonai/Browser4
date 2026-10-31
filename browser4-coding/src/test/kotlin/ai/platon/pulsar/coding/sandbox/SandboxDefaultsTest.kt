package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan P3-4 (D1) — the default is for **new** installations only.
 *
 * An existing installation keeps `off` because turning the boundary on changes what every command
 * may do; a fresh one starts with the platform backend; an explicit `-Dbrowser4.agent.sandbox`
 * always wins. The decision is persisted so a later start does not drift with the filesystem.
 */
@DisplayName("sandbox defaults")
class SandboxDefaultsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun <T> withSandboxProperty(value: String?, block: () -> T): T {
        val previous = System.getProperty(ExecutionSandboxes.PROP_SANDBOX)
        if (value == null) System.clearProperty(ExecutionSandboxes.PROP_SANDBOX)
        else System.setProperty(ExecutionSandboxes.PROP_SANDBOX, value)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(ExecutionSandboxes.PROP_SANDBOX)
            else System.setProperty(ExecutionSandboxes.PROP_SANDBOX, previous)
        }
    }

    private fun marker(dataDir: Path): Path = dataDir.resolve(SandboxDefaults.MARKER)

    @Test
    @DisplayName("a fresh installation starts with the platform backend and persists the decision")
    fun freshInstallationIsDefaultedAndPersisted() = withSandboxProperty(null) {
        val dataDir = tempDir.resolve("new-install").resolve(".browser4")
        val expected = SandboxDefaults.defaultForPlatform()

        val id = SandboxDefaults.apply(dataDir)

        assertEquals(expected, id)
        assertEquals(id, System.getProperty(ExecutionSandboxes.PROP_SANDBOX), "the default is published")
        assertEquals(id, Files.readString(marker(dataDir)), "the decision is persisted")
        // A later start must not re-derive from the filesystem: the data dir now exists, yet the
        // marker keeps the decision.
        assertEquals(id, SandboxDefaults.apply(dataDir))
    }

    @Test
    @DisplayName("an existing installation keeps off")
    fun existingInstallationKeepsOff() = withSandboxProperty(null) {
        val dataDir = Files.createDirectories(tempDir.resolve("old-install").resolve(".browser4"))

        val id = SandboxDefaults.apply(dataDir)

        assertEquals("off", id)
        assertEquals("off", Files.readString(marker(dataDir)))
    }

    @Test
    @DisplayName("an explicit value wins and is not recorded as the installation default")
    fun explicitValueWins() = withSandboxProperty("windows-acl") {
        val dataDir = tempDir.resolve("fresh").resolve(".browser4")

        assertEquals("windows-acl", SandboxDefaults.apply(dataDir))
        assertFalse(Files.exists(marker(dataDir)), "an explicit value is not persisted")
        assertFalse(Files.exists(dataDir), "the defaulting must not create a data dir it did not need")
    }

    @Test
    @DisplayName("a blank property counts as absent")
    fun blankPropertyIsAbsent() = withSandboxProperty("   ") {
        val dataDir = Files.createDirectories(tempDir.resolve("ws").resolve(".browser4"))

        assertEquals("off", SandboxDefaults.apply(dataDir))
    }

    @Test
    @DisplayName("only a working backend may be a default: Windows today, off elsewhere until P2")
    fun platformDefaultIsRestrictedToImplementedBackends() {
        assertEquals("windows-acl", SandboxDefaults.defaultForPlatform("Windows 11"))
        assertEquals("off", SandboxDefaults.defaultForPlatform("Linux"))
        assertEquals("off", SandboxDefaults.defaultForPlatform("Mac OS X"))
        assertTrue(SandboxDefaults.defaultForPlatform("Windows 11") in ExecutionSandboxes.KNOWN_IDS)
    }
}
