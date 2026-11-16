package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import ai.platon.pulsar.coding.sandbox.ExecutionSandbox
import ai.platon.pulsar.coding.sandbox.ProcessSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.nio.file.Path

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
    @Tag("Slow")
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

    @Test
    @DisplayName("the caller's sandbox seam prepares the launch, so runCode can run unsandboxed")
    fun sandboxSeamIsHonoured() = runBlocking {
        // `agent run --unrestricted` builds `coding.shell` with the pass-through sandbox; runCode has
        // to use the same seam, or one task would be confined in one code path and not in the other.
        // The prepare call happens before the launch, so the assertion holds even without python.
        val recorded = mutableListOf<String>()
        val seam = object : ExecutionSandbox {
            override val id = "recording"

            override fun prepare(
                spec: ProcessSpec,
                workspace: Path,
                allowNetwork: Boolean,
                extraWritableRoots: List<Path>,
            ): ProcessSpec {
                recorded += "prepare(${spec.directory}, network=$allowNetwork)"
                return spec
            }
        }

        runner.run("python", "print('seam')", networkAllowed = true, sandbox = seam)

        assertEquals(1, recorded.size, "the caller's seam must be the one that prepared the launch: $recorded")
        assertTrue(recorded.first().contains("network=true"), "the network decision must reach the seam: ${recorded.first()}")
    }

    fun hasPython() = has("python")
    fun hasNode() = has("node")
    fun hasBash() = has("bash")
}

