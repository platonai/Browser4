package ai.platon.pulsar.agentic.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("CliJobRegistry")
class CliJobRegistryTest {

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

    @Test
    @DisplayName("start/status/kill/await lifecycle with aborted result")
    fun lifecycle() = runBlocking {
        val mgr = CliProcessManager(CliBinaryResolver(explicitPath = pwshPath()))
        val registry = CliJobRegistry(mgr, CoroutineScope(Dispatchers.Default))
        try {
            val id = registry.start(
                CliRunRequest(args = "-NoProfile -Command \"Start-Sleep -Seconds 60\"", timeoutSeconds = 120)
            )
            assertEquals(CliJobRegistry.JobState.RUNNING, registry.status(id)?.state)
            delay(300)
            assertTrue(registry.kill(id), "kill should succeed")
            val result = registry.await(id, 20_000)
            assertTrue(result?.aborted == true, "killed job should be aborted: $result")
            assertEquals(CliJobRegistry.JobState.CANCELLED, registry.status(id)?.state)
        } finally {
            registry.close()
        }
    }
}
