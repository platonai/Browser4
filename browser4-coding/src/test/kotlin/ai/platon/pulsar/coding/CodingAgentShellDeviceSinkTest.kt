package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Device sinks are not workspace paths.
 *
 * A live run lost `2>/dev/null` to `COMMAND_PATH_DENIED`: the redirection token was stripped to
 * `/dev/null` and refused as "outside the workspace", although discarding output is shell hygiene
 * and touches no file. The guard must keep refusing real devices such as `/dev/sda` and ordinary
 * paths outside the workspace.
 */
class CodingAgentShellDeviceSinkTest {

    @TempDir
    lateinit var workspace: Path

    private fun shell() = CodingAgentShell(
        baseDir = workspace,
        allowExternalAccess = false,
        workspaceBoundaryEnabled = true,
    )

    private fun outsideFile(): Path = workspace.root.resolve("b4-outside-data.csv")

    private fun assertAllowed(s: CodingAgentShell, command: String) {
        assertTrue(s.isAllowed(command), "expected ALLOWED: $command (${s.explain(command)})")
    }

    private fun assertDenied(s: CodingAgentShell, command: String) {
        assertFalse(s.isAllowed(command), "expected DENIED: $command (${s.explain(command)})")
    }

    @Test
    @DisplayName("redirections to POSIX device sinks are allowed")
    fun posixDeviceSinksAreAllowed() {
        val s = shell()

        for (command in listOf(
            "echo hi 2>/dev/null",
            "echo hi > /dev/null",
            "python .b4scratch/run.py > .b4scratch/out.txt 2>/dev/null",
            "echo hi > /dev/zero",
            "echo hi 2>/dev/stdout",
            "echo hi 2>/dev/stderr",
            "echo hi > /dev/tty",
        )) {
            assertAllowed(s, command)
        }
    }

    @Test
    @DisplayName("a Windows device name is allowed as a redirection target")
    fun windowsDeviceSinkIsAllowed() {
        val s = shell()

        assertAllowed(s, "echo hi > //./NUL")
        assertAllowed(s, "echo hi > NUL")
    }

    @Test
    @DisplayName("real devices and ordinary outside paths stay refused")
    fun realDevicesAndOutsidePathsStayRefused() {
        val s = shell()

        assertDenied(s, "ls /dev/sda")
        assertDenied(s, "cat /dev/mem")
        assertDenied(s, "type \"${outsideFile()}\"")
    }
}
