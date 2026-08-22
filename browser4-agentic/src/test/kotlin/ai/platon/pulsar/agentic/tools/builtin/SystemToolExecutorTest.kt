package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.AgentToolManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SystemToolExecutorTest {

    private lateinit var agentToolManager: AgentToolManager
    private lateinit var executor: SystemToolExecutor

    @BeforeEach
    fun setUp() {
        agentToolManager = mockk(relaxed = true)
        executor = SystemToolExecutor(agentToolManager)
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
        every { agentToolManager.help("fs", "writeString") } returns "File system help"

        val result = executor.help("fs", "writeString")

        assertEquals("File system help", result)
    }

    @Test
        @DisplayName("system help method executes correctly")
    fun systemHelpMethodExecutesCorrectly() = runBlocking {
        every { agentToolManager.help("tab", "click") } returns "Click help text"

        val tc = ToolCall(
            domain = "system",
            method = "help",
            arguments = mutableMapOf("domain" to "tab", "method" to "click")
        )

        val result = executor.callFunctionOn(tc, executor)

        assertEquals("Click help text", result.value)
    }

    @Test
        @DisplayName("skillDoc reads bundled browser4-cli SKILL.md from classpath")
    fun skillDocReadsBundledSkill() {
        val content = executor.skillDoc("SKILL.md")

        assertTrue(content.startsWith("---"), "expected bundled SKILL.md frontmatter, got: ${content.take(60)}")
        assertTrue(content.contains("browser4-cli"), "SKILL.md must mention browser4-cli")
    }

    @Test
        @DisplayName("skillDoc lists available documents for an unknown name")
    fun skillDocUnknownNameListsAvailable() {
        val content = executor.skillDoc("does-not-exist.md")

        assertTrue(content.contains("Skill document not found"))
        assertTrue(content.contains("SKILL.md"))
        assertTrue(content.contains("x-sql.md"))
    }

    @Test
        @DisplayName("skillDoc rejects path traversal")
    fun skillDocRejectsPathTraversal() {
        val thrown = runCatching { executor.skillDoc("../../secret") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "expected IllegalArgumentException, got: $thrown")
    }

    @Test
        @DisplayName("taskComplete executes and returns a confirmation")
    fun taskCompleteReturnsConfirmation() = runBlocking {
        val tc = ToolCall(
            domain = "system",
            method = "taskComplete",
            arguments = mutableMapOf(
                "summary" to "Task done",
                "keyFindings" to listOf("k1"),
                "filesChanged" to listOf("f.md"),
                "problems" to listOf("p1"),
            )
        )

        val text = executor.callFunctionOn(tc, executor).value?.toString() ?: ""

        assertTrue(text.contains("Task marked complete"), text)
        assertTrue(text.contains("Task done"), text)
    }

    @Test
        @DisplayName("taskComplete rejects a blank summary")
    fun taskCompleteRejectsBlankSummary() {
        val thrown = runCatching { executor.taskComplete("   ", null, null, null) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "expected IllegalArgumentException, got: $thrown")
    }

    @Test
        @DisplayName("TaskCompletion parses native tool-call arguments JSON")
    fun taskCompletionFromJsonParses() {
        val completion = TaskCompletion.fromJson(
            """{"summary":"done","keyFindings":["k1"],"filesChanged":["f1"],"problems":["p1"]}"""
        )

        assertEquals("done", completion.summary)
        assertTrue(completion.keyFindings == listOf("k1"), "keyFindings: ${completion.keyFindings}")
        assertTrue(completion.filesChanged == listOf("f1"), "filesChanged: ${completion.filesChanged}")
        assertTrue(completion.problems == listOf("p1"), "problems: ${completion.problems}")
    }

    @Test
        @DisplayName("domain property is system")
    fun domainPropertyIsSystem() {
        assertEquals("system", executor.domain)
    }
}
