package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.agentic.model.ToolCall
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for `coding.verify` (plan P0.1): the deterministic verification gate
 * the finish report and the CLI are anchored to.
 *
 * Uses a real temporary workspace and a real [CodingAgentShell] so the paths
 * exercised here are the ones the agent uses; only checks that would build
 * (compile/test) are kept out of the happy path — a gate whose "green" depends
 * on a multi-minute Maven build is not a unit test.
 */
class CodingToolExecutorVerifyTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var shell: CodingAgentShell
    private lateinit var fs: CodingAgentFileSystem
    private lateinit var executor: CodingToolExecutor
    private lateinit var target: CodingToolExecutor.Target

    private val mapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        shell = CodingAgentShell(baseDir = tempDir)
        fs = CodingAgentFileSystem(tempDir)
        executor = CodingToolExecutor()
        target = CodingToolExecutor.Target(shell, fs)
    }

    private fun verifyCall(vararg args: Pair<String, Any?>): ToolCall = ToolCall(
        domain = "coding",
        method = "verify",
        arguments = linkedMapOf(*args),
    )

    @Test
    @DisplayName("trapCheck on a driver file passes and reports the advisory")
    fun trapCheckPasses() = runBlocking {
        val file = tempDir.resolve("browser4-core/browser4-browser/src/PulsarWebDriver.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "// Input.insertText is used here with a delay\n")

        val result = executor.callFunctionOn(
            verifyCall("checks" to "trapCheck", "files" to "browser4-core/browser4-browser/src/PulsarWebDriver.kt"),
            target,
        )
        val text = result.value.toString()
        assertTrue(text.contains("Verification: PASSED"), "text: $text")
        assertTrue(text.contains("insertText"), "advisory missing: $text")
        val verdict = mapper.readTree(ai.platon.pulsar.coding.VerificationReport.extractJson(text)!!)
        assertEquals(true, verdict["passed"].asBoolean())
        assertEquals("trapCheck", verdict["checks"][0]["name"].asText())
        assertEquals(true, verdict["checks"][0]["ran"].asBoolean())
    }

    @Test
    @DisplayName("trapCheck on an unreadable file fails the verdict")
    fun trapCheckMissingFileFails() = runBlocking {
        val result = executor.callFunctionOn(
            verifyCall("checks" to "trapCheck", "files" to "does/not/Exist.kt"),
            target,
        )
        val text = result.value.toString()
        assertTrue(text.contains("Verification: FAILED"), "text: $text")
        assertTrue(text.contains("Reason:"), "text: $text")
    }

    @Test
    @DisplayName("compile without a module never reports green")
    fun compileWithoutModuleIsNotGreen() = runBlocking {
        val result = executor.callFunctionOn(verifyCall("checks" to "compile", "json" to "true"), target)
        val verdict = mapper.readTree(result.value.toString())
        assertEquals(false, verdict["passed"].asBoolean())
        assertEquals(false, verdict["checks"][0]["ran"].asBoolean())
        assertTrue(verdict["failureReason"].asText().contains("did not run"), "verdict: $verdict")
    }

    @Test
    @DisplayName("json=true returns pure JSON with the documented fields")
    fun jsonModeReturnsPureJson() = runBlocking {
        val file = tempDir.resolve("Driver.kt")
        Files.writeString(file, "val x = 1\n")
        val result = executor.callFunctionOn(
            verifyCall("checks" to "trapCheck", "files" to "Driver.kt", "json" to "true"),
            target,
        )
        val text = result.value.toString()
        assertTrue(text.trimStart().startsWith("{"), "expected pure JSON, got: $text")
        val verdict = mapper.readTree(text)
        assertNotNull(verdict["checks"])
        assertTrue(verdict["checks"].isArray)
        assertTrue(verdict["checks"][0].has("durationMs"))
        assertTrue(verdict["checks"][0].has("evidence"))
    }

    @Test
    @DisplayName("an unknown check name fails loudly instead of silently passing")
    fun unknownCheckFailsLoudly() = runBlocking {
        val result = executor.callFunctionOn(verifyCall("checks" to "does-not-exist"), target)
        val text = result.value.toString()
        assertTrue(text.contains("Verification: FAILED"), "text: $text")
        assertTrue(text.contains("unknown check"), "text: $text")
    }

    @Test
    @DisplayName("verify is listed in the tool specs so agents can discover it")
    fun verifyIsAdvertised() {
        val spec = executor.getToolSpecs()["verify"]
        assertNotNull(spec, "coding.verify must be registered")
        assertTrue(spec!!.description!!.contains("verdict"), "description: ${spec.description}")
    }
}
