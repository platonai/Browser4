package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.model.ToolSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The initial tool set is what the model sees before its first call. A coding run
 * must be able to do the coding loop without a discovery round: the contract names
 * `coding.verify`, the refactor of a 4,000-line file needs bounded `coding.readLines`
 * reads and `coding.mvnBuild` drives the compile loop.
 *
 * A live run showed the cost of getting this wrong: with the browsing profile the
 * model spent its opening rounds on `system.listTools` / `system.exposeTools` to
 * discover exactly those tools, and `coding.verify` was not exposed at all.
 */
@DisplayName("CLI engine initial tool set")
class CliEngineToolSetTest {

    private fun spec(domain: String, method: String) = ToolSpec(domain = domain, method = method)

    private val codingMethods = listOf(
        "shell", "read", "readLines", "write", "replace", "editLines", "grep", "glob",
        "verify", "mvnBuild", "diagnostics", "diff", "changeSummary", "workspaceRoot",
        "scaffoldToDir", "symbols", "impact",
    )

    private fun registry(): Map<String, ToolSpec> =
        (codingMethods.map { spec("coding", it) } +
            listOf(spec("b4", "run"), spec("memory", "read"), spec("memory", "note")))
            .associateBy { "${it.domain}_${it.method}" }

    private val systemSpecs = listOf(
        ToolSpec(domain = "system", method = "help"),
        ToolSpec(domain = "system", method = "skillDoc"),
        ToolSpec(domain = "system", method = "taskComplete"),
    )

    private fun names(codingMode: Boolean): Set<String> =
        buildCliEngineToolSet(
            allRegistry = registry(),
            systemSpecs = systemSpecs,
            initialToolSet = "core",
            disclosureEnabled = true,
            codingMode = codingMode,
        ).initialSpecs
            .map { it.name() }
            .toSet()

    @Test
    @DisplayName("a coding run gets the tools its own contract names")
    fun codingProfileCarriesTheCodingContract() {
        val names = names(codingMode = true)

        // The prompt says "run coding.verify before finishing" — it must be callable.
        assertTrue("coding_verify" in names, "coding.verify must be exposed initially: $names")
        assertTrue("coding_mvnBuild" in names, "the compile loop needs coding.mvnBuild: $names")
        assertTrue("coding_readLines" in names, "bounded reads of a large file need coding.readLines: $names")
        assertTrue("coding_diagnostics" in names, "initial set: $names")
        // The completion contract survives every mode.
        assertTrue("system_taskComplete" in names, "initial set: $names")
        assertTrue("system_skillDoc" in names, "initial set: $names")
    }

    @Test
    @DisplayName("a browsing run does not pay for the coding long tail")
    fun browsingProfileStaysBrowsing() {
        val names = names(codingMode = false)

        assertTrue("b4_run" in names, "initial set: $names")
        assertTrue("coding_read" in names, "small helper-file tooling stays available: $names")
        // The heavy coding-only tools stay behind system.exposeTools.
        assertFalse("coding_verify" in names, "initial set: $names")
        assertFalse("coding_mvnBuild" in names, "initial set: $names")
        assertFalse("coding_readLines" in names, "initial set: $names")
    }

    @Test
    @DisplayName("a coding round gets a higher tool-loop floor")
    fun codingRoundsGetAHigherFloor() {
        // Browsing rounds keep the historical 40-call floor; coding chains
        // (read → grep → readLines → replace → build → test) get 60, because every
        // overflow restarts the round from a lossy digest — a live refactor run hit
        // the 40-call cap three times in a row without landing an edit.
        assertEquals(40, cliToolLoopMaxIterations(configured = 12, codingMode = false))
        assertEquals(60, cliToolLoopMaxIterations(configured = 12, codingMode = true))
        // An explicitly larger configuration is never lowered.
        assertEquals(80, cliToolLoopMaxIterations(configured = 80, codingMode = true))
        assertEquals(80, cliToolLoopMaxIterations(configured = 80, codingMode = false))
    }

    @Test
    @DisplayName("the coding long tail stays disclosed, not resident")
    fun codingLongTailIsDisclosable() {
        val toolSet = buildCliEngineToolSet(
            allRegistry = registry(),
            systemSpecs = systemSpecs,
            initialToolSet = "core",
            disclosureEnabled = true,
            codingMode = true,
        )

        assertTrue(toolSet.disclosureSpecs.isNotEmpty(), "listTools/exposeTools must stay available")
        val disclosed = toolSet.disclosureSpecs.map { it.name() }
        assertTrue(disclosed.any { it.endsWith("_scaffoldToDir") }, "$disclosed")
        assertTrue(disclosed.any { it.endsWith("_symbols") }, "$disclosed")
    }
}
