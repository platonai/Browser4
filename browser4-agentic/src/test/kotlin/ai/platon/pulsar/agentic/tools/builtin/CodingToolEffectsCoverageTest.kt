package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.skills.tools.SkillToolExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Registration-time guard for the capability declarations introduced by plan B1.
 *
 * A tool without `effects` would be invisible to the capability view and would silently look
 * "pure" — the exact drift M1 exists to prevent. Failing here (build/test time) is deliberate:
 * the alternative is failing at dispatch time, when the damage is already modelled as allowed.
 */
class CodingToolEffectsCoverageTest {

    @Test
    @DisplayName("every registered coding method declares its effects")
    fun testEveryCodingMethodDeclaresEffects() {
        val specs = CodingToolExecutor().getToolSpecs()
        assertTrue(specs.size >= 56, "expected the coding tool set, got ${specs.size} methods")

        val undeclared = specs.filterValues { it.effects == null }.keys.sorted()
        assertTrue(
            undeclared.isEmpty(),
            "undeclared coding tools (add them to CodingToolExecutor.TOOL_EFFECTS): $undeclared",
        )
    }

    @Test
    @DisplayName("the coding effect table has no stale entries")
    fun testNoStaleCodingEffectEntries() {
        val registered = CodingToolExecutor().getToolSpecs().keys
        val declared = CodingToolEffects.TOOL_EFFECTS.keys
        assertEquals(
            registered.sorted(),
            declared.sorted(),
            "TOOL_EFFECTS and the registered methods drifted apart",
        )
    }

    @Test
    @DisplayName("shell is declared maximally, reporting tools as pure")
    fun testRepresentativeCodingEffects() {
        val specs = CodingToolExecutor().getToolSpecs()
        val shell = specs.getValue("shell").effects
        assertEquals(
            setOf(
                ai.platon.pulsar.coding.Effect.FS_READ,
                ai.platon.pulsar.coding.Effect.FS_WRITE,
                ai.platon.pulsar.coding.Effect.FS_EXEC,
                ai.platon.pulsar.coding.Effect.NET_CONNECT,
            ),
            shell,
        )
        listOf("permissions", "explain", "protect", "workspaceRoot").forEach { method ->
            assertEquals(emptySet<ai.platon.pulsar.coding.Effect>(), specs.getValue(method).effects, method)
        }
    }

    @Test
    @DisplayName("every declared skill entry point carries its effects")
    fun testSkillMethodsDeclareEffects() {
        // Registry-provided skill specs are out of scope here: only the built-in entry points are
        // owned by this module and therefore must be declared.
        val specs = SkillToolExecutor().getToolSpecs()
        val required = setOf(
            "list", "activate", "run", "install", "uninstall", "readDocumentation",
            "readReference", "runScript",
        )
        val undeclared = required.filter { specs[it]?.effects == null }.sorted()
        assertTrue(
            undeclared.isEmpty(),
            "undeclared skill tools (declare effects in the spec): $undeclared",
        )
    }
}
