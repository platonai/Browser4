package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.AgentMode
import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.CodingComponents
import ai.platon.pulsar.coding.IsolationProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Mode → backend selection, and the one line `coding.permissions` prints about it.
 *
 * The selection is what makes the redesign more than a document: `--coding` must end up on a backend
 * that confines writes without confining reads, browse must never silently get the coding backend,
 * an operator's explicit `-Dbrowser4.agent.sandbox` must beat both, and a backend that cannot start
 * must not be selected just because it appears in the chain.
 */
@DisplayName("sandbox selection by mode")
class ExecutionSandboxesModeTest {

    private class FakeSandbox(
        override val id: String,
        private val ready: Boolean = true,
        override val autoSelectable: Boolean = true,
    ) : ExecutionSandbox {
        override fun isReady(): Boolean = ready

        override fun prepare(
            spec: ProcessSpec,
            workspace: Path,
            allowNetwork: Boolean,
            extraWritableRoots: List<Path>,
        ): ProcessSpec = spec
    }

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

    @Test
    @DisplayName("coding resolves to the low-integrity backend when it is ready")
    fun codingResolvesToLowIntegrity() {
        val ready = setOf("windows-low-il")
        val selected = ExecutionSandboxes.resolveForMode(
            AgentMode.CODING,
            ready = { it in ready },
            instantiate = { FakeSandbox(it) },
        )

        assertEquals("windows-low-il", selected.id)
    }

    @Test
    @DisplayName("browse never falls through to the coding backend")
    fun browseDoesNotUseTheCodingBackend() {
        // Only the account backend is usable here: browse must take it, not `windows-low-il`, because
        // browse needs *read* confinement and an integrity level does not provide one.
        val ready = setOf("windows-low-il", "windows-acl")
        val selected = ExecutionSandboxes.resolveForMode(
            AgentMode.BROWSE,
            ready = { it in ready },
            instantiate = { FakeSandbox(it) },
        )

        assertEquals("windows-acl", selected.id)
    }

    @Test
    @DisplayName("an unimplemented backend in the chain is skipped, not fatal")
    fun unimplementedBackendIsSkipped() {
        // `windows-appcontainer` is browse's first choice but is not implemented: the chain must keep
        // walking instead of throwing.
        val selected = ExecutionSandboxes.resolveForMode(
            AgentMode.BROWSE,
            ready = { it == "windows-acl" },
            instantiate = { FakeSandbox(it) },
        )

        assertEquals("windows-acl", selected.id)
    }

    @Test
    @DisplayName("a backend that must not be a default is skipped for a mode that never asked for it")
    fun notAutoSelectableIsSkipped() {
        // `windows-appcontainer` confines reads *and* writes but cannot reach the user profile, where
        // `browser4-cli` lives; until that is solved it must not become a browse task's default.
        val selected = ExecutionSandboxes.resolveForMode(
            AgentMode.BROWSE,
            ready = { true },
            instantiate = { id -> FakeSandbox(id, autoSelectable = id != "windows-appcontainer") },
        )

        assertEquals("windows-acl", selected.id, "the chain must skip a backend that is opt-in only")
    }

    @Test
    @DisplayName("an operator's explicit backend beats the mode's chain")
    fun explicitOperatorBackendWins() = withSandboxProperty("off") {
        val selected = ExecutionSandboxes.resolveForMode(
            AgentMode.CODING,
            ready = { true },
            instantiate = { FakeSandbox(it) },
        )

        assertEquals("off", selected.id, "an explicit -Dbrowser4.agent.sandbox must not be overridden")
    }

    @Test
    @DisplayName("with nothing ready the installation default applies, and off is reported as off")
    fun nothingReadyFallsBackToTheDefault() = withSandboxProperty(null) {
        val selected = ExecutionSandboxes.resolveForMode(
            AgentMode.CODING,
            ready = { false },
            instantiate = { FakeSandbox(it) },
        )

        assertEquals("off", selected.id, "no backend claimed readiness, so the honest answer is off")
        assertFalse(selected.enforcesNetwork)
    }

    @Test
    @DisplayName("the fallback never claims readiness it did not get")
    fun readinessIsNotAssumed() {
        val ready = setOf("windows-appcontainer")
        val selected = ExecutionSandboxes.resolveForMode(
            AgentMode.BROWSE,
            ready = { it in ready },
            instantiate = { FakeSandbox(it) },
        )

        // appcontainer is first in the chain and "ready" was faked, so it is selected — the point of
        // this case is that readiness is consulted per id, not assumed from the id's position.
        assertEquals("windows-appcontainer", selected.id)
    }

    @Test
    @DisplayName("coding.permissions names the backend, the goals and whether the network is refused")
    fun isolationSummaryNamesTheEffectiveLayer() = withSandboxProperty("off") {
        val coding = CodingComponents.isolationSummary(AgentAccessPolicy(mode = AgentMode.CODING))
        val browse = CodingComponents.isolationSummary(AgentAccessPolicy(mode = AgentMode.BROWSE))

        assertTrue(coding.startsWith("OS sandbox: off"), coding)
        assertTrue(coding.contains("mode=coding"), coding)
        assertTrue(coding.contains("read=user"), coding)
        assertTrue(coding.contains("write=workspace+"), coding)
        assertTrue(coding.contains("network: NOT enforced"), coding)

        assertTrue(browse.contains("mode=browse"), browse)
        assertTrue(browse.contains("read=workspace"), browse)
        assertTrue(browse.contains("net=browser-only"), browse)
        assertFalse(browse.contains("read=user"), browse)
    }

    @Test
    @DisplayName("the policy defaults to browse, so a task that says nothing is not silently relaxed")
    fun policyDefaultsToBrowse() {
        val policy = AgentAccessPolicy()

        assertEquals(AgentMode.BROWSE, policy.mode)
        assertEquals(AccessPreset.FULL, policy.preset)
        // `AgentMode` is what the backend selection reads; nothing else in the policy changes it.
        assertEquals(AgentMode.CODING, policy.copy(mode = AgentMode.CODING).mode)
    }

    @Test
    @DisplayName("the coding profile's cache roots come from the toolchain list, not a hard-coded set")
    fun codingCachesAreTheToolchainCaches() {
        val caches = IsolationProfile.forMode(AgentMode.CODING).write
        assertTrue(caches is ai.platon.pulsar.coding.WriteScope.WorkspacePlus, "coding writes caches: $caches")
        val roots = (caches as ai.platon.pulsar.coding.WriteScope.WorkspacePlus).caches
        assertTrue(
            roots.all { it.fileName.toString().lowercase() != "jdk" },
            "a JDK is read-only for a build: $roots",
        )
    }
}
