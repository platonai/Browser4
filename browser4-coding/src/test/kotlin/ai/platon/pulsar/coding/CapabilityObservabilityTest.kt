package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.BeforeEach

/**
 * Plan B3/B5 — the observation layer.
 *
 * These tests pin the *facts* the capability model records while nothing is enforced yet:
 * [PermissionAudit.writeEvent] must count separately from refusals, the file tools must record a
 * real out-of-scope write (they resolve the concrete path), and the shell must record only a
 * spelling-level marker because it cannot tell a read from a write.
 *
 * Assertions use deltas against [PermissionAudit.snapshot] rather than absolute counts: the audit
 * object is process-wide and other test classes legitimately add to it.
 */
class CapabilityObservabilityTest {

    @TempDir
    lateinit var tempDir: Path

    /** Both boundaries canonicalize their root, so the workspace must exist before construction. */
    @BeforeEach
    fun createWorkspace() {
        Files.createDirectories(tempDir.resolve("ws"))
    }

    private fun events(key: String, before: Map<String, Int>): Int {
        val after = PermissionAudit.snapshot()
        return (after[key] ?: 0) - (before[key] ?: 0)
    }

    private fun fs(allowExternalAccess: Boolean): CodingAgentFileSystem =
        CodingAgentFileSystem(
            workspaceRoot = tempDir.resolve("ws"),
            allowExternalAccess = allowExternalAccess,
        )

    private fun shell(
        allowExternalAccess: Boolean = false,
        allowNetwork: Boolean = false,
    ): CodingAgentShell = CodingAgentShell(
        baseDir = tempDir.resolve("ws"),
        allowExternalAccess = allowExternalAccess,
        allowNetwork = allowNetwork,
    )

    // ==================== B3: audit primitive ====================

    @Test
    @DisplayName("writeEvent counts under tool:code without touching the refusal path")
    fun writeEventIsCounted() {
        val before = PermissionAudit.snapshot()
        PermissionAudit.writeEvent("test.tool", PermissionAudit.CODE_OUT_OF_SCOPE_WRITE, "detail")
        PermissionAudit.writeEvent("test.tool", PermissionAudit.CODE_OUT_OF_SCOPE_WRITE, "detail again")

        assertEquals(2, events("test.tool:${PermissionAudit.CODE_OUT_OF_SCOPE_WRITE}", before))
    }

    @Test
    @DisplayName("a refusal and an event for the same tool:code both count")
    fun refusalAndEventCoexist() {
        val before = PermissionAudit.snapshot()
        PermissionAudit.denied("test.tool", PermissionAudit.CODE_SCOPE_UNCERTAIN, "refused")
        PermissionAudit.writeEvent("test.tool", PermissionAudit.CODE_SCOPE_UNCERTAIN, "observed")

        assertEquals(2, events("test.tool:${PermissionAudit.CODE_SCOPE_UNCERTAIN}", before))
    }

    @Test
    @DisplayName("reset clears the counters")
    fun resetClearsCounters() {
        PermissionAudit.writeEvent("test.tool", PermissionAudit.CODE_NETWORK_TARGET, "detail")
        PermissionAudit.reset()
        assertTrue(PermissionAudit.snapshot().isEmpty(), "snapshot: ${PermissionAudit.snapshot()}")
    }

    // ==================== B5: file system ====================

    @Test
    @DisplayName("an allowed out-of-scope write is recorded as a fact")
    fun outOfScopeWriteIsRecorded() = runBlocking {
        val outside = tempDir.resolve("outside.txt")
        val before = PermissionAudit.snapshot()

        val result = fs(allowExternalAccess = true).writeFile(outside.toString(), "x")
        assertTrue(result.startsWith("✓"), "result: $result")
        assertEquals("x", Files.readString(outside))

        assertEquals(1, events("coding.fs:${PermissionAudit.CODE_OUT_OF_SCOPE_WRITE}", before))
    }

    @Test
    @DisplayName("an in-scope write records nothing")
    fun inScopeWriteIsNotRecorded() = runBlocking {
        val before = PermissionAudit.snapshot()

        val result = fs(allowExternalAccess = true).writeFile("inside.txt", "x")
        assertTrue(result.startsWith("✓"), "result: $result")

        assertEquals(0, events("coding.fs:${PermissionAudit.CODE_OUT_OF_SCOPE_WRITE}", before))
    }

    @Test
    @DisplayName("a refused external write records the refusal, not the write fact")
    fun refusedExternalWriteIsNotAWriteFact() = runBlocking {
        val before = PermissionAudit.snapshot()

        val result = fs(allowExternalAccess = false).writeFile(tempDir.resolve("outside.txt").toString(), "x")
        assertTrue(result.startsWith("Error:"), "result: $result")

        assertEquals(1, events("coding.fs:${PermissionAudit.CODE_PATH_OUTSIDE_WORKSPACE}", before))
        assertEquals(0, events("coding.fs:${PermissionAudit.CODE_OUT_OF_SCOPE_WRITE}", before))
    }

    // ==================== B3/B5: shell advisories ====================

    @Test
    @DisplayName("isAllowed stays a dry run and records nothing")
    fun isAllowedRecordsNothing() {
        val outside = tempDir.root.resolve("b4-capability-outside.csv")
        val before = PermissionAudit.snapshot()

        shell(allowExternalAccess = true).isAllowed("type \"$outside\"")

        assertEquals(0, events("coding.shell:${PermissionAudit.CODE_SCOPE_UNCERTAIN}", before))
    }

    @Test
    @DisplayName("an executed command naming an outside path records scope uncertainty")
    fun executedExternalPathIsRecorded() = runBlocking {
        val outside = tempDir.root.resolve("b4-capability-outside.csv")
        val before = PermissionAudit.snapshot()

        shell(allowExternalAccess = true).execute("type \"$outside\"")

        assertEquals(1, events("coding.shell:${PermissionAudit.CODE_SCOPE_UNCERTAIN}", before))
    }

    @Test
    @DisplayName("an executed network command is recorded when the network tier is on")
    fun executedNetworkCommandIsRecorded() = runBlocking {
        val before = PermissionAudit.snapshot()

        shell(allowNetwork = true).execute("curl --version")

        assertEquals(1, events("coding.shell:${PermissionAudit.CODE_NETWORK_TARGET}", before))
    }
}
