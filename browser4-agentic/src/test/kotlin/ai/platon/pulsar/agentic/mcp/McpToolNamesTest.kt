package ai.platon.pulsar.agentic.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("McpToolNames")
class McpToolNamesTest {

    @Test
    @DisplayName("tab and system methods carry no domain prefix")
    fun tabAndSystemMethodsCarryNoPrefix() {
        assertEquals("navigate", McpToolNames.toMcpToolName("tab", "navigate"))
        assertEquals("go_back", McpToolNames.toMcpToolName("tab", "goBack"))
        assertEquals("help", McpToolNames.toMcpToolName("system", "help"))
    }

    @Test
    @DisplayName("other domains are prefixed, including compound ones")
    fun otherDomainsArePrefixed() {
        assertEquals("fs_write_string", McpToolNames.toMcpToolName("fs", "writeString"))
        assertEquals("browser_switch_tab", McpToolNames.toMcpToolName("browser", "switchTab"))
        assertEquals("html_snapshot_capture", McpToolNames.toMcpToolName("html_snapshot", "capture"))
    }

    @Test
    @DisplayName("frontend alias names are unique")
    fun frontendAliasNamesAreUnique() {
        val names = McpToolNames.frontendAliases.map { it.frontendName }
        assertEquals(names.size, names.toSet().size, "Duplicate frontend alias: $names")
        assertEquals(names.size, McpToolNames.frontendAliasNames.size)
    }

    @Test
    @DisplayName("every frontend alias is a browser_* name pointing at a real tab tool")
    fun frontendAliasesAreBrowserNamesForTabTools() {
        for (alias in McpToolNames.frontendAliases) {
            assertTrue(
                alias.frontendName.startsWith("browser_"),
                "Alias '${alias.frontendName}' must use the browser_ prefix",
            )
            assertEquals("tab", alias.domain, "Alias '${alias.frontendName}' must target the tab domain")
            assertTrue(alias.method.isNotBlank())
            assertTrue(alias.canonicalName.isNotBlank())
        }
    }

    @Test
    @DisplayName("dispatcher-only aliases are browser_* names the shared table does not claim")
    fun dispatcherOnlyAliasesAreDisjointFromSharedOnes() {
        // The dispatcher resolves a few session-scoped names that have no
        // (domain, method) tool behind them (`browser_window_state`). They are
        // declared once, here, so both parity guards can compare key sets exactly
        // instead of tolerating an unexplained difference.
        for (name in McpToolNames.dispatcherOnlyAliasNames) {
            assertTrue(name.startsWith("browser_"), "Dispatcher-only alias '$name' must use the browser_ prefix")
            assertTrue(
                name !in McpToolNames.frontendAliasNames,
                "Alias '$name' is both shared and dispatcher-only — pick one"
            )
        }
        assertEquals(
            McpToolNames.frontendAliasNames + McpToolNames.dispatcherOnlyAliasNames,
            McpToolNames.dispatcherAliasNames,
            "dispatcherAliasNames must be exactly the union of both sets"
        )
    }

    @Test
    @DisplayName("aliases resolve to the canonical spelling an agent expects")
    fun aliasesResolveToCanonicalSpellings() {
        fun canonical(frontend: String): String? =
            McpToolNames.frontendAliases.firstOrNull { it.frontendName == frontend }?.canonicalName

        assertEquals("navigate", canonical("browser_navigate"))
        assertEquals("fill", canonical("browser_type"))
        assertEquals("aria_snapshot", canonical("browser_snapshot"))
        assertEquals("evaluate_value", canonical("browser_evaluate"))
        assertEquals("network_request_detail", canonical("browser_network_request"))
        assertEquals("network_routes", canonical("browser_network_routes"))
    }
}
