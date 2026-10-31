package ai.platon.pulsar.coding

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Tests for [AccessApprovals]: the non-blocking request, dedupe, the operator capability it issues,
 * lazy expiry and the rails an approval can never cross (plan P4-1).
 */
@DisplayName("Operator approvals")
class AccessApprovalsTest {

    @AfterEach
    fun clearProperties() {
        System.clearProperty(AgentAccessPolicy.PROP_ACCESS_UNATTENDED)
    }

    private fun approvals(
        root: Path,
        policy: AgentAccessPolicy = AgentAccessPolicy(preset = AccessPreset.READ_BUILD),
        clock: () -> Instant = Instant::now,
    ) = AccessApprovals("task-1", { policy }, { root }, clock)

    private fun pathTarget(root: Path, name: String) = Target.PathTarget(root.resolve(name))

    @Test
    @DisplayName("a request returns immediately as pending and does not block the task")
    fun testRequestIsPending(@TempDir root: Path) {
        Files.writeString(root.resolve("a.txt"), "hello")
        PermissionAudit.reset()
        val approvals = approvals(root)

        val request = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"), "fix the import", 600)

        assertEquals(AccessState.PENDING, request.state)
        assertEquals(Effect.FS_WRITE, request.effect)
        assertEquals(600L, request.ttlSeconds)
        assertEquals("fix the import", request.reason)
        assertNull(request.capability, "a pending request grants nothing yet")
        assertTrue(request.id in approvals.pending().map { it.id })
        assertTrue(approvals.activeCapabilities().isEmpty())
        assertTrue(
            PermissionAudit.snapshot().containsKey("coding.requestAccess:${PermissionAudit.CODE_ACCESS_REQUESTED}"),
            "the request must be audited",
        )
    }

    @Test
    @DisplayName("the same effect + target is deduplicated")
    fun testDuplicateRequestIsReused(@TempDir root: Path) {
        val approvals = approvals(root)

        val first = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"))
        val again = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"))
        val other = approvals.request(Effect.FS_WRITE, pathTarget(root, "b.txt"))

        assertEquals(first.id, again.id, "a retry must not flood the queue")
        assertTrue(again.id != other.id, "a different target is a different request")
        assertEquals(2, approvals.pending().size)
    }

    @Test
    @DisplayName("an approval issues one OPERATOR capability and stops the request being pending")
    fun testApprovalIssuesOperatorCapability(@TempDir root: Path) {
        PermissionAudit.reset()
        val approvals = approvals(root)

        val request = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"), "fix the import")
        val decided = approvals.approve(request.id, AccessSource.REST)!!

        assertEquals(AccessState.APPROVED, decided.state)
        val capability = decided.capability!!
        assertEquals(CapabilitySource.OPERATOR, capability.source)
        assertEquals(setOf(Effect.FS_WRITE), capability.effects)
        assertNull(capability.expiresAt, "no ttl means the grant lasts until the task ends")
        assertEquals(listOf(capability), approvals.activeCapabilities())
        assertFalse(request.id in approvals.pending().map { it.id })
        assertTrue(
            PermissionAudit.snapshot().containsKey("coding.requestAccess:${PermissionAudit.CODE_ACCESS_APPROVED}"),
            "the approval must be audited",
        )
    }

    @Test
    @DisplayName("a ttl grant expires lazily on the next decision and allows a fresh request")
    fun testGrantExpiresLazily(@TempDir root: Path) {
        PermissionAudit.reset()
        var now = Instant.now()
        val approvals = approvals(root, clock = { now })

        val request = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"), ttlSeconds = 60)
        approvals.approve(request.id, AccessSource.REST)
        assertEquals(1, approvals.activeCapabilities().size)

        now = now.plus(Duration.ofSeconds(61))
        assertTrue(approvals.activeCapabilities().isEmpty(), "an expired grant must not decide anything")
        assertEquals(AccessState.EXPIRED, request.state)
        assertTrue(
            PermissionAudit.snapshot().containsKey("coding.requestAccess:${PermissionAudit.CODE_ACCESS_EXPIRED}"),
            "expiry must be audited, not silently dropped",
        )

        val fresh = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"))
        assertTrue(fresh.id != request.id, "after expiry the model may ask again")
        assertEquals(AccessState.PENDING, fresh.state)
    }

    @Test
    @DisplayName("an approval can never cross denyTools")
    fun testApprovalCannotCrossDenyTools(@TempDir root: Path) {
        val policy = AgentAccessPolicy(preset = AccessPreset.READ_BUILD, denyTools = setOf("coding.*"))
        val approvals = approvals(root, policy)

        val request = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"))
        val decided = approvals.approve(request.id, AccessSource.REST)!!

        assertEquals(AccessState.DENIED, decided.state)
        assertNull(decided.capability)
        assertTrue(approvals.activeCapabilities().isEmpty())
    }

    @Test
    @DisplayName("an approval can never cross protected paths, credential paths or the workspace boundary")
    fun testApprovalCannotCrossPathRails(@TempDir root: Path) {
        val approvals = approvals(root)

        val governance = approvals.request(Effect.FS_WRITE, pathTarget(root, "pom.xml"))
        assertEquals(AccessState.DENIED, approvals.approve(governance.id, AccessSource.REST)!!.state)

        val workflow = approvals.request(Effect.FS_WRITE, pathTarget(root, ".github/workflows/ci.yml"))
        assertEquals(AccessState.DENIED, approvals.approve(workflow.id, AccessSource.REST)!!.state)

        val outside = approvals.request(Effect.FS_WRITE, Target.PathTarget(root.parent.resolve("escape.txt")))
        assertEquals(AccessState.DENIED, approvals.approve(outside.id, AccessSource.REST)!!.state)

        val secret = approvals.request(Effect.SECRET_READ, pathTarget(root, "id_rsa"))
        assertEquals(AccessState.DENIED, approvals.approve(secret.id, AccessSource.REST)!!.state)
    }

    @Test
    @DisplayName("an approval may pick a whitelisted command but cannot add an arbitrary program")
    fun testApprovalCannotAddArbitraryCommand(@TempDir root: Path) {
        val approvals = approvals(root)

        val devTool = approvals.request(Effect.FS_EXEC, Target.CommandTarget("git"))
        assertEquals(AccessState.APPROVED, approvals.approve(devTool.id, AccessSource.REST)!!.state)

        val arbitrary = approvals.request(Effect.FS_EXEC, Target.CommandTarget("curl"))
        assertEquals(AccessState.DENIED, approvals.approve(arbitrary.id, AccessSource.REST)!!.state)
    }

    @Test
    @DisplayName("a host approval is not fenced by the path rails")
    fun testHostApproval(@TempDir root: Path) {
        val approvals = approvals(root)

        val request = approvals.request(Effect.NET_CONNECT, Target.HostTarget("example.com"))
        val decided = approvals.approve(request.id, AccessSource.REST)!!

        assertEquals(AccessState.APPROVED, decided.state)
        assertEquals(setOf(Effect.NET_CONNECT), decided.capability!!.effects)
    }

    @Test
    @DisplayName("a denial issues nothing and is audited with its channel")
    fun testDenyIssuesNothing(@TempDir root: Path) {
        PermissionAudit.reset()
        val approvals = approvals(root)

        val request = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"))
        val decided = approvals.deny(request.id, AccessSource.CLI, "not now")!!

        assertEquals(AccessState.DENIED, decided.state)
        assertNull(decided.capability)
        assertTrue(approvals.activeCapabilities().isEmpty())
        assertTrue(
            PermissionAudit.snapshot().containsKey("coding.requestAccess:${PermissionAudit.CODE_ACCESS_DENIED}"),
            "the denial must be audited",
        )
    }

    @Test
    @DisplayName("describe reports the pending queue, the grants in force and the unattended default")
    fun testDescribe(@TempDir root: Path) {
        val approvals = approvals(root)
        val pending = approvals.request(Effect.FS_WRITE, pathTarget(root, "a.txt"), "fix the import")
        val approved = approvals.request(Effect.FS_EXEC, Target.CommandTarget("git"))
        approvals.approve(approved.id, AccessSource.REST)

        val report = approvals.describe()

        assertTrue(report.contains("Pending access requests (1):"), report)
        assertTrue(report.contains(pending.id), report)
        assertTrue(report.contains("Operator grants in force (1):"), report)
        assertTrue(report.contains("Unattended default: full"), report)

        System.setProperty(AgentAccessPolicy.PROP_ACCESS_UNATTENDED, "deny")
        assertEquals(
            "Unattended default: deny",
            approvals.describe().lineSequence().first { it.startsWith("Unattended default:") },
        )
    }
}
