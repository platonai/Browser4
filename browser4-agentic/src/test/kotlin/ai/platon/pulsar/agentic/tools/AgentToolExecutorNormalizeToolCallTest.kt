package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class AgentToolExecutorNormalizeToolCallTest {

    private val agent = mockk<BasicBrowserAgent>(relaxed = true)

    @Test
    fun normalizeToolCallMapsDriverPositionalArgsToNamedArgs() {
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall("tab", "fill", mutableMapOf("0" to "#search", "1" to "Browser4"))

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("tab", normalized.domain)
        assertEquals("#search", normalized.arguments["selector"])
        assertEquals("Browser4", normalized.arguments["text"])
    }

    @Test
    fun normalizeToolCallNormalizesBrowserAliasAndTabArgument() {
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall("browser", "switchTab", mutableMapOf("0" to "tab-2"))

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("browser", normalized.domain)
        assertEquals("tab-2", normalized.arguments["tabId"])
    }

    @Test
    fun normalizeToolCallMapsBrowserNumericPositionalArgToIndex() {
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall("browser", "switchTab", mutableMapOf("0" to 2))

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("browser", normalized.domain)
        assertEquals(2, normalized.arguments["index"])
    }

    @Test
    fun normalizeToolCallMapsEvalExpressionPositionalArgToNamedArg() {
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall("tab", "eval", mutableMapOf("0" to "document.title"))

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("tab", normalized.domain)
        assertEquals("document.title", normalized.arguments["expression"])
    }

    @Test
    fun normalizeToolCallMapsEvalExpressionAndSelectorPositionalArgsToNamedArgs() {
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "tab",
            "eval",
            mutableMapOf(
                "0" to "(element) => element.textContent",
                "1" to "#page-marker"
            )
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("tab", normalized.domain)
        assertEquals("#page-marker", normalized.arguments["selector"])
        assertEquals("(element) => element.textContent", normalized.arguments["expression"])
    }
}
