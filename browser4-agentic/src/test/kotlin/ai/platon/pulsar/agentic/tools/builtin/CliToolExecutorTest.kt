package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.CodingAgentShell
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

class CliToolExecutorTest {

    private val executor = CliToolExecutor()
    private val shell = CodingAgentShell(Paths.get("."))

    @Test
    fun `rejects agent run subcommand`() = runBlocking {
        val ex = assertThrows<IllegalArgumentException> {
            executor.callFunctionOn(
                domain = "cli", functionName = "run",
                args = mapOf("args" to "agent run 'do something'"),
                receiver = shell,
            )
        }
        assertTrue(ex.message!!.contains("Nested agent spawning"))
    }

    @Test
    fun `rejects agent-run subcommand`() = runBlocking {
        val ex = assertThrows<IllegalArgumentException> {
            executor.callFunctionOn(
                domain = "cli", functionName = "run",
                args = mapOf("args" to "agent-run 'do something'"),
                receiver = shell,
            )
        }
        assertTrue(ex.message!!.contains("Nested agent spawning"))
    }

    @Test
    fun `rejects act subcommand`() = runBlocking {
        val ex = assertThrows<IllegalArgumentException> {
            executor.callFunctionOn(
                domain = "cli", functionName = "run",
                args = mapOf("args" to "act 'do something'"),
                receiver = shell,
            )
        }
        assertTrue(ex.message!!.contains("Nested agent spawning"))
    }

    @Test
    fun `rejects leading whitespace agent run`() = runBlocking {
        val ex = assertThrows<IllegalArgumentException> {
            executor.callFunctionOn(
                domain = "cli", functionName = "run",
                args = mapOf("args" to "   agent run 'do something'"),
                receiver = shell,
            )
        }
        assertTrue(ex.message!!.contains("Nested agent spawning"))
    }
}
