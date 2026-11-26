package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

/**
 * 一次工具调用的摘要（用于生成 SKILL，不含完整参数/结果，仅预览）。
 */
data class ToolCallRecord(
    val tool: String,
    val argumentsPreview: String?,
    val ok: Boolean?,
    val durationMs: Long?,
    val resultPreview: String?,
)

/**
 * 从一次成功的 agent 运行聚合出的、可用于生成 SKILL 的上下文。
 *
 * 由 [TaskContextCollector] 从 `AgentTaskStatus` + 事件流构建。所有字符串字段
 * 都经过预览截断；完整敏感值不会出现在这里（事件写入时已脱敏，生成层会再兜底）。
 */
data class TaskSummaryContext(
    val taskId: String,
    val prompt: String?,
    val result: String?,
    val workspace: String?,
    val rounds: Long,
    val toolCalls: Int,
    val mutations: Long,
    /**
     * "策略切换"次数：前一次调用失败、随后成功，且做法变了（同工具换参数，或换工具）。
     *
     * 无文件改动的任务靠它识别真实的知识增量——运行中值得沉淀的往往就是这些岔路。
     */
    val strategySwitches: Int = 0,
    /**
     * 原样重试次数：同工具、同参数的连续调用。
     *
     * 说明运行"卡住了"而不是"学到了"，在浏览类评分中扣分。
     */
    val verbatimRetries: Int = 0,
    /** 真实工具名 → 调用次数，按次数降序。用于推导 SKILL 的 allowed-tools。 */
    val toolCounts: Map<String, Long>,
    /** 按执行顺序的工具调用记录。用于推导 SKILL 的步骤。 */
    val toolSequence: List<ToolCallRecord>,
    val verifyVerdict: String?,
    /** finish gate 记录的改动文件路径列表（可能为空）。 */
    val filesChanged: List<String>,
    val inputTokens: Long,
    val outputTokens: Long,
    val durationMs: Long?,
)
