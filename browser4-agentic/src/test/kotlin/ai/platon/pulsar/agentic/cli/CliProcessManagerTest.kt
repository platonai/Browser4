package ai.platon.pulsar.agentic.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

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
}
