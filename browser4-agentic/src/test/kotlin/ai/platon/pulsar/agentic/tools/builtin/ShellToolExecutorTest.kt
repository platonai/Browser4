package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.common.AgentShell
import ai.platon.pulsar.agentic.model.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ShellToolExecutorTest {

    private lateinit var shell: AgentShell
    private lateinit var executor: ShellToolExecutor

    @BeforeEach
    fun setUp() {
        shell = mockk(relaxed = true)
        executor = ShellToolExecutor()
    }

    @Test
    fun testExecuteCallsShellExecuteWithCorrectArgs() = runBlocking {
        coEvery { shell.execute(any(), any(), any()) } returns "success output"

        val tc = ToolCall(
            domain = "shell",
            method = "execute",
            arguments = mutableMapOf("command" to "echo hello", "timeoutSeconds" to "10")
        )

        executor.callFunctionOn(tc, shell)
        coVerify { shell.execute("echo hello", 10L, null) }
    }

    @Test
    fun testExecuteWithDefaultTimeout() = runBlocking {
        coEvery { shell.execute(any(), any(), any()) } returns "success output"

        val tc = ToolCall(
            domain = "shell",
            method = "execute",
            arguments = mutableMapOf("command" to "ls -la")
        )

        executor.callFunctionOn(tc, shell)
        coVerify { shell.execute("ls -la", 30L, null) }
    }

    @Test
    fun testExecuteWithWorkingDir() = runBlocking {
        coEvery { shell.execute(any(), any(), any()) } returns "success output"

        val tc = ToolCall(
            domain = "shell",
            method = "execute",
            arguments = mutableMapOf("command" to "pwd", "workingDir" to "/tmp")
        )

        executor.callFunctionOn(tc, shell)
        coVerify { shell.execute("pwd", 30L, "/tmp") }
    }

    @Test
    fun testReadOutputCallsShellReadOutput() = runBlocking {
        every { shell.readOutput(any()) } returns "previous output"

        val tc = ToolCall(
            domain = "shell",
            method = "readOutput",
            arguments = mutableMapOf("sessionId" to "shell-1")
        )

        executor.callFunctionOn(tc, shell)
        verify { shell.readOutput("shell-1") }
    }

    @Test
    fun testGetStatusCallsShellGetStatus() = runBlocking {
        every { shell.getStatus(any()) } returns "status info"

        val tc = ToolCall(
            domain = "shell",
            method = "getStatus",
            arguments = mutableMapOf("sessionId" to "shell-1")
        )

        executor.callFunctionOn(tc, shell)
        verify { shell.getStatus("shell-1") }
    }

    @Test
    fun testListSessionsCallsShellListSessions() = runBlocking {
        every { shell.listSessions() } returns "session list"

        val tc = ToolCall(
            domain = "shell",
            method = "listSessions",
            arguments = mutableMapOf()
        )

        executor.callFunctionOn(tc, shell)
        verify { shell.listSessions() }
    }

    @Test
    fun testUnsupportedMethodReturnsException() = runBlocking {
        val tc = ToolCall(
            domain = "shell",
            method = "unsupportedMethod",
            arguments = mutableMapOf()
        )

        val result = executor.callFunctionOn(tc, shell)
        assertNotNull(result.exception)
        assertTrue(result.exception?.cause?.message?.contains("Unsupported") == true)
    }

    @Test
    fun testMissingRequiredParameterReturnsException() = runBlocking {
        val tc = ToolCall(
            domain = "shell",
            method = "execute",
            arguments = mutableMapOf()
            // missing "command" parameter
        )

        val result = executor.callFunctionOn(tc, shell)
        assertNotNull(result.exception)
        assertTrue(result.exception?.cause?.message?.contains("command") == true)
    }

    @Test
    fun testWrongDomainReturnsException() = runBlocking {
        val tc = ToolCall(
            domain = "wrong",
            method = "execute",
            arguments = mutableMapOf("command" to "echo hello")
        )

        val result = executor.callFunctionOn(tc, shell)
        assertNotNull(result.exception)
    }

    @Test
    fun testHelpReturnsAvailableShellMethods() {
        val help = executor.help()

        assertNotNull(help)
        assertTrue(help.isNotBlank())
        assertTrue(help.contains("Execute a shell command") || help.contains("execute"))
    }

    @Test
    fun testHelpForExecuteReturnsDetailedHelp() {
        val help = executor.help("execute")

        assertNotNull(help)
        assertTrue(help.contains("Execute a shell command"))
    }

    @Test
    fun testHelpForReadOutputReturnsDetailedHelp() {
        val help = executor.help("readOutput")

        assertNotNull(help)
        assertTrue(help.contains("Read the output"))
    }

    @Test
    fun testHelpForAllMethodsIsAvailable() {
        val methods = listOf("execute", "readOutput", "getStatus", "listSessions")

        methods.forEach { method ->
            val help = executor.help(method)
            assertNotNull(help, "Help for $method should not be null")
            assertTrue(help.isNotBlank(), "Help for $method should not be blank")
        }
    }

    @Test
    fun testHelpForUnknownMethodReturnsEmptyString() {
        val help = executor.help("unknownMethod")
        assertEquals("", help)
    }

    @Test
    fun testDomainPropertyIsShell() {
        assertEquals("shell", executor.domain)
    }

    @Test
    fun testTargetClassIsAgentShell() {
        assertEquals(AgentShell::class, executor.receiverClass)
    }
}
