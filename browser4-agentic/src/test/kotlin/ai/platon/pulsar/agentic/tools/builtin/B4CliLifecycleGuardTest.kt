package ai.platon.pulsar.agentic.tools.builtin

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `b4.run` must not let an agent stop the backend that is executing the task.
 *
 * Origin: a live supervision run (Amazon top-20, 2026-10-28) called
 * `b4.run("stop")` while "fixing" an unrelated browser failure, killed its own
 * backend mid-task, and the task was reported as `server restarted` — the real
 * cause was lost.
 */
@DisplayName("b4.run lifecycle guard")
class B4CliLifecycleGuardTest {

    private fun refusal(args: String): String? = b4CliLifecycleRefusal(args)

    @Test
    @DisplayName("stop is refused and names the command plus a safe route")
    fun stopIsRefused() {
        val r = refusal("stop")

        assertNotNull(r, "`stop` would terminate the backend running the task")
        assertTrue(r!!.contains("COMMAND_NOT_ALLOWED"), r)
        assertTrue(r.contains("b4.run"), r)
        assertTrue(r.contains("'stop'"), r)
        // The model needs a way out: report the blocker instead of retrying.
        assertTrue(r.contains("system.taskComplete(problems"), r)
    }

    @Test
    @DisplayName("a global flag before the command does not hide it")
    fun globalFlagBeforeCommand() {
        assertNotNull(refusal("-s work1 stop"), "`-s <name> stop` must still be refused")
        assertNotNull(refusal("--session work1 stop"))
        assertNotNull(refusal("--server http://localhost:8282 stop"))
        assertNotNull(refusal("--timeout 30 stop"))
        assertNotNull(refusal("--timeout=30 stop"))
    }

    @Test
    @DisplayName("every whole-backend / installation command is refused")
    fun lifecycleFamilyIsRefused() {
        listOf("stop", "close-all", "kill-all", "install", "uninstall", "upgrade", "delete-data")
            .forEach { assertNotNull(refusal(it), "$it must be refused") }
        // The CLI's spaced-to-kebab rewrite is not applied inside `b4.run`, but a
        // user-typed spelling must not slip through either.
        assertNotNull(refusal("CLOSE-ALL"))
    }

    @Test
    @DisplayName("doctor --fix is refused while read-only doctor forms stay allowed")
    fun mutatingDoctorIsRefused() {
        assertNotNull(refusal("doctor --fix"))

        assertNull(refusal("doctor"), "plain doctor is read-only")
        assertNull(refusal("doctor log"), "doctor log reads the backend log")
        assertNull(refusal("doctor status --section browsers"))
        assertNull(refusal("doctor --verbose"))
    }

    @Test
    @DisplayName("normal browsing commands stay allowed, including close")
    fun browsingCommandsAreAllowed() {
        // `close` is the documented anti-bot escalation step (close → open --headed):
        // it closes one session, not the backend.
        assertNull(refusal("close"))
        assertNull(refusal("open --headless \"https://example.com\""))
        assertNull(refusal("-s work1 open --headless --fresh \"https://example.com\""))
        assertNull(refusal("goto \"https://example.com\""))
        assertNull(refusal("snapshot -v 0"))
        assertNull(refusal("htmlsnapshot get text \"h1\""))
        assertNull(refusal("--version"))
        assertNull(refusal("help"))
        // Asking what a blocked command does is read-only.
        assertNull(refusal("stop --help"))
        assertNull(refusal("doctor --fix -h"))
    }

    @Test
    @DisplayName("skill install/uninstall/reload are refused in both spaced and kebab form")
    fun skillManagementIsRefused() {
        // `skill install --path <dir>` deploys files outside the workspace and registers
        // executable skills, so it is a lifecycle rewrite like `install`.
        assertNotNull(refusal("skill install --path /tmp/skills"))
        assertNotNull(refusal("skill uninstall my-skill"))
        assertNotNull(refusal("skill reload"))
        assertNotNull(refusal("skill-install --path /tmp/skills"))
        assertNotNull(refusal("SKILL INSTALL --path /tmp/skills"))
        // The generic MCP passthrough reaches the same deployment tools: `tool call` starts
        // with `tool`, so the `skill <verb>` guard alone would miss it.
        assertNotNull(refusal("tool call skill_install --json '{\"path\":\"/opt/skills\"}'"))
        assertNotNull(refusal("tool call --json '{\"id\":\"x\"}' skill_uninstall"))
        assertNotNull(refusal("tool-call skill.reload --json '{\"id\":\"x\"}'"))

        // Read-only tool calls stay available (the deployment names are what is blocked).
        assertNull(refusal("tool call htmlsnapshot_get --json '{}'"))

        // Read-only skill discovery stays available, including its own help.
        assertNull(refusal("skill list"))
        assertNull(refusal("skill info my-skill"))
        assertNull(refusal("skill install --help"))
    }
}
