package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.agentic.model.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CodingToolExecutorTokenStatsTest {

    private lateinit var shell: CodingAgentShell
    private lateinit var fs: CodingAgentFileSystem
    private lateinit var target: CodingToolExecutor.Target
    private lateinit var executor: CodingToolExecutor

    @BeforeEach
    fun setUp() {
        shell = mockk(relaxed = true)
        fs = mockk(relaxed = true)
        target = CodingToolExecutor.Target(shell, fs)
        executor = CodingToolExecutor()
    }

    private fun tc(method: String, args: Map<String, Any?> = emptyMap()) = ToolCall(
        domain = "coding", method = method, arguments = args.toMutableMap()
    )

    @Test
    @DisplayName("successful tool calls are recorded with input and output tokens")
    fun testRecordsSuccessfulCall() = runBlocking {
        coEvery { fs.readFile(any<String>()) } returns "fun main() {}"

        executor.callFunctionOn(tc("read", mapOf("path" to "Foo.kt")), target)

        val snap = executor.tokenStats.snapshot().single()
        assertEquals("read", snap.method)
        assertEquals(1L, snap.calls)
        assertEquals(0L, snap.errors)
        assertTrue(snap.inTokens > 0)
        assertTrue(snap.outTokens > 0)
    }

    @Test
    @DisplayName("failed tool calls are recorded as errors")
    fun testRecordsFailedCall() = runBlocking {
        // No stubbing: fs.readFile on a relaxed mock returns "" — force a failure instead
        coEvery { fs.readFile(any<String>()) } throws IllegalArgumentException("not allowed")

        val result = executor.callFunctionOn(tc("read", mapOf("path" to "X.kt")), target)
        assertFalse(result.success)

        val snap = executor.tokenStats.snapshot().single()
        assertEquals(1L, snap.calls)
        assertEquals(1L, snap.errors)
    }

    @Test
    @DisplayName("meta tools are not recorded")
    fun testMetaToolsNotRecorded() = runBlocking {
        executor.callFunctionOn(tc("tokenStats"), target)
        executor.callFunctionOn(tc("estimateTokens", mapOf("text" to "hello world")), target)
        assertTrue(executor.tokenStats.snapshot().isEmpty())
    }

    @Test
    @DisplayName("tokenStats tool reports recorded usage and reset clears counters")
    fun testTokenStatsTool() = runBlocking {
        coEvery { fs.readFile(any<String>()) } returns "x".repeat(500)
        executor.callFunctionOn(tc("read", mapOf("path" to "a.kt")), target)
        executor.callFunctionOn(tc("read", mapOf("path" to "b.kt")), target)

        val report = executor.callFunctionOn(tc("tokenStats"), target).value as String
        assertTrue(report.contains("2 calls"))
        assertTrue(report.contains("read"))

        val afterReset = executor.callFunctionOn(tc("tokenStats", mapOf("reset" to "true")), target).value as String
        assertTrue(afterReset.contains("2 calls"), "report is returned before the reset takes effect")
        assertTrue(executor.tokenStats.snapshot().isEmpty())
    }

    @Test
    @DisplayName("estimateTokens tool returns token and char counts")
    fun testEstimateTokensTool() = runBlocking {
        val text = "val greeting = \"hello\""
        val result = executor.callFunctionOn(tc("estimateTokens", mapOf("text" to text)), target).value as String
        assertTrue(result.contains("tokens"))
        assertTrue(result.contains("${text.length} chars"))
    }
}
