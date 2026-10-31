package ai.platon.pulsar.agentic.mcp

import ai.platon.pulsar.agentic.mcp.server.FakeToolExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The catalog is the single enumeration both MCP channels build their tool
 * surface from, so its naming, alias and conflict rules decide what the
 * standard server and the private dispatcher advertise.
 */
@DisplayName("ToolCatalog")
class ToolCatalogTest {

    private fun build(
        builtIn: List<FakeToolExecutor> = emptyList(),
        custom: List<FakeToolExecutor> = emptyList(),
        aliases: List<McpToolAlias> = emptyList(),
        renames: Map<String, String> = emptyMap(),
        includeUnresolvedAliases: Boolean = false,
    ): ToolCatalog = ToolCatalog.build(
        groups = listOf(
            ExecutorGroup(builtIn, CatalogSource.BUILT_IN),
            ExecutorGroup(custom, CatalogSource.CUSTOM),
        ),
        aliases = aliases,
        renames = renames,
        includeUnresolvedAliases = includeUnresolvedAliases,
    )

    @Test
    @DisplayName("an advertised name is derived from the domain and method")
    fun derivesNamesFromDomainAndMethod() {
        val catalog = build(
            builtIn = listOf(FakeToolExecutor("tab", listOf("navigate", "goBack"))),
            custom = listOf(FakeToolExecutor("command", listOf("run"))),
        )

        assertEquals(listOf("navigate", "go_back", "command_run"), catalog.names)
    }

    @Test
    @DisplayName("a renamed tool is advertised under its public spelling only")
    fun renamesToolsToTheirPublicSpelling() {
        val catalog = build(
            custom = listOf(FakeToolExecutor("session", listOf("open"))),
            renames = mapOf("session.open" to "open_session"),
        )

        assertTrue(catalog.contains("open_session"), "Got: ${catalog.names}")
        assertFalse(catalog.contains("session_open"), "the derived spelling must not survive the rename")
    }

    @Test
    @DisplayName("an alias keeps the canonical tool's spec under its own name")
    fun aliasCarriesTheCanonicalSpec() {
        val catalog = build(
            builtIn = listOf(FakeToolExecutor("tab", listOf("navigate"))),
            aliases = listOf(McpToolAlias("browser_navigate", "tab", "navigate")),
        )

        val alias = catalog.get("browser_navigate")
        assertNotNull(alias)
        assertTrue(alias!!.isAlias)
        assertEquals("navigate", alias.canonicalName)
        assertEquals("tab", alias.domain)
        assertEquals("navigate", alias.method)
        assertEquals(catalog.get("navigate")!!.spec, alias.spec)
        assertEquals(CatalogSource.BUILT_IN, alias.source)
    }

    @Test
    @DisplayName("an alias whose canonical tool is missing is skipped")
    fun skipsAliasesWithoutACanonicalTool() {
        val catalog = build(
            builtIn = listOf(FakeToolExecutor("tab", listOf("navigate"))),
            aliases = listOf(McpToolAlias("browser_click", "tab", "click")),
        )

        assertFalse(catalog.contains("browser_click"), "Got: ${catalog.names}")
        assertEquals(1, catalog.size)
    }

    @Test
    @DisplayName("an unresolved alias can be advertised by name for a readiness probe")
    fun advertisesUnresolvedAliasesAsNameOnlyEntries() {
        val catalog = build(
            aliases = listOf(McpToolAlias("browser_navigate", "tab", "navigate")),
            includeUnresolvedAliases = true,
        )

        val alias = catalog.get("browser_navigate")
        assertNotNull(alias)
        assertNull(alias!!.spec, "a name-only entry must not pretend to be executable")
        assertEquals("navigate", alias.canonicalName)
        assertTrue(catalog.executable().isEmpty())
    }

    @Test
    @DisplayName("an alias resolves against a renamed canonical tool")
    fun resolvesAliasesAgainstRenamedTools() {
        val catalog = build(
            custom = listOf(FakeToolExecutor("session", listOf("windowState"))),
            renames = mapOf("session.windowState" to "window_state"),
            aliases = listOf(McpToolAlias("browser_window_state", "session", "windowState")),
        )

        val alias = catalog.get("browser_window_state")
        assertNotNull(alias)
        assertEquals("window_state", alias!!.canonicalName)
        assertNotNull(alias.spec)
    }

    @Test
    @DisplayName("an alias never overrides an advertised tool of the same name")
    fun aliasNeverShadowsAnExistingTool() {
        val catalog = build(
            builtIn = listOf(FakeToolExecutor("tab", listOf("click"))),
            aliases = listOf(McpToolAlias("click", "tab", "navigate")),
        )

        val entry = catalog.get("click")
        assertNotNull(entry)
        assertFalse(entry!!.isAlias, "the real tool keeps its name")
        assertEquals("click", entry.method)
    }

    @Test
    @DisplayName("rescanning the same tool is not reported as a conflict")
    fun rescannedToolsAreNotConflicts() {
        val executor = FakeToolExecutor("tab", listOf("navigate"))
        val catalog = build(builtIn = listOf(executor, executor))

        assertEquals(1, catalog.size)
        assertTrue(catalog.conflicts.isEmpty(), "Got: ${catalog.conflicts}")
    }

    @Test
    @DisplayName("a genuine name clash keeps the first tool and reports the shadowed one")
    fun reportsGenuineNameClashes() {
        val first = FakeToolExecutor("a", listOf("bC"))
        val second = FakeToolExecutor("a_b", listOf("c"))
        val catalog = build(builtIn = listOf(first), custom = listOf(second))

        assertEquals(1, catalog.size)
        assertEquals("a.bC", catalog.get("a_b_c")!!.expression)
        assertEquals(1, catalog.conflicts.size)
        assertEquals("a_b_c", catalog.conflicts[0].name)
        assertEquals("a.bC", catalog.conflicts[0].kept)
        assertEquals("a_b.c", catalog.conflicts[0].shadowed)
    }

    @Test
    @DisplayName("executable lists exactly the entries a channel can run")
    fun executableListsOnlyEntriesWithASpec() {
        val catalog = build(
            builtIn = listOf(FakeToolExecutor("tab", listOf("navigate"))),
            aliases = listOf(McpToolAlias("browser_navigate", "tab", "navigate")),
            includeUnresolvedAliases = true,
        )

        assertEquals(2, catalog.size)
        assertEquals(setOf("navigate", "browser_navigate"), catalog.executable().map { it.name }.toSet())
    }
}
