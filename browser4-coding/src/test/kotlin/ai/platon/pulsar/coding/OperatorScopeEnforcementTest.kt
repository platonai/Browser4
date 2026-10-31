package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Target-level checks for operator grants.
 *
 * An approval carries a scope (the path, command or host it was issued for), but the method gate
 * only sees the *effect*. These tests pin the second layer: when the preset alone refuses, the
 * concrete target must be covered by the grant, and a grant for one target must not unlock another.
 *
 * No process is started here — the shell and `runCode` refusals happen before the launch seam.
 */
@DisplayName("Operator scope enforcement")
class OperatorScopeEnforcementTest {

    private fun readOnlyApprovals(root: Path) = AccessApprovals(
        "task-1",
        { AgentAccessPolicy(preset = AccessPreset.READ) },
        { root },
        Instant::now,
    )

    private fun policyWithGrants(approvals: AccessApprovals) = AgentAccessPolicy(
        preset = AccessPreset.READ,
        operatorGrants = { approvals.activeCapabilities() },
    )

    // ==================== files ====================

    @Test
    @DisplayName("under read, only the approved path is writable and a sibling path is refused")
    fun testApprovedPathOnlyIsWritable(@TempDir root: Path) = runBlocking {
        Files.createDirectories(root)
        val approvals = readOnlyApprovals(root)
        val request = approvals.request(Effect.FS_WRITE, Target.PathTarget(root.resolve("a.txt")))
        assertEquals(AccessState.APPROVED, approvals.approve(request.id, AccessSource.REST)!!.state)
        val policy = policyWithGrants(approvals)

        assertTrue(policy.allowsMutationOf(root.resolve("a.txt")))
        assertFalse(policy.allowsMutationOf(root.resolve("b.txt")))

        val fs = CodingComponents.fs(root, policy)
        val approved = fs.writeFile("a.txt", "x")
        assertTrue(approved.startsWith("✓"), approved)
        assertEquals("x", Files.readString(root.resolve("a.txt")))

        val refused = fs.writeFile("b.txt", "y")
        assertTrue(refused.startsWith("Error:"), refused)
        assertTrue(refused.contains("not covered by an active operator grant"), refused)
        assertTrue(refused.contains("needed=fs.write"), refused)
        assertFalse(Files.exists(root.resolve("b.txt")), "a refused write must not touch the file")
    }

    @Test
    @DisplayName("under full the preset still grants every path without an operator grant")
    fun testFullPresetIsUnchanged(@TempDir root: Path) = runBlocking {
        Files.createDirectories(root)
        val policy = AgentAccessPolicy(preset = AccessPreset.FULL)
        assertTrue(policy.allowsMutationOf(root.resolve("a.txt")))
        assertTrue(policy.allowsMutationOf(root.resolve("b.txt")))

        val fs = CodingComponents.fs(root, policy)
        assertTrue(fs.writeFile("a.txt", "x").startsWith("✓"))
        assertTrue(fs.writeFile("b.txt", "y").startsWith("✓"))
    }

    // ==================== commands ====================

    @Test
    @DisplayName("a command grant covers the approved program only")
    fun testCommandGrantCoversApprovedProgramOnly(@TempDir root: Path) {
        Files.createDirectories(root)
        val approvals = readOnlyApprovals(root)
        // `git status` is approved as its program name, the same granularity the rail checks.
        val request = approvals.request(Effect.FS_EXEC, Target.CommandTarget("git status"))
        assertEquals(AccessState.APPROVED, approvals.approve(request.id, AccessSource.REST)!!.state)
        val policy = policyWithGrants(approvals)

        assertTrue(policy.allowsCommandExecution("git status"))
        assertTrue(policy.allowsCommandExecution("git"))
        assertFalse(policy.allowsCommandExecution("node -v"))
        assertFalse(policy.allowsCommandExecution("echo hi"))
    }

    @Test
    @DisplayName("the shell refuses an unapproved program before launching anything")
    fun testShellRefusesUnapprovedCommand(@TempDir root: Path) = runBlocking {
        Files.createDirectories(root)
        val approvals = readOnlyApprovals(root)
        val request = approvals.request(Effect.FS_EXEC, Target.CommandTarget("git"))
        approvals.approve(request.id, AccessSource.REST)
        val policy = policyWithGrants(approvals)

        val shell = CodingAgentShell(
            baseDir = root,
            allowExecution = true,
            commandAllowedProvider = { policy.allowsCommandExecution(it) },
        )

        val refused = shell.execute("node -v")
        assertTrue(refused.startsWith("Error:"), refused)
        assertTrue(refused.contains("not covered by an active operator grant"), refused)
        assertTrue(refused.contains("needed=fs.exec"), refused)
    }

    @Test
    @DisplayName("runCode maps each language to the interpreter the grant must cover")
    fun testInterpreterFor() {
        val runner = CodeRunner()
        assertEquals("node", runner.interpreterFor("js"))
        assertEquals("node", runner.interpreterFor("javascript"))
        assertEquals("python", runner.interpreterFor("python"))
        assertEquals("kotlinc", runner.interpreterFor("kotlin"))
        assertNull(runner.interpreterFor("ruby"))
    }

    // ==================== network ====================

    @Test
    @DisplayName("an active net.connect grant turns the network flag on")
    fun testNetworkGrantIsVisible(@TempDir root: Path) {
        Files.createDirectories(root)
        val approvals = readOnlyApprovals(root)
        val request = approvals.request(Effect.NET_CONNECT, Target.HostTarget("example.com"))
        assertEquals(AccessState.APPROVED, approvals.approve(request.id, AccessSource.REST)!!.state)

        assertTrue(policyWithGrants(approvals).hasActiveNetworkGrant())
        assertFalse(AgentAccessPolicy(preset = AccessPreset.READ).hasActiveNetworkGrant())
    }

    // ==================== boundary cache ====================

    @Test
    @DisplayName("the boundary authorizer cache is bounded and shares one instance per workspace")
    fun testBoundaryAuthorizerCacheIsBounded(@TempDir root: Path) {
        val shared = root.resolve("ws0")
        Files.createDirectories(shared)
        val first = CodingComponents.boundaryAuthorizer(shared)
        assertSame(first, CodingComponents.boundaryAuthorizer(shared), "the same root must reuse its instance")

        for (index in 1..70) {
            val workspace = root.resolve("ws$index")
            Files.createDirectories(workspace)
            CodingComponents.boundaryAuthorizer(workspace)
        }

        val count = CodingComponents.cachedBoundaryAuthorizerCount()
        assertTrue(
            count <= CodingComponents.MAX_BOUNDARY_AUTHORIZERS,
            "the cache must not grow past its bound: $count",
        )
    }
}
