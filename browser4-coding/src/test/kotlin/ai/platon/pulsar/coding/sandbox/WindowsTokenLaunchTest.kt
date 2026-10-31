package ai.platon.pulsar.coding.sandbox

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Plan P1-2 — the Windows token launch.
 *
 * Two halves: the pure pieces (argument quoting, environment block, merge policy) hold everywhere,
 * the real launch only on Windows. The launch reuses the current process token, so no provisioned
 * sandbox account is needed; when the backend lacks `SeAssignPrimaryTokenPrivilege` the launch is a
 * structured hard failure and the test is skipped — it never falls back to an unwrapped launch.
 */
@DisplayName("windows token launch")
class WindowsTokenLaunchTest {

    @TempDir
    lateinit var tempDir: Path

    private val isWindows = System.getProperty("os.name", "").lowercase().contains("win")

    @Test
    @DisplayName("arguments are quoted with the CommandLineToArgvW rules")
    fun quotingRules() {
        assertEquals("plain", WindowsTokenLauncher.quoteWindowsArgument("plain"))
        assertEquals("\"\"", WindowsTokenLauncher.quoteWindowsArgument(""))
        assertEquals("\"a b\"", WindowsTokenLauncher.quoteWindowsArgument("a b"))
        assertEquals("\"a\\\"b\"", WindowsTokenLauncher.quoteWindowsArgument("a\"b"))
        assertEquals("\"a\\b c\"", WindowsTokenLauncher.quoteWindowsArgument("a\\b c"))
        assertEquals("\"a b\\\\\"", WindowsTokenLauncher.quoteWindowsArgument("a b\\"))
        assertEquals("a\\b", WindowsTokenLauncher.quoteWindowsArgument("a\\b"))

        assertEquals(
            "cmd.exe /c \"echo hi\"",
            WindowsTokenLauncher.buildWindowsCommandLine(listOf("cmd.exe", "/c", "echo hi")),
        )
    }

    @Test
    @DisplayName("the environment block is UTF-16LE, NUL-separated and double-NUL-terminated")
    fun environmentBlockEncoding() {
        val block = WindowsTokenLauncher.buildEnvironmentBlock(
            mapOf("PATH" to "C:\\tools;C:\\Windows", "A=B" to "ignored", "" to "ignored"),
        )

        val text = String(block.getByteArray(0, block.size().toInt()), Charsets.UTF_16LE)

        assertTrue(text.startsWith("PATH=C:\\tools;C:\\Windows\u0000"), text)
        assertTrue(text.endsWith("\u0000\u0000"), "the block must end with a double NUL: $text")
        assertFalse(text.contains("A=B"), "a name containing '=' is not a variable: $text")
        assertFalse(text.contains("ignored"), text)
    }

    @Test
    @DisplayName("token-owned variables survive a declared override; toolchain variables do not")
    fun mergeKeepsTokenIdentity() {
        val merged = WindowsTokenLauncher.mergeEnvironment(
            tokenEnvironment = mapOf(
                "TEMP" to "C:\\Users\\b4-agent\\AppData\\Local\\Temp",
                "USERPROFILE" to "C:\\Users\\b4-agent",
                "PATH" to "C:\\Windows",
            ),
            declared = mapOf(
                "temp" to "C:\\Users\\backend\\Temp",
                "USERPROFILE" to "C:\\Users\\backend",
                "PATH" to "C:\\tools;C:\\Windows",
                "JAVA_HOME" to "C:\\jdk",
            ),
        )

        assertEquals("C:\\Users\\b4-agent\\AppData\\Local\\Temp", merged["TEMP"], "the child needs a writable temp")
        assertEquals("C:\\Users\\b4-agent", merged["USERPROFILE"])
        assertEquals("C:\\tools;C:\\Windows", merged["PATH"], "the toolchain still comes from the declaration")
        assertEquals("C:\\jdk", merged["JAVA_HOME"])
        assertFalse(merged.containsKey("temp"), "a case variant must not duplicate a token-owned variable")
    }

    @Test
    @DisplayName("a non-Windows backend fails hard instead of launching unwrapped")
    fun nonWindowsIsHardFailure() {
        assumeTrue(!isWindows, "verifies the non-Windows contract")

        val spec = spec("cmd.exe", "/c", "echo hi")
            .copy(sandboxAccount = SandboxAccount("b4-agent", charArrayOf('p')))

        val error = assertThrows(SandboxUnavailableException::class.java) { ProcessLauncher.start(spec) }

        assertEquals("windows-acl", error.sandboxId)
        assertTrue(error.message!!.contains("requires Windows"), error.message!!)
        assertFalse(error.message!!.contains("falling back"), "must not advertise a fallback")
    }

    @Test
    @DisplayName("a bare command name resolves against the sandbox PATH")
    fun programResolution() {
        val bin = Files.createDirectories(tempDir.resolve("bin"))
        val executable = Files.writeString(bin.resolve("fake-tool.exe"), "")

        val environment = mapOf("PATH" to bin.toString())
        val resolved = WindowsTokenLauncher.resolveProgram("fake-tool", environment)

        assertEquals(executable.toAbsolutePath().toString(), resolved)
        assertEquals("no-such-tool", WindowsTokenLauncher.resolveProgram("no-such-tool", environment))
        assertEquals(
            "C:\\Windows\\System32\\cmd.exe",
            WindowsTokenLauncher.resolveProgram("C:\\Windows\\System32\\cmd.exe", environment),
            "an explicit path is never rewritten",
        )
    }

    @Test
    @DisplayName("a missing sandbox account is a hard failure, not an unwrapped launch")
    fun logonFailureIsHardFailure() {
        assumeTrue(isWindows, "LogonUserW only exists on Windows")

        val account = SandboxAccount("b4-no-such-account-${System.nanoTime()}", charArrayOf('x'))
        val error = assertThrows(SandboxUnavailableException::class.java) {
            WindowsTokenLauncher.start(spec("cmd.exe", "/c", "echo hi"), account)
        }

        assertEquals("windows-acl", error.sandboxId)
        assertTrue(error.message!!.contains("LogonUserW failed"), error.message!!)
    }

    @Test
    @DisplayName("cmd.exe runs with piped stdio and reports its exit code")
    fun cmdLaunch() {
        assumeTrue(isWindows, "the token launch only exists on Windows")

        val process = startWithCurrentToken(spec("cmd.exe", "/c", "echo token-stdout")) ?: return
        val output = readAll(process)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the child must exit")
        assertEquals(0, process.exitValue())
        assertTrue(output.contains("token-stdout"), output)

        val failing = startWithCurrentToken(spec("cmd.exe", "/c", "exit 7")) ?: return
        assertTrue(failing.waitFor(30, TimeUnit.SECONDS))
        assertEquals(7, failing.exitValue())

        // The token environment supplies the profile/temp variables the sandbox identity needs.
        val environment = startWithCurrentToken(spec("cmd.exe", "/c", "set")) ?: return
        val variables = readAll(environment)
        assertTrue(environment.waitFor(30, TimeUnit.SECONDS))
        assertTrue(variables.contains("USERPROFILE="), variables)
        assertTrue(variables.contains("SYSTEMROOT=", ignoreCase = true), variables)
    }

    @Test
    @DisplayName("stderr is redirected into stdout when requested")
    fun mergedStderr() {
        assumeTrue(isWindows, "the token launch only exists on Windows")

        val process = startWithCurrentToken(spec("cmd.exe", "/c", "echo token-stderr 1>&2"), true) ?: return
        val output = readAll(process)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertTrue(output.contains("token-stderr"), output)
    }

    @Test
    @DisplayName("a PowerShell command runs as a child process")
    fun powerShellLaunch() {
        assumeTrue(isWindows, "the token launch only exists on Windows")

        val spec = spec("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Write-Output token-pwsh")
        val process = startWithCurrentToken(spec) ?: return
        val stdout = readAll(process)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), "stdout=$stdout stderr=$stderr")
        assertTrue(stdout.contains("token-pwsh"), "stdout=$stdout stderr=$stderr")
    }

    private fun spec(vararg command: String): ProcessSpec = ProcessSpec(
        command.toList(),
        Files.createTempDirectory("b4-token-launch"),
        mapOf("PATH" to (System.getenv("PATH") ?: "")),
    )

    /**
     * Launches [spec] with the current process token.
     *
     * A backend without `SeAssignPrimaryTokenPrivilege` cannot assign a primary token; that is the
     * documented hard failure, so the test skips instead of turning it into a red suite. Any other
     * failure is a real bug and propagates.
     */
    private fun startWithCurrentToken(spec: ProcessSpec, redirectErrorStream: Boolean = false): Process? {
        val token = PointerByReference()
        if (!Win32.advapi32.OpenProcessToken(Win32.kernel32.GetCurrentProcess(), Win32.TOKEN_ALL_ACCESS, token)) {
            assumeTrue(false, "OpenProcessToken failed (${Native.getLastError()})")
            return null
        }
        val handle: Pointer = token.value ?: return null
        try {
            WindowsTokenLauncher.enableTokenPrivileges()
            return WindowsTokenLauncher.startWithToken(spec, handle, redirectErrorStream)
        } catch (e: SandboxUnavailableException) {
            val message = e.message.orEmpty()
            if (message.contains("1314") || message.contains("privilege", ignoreCase = true)) {
                assumeTrue(false, "the test backend cannot assign a primary token: $message")
            }
            throw e
        } finally {
            Win32.kernel32.CloseHandle(handle)
        }
    }

    private fun readAll(process: Process): String = process.inputStream.bufferedReader().use { it.readText() }
}
