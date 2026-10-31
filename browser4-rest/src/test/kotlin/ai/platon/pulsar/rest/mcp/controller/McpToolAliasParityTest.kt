package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.mcp.CatalogSource
import ai.platon.pulsar.agentic.mcp.ExecutorGroup
import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.mcp.ToolCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The private MCP dispatcher and the standard MCP server must advertise the same
 * `browser_*` frontend names: a model that learned a name on one channel must not
 * get "unknown tool" on the other.
 *
 * The dispatcher additionally owns the session-scoped names the standard server
 * cannot express — no `(domain, method)` tool stands behind them — and those are
 * declared in [McpToolNames.dispatcherOnlyAliasNames] rather than excused here, so
 * the comparison stays exact: a name missing from the shared table and from that
 * set is still a failure.
 */
@DisplayName("MCP frontend alias parity (rest)")
class McpToolAliasParityTest {

    @Test
    @DisplayName("the dispatcher aliases exactly the frontend names the shared table defines")
    fun dispatcherAliasesMatchSharedTable() {
        assertEquals(
            McpToolNames.dispatcherAliasNames,
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

    @Test
    @DisplayName("a name the standard server advertises is never unknown to the dispatcher")
    fun dispatcherResolvesEveryStandardServerAlias() {
        assertEquals(
            emptySet<String>(),
            McpToolNames.frontendAliasNames - McpToolNames.dispatcherAliasNames,
            "The dispatcher must resolve every alias the standard MCP server advertises"
        )
    }

    @Test
    @DisplayName("the dispatcher's alias tables declare each spelling exactly once")
    fun dispatcherAliasTablesAreUnique() {
        val names = McpToolNames.dispatcherAliases.map { it.frontendName }
        val duplicates = names.groupBy { it }.filterValues { it.size > 1 }.keys

        assertEquals(emptySet<String>(), duplicates, "Duplicate alias spelling: $duplicates")
    }

    @Test
    @DisplayName("the dispatcher's static catalog advertises the lifecycle tools under their public names")
    fun dispatcherCatalogAdvertisesSessionLifecycleNames() {
        val catalog = ToolCatalog.build(
            groups = listOf(ExecutorGroup(listOf(SessionToolExecutor()), CatalogSource.CUSTOM)),
            aliases = McpToolNames.dispatcherAliases,
            renames = McpToolNames.sessionRenames,
            includeUnresolvedAliases = true,
        )

        // The session executor's methods and the rename table must describe the
        // same tools: a method without a public name would show up as `session_*`,
        // a rename without a method would be advertised but not executable.
        assertEquals(
            McpToolNames.sessionRenames.values.toSet() + McpToolNames.dispatcherAliases.map { it.frontendName },
            catalog.names.toSet(),
        )
        assertTrue(catalog.contains("open_session"), "the CLI's readiness probe expects it")
        assertTrue(catalog.contains("browser_navigate"), "aliases are advertised before a session exists")
    }
}
