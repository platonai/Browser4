# 修复方案：模型看不到工具输出（code 工具致命缺陷）

> 状态：待实施 · 日期：2026-08-20 · 来源：v1.4 监督轮（browser4-wordcount 任务）实测结论
> 优先级：P0。这是 agentic 编码回路的命门——默认配置下多步任务不可行（空转/盲猜/假完成），详见 `browser4-code-tool-supervision-v1.4.md` §0.1/§0.2。

---

## 1. 问题与证据（实测）

| 现象 | 实测数据 |
|---|---|
| 空转循环 | 旧后端：21 步 / 10 分钟 / 仅 6 次工具调用，模型反复 listDir 并声明 "I don't have its output" |
| 盲猜写出不可编译代码 | HEAD+TEXT 轮：24 条编译诊断（幻觉 `ai.platon.pulsar.pdk.*` API），模型毫不知情，继续宣称完成 |
| 假完成 | 修正轮：35 秒 / 1 步 / 0 工具调用，编造"全部完成"叙述 |
| toolCalling 模式可回灌但失控 | 每轮 90k 输入 token、单轮 5 次工具上限、history 被预算压成空 `{"step":N}`、重复编译 4 次 |

## 2. 根因（三个锚点）

1. **`browser4-agentic/.../inference/ToolExposeMode.kt:38-43`** — `from(conf)` 默认返回 `TEXT`（注释自认 "This is today's behaviour and the default"）。TEXT 路径（`ContextToAction.generateResponseRawLegacy`）没有工具结果回灌通道；只有 `TOOL_CALLING` 的 `AgentToolCallLoop` 会 append `ToolExecutionResultMessage`。
2. **`browser4-agentic/.../model/AgentState.kt:62`** — `@JsonIgnore var toolCallResult`：序列化进提示词的 history 时结果被丢弃；`DefaultHistoryRenderStrategy.renderDetailedState`（`inference/history/DefaultHistoryRenderStrategy.kt:124-136`）渲染的每步 JSON 只有 `toolCall`（伪表达式），没有 `result`。
3. **`browser4-agentic/.../agents/RobustBrowserAgent.kt:433-439`** — noop 只对 `ToolSpecification.isBrowserInteraction(lastToolCall.domain)` 计数，text-only 响应（无工具调用）永不计数 → 空转永不中止（`d284c60353` 刻意为之防误杀，留下漏洞）。

## 3. 修复方案（三层，全部可独立落地）

### A. 默认启用原生工具调用（治本）

**文件**：`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/ToolExposeMode.kt`

```kotlin
// before
fun from(conf: ImmutableConfig): ToolExposeMode {
    return when (conf.get("agent.tool.expose.mode")?.lowercase()) {
        "chat" -> CHAT
        "toolcalling", "langchain4j" -> TOOL_CALLING
        else -> TEXT
    }
}

// after：默认 TOOL_CALLING；显式 "text" 仍可回退
fun from(conf: ImmutableConfig): ToolExposeMode {
    return when (conf.get("agent.tool.expose.mode")?.lowercase()) {
        "text" -> TEXT
        "chat" -> CHAT
        else -> TOOL_CALLING
    }
}
```

配套（推荐一并做）：
- 更新枚举 KDoc（"default" 描述改为 TOOL_CALLING）；
- **降级兜底**：`ContextToAction.generateResponseRawWithLangChain4j` 捕获“模型/供应商不支持工具规格”类错误（如 400 "tools not supported"）时，本次任务自动回落 `TEXT` 并告警一次（记日志 + state 标记），避免本地小模型环境直接不可用；
- `application.properties` 增加注释示例 `# agent.tool.expose.mode=toolCalling`（默认值说明）。

### B. TEXT 模式兜底：history 渲染带结果预览（防回归）

**文件 1**：`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/model/AgentState.kt`

在 `AgentState` 中新增派生属性（`toolCallResult` 保持 `@JsonIgnore` 不变）：

```kotlin
/**
 * Bounded, single-line preview of the last tool result, rendered into the
 * agent's Execution History so TEXT-mode agents can see what their tools
 * returned (the full result stays in persisted logs).
 */
val resultPreview: String?
    get() = toolCallResult?.evaluate?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(600)
```

**文件 2**：`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/history/DefaultHistoryRenderStrategy.kt:124-136`

```kotlin
// before
return Pson.toJson(
    mapOf(
        "step" to state.step,
        "toolCall" to state.actionDescription?.pseudoExpression,
        "exception" to state.exception?.brief(),
        "summary" to state.summary,
        "nextGoal" to state.nextGoal.takeIf { !compress },
        "thinking" to state.thinking.takeIf { !compress },
        "keyFindings" to state.keyFindings.takeIf { !compress }
    )
)

// after：新增 result（预算压缩时也保留 200 字——结果是最重要的反馈信号）
"result" to state.resultPreview?.let { if (compress) it.take(200) else it },
```

注意：TEXT 模式每轮只回放“上一步”结果，多步任务的中间结果仍可能被预算压缩——压缩分支保留 200 字即为此设计。全量结果仍在 `stateHistoryPath` 的持久化日志里（渲染器已提示路径）。

### C. 空转保险丝：text-only 连续计数中止（防挂死）

**文件**：`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/RobustBrowserAgent.kt`（run 循环，约 356-396 行）

在 `consecutiveNoOps` 旁新增计数器，规则：
- 本步**未执行任何工具**且未声明完成 → `consecutiveTextOnly++`；
- 执行了任何工具 或 声明完成 → 清零；
- `consecutiveTextOnly >= textOnlyStallLimit`（新配置 `browser4.agent.textOnlyStallLimit`，默认 5）→ 以 `StopReason.NOOP_LIMIT`（或新增 `StopReason.TEXT_ONLY_LIMIT`）中止，任务标 failed。

```kotlin
// 伪代码（锚点：RobustBrowserAgent.run 循环内）
var consecutiveTextOnly = 0
...
val toolCall = stepResult.context.agentState.actionDescription?.toolCall
if (toolCall == null && !isComplete) {
    consecutiveTextOnly++
    if (consecutiveTextOnly >= textOnlyStallLimit) {
        lastStopReason = StopReason.NOOP_LIMIT
        break
    }
} else {
    consecutiveTextOnly = 0
}
```

配套：
- `AgentConfig`/conf 读取 `browser4.agent.textOnlyStallLimit`（默认 5，允许 0=禁用）；
- `agent run --noop-limit` 保持现状（浏览器 noop），本计数器独立，CLI 帮助补一句。

## 4. 相关加固（同病根，建议同批修）

| # | 问题 | 改动点 | 建议 |
|---|---|---|---|
| R1 | toolCalling 单轮 5 次工具上限导致链条中断重来 | `AgentToolCallLoop` 构造处（`ContextToAction.kt:78-85`） | maxIterations 提升为可配置（默认 12）；超限时把“已执行了哪些工具”写进 modelError，便于下一轮续跑 |
| R2 | Maven 输出回灌撑爆上下文（90k token/请求） | `CodingToolExecutor.mvnBuild` / `CodingAgentShell` 结果格式化 | 结果截断到 ~3000 字（exit code + 诊断摘要优先保留）；`devTask` verify 分支已有 takeLast(3000) 先例 |
| R3 | 跨任务历史污染 → 假完成（P0-2，见 v1.4 报告 §0.2） | `StatefulAgentRunner` / `UserCommandExecutor` 任务创建处 | 每任务新 sessionId 并用 `AgentHistory.snapshotFor(sessionId)` 渲染；完成声明校验：0 工具调用即拒绝 completed |
| R4 | history 预算把整步压成空 `{"step":N}` | `DefaultHistoryRenderStrategy.renderHistoryWithBudget` | 压缩分支至少保留 `step/toolCall/result` 三个字段（见 B 的压缩保留） |

## 5. 测试计划

| 层 | 用例 |
|---|---|
| 单元 | `ToolExposeModeTest`：默认 TOOL_CALLING；显式 `text`/`chat`/`toolcalling` 各自生效；null/未知值 → TOOL_CALLING |
| 单元 | `AgentStateTest`：resultPreview 正常截断 600 字、空白折叠、无结果时 null、不进入 toJson 的 toolCallResult 主体（仍 @JsonIgnore） |
| 单元 | `DefaultHistoryRenderStrategyTest`：渲染 JSON 含 `result` 字段；compress=true 时保留 200 字；预算超限行为不回退 |
| 单元 | `RobustBrowserAgentTest`：连续 5 次 text-only → NOOP_LIMIT/失败；中间插一次工具调用 → 计数清零；配置 0 禁用 |
| 集成 | 重建 bundle 后跑 v1.4 回归：`mvn test -pl browser4-agentic -am`；wordcount 插件任务重跑（TEXT 与 toolCalling 各一轮），断言：无 listDir 空转、完成声明含真实门禁结果、假完成被拒 |

## 6. 验收标准（Definition of Done）

- [ ] 默认 `agent.tool.expose.mode` 缺省时走 TOOL_CALLING，且 wordcount 类多步任务能自主看到 mvnBuild/test/validate 输出并据其行动
- [ ] TEXT 显式模式下，模型可见每步工具结果预览（≤600 字）
- [ ] 连续 text-only 空转 ≥5 次自动中止并标 failed（不依赖人工 cancel）
- [ ] 供应商不支持原生工具时自动降级 TEXT 且日志告警一次
- [ ] 新增单测全绿；`browser4-agentic` 既有测试无回归
- [ ] 无新增高噪声日志；改动经 b4w `-Rebuild` 重建 bundle 后实测

## 7. 风险与回退

- **默认切换风险**：本地/私有模型若不支持 function-calling，靠 A 的降级兜底 + `agent.tool.expose.mode=text` 显式回退即可，行为等价于今天。
- **resultPreview 上下文膨胀**：600 字/步 × 6 步 ≈ 3.6k 字符，远小于 Maven 原始输出的膨胀量，且有渲染预算双保险。
- **text-only 保险丝误伤**：阈值为连续 5 次且任何工具调用即清零；思考型模型单次 text-only 不受影响；配置可关（0）。
