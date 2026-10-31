package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for [AccessPolicyFile] and [AccessApprovals.loadPolicyFile] (plan P4-4): the
 * strategy-file channel that pre-approves grants for unattended jobs.
 */
@DisplayName("Access policy file (P4-4)")
class AccessPolicyFileTest {

    private fun approvals(
        root: Path,
        policy: AgentAccessPolicy = AgentAccessPolicy(preset = AccessPreset.READ_BUILD),
    ) = AccessApprovals("task-file", { policy }, { root })

    private fun writePolicy(root: Path, json: String): Path {
        val path = root.resolve(AccessPolicyFile.RELATIVE_PATH)
        Files.createDirectories(path.parent)
        Files.writeString(path, json)
        return path
    }

    @Test
    @DisplayName("a missing file means no pre-approvals")
    fun testMissingFile(@TempDir root: Path) {
        val approvals = approvals(root)

        assertEquals(0, approvals.loadPolicyFile())
        assertTrue(approvals.all().isEmpty())
        assertTrue(approvals.activeCapabilities().isEmpty())
    }

    @Test
    @DisplayName("entries are pre-approved with source=file and take effect immediately")
    fun testEntriesArePreApproved(@TempDir root: Path) {
        PermissionAudit.reset()
        val path = writePolicy(
            root,
            """
            {
              "grants": [
                { "effect": "fs.write", "target": "src/generated", "ttlSeconds": 3600, "reason": "codegen output" },
                { "effect": "fs_exec", "target": "git" }
              ]
            }
            """.trimIndent(),
        )
        val approvals = approvals(root)

        val approved = approvals.loadPolicyFile(path)

        assertEquals(2, approved)
        val all = approvals.all()
        assertEquals(2, all.size)
        assertTrue(all.all { it.state == AccessState.APPROVED })
        assertTrue(all.all { it.requestedBy == AccessSource.FILE }, "the audit source must name the file channel")

        val write = all.first { it.effect == Effect.FS_WRITE }
        assertEquals(root.resolve("src/generated").normalize(), (write.target as Target.PathTarget).path)
        assertEquals(3600L, write.ttlSeconds)
        assertEquals("codegen output", write.reason)
        assertNotNull(write.capability?.expiresAt, "a ttl must become an expiry on the capability")

        val exec = all.first { it.effect == Effect.FS_EXEC }
        assertEquals("git", (exec.target as Target.CommandTarget).normalized)

        assertEquals(2, approvals.activeCapabilities().size)
        assertTrue(
            PermissionAudit.snapshot().containsKey("coding.requestAccess:${PermissionAudit.CODE_ACCESS_APPROVED}"),
            "pre-approvals are audited like any other approval",
        )
    }

    @Test
    @DisplayName("an entry that crosses the workspace boundary is denied and audited, not silently dropped")
    fun testRailCrossingEntryIsDenied(@TempDir root: Path) {
        PermissionAudit.reset()
        writePolicy(root, """{ "grants": [ { "effect": "fs.write", "target": "../outside.txt" } ] }""")
        val approvals = approvals(root)

        val approved = approvals.loadPolicyFile()

        assertEquals(0, approved)
        val request = approvals.all().single()
        assertEquals(AccessState.DENIED, request.state)
        assertNull(request.capability)
        assertTrue(approvals.activeCapabilities().isEmpty())
        assertTrue(
            PermissionAudit.snapshot().containsKey("coding.requestAccess:${PermissionAudit.CODE_ACCESS_DENIED}"),
            "the rail refusal must be audited",
        )
    }

    @Test
    @DisplayName("an entry naming the protected .browser4 directory can never be pre-approved")
    fun testProtectedDirectoryEntryIsDenied(@TempDir root: Path) {
        writePolicy(root, """{ "grants": [ { "effect": "fs.write", "target": ".browser4/snapshots" } ] }""")
        val approvals = approvals(root)

        assertEquals(0, approvals.loadPolicyFile())
        assertEquals(AccessState.DENIED, approvals.all().single().state)
    }

    @Test
    @DisplayName("malformed JSON, a missing grants array and bad entries are all skipped")
    fun testMalformedInput(@TempDir root: Path) {
        val notJson = writePolicy(root, "{ not json")
        assertEquals(0, approvals(root).loadPolicyFile(notJson))

        val noGrants = writePolicy(root, """{ "other": true }""")
        assertEquals(0, approvals(root).loadPolicyFile(noGrants))

        val badEntries = writePolicy(
            root,
            """{ "grants": [ {"effect": ""}, {"target": "y"}, {"effect": "nope", "target": "x"} ] }""",
        )
        val approvals = approvals(root)
        assertEquals(0, approvals.loadPolicyFile(badEntries))
        assertTrue(approvals.all().isEmpty(), "skipped entries must not appear in the queue")
    }
}
