package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AgentAccessPolicy
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `b4.run` hands the command to a CLI subprocess, and that subprocess carries no access
 * preset. `code`, `tool call` and `plugin` are answered by the backend's server-wide policy,
 * so a narrowed task could still write files or run plugin tools through them (P3, 2026-10-30).
 */
@DisplayName("b4.run access-preset guard")
class B4CliPolicyGuardTest {

    private fun refusal(args: String, preset: AccessPreset): String? =
        b4CliPolicyRefusal(args, AgentAccessPolicy(preset = preset))

    @Test
    @DisplayName("full access keeps code, tool call and plugin available")
    fun fullAccessKeepsDirectMcpCommands() {
        assertNull(refusal("code write notes.txt hi", AccessPreset.FULL))
        assertNull(refusal("tool call htmlsnapshot_get", AccessPreset.FULL))
        assertNull(refusal("plugin captcha detect", AccessPreset.FULL))
    }

    @Test
    @DisplayName("a narrowed preset refuses the routes that re-enter the backend")
    fun narrowedPresetRefusesDirectMcpCommands() {
        for (preset in listOf(AccessPreset.READ, AccessPreset.READ_BUILD, AccessPreset.WRITE)) {
            listOf(
                "code write notes.txt hi",
                "code read src/Main.kt",
                "tool call htmlsnapshot_get",
                "plugin captcha detect",
            ).forEach { args ->
                val r = refusal(args, preset)
                assertNotNull(r, "'$args' must be refused under ${preset.id}")
                assertTrue(r!!.contains("COMMAND_NOT_ALLOWED"), r)
                assertTrue(r.contains(preset.id), "the refusal must name the preset: $r")
                assertTrue(r.contains("b4.run"), r)
                assertTrue(r.contains("gated tools"), "the hint must name the way out: $r")
            }
        }
    }

    @Test
    @DisplayName("browsing and snapshot commands stay available under a narrowed preset")
    fun browsingCommandsStayAllowed() {
        val read = AgentAccessPolicy(preset = AccessPreset.READ)
        listOf(
            "goto \"https://example.com\"",
            "snapshot -v 0",
            "htmlsnapshot get text \"h1\"",
            "eval --json \"1+1\"",
            "version",
        ).forEach { assertNull(b4CliPolicyRefusal(it, read), it) }
    }

    @Test
    @DisplayName("a global flag before the command does not hide it")
    fun globalFlagBeforeCommand() {
        assertNotNull(refusal("--timeout 30 code write notes.txt hi", AccessPreset.READ))
        assertNotNull(refusal("-s work1 tool call htmlsnapshot_get", AccessPreset.READ))
    }
}
