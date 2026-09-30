package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.mcp.McpToolNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The private MCP dispatcher and the standard MCP server must advertise the same
 * `browser_*` frontend names: a model that learned a name on one channel must not
 * get "unknown tool" on the other.
 */
@DisplayName("MCP frontend alias parity (rest)")
class McpToolAliasParityTest {

    @Test
    @DisplayName("the dispatcher aliases exactly the frontend names the shared table defines")
    fun dispatcherAliasesMatchSharedTable() {
        assertEquals(
            McpToolNames.frontendAliasNames,
            MCPToolController.FRONTEND_TOOL_NAME_ALIASES.keys,
            "MCPToolController.FRONTEND_TOOL_NAME_ALIASES drifted from McpToolNames.frontendAliases"
        )
    }

    @Test
    @DisplayName("every shared alias also names the tab domain method it stands for")
    fun sharedAliasesTargetTabTools() {
        val aliasTargets = McpToolNames.frontendAliases.associate { it.frontendName to it.domain }

        assertEquals(
            McpToolNames.frontendAliasNames.size,
            aliasTargets.size,
            "Duplicate frontend alias in McpToolNames"
        )
        assertEquals(setOf("tab"), aliasTargets.values.toSet())
    }
}
