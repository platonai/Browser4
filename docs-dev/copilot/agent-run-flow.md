# `browser4-cli agent run "<task>"` 执行流程梳理

> 日期：2026-09-09 · 对象：`agent run` 全链路（CLI 解析 → MCP `command_run` 提交 →
> CustomToolRegistry/CommandToolExecutor → UserCommandExecutor → StatefulAgentRunner →
> RobustBrowserAgent(CLI_TOOL_LOOP) → AgentToolCallLoop → `b4.run` → browser4-cli 子进程 →
> 同后端浏览器），即 v2 CLI 工具循环引擎落地后的内置上网智能体。

## 0. 总体链路

```
browser4-cli (Rust)                       browser4-rest (Kotlin)                     browser4-agent-tools / browser4-agentic
┌──────────────────────┐  POST /mcp/call-tool   ┌────────────────────────────┐
│ agent run "xxx"      │  {tool: command_run,    │ MCPToolController           │
│   ↓ rewrite →agent-run│  arguments:{command,    │  → dispatchToToolExecutor   │
│   ↓ handle_agent_run  │  async:true, engine}   │  → CustomToolRegistry(domain│
│   ↓ submit (async)    │ ─────────────────────▶ │    "command")               │
│                       │                        │  → CommandToolExecutor      │
│                       │                        │  → UserCommandExecutor      │
│ --wait 轮询 2s        │ ◀──────────────────────│  → StatefulAgentRunner       │
│ command_status /      │                        │  → RobustBrowserAgent.run    │
│ command_result        │                        │    → CLI_TOOL_LOOP 引擎      │
└──────────────────────┘                        └────────────────────────────┘
```

两条数据流向：

1. **提交**：CLI 以 `async=true` 调 `command_run`，后端立即返回 task ID（UUID），任务在服务端后台执行；
2. **取回**：CLI 用 `command_status` / `command_result` 轮询同一 task ID 拿状态与结果。

---

## 1. CLI 端（Rust，cli/browser4-cli）

### 1.1 命令解析与重写

- `agent run "xxx"` 首先被 `rewrite_prefixed_command()`（`src/main.rs:19964`，prefix `"agent"` →
  `agent-{sub}`）重写为内部名 **`agent-run`**。
- `preferred_spaced_command_form()`（`src/main.rs:19981`）保证直接敲 `agent-run` 时提示改用
  spaced 形式（`agent run`）。
- `run()`（`src/main.rs:21337`）查 `commands_map()` 得到 `CommandDef`（`src/commands.rs:3429`）：
  - `tool_name_fn` → MCP 工具名 **`"command_run"`**；
  - `tool_params_fn` → 生成参数 `{task, wait?, waitTimeout?, noopLimit?, engine}`，`engine` 默认 `"cli"`；
  - `batch_supported: false`。
- `should_ensure_server_running("agent-run")` 为真（`src/main.rs:20655`）→ `ensure_server_running()`
  自动拉起/复用后端（Spring Boot，生产默认 `http://localhost:18182`；开发检出从 8282 起另分配端口），无需用户先 `open`。
- 分发到 `"agent-run" => handle_agent_run(...)`（`src/main.rs:22335`）。

### 1.2 `handle_agent_run`（`src/main.rs:10792`）

1. 取 `task`（为空直接报错），读 `noopLimit` / `engine`，组装
   `extra = {"engine": "cli", "noopLimit"?: N}`。
2. `submit_plain_command_with_options(client, base_url, task, async=true, extra)`
   （`src/http.rs:512`）：
   - payload：`{"command": task, "async": true, "engine": "cli", ...}`；
   - 经 `call_tool_with_timeout_override(..., "command_run", payload, Some(180))` 发
     `POST {base}/mcp/call-tool`，body `{"tool":"command_run","arguments":{...}}`
     （`src/http.rs:402-412`）。
   - **180s HTTP 超时覆盖**：异步提交首次会创建 session 的 companion agent（热身），
     默认 30s 会误报超时（`src/http.rs:525-530`）。
3. 响应文本即 **task ID**（可能带引号，`trim_matches('"')` 后使用）。
4. `detect_missing_llm_error_for_submitted_agent_task`（`src/main.rs:11001`）短暂轮询
   `command_status`，把「LLM API key 未配置」类失败转为可读错误并提前退出。
5. `track_async_task(&task_id, "agent", task, None)` 写入本地状态（`~/.browser4`），
   保证 `agent list` / `agent status` 跨会话可查。
6. **不带 `--wait`**：打印 `Task submitted: <id>` 与使用提示后返回；
   **带 `--wait`**（`src/main.rs:10841`）：
   - 每 2s 轮询 `command_status`（`get_command_status`，`src/http.rs:941`）；
   - 超时默认 600s，可被 `--wait-timeout` 或 `BROWSER4_CLI_AGENT_WAIT_TIMEOUT_SECS` 覆盖；
     超时后任务仍在服务端运行，本地状态标记 `processing`；
   - `processState == "done" | "completed"` → 再调 `command_result` 打印结果
     （`src/main.rs:10880-10904`）；结果先经 `agent_result::render_result_text`
     渲染成段落（`Summary`/`Key findings`/…），不再整段倾倒原始 JSON；
   - `processState == "failed" | "error"` → 报错退出（`src/main.rs:10906`）；
   - 其他状态每 5s 打印一次进度（`[Ns] <message>`）。

---

## 2. 后端 HTTP/MCP 调度（browser4-rest）

### 2.1 `MCPToolController.callTool`（`MCPToolController.kt:298`，`POST /mcp/call-tool`）

`command_run` 不在 session 生命周期工具集合内 → 走 `dispatchToToolExecutor()`
（`MCPToolController.kt:781`）：

1. `normalizeFrontendToolCall("command_run", args)`：无前端别名，原样保留；
2. `normalizeToolArguments()`：参数规范化（清空 `sessionId`、命名转换等）；
3. `extractDomain("command_run")`（`MCPToolController.kt:895`）：按
   `CustomToolRegistry.instance.getAllDomains()` 最长前缀匹配 → 域 **`command`**；
4. `dispatchToCustomExecutor()`（`MCPToolController.kt:914`）：通过 executor 的 ToolSpec
   反向匹配（`toMcpToolName("command","run") == "command_run"`）把 snake_case 方法名解析为
   `"run"`。

域注册：`CommandToolMountConfiguration`（`browser4-rest/.../config/CommandToolMountConfiguration.kt`）
实现 `ToolMount`（`PluginMount`），被 `PluginManager` 自动发现并把
`CommandToolExecutor(userCommandExecutor)` 注册进 `CustomToolRegistry`
（`browser4-agentic/.../tools/CustomToolRegistry.kt:46`）；MCP 调度器与 LLM agent
工具系统共用同一注册表。

### 2.2 `CommandToolExecutor.callFunctionOn("command","run",args)`
（`browser4-rest/.../agent/tool/CommandToolExecutor.kt:81`）

- `sessionId` 缺省 `DEFAULT_SESSION_ID`（CLI 提交时未携带 sessionId）；
- 校验 `command` 必填（`allowed = {command, async, noopLimit, engine}`）；
- `isAsync` 缺省 `true` → 本次进入 `service.submitPlainCommand(sessionId, command, noopLimit, engine)`。

### 2.3 `UserCommandExecutor.submitPlainCommand`
（`browser4-rest/.../agent/tool/UserCommandExecutor.kt:178`）

1. 空串 → 立即返回拒绝状态（`SC_BAD_REQUEST`，缓存于 `rejectedStatuses`）；
2. `engine != OBSERVE_ACT`（默认 cli 引擎）→ **无条件走 agent 分支**
   `submitAgentTask()`，跳过 URL 快捷路径与 `CommandNormalizer`（`UserCommandExecutor.kt:193-197`）；
3. 只有显式 `--engine observe-act` 的旧引擎才走 URL/normalizer → `StatefulPageVisitor` 路径。

`submitAgentTask`（`UserCommandExecutor.kt:243`）：

1. `ensureAgentRunner(sessionId)`：按 session 懒建/复用
   `StatefulAgentRunner(sessionManager.getOrCreateSession(sessionId).agenticSession)`；
2. `runner.create()`：生成 `AgentTaskStatus(id = UUID, processState="created")`，
   写入内存缓存 + JSONL 持久化，发事件 `StatefulAgentRunner.created`；
3. `taskOwner[id] = "agent"`（供 `getStatus` 精确路由）；
4. `runner.submit(command, status, noopLimit, engine)` → 在 `taskScope`
   （`Dispatchers.IO` + `SupervisorJob`）中 `launch { execute(...) }`，立即返回 task ID。

---

## 3. Agent 执行（browser4-agent-tools / browser4-agentic）

### 3.1 `StatefulAgentRunner.execute`（`StatefulAgentRunner.kt:232`）

- `runMutex.withLock` —— **同一 session 的 agent 任务串行执行**（companion agent 与
  `AgentStateManager` 是共享资源，并发会交错上下文与历史），任务提交时排队。
- `executeSerialized`（`StatefulAgentRunner.kt:243`）：
  - `status.refresh(SC_PROCESSING)`（`processState → "in_progress"`）；
  - 把 `noopLimit` / `engine` 写入 `RobustBrowserAgent.noopLimitOverride` /
    `runEngineOverride`（**每任务级覆盖**，`--noop-limit` 生效点）；
  - 绑定 `DefaultServerSideAgentEventHandlers` + 事件收集协程 → `status.emitEvent(...)`
    实时更新 message（"Task created, waiting to start" → "Agent is working" → ...）。

### 3.2 `executeAgentCommand`（`StatefulAgentRunner.kt:353`）

1. `status.submittedTask = plainCommand`；
2. 任务类型**显式声明**（2026-10 起）：`executeSerialized` 把 `command_run` 的 `coding`
   参数写入 `RobustBrowserAgent.codingMode`（CLI 对应 `agent run --coding`）。不显式指定
   一律视为 browser 任务，**不再做关键词自动判定**（原 `CodingTaskDetector` 已移除）。
   浏览任务走 `ensureSessionDriverHealthy()` 检测 bound driver，不健康则
   unbind + close 并让下次 `getOrCreateBoundDriver` 重新拉起浏览器；coding 任务跳过这一步;
3. 保存用户当前页面 URL（任务结束后恢复现场，`StatefulAgentRunner.kt:382-410`）；
4. `agent.run(plainCommand)` → `RobustBrowserAgent.run`（`RobustBrowserAgent.kt:354`）：
   `run(task)` → `run(ActionOptions)` → `resolveProblemInCoroutine` →
   `resolveProblemWithRetry` → `doRunAgentLoop` → 因
   `effectiveRunEngine == CLI_TOOL_LOOP`（`RunEngine.parse("cli")` → CLI_TOOL_LOOP，
   `AgentConfig.kt:27`；默认值即 CLI_TOOL_LOOP）进入 `doRunCliAgentLoop`；
5. 结束后 `status.agentHistory = agent.stateHistory.snapshotFor(agent.lastRunSessionId)`
   —— **任务级 history 切片**，防止与其他任务共享/后续 trim 串扰；
6. 根据 `history.finalResult` 设置结果：
   - 有 finalState → `status.message = summary/description`，`SC_OK`；
   - 无 finalState 但页面有内容 → `SC_OK` + 提示；
   - 无 finalState 且无页面内容 → `SC_EXPECTATION_FAILED`
     （"Agent produced no results (no page content)..."）。

### 3.3 `doRunCliAgentLoop`（`RobustBrowserAgent.kt:745`）—— CLI 工具循环引擎

- `buildCliToolLoop()` 构造 `AgentToolCallLoop`（chatModel + toolSpecifications +
  `ToolExecutionCoordinator(agentToolManager, registry)`）；初始工具集
  `browser4.agent.toolLoop.initialToolSet`（默认 `core`）+ 编码模式自适应暴露；
  `maxIterations = max(toolLoopMaxIterations, 40)`（浏览器任务跨多轮，溢出由外层接管）。
- agent memory 初始化：`recall` 片段注入 system prompt（跨任务渐进记忆），
  scratchpad 每轮作为尾消息重注入；`CliLoopTracer` 把完整 prompt / 模型响应 / 每个工具
  执行的轨迹落盘 `~/.browser4/logs/agent/<start-time>/<agent-uuid>/`。
- 主循环（`while turn < maxTurns`）：
  - `loop.generate(roundMessages)`：LLM 推理（`llmInferenceTimeoutMs`，requestTokenLimiter，
    超量/溢出时 `overflowContinuationMessage` 续跑，避免重放已完成工具）；
  - 模型产出工具调用，经 `ToolExecutionCoordinator` → `AgentToolManager.execute` 执行：
    - **`b4.run(args="...")`** → `B4CliToolExecutor`（`B4CliToolExecutor.kt:131`）：
      解析 `browser4-cli` 二进制（bundle / PATH / 自动安装）并启动子进程
      （`CliJobRegistry`；**嵌套 `agent run`/`agent-run`/`act` 被禁止**）；短命令直接返回，
      超过 ~10s 升级为 `[job: <id>]` 句柄，用 `b4.status / b4.wait / b4.kill` 管理；
    - `system.taskComplete(summary, keyFindings, filesChanged, problems)` →
      `completionRef` → `completeCliRun`（`RobustBrowserAgent.kt:1403`，
      memory `sink.completed` + L0→L1 consolidator 调度）；
    - `system.skillDoc(...)`（按需加载 SKILL.md / 参考文档）、`coding.*`、
      `memory_*`（记忆读写）等。
  - **完成判定**（`RobustBrowserAgent.kt:785-837`）：
    ① 首选 `system.taskComplete`；② 兜底：本 run 已执行 ≥1 个工具后再出现纯文本回复
    （finish-gate 拒绝 0 工具的假完成）；③ 纯文本卡死 → `StopReason.NOOP_LIMIT`；
    超过 `maxSteps` → `MAX_STEPS`（异常上抛，Runner 标记失败而非假成功）。
- 非正常终止（no-op limit / max steps / 重试耗尽）通过 `result.exception?.let { throw it }`
  上抛（`RobustBrowserAgent.kt:391`），确保任务被标记为失败而不是 status 200。

### 3.4 状态收尾

- `executeSerialized` 的 `finally`：`status.done()`（`processState="done"`、`finishTime`）+
  `onStatusChanged` 追加 JSONL（`~/.browser4/data/agent/agent-tasks.jsonl`）。
- `AgentTaskStatus` 状态机：`created → in_progress → done`；`isDone = processState=="done"`；
  状态缓存：Caffeine（100 条 / 2h TTL）+ JSONL 持久化，重启后 `restoreFromDisk()` 恢复，
  终端态保留 120 分钟（`taskTtlMinutes`），每 5 分钟压缩。

---

## 4. 结果回传（CLI 轮询）

- `command_status` → `MCPToolController` → `CommandToolExecutor.status` →
  `UserCommandExecutor.getStatus`（`UserCommandExecutor.kt:289`）：
  按 `taskOwner` 路由到 agent runner（未知归属时探测所有 runner/visitor，服务重启后
  仍能恢复终态），读 `StatefulAgentRunner.statusCache`；
- `AgentTaskStatus.toCommandStatus()`（`CommandStatus.kt:199`）：内部 `"done"` 映射为
  用户面 **`"completed"`**，`statusCode=200`，`commandResult.summary` 取 agentHistory
  末态 summary（失败时优先 failureReason）；
- `command_result` → `getResult` → `commandResult` JSON；CLI 把它渲染为段落后打印
  （原始信封只在 `--json` / `/result --json` 下出现）/ `agent status`
  显示进度 / `agent list` 汇总（本地缓存 + 服务端刷新）。

---

## 5. 关键业务点速查

| 点 | 位置 | 说明 |
|---|---|---|
| 任务异步提交 | `cli/browser4-cli/src/http.rs:512` | `async=true`；180s HTTP 超时覆盖（首用 warm-up） |
| session 归属 | `CommandToolExecutor.kt:100` | CLI 未传 sessionId → 后端默认 session |
| 引擎分支 | `UserCommandExecutor.kt:195` | 默认 cli 引擎永远走 agent，URL 不再劫持 |
| 任务串行 | `StatefulAgentRunner.kt:238` | 每 session `runMutex`，并发任务排队 |
| 每任务参数 | `StatefulAgentRunner.kt:255-263` | `--noop-limit` / `--engine` 运行时覆盖 |
| 历史隔离 | `StatefulAgentRunner.kt:392` | `snapshotFor(lastRunSessionId)` 防串扰 |
| 完成信号 | `RobustBrowserAgent.kt:785-837` | `system.taskComplete` 或 ≥1 工具后纯文本 |
| 失败上抛 | `RobustBrowserAgent.kt:391` | 异常终止不再伪装成 status 200 |
| 状态持久化 | `StatefulAgentRunner.kt:63` | JSONL 持久化，重启恢复，TTL 120min 压缩 |
| 工具回环 | `B4CliToolExecutor.kt:131` | `b4.run` 以子进程跑 browser4-cli；禁嵌套 agent |

## 6. 相关入口文件

| 文件 | 角色 |
|---|---|
| `cli/browser4-cli/src/commands.rs` | `agent-run` / `agent-status` / `agent-result` / `agent-list` / `agent-cancel` CommandDef |
| `cli/browser4-cli/src/main.rs` | `rewrite_prefixed_command`、`handle_agent_run`、`--wait` 轮询 |
| `cli/browser4-cli/src/http.rs` | `submit_plain_command_with_options` / `get_command_status` / `get_command_result` |
| `browser4-rest/.../mcp/controller/MCPToolController.kt` | `/mcp/call-tool` 入口与统一分发 |
| `browser4-rest/.../config/CommandToolMountConfiguration.kt` | 注册 `command` 域 executor |
| `browser4-rest/.../agent/tool/CommandToolExecutor.kt` | `command.run/status/result` |
| `browser4-rest/.../agent/tool/UserCommandExecutor.kt` | 提交/状态/结果聚合，任务归属路由 |
| `browser4-agent-tools/.../advanced/agent/StatefulAgentRunner.kt` | agent 任务状态机、串行执行、持久化 |
| `browser4-agentic/.../agents/RobustBrowserAgent.kt` | CLI_TOOL_LOOP 引擎主循环 |
| `browser4-agentic/.../tools/builtin/B4CliToolExecutor.kt` | `b4.run` 子进程执行器 |

## 7. 已知边界与坑（实测/代码注释沉淀）

- **无 LLM key**：`command_run` 任务会快速失败，CLI 通过
  `detect_missing_llm_error_for_submitted_agent_task` 把「LLM API key is not configured」
  转成可操作错误，而非 30s HTTP 超时；
- `sessionId` 缺省 DEFAULT：多会话用户需注意任务归属默认 session；
- 任务式 noop 上限：长编码链建议 `--noop-limit 8-10`；
- 单个 session 内任务串行：并行需求用 swarm（独立浏览器上下文）而不是并发 `agent run`。

## 8. 实时事件流与交互式会话（2026-10-23 新增）

`agent run --follow` / `agent run -i` 走同一条链路：**任务级事件日志 + 游标轮询**。
默认 `agent run` 行为完全不变，只有显式传 `--follow` / `--ndjson` / `-i` 才会消费事件。

| 环节 | 位置 | 说明 |
|---|---|---|
| 事件产生 | `RobustBrowserAgent.doRunCliAgentLoop` / `buildCliToolLoop` | 每轮 `round.start`、模型发言 `model.text`、每次工具 `tool.call` / `tool.result`。经 `runEventListener` 回调直连 runner（**不走** `AgentEventBus`：需要顺序、且不给全局事件总线增加高频事件）。 |
| 事件落地 | `StatefulAgentRunner.recordRunEvent` → `AgentTaskEventLog` | 每任务 1000 条环形缓冲；任务级 Caffeine 缓存（128 个任务 / 120 分钟）；**不写 JSONL**（行集是任务注册表，不是运行日志）。参数/结果截断 240 字符，凭据键名与密码类 selector 的输入脱敏。 |
| 查询 | `GET /api/commands/{id}/events?after=<seq>&limit=` | 返回 `{taskId,status,done,nextSeq,events[]}`；空页不回退游标；未知 id 404。 |
| CLI 消费 | `cli/browser4-cli/src/agent_interactive.rs` | 400ms 游标轮询（状态回退 1s）；老后端 404 → 提示一行并退回 `command_status` 轮询；`--ndjson` 每行一个 JSON 对象。 |
| 插话 | `POST /api/commands/{id}/input` | `StatefulAgentRunner.pendingInputs` 队列；`RobustBrowserAgent.steeringSource` 在每个模型请求前（`AgentToolCallLoop.onBeforeIteration`）与每个外层轮次顶部抽取，注入为 `UserMessage`，因此不需要等整轮结束。 |
| 统计 | `GET /api/commands/{id}/stats` | 返回 `{taskId,status,processState,isDone,elapsedMs,stats{rounds,modelRequests,toolCalls,toolFailures,steerMessages,inputTokens,outputTokens,totalTokens,cachedInputTokens,cacheWriteInputTokens,cacheReported,cacheHitRatio,events,droppedEvents,firstEpochMillis,lastEpochMillis,toolCounts}}`。计数由 `AgentTaskEventLog` 在 `append()` 时累加（`AgentTaskRunStats`），因此环形缓冲淘汰事件后仍然精确；token 来自每轮模型请求的 `run.usage` 事件（`RobustBrowserAgent.onModelResponse` 与压缩调用）。`cachedInputTokens`/`cacheWriteInputTokens` 是 provider 上报的 prompt cache 读取/写入量（DeepSeek/OpenRouter 等 `prompt_tokens_details.cached_tokens`，Anthropic `cache_read_input_tokens` + `cache_creation_input_tokens`）；未上报时计数为 0 且 `cacheReported=false`（"未知"，不是 0% 命中），`inputTokens` 已归一为 cache-inclusive，`cacheHitRatio` 为派生命中率。预算口径按 **cache-miss** 计（`(inputTokens − cachedInputTokens) + outputTokens`），`run.usage.cumulativeTokens` 与 `AgentTokenBudget.consumedTotal` 都是这个"charged"值，`inputTokens` 保持原始 prompt 计数。未知 id / 日志过期 404。 |

关键约束：

- 事件**不持久化**：后端重启后 `agent status/result` 仍可用（磁盘行集），但事件尾丢失；
- `--follow` 默认**不设超时**（`--wait` 仍默认 600s）；`--wait-timeout` 可给 `--follow` 封顶；
- Ctrl+C 在 `--follow` / `-i` 中会 **cancel 任务**，不再把任务遗留在服务端；
- `-s <name>` 现在会作为 `sessionId` 透传给 `command.run`（原先被忽略，任务一律落到 DEFAULT session）。

## 9. 多轮会话上下文与下一步预测（2026-11-12 新增）

### 9.1 跨任务会话上下文：完整对话继承 + 压缩指令（2026-11-21 改版）

问题：`agent.run(instruction)` 每次都是**全新对话**，所以 `-i` 中第 N 个任务完全不知道第
N-1 个任务做过什么。按「每个任务一条摘要 bullet」的做法能补一点背景，却答不了"我们最后用的是哪个
选择器""工具到底返回了什么""那次运行写了哪个文件"。

现在的策略：**第二个任务直接继承第一个任务的真实对话**。会话保留最近若干任务的完整转录（任务指令、
模型每次发言、每次工具调用与参数/结果、用户插话、任务终态与其文件/工作区），下一个任务把这段转录
整体作为 `## Session context` 附加在指令后发给模型；超预算时用**压缩指令**让模型把较老的转录折成
会话记忆。实现：
`browser4-agent-tools/.../advanced/agent/SessionConversation.kt` + `StatefulAgentRunner` +
`RobustBrowserAgent.compressSessionMemory`。

| 环节 | 位置 | 说明 |
|---|---|---|
| 转录 | `StatefulAgentRunner` 的 `runEventListener` → `SessionConversation.noteEvent` | `model.text` / `tool.call` / `tool.result` / `steer` 逐条记录（单条 600/300/300/300 字上限）；`round.start` 与 `run.usage` 是统计不是上下文，丢弃。 |
| 任务边界 | `executeSerialized` 开头 `beginTask(instruction)`、终态 `endTask(status)`  | 任务行 `#N <用户指令>`；终态行 `→ done in 2m 13s · files: a, b · workspace: <绝对路径>`（工作区目录已不存在则不写，避免死路径）。指令用用户原文，绝不包含块本身。 |
| 注入 | 同上，`instruction` 之后 | 渲染成 `## Session context` 块：块头只陈述"下面是本会话已发生任务的真实对话（问了什么、模型说了什么、调了哪些工具及结果、插话、每个任务的终态）"，**不判断**哪条指令是当前目标、哪条被放弃、也不给"该不该续接"的指引——判断交给模型。随 `agent.run(instruction)` 进入模型；首任务为空 ⇒ 单次 `agent run` 行为不变。 |
| 压缩触发 | `sessionBlockFor()`（下一个任务开始前） | `needsCompression()` = 渲染转录超过 `browser4.agent.session.contextTokens`（默认 24000 token ≈ 96KB）。压缩放在**任务开始**而不是终态 finally：那里是正常 suspend 上下文（取消中的任务不需要跑压缩），且新任务的第一次模型请求就能看到压缩结果。 |
| 压缩指令 | `SessionConversation.compressionSystemInstruction()` | 只要求**事实记录**：指令原话、决策、路径/URL/选择器/id/格式/错误串/数字、每个任务如何结束；明确"不要加入转录中没有的目标、优先级、建议或结论"，不规定保留顺序、不判断哪个目标是"活动"的。输出仅记录，约 `browser4.agent.session.memoryTokens`（默认 2000 token）。 |
| 压缩执行 | `RobustBrowserAgent.compressSessionMemory()` | `cta.chatModel.callUmSm(transcript, instruction)`；失败返回 null ⇒ runner 记 warn 并**发送未压缩转录**（丢上下文的代价高于长 prompt）；取消照常向上传播。 |
| 落盘 | `SessionConversationStore`（`agent-session-context.jsonl`） | 任务开始/结束与迟到插话各追加一行快照（`sessionId`/`updatedAt`/`taskCount`/`currentTaskStart`/`compressed`/`turns`），读取取该 session 最后一行；超过 TTL（`contextTtlMinutes`，默认 720min）忽略；文件 >4MB 时 restore 折叠成每 session 一行。**DEFAULT session 不落盘**（`open` 返回 null），避免无关的一次性任务跨重启互相继承。 |
| 折叠边界 | `adoptCompressed()` | 只折 `currentTaskStart` 之前的 turn，**最新任务永远保留原文**；新的压缩输入包含上一次的 `compressed`，因此多轮折叠不丢信息。 |
| 剥离 | `executeSerialized` 开头 | `status.submittedTask = stripResumeBlock(stripSessionBlock(effectiveInstruction))`——两个块都是 runner 追加的，不属于用户提交的文本；不剥离会让 `agent status` 误报任务。 |
| 迟到插话 | `drainUndeliveredSteering`（终态 `status.done()` 之后） | 最后一轮之后再来的 steering 永远送不进模型：drain 后作为"未送达"user turn 写进转录（下一任务仍会遵守），并按 `steering.undelivered` 事件回显（`⚠ steering not delivered — the task ended first: …`）；`enqueueInput` 在锁内二次检查 `isDone`，避免任务结束后残留无人 drain 的队列。 |
| 早期失败 | `executeSerialized` 开头 | `status.submittedTask` 在 run 之前赋值：access-policy/workspace rebind 阶段抛出时任务仍会被记录（原先 `submittedTask` 为 null ⇒ 跳过），`endTask` 也照常写入失败原因。 |

配置：`browser4.agent.session.contextEnabled`（默认 true）、`browser4.agent.session.contextTokens`
（默认 24000，`0`/负值即关闭；含义已从"摘要块预算"变为"转录预算"）、
`browser4.agent.session.memoryTokens`（默认 2000，压缩记忆大小）、
`browser4.agent.session.contextTtlMinutes`（默认 720，`0`/负值 ⇒ 只放在内存）。作用域是
**session**：`-i` 的 `/new` 递增 generation 得到新 sessionId ⇒ 新 runner ⇒ 新上下文，语义正好对齐。
不带 `-s` 的 `-i` 用**本进程自己的** `interactive-<hash>`（CLI 侧 `interactive_session_base()`，由
pid+启动时间派生），不再落到后端 DEFAULT 池——否则两个无关的 `-i` 会互相继承上下文并互相排队。

**提示词中立化 + 外部配置（2026-11-22 二改）**：内置提示只陈述事实/契约，不替模型判断历史含义。
块头只说明"下面是本会话已发生任务的真实对话"，压缩指令只要求事实记录（不规定保留顺序、不判断哪个目标是
"活动"的、不写 SUPERSEDED）。用户可能完全换题，如何理解由模型自行判断。需要引导的部署用外部配置：
`browser4.agent.systemPrompt.extra` / `.file` 追加在内置提示之后（最后，最具权重），
`.override` / `.overrideFile` 替换角色前言（`CliAgentPrompt.role`）；工具、权限、`system.taskComplete`
契约段落由引擎强制保留，覆盖模式也去不掉。配套地，工作记忆写板 (`TaskScratchpad`) 按任务作用域：
`AgentMemory.beginTaskScratchpad(taskId)` 在新任务开始时清空（同一任务的 retry 保留），否则上一任务的
`todo:`/`assumption:` 会作为**每轮最后一条** user 消息进入新任务。回归覆盖：`SessionConversationTest`
（压缩指令/块头只陈述事实）、`StatefulAgentRunnerTest.theTranscriptReachesThePromptAsFacts`
（块进入 `agent.run` 的 prompt 且不含指引）、`CliAgentPromptTest`（外部 override/append/文件/缺失文件）、
`AgentMemoryTwoRunIntegrationTest.testScratchpadIsScopedToOneTask`。

### 9.1.1 连续会话载体（2026-11-21，对照 pi）

转录仍是"重启后的续接"，但**进程内**改为 pi 的连续会话语义（`browser4.agent.session.continuousMessages`，默认 true）：

| 环节 | 位置 | 说明 |
|---|---|---|
| 注入 | `RobustBrowserAgent.doRunCliAgentLoop` | `sessionMessages`（runner 从 `SessionConversation.messageHistory()` 取）插在 system 与本次任务 user 之间；system 每次重建，**不继承**旧 system。有消息时 runner 不再拼 `## Session context` 文本块（`StatefulAgentRunner.sessionBlockFor` 提前返回 ""）。 |
| 轮次续跑 | 同上 + `AgentToolCallLoop.finalMessages` | `browser4.agent.toolLoop.carryRoundMessages`（默认 true）：外层下一轮直接接上本轮 `finalMessages`（assistant tool-call + 全部 tool result），只丢弃本轮装饰消息（scratchpad、coding nudge）。不再需要 nudge+digest；`--follow` 的 digest 事件不受影响。 |
| 回收 | 同上的 `finally` | `capturedMessages = messages.drop(1)`（去 system）；runner 在 per-task 清理处 `takeCapturedMessages()` 交给 `SessionConversation.adoptConversationMessages`，然后清空 `sessionMessages`。失败/取消的运行也会被继承。 |
| 压缩 | `ToolLoopCompressor`（复用） | 会话列表里现在有真实 tool 轮次 ⇒ `findRounds` 能识别、按轮边界折叠；阈值走 `windowTokens - reserveTokens`，决策优先 provider 实测 usage。**不再**为跨任务单独调用自由格式压缩指令。 |
| 重启 | 落盘转录 | `conversationMessages` 只在内存；后端重启后为空 ⇒ 回退到 9.1 的转录（压缩记忆 + turns），语义上"重启丢消息细节、不丢任务脉络"。 |

**分支/回溯（同日）**：`SessionConversation.beginTask(instruction, taskId)` 在任务开始时把**当时的会话消息列表**存为检查点（`rewindStartOf(taskId)`，最多 `MAX_REWIND_CHECKPOINTS=8` 条，最旧先淘汰；仅内存）。`command.run(rewindTaskId=…)`（CLI：`agent run --rewind <id>` / `-i` `/rewind <id>`）在 `executeSerialized` 开头装回该检查点，并先用 `branchSummaryInstruction()` + `compressSessionMemory` 把**被放弃的分支**（当前会话去掉检查点前缀）折成一条 branch summary 附在目标处，然后正常跑新任务；`replaceConversationMessages` 允许"空检查点"（回到第一个任务前），并用 `rewoundConversation` 阻止转录回退。与 `--resume` 互斥（后者是从某任务工作区重开新 run）；检查点随进程消失，重启后 rewind 报明确错误。

**`followUp` 队列（同日）**：`POST /api/commands/{id}/input` 带 `deliverAs`（`steer` 默认 / `followUp`）。follow-up 不进 steering 队列，而是在 `AgentToolCallLoop` 的停止点（模型返回无 tool call、即将 return 时）drain，作为真实 user 消息追加后 `continue` 同一轮（事件 `follow_up`），因此不打断当前步骤、也不变成第二个任务；任务结束后才到的 follow-up 发 `follow_up.undelivered` 并写入会话转录。CLI 入口 `/followup <text>`（无运行任务时按普通任务提交）。

已知边界：连续消息列表没有单独落盘（重启回退），`/new` 仍是新 session ⇒ 新 runner ⇒ 空列表；关闭两个开关即可回到 2026-11-21 之前的"纯转录 + digest"行为。真实浏览器 e2e（成本、完成门禁、页面快照体积）尚未在本轮执行，发布前需补跑一次多轮 `-i` 任务。

### 9.2 下一步预测（`nextSuggestions` → 编号菜单）

旧行为是三个内部指令（`/result --json`、`/stats`、`/new`）。现在换成**用户可能想做的后续任务**：

| 环节 | 位置 | 说明 |
|---|---|---|
| 模型产出 | `system.taskComplete(nextSuggestions=[...])` | tool spec + `TaskCompletion` + `ActionDescription` 携带；提示词要求"最多 3 条、写成指令、不是状态描述"。 |
| 透传 | `CommandResult.nextSuggestions` | `AgentTaskStatus.toCommandStatus()` 从 `agentState.nextSuggestions` 填充，`command_result` 随之返回。 |
| 消费 | `cli/browser4-cli/src/agent_interactive.rs` | `next_actions()`：① 可续跑任务先给 `continue`（`/resume --budget 2×`）；② 模型自己的 `nextSuggestions` 作为**任务文本**逐条入选；③ 再按 payload 推导：未解决 `problems` → 修复；coding 且改过文件 → review / run tests / commit；浏览任务 → 深挖 finding / 存档；失败 → 定位原因 / 重试。去重按任务文本，最多 3 条。 |
| 展示 | `format_next_actions()` | 只显示意图（`1) run the browser4-rest tests`），**不显示命令**；选中后走 `start_task`，与手打任务同一条路径。 |

`-i` 自然排版时不重复打印 `Suggested next steps`（菜单即同一份列表，`with_suggestions(false)`）；
一次性路径（`agent result` / `--wait` / `--follow`）把它作为末尾小节渲染。

### 9.3 `/result` 的两个改进

- `--json`：`agent_result::pretty_json()` 缩进输出（仍是合法 JSON），不再是一条 5KB 单行；
- `--full`：自然排版下不再折叠（去掉 40 行 fold 与各 section 的 10/10/5/5/20 条上限）。

