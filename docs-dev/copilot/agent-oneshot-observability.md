# `agent run` oneshot 可观测性与外部监督接口（分析 + P0 实施计划）

> 日期：2026-11-22
> 对象：`agent run "<task>"` 全链路（CLI → MCP `command.run` → `UserCommandExecutor` →
> `StatefulAgentRunner` → `RobustBrowserAgent` CLI 工具循环 → `b4.run` 子进程）
> 读者：要在这条链路上做外部监督（人或智能体）的实现者
> 状态：**P0-A…P0-F 已实施**（issue #652；实施记录见 §9）

---

## 1. 结论

1. **"在做什么、花了多少"已经可观测**：步骤流（15 类事件）+ 精确计数（rounds / modelRequests /
   toolCalls / tokens）+ 终态信封 + 结果载荷；游标轮询的契约（`after` / `nextSeq` / 幂等空页）
   设计正确，断线可自愈。
2. **"有没有接近目标、会不会被判失败"基本不可观测**：引擎自己用来纠偏的信号——mutation 计数、
   只读轮次、nudge、`FinishReportGate` 裁决、`RepairLoop` 轮次、`coding.verify` verdict、
   `IsolationGate` 合并决定——**一个都没有进入事件流**，只落在后端日志和本地 trace 文件里。
3. **面向 agent 的接口（MCP）缺少监督三件套**：`command` 域只有 `run/status/result/cancel`，
   `events` / `stats` / `input` 只在 REST，且 tool spec 不告诉调用方这些端点存在。
4. **过程数据易失且不均衡**：事件不落盘（1000 条环形 / 128 任务 / 120 分钟空闲 TTL），重启后
   "有状态无过程"；审计全量只在后端磁盘，**未脱敏**且没有 API。
5. 一句话：现在是 **"执行可观看"**，还不是 **"执行可裁判"**。监督者能看着它跑，但很难独立判断
   它是否在达成目标——而这正是 oneshot "目标必须达到"最需要外部验证的部分。

---

## 2. 监督方的需求模型

外部监督者（无论人或 agent）只需回答四个问题：

| # | 问题 | 需要什么信号 | 现状 |
|---|---|---|---|
| S1 | 它还活着吗？在哪个阶段？ | 状态词、`task.started`、`round.start`、心跳 | ✅ 好 |
| S2 | 它现在在做什么？花了多少？ | `tool.call/result`、`model.text`、`run.usage` | ✅ 好（受截断限制） |
| S3 | **它在接近目标吗？** | 产出增量（mutation）、只读轮次、nudge | ❌ 缺失 |
| S4 | **它会被判成功吗？我该怎么干预？** | gate 裁决、repair 轮、verdict、merge 决定；steer/cancel | ⚠️ 干预有，裁决缺 |

S3/S4 是本文的重点。

---

## 3. 现状：外部可见的观测面

### 3.1 面清单

| 面 | 传输 | 粒度 | 持久化 | 机器可读 | 锚点 |
|---|---|---|---|---|---|
| 任务信封 | `GET /api/commands/{id}/status` | 状态词 / `statusCode` / `message` / `processed` / `elapsedMs` | JSONL 行集（10s 节流 + 终态） | ✓ | `CommandStatus.kt` |
| 步骤流 | `GET /api/commands/{id}/events?after=&limit=` | 单步：工具 / 参数 / ok / 耗时 / 模型文本 / token | ✗ 内存环形 1000 | ✓ | `CommandController.getEvents` |
| 运行统计 | `GET /api/commands/{id}/stats` | rounds / modelRequests / toolCalls / toolFailures / tokens / cache / droppedEvents / toolCounts | ✗ 同生命周期 | ✓ | `CommandController.getStats` |
| 状态 SSE | `GET /api/commands/{id}/stream` | 只有 `CommandStatus` | — | ✓ | `CommandController.streamEvents` |
| 干预 | `POST /{id}/input`（`steer` / `followUp`）、`POST /{id}/cancel` | 单条消息 / 取消 | ✗ | ✓ | `CommandController.postInput` |
| 结果载荷 | `GET /api/commands/{id}/result` | summary / keyFindings / filesChanged / problems / workspace / merge / timings / nextSuggestions / errorCode / resumable | JSONL | ✓ | `CommandStatus.toCommandStatus` |
| CLI 实时视图 | `agent run --follow [--ndjson]` | 人类行 / 每行一个 JSON | — | `--ndjson` ✓ | `agent_interactive.rs` |
| 追踪目录 | `<data>/logs/agent/<start>/<uuid>/` | **全量**：完整 prompt + tool specs、原始参数与完整结果、每次请求 usage、页面时间线 | ✓ 磁盘（无保留策略） | ✓ | `CliLoopTracer` |
| 会话转录 | `agent-session-context.jsonl` | 跨任务对话快照 | ✓ | ✓ | `SessionConversationStore` |
| 权限 | `GET /api/agent/{taskId}/access` | 待审批的越权请求 | ✗ | ✓ | `AgentAccessController` |
| 服务日志 | `GET /api/doctor/logs` | 文本（含 `⚠ finish.report`、`🔁 repair.round`） | ✓ | ✗ 需解析 | `DoctorController`（仅 `logs/*.log`） |

### 3.2 事件模型

`AgentTaskEvent`（`browser4-agent-tools/.../advanced/agent/AgentTaskEvents.kt`）字段：
`seq`（游标）、`type`、`epochMillis`、`tool`、`arguments`、`ok`、`durationMs`、`text`、`turn`、
`inputTokens`、`outputTokens`、`totalTokens`、`cachedInputTokens`、`cacheWriteInputTokens`、
`budgetTokens`、`cumulativeTokens`、`errorCode`、`resumable`。

15 类事件：

| 类别 | 类型 |
|---|---|
| 生命周期 | `task.created`、`task.started`、`task.done`、`task.failed`、`task.cancelled` |
| 轮次 | `round.start` |
| 工具 | `tool.call`、`tool.result` |
| 模型 | `model.text`、`run.usage` |
| 干预 | `steer`、`steering.undelivered`、`follow_up`、`follow_up.undelivered` |

### 3.3 关键容量与节流常量

| 常量 | 值 | 位置 |
|---|---|---|
| 每任务事件缓冲 | 1000 | `AgentTaskEventLog.DEFAULT_CAPACITY` |
| 事件页默认 / 上限 | 200 / 1000 | `DEFAULT_PAGE_LIMIT`、`CommandController.MAX_EVENTS_PAGE` |
| 保留事件日志的任务数 | 128 | `StatefulAgentRunner.EVENT_LOG_CACHE_SIZE` |
| 事件日志空闲 TTL | 120 分钟 | `EVENT_LOG_TTL_MINUTES` |
| 状态缓存 | 1000 条 / 2h `expireAfterWrite` | `STATUS_CACHE_SIZE` |
| 进度落盘间隔 | 10s | `PROGRESS_PERSIST_INTERVAL_MS` |
| 事件/状态预览长度 | 240 字符 | `AgentTaskEventSanitizer.MAX_PREVIEW_CHARS` |
| CLI 轮询间隔 | 事件 400ms / 状态 1s | `agent_interactive.rs` |

### 3.4 两个可见性断层

**断层 A：MCP ↔ REST。** 外部 agent 的天然接入面是 MCP（`command` 域只有 `run/status/result/cancel`），
监督所需的三个端点只在 REST，且 `command.status` 的信封里没有 `eventsUrl` / `statsUrl` 指针
（现有约定只有 `statusTool` / `resultTool` / `cancelTool`，见 `TaskEnvelopes`）。
结果是：通过 MCP 接入的监督者**不知道自己缺什么**。

**断层 B：事件 ↔ trace。** 事件流做了认真的脱敏（`AgentTaskEventSanitizer`：敏感键、password 型
selector、CLI `--token` 形态、代理对修复）并且截断到 240 字；`cli-tool-trace.jsonl` 写的是
**原始参数与完整结果**（`CliLoopTracer.logTool`）。于是：

- 要安全 → 用事件流 → 被截断、缺裁决；
- 要完整 → 用 trace 文件 → 必须登后端机器，且明文（表单里输入的密码、抓到的页面内容都在里面）。

远程监督者两头都够不着。

---

## 4. 问题清单

严重度：**P0** = 阻断"外部智能体监督"这一目标；**P1** = 显著降低监督质量；
**P2** = 体验/一致性缺陷。

### P0-1 决定成败的裁决，全部在事件流外

引擎收尾的三件事——`FinishReportGate.evaluate`（8 条规则）、`RepairLoop`（verify 失败回灌）、
`IsolationGate`（是否合并 worktree）——只写 `cliLoopTracer.logEvent(...)` 和服务日志：

```
🔁 cli-agent.repair.round sid=… round=2/3 reason=…
⚠ finish.report sid=… filesChanged lists 3 file(s) neither git nor this run's file trace shows
⚠ finish.report sid=… amended filesChanged: the run wrote 17 file(s) the report does not list
```

- **证据**：`RobustBrowserAgent.emitRunEvent` 的调用点只有 `ROUND_START` / `MODEL_TEXT` /
  `TOOL_CALL` / `TOOL_RESULT` / `USAGE` / `STEER` / `FOLLOW_UP`（`RobustBrowserAgent.kt` ≈1266/1311/1697/1767/1911/1950/1980）；
  `reconcileFinishReport`、`nextRepairMessage`、`recordVerificationVerdict` 均不发射运行事件。
- **影响**：监督者只能等 `task.failed`，而那行只有**第一条**违规
  （`finishRejectionMessage`：`"… (+N more violation(s) in the run log)"`），指向它读不到的日志。
  监督从"提前干预"退化为"事后验尸"。

### P0-2 引擎自己的"进展信号"没有导出

`doRunCliAgentLoop` 维护 `readOnlyCodingRounds` 与 `codingFs.mutationsSnapshot()`，并用它触发
`codingProgressNudge`（"别再测绘了，开始改"）。这两个数字：

- 不在 `AgentTaskEvent` 里；
- 不在 `AgentTaskRunStats` 里；
- 只在 `coding.nudge` 的 tracer 事件里。

**影响**：外部监督者无法计算"N 次工具调用 / 0 次文件改动"——而这正是"任务不会收敛"最早、最可靠
的信号。`docs-dev/copilot/agent-run-supervision-2026-10-22.md` 里 175 tools / 12.2min / 0 mutation
的判定只能靠人肉比对工作区 mtime。

### P0-3 MCP 面缺监督三件套

`CommandToolExecutor` 只注册 `run` / `status` / `result` / `cancel`；`docs/mcp-tools.md` 的
`command` 域同样只有这四个。`events` / `stats` / `input` 没有 MCP 封装，`command.status` 响应
也没有指向它们的字段。

**影响**：通过 MCP 接入的外部 agent，监督能力等于零，且无法发现能力存在。

### P0-4 过程不持久，且"丢了多少 / 丢在哪"不可区分

- `AgentTaskEventLog`：1000 条环形，超出即丢（`droppedEvents` 记账）；`eventLogs` Caffeine
  128 任务 / 120 分钟空闲 TTL；**明确不落盘**。
- 后端重启：JSONL 状态行恢复，事件全无——"有状态、无过程"。
- 游标语义：`after` 落在保留窗口之前时返回**空页 + `nextSeq` 原样回传**，与"暂无新事件"不可区分；
  `CommandEventsResponse` 没有 `dropped` / `firstRetainedSeq` / `resynced` 字段。

**影响**：监督者睡眠超过缓冲窗口（40 分钟编码任务轻松几千事件）就会**静默少看一段过程**，
并误以为 agent 停滞。

### P0-5 审计详情只在后端磁盘，且未脱敏

`CliLoopTracer.logTool` 写入 `"arguments" to request.arguments()` 与 `"resultText" to result.text()`
（全量、无脱敏）；trace 目录无保留策略、无读取 API（`DoctorController` 只暴露 `logs/*.log`）。

**影响**：远程监督者拿不到过程细节；有磁盘权限的人拿到的可能是明文凭据。

### P1-1 240 字预览切掉了监督最需要的两样东西

`VERDICT_JSON` 通常远超 240 字；编译/测试诊断**常出现在输出尾部**，而 `preview()` 保留头部。
工具层已修过"尾部截断"（plan P1.5），**事件层没有对应策略**。

### P1-2 没有 `callId`，call/result 靠启发式配对

`tool.call` / `tool.result` 只带工具名；CLI 渲染器用"同名匹配、否则取最老"的启发式
（`agent_interactive.rs` 的 `take_pending_call` 注明"需要后端 `callId`，plan P2-1"）。
`AgentToolCallLoop.onToolRequest` 的签名是 `(String, String) -> Unit`，拿不到 `ToolExecutionRequest.id()`。
事件也没有 `schemaVersion`。

### P1-3 只有轮询，没有事件级推送

`/stream` 是 `CommandStatus` 的 SSE，**不是事件流**。观察者必须 400ms 轮询 `/events`；
没有 `Last-Event-ID`、没有长轮询、没有背压。N 个监督者 = N 份轮询压力。

### P1-4 状态行粗且节流

进度持久化 `PROGRESS_PERSIST_INTERVAL_MS = 10s`；`processed` 是工具计数。只读 JSONL 的监督者
看到"每 10 秒一跳"的粗粒度。

### P1-5 干预是单向弱确认

`POST /input` 返回 `accepted=true` 只表示入队；真正投递体现为一条 `steer` / `follow_up` 事件。
两者之间**没有 correlationId**，监督者无法证明"被投递的那条是我的"。

### P1-6 权限审批不在流里

越权请求只能通过 `GET /api/agent/{taskId}/access` 轮询；事件流没有 `access.requested`。
无人值守场景下，外部监督者不会知道"agent 正卡在等人批准"。

### P2-1 browser 任务的主要工作量不可见

浏览任务的实质工作是 `b4.run` 起的 CLI 子进程；事件里只有一个 `tool.call`，长任务只返回
`[job: id]` 句柄。内部步骤既不在事件流里，也不在该任务的 trace 里（trace 只记外层工具结果）。

### P2-2 没有结构化的"目标 / 计划 / 子目标"

`nextGoal` / `evaluationPreviousGoal` 只存在于旧 observation 模型；CLI 循环不产出结构化 intent。
监督者只能从 `model.text` 猜目标是否漂移。

### P2-3 跨面关联靠人

`taskId` / `agentSession` / agent `uuid` / `runDir` / `requestSeq` 分散，没有统一关联字段
（trace 目录名只有 start-time + agent uuid，不含 taskId）。

---

## 5. P0 实施计划（第一档 1–5 项）

### 5.0 总览

| # | 项 | 解决的问题 | 主要落点 | 预估 | 回退 |
|---|---|---|---|---|---|
| P0-A | 事件模型扩展 + 新增裁决事件 | P0-1、P1-2 | `AgentTaskEvents.kt`、`AgentRunEvent.kt`、`RobustBrowserAgent.kt` | 1.5d | 新事件类型默认发射；未知类型消费方本就忽略 |
| P0-B | 进展指标导出（mutations / readOnlyRounds） | P0-2 | `RobustBrowserAgent`、`AgentTaskRunStats`、`AgentTaskStatus` | 0.5d | 字段可空，不填即旧行为 |
| P0-C | 终态结构化裁决 | P0-1、P1-1 | `AgentTaskStatus` / `CommandResult` / `StatefulAgentRunner.recordFailure` | 1d | 字段可空 |
| P0-D | 游标语义修正（`dropped` / `firstRetainedSeq` / `resynced`） | P0-4 | `CommandEventsResponse`、`CommandController.getEvents` | 0.5d | 纯新增字段 |
| P0-E | MCP 监督三件套 + 信封指针 | P0-3 | `CommandToolExecutor`、`TaskEnvelopes`、`docs/mcp-tools.md` | 1d | 新工具，可独立下线 |

依赖：A → B → C；D 与 E 独立，可并行。

### P0-A 事件模型扩展 + 新增裁决事件

#### A1. 模型字段（向后兼容）

`AgentTaskEvent`（以及上游 `AgentRunEvent`）新增三个可选字段：

```kotlin
/**
 * Stable id of one tool invocation, shared by its `tool.call` and `tool.result`.
 * Null on every non-tool event. Consumers must not infer pairing from tool name.
 */
val callId: String? = null,

/**
 * Bounded structured payload of a decision event (`gate.evaluated`, `verify.verdict`,
 * `repair.round`, …). Rendered text stays in [text]; this is what a machine reads.
 * Strings inside are previewed and redacted by [AgentTaskEventSanitizer.detail].
 */
val detail: Map<String, Any?>? = null,

/** Schema version of the event model; absent on rows written before 2026-11-22. */
val schemaVersion: Int? = null,
```

约束：
- `detail` 序列化后 ≤ 4 KB：字符串按 `preview(…, 240)`，数组 ≤ 20 项，嵌套 ≤ 2 层，字典 ≤ 12 键；
  超限时丢弃尾部并置 `detail["truncated"] = true`。
- `callId` 由 `AgentToolCallLoop` 在 `onToolRequest` 处透出 `ToolExecutionRequest.id()`；
  需要把回调签名从 `(String, String) -> Unit` 改为 `(String, String, String?) -> Unit`
  （`AgentToolCallLoop.kt:140`，调用点 `:431`），`RobustBrowserAgent` 的 `onToolRequest`
  同步接收；`tool.result` 侧已有 `request.id()`（`:451`）。

#### A2. 新增事件类型与埋点

所有埋点都在**已有** `cliLoopTracer.logEvent(...)` / `logger.info(...)` 的位置旁边，成本近乎为零。

| 事件 | 触发 | `detail` 关键字段 | 埋点 |
|---|---|---|---|
| `verify.verdict` | 每次 `coding.verify` 结果被解析后 | `passed`、`module`、`checks[{name,ran,ok,exitCode}]` | `recordVerificationVerdict`（≈2886） |
| `gate.evaluated` | 每次完成报告评估后（pass 也发） | `passed`、`strict`、`violations[]`、`unlistedFiles[]`、`requiredGates[]` | `evaluateFinishReport`（≈2622） |
| `repair.round` | 注入一轮 verify 修复 | `round`、`maxRounds`、`reason` | `nextRepairMessage`（≈2903） RETRY 分支 |
| `repair.exhausted` | 修复预算耗尽 | `roundsUsed`、`verdict` | 同上 EXHAUSTED 分支（抛错前） |
| `finish.repair` | 报告漏列文件后的修复轮 | `round`、`missing[]` | `reconcileFinishReport`（≈2684） RETRY 分支 |
| `finish.amend` | 用 mutation trace 补全报告 | `added[]`、`source="mutation-trace"` | 同上 AMEND 分支 |
| `nudge` | 引擎注入纠偏指令 | `kind="coding"` + `readOnlyRounds`；`kind="budget"` + `percent`/`consumed`/`limit` | `codingProgressNudge` 调用点（≈1284）、`onBeforeIteration` 预算分支（≈1827） |
| `isolation.merge` | worktree 合并决定 | `decision="applied"\|"refused"`、`changedFiles`、`reason` | `StatefulAgentRunner.finishIsolation`（≈942） |

发射辅助（runner 侧）：

```kotlin
internal fun recordStructuredEvent(
    taskId: String,
    type: String,
    detail: Map<String, Any?>? = null,
    text: String? = null,
) = eventLog(taskId).append(type, text = text, detail = detail)
```

> 设计取舍：用通用的 `detail` map 而不是每类事件加 4~6 个平铺字段——事件类型还会增长，
> 平铺字段会让 `AgentTaskEvent` 变成一个大杂烩；schema 由 KDoc + 本文附录约束，
> `schemaVersion` 提供演进锚点。

#### A3. 容量影响

新增事件量级：`progress` 类并入 `round.start`（见 P0-B），`gate.*` / `repair.*` / `finish.*`
每个任务个位数，`nudge` 个位数，`verify.verdict` = verify 调用次数。相对 1000 条缓冲可忽略。
终态相关的裁决事件天然位于流的尾部，不会被淘汰。

### P0-B 进展指标导出

1. `AgentRunEvent` / `AgentTaskEvent` 新增 `mutations: Long?`、`readOnlyRounds: Int?`（gauge，
   非累加）。
2. `round.start` 发射点带上**当前累计值**：

```kotlin
emitRunEvent(
    AgentRunEvent(
        AgentRunEvent.ROUND_START,
        turn = turn + 1,
        mutations = agentToolManager.codingFs.mutationsSnapshot().size.toLong(),
        readOnlyRounds = readOnlyCodingRounds,
    )
)
```

   监督者用相邻两条 `round.start` 的差值即可得到"这一轮改了几个文件"。

3. `RunStatsAccumulator.record(...)` 接收这两个值并**覆盖**保存（gauge 语义），
   `AgentTaskRunStats` 暴露 `mutations` / `readOnlyRounds`；`/stats` 即可回答
   "工具调用 175 次，文件改动 0 次"。
4. `AgentTaskStatus` 增加 `mutations: Long?`，由 `StatefulAgentRunner.recordRunEvent` 在
   `round.start` 时同步（`statusCache.getIfPresent(taskId)?.mutations = …`），
   让 2 秒一次的 `command.status` 不必再取 `/stats` 就能看到进展（也让 10 秒落盘的 JSONL 行带上它）。

### P0-C 终态结构化裁决

目标：让 `/result`（以及 MCP `command.result`）回答"它为什么失败、差在哪、还能不能续"。

1. `AgentTaskStatus` 新增：

```kotlin
/** Structured failure evidence: gate violations, verdict checks, repair rounds. */
var failureDetail: Map<String, Any?>? = null
```

2. `recordFailure(status)` 在追加 `task.failed` 时，从**同一个事件日志**取最近的裁决事件补全
   （`lastOfType` 是新增的小工具，从 `AgentTaskEventLog` 尾部向前找，O(n) 且 n ≤ 1000）：

```kotlin
val verdict = eventLog(status.id).lastOfType(AgentTaskEvent.TYPE_VERIFY_VERDICT)
val gate = eventLog(status.id).lastOfType(AgentTaskEvent.TYPE_GATE_EVALUATED)
status.failureDetail = buildMap {
    status.errorCode?.let { put("errorCode", it) }
    status.resumable?.let { put("resumable", it) }
    verdict?.detail?.let { put("verdict", it) }
    gate?.detail?.let { put("finishGate", it) }
}
```

   这样不依赖跨层传参：裁决事件就在抛错前一刻进入日志。
3. 持久化：`AgentTaskStatus.snapshot()` / JSONL 行自动带上（Jackson 序列化 `Map`）。
4. `CommandResult` 新增 `failureDetail: Map<String, Any?>?`，在 `toCommandStatus()` 里透传
   （与 `errorCode` / `resumable` 同一位置）。
5. `finishRejectionMessage` 的"only first violation + 去读日志"可以保留（日志仍是人读入口），
   但**机器入口不再依赖它**。

### P0-D 游标语义修正

`CommandEventsResponse` 新增：

```kotlin
/** Seq of the oldest retained event (0 when the log is empty/unknown). */
val firstRetainedSeq: Long,
/** Events evicted from the buffer so far; a follower can detect a silent gap. */
val dropped: Long,
/** True when `after` had fallen behind the retained window: the page is a tail, not a continuation. */
val resynced: Boolean = false,
```

计算规则（`CommandController.getEvents`）：

```
firstRetained = events.firstOrNull()?.seq ?: after
resynced = after > 0 && after < firstRetained - 1
```

空页语义不变（`nextSeq = after`，幂等），区别只在于 `resynced=true` 明示"你漏了一段"。
`AgentTaskEventLog` 增加 `firstSeq()`，与已有 `droppedCount()` 一起取。
`/stats` 的 `droppedEvents` 保留（跨全生命周期的总量）。

### P0-E MCP 监督三件套

1. `CommandToolExecutor` 新增三个 `ToolSpec`（薄封装 `UserCommandExecutor`，与 REST 同源）：

```
command.events(id, after: Long = 0, limit: Int = 200, sessionId: String? = null)
command.stats(id, sessionId: String? = null)
command.input(id, text: String, deliverAs: String = "steer")
```

   返回结构直接复用 `CommandEventsResponse` / `CommandStatsResponse` / `CommandInputResponse`，
   避免第二套 schema。

2. `TaskEnvelopes` 的共享任务信封增加可选 `eventsTool` / `statsTool`（与 `statusTool` 并列），
   `CommandToolExecutor` 填 `command_events` / `command_stats`：

```json
{"taskId":"…","status":"running","statusTool":"command_status",
 "resultTool":"command_result","cancelTool":"command_cancel",
 "eventsTool":"command_events","statsTool":"command_stats"}
```

3. 文档与门禁：`docs/mcp-tools.md` / `.json` 是 `ToolDocGenerator` 的**生成产物**，
   `ToolDocGeneratorTest` 是漂移门禁（重生成必须零 diff，用 `-DregenerateToolDocs=true` 重生成）。
   新增工具必须同时进 `ToolContractMatrixTest` 的夹具，覆盖三个新工具的契约
   （task envelope / 参数校验 / 404 语义）——漏了会让文档与契约检查一起静默失效。

### P0-F（可选）CLI 渲染新事件

`--ndjson` 无需改动（原样透传）。人类渲染器 `Renderer::format` 的兜底是 `_ => None`，
所以新事件默认静默；建议补 3 条线（约 30 行）：

```
⏱ gate rejected: 2 violation(s) — the run wrote 1 file the report does not list
🔁 repair round 1/3 — verification: compile browser4-browser failed (exit 1)
✔ finish report amended from the mutation trace: +3 file(s)
```

### P0 兼容性与回退

| 变更 | 兼容性 | 回退 |
|---|---|---|
| 新事件类型 | 旧消费方忽略未知类型（REST 原样返回、`--ndjson` 原样打印） | 不发射即可 |
| 新字段（`callId` / `detail` / `schemaVersion` / `mutations`） | 全部可空，旧行反序列化为 null | 保留字段不填 |
| `AgentToolCallLoop.onToolRequest` 签名 | 内部 API，仅 `RobustBrowserAgent` 一个调用方 | 传 `null` |
| MCP 新工具 | 纯新增 | 从 toolSpec 移除 |

---

## 6. 测试与验收

### 6.1 单测

| 用例 | 文件 |
|---|---|
| 事件 append 保留 `detail` / `callId` / `schemaVersion`，超限被裁剪 | `AgentTaskEventLogTest` |
| `detail` 内字符串被 preview + 脱敏（密码 / `--token`）、超限被裁剪 | `AgentTaskEventSanitizerTest`（扩充） |
| `round.start` 的 gauge 覆盖语义（不是累加） | `AgentTaskEventLogTest` |
| `stats.mutations` / `readOnlyRounds` 取最后一次 | `AgentTaskEventLogTest` |
| 新事件经 `recordRunEvent` / `recordStructuredEvent` 进入日志 | `StatefulAgentRunnerTest`（≈961 的现有用例旁） |
| `recordFailure` 从日志补全 `failureDetail` | `StatefulAgentRunnerTest` |
| 游标：`after` 在窗口前 → `resynced=true`；空页 `nextSeq` 不变 | `CommandControllerTest` |
| `toCommandStatus` 透传 `failureDetail` | `CommandStatusConversionTest` |

### 6.2 REST 契约

`CommandControllerTest` 增补：`/events` 返回 `firstRetainedSeq` / `dropped` / `resynced`；
`/stats` 返回 `mutations`（未知时为 null，不是 0）；`/result` 返回 `failureDetail`。

### 6.3 E2E（mock LLM，无需真实模型）

新增 3 个 scenario（`cli/browser4-cli/tests/e2e/scenarios/agent_run.rs`，命名沿用
`test_e2e_*` 的既有约定，并在 `scenarios/mod.rs` 注册）：

1. `test_e2e_agent_run_follow_reports_gate_rejection`：mock 让模型提交一个 `filesChanged`
   含幽灵文件的 `taskComplete` → `--ndjson` 流里必须出现 `gate.evaluated`（`passed=false`）与
   `task.failed`（`detail.finishGate.violations` 非空）。
2. `test_e2e_agent_run_follow_reports_repair_round`：mock 先返回失败的 `VERDICT_JSON`、
   再返回通过的 → 流里出现 `verify.verdict`（`passed` 由 false 变 true）与
   `repair.round`（`round=1`）。
3. `test_e2e_agent_run_stats_exposes_progress`：mock 让模型连续 `coding_read` 若干轮 →
   `/stats.mutations == 0` 且 `readOnlyRounds > 0`。

### 6.4 手工验收（对真实后端）

```bash
# 1) 起后端，跑一个需要修复的编码任务
browser4-cli agent run "fix the failing XTest" --coding --follow --ndjson > run.ndjson

# 2) 断点重放：先跟 30s 再断开，1 分钟后从头补
browser4-cli agent run "…" --coding          # 记下 taskId
curl -s "localhost:18182/api/commands/$TID/events?after=0&limit=5"   # 记 nextSeq/dropped
# 等待 > 缓冲窗口后再取，断言 resynced 或 dropped 增长可解释

# 3) MCP 面：仅用 MCP 客户端完成"提交 → 看步 → 看统计 → 干预"
#    command.run → command.events → command.stats → command.input
```

验收口径（Definition of Done 的机器化版本）：

- 任一失败任务，`/result.failureDetail` 能回答 **哪条规则、差哪些文件、哪个 check 红了**；
- 任一编码任务，`/stats` 能在运行中回答 **工具数 vs 文件改动数**；
- 任一从头到尾的跟随，**不会静默丢事件**（`resynced` 或 `dropped` 必须能解释缺口）；
- 只使用 MCP 的客户端可以完成上述三件事。

---

## 7. 后续路线（本文不做）

| 档位 | 项 | 理由 |
|---|---|---|
| P1 | 事件持久化（每任务分片 JSONL / 监督摘要） | 解决重启与长任务回放；需要容量与保留策略设计 |
| P1 | 事件级 SSE（`Last-Event-ID`） | 替换 400ms 轮询；需要背压与多观察者语义 |
| P1 | trace 读取 API + 写入侧脱敏 + 保留策略 | 安全与远程审计同时要满足 |
| P1 | `access.requested` 事件 | 依赖权限请求与任务事件的关联设计 |
| P1 | 干预 correlationId | `POST /input` 返回 id，`steer` 事件回带 |
| P2 | `b4.run` 子进程 span | 浏览任务的实质工作量目前在盲区 |
| P2 | 结构化目标 / 计划检查点 | 让"目标漂移"可判定；涉及提示词与 planner |
| P2 | `agent supervise <id>` 命令 | 在 P0 的机器判据之上做开箱即用的监督视图 |

---

## 8. 附录

### 8.1 新事件 schema 示例

```json
{"seq":41,"type":"verify.verdict","turn":12,"schemaVersion":1,
 "detail":{"passed":false,"module":"browser4-browser",
          "checks":[{"name":"compile","ran":true,"ok":false,"exitCode":1},
                    {"name":"test","ran":true,"ok":true,"exitCode":0}]}}

{"seq":42,"type":"repair.round","turn":13,"schemaVersion":1,
 "detail":{"round":1,"maxRounds":3,"reason":"compile browser4-browser failed (exit 1)"}}

{"seq":43,"type":"gate.evaluated","turn":14,"schemaVersion":1,
 "detail":{"passed":false,"strict":true,
          "violations":["filesChanged lists 1 file(s) neither git nor this run's file trace shows: ev.js"],
          "unlistedFiles":["ev.js"],"requiredGates":["mvnBuild"]}}

{"seq":44,"type":"finish.amend","turn":14,"schemaVersion":1,
 "detail":{"added":["ev.js"],"source":"mutation-trace"}}

{"seq":45,"type":"isolation.merge","schemaVersion":1,
 "detail":{"decision":"refused","changedFiles":2,
          "reason":"coding.verify did not cover browser4-agentic"}}

{"seq":46,"type":"round.start","turn":15,"schemaVersion":1,
 "mutations":7,"readOnlyRounds":0}
```

### 8.2 事件类型总表（P0 后）

| 组 | 类型 |
|---|---|
| 生命周期 | `task.created`、`task.started`、`task.done`、`task.failed`、`task.cancelled` |
| 轮次 | `round.start`（+ `mutations` / `readOnlyRounds`） |
| 工具 | `tool.call`、`tool.result`（+ `callId`） |
| 模型 | `model.text`、`run.usage` |
| 干预 | `steer`、`steering.undelivered`、`follow_up`、`follow_up.undelivered` |
| **裁决（新）** | `verify.verdict`、`gate.evaluated`、`repair.round`、`repair.exhausted`、`finish.repair`、`finish.amend`、`isolation.merge` |
| **引擎决策（新）** | `nudge`（coding / budget） |

### 8.3 文件索引

| 关注点 | 文件 |
|---|---|
| 事件模型 / 环形缓冲 / 脱敏 | `browser4-agent-tools/.../advanced/agent/AgentTaskEvents.kt` |
| 运行事件定义 | `browser4-agentic/.../agents/AgentRunEvent.kt` |
| 事件发射 / 完成判定 / 门禁 / 修复 | `browser4-agentic/.../agents/RobustBrowserAgent.kt` |
| 门禁规则 | `browser4-agentic/.../agents/FinishReportGate.kt` |
| 合并规则 | `browser4-coding/.../IsolationGate.kt` |
| 任务状态 / 事件落点 / 合并执行 | `browser4-agent-tools/.../advanced/agent/StatefulAgentRunner.kt` |
| REST 端点 | `browser4-rest/.../api/controller/CommandController.kt` |
| 响应实体 | `browser4-rest/.../api/entities/CommandEvents.kt` |
| 状态 / 结果映射 | `browser4-rest/.../api/entities/CommandStatus.kt` |
| MCP 工具定义 | `browser4-rest/.../agent/tool/CommandToolExecutor.kt` |
| 任务信封 | `browser4-agentic/.../tools/TaskEnvelopes.kt` |
| CLI 跟随渲染 | `cli/browser4-cli/src/agent_interactive.rs` |

---

## 9. 实施记录（2026-11-22，issue #652）

P0-A…P0-F 全部落地，全部为**新增可空字段 / 新增事件类型 / 新增工具**，旧消费方零改动。

### 9.1 落地清单

| 项 | 落点 | 关键点 |
|---|---|---|
| P0-A | `AgentTaskEvent` / `AgentRunEvent` 各加 `callId`、`detail`、`schemaVersion`（后者仅 TaskEvent）、`mutations`、`readOnlyRounds`；`AgentToolCallLoop.onToolRequest` 改为三参回调 | `AgentTaskEventSanitizer.detail()` 负责脱敏 + 预算：字符串 preview 240、数组 ≤20、字典 ≤12 键、嵌套 ≤2 层、序列化 ≤4 KB；任何裁剪都置 `detail["truncated"]=true`；超预算时先按半减数组、再丢尾部键 |
| P0-A 埋点 | `RobustBrowserAgent`：`verify.verdict`（VERDICT_JSON 解析处）、`gate.evaluated`（每次评估，含通过）、`repair.round` / `repair.exhausted`、`finish.repair` / `finish.amend`、`nudge`（coding / budget）；`StatefulAgentRunner.markIsolationRefused` + `finishIsolation`（`isolation.merge`） | 每个埋点紧贴既有的 tracer / logger 调用，成本近零；`task.failed` 之前发射，保证终态能取到 |
| P0-B | `round.start` 带 `mutations` / `readOnlyRounds`（gauge）；`RunStatsAccumulator` 覆盖式记录；`AgentTaskRunStats` / `AgentTaskStatus` / `CommandStatus` / 状态信封暴露 | 编码任务才有值，浏览任务保持 `null`（未知 ≠ 0） |
| P0-C | `StatefulAgentRunner.attachFailureDetail()` 在**终态行落盘前**汇总 `verify.verdict` / `gate.evaluated` / `repair.*` / `isolation.merge`；`AgentTaskStatus.failureDetail` → `CommandStatus` → `CommandResult` | 不是"失败时解析日志"，而是失败时把证据搬到结果里；`detachedSnapshot()` 显式复制，重启后仍可读 |
| P0-D | `AgentTaskEventPage`（`AgentTaskEventLog.page()`）+ `CommandEventsResponse.firstRetainedSeq/dropped/resynced` | `resynced = after > 0 && firstRetained > 0 && after < firstRetained - 1`；空页 `nextSeq` 仍是请求游标（幂等） |
| P0-E | `CommandToolExecutor.events/stats/input`；`TaskPolicy.eventsTool/statsTool` → 提交信封；`TaskEnvelopes.of(eventsTool, statsTool, mutations, readOnlyRounds)` + `EVENTS_SCHEMA` / `STATS_SCHEMA` | `CommandEvents.page()` 由 REST 与 MCP 共用，两条通道不会给出不同的 `status`/`done`/游标 |
| P0-F | CLI：`TaskEvent` 新增 `callId` / `detail` / `schemaVersion` / `mutations` / `readOnlyRounds`，`--ndjson` 原样透传；`EventsPage` 新增三个游标字段；`gate.evaluated` / `repair.round` / `finish.amend` 三条人类行；`resynced` 时一行警告（ndjson 下是一条 `{"type":"events.resynced"}` 记录） | `--ndjson` 的字段名与后端一致，不需要第二套解析 |

### 9.2 事件类型总表（实际）

生命周期 `task.created|started|done|failed|cancelled`；轮次 `round.start`（带 gauge）；工具
`tool.call|tool.result`（带 `callId`）；模型 `model.text`、`run.usage`；干预
`steer`、`steering.undelivered`、`follow_up`、`follow_up.undelivered`；裁决
`verify.verdict`、`gate.evaluated`、`repair.round`、`repair.exhausted`、`finish.repair`、
`finish.amend`、`isolation.merge`；引擎决策 `nudge`。

`schemaVersion = 1` 由 `AgentTaskEventLog.append()` 统一盖章。

### 9.3 追加的 P1 项（同一轮一并落地）

| 项 | 落点 | 说明 |
|---|---|---|
| P1-1 预览保留尾部 | `AgentTaskEventSanitizer.previewKeepingTail()`；`StatefulAgentRunner.recordRunEvent` 只对 `tool.result` 使用 | 工具输出的"结论"在末尾（编译器错误、`Tests run: …`），原来的头部截断恰好把它切掉。现在输出 `head…tail`，共享 240 字预算（尾部 45%），仍是一行、仍在码点边界切断；模型文本保持头部截断（它的开头才是重点） |
| P1-5 干预 correlationId | `PendingUserMessage`（agentic）、`StatefulAgentRunner.enqueueInput` 返回 `inputId`、`POST /{id}/input` 与 `command.input` 回带 `inputId`、交付的 `steer` / `follow_up` 步骤带 `detail.inputIds` | `accepted=true` 只证明"入队"；现在调用方能在事件流里认出自己那条消息。未交付的线（`steering.undelivered` / `follow_up.undelivered`）带同一个 `inputId`，两种结局都可对照 |
| P1-6 权限审批进流 | `AccessApprovals.observer` + `AccessApprovalEvent`（browser4-coding）；`StatefulAgentRunner.recordAccessEvent` 写入 `access.requested` / `access.resolved`，观察者在 applyAccessPolicy 后安装、任务结束时清除 | 无人值守时"卡在等审批"与"工具很慢"外观相同；现在是一条明确的步骤，且 `/approve` / `/deny` 的结果也回到流里。观察者异常被吞掉，绝不影响审批裁决 |
| P1 事件持久化 | `AgentTaskEventStore`（每任务 `<data>/agent/events/<taskId>.jsonl`，写后台线程 + 有界队列 + 4 MB 轮转 + TTL 清理）；`AgentTaskEventLog.onAdopt/restore()`；`StatefulAgentRunner` 在日志创建时回填、`events/stats/eventsPage` 走 `persistedEventLog()` | 解决 issue §4 的另一半（"重启后有状态无过程"）：恢复的步骤保留原 `seq`（游标仍有效），计数器按恢复的事件重算；写盘是 write-behind，队列满就丢（只影响磁盘历史，不影响运行），读取容忍崩溃留下的半行。恢复后的 `dropped` 从 0 起（重启前淘汰的步骤在分片里也已不存在） |
| P2 `agent supervise <id>` | `cli/browser4-cli/src/agent_supervise.rs`（纯读聚合：`command_status` + `/stats` + `/events` + `/api/agent/{id}/access`）+ `agent supervise` 命令；状态信封新增 `failureDetail`（`TaskEnvelopes` / `STATUS_SCHEMA`） | 把 P0/P1 导出的判据做成开箱即用的一屏：工具数与文件改动并列、gate/verify 裁决与红掉的 check、repair 轮次、待审批项、步骤窗口（`resynced`）。`--json` 同源；四个来源各自可失败（某一源 404/超时只让该段显示"unknown"） |
| P1 事件级 SSE | `GET /api/commands/{id}/events/stream`（`CommandController.streamSteps`；`ServerSentEvents.kt` 新增 `toServerSentEventsOf`，一个流可同时产出数据帧与注释保活帧） | 每帧带 `id: <seq>`，`Last-Event-ID` 或 `?after=` 决定起点，终态步骤之后立刻 complete，静默 15s 发注释保活，`?maxSeconds=` 可限时；慢读者只推迟下一次读取，不会丢步。CLI 跟随器仍用轮询（本轮未改），该端点面向看板/其它 agent |

| P1 trace 写侧脱敏 + 保留 | `SecretRedactor`（agentic `common`：敏感键/正则/URL 参数的**唯一**规则源，事件流 `AgentTaskEventSanitizer` 改为委托）；`CliLoopTracer.logTool` / `logPageView` 写盘前脱敏（`toolTracePayload` 抽成可测函数）；`AgentPaths.pruneTraceRuns()` + `browser4.agent.trace.retentionDays`（默认 7 天，`0`=永久）挂到 runner 的周期压缩 | issue §5 的两半：`cli-tool-trace.jsonl` 曾是唯一把 `coding.shell` 的命令、表单里的密码**明文**写到磁盘的地方；现在参数/结果/URL 查询参数在写入前即脱敏，记录形状不变（旧读者不受影响），整轮 trace 目录按 7 天老化删除（原先从不清理）。读取侧 API 未做（见 §9.4） |
| P1 trace 读取 API | `AgentTraceIndex`（`<data>/agent/trace-index.jsonl`，一行 `taskId → runDir`，按字节+行数双重上限压缩，目录被保留策略删掉时解析为 null）；`StatefulAgentRunner` 任务开始时记录；`GET /api/commands/{id}/trace`（列 `*.jsonl` 审计流）与 `/{id}/trace/{file}?offset=&limit=`（按行分页，读侧再脱敏一次） | 补上 issue §5 的最后一段：trace 目录按 agent uuid 命名，持有 taskId 的审阅者原先根本找不到它。现在可按 taskId 列出并分页读取完整过程（工具调用/用量/时间线/压缩账本），读取前对整行再脱敏一次（覆盖老版本写的明文 trace）；`cli-prompt/` 原始 prompt 转储**不**通过 API 暴露（其逐条消息的凭据尚未脱敏），仍是本地审计面 |
| P1 prompt 转储逐条脱敏 | `promptMessagePayload()`（agentic，抽成可测函数）：system/user/ai/tool_result 每条消息的 `text`、每个工具请求的 `arguments` 在写盘前脱敏；`CliLoopTracer.logPrompt` 改用它；trace API 因此开放 `cli-prompt/<ts>.<seq>.request.json` | `cli-prompt/` 是"模型看到的确切提示"，一份里同时有用户任务/指令、模型的工具请求、以及**完整**的工具结果——它也是最后一个把密码明文写盘的地方。逐条脱敏后，trace API 才能把它一起提供（此前只能列 `*.jsonl`）。记录形状不变（`type`/`text`/`toolExecutionRequests`），旧读者不受影响 |
### 9.4 未做（仍留在 §7 后续路线）

`b4.run` 子进程 span、结构化目标/计划检查点。
这些属于 P1/P2，不阻断"外部智能体监督"。
