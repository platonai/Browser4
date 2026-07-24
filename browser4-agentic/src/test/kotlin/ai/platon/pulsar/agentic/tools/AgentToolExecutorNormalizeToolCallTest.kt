package ai.platon.pulsar.agentic.tools

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

    // =========================================================================
    // normalizeBrowserTabArguments — switchTab / closeTab positional arg heuristics
    // =========================================================================

    @Test
    fun normalizeBrowserTabArgumentsPreservesNamedTabId() {
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "browser", "switchTab",
            mutableMapOf("tabId" to "DEADBEEF000000000000000000000000")
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("browser", normalized.domain)
        assertEquals("switchTab", normalized.method)
        assertEquals("DEADBEEF000000000000000000000000", normalized.arguments["tabId"])
        assert(!normalized.arguments.containsKey("index"))
    }

    @Test
    fun normalizeBrowserTabArgumentsPreservesNamedIndex() {
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "browser", "switchTab",
            mutableMapOf("index" to 3)
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("browser", normalized.domain)
        assertEquals(3, normalized.arguments["index"])
        assert(!normalized.arguments.containsKey("tabId"))
    }

    @Test
    fun normalizeBrowserTabArgumentsSinglePositionalStringBecomesTabId() {
        // When a single positional arg "0" is a string, treat it as tabId (GUID)
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "browser", "switchTab",
            mutableMapOf("0" to "my-tab-guid")
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("my-tab-guid", normalized.arguments["tabId"])
        assert(!normalized.arguments.containsKey("index"))
        assert(!normalized.arguments.containsKey("0"))
    }

    @Test
    fun normalizeBrowserTabArgumentsSinglePositionalNumberBecomesIndex() {
        // When a single positional arg "0" is a number, treat it as index
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "browser", "switchTab",
            mutableMapOf("0" to 2)
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals(2, normalized.arguments["index"])
        assert(!normalized.arguments.containsKey("tabId"))
        assert(!normalized.arguments.containsKey("0"))
    }

    @Test
    fun normalizeBrowserTabArgumentsNamedTabIdPreventsPositionalHeuristic() {
        // When tabId is already named, positional "0" should NOT be treated as tabId
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "browser", "switchTab",
            mutableMapOf("tabId" to "explicit-guid", "0" to "ignored-positional")
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("explicit-guid", normalized.arguments["tabId"])
        // positional "0" maps to index since tabId is already filled
        assert(!normalized.arguments.containsKey("0"))
    }

    @Test
    fun normalizeBrowserTabArgumentsCloseTabUsesSameHeuristicAsSwitchTab() {
        // closeTab must use the same normalizeBrowserTabArguments path
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "browser", "closeTab",
            mutableMapOf("0" to "close-this-guid")
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("browser", normalized.domain)
        assertEquals("closeTab", normalized.method)
        assertEquals("close-this-guid", normalized.arguments["tabId"])
        assert(!normalized.arguments.containsKey("index"))
    }

    @Test
    fun normalizeBrowserTabArgumentsPreservesBothTabIdAndIndexWhenNamed() {
        // Both explicitly named arguments should pass through
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "browser", "closeTab",
            mutableMapOf("index" to 1, "tabId" to "some-guid")
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals(1, normalized.arguments["index"])
        assertEquals("some-guid", normalized.arguments["tabId"])
    }

    @Test
    fun normalizeBrowserTabArgumentsNoArgsPassesThrough() {
        // No index, no tabId — resolveTabDriver will throw "Missing parameter"
        val executor = AgentToolManager(Files.createTempDirectory("agent-tool-normalize"), agent)
        val toolCall = ToolCall(
            "browser", "switchTab",
            mutableMapOf()
        )

        val normalized = executor.normalizeToolCall(toolCall)

        assertEquals("browser", normalized.domain)
        assertEquals("switchTab", normalized.method)
        assert(normalized.arguments.isEmpty())
    }
}
