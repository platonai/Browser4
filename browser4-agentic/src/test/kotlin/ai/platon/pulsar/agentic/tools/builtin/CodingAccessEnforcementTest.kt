package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.coding.AccessApprovals
import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AccessSource
import ai.platon.pulsar.coding.AccessState
import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.Effect
import ai.platon.pulsar.coding.PermissionAudit
import ai.platon.pulsar.coding.Target
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Enforcement tests for the access presets: the executor's method gate (E3), the read-only file
 * system (E1) and the build-only shell split (E2).
 *
 * These use real components (no mocks) because the point is the wiring between the policy,
 * [CodingToolExecutor.Target] and the components the factory builds. A refusal surfaces as a
 * failed [TcEvaluate] (the executor wraps exceptions), not as a thrown exception.
 */
class CodingAccessEnforcementTest {

    private val executor = CodingToolExecutor()

    private fun target(root: Path, preset: AccessPreset) =
        CodingToolExecutor.target(root, AgentAccessPolicy(preset = preset))

    /**
     * A target wired to [approvals] the same way `AgentToolManager` does it: the policy reads the
     * live grants, and the executor can answer `coding.requestAccess`.
     */
    private fun target(root: Path, policy: AgentAccessPolicy, approvals: AccessApprovals) =
        CodingToolExecutor
            .target(root, policy.copy(operatorGrants = { approvals.activeCapabilities() }))
            .copy(approvals = approvals)

    private fun call(
        target: CodingToolExecutor.Target,
        method: String,
        args: Map<String, Any?> = emptyMap(),
    ): TcEvaluate = runBlocking {
        executor.callFunctionOn(
            ToolCall(domain = "coding", method = method, arguments = args.toMutableMap()),
            target,
        )
    }

    private fun assertDenied(method: String, result: TcEvaluate) {
        assertFalse(result.success, "expected '$method' to be refused, got: ${result.value}")
        val message = result.exception?.message ?: ""
        assertTrue(message.contains("under access preset"), message)
        assertTrue(message.contains("coding.$method"), message)
    }

    private fun valueOf(result: TcEvaluate): String {
        assertTrue(result.success, "call failed: ${result.exception}")
        return result.value?.toString() ?: ""
    }

    @Test
    @DisplayName("read preset refuses a mutating tool with a structured denial and writes nothing")
    fun testReadPresetDeniesWrite(@TempDir root: Path) {
        val result = call(target(root, AccessPreset.READ), "write", mapOf("path" to "a.txt", "content" to "x"))
        assertDenied("write", result)
        // P4-3: the text only says what is needed and where to ask — never a preset/JVM switch.
        val message = result.exception?.message.orEmpty()
        assertTrue(message.contains("needed=fs.write"), message)
        assertTrue(message.contains("coding.requestAccess"), message)
        assertFalse(message.contains("access="), message)
        assertFalse(message.contains("-Dbrowser4"), message)
        assertFalse(Files.exists(root.resolve("a.txt")), "a denied write must not touch the disk")
    }

    @Test
    @DisplayName("read preset still serves read tools")
    fun testReadPresetAllowsRead(@TempDir root: Path) {
        Files.writeString(root.resolve("a.txt"), "hello")
        val result = call(target(root, AccessPreset.READ), "read", mapOf("path" to "a.txt"))
        assertEquals("hello", valueOf(result))
    }

    @Test
    @DisplayName("an operator approval lets a refused call through at every layer")
    fun testOperatorApprovalReachesEveryLayer(@TempDir root: Path) {
        val policy = AgentAccessPolicy(preset = AccessPreset.READ)
        val approvals = AccessApprovals("task-1", { policy }, { root })
        val t = target(root, policy, approvals)

        val request = approvals.request(Effect.FS_WRITE, Target.PathTarget(root.resolve("a.txt")))
        assertEquals(AccessState.APPROVED, approvals.approve(request.id, AccessSource.REST)!!.state)

        // E3 lets the call through, and E1 must agree — otherwise the file system would refuse
        // what the tool gate just approved.
        val write = call(t, "write", mapOf("path" to "a.txt", "content" to "x"))
        assertTrue(write.success, "approved write failed: ${write.exception}")
        assertEquals("x", Files.readString(root.resolve("a.txt")))

        // The grant is effect-level: `fs.write` approves mutation, never the shell.
        assertDenied("shell", call(t, "shell", mapOf("command" to "echo hi")))
        val blocked = runBlocking { t.shell.execute("echo hi") }
        assertTrue(blocked.contains("disabled by the access policy"), blocked)
    }

    @Test
    @DisplayName("an fs.exec approval grants the shell, and an arbitrary program is still refused")
    fun testOperatorApprovalGrantsShell(@TempDir root: Path) {
        val policy = AgentAccessPolicy(preset = AccessPreset.READ)
        val approvals = AccessApprovals("task-1", { policy }, { root })
        val t = target(root, policy, approvals)

        val request = approvals.request(Effect.FS_EXEC, Target.CommandTarget("echo approved"))
        assertEquals(AccessState.APPROVED, approvals.approve(request.id, AccessSource.REST)!!.state)

        val shell = call(t, "shell", mapOf("command" to "echo approved"))
        assertTrue(shell.success, "approved shell failed: ${shell.exception}")
        assertTrue(valueOf(shell).contains("approved"), valueOf(shell))

        // A program outside the dev-command whitelist can never be approved, even on request.
        val arbitrary = approvals.request(Effect.FS_EXEC, Target.CommandTarget("curl example.com"))
        assertEquals(
            AccessState.DENIED,
            approvals.approve(arbitrary.id, AccessSource.REST)!!.state,
            "an approval must not add a program the shell would refuse",
        )
    }

    @Test
    @DisplayName("a workspace strategy file pre-approves grants for an unattended task (P4-4)")
    fun testStrategyFilePreApproves(@TempDir root: Path) {
        val policy = AgentAccessPolicy(preset = AccessPreset.READ)
        val approvals = AccessApprovals("task-1", { policy }, { root })
        Files.createDirectories(root.resolve("src/generated"))
        val policyFile = root.resolve(".browser4/access-policy.json")
        Files.createDirectories(policyFile.parent)
        Files.writeString(
            policyFile,
            """{ "grants": [ { "effect": "fs.write", "target": "src/generated", "reason": "codegen" } ] }""",
        )

        assertEquals(1, approvals.loadPolicyFile())

        val t = target(root, policy, approvals)
        val write = call(t, "write", mapOf("path" to "src/generated/out.txt", "content" to "x"))
        assertTrue(write.success, "pre-approved write failed: ${write.exception}")
        assertEquals("x", Files.readString(root.resolve("src/generated/out.txt")))

        // The grant is effect-level: the shell stays refused, exactly like an operator approval.
        assertDenied("shell", call(t, "shell", mapOf("command" to "echo hi")))
    }

    @Test
    @DisplayName("read preset refuses the build entry points and the model shell executes nothing")
    fun testReadPresetHasNoBuild(@TempDir root: Path) {
        val t = target(root, AccessPreset.READ)
        assertDenied("mvnBuild", call(t, "mvnBuild"))

        // The model-facing shell is disabled by policy: it refuses before validation.
        val blocked = runBlocking { t.shell.execute("mvn -v") }
        assertTrue(blocked.contains("disabled by the access policy"), blocked)
    }

    @Test
    @DisplayName("read-build allows the build shell but not shell execution")
    fun testReadBuildSplitsShells(@TempDir root: Path) {
        val t = target(root, AccessPreset.READ_BUILD)
        val buildShell = t.buildShell
            ?: throw AssertionError("read-build must create a build shell")
        assertNotSame(t.shell, buildShell, "the build shell must not be the model-facing shell")

        // The build shell is build-only: interpreters are refused before anything runs.
        val refused = runBlocking { buildShell.execute("python -c print(1)") }
        assertTrue(refused.contains("not allowed"), refused)

        // The executor refuses the shell methods under the same preset.
        assertDenied("shell", call(t, "shell", mapOf("command" to "mvn -v")))
    }

    @Test
    @DisplayName("read-build refuses file mutation through the executor")
    fun testReadBuildDeniesMutation(@TempDir root: Path) {
        assertDenied(
            "write",
            call(target(root, AccessPreset.READ_BUILD), "write", mapOf("path" to "a.txt", "content" to "x")),
        )
    }

    @Test
    @DisplayName("the read-only shape of protect is allowed while its mutating shape is refused")
    fun testProtectMutatingFormIsDenied(@TempDir root: Path) {
        Files.writeString(root.resolve("a.txt"), "hello")
        val t = target(root, AccessPreset.READ_BUILD)
        // Listing protections (no `path`) is a read.
        val listing = valueOf(call(t, "protect"))
        assertTrue(listing.contains("protect", ignoreCase = true), listing)
        // Changing them is not.
        assertDenied("protect", call(t, "protect", mapOf("path" to "a.txt", "on" to "false")))
    }

    @Test
    @DisplayName("write preset mutates files but has no shell")
    fun testWritePresetMutatesWithoutShell(@TempDir root: Path) {
        val t = target(root, AccessPreset.WRITE)
        val written = valueOf(call(t, "write", mapOf("path" to "a.txt", "content" to "hi")))
        assertTrue(written.contains("✓"), written)
        assertEquals("hi", Files.readString(root.resolve("a.txt")))
        assertDenied("shell", call(t, "shell", mapOf("command" to "mvn -v")))
    }

    @Test
    @DisplayName("full keeps one shared shell instance for both roles")
    fun testFullSharesOneShell(@TempDir root: Path) {
        val t = target(root, AccessPreset.FULL)
        assertSame(t.shell, t.buildShell, "FULL must not fork the shell (pre-policy behaviour)")
    }

    @Test
    @DisplayName("denyTools blocks a method even under full")
    fun testDenyToolsBlocksUnderFull(@TempDir root: Path) {
        val policy = AgentAccessPolicy(preset = AccessPreset.FULL, denyTools = setOf("coding.shell"))
        val t = CodingToolExecutor.target(root, policy)
        assertDenied("shell", call(t, "shell", mapOf("command" to "mvn -v")))
    }

    @Test
    @DisplayName("coding.permissions reports what the gate actually enforces, operator grants included")
    fun testPermissionsReport(@TempDir root: Path) {
        val plain = valueOf(call(target(root, AccessPreset.READ), "permissions"))
        assertTrue(plain.contains("Access preset: read"), plain)
        assertTrue(plain.contains("project build (mvn/verify): NO"), plain)
        assertTrue(plain.contains("shell + arbitrary code    : NO"), plain)
        assertTrue(plain.contains(".browser4"), "the report must list the protections: $plain")
        // No approval channel is attached here, and the report must say so rather than imply that
        // `coding.requestAccess` will reach an operator.
        assertTrue(plain.contains("Operator approval:"), plain)
        assertTrue(plain.contains("channel: none"), plain)

        // An approved `fs.write` grant grants mutation *and* the build: the build requires the
        // narrower `read-build` preset, so it is approved too. The report has to agree with the
        // gate — a permissions tool that contradicts the decision is worse than none.
        val policy = AgentAccessPolicy(preset = AccessPreset.READ)
        val approvals = AccessApprovals("task-1", { policy }, { root })
        val widened = target(root, policy, approvals)
        val request = approvals.request(Effect.FS_WRITE, Target.PathTarget(root.resolve("a.txt")))
        assertEquals(AccessState.APPROVED, approvals.approve(request.id, AccessSource.REST)!!.state)

        val report = valueOf(call(widened, "permissions"))
        assertTrue(report.contains("write workspace          : yes"), report)
        assertTrue(report.contains("project build (mvn/verify): yes"), report)
        assertFalse(report.contains("project build (mvn/verify): NO"), report)
        assertTrue(report.contains("shell + arbitrary code    : NO"), report)
        assertTrue(report.contains("Operator grants in force (1)"), report)
    }

    @Test
    @DisplayName("an operator decision leaves an approval audit entry")
    fun testOperatorDecisionIsAudited(@TempDir root: Path) {
        Files.writeString(root.resolve("a.txt"), "hello")
        PermissionAudit.reset()
        val policy = AgentAccessPolicy(preset = AccessPreset.READ_BUILD)
        val approvals = AccessApprovals("task-1", { policy }, { root })
        val t = target(root, policy, approvals)

        val request = approvals.request(Effect.FS_WRITE, Target.PathTarget(root.resolve("a.txt")))
        assertEquals(AccessState.APPROVED, approvals.approve(request.id, AccessSource.REST)!!.state)

        val result = call(t, "protect", mapOf("path" to "a.txt", "on" to "true"))
        assertTrue(result.success, "approved protect failed: ${result.exception}")

        val audit = PermissionAudit.snapshot()
        assertTrue(
            audit.keys.any { it == "coding.requestAccess:${PermissionAudit.CODE_ACCESS_REQUESTED}" },
            "the request must be audited, got: $audit",
        )
        assertTrue(
            audit.keys.any { it == "coding.requestAccess:${PermissionAudit.CODE_ACCESS_APPROVED}" },
            "the approval must be audited, got: $audit",
        )
    }
}
