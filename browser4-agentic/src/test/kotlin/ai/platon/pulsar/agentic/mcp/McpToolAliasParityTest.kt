package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.agentic.tools.AgenticCliRunner
import ai.platon.pulsar.agentic.tools.AgenticCliRunner.Companion.FRONTEND_TOOL_NAME_ALIASES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The `browser_*` alias table exists in three places (this shared table, the
 * private MCP dispatcher, and the agent CLI runner). They had already drifted —
 * the runner was missing five entries — so their key sets are asserted here.
 */
@DisplayName("MCP frontend alias parity (agentic)")
class McpToolAliasParityTest {

    @Test
    @DisplayName("the agent CLI runner aliases every frontend name the shared table defines")
    fun agentCliRunnerAliasesEverySharedFrontendName() {
        assertEquals(
            McpToolNames.frontendAliasNames, FRONTEND_TOOL_NAME_ALIASES.keys,
            "AgenticCliRunner.FRONTEND_TOOL_NAME_ALIASES drifted from McpToolNames.frontendAliases"
        )
    }

    @Test
    @DisplayName("no table advertises an alias the other one cannot resolve")
    fun tablesAgreeOnEveryAlias() {
        val shared = McpToolNames.frontendAliasNames
        val runner = FRONTEND_TOOL_NAME_ALIASES.keys

        assertEquals(
            emptySet<String>(), shared - runner,
            "Aliases missing from AgenticCliRunner"
        )
        assertEquals(
            emptySet<String>(), runner - shared,
            "Aliases defined only in AgenticCliRunner"
        )
        assertEquals(shared.size, runner.size)
    }
}
