package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("Unit")
@Tag("Fast")
class TaskContextCollectorTest {

    private fun event(
        type: String,
        seq: Long,
        tool: String? = null,
        arguments: String? = null,
        callId: String? = null,
        ok: Boolean? = null,
        durationMs: Long? = null,
        text: String? = null,
        detail: Map<String, Any?>? = null,
        mutations: Long? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
    ) = AgentTaskEvent(
        seq = seq, type = type, tool = tool, arguments = arguments, callId = callId,
        ok = ok, durationMs = durationMs, text = text, detail = detail,
        mutations = mutations, inputTokens = inputTokens, outputTokens = outputTokens,
    )

    @Test
    @DisplayName("TR-2.3 collects prompt, tool sequence, counts, verdict and filesChanged from event stream")
    fun collectsFullContext() {
        val status = AgentTaskStatus().apply {
            submittedTask = "refactor the auth module"
            message = "done: 3 files changed"
            workspace = "/tmp/ws"
        }
        val events = listOf(
            event(AgentTaskEvent.TYPE_STARTED, 1),
            event(AgentTaskEvent.TYPE_ROUND, 2, mutations = 3),
            event(AgentTaskEvent.TYPE_TOOL_CALL, 3, tool = "coding.read", arguments = "{\"path\":\"Auth.kt\"}", callId = "c1"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 4, callId = "c1", ok = true, durationMs = 11, text = "file content"),
            event(AgentTaskEvent.TYPE_TOOL_CALL, 5, tool = "coding.edit", arguments = "{}", callId = "c2"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 6, callId = "c2", ok = true, durationMs = 5),
            event(AgentTaskEvent.TYPE_TOOL_CALL, 7, tool = "coding.read", arguments = "{}", callId = "c3"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 8, callId = "c3", ok = false, text = "not found"),
            event(AgentTaskEvent.TYPE_USAGE, 9, inputTokens = 120, outputTokens = 60),
            event(AgentTaskEvent.TYPE_VERIFY_VERDICT, 10, text = "verify: 2 checks passed"),
            event(AgentTaskEvent.TYPE_GATE_EVALUATED, 11, detail = mapOf("filesChanged" to listOf("Auth.kt", "Login.kt"))),
            event(AgentTaskEvent.TYPE_DONE, 12),
        )

        val ctx = TaskContextCollector.collect(status, events)

        assertEquals("refactor the auth module", ctx.prompt)
        assertEquals("done: 3 files changed", ctx.result)
        assertEquals("/tmp/ws", ctx.workspace)
        assertEquals(1, ctx.rounds)
        assertEquals(3, ctx.toolCalls)
        assertEquals(3, ctx.mutations)
        // toolCounts sorted desc: coding.read(2) before coding.edit(1)
        assertEquals(listOf("coding.read", "coding.edit"), ctx.toolCounts.keys.toList())
        assertEquals(2L, ctx.toolCounts["coding.read"])
        assertEquals(1L, ctx.toolCounts["coding.edit"])
        // sequence paired by callId
        assertEquals("coding.read", ctx.toolSequence[0].tool)
        assertTrue(ctx.toolSequence[0].ok == true)
        assertEquals(11L, ctx.toolSequence[0].durationMs)
        assertEquals("coding.edit", ctx.toolSequence[1].tool)
        assertEquals("verify: 2 checks passed", ctx.verifyVerdict)
        assertEquals(listOf("Auth.kt", "Login.kt"), ctx.filesChanged)
        assertEquals(120L, ctx.inputTokens)
        assertEquals(60L, ctx.outputTokens)
    }

    @Test
    @DisplayName("TR-2.4 a failed call followed by a different approach counts as one strategy switch")
    fun countsStrategySwitch() {
        val events = listOf(
            event(AgentTaskEvent.TYPE_TOOL_CALL, 1, tool = "click", arguments = "{\"ref\":\"#buy\"}", callId = "c1"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 2, callId = "c1", ok = false),
            event(AgentTaskEvent.TYPE_TOOL_CALL, 3, tool = "click", arguments = "{\"text\":\"Buy now\"}", callId = "c2"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 4, callId = "c2", ok = true),
        )

        val ctx = TaskContextCollector.collect(AgentTaskStatus(), events)

        assertEquals(1, ctx.strategySwitches)
        assertEquals(0, ctx.verbatimRetries)
    }

    @Test
    @DisplayName("TR-2.4 repeating an identical call counts as a verbatim retry, not a switch")
    fun countsVerbatimRetry() {
        val events = listOf(
            event(AgentTaskEvent.TYPE_TOOL_CALL, 1, tool = "click", arguments = "{\"ref\":\"#buy\"}", callId = "c1"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 2, callId = "c1", ok = false),
            event(AgentTaskEvent.TYPE_TOOL_CALL, 3, tool = "click", arguments = "{\"ref\":\"#buy\"}", callId = "c2"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 4, callId = "c2", ok = true),
        )

        val ctx = TaskContextCollector.collect(AgentTaskStatus(), events)

        assertEquals(1, ctx.verbatimRetries)
        assertEquals(0, ctx.strategySwitches)
    }

    @Test
    @DisplayName("TR-2.4 a strategy switch carries both argument previews, for the LLM to learn from")
    fun correctionCarriesBothPreviews() {
        val events = listOf(
            event(AgentTaskEvent.TYPE_TOOL_CALL, 1, tool = "click", arguments = "{\"ref\":\"#buy\"}", callId = "c1"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 2, callId = "c1", ok = false),
            event(AgentTaskEvent.TYPE_TOOL_CALL, 3, tool = "click", arguments = "{\"text\":\"Buy now\"}", callId = "c2"),
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 4, callId = "c2", ok = true),
        )

        val ctx = TaskContextCollector.collect(AgentTaskStatus(), events)

        assertEquals(
            listOf(Correction("click", "{\"ref\":\"#buy\"}", "{\"text\":\"Buy now\"}")),
            ctx.corrections,
        )
    }

    @Test
    @DisplayName("filesChanged extracted from finish.amend detail with map-shaped entries")
    fun collectsFilesChangedFromAmend() {
        val status = AgentTaskStatus()
        val events = listOf(
            event(AgentTaskEvent.TYPE_FINISH_AMEND, 1, detail = mapOf(
                "filesChanged" to listOf(mapOf("path" to "a.kt"), mapOf("path" to "b.kt")),
            )),
        )
        val ctx = TaskContextCollector.collect(status, events)
        assertEquals(listOf("a.kt", "b.kt"), ctx.filesChanged)
    }

    @Test
    @DisplayName("empty event stream yields zeroed context without throwing")
    fun emptyStreamIsSafe() {
        val ctx = TaskContextCollector.collect(AgentTaskStatus(), emptyList())
        assertEquals(0, ctx.rounds)
        assertEquals(0, ctx.toolCalls)
        assertEquals(0, ctx.mutations)
        assertTrue(ctx.toolCounts.isEmpty())
        assertTrue(ctx.toolSequence.isEmpty())
        assertNull(ctx.verifyVerdict)
        assertTrue(ctx.filesChanged.isEmpty())
        assertNull(ctx.durationMs)
    }

    @Test
    @DisplayName("orphan tool.result without matching call does not crash or create a record")
    fun orphanResultIsIgnored() {
        val status = AgentTaskStatus()
        val events = listOf(
            event(AgentTaskEvent.TYPE_TOOL_RESULT, 1, callId = "missing", ok = true, text = "x"),
        )
        val ctx = TaskContextCollector.collect(status, events)
        assertEquals(0, ctx.toolCalls)
        assertTrue(ctx.toolSequence.isEmpty())
    }
}
