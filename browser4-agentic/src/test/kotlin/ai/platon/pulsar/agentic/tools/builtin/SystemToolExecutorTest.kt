package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.AgentToolExecutor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class SystemToolExecutorTest {

    private lateinit var agentToolExecutor: AgentToolExecutor
    private lateinit var executor: SystemToolExecutor

    @BeforeEach
    fun setUp() {
        agentToolExecutor = mockk(relaxed = true)
        executor = SystemToolExecutor(agentToolExecutor)
    }

    @Test
        @DisplayName("help for help method returns detailed help")
    fun helpForHelpMethodReturnsDetailedHelp() {
        val help = executor.help("help")

        assertNotNull(help)
        assertTrue(help.contains("Get help information"))
        assertTrue(help.contains("help"))
    }

    @Test
        @DisplayName("help with domain and method delegates to agent tool manager")
    fun helpWithDomainAndMethodDelegatesToAgentToolManager() = runBlocking {
        every { agentToolExecutor.help("fs", "writeString") } returns "File system help"

        val result = executor.help("fs", "writeString")

        assertEquals("File system help", result)
    }

    @Test
        @DisplayName("system help method executes correctly")
    fun systemHelpMethodExecutesCorrectly() = runBlocking {
        every { agentToolExecutor.help("tab", "click") } returns "Click help text"

        val tc = ToolCall(
            domain = "system",
            method = "help",
            arguments = mutableMapOf("domain" to "tab", "method" to "click")
        )

        val result = executor.callFunctionOn(tc, executor)

        assertEquals("Click help text", result.value)
    }

    @Test
        @DisplayName("domain property is system")
    fun domainPropertyIsSystem() {
        assertEquals("system", executor.domain)
    }
}
