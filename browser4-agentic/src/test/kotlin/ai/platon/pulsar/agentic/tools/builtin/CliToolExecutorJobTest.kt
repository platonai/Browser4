package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.cli.CliBinaryResolver
import ai.platon.pulsar.agentic.cli.CliProcessManager
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.coding.CodingAgentShell
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("CliToolExecutor long-command job escalation")
class CliToolExecutorJobTest {

    private fun pwshPath(): Path {
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val pathVar = System.getenv().entries.firstOrNull { it.key.equals("PATH", true) }?.value
            ?: error("no PATH")
        val exe = if (isWindows) "pwsh.exe" else "pwsh"
        return pathVar.split(if (isWindows) ";" else ":")
            .map { Path.of(it).resolve(exe) }
            .firstOrNull { Files.isRegularFile(it) }
            ?: error("pwsh not found on PATH")
    }

    private fun executor(jobYieldMs: Long = 10_000) = CliToolExecutor(
        backendBaseUrl = null,
        cliProcessManager = CliProcessManager(CliBinaryResolver(explicitPath = pwshPath())),
        jobYieldMs = jobYieldMs,
    )

    private fun shell() = CodingAgentShell(baseDir = Path.of(System.getProperty("user.dir")))

    @Test
    @DisplayName("quick commands return their result directly (no job handle)")
    fun quickCommandReturnsResult() = runBlocking {
        // Windows Store pwsh cold start can take several seconds; use a wide
        // yield window so a genuinely quick command is not escalated.
        val ex = executor(jobYieldMs = 30_000)
        try {
            val text = ex.callFunctionOn(
                ToolCall(
                    domain = "cli", method = "run",
                    arguments = mutableMapOf(
                        "args" to "-NoProfile -Command \"Write-Output job-ok\"",
                        "timeoutSeconds" to 30L,
                    )
                ),
                shell(),
            ).value?.toString() ?: ""

            assertTrue(text.contains("job-ok"), "quick result expected, got: $text")
            assertFalse(text.startsWith("[job:"), "quick command must not escalate: $text")
        } finally {
            ex.closeJobs()
        }
    }

    @Test
    @DisplayName("long commands escalate to a job handle, then status/wait/kill work")
    fun longCommandEscalatesToJob() = runBlocking {
        val ex = executor(jobYieldMs = 500)
        try {
            val text = ex.callFunctionOn(
                ToolCall(
                    domain = "cli", method = "run",
                    arguments = mutableMapOf(
                        "args" to "-NoProfile -Command \"Start-Sleep -Seconds 60\"",
                        "timeoutSeconds" to 120L,
                    )
                ),
                shell(),
            ).value?.toString() ?: ""

            assertTrue(text.startsWith("[job:"), "expected job handle, got: $text")
            val id = Regex("\\[job: ([0-9a-f-]+)\\]").find(text)?.groupValues?.get(1)
            assertNotNull(id, "job id must be present: $text")

            val status = ex.callFunctionOn(
                ToolCall(domain = "cli", method = "status", arguments = mutableMapOf("id" to id!!)),
                shell(),
            ).value?.toString() ?: ""
            assertTrue(status.contains("state="), "status shape: $status")

            val killed = ex.callFunctionOn(
                ToolCall(domain = "cli", method = "kill", arguments = mutableMapOf("id" to id)),
                shell(),
            ).value?.toString() ?: ""
            assertTrue(killed.contains("cancelled"), "kill result: $killed")

            val waited = ex.callFunctionOn(
                ToolCall(
                    domain = "cli", method = "wait",
                    arguments = mutableMapOf("id" to id, "timeoutSeconds" to 20L),
                ),
                shell(),
            ).value?.toString() ?: ""
            assertTrue(waited.contains("aborted"), "wait after kill should surface aborted result: $waited")
        } finally {
            ex.closeJobs()
        }
    }
}
