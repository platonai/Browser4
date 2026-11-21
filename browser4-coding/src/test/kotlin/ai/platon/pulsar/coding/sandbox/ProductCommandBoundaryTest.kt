package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.CodingComponents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * `b4.run` — the CLI is a **product** channel, and one backend cannot host it (decision 2026-11-17).
 *
 * `browser4-cli` lives under the operator profile and talks to the backend over `127.0.0.1`. An
 * AppContainer is refused both (measured: profile unreachable, loopback refused), so handing the
 * launch to that backend makes `b4.run` fail outright. The resolution is a declared capability
 * ([ExecutionSandbox.executesProductCommands]) rather than a silent fallback: the channel keeps its
 * historical launch in the backend, and `coding.permissions` names it as outside the boundary — the
 * same honest treatment git/worktree/LSP get, instead of weakening the fence (a
 * `privateNetworkClientServer` grant would open every host-local service) to fit one program.
 */
@DisplayName("b4.run product-channel boundary")
class ProductCommandBoundaryTest {

    /** A backend that stands in for the container: it cannot host the CLI. */
    private class ContainerLike(override val id: String = WindowsAppContainerSandbox.SANDBOX_ID) : ExecutionSandbox {
        override val executesFileOps: Boolean = true
        override val executesProductCommands: Boolean = false

        override fun prepare(
            spec: ProcessSpec,
            workspace: Path,
            allowNetwork: Boolean,
            extraWritableRoots: List<Path>,
        ): ProcessSpec = spec
    }

    private class TokenLike(override val id: String = WindowsLowIntegritySandbox.SANDBOX_ID) : ExecutionSandbox {
        override fun prepare(
            spec: ProcessSpec,
            workspace: Path,
            allowNetwork: Boolean,
            extraWritableRoots: List<Path>,
        ): ProcessSpec = spec
    }

    @Test
    @DisplayName("the capabilities are declared per backend, not inferred from the id")
    fun testDeclaredCapabilities() {
        assertFalse(
            WindowsAppContainerSandbox().executesProductCommands,
            "the container cannot read the profile or the host loopback, so it cannot host the CLI",
        )
        assertTrue(WindowsLowIntegritySandbox().executesProductCommands, "a Low-integrity child can host the CLI")
        assertTrue(WindowsAclSandbox().executesProductCommands, "the account backend can host the CLI")
        assertTrue(OffSandbox.executesProductCommands, "no boundary keeps the historical launch")

        // The two capabilities are independent: the container executes file mutations but not the CLI.
        assertTrue(WindowsAppContainerSandbox().executesFileOps)
        assertFalse(OffSandbox.executesFileOps)
    }

    @Test
    @DisplayName("a backend that cannot host the CLI leaves b4.run in the backend instead of failing it")
    fun testContainerKeepsTheHistoricalLaunch() {
        val policy = AgentAccessPolicy()

        assertNull(
            CodingComponents.productCommandSandbox(policy, ContainerLike()),
            "a container must not be handed a launch it cannot perform",
        )
        val token = TokenLike()
        assertSame(
            token,
            CodingComponents.productCommandSandbox(policy, token),
            "a backend that can host the CLI keeps it inside the boundary",
        )
    }

    @Test
    @DisplayName("an unrestricted task asks for the operator's own permissions, so nothing is wrapped")
    fun testUnrestrictedIsNeverWrapped() {
        val unrestricted = AgentAccessPolicy().withUnrestricted()

        assertNull(CodingComponents.productCommandSandbox(unrestricted, TokenLike()))
        assertNull(CodingComponents.productCommandSandbox(unrestricted, ContainerLike()))
    }

    @Test
    @DisplayName("the decision is not a fallback: an unavailable backend still throws")
    fun testUnavailableBackendStillFails() {
        val failing = object : ExecutionSandbox {
            override val id: String = "failing"
            override fun prepare(
                spec: ProcessSpec,
                workspace: Path,
                allowNetwork: Boolean,
                extraWritableRoots: List<Path>,
            ): ProcessSpec = throw SandboxUnavailableException(id, "sandbox unavailable: probe failed")
        }

        // The decision only answers "may this backend host the CLI"; readiness is still enforced by
        // `prepare`, which throws. Resolving it must not swallow that.
        assertSame(failing, CodingComponents.productCommandSandbox(AgentAccessPolicy(), failing))
        val error = org.junit.jupiter.api.Assertions.assertThrows(SandboxUnavailableException::class.java) {
            failing.prepare(
                ProcessSpec(listOf("browser4-cli", "status"), Path.of("."), emptyMap()),
                Path.of("."),
                allowNetwork = false,
            )
        }
        assertTrue(error.message!!.contains("probe failed"), error.message!!)
        assertEquals("failing", error.sandboxId)
    }
}
