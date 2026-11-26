package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEventSanitizer
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus

/**
 * 从 `AgentTaskStatus` + 事件流聚合 [TaskSummaryContext]。
 *
 * 纯函数：无副作用、不抛异常（任何解析失败的字段降级为空/0），保证总结链路
 * 不会因为某条事件格式异常而中断（NFR-1）。
 */
object TaskContextCollector {

    fun collect(status: AgentTaskStatus, events: List<AgentTaskEvent>): TaskSummaryContext {
        val callsById = LinkedHashMap<String, ToolCallRecord>()
        val toolCounts = LinkedHashMap<String, Long>()
        var rounds = 0L
        var mutations = 0L
        var inputTokens = 0L
        var outputTokens = 0L
        var verifyVerdict: String? = null
        var filesChanged: List<String> = emptyList()
        var firstEpoch: Long? = null
        var lastEpoch: Long? = null

        for (e in events) {
            if (firstEpoch == null) firstEpoch = e.epochMillis
            lastEpoch = e.epochMillis
            when (e.type) {
                AgentTaskEvent.TYPE_ROUND -> {
                    rounds++
                    e.mutations?.let { mutations = it }
                }
                AgentTaskEvent.TYPE_TOOL_CALL -> {
                    val tool = e.tool ?: continue
                    toolCounts.merge(tool, 1L, Long::plus)
                    callsById[e.callId ?: "call-${e.seq}"] = ToolCallRecord(
                        tool = tool,
                        argumentsPreview = preview(e.arguments),
                        ok = null,
                        durationMs = null,
                        resultPreview = null,
                    )
                }
                AgentTaskEvent.TYPE_TOOL_RESULT -> {
                    val id = e.callId ?: continue
                    callsById[id]?.let { existing ->
                        callsById[id] = existing.copy(
                            ok = e.ok,
                            durationMs = e.durationMs,
                            resultPreview = preview(e.text),
                        )
                    }
                }
                AgentTaskEvent.TYPE_USAGE -> {
                    inputTokens += (e.inputTokens ?: 0).toLong()
                    outputTokens += (e.outputTokens ?: 0).toLong()
                }
                AgentTaskEvent.TYPE_VERIFY_VERDICT -> {
                    verifyVerdict = e.text ?: e.detail?.get("summary")?.toString()
                }
                AgentTaskEvent.TYPE_GATE_EVALUATED, AgentTaskEvent.TYPE_FINISH_AMEND -> {
                    extractFilesChanged(e.detail)?.let { filesChanged = it }
                }
            }
        }

        val sortedToolCounts = toolCounts.entries
            .sortedByDescending { it.value }
            .associate { it.key to it.value }

        val sequence = callsById.values.toList()
        var strategySwitches = 0
        var verbatimRetries = 0
        for (i in 1 until sequence.size) {
            val prev = sequence[i - 1]
            val cur = sequence[i]
            when {
                // 原样重试：同工具同参数连续调用——卡住了，不是学到了
                prev.tool == cur.tool && prev.argumentsPreview == cur.argumentsPreview -> verbatimRetries++
                // 策略切换：上一次失败、这次成功，且做法变了
                prev.ok == false && cur.ok == true -> strategySwitches++
            }
        }

        return TaskSummaryContext(
            taskId = status.id,
            prompt = status.submittedTask,
            result = status.message,
            workspace = status.workspace,
            rounds = rounds,
            toolCalls = callsById.size,
            mutations = mutations,
            strategySwitches = strategySwitches,
            verbatimRetries = verbatimRetries,
            toolCounts = sortedToolCounts,
            toolSequence = sequence,
            verifyVerdict = verifyVerdict,
            filesChanged = filesChanged,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            durationMs = if (firstEpoch != null && lastEpoch != null) lastEpoch - firstEpoch else null,
        )
    }

    private fun preview(value: String?): String? = AgentTaskEventSanitizer.preview(value, 500)

    @Suppress("UNCHECKED_CAST")
    private fun extractFilesChanged(detail: Map<String, Any?>?): List<String>? {
        val raw = detail?.get("filesChanged") as? List<*> ?: return null
        return raw.mapNotNull { entry ->
            when (entry) {
                is String -> entry
                is Map<*, *> -> entry["path"]?.toString() ?: entry["file"]?.toString()
                else -> entry?.toString()
            }
        }.ifEmpty { null }
    }
}
