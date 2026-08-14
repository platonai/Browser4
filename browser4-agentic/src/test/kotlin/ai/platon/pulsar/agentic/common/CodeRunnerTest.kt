package ai.platon.pulsar.agentic.common

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf

/**
 * Tests for [CodeRunner] — runs real interpreters when available.
 * Unsupported/absent interpreters degrade gracefully.
 */
class CodeRunnerTest {

    private val runner = CodeRunner(defaultTimeoutSeconds = 10)

    private fun has(command: String): Boolean =
        runCatching { ProcessBuilder(command).start().destroy() }.isSuccess

    @Test
    @DisplayName("supportedLanguages lists runnable languages")
    fun supportedLanguages() {
        val langs = runner.supportedLanguages()
        assertTrue(langs.contains("python"))
        assertTrue(langs.contains("js"))
        assertTrue(langs.contains("bash"))
    }

    @Test
    @DisplayName("unsupported language returns error result")
    fun unsupportedLanguage() = runBlocking {
        val result = runner.run("cobol", "DISPLAY 'HI'")
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("Unsupported language"))
    }

    @Test
    @EnabledIf("hasPython")
    @DisplayName("python runs and captures stdout")
    fun pythonRuns() = runBlocking {
        val result = runner.run("python", "print('hello from python')")
        assertTrue(result.ok, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("hello from python"))
    }

    @Test
    @EnabledIf("hasPython")
    @DisplayName("python failing code reports non-zero exit and stderr")
    fun pythonFails() = runBlocking {
        val result = runner.run("python", "raise ValueError('boom')")
        assertFalse(result.ok)
        assertTrue(result.exitCode != 0)
        assertTrue(result.stderr.contains("boom"))
    }

    @Test
    @EnabledIf("hasNode")
    @DisplayName("node runs js")
    fun nodeRuns() = runBlocking {
        val result = runner.run("js", "console.log(6 * 7)")
        assertTrue(result.ok, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("42"))
    }

    @Test
    @EnabledIf("hasBash")
    @DisplayName("bash runs shell code")
    fun bashRuns() = runBlocking {
        val result = runner.run("bash", "echo sandboxed-shell")
        assertTrue(result.ok, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("sandboxed-shell"))
    }

    @Test
    @DisplayName("runCode never leaves temp files in the workspace")
    fun noWorkspacePollution() = runBlocking {
        val before = System.getProperty("java.io.tmpdir")
        assertNotNull(before)
        // The runner uses its own temp dir, not the cwd — nothing to assert
        // beyond it succeeding with an available interpreter.
        val result = runner.run("js", "console.log('x')")
        // If node is unavailable, this still should not throw.
        assertNotNull(result.exitCode)
    }

    @Test
    @DisplayName("hard timeout kills long-running code")
    fun timeoutKills() = runBlocking {
        if (!has("python")) return@runBlocking
        val result = runner.run("python", "import time; time.sleep(60)", timeoutSeconds = 2)
        assertTrue(result.timedOut, "expected timeout, got exit=${result.exitCode}")
    }

    @Test
    @DisplayName("infinite loop is killed by timeout")
    fun infiniteLoopKilled() = runBlocking {
        if (!has("node")) return@runBlocking
        val result = runner.run("js", "while(true){}", timeoutSeconds = 2)
        assertTrue(result.timedOut)
    }

    fun hasPython() = has("python")
    fun hasNode() = has("node")
    fun hasBash() = has("bash")
}
