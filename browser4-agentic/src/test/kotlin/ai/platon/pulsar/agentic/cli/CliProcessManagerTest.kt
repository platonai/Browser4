package ai.platon.pulsar.agentic.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Base64

@DisplayName("CliProcessManager M1 core")
class CliProcessManagerTest {

    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun pwshPath(): Path {
        val pathVar = System.getenv().entries.firstOrNull { it.key.equals("PATH", true) }?.value
            ?: error("no PATH")
        val exe = if (isWindows) "pwsh.exe" else "pwsh"
        return pathVar.split(if (isWindows) ";" else ":")
            .map { Path.of(it).resolve(exe) }
            .firstOrNull { Files.isRegularFile(it) }
            ?: error("pwsh not found on PATH")
    }

    private fun manager(config: CliProcessConfig = CliProcessConfig()) =
        CliProcessManager(CliBinaryResolver(explicitPath = pwshPath()), config)

    private fun sleepCmd(seconds: Int) =
        "-NoProfile -Command \"Start-Sleep -Seconds $seconds\""

    @Test
    @DisplayName("resolve fills defaults and clamps timeout")
    fun resolveCaps() {
        val mgr = manager()
        val spec = mgr.resolve(CliRunRequest(args = "goto --url https://example.com"))
        assertTrue(spec.timeoutMs == 120_000L, "default timeout")
        assertTrue(spec.outputBufferBytes == 1 shl 20, "default output buffer")
        assertTrue(spec.env.containsKey("BROWSER4_CLI_DISABLE_PLUGIN_WARM_RESTART"))
        assertFalse(spec.env.containsKey("DEEPSEEK_API_KEY"))

        val capped = mgr.resolve(CliRunRequest(args = "x", timeoutSeconds = 9999))
        assertTrue(capped.timeoutMs == 600_000L, "timeout capped at max")

        val min = mgr.resolve(CliRunRequest(args = "x", timeoutSeconds = 1))
        assertTrue(min.timeoutMs == 1_000L, "timeout floor")
    }

    @Test
    @Tag("Slow")
    @DisplayName("timeout and cancel are mutually exclusive: timeout wins")
    fun timeoutAttribution() = runBlocking {
        val mgr = manager()
        val result = mgr.run(
            CliRunRequest(args = sleepCmd(30), timeoutSeconds = 1),
            backendBaseUrl = null,
        )
        assertTrue(result.timedOut, "expected timedOut, got $result")
        assertFalse(result.aborted, "aborted must be false when timedOut")
        assertTrue(result.durationMs < 30_000, "timeout kill should be fast")
    }

    @Test
    @Tag("Slow")
    @DisplayName("cancel token produces aborted (not timedOut)")
    fun abortAttribution() = runBlocking {
        val mgr = manager()
        val token = Job()
        val deferred = CoroutineScope(Dispatchers.Default).async {
            mgr.run(CliRunRequest(args = sleepCmd(30), timeoutSeconds = 30), cancelToken = token)
        }
        delay(500)
        token.cancel()
        val result = deferred.await()
        assertTrue(result.aborted, "expected aborted, got $result")
        assertFalse(result.timedOut, "timedOut must be false when aborted")
    }

    @Test
    @DisplayName("env is whitelisted: PATH kept, secrets not inherited")
    fun envWhitelist() = runBlocking {
        val mgr = manager()
        val result = mgr.run(
            CliRunRequest(
                args = "-NoProfile -Command \"Write-Output ('HAS_PATH=' + [bool]\$env:PATH); " +
                    "Write-Output ('KEY=' + \$env:DEEPSEEK_API_KEY)\""
            ),
            backendBaseUrl = null,
        )
        assertTrue(result.isSuccess, "result: $result")
        assertTrue(result.stdout.contains("HAS_PATH=True"), "PATH must be injected: ${result.stdout}")
        assertTrue(result.stdout.contains("KEY="), "output shape: ${result.stdout}")
        assertFalse(result.stdout.contains("KEY=sk-"), "secret must not leak: ${result.stdout}")
    }

    @Test
    @DisplayName("b4.run inherits the checkout's CLI state namespace")
    fun stateNamespacePassthrough() {
        // b4.run's cwd is the task workspace, outside the checkout. Without the
        // invocation dir the child CLI resolved the global ~/.browser4 state, saw
        // a stale session and re-attached — creating extra sessions and browsers
        // instead of driving the session it was given.
        val mgr = CliProcessManager(
            CliBinaryResolver(explicitPath = pwshPath()),
            processEnv = mapOf(
                "PATH" to (System.getenv("PATH") ?: ""),
                "BROWSER4_CLI_INVOKE_DIR" to "/repo/checkout",
                "BROWSER4_CLI_STATE_DIR" to "/state/dir",
            ),
        )

        val spec = mgr.resolve(CliRunRequest(args = "status"))

        assertTrue(
            spec.env["BROWSER4_CLI_INVOKE_DIR"] == "/repo/checkout",
            "invocation dir must reach the CLI: ${spec.env}"
        )
        assertTrue(
            spec.env["BROWSER4_CLI_STATE_DIR"] == "/state/dir",
            "state dir must reach the CLI: ${spec.env}"
        )
    }

    @Test
    @DisplayName("a production backend does not invent a state namespace")
    fun stateNamespaceNotInvented() {
        val mgr = CliProcessManager(
            CliBinaryResolver(explicitPath = pwshPath()),
            processEnv = mapOf("PATH" to (System.getenv("PATH") ?: "")),
        )

        val spec = mgr.resolve(CliRunRequest(args = "status"))

        assertFalse(spec.env.containsKey("BROWSER4_CLI_INVOKE_DIR"), "env: ${spec.env}")
        assertFalse(spec.env.containsKey("BROWSER4_CLI_STATE_DIR"), "env: ${spec.env}")
    }

    @Test
    @Tag("Slow")
    @DisplayName("tree kill: grandchild is terminated with the parent")
    fun treeKill() = runBlocking {
        assumeTrue(isWindows, "tree-kill test uses Windows process tree")
        // Tree: outer pwsh -> cmd -> ping. NOTE: `start /b` must NOT be used —
        // it goes through ShellExecute and the child escapes taskkill /T's tree
        // (empirically verified), which is exactly why the design wants Job
        // Objects on Windows.
        val cmdExe = Path.of(System.getenv().entries.firstOrNull { it.key.equals("SystemRoot", true) }?.value
            ?: "C:\\Windows", "System32", "cmd.exe")
        val mgr = CliProcessManager(CliBinaryResolver(explicitPath = cmdExe))
        val args = "/c \"ping -n 120 127.0.0.1 > nul\""
        val before = pingPids()
        val result = mgr.run(CliRunRequest(args = args, timeoutSeconds = 2), backendBaseUrl = null)
        assertTrue(result.timedOut, "expected timeout kill: $result")

        // Poll until the new ping (grandchild) is gone.
        var leftover = pingPids() - before
        repeat(20) {
            leftover = pingPids() - before
            if (leftover.isEmpty()) return@repeat
            Thread.sleep(500)
        }
        assertTrue(leftover.isEmpty(), "grandchild pings must be dead after tree kill, still alive: $leftover")
    }

    private fun pingPids(): Set<Long> {
        val probe = ProcessBuilder("powershell", "-NoProfile", "-Command",
            "(Get-Process ping -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id) -join ','")
            .redirectErrorStream(true).start()
        probe.waitFor()
        val text = probe.inputStream.bufferedReader().readText().trim()
        return if (text.isEmpty()) emptySet() else text.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    @Test
    @DisplayName("backend pre-check fails fast instead of letting CLI start a server")
    fun backendHealthFailFast() = runBlocking {
        val mgr = manager(CliProcessConfig(backendHealthTimeoutMs = 1_000))
        val start = System.currentTimeMillis()
        val result = mgr.run(
            CliRunRequest(args = sleepCmd(1)),
            backendBaseUrl = "http://127.0.0.1:1",
        )
        val duration = System.currentTimeMillis() - start
        assertNotNull(result.infraFailure, "expected infra failure, got $result")
        assertTrue(result.infraFailure!!.contains("Backend unreachable"), result.infraFailure)
        assertTrue(duration < 5_000, "must fail fast, took ${duration}ms")
    }

    @Test
    @DisplayName("per-session concurrency cap rejects the overflow")
    fun concurrencyRejects() = runBlocking {
        val mgr = manager(
            CliProcessConfig(
                maxConcurrentPerSession = 1,
                maxConcurrentGlobal = 4,
                queueWaitMs = 300,
            )
        )
        val scope = CoroutineScope(Dispatchers.Default)
        val a = scope.async { mgr.run(CliRunRequest(args = sleepCmd(2), sessionId = "s1"), null) }
        val b = scope.async { mgr.run(CliRunRequest(args = sleepCmd(2), sessionId = "s1"), null) }
        val results = listOf(a.await(), b.await())
        assertTrue(results.count { it.rejected } == 1, "exactly one rejected: $results")
        assertTrue(results.count { it.isSuccess } == 1, "exactly one success: $results")
    }

    @Test
    @DisplayName("a long command gets its own global --timeout, once and only for the CLI")
    fun timeoutInjection() {
        // The child used the CLI's 30s MCP default: a 20-page `eval --await` batch was
        // cut into five retries, each an unexplained timeout (2026-10-28 run).
        assertEquals(
            "--timeout 300 eval --file x.js",
            injectCliGlobalTimeout("browser4-cli.exe", "eval --file x.js", 300),
        )
        assertEquals(
            "--timeout 60 --version",
            injectCliGlobalTimeout("browser4-cli", "--version", 60),
        )
        // ≤30s is a deliberate fast-fail budget; the outer kill must not be pushed out.
        assertEquals("eval --file x.js", injectCliGlobalTimeout("browser4-cli", "eval --file x.js", 30))
        // An explicit --timeout (both spellings) is never duplicated.
        assertEquals(
            "eval --timeout 120 --file x.js",
            injectCliGlobalTimeout("browser4-cli", "eval --timeout 120 --file x.js", 300),
        )
        assertEquals(
            "eval --timeout=120",
            injectCliGlobalTimeout("browser4-cli", "eval --timeout=120", 300),
        )
        // Other binaries go through this manager in tests: never inject a CLI flag.
        assertEquals(
            "-NoProfile -Command \"Write-Output x\"",
            injectCliGlobalTimeout("pwsh.exe", "-NoProfile -Command \"Write-Output x\"", 300),
        )
        assertTrue(hasCliTimeoutFlag("--timeout 120 eval"))
        assertTrue(
            hasCliTimeoutFlag("eval --timeout 120"),
            "a caller-named deadline anywhere must suppress the injection",
        )
        assertFalse(hasCliTimeoutFlag("eval --timeouts 120"))
    }

    @Test
    @DisplayName("the encoded script carries the command verbatim, not through argv")
    fun encodedScriptKeepsTheCommandVerbatim() {
        val command = "'browser4-cli.exe' eval --js \"JSON.stringify({url: location.href})\""
        val preamble = "preamble; "
        val script = buildEncodedCommandScript(command, preamble)
        val expectedPayload = "& " + escapeForPowerShellScript(command)
        val payload = Base64.getEncoder()
            .encodeToString(expectedPayload.toByteArray(StandardCharsets.UTF_8))

        assertTrue(script.startsWith(preamble), script)
        assertTrue(script.contains(payload), "the command must travel as a base64 payload")
        assertTrue(script.contains("[ScriptBlock]::Create"), script)
        assertTrue(script.contains("exit \$LASTEXITCODE"), "the child exit code must be propagated")
        // Nothing of the command's own quoting may survive into the outer script: that is
        // exactly what PowerShell's -Command parser ate (2026-10-29 Gate run).
        assertFalse(script.contains("JSON.stringify"), "raw command text leaked into the script")
        assertFalse(script.contains("\""), "no double quote may remain in the outer script")
        // The block must be a command invocation, not a string literal followed by args.
        assertTrue(
            String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8).startsWith("& '"),
            "the script block must invoke the binary with &",
        )
    }

    @Test
    @DisplayName("PowerShell would expand $ and backticks in the command text; escape them")
    fun powerShellEscapeKeepsTheCommandLiteral() {
        assertEquals(
            "`\$env:PATH and ``backtick``",
            escapeForPowerShellScript("\$env:PATH and `backtick`"),
        )
        // The escaped text must decode back to the literal command, byte for byte after
        // the PowerShell parse: the doubling is what makes `$env:` reach the child.
        val payload =
            "'cli.exe' eval --js \"`\$('a')`\"".let { escapeForPowerShellScript(it) }
        assertTrue(payload.contains("`\$('a')"), payload)
        assertTrue(payload.contains("``"), "backticks must be doubled: $payload")
    }

    @Test
    @DisplayName("the encoded command is a UTF-16LE base64 script")
    fun encodedCommandIsBase64Utf16() {
        val encoded = encodePowerShellCommand("'x.exe' e --js \"a b\"", "")
        assertNotNull(encoded, "a normal command must produce an encoded script")
        val script = String(Base64.getDecoder().decode(encoded!!), StandardCharsets.UTF_16LE)
        assertTrue(script.contains("[ScriptBlock]::Create"), script)
        assertTrue(script.contains("exit \$LASTEXITCODE"), script)
    }

    @Test
    @DisplayName("a command too long for a Windows command line falls back instead of truncating")
    fun encodedCommandRefusesAnOverlongPayload() {
        val command = "eval --js \"" + "x".repeat(40_000) + "\""
        assertEquals(null, encodePowerShellCommand(command, ""))
    }

    @Test
    @Tag("Slow")
    @DisplayName("a quoted argument reaches the target program intact")
    fun quotedArgumentSurvivesTheShell() = runBlocking {
        // Regression for the 2026-10-29 Gate run: `eval --js "JSON.stringify({...})"`
        // arrived at the CLI without its quotes because ProcessBuilder's argv quoting
        // was undone by PowerShell's own -Command parser. The inner pwsh prints
        // "a b" on one line only when it receives the quotes.
        val mgr = manager()
        val result = mgr.run(
            CliRunRequest(args = "-NoProfile -Command \"Write-Output 'a b'\""),
            backendBaseUrl = null,
        )
        assertEquals(0, result.exitCode, "stdout=${result.stdout} stderr=${result.stderr}")
        assertEquals("a b", result.stdout.trim(), "the quoted argument must stay one token")
    }

    @Test
    @Tag("Slow")
    @DisplayName("a command that reads stdin sees EOF instead of hanging")
    fun stdinIsClosedForArgvCommands() = runBlocking {
        // `b4.run("eval --js \"…\"")` is `--stdin` under the hood; with the default
        // ProcessBuilder pipe open, the CLI waited on stdin until its deadline
        // (2026-10-29 smoke run: 300s). pi's shell transport spawns argv commands with
        // stdio[0] = "ignore" for the same reason.
        val mgr = manager()
        val started = System.currentTimeMillis()
        val result = mgr.run(
            CliRunRequest(
                args = "-NoProfile -Command \"[Console]::In.ReadToEnd(); Write-Output 'EOF-reached'\"",
                timeoutSeconds = 30,
            ),
            backendBaseUrl = null,
        )
        val elapsed = System.currentTimeMillis() - started
        assertTrue(result.isSuccess, "result: $result")
        assertTrue(result.stdout.contains("EOF-reached"), "stdout: ${result.stdout}")
        assertTrue(elapsed < 15_000, "must not block on stdin, took ${elapsed}ms")
    }
}
