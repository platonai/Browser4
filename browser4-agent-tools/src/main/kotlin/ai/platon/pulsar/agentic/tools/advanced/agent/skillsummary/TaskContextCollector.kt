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

        // 读回类工具（快照/摘要/读文件/校验/收尾）不参与"失败→换做法"配对：
        // 浏览任务是"动作后必读回"的模式，失败的 click 紧跟成功的 snapshot 会被
        // 误判成一次策略切换，真值应来自同一目标换了做法且最终成功。
        val actionSequence = sequence.filterNot { isReadBackTool(it.tool) }

        var verbatimRetries = 0
        for (i in 1 until actionSequence.size) {
            val prev = actionSequence[i - 1]
            val cur = actionSequence[i]
            // 原样重试：同工具同参数连续调用——卡住了，不是学到了
            if (prev.tool == cur.tool && prev.argumentsPreview == cur.argumentsPreview) verbatimRetries++
        }

        // 策略切换：同一工具第一次失败之后，用不同参数（或不同做法）成功。每个工具最多记一次。
        var strategySwitches = 0
        val corrections = mutableListOf<Correction>()
        actionSequence.groupBy { it.tool }.forEach { (tool, calls) ->
            val firstFail = calls.indexOfFirst { it.ok == false }
            if (firstFail >= 0) {
                val failedArgs = calls[firstFail].argumentsPreview
                val laterSuccess = calls.drop(firstFail + 1).firstOrNull { it.ok == true }
                if (laterSuccess != null && laterSuccess.argumentsPreview != failedArgs) {
                    strategySwitches++
                    corrections += Correction(tool, failedArgs, laterSuccess.argumentsPreview)
                }
            }
        }

        // 事件流可能被截断（store 只保留尾部），此时 round.start 会整段丢失。
        // 有工具调用就说明至少跑过一轮，用下限兜底，避免长任务被判成 0 轮而误跳过。
        val effectiveRounds = maxOf(rounds, if (callsById.isNotEmpty()) 1L else 0L)

        return TaskSummaryContext(
            taskId = status.id,
            prompt = status.submittedTask,
            result = status.message,
            workspace = status.workspace,
            rounds = effectiveRounds,
            toolCalls = callsById.size,
            mutations = mutations,
            strategySwitches = strategySwitches,
            verbatimRetries = verbatimRetries,
            corrections = corrections,
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

    /** 读回类工具：只观察状态，不含"换做法"的语义。 */
    private fun isReadBackTool(tool: String): Boolean =
        READ_BACK_KEYWORDS.any { tool.contains(it, ignoreCase = true) }

    private val READ_BACK_KEYWORDS = listOf(
        "snapshot", "summary", "scrape", "extract", "read", "verify", "taskcomplete", "diff",
    )

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
