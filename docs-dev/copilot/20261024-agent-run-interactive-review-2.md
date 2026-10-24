# `agent run -i` 二次评审 + `/stats` 运行统计命令

- 日期：2026-10-24
- 分支：`4.14-feat`（工作区变更，未提交）
- 范围：交互式会话 `agent run -i`（`cli/browser4-cli/src/agent_interactive.rs`）及其数据源
  - CLI：`agent_interactive.rs`、`http.rs`、`help.rs`、`commands.rs`、`tips.rs`
  - 后端：`AgentTaskEvents.kt`、`AgentRunEvent.kt`、`RobustBrowserAgent.kt`、`StatefulAgentRunner.kt`、
    `UserCommandExecutor.kt`、`CommandEvents.kt`、`CommandController.kt`
  - 测试：`AgentTaskEventLogTest`、`StatefulAgentRunnerTest`、`CommandControllerTest`、CLI 单测、mock e2e
  - 文档：`references/agent.md`、`SKILL.md`、两份 README、`agent-run-flow.md`、`help.rs`、`tips.rs`
- 结论：上一轮评审（`20261023-agent-run-interactive-review.md`）的 P1/P2 修复已落地并逐条复核通过；
  本轮**未发现新的阻断级缺陷**，只剩 4 条 P3 级 UX/后续项。同时实现用户要求的第 1 项功能：
  交互命令 `/stats`（含后端 `GET /api/commands/{id}/stats`）。

---

## 一、复核上一轮修复

| 编号 | 复核结果 | 证据 |
|---|---|---|
| P1-1 坏事件页杀死跟随器/会话 | 已修复 | `TaskFollower::poll` 的 `parse_events_page` 失败走 `tolerate_transient`（游标不动）；`Repl::run` 轮询错误只打印并释放任务 |
| P1-2 `--ndjson` / `--json` 输出纯度 | 已修复 | `Renderer::print` 受 `quiet` + `json_active()` 双门控；新增 `notice_enabled`；`--follow --json` 有 `json_field("raw", …)` |
| P2-1 瞬态失败预算 | 已修复 | `TRANSIENT_FAILURE_WINDOW = 300s` 时间窗，成功响应即复位（JVM 重启 10–30s 可承受） |
| P2-2 status 轮询创建 DEFAULT session | 已修复 | `UserCommandExecutor.getStatus` 的 agent 分支先 `probeAllRunners`，不再 `ensureAgentRunner`；本轮新增的 `/stats` 端点同样只探已有 runner，不产生会话副作用 |
| P2-3 `done` 恒 false | 已修复 | `CommandController.getEvents` 状态行缺失时由最后一条终态事件推导 |
| P2-4 `--follow` 不维护本地任务跟踪 | 已修复 | `track_terminal` / 超时 / 取消路径都写 `update_async_task_status` |
| P2-5 提交失败终止 REPL | 已修复 | `handle_line` 的提交与轮询错误都只打印 |
| P2-6 404 语义歧义 | 已修复 | 先探状态，`statusCode==404` 或消息含 `No task found` → 明确"任务不存在"且不打印降级提示 |
| P3-3 `/events [n]` 未实现 | 已修复 | 按当前游标取最新 n 条，非法参数给用法 |

上一轮保留的"steer 后任务运行中不重打提示符"仍是刻意取舍，本轮不动。

## 二、本轮新发现（均为 P3 / 后续项，不阻塞）

1. **`/quit` 或 stdin EOF 时仍有任务在跑**：`handle_slash_command` 的 `quit` 直接返回 `false` 结束会话，
   `run()` 的 EOF 分支同理——任务继续在服务端跑，但用户看不到任何提示（对比 Ctrl+C 会 cancel）。
   建议退出前打印一行 "task <id> keeps running — `agent status <id>`"（或提示 `/cancel`）。
   位置：`agent_interactive.rs` 的 `handle_slash_command` / `Repl::run`。
2. **完成时不显示消耗**：`complete_current` 只打印 `✔ done in 18.4s · <id>`，耗费需要再敲 `/stats`。
   若希望"跑完即知道花销"，可在完成行追加紧凑摘要（需要一次额外的 stats 请求）——本轮未做，属于产品取舍。
3. **本地计数可能少算**：`TaskFollower` 的本地统计只覆盖本次会话真正看到的步（400ms 轮询 + 200 条/页；
   服务端环形缓冲 1000 条，单轮工具爆发时游标会跳过被淘汰的事件）。因此 `/stats` 优先用服务端计数器，
   本地回退报告显式标注 "counted by this session … may undercount"。
4. **`-i` 下的 `--ndjson` 仍被忽略**（已在 stderr 明说），REPL 本身不产生 NDJSON；`/stats` 在 NDJSON
   语境下无意义。现状可接受，仅记录。

## 三、实现：`/stats`（需求 1）

### 3.1 目标

在 `agent run -i` 会话里用一个命令回答"这次运行花了什么"：

- 几次 LLM 对话（外层轮次）与几次模型请求（真实 LLM 调用）；
- token 消耗（输入/输出/合计）；
- 几次工具调用、失败几次、各自分布；
- 步数、被缓冲淘汰的步数、steering 条数。

### 3.2 数据链路

```
RobustBrowserAgent（CLI 引擎）
  round.start / tool.call / tool.result / model.text / steer   ← 已有
  run.usage {inputTokens, outputTokens, totalTokens, turn}     ← 新增（onModelResponse + 压缩调用）
        │ runEventListener
        ▼
StatefulAgentRunner.recordRunEvent → AgentTaskEventLog.append()
        │ 每追加一条即累加 AgentTaskRunStats（环形缓冲淘汰不影响计数）
        ▼
UserCommandExecutor.stats(id) → GET /api/commands/{id}/stats
        │ {taskId,status,processState,isDone,elapsedMs,stats{…}}
        ▼
CLI Repl::print_stats → 人读四行 / --json 的 stats 字段
```

为什么是服务端计数器而不是客户端数事件：事件日志是每任务 1000 条的环形缓冲，长编码任务会淘汰旧步；
客户端按收到的事件数只能少算。计数器在 `append()` 时累加，因此对淘汰的步仍然精确。

### 3.3 语义决议

| 指标 | 语义 | 理由 |
|---|---|---|
| `rounds` | 外层 agent 轮次（`round.start`） | 用户口中的"几次对话"；一轮内可能包含多次模型请求 |
| `modelRequests` | 成功模型请求数（`run.usage`，含上下文压缩调用） | 真实 LLM API 调用次数；比 rounds 更接近计费口径 |
| `inputTokens`/`outputTokens`/`totalTokens` | provider 上报值累加；provider 只给 in/out 时 total 记为二者之和 | 不给 total 的网关很常见，缺省不能用 0 冒充 |
| `toolCalls` | `tool.call` 数（含 `system.listTools` 等发现类元工具） | 与 `--follow` 打印的 `#n` 序号一致 |
| `toolFailures` | `tool.result` 且 `ok=false` | 只统计明确的失败 |
| `steerMessages` | 注入的 steering 条数 | 交互会话专有 |
| `events` / `droppedEvents` | 累计步数 / 环形缓冲淘汰数 | 让"少算了多少"可见 |
| `toolCounts` | 每工具调用次数，按次数降序，最多 20 项 | `/stats` 只展示前 5 |

token 的"未知"与"零"必须区分：`AgentTaskRunStats.tokensReported`（CLI 侧 `tokens_reported()`）为 false 时
报告 `not reported by the provider`；一次请求都没发生时报告 `no model request yet`。

### 3.4 REST 契约

`GET /api/commands/{id}/stats`（`CommandController.getStats`）：

```json
{
  "taskId": "5b0c…",
  "status": "running",
  "processState": "in_progress",
  "isDone": false,
  "elapsedMs": 192400,
  "stats": {
    "rounds": 3, "modelRequests": 11, "toolCalls": 39, "toolFailures": 2,
    "steerMessages": 1, "inputTokens": 48231, "outputTokens": 6120, "totalTokens": 54351,
    "events": 132, "droppedEvents": 0, "tokensReported": true,
    "firstEpochMillis": 1761…, "lastEpochMillis": 1761…,
    "toolCounts": {"b4.run": 12, "browser_snapshot": 8}
  }
}
```

- `404`：没有任何 runner 持有该任务的运行日志（未知 id、后端重启、日志 TTL 过期）——与 `/events` 的 404
  语义一致；
- `status` 行过期但计数器还在时返回 `status:"unknown"` 而不是编造 `done`；
- `elapsedMs` 优先用状态行的 `startedTime`（运行中按 `now` 计），状态行缺失时退回计数器的首末步时间跨度；
- 该端点**不调用** `ensureAgentRunner`，因此轮询既有任务的统计不会凭空创建 DEFAULT 会话（沿用 P2-2 的约束）。

### 3.5 CLI 行为

```
agent> /stats
5b0c3f2a · running · 3m12s
  llm     3 round(s) · 11 request(s)
  tokens  in 48,231 · out 6,120 · total 54,351
  tools   39 call(s) · 2 failed (b4.run 12, browser_snapshot 8)
  steps   132 · 1 steering message(s)
```

- `/stats [task-id]`：无参数 = 当前（或最后一个）任务；显式 id 用于回看本次会话内别的任务；
- 输出走 `self.out`（`--quiet` 静默），同时写 `--json` 信封的 `stats` 字段；
- 旧后端 / 日志过期（404/405/501）：退回"本会话观察到的步"计数，并在报告里加一行
  `⚠ counted by this session … may undercount`；从未观察到任何步时直接说"没有统计"，不打印一行 0；
- 任务结束时把本地计数（`release_current`）保存下来，`/stats` 在 follower 已销毁后仍可回答。

### 3.6 文件与改动点

| 层 | 文件 | 改动 |
|---|---|---|
| 事件模型 | `browser4-agent-tools/…/AgentTaskEvents.kt` | `AgentTaskEvent` 增加 `inputTokens/outputTokens/totalTokens` 与 `TYPE_USAGE`；新增 `AgentTaskRunStats` + 私有累加器；`AgentTaskEventLog.stats()` |
| agent 上报 | `browser4-agentic/…/AgentRunEvent.kt` | `USAGE`/`USAGE_CATEGORY_COMPACTION` 常量 + token 字段 |
| agent 上报 | `browser4-agentic/…/RobustBrowserAgent.kt` | `emitUsageEvent()`；`onModelResponse` 与 `summarizeCliLoop` 各发一条 `run.usage` |
| runner | `browser4-agent-tools/…/StatefulAgentRunner.kt` | `recordRunEvent` 透传 token；新增 `stats(id)` |
| REST | `browser4-rest/…/UserCommandExecutor.kt` | `stats(id)`（探所有 runner，不建会话） |
| REST | `browser4-rest/…/entities/CommandEvents.kt` | `CommandStatsResponse` |
| REST | `browser4-rest/…/controller/CommandController.kt` | `GET /{id}/stats` |
| CLI | `cli/browser4-cli/src/http.rs` | `get_command_stats` |
| CLI | `cli/browser4-cli/src/agent_interactive.rs` | `RunStats`/`TaskStatsReport`/`parse_task_stats`、本地计数、`/stats` 渲染与降级、`/help` 条目 |
| 测试 | `AgentTaskEventLogTest`、`StatefulAgentRunnerTest`、`CommandControllerTest`、CLI 单测、mock e2e | 见 3.7 |
| 文档 | `references/agent.md`、`SKILL.md`、`README.md`、`README.zh.md`、`cli/browser4-cli/README.md`、`help.rs`、`commands.rs`、`tips.rs`、`agent-run-flow.md` | `/stats` 与 `run.usage` 说明 |

### 3.7 测试覆盖

- Kotlin：`AgentTaskEventLogTest` 新增 4 个用例（聚合语义、淘汰后仍精确、未知 token 不等于 0、首末时间戳）；
  `StatefulAgentRunnerTest` 新增 1 个（mock agent 上报的步 → 统计，含 token 透传）；
  `CommandControllerTest` 新增 4 个（200 计数/状态/耗时、状态行过期仍可读、未知 id 404、**线格式契约**：
  序列化后的 JSON 必须包含 CLI 读取的键名，避免 Kotlin 属性改名后 `/stats` 静默全 0）。
- Rust 单测：`parse_task_stats`（完整/缺字段/无 counters/非 JSON）、`RunStats::observe` 与服务端聚合一致、
  四种渲染场景（完整、provider 未上报 token、无请求、本地回退）、`format_run_duration`、`format_thousands`、
  `to_json` 携带 token。
- e2e（mock 后端）：`test_e2e_agent_run_interactive_reports_run_statistics` —— `/stats` 打出四行统计并只请求
  一次 `GET /api/commands/agent-task-1/stats`；`/help` 断言列出 `/stats`。

### 3.8 验证结果

| 检查 | 命令 | 结果 |
|---|---|---|
| CLI 单测（全量） | `cargo test --bin browser4-cli` | **1910 passed / 0 failed / 4 ignored**（较上轮 1902 增加 8 个统计用例：解析 2、聚合 1、渲染 2、时长/千分位 2、token JSON 1；`help.rs` 的 `/stats` 断言补进既有用例） |
| CLI e2e（mock，交互式） | `cargo test --test e2e -- --scenario 'test_e2e_agent_run_interactive*'` | **3/3 通过**（含新增 `test_e2e_agent_run_interactive_reports_run_statistics`，2.82s） |
| Kotlin `AgentTaskEventLogTest` | 定向 maven test | **10/10 通过**（原 6 + 新 4） |
| Kotlin `StatefulAgentRunnerTest` | 定向 maven test | **23/23 通过**（原 22 + 新 1） |
| Kotlin `CommandControllerTest` | 定向 maven test（`-pl browser4-rest -am`） | **16/16 通过**（原 12 + 新 4，含线格式契约） |
| Kotlin 编译 | `mvnw -pl browser4-rest -am test-compile` | BUILD SUCCESS |
| 全量 maven 定向（agent-tools + rest，-am） | 同上 | BUILD SUCCESS |

未做的验证（如实记录）：
1. 未在真实后端 + 真实 LLM 上跑一次端到端 token 统计（需要 API key 与浏览器）。`browser4-bundle/target/Browser4Bundle.jar`
   仍是 10-22 的构建产物，因此本次没有再跑 `requires_browser4` 场景；真实链路由编译期接线 + runner 级单测
   （`recordRunEvent` → 计数）+ controller 线格式契约覆盖。
2. `RobustBrowserAgent.emitUsageEvent` 的实际触发（`onModelResponse` / 压缩调用）没有专门单测——与上一轮
   `runEventListener` 接线的覆盖缺口同源，需要一次真实运行才能验证。

## 四、后续建议

1. 任务完成行追加紧凑消耗摘要（rounds / tools / tokens），免去再敲 `/stats`；
2. `/quit` 时对仍在运行的任务给一行提示（可选：提供 `/quit --cancel`）；
3. 若后续需要跨会话回看历史任务的消耗，可把 `AgentTaskRunStats` 落盘到任务的 JSONL 行（当前只在内存，
   随环形缓冲/Caffeine TTL 消失）；
4. 顶层 `agent stats <id>` 命令（非交互）只是一个薄封装，等有需求再做。
