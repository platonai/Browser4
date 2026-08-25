package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("ToolDisclosureTools progressive disclosure")
class ToolDisclosureTest {

    private fun spec(domain: String, method: String) =
        ToolSpec(domain, method, description = "$domain.$method does something")

    private fun lc4j(specs: List<ToolSpec>): List<LangChain4jToolSpec> =
        ToolSpecificationConverter.toToolSpecifications(specs)

    private val pageSpecs = listOf(
        spec("tab", "navigate"), spec("tab", "ariaSnapshot"), spec("browser", "listTabs"),
        spec("agent", "extract"), spec("system", "help"), spec("system", "taskComplete"),
    )
    private val devSpecs = listOf(
        spec("coding", "read"), spec("coding", "write"), spec("coding", "ktSymbols"),
        spec("b4", "run"), spec("shell", "execute"),
    )
    private val allToolSpecs = pageSpecs + devSpecs
    private val allByName = lc4j(allToolSpecs).associateBy { it.name() }

    private fun names(specs: List<ToolSpec>): Set<String> = specs.map { "${it.domain}.${it.method}" }.toSet()

    @Test
    @DisplayName("meta specs expose listTools and exposeTools with the right schemas")
    fun metaSpecsHaveExpectedNamesAndSchemas() {
        val meta = ToolDisclosureTools.metaSpecs()
        assertEquals(setOf(ToolDisclosureTools.LIST_TOOLS_NAME, ToolDisclosureTools.EXPOSE_TOOLS_NAME),
            meta.map { it.name() }.toSet())
        assertTrue(ToolDisclosureTools.LIST_TOOLS_NAME.endsWith("listTools"),
            "sanitized names use underscores: ${ToolDisclosureTools.LIST_TOOLS_NAME}")

        val expose = meta.first { it.name() == ToolDisclosureTools.EXPOSE_TOOLS_NAME }
        assertTrue(expose.parameters()?.properties()?.containsKey("toolNames") == true,
            "exposeTools must declare a toolNames parameter")
        assertTrue(expose.parameters()?.properties()?.get("toolNames") is JsonArraySchema,
            "toolNames must be declared as a JSON array schema")
        assertTrue(expose.description().contains("Expose additional tools"),
            "description must teach the discovery protocol")
    }

    @Test
    @DisplayName("initialToolSet=all keeps the legacy full exposure")
    fun selectInitialSpecsAllModeReturnsEverything() {
        val selected = ToolDisclosureTools.selectInitialSpecs(allToolSpecs, "all")
        assertEquals(allToolSpecs.size, selected.size)
    }

    @Test
    @DisplayName("initialToolSet=core keeps only the core domains")
    fun selectInitialSpecsCoreKeepsOnlyCoreDomains() {
        val selected = ToolDisclosureTools.selectInitialSpecs(allToolSpecs, "core")
        val selectedNames = names(selected)
        assertEquals(setOf("tab", "browser", "agent", "system"), selected.map { it.domain }.toSet(),
            "coding/b4/shell must be hidden initially")
        assertTrue(selectedNames.contains("tab.navigate"))
        assertFalse(selectedNames.contains("coding.read"))
        assertFalse(selectedNames.contains("b4.run"))
    }

    @Test
    @DisplayName("initialToolSet=core honors a per-domain method allowlist")
    fun selectInitialSpecsCoreHonorsMethodAllowlist() {
        val selected = ToolDisclosureTools.selectInitialSpecs(
            allToolSpecs, "core",
            coreDomains = setOf("tab"),
            coreMethodAllowlist = mapOf("coding" to setOf("read", "write")),
        )
        val selectedNames = names(selected)
        assertTrue(selectedNames.contains("coding.read"))
        assertTrue(selectedNames.contains("coding.write"))
        assertFalse(selectedNames.contains("coding.ktSymbols"), "non-allowlisted coding methods stay hidden")
        assertFalse(selectedNames.contains("b4.run"), "domains outside core stay hidden")
    }

    @Test
    @DisplayName("explicit pattern list supports wildcards, exact names and bare methods")
    fun selectInitialSpecsExplicitPatterns() {
        val selected = ToolDisclosureTools.selectInitialSpecs(
            allToolSpecs, "coding.*, tab.navigate, run",
        )
        val selectedNames = names(selected)
        assertTrue(selectedNames.contains("coding.read"))
        assertTrue(selectedNames.contains("coding.ktSymbols"))
        assertTrue(selectedNames.contains("tab.navigate"))
        assertTrue(selectedNames.contains("b4.run"), "bare method names match any domain")
        assertFalse(selectedNames.contains("tab.ariaSnapshot"))
        assertFalse(selectedNames.contains("shell.execute"))
    }

    @Test
    @DisplayName("core token composes with explicit patterns")
    fun selectInitialSpecsCoreTokenComposesWithPatterns() {
        val selected = ToolDisclosureTools.selectInitialSpecs(
            allToolSpecs, "core,coding.ktSymbols",
            coreDomains = setOf("tab"),
            coreMethodAllowlist = mapOf("coding" to setOf("read")),
        )
        val selectedNames = names(selected)
        assertTrue(selectedNames.contains("tab.navigate"), "core token keeps whole core domains")
        assertTrue(selectedNames.contains("coding.read"), "core token keeps allowlisted methods")
        assertTrue(selectedNames.contains("coding.ktSymbols"), "explicit patterns still apply")
        assertFalse(selectedNames.contains("b4.run"), "domains outside core stay hidden")
    }

    @Test
    @DisplayName("all token composes with patterns and returns everything")
    fun selectInitialSpecsAllTokenComposesWithPatterns() {
        val selected = ToolDisclosureTools.selectInitialSpecs(allToolSpecs, "all, coding.read")
        assertEquals(allToolSpecs.size, selected.size)
    }

    @Test
    @DisplayName("pattern list matching nothing returns an empty set")
    fun selectInitialSpecsEmptyForUnmatchedPatterns() {
        val selected = ToolDisclosureTools.selectInitialSpecs(allToolSpecs, "no.such.tool")
        assertTrue(selected.isEmpty(), "unmatched patterns must not silently pick tools")
    }

    @Test
    @DisplayName("parseToolNames accepts arrays, single strings, blanks and garbage")
    fun parseToolNamesIsRobust() {
        assertEquals(listOf("coding_read", "coding_write"),
            ToolDisclosureTools.parseToolNames("""{"toolNames":["coding_read","coding_write"]}"""))
        assertEquals(listOf("coding_read"),
            ToolDisclosureTools.parseToolNames("""{"toolNames":"coding_read"}"""))
        assertEquals(emptyList(), ToolDisclosureTools.parseToolNames(null))
        assertEquals(emptyList(), ToolDisclosureTools.parseToolNames("not json"))
        assertEquals(emptyList(), ToolDisclosureTools.parseToolNames("""{"toolNames":42}"""))
    }

    @Test
    @DisplayName("listToolsResult lists only hidden tools, honors domain filter and limit")
    fun listToolsResultListsHiddenTools() {
        val exposed = allByName[ToolSpecificationConverter.toolName("tab", "navigate")]?.let { mutableListOf(it) }
            ?: mutableListOf()

        val all = ToolDisclosureTools.listToolsResult("id1", allByName, exposed.map { it.name() }.toSet())
        assertFalse(all.text().contains("tab_navigate"), "exposed tools must not be listed")
        assertTrue(all.text().contains("coding_read"), "hidden tools must be listed")
        assertTrue(all.text().contains("shell_execute"))
        assertFalse(all.text().contains("system_listTools"), "meta tools must never be listed")

        val codingOnly = ToolDisclosureTools.listToolsResult("id1", allByName, emptySet(), domain = "coding")
        assertTrue(codingOnly.text().contains("coding_read"))
        assertFalse(codingOnly.text().contains("tab_navigate"))

        val limited = ToolDisclosureTools.listToolsResult("id1", allByName, emptySet(), limit = 2)
        assertTrue(limited.text().contains("以及另外"), "over-limit listings must report the remainder")
    }

    @Test
    @DisplayName("exposeToolsResult enables new tools, skips unknown and already-exposed ones")
    fun exposeToolsResultExpandsExposedSet() {
        val exposed = mutableListOf<LangChain4jToolSpec>()
        val msg = ToolDisclosureTools.exposeToolsResult(
            "id1",
            """{"toolNames":["coding_read","coding_unknown","coding_read"]}""",
            allByName, exposed,
        )

        assertEquals(listOf("coding_read"), exposed.map { it.name() })
        assertTrue(msg.text().contains("已启用: coding_read"))
        assertTrue(msg.text().contains("coding_unknown(未知或歧义)"))
        assertTrue(msg.text().contains("coding_read(已暴露)"), "re-requesting an exposed tool must be reported, not duplicated")
        assertEquals(1, exposed.size, "no duplicates in the exposed set")
    }

    @Test
    @DisplayName("bare method names resolve only when unambiguous")
    fun exposeToolsResultSkipsAmbiguousBareNames() {
        val withFs = lc4j(listOf(spec("coding", "read"), spec("fs", "read"), spec("coding", "write")))
        val byName = withFs.associateBy { it.name() }
        val exposed = mutableListOf<LangChain4jToolSpec>()

        val msg = ToolDisclosureTools.exposeToolsResult("id1", """{"toolNames":["read"]}""", byName, exposed)

        assertEquals(emptyList(), exposed, "ambiguous bare names must not silently pick a tool")
        assertTrue(msg.text().contains("read(未知或歧义)"))
    }
}
