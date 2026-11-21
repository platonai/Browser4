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

        // A non-shell program keeps the CRT rules: powershell.exe parses its own argv that way.
        assertEquals(
            "powershell.exe -Command \"Write-Output a\"",
            WindowsTokenLauncher.buildWindowsCommandLine(
                listOf("powershell.exe", "-Command", "Write-Output a"),
            ),
        )
    }

    /**
     * `cmd.exe /c <text>` hands `<text>` to cmd's own parser, so the text is a command-line fragment,
     * not an argv element. Escaping its quotes produced a literal `\"` the child saw (measured
     * 2026-11-20: `pwsh -NoProfile -File "…"` exited 64, `cmd /c "…"` reported `'\"echo …\"' is not a
     * recognized command`).
     */
    @Test
    @DisplayName("the cmd.exe /c tail is passed verbatim, quotes included")
    fun shellTailIsVerbatim() {
        assertEquals(
            "cmd.exe /c echo hi",
            WindowsTokenLauncher.buildWindowsCommandLine(listOf("cmd.exe", "/c", "echo hi")),
        )
        assertEquals(
            "cmd.exe /c pwsh -NoProfile -File \"C:\\a b\\x.ps1\"",
            WindowsTokenLauncher.buildWindowsCommandLine(
                listOf("cmd.exe", "/c", "pwsh -NoProfile -File \"C:\\a b\\x.ps1\""),
            ),
        )
        assertEquals(
            "cmd.exe /v:on /c cmd /v:on /c \"echo probe > !B4_CANARY!\"",
            WindowsTokenLauncher.buildWindowsCommandLine(
                listOf("cmd.exe", "/v:on", "/c", "cmd /v:on /c \"echo probe > !B4_CANARY!\""),
            ),
        )
        // Several fragment arguments stay one raw tail: cmd joins them with whitespace itself.
        assertEquals(
            "cmd.exe /c echo a b",
            WindowsTokenLauncher.buildWindowsCommandLine(listOf("cmd.exe", "/c", "echo", "a b")),
        )
        // Without /c or /k the argument list is ordinary argv and keeps the CRT rules.
        assertEquals(
            "cmd.exe /v:on \"a b\"",
            WindowsTokenLauncher.buildWindowsCommandLine(listOf("cmd.exe", "/v:on", "a b")),
        )
        // Only a leading cmd.exe qualifies: a program that merely takes "/c" is not a shell.
        assertEquals(
            "my-tool.exe /c \"a b\"",
            WindowsTokenLauncher.buildWindowsCommandLine(listOf("my-tool.exe", "/c", "a b")),
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

    /**
     * `permission-e2e` A10 on `windows-low-il` (2026-11-20): the token environment is the *user's
     * persistent* environment, so copying it verbatim handed `cmd /c echo %DEEPSEEK_API_KEY%` the
     * real key — the in-process path's whitelist was bypassed. The token block may only contribute
     * identity/plumbing and toolchain variables.
     */
    @Test
    @DisplayName("a persistent user secret in the token environment never reaches the child")
    fun mergeStripsUserSecrets() {
        val merged = WindowsTokenLauncher.mergeEnvironment(
            tokenEnvironment = mapOf(
                "USERPROFILE" to "C:\\Users\\pereg",
                "TEMP" to "C:\\Users\\pereg\\AppData\\Local\\Temp",
                "PATH" to "C:\\Windows",
                "JAVA_HOME" to "C:\\jdk",
                "DEEPSEEK_API_KEY" to "sk-live-secret",
                "OPENAI_API_KEY" to "sk-other-secret",
                "B4_E2E_UNLISTED" to "leaked",
            ),
            declared = mapOf("PATH" to "C:\\tools;C:\\Windows"),
        )

        assertFalse(merged.containsKey("DEEPSEEK_API_KEY"), "an API key must not be inherited: $merged")
        assertFalse(merged.containsKey("OPENAI_API_KEY"), "an API key must not be inherited: $merged")
        assertFalse(merged.containsKey("B4_E2E_UNLISTED"), "an unlisted variable must not be inherited: $merged")
        assertEquals("C:\\Users\\pereg", merged["USERPROFILE"], "the account identity stays")
        assertEquals("C:\\Users\\pereg\\AppData\\Local\\Temp", merged["TEMP"])
        assertEquals("C:\\jdk", merged["JAVA_HOME"], "a whitelisted toolchain variable stays")
        assertEquals("C:\\tools;C:\\Windows", merged["PATH"])
    }

    /**
     * `windows-low-il` provisions and labels the child's temp itself, so for that backend the declared
     * `TEMP`/`TMP` must win — the token's value is the user's Medium `%TEMP%`, which a Low child cannot
     * write.
     */
    @Test
    @DisplayName("a backend that owns its temp dir can make the declared value win")
    fun declaredTempWinsWhenTheBackendAsks() {
        val merged = WindowsTokenLauncher.mergeEnvironment(
            tokenEnvironment = mapOf(
                "TEMP" to "C:\\Users\\b4-agent\\AppData\\Local\\Temp",
                "TMP" to "C:\\Users\\b4-agent\\AppData\\Local\\Temp",
                "USERPROFILE" to "C:\\Users\\b4-agent",
            ),
            declared = mapOf(
                "temp" to "C:\\ws\\.b4scratch\\tmp",
                "TMP" to "C:\\ws\\.b4scratch\\tmp",
                "USERPROFILE" to "C:\\Users\\backend",
            ),
            declaredWins = setOf("TEMP", "TMP"),
        )

        assertEquals("C:\\ws\\.b4scratch\\tmp", merged["temp"], "the task temp must reach the child")
        assertFalse(merged.containsKey("TEMP"), "the token's spelling must not survive as a duplicate")
        assertEquals("C:\\ws\\.b4scratch\\tmp", merged["TMP"])
        assertEquals("C:\\Users\\b4-agent", merged["USERPROFILE"], "identity is still the token's")
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

    /**
     * The measured P0-1 failures (`windows-low-il`, 2026-11-20): a quoted `-File` path made `pwsh`
     * exit 64 and `cmd /c "…"` received a literal `\"…\"`. Both are the launcher's own quoting, so
     * both are reproduced and pinned here through a real child process.
     */
    @Test
    @DisplayName("quoted arguments reach the child verbatim")
    fun quotedArgumentsReachTheChild() {
        assumeTrue(isWindows, "the token launch only exists on Windows")

        val echoed = startWithCurrentToken(spec("cmd.exe", "/c", "echo \"token quoted\"")) ?: return
        val echoedOut = readAll(echoed)
        assertTrue(echoed.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, echoed.exitValue(), "stdout=$echoedOut")
        assertTrue(echoedOut.contains("token quoted"), echoedOut)
        assertFalse(
            echoedOut.contains("\\\""),
            "the launcher escaped a quote the child should have received verbatim: $echoedOut",
        )

        // The A05 shape: an inner cmd composes the redirect target, which contains a space. The
        // outer `^>` keeps the redirect for the inner shell (the outer one would eat it), and the
        // quoted target must arrive intact instead of becoming a literal `\"…\"`.
        val target = tempDir.resolve("quoted target.txt")
        val inner = "cmd /v:on /c echo probe ^> \"$target\""
        val redirect = startWithCurrentToken(spec("cmd.exe", "/c", inner)) ?: return
        val redirectOut = readAll(redirect)
        val redirectErr = redirect.errorStream.bufferedReader().use { it.readText() }
        assertTrue(redirect.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, redirect.exitValue(), "stdout=$redirectOut stderr=$redirectErr")
        assertTrue(
            Files.isRegularFile(target),
            "the quoted redirect target must exist; stdout=$redirectOut stderr=$redirectErr",
        )
        assertEquals("probe", Files.readString(target).trim())
    }

    @Test
    @DisplayName("a quoted -File path runs instead of failing with a usage error")
    fun quotedScriptPathRuns() {
        assumeTrue(isWindows, "the token launch only exists on Windows")

        val script = tempDir.resolve("probe script.ps1")
        Files.writeString(script, "'PROBE_QUOTED_OK'\n")
        val process = startWithCurrentToken(
            spec("cmd.exe", "/c", "pwsh -NoProfile -File \"$script\""),
        ) ?: return
        val stdout = readAll(process)
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the child must exit")
        assertEquals(0, process.exitValue(), "stdout=$stdout stderr=$stderr")
        assertTrue(stdout.contains("PROBE_QUOTED_OK"), "stdout=$stdout stderr=$stderr")
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
