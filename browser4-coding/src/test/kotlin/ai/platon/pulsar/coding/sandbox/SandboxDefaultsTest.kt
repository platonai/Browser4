package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The sandbox default is a **security** default, not a preference.
 *
 * Plan P3-4 (D1) applied the boundary to new installations only; the 2026-11-16 review of run
 * `966a5f2e` showed that this leaves every older installation with `off` — where the string layer is
 * the only boundary, and an inline script walks straight through it. The installation therefore
 * upgrades once, with an explicit marker schema, but **only when the backend is actually ready**:
 * switching on a backend that cannot provision would fail every command instead of protecting it.
 *
 * Both the platform backend and its readiness probe are injected so every rule is testable on every
 * host.
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

    /**
     * `apply()` is global state (see [SandboxDefaults.resetForTests]). Several cases below apply an
     * explicit id, and leaving that behind would make every later `resolveForMode` in this JVM
     * resolve to it — a class-order dependency, not a behaviour.
     */
    @AfterEach
    fun resetPublishedDefault() = SandboxDefaults.resetForTests()

    private fun marker(dataDir: Path): Path = dataDir.resolve(SandboxDefaults.MARKER)
    private fun newDataDir(name: String): Path = tempDir.resolve(name).resolve(".browser4")

    private fun existingDataDir(name: String): Path = Files.createDirectories(newDataDir(name))

    private val ready: (String) -> Boolean = { true }
    private val notReady: (String) -> Boolean = { false }

    @Test
    @DisplayName("a ready platform backend is chosen and persisted as a versioned decision")
    fun freshInstallationIsDefaultedAndPersisted() = withSandboxProperty(null) {
        val dataDir = newDataDir("new-install")

        val id = SandboxDefaults.apply(dataDir, "windows-low-il", ready)

        assertEquals("windows-low-il", id)
        assertEquals(id, System.getProperty(ExecutionSandboxes.PROP_SANDBOX), "the default is published")
        assertEquals("v2:windows-low-il", Files.readString(marker(dataDir)), "the decision is versioned")
        // A later start must not re-derive from the filesystem: the data dir now exists, yet the
        // marker keeps the decision.
        assertEquals(id, SandboxDefaults.apply(dataDir, "off", notReady))
    }

    @Test
    @DisplayName("a backend that is not provisioned stays off, is not pinned, and upgrades once ready")
    fun unprovisionedBackendStaysOffUntilReady() = withSandboxProperty(null) {
        val dataDir = newDataDir("unprovisioned")

        val first = SandboxDefaults.apply(dataDir, "windows-acl", notReady)

        assertEquals("off", first, "an unusable backend must not be switched on")
        assertEquals("off", ExecutionSandboxes.configuredId(), "the effective backend stays off")
        assertFalse(Files.exists(marker(dataDir)), "nothing is pinned, so the next start re-probes")
        assertFalse(Files.exists(dataDir), "the failed probe must not create a data dir it did not need")

        // An administrator provisions the account; the next start upgrades automatically.
        assertEquals("windows-acl", SandboxDefaults.apply(dataDir, "windows-acl", ready))
        assertEquals("v2:windows-acl", Files.readString(marker(dataDir)))
    }

    @Test
    @DisplayName("a legacy bare 'off' is upgraded when the backend is ready: it was never a decision")
    fun legacyOffIsUpgraded() = withSandboxProperty(null) {
        val dataDir = existingDataDir("legacy-off")
        Files.writeString(marker(dataDir), "off")

        val id = SandboxDefaults.apply(dataDir, "windows-low-il", ready)

        assertEquals("windows-low-il", id, "an upgraded installation must be protected")
        assertEquals("windows-low-il", System.getProperty(ExecutionSandboxes.PROP_SANDBOX))
        assertEquals("v2:windows-low-il", Files.readString(marker(dataDir)), "the upgrade is pinned")
        assertEquals("windows-low-il", SandboxDefaults.apply(dataDir, "off", notReady), "the upgrade is stable")
    }

    @Test
    @DisplayName("a legacy bare 'off' stays off while the backend is unprovisioned, marker untouched")
    fun legacyOffStaysOffWhileUnprovisioned() = withSandboxProperty(null) {
        val dataDir = existingDataDir("legacy-off-unprovisioned")
        Files.writeString(marker(dataDir), "off")

        assertEquals("off", SandboxDefaults.apply(dataDir, "windows-acl", notReady))
        assertEquals("off", Files.readString(marker(dataDir)), "an unprovisioned probe must not pin off")
    }

    @Test
    @DisplayName("an installation with no marker at all is upgraded when the backend is ready")
    fun undecidedInstallationIsUpgraded() = withSandboxProperty(null) {
        val dataDir = existingDataDir("undecided")

        val id = SandboxDefaults.apply(dataDir, "windows-low-il", ready)

        assertEquals("windows-low-il", id)
        assertEquals("v2:windows-low-il", Files.readString(marker(dataDir)))
    }

    @Test
    @DisplayName("a versioned 'off' is an operator decision and is replayed verbatim")
    fun versionedOffIsKept() = withSandboxProperty(null) {
        val dataDir = existingDataDir("operator-off")
        Files.writeString(marker(dataDir), "v2:off")

        assertEquals("off", SandboxDefaults.apply(dataDir, "windows-acl", ready))
        assertEquals("v2:off", Files.readString(marker(dataDir)), "an explicit off is never upgraded")
    }

    @Test
    @DisplayName("a later marker schema is honoured, not overwritten")
    fun futureSchemaIsHonoured() = withSandboxProperty(null) {
        val dataDir = existingDataDir("future")
        Files.writeString(marker(dataDir), "v3:off")

        assertEquals("off", SandboxDefaults.apply(dataDir, "windows-acl", ready))
        assertEquals("v3:off", Files.readString(marker(dataDir)))
    }

    @Test
    @DisplayName("a legacy marker that names a boundary is kept and pinned")
    fun legacyBoundaryMarkerIsKept() = withSandboxProperty(null) {
        val dataDir = existingDataDir("legacy-on")
        Files.writeString(marker(dataDir), "windows-acl")

        assertEquals("windows-acl", SandboxDefaults.apply(dataDir, "off", notReady))
        assertEquals("v2:windows-acl", Files.readString(marker(dataDir)), "pinned so it cannot drift")
    }

    @Test
    @DisplayName("a platform without an implemented backend stays off and says so in the marker")
    fun platformWithoutBackendStaysOff() = withSandboxProperty(null) {
        val dataDir = existingDataDir("linux-like")
        Files.writeString(marker(dataDir), "off")

        assertEquals("off", SandboxDefaults.apply(dataDir, "off", ready))
        assertEquals("v2:off", Files.readString(marker(dataDir)))
    }

    @Test
    @DisplayName("an explicit value wins and is not recorded as the installation default")
    fun explicitValueWins() = withSandboxProperty("windows-acl") {
        val dataDir = newDataDir("fresh")

        assertEquals("windows-acl", SandboxDefaults.apply(dataDir, "off", notReady))
        assertFalse(Files.exists(marker(dataDir)), "an explicit value is not persisted")
        assertFalse(Files.exists(dataDir), "the defaulting must not create a data dir it did not need")
    }

    @Test
    @DisplayName("an explicit off beats an upgrade, so the historical behaviour stays reachable")
    fun explicitOffWinsOverUpgrade() = withSandboxProperty("off") {
        val dataDir = existingDataDir("explicit-off")
        Files.writeString(marker(dataDir), "off")

        assertEquals("off", SandboxDefaults.apply(dataDir, "windows-acl", ready))
        assertEquals("off", Files.readString(marker(dataDir)), "an explicit off never rewrites the marker")
    }

    @Test
    @DisplayName("a blank property counts as absent")
    fun blankPropertyIsAbsent() = withSandboxProperty("   ") {
        val dataDir = existingDataDir("ws")

        assertEquals("windows-acl", SandboxDefaults.apply(dataDir, "windows-acl", ready))
    }

    @Test
    @DisplayName("only a working backend may be a default: Windows today, off elsewhere until P2")
    fun platformDefaultIsRestrictedToImplementedBackends() {
        // `windows-acl` is still the shipped default; `windows-low-il` is a per-mode backend and
        // becomes the coding default together with the mode wiring.
        assertEquals("windows-acl", SandboxDefaults.defaultForPlatform("Windows 11"))
        assertEquals("off", SandboxDefaults.defaultForPlatform("Linux"))
        assertEquals("off", SandboxDefaults.defaultForPlatform("Mac OS X"))
        assertTrue(SandboxDefaults.defaultForPlatform("Windows 11") in ExecutionSandboxes.KNOWN_IDS)
    }
}
