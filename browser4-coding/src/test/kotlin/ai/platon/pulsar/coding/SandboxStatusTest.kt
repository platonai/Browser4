package ai.platon.pulsar.coding

import ai.platon.pulsar.coding.sandbox.ExecutionSandbox
import ai.platon.pulsar.coding.sandbox.ExecutionSandboxes
import ai.platon.pulsar.coding.sandbox.ProcessSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * One source for "is an OS boundary actually behind this refusal".
 *
 * Origin (2026-11-16, run `966a5f2e`): the shell and the file tools each restated the promise
 * "the OS sandbox enforces the boundary" even on an installation where
 * `browser4.agent.sandbox=off` — where nothing was enforced and a `python -c` walked out of the
 * workspace. Both now ask [SandboxStatus], so the text cannot drift from the effective state.
 */
@DisplayName("sandbox status reported to the model")
class SandboxStatusTest {

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
    @DisplayName("no backend: the clause says the tool layer is the only thing that refused")
    fun offSaysTheToolLayerIsAlone() = withSandboxProperty(null) {
        assertEquals("off", SandboxStatus.effectiveId)
        assertFalse(SandboxStatus.osBoundaryActive)

        val clause = SandboxStatus.boundaryClause()

        assertTrue(clause.contains("no OS sandbox is active"), clause)
        assertTrue(clause.contains(ExecutionSandboxes.PROP_SANDBOX), "the clause names the switch: $clause")
        assertFalse(clause.contains("enforces the boundary"), "must not promise an OS boundary: $clause")
    }

    @Test
    @DisplayName("a configured backend: the clause names it as the enforcing layer")
    fun configuredBackendIsNamed() = withSandboxProperty("windows-acl") {
        assertEquals("windows-acl", SandboxStatus.effectiveId)
        assertTrue(SandboxStatus.osBoundaryActive)

        val clause = SandboxStatus.boundaryClause()

        assertTrue(clause.contains("windows-acl"), clause)
        assertTrue(clause.contains("enforces the boundary"), clause)
    }

    @Test
    @DisplayName("a blank property counts as off")
    fun blankPropertyIsOff() = withSandboxProperty("   ") {
        assertEquals("off", SandboxStatus.effectiveId)
        assertFalse(SandboxStatus.osBoundaryActive)
    }

    /**
     * Run `4125acaa` (2026-11-19): the property was unset while the coding chain selected
     * `windows-low-il`, and every refusal told the model "no OS sandbox is active". The task's own
     * backend has to win — the property is only the operator's override.
     */
    @Test
    @DisplayName("the task's own backend wins over the process-wide property")
    fun taskBackendWinsOverProperty() = withSandboxProperty(null) {
        val clause = SandboxStatus.boundaryClause(LowIntegrityStub)

        assertEquals("windows-low-il", SandboxStatus.effectiveIdOf(LowIntegrityStub))
        assertTrue(SandboxStatus.osBoundaryActive(LowIntegrityStub), "the seam says a backend runs")
        assertTrue(clause.contains("windows-low-il"), clause)
        assertTrue(clause.contains("enforces the boundary"), clause)
        assertFalse(clause.contains("no OS sandbox is active"), clause)

        // The property-only view stays honest about being the property, not the task.
        assertEquals("off", SandboxStatus.effectiveId)
        assertFalse(SandboxStatus.osBoundaryActive)
    }

    @Test
    @DisplayName("an explicit off seam is reported as off, whatever the property says")
    fun taskOffSeamIsOff() = withSandboxProperty("windows-low-il") {
        val off = ExecutionSandboxes.of("off")

        assertEquals("off", SandboxStatus.effectiveIdOf(off))
        assertFalse(SandboxStatus.osBoundaryActive(off))
        assertTrue(SandboxStatus.boundaryClause(off).contains("no OS sandbox is active"))
    }

    @Test
    @DisplayName("a shell built without a seam falls back to the process-wide property")
    fun missingSeamFallsBackToProperty() = withSandboxProperty("windows-acl") {
        assertEquals("windows-acl", SandboxStatus.effectiveIdOf(null))
        assertTrue(SandboxStatus.osBoundaryActive(null))
    }

    /** Minimal stand-in for a backend that exists without touching the OS (no token, no probe). */
    private object LowIntegrityStub : ExecutionSandbox {
        override val id: String get() = "windows-low-il"

        override fun prepare(
            spec: ProcessSpec,
            workspace: Path,
            allowNetwork: Boolean,
            extraWritableRoots: List<Path>,
        ): ProcessSpec = spec
    }
}
