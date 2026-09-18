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
  自动拉起/复用后端（Spring Boot，默认 `http://localhost:8182`），无需用户先 `open`。
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
     （`src/main.rs:10880-10904`）；
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
2. `CodingTaskDetector.detect()` → 纯编码任务进入 `codingMode`（跳过 driver 健康检查、
   搜索导航与截图）；否则 `ensureSessionDriverHealthy()` 检测 bound driver，不健康则
   unbind + close 并让下次 `getOrCreateBoundDriver` 重新拉起浏览器；
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
- `command_result` → `getResult` → `commandResult` JSON；CLI 打印结果 / `agent status`
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
