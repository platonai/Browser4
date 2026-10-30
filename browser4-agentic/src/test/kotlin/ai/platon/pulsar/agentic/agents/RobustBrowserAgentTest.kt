package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AgentAccessPolicy
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobustBrowserAgentTest {
    // ─────────────────────────────────────────────────────────────────────
    // CLI engine initial tool set (buildCliEngineToolSet)
    // ─────────────────────────────────────────────────────────────────────

    private fun spec(domain: String, method: String, argCount: Int = 0) = ToolSpec(
        domain = domain,
        method = method,
        arguments = (1..argCount).map { ToolSpec.Arg("arg$it", "String") },
        description = "$domain.$method does something",
    )

    /** Mirrors the runtime registry: coding + b4 (registered executors) + hardcoded system.help only. */
    private val cliRegistry = ToolSpecificationConverter.toRegistry(
        listOf(
            spec("coding", "read"), spec("coding", "write"), spec("coding", "shell"),
            spec("coding", "ktSymbols"),
            spec("b4", "run"), spec("b4", "status"),
            spec("system", "help", 2),
        )
    )

    /** Mirrors SystemToolExecutor.toolSpec: the authoritative system-domain specs. */
    private val systemSpecs = listOf(
        spec("system", "help", 2),
        spec("system", "skillDoc"),
        spec("system", "taskComplete", 4),
    )

    private fun names(specs: List<LangChain4jToolSpec>): Set<String> = specs.map { it.name() }.toSet()

    @Test
    @DisplayName("CLI engine core initial set carries the completion + skillDoc contract")
    fun cliEngineCoreInitialSetCarriesSystemContract() {
        val toolSet = buildCliEngineToolSet(cliRegistry, systemSpecs, "core", disclosureEnabled = true)
        val initial = names(toolSet.initialSpecs)
        assertTrue(initial.contains("system_taskComplete"), "completion tool must be exposed from the start")
        assertTrue(initial.contains("system_skillDoc"), "skillDoc must be exposed from the start")
        assertTrue(initial.contains("b4_run"), "b4 is a whole core domain")
        assertTrue(initial.contains("coding_read"), "allowlisted coding methods are in the core set")
        assertFalse(initial.contains("coding_ktSymbols"), "coding long tail stays hidden")
        assertTrue(toolSet.disclosureSpecs.isNotEmpty(), "disclosure must be enabled")
        assertEquals("core", toolSet.usedMode)
        assertEquals(toolSet.initialSpecs.size, toolSet.initialSpecs.distinctBy { it.name() }.size)
        assertTrue(toolSet.registry.containsKey("system_taskComplete"), "coordinator registry resolves the completion tool")
    }

    @Test
    @DisplayName("CLI engine pattern mode keeps the system contract tools")
    fun cliEnginePatternModeKeepsSystemContract() {
        val toolSet = buildCliEngineToolSet(cliRegistry, systemSpecs, "coding.read", disclosureEnabled = true)
        val initial = names(toolSet.initialSpecs)
        assertTrue(initial.contains("coding_read"))
        assertTrue(initial.contains("system_taskComplete"), "contract tools survive pattern modes")
        assertTrue(initial.contains("system_skillDoc"))
        assertFalse(initial.contains("b4_run"))
    }

    @Test
    @DisplayName("CLI engine browsing profile exposes file tools but hides the coding long tail")
    fun cliEngineBrowsingProfileHidesCodingLongTail() {
        val toolSet = buildCliEngineToolSet(cliRegistry, systemSpecs, "core", disclosureEnabled = true, codingMode = false)
        val initial = names(toolSet.initialSpecs)
        assertTrue(initial.contains("b4_run"), "web access tools are always exposed")
        assertTrue(initial.contains("coding_read"), "browsing profile keeps the most common file tools")
        // `shell` IS exposed now: the browsing prompt tells the model to extract an
        // `.xlsx` input with a script, and a strict function-calling provider cannot
        // call an undeclared tool (Amazon top-20, 2026-10-28). The long tail stays
        // hidden — that is what this test pins.
        assertTrue(initial.contains("coding_shell"), "browsing profile keeps coding.shell (named by the prompt)")
        assertFalse(initial.contains("coding_shellOutput"), "the shell long tail stays hidden")
        assertFalse(initial.contains("coding_mvnBuild"), "build tools stay hidden in a browsing task")
        assertFalse(initial.contains("coding_ktSymbols"), "coding long tail stays hidden")
        assertTrue(initial.contains("system_taskComplete"), "system contract survives the browsing profile")
        assertTrue(toolSet.disclosureSpecs.isNotEmpty(), "the hidden coding tools stay disclosable")
    }

    @Test
    @DisplayName("CLI engine coding profile exposes the full curated coding core")
    fun cliEngineCodingProfileExposesCodingCore() {
        val toolSet = buildCliEngineToolSet(cliRegistry, systemSpecs, "core", disclosureEnabled = true, codingMode = true)
        val initial = names(toolSet.initialSpecs)
        assertTrue(initial.contains("coding_shell"), "coding profile exposes shell")
        assertTrue(initial.contains("coding_read"))
        assertFalse(initial.contains("coding_ktSymbols"), "coding long tail still hidden (progressive disclosure)")
        assertTrue(initial.contains("b4_run"))
    }

    @Test
    @DisplayName("CLI engine falls back to core when the pattern list matches nothing")
    fun cliEngineFallsBackToCoreForEmptySelection() {
        val toolSet = buildCliEngineToolSet(cliRegistry, systemSpecs, "tab.snapshot,no.such.tool", disclosureEnabled = true)
        assertEquals("core", toolSet.usedMode, "misconfigured patterns must not leave an empty initial set")
        val initial = names(toolSet.initialSpecs)
        assertTrue(initial.contains("b4_run"), "fallback restores the default core set")
        assertTrue(initial.contains("system_taskComplete"))
        assertTrue(toolSet.disclosureSpecs.isNotEmpty())
    }

    @Test
    @DisplayName("a denied coding method never reaches any view, so exposeTools cannot revive it")
    fun deniedCodingMethodStaysOutOfEveryView() {
        val policy = AgentAccessPolicy(preset = AccessPreset.READ_BUILD)
        val toolSet = buildCliEngineToolSet(
            cliRegistry, systemSpecs, "core",
            disclosureEnabled = true, codingMode = true, policy = policy,
        )
        // E4: the filter runs before the initial selection and the disclosure pool.
        assertFalse(toolSet.registry.containsKey("coding_shell"), "denied method must not be resolvable")
        assertFalse(names(toolSet.initialSpecs).contains("coding_shell"))
        assertFalse(names(toolSet.disclosureSpecs).contains("coding_shell"))
        assertFalse(names(toolSet.specs).contains("coding_shell"))
        // Mutation is denied too, reads survive.
        assertFalse(toolSet.registry.containsKey("coding_write"))
        assertTrue(toolSet.registry.containsKey("coding_read"))
    }

    /** Mirrors the skill-domain registrations: reads, execution and deployment. */
    private val skillRegistry = ToolSpecificationConverter.toRegistry(
        listOf(
            spec("skill", "list"), spec("skill", "activate"),
            spec("skill", "readDocumentation"), spec("skill", "readReference"),
            spec("skill", "run"), spec("skill", "install"),
            spec("skill", "uninstall"), spec("skill", "reload"),
        )
    )

    private fun skillToolSet(preset: AccessPreset) = buildCliEngineToolSet(
        skillRegistry, systemSpecs, "core",
        disclosureEnabled = true, codingMode = false, policy = AgentAccessPolicy(preset = preset),
    )

    @Test
    @DisplayName("the skill domain reaches the CLI engine, so installed skills are discoverable")
    fun skillDomainIsExposedToTheCliEngine() {
        val toolSet = skillToolSet(AccessPreset.FULL)
        val registry = toolSet.registry.keys
        assertTrue(registry.contains("skill_list"), "skill.list must resolve")
        assertTrue(registry.contains("skill_activate"))
        assertTrue(registry.contains("skill_readDocumentation"))
        assertTrue(registry.contains("skill_run"), "full access can execute a skill")
        // Progressive disclosure: not in the initial set, but reachable through the pool.
        assertFalse(names(toolSet.initialSpecs).contains("skill_list"), "skill tools stay out of the core set")
        assertTrue(names(toolSet.disclosureSpecs).contains("skill_list"))
    }

    @Test
    @DisplayName("a read-only task can discover skills but cannot execute them")
    fun readPresetKeepsSkillReadsOnly() {
        val toolSet = skillToolSet(AccessPreset.READ)
        assertTrue(toolSet.registry.containsKey("skill_list"))
        assertTrue(toolSet.registry.containsKey("skill_readReference"))
        assertFalse(toolSet.registry.containsKey("skill_run"), "skill.run executes code, so it needs access=full")
        assertFalse(names(toolSet.disclosureSpecs).contains("skill_run"), "exposeTools must not revive it")
        assertFalse(names(toolSet.specs).contains("skill_run"))
    }

    @Test
    @DisplayName("skill deployment methods never reach the CLI engine, whatever the preset")
    fun skillDeploymentStaysOutOfEveryPreset() {
        AccessPreset.entries.forEach { preset ->
            val exposed = skillToolSet(preset).registry.keys
            listOf("skill_install", "skill_uninstall", "skill_reload").forEach { blocked ->
                assertFalse(exposed.contains(blocked), "$blocked must stay out under $preset")
            }
        }
    }

    @Test
    @DisplayName("CLI engine without disclosure exposes the full CLI-domain set")
    fun cliEngineDisclosureDisabledExposesFullSet() {
        val toolSet = buildCliEngineToolSet(cliRegistry, systemSpecs, "core", disclosureEnabled = false)
        assertTrue(toolSet.disclosureSpecs.isEmpty(), "no meta-tool disclosure without the disclosure registry")
        assertEquals(names(toolSet.specs), names(toolSet.initialSpecs), "initial set equals the full CLI-domain set")
        assertEquals(toolSet.specs.size, toolSet.initialSpecs.size)
    }

    @Test
    @DisplayName("overflow continuation points the model at the round log and at persistence")
    fun overflowContinuationCarriesRoundLogAndPersistence() {
        val message = overflowContinuationMessage(
            modelError = "max iterations reached",
            progress = "row.csv (20 rows)",
            roundLogPath = ".b4scratch/round-3.log",
        )

        assertTrue(message.contains("max iterations reached"), message)
        assertTrue(message.contains("row.csv (20 rows)"), message)
        // The complete round log must be reachable: the entry digest is lossy, and the
        // 2026-10-29 run re-derived a 30-item list over two more rounds.
        assertTrue(message.contains(".b4scratch/round-3.log"), message)
        assertTrue(message.contains("coding.readLines"), message)
        // Page-JavaScript state is gone across the cut — say it, and name the fix.
        assertTrue(message.contains("page JavaScript"), message)
        assertTrue(message.contains(".b4scratch/progress.txt"), message)
        // Without a dumped log the message stays valid (and promises no file).
        val withoutLog = overflowContinuationMessage("boom", "nothing yet")
        assertFalse(withoutLog.contains("coding.readLines"), withoutLog)
        assertTrue(withoutLog.contains("nothing yet"), withoutLog)
    }
}
