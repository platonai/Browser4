package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.model.*
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.api.model.BrowserUseState
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobustBrowserAgentTest {
    private val session = mockk<AgenticSession>(relaxed = true)

    @Test
    @DisplayName("test last executed tool call comes from previous agent state")
    fun testLastExecutedToolCallComesFromPreviousAgentState() = runBlocking {
        val previousToolCall = ToolCall("tab", "click", mutableMapOf("selector" to "#submit"))
        val previousActionDescription = ActionDescription(
            instruction = "click submit",
            observeElements = listOf(ObserveElement(toolCall = previousToolCall))
        )
        val previousAgentState = AgentState(
            step = 1,
            instruction = "click submit",
            browserUseState = BrowserUseState.DUMMY,
        ).apply {
            toolCallResult = ToolCallResult(
                evaluate = TcEvaluate(expression = previousToolCall.pseudoExpression),
                actionDescription = previousActionDescription
            )
        }
        val currentAgentState = AgentState(
            step = 2,
            instruction = "click submit",
            browserUseState = BrowserUseState.DUMMY,
            prevState = previousAgentState
        )
        val context = ExecutionContext(
            step = 2,
            instruction = "click submit",
            event = "step",
            agentState = currentAgentState,
            stateHistory = AgentHistory(),
            config = AgentConfig(),
            sessionId = "session-id"
        )

        val actual = TestRobustBrowserAgent(session).resolveLastExecutedToolCall(context)

        assertEquals(previousToolCall.pseudoExpression, actual?.pseudoExpression)
    }

    private class TestRobustBrowserAgent(session: AgenticSession) : RobustBrowserAgent(session) {
        fun resolveLastExecutedToolCall(context: ExecutionContext): ToolCall? = lastExecutedToolCall(context)
    }

    // ─────────────────────────────────────────────────────────────────────
    // P0.2-2: text-only stall counter semantics
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("text-only stall increments for pure text responses")
    fun stallCounterIncrementsForPureText() {
        assertEquals(3, nextTextOnlyStallCount(2, null, false))
    }

    @Test
    @DisplayName("text-only stall resets when the response carries a parsed ToolCall")
    fun stallCounterResetsForParsedToolCall() {
        val toolCall = ToolCall("tab", "click", mutableMapOf("selector" to "#x"))
        assertEquals(0, nextTextOnlyStallCount(4, toolCall, false))
    }

    @Test
    @DisplayName("text-only stall resets when internal tools executed (overflow steps)")
    fun stallCounterResetsForInternalToolExecution() {
        // The step executed loop tools but the final response carries no
        // ToolCall (overflow) — never count real work as text idling.
        assertEquals(0, nextTextOnlyStallCount(4, null, true))
    }

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
        assertFalse(initial.contains("coding_shell"), "browsing profile hides shell (not a file tool)")
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
    @DisplayName("CLI engine without disclosure exposes the full CLI-domain set")
    fun cliEngineDisclosureDisabledExposesFullSet() {
        val toolSet = buildCliEngineToolSet(cliRegistry, systemSpecs, "core", disclosureEnabled = false)
        assertTrue(toolSet.disclosureSpecs.isEmpty(), "no meta-tool disclosure without the disclosure registry")
        assertEquals(names(toolSet.specs), names(toolSet.initialSpecs), "initial set equals the full CLI-domain set")
        assertEquals(toolSet.specs.size, toolSet.initialSpecs.size)
    }
}
