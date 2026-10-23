# agent run 交互式功能代码评审报告（`--follow` / `--ndjson` / `-i`）

- 日期：2026-10-23
- 分支：`feat/agent-run-interactive`（工作区未提交变更，未暂存）
- 范围：27 个修改文件 + 6 个新增文件，约 1781 行插入
  - 后端事件源：`AgentTaskEvents.kt`、`AgentRunEvent.kt`、`StatefulAgentRunner.kt`、`RobustBrowserAgent.kt`、`AgentToolCallLoop.kt`
  - REST：`CommandController.kt`（`GET /api/commands/{id}/events`、`POST /{id}/input`）、`CommandEvents.kt`、`UserCommandExecutor.kt`、`CommandToolExecutor.kt`、`CommandStatus.kt`
  - CLI：`agent_interactive.rs`（新增，1488 行）、`main.rs`、`http.rs`、`commands.rs`、`help.rs`、`tips.rs`
  - 测试：`AgentTaskEventLogTest`、`AgentTaskEventSanitizerTest`、`StatefulAgentRunnerTest`（新增段）、`AgentToolCallLoopTest`、`CommandControllerTest`、`LlmStatusReporterTest`、CLI e2e（mock server，6 个新场景）
  - 文档：`SKILL.md`、`references/agent.md`、`cli/browser4-cli/README.md`、`docs-dev/copilot/agent-run-flow.md`
- 评审方式：全文通读 diff + 新增文件；编译 + 定向测试验证
- 结论：**设计成立，文档同步到位，新增 CLI 单测与 6 个 mock e2e 场景全部通过；但 CLI 跟随器存在 2 个 P1 缺陷（其中一个会终止整个 `-i` 会话），合并前应修复。**

---

## 一、设计合理性（确认通过）

- **游标轮询而非 SSE**（`GET /api/commands/{id}/events?after=<nextSeq>`）：可自然重连、容忍缓冲代理、客户端无需流解析，降级干净。选型正确。
- **事件日志有界且不持久化**（每任务 1000 条环形缓冲；任务级 Caffeine 缓存 128 条 / 120 分钟；KDoc 说明了为何不写 JSONL）；脱敏在日志边界做而非 agent 侧，层次正确。
- **默认行为不变**：不传 `--follow` / `--ndjson` / `-i` 时，`agent run` 与旧行为逐字节一致（整个 CLI 单测套件通过佐证）。
- **无 events 端点时降级为状态轮询**，且降级提示在 `--ndjson` 下正确静默（`note_fallback`，`agent_interactive.rs:592-600`）——正因如此，下面 P1-2 里不做同样处理的两处属于明确的 bug 而非设计取舍。
- 文档在 `skills/browser4-cli/{SKILL.md,references/agent.md}`、`cli/browser4-cli/README.md`、`help.rs`、`tips.rs`、`docs-dev/copilot/agent-run-flow.md` 全部同步；`SKILL.md` 375 行，低于 500 行上限。

## 二、验证证据

| 检查项 | 结果 |
|---|---|
| `cargo test --bin browser4-cli` | 1899 通过，0 失败，4 忽略 |
| `cargo test --bin browser4-cli agent_interactive` | 18 通过 |
| e2e `--scenario 'test_e2e_agent_run_follow_*'`（mock server） | 4/4 通过 |
| e2e `--scenario 'test_e2e_agent_run_interactive_*'` | 2/2 通过 |
| `mvnw -DskipTests -pl browser4-rest,browser4-agent-tools -am test-compile` | 成功 |
| `AgentToolCallLoopTest` / `AgentTaskEventLogTest` / `AgentTaskEventSanitizerTest` / `StatefulAgentRunnerTest` / `CommandControllerTest` | 25 / 6 / 10 / 21 / 11 全部通过 |
| `LlmStatusReporterTest` | 7/8 通过，1 失败——**环境性，非本次变更引入**（见附录 A） |

> 注意：e2e runner 的 `--scenario` 不叠加，传多个时只有最后一个生效，需分次运行。

---

## 三、P1 —— 合并前必须修复

### P1-1 单个格式异常的 events 页会杀死跟随器，进而杀死整个 `-i` 会话

`cli/browser4-cli/src/agent_interactive.rs:524`：

```rust
let page = parse_events_page(&response.body)?;
```

`parse_events_page` 的 KDoc（`:114-118`）明确承诺："a malformed page is retried (the cursor is unchanged) instead of killing the session"。实现违背了承诺：一次 `200` 但 body 非 JSON（代理错误页、截断 body、网关返回 HTML）就从 `poll` 返回 `Err`；在交互模式下该 `?` 一路穿出 `Repl::run`（`:926`、`:932`），**整个 REPL 退出**。

修复：解析错误走 `tolerate_transient`：

```rust
let page = match parse_events_page(&response.body) {
    Ok(page) => page,
    Err(error) => return self.tolerate_transient(error),
};
```

并补一个跟随器级测试：坏页不中断、游标不动。

### P1-2 `--ndjson` / `--json` 输出纯度被破坏

`Renderer::print`（`agent_interactive.rs:357-362`）只检查 `quiet`，且最终落到 `print_stdout_line`（`main.rs:257-261`）——同时绕过 `json_active()` 与 ndjson 分支。对照 `cli_println!`（`main.rs:263-272`在 `--json` 下静默），三处泄漏：

1. `tolerate_transient`（`:610-613`）在 `--ndjson` 下打印 `… <error> (retrying)` 纯文本行 → 机器流里混入非 JSON 行。
2. `poll_status`（`:570-573`）在 `--ndjson` 下逐条打印状态纯文本——而这恰恰是文档宣称"旧后端降级仍可用"的那条路径。
3. `agent run --follow --json`：步骤行与完成块直接打到 stdout，随后 `json_finish()` 再输出信封 → stdout 不再是合法 JSON。此外 `--follow` 未设置 `--wait` 路径会设置的 `json_field("raw", …)`（`main.rs:14918-14929`）。

修复：`Renderer::print` 同时以 `crate::json_active()` 短路；`ndjson` 下重试/降级提示要么发 JSON 事件、要么静默（复用 `note_fallback` 的写法）。补一个跟随器级测试：`--ndjson` 下所有 stdout 行必须可解析为 JSON。

---

## 四、P2 —— 应当修复

### P2-1 瞬态失败预算对 JVM 重启过短

`agent_interactive.rs:41` `MAX_TRANSIENT_FAILURES = 30`，400ms 轮询间隔下约为 **12 秒**，随后放弃——而 `--follow` 本身无上限、`--wait` 容忍 600 秒。后端重启（JVM + Spring 约 10–30 秒）会中止一次用户显式要求的"盯着看"。建议改时间窗（如 `MAX_TRANSIENT_WINDOW = 5 min`）或复用 `wait_timeout`。

### P2-2 events/status 端点对缺省 session 产生"创建 DEFAULT 会话"副作用

`CommandController.getEvents`（`CommandController.kt:280-303`）在 CLI 不传 `sessionId` 时解析为 `DEFAULT`；随后 `UserCommandExecutor.getStatus`（`UserCommandExecutor.kt:402-413`）在 `probeAllRunners` **之前**调用 `ensureAgentRunner(sessionId)`，而它会 `sessionManager.getOrCreateSession(...)`。因此 `agent run -s work --follow` 会让后端凭空创建一个 DEFAULT 会话（含 runner 及其压缩任务）——正是 `UserCommandExecutor.kt:415-420` 注释警告的模式。修复：先探测已存在的 runner，最后才 `ensureAgentRunner`；或让 CLI 的 follow/status/result 调用透传 `sessionId`（controller 已接受该参数）。

### P2-3 `done` 可能永远为 false

`CommandController.kt:296` 用 `status?.isDone ?: false` 构造 `done`。若状态行已被淘汰/压缩而事件仍在，无默认上限的跟随器将无限轮询。建议：_status 未知时以最后一条终态事件（`task.done`/`task.failed`/`task.cancelled`）推导 `done`，或直接 404。

### P2-4 一次性 `--follow` 不维护本地任务跟踪

`print_completion`（`agent_interactive.rs:689-718`）从不调用 `update_async_task_status`，而 `--wait`（`main.rs:14918-14929`、`:14941-14945`）与 REPL 的 `complete_current` 都会。`agent list` 会从后端对账，可见影响有限，但任何读取本地状态的逻辑都会看到任务仍在 processing，属于潜伏不一致。应对齐 wait 路径。

### P2-5 提交失败会终止整个 REPL

`handle_line`（`agent_interactive.rs:971`）`self.start_task(line).await?`：缺 LLM key、后端抖动等一次性错误会把交互会话整个带崩，而不是打印错误后重新出提示符。`poll_current().await?`（`:932`，并与 P1-1 叠加）同理。REPL 中这两类错误应打印并保持会话；一次性模式下才应上抛。

### P2-6 404 语义有歧义

`is_missing_endpoint()`（`:511`）把任何 404/405/501 判为"旧后端"，但 controller 对未知/已过期任务 id 同样返回 404。错误 task id 会得到具有误导性的"this backend has no events endpoint — falling back to status polling"，然后无限轮询。建议 controller 的 404 带可区分 body（如 `{"error":"unknown task"}`），CLI 检查 body/content-type 再判定。

---

## 五、P3 —— 小问题与后续项

1. **脱敏器健壮性**（`AgentTaskEvents.kt:296-313`）：`redactNode` 在迭代 `fields()` 的同时 `put(name, REDACTED)`。当前 Jackson（对已有键做值替换，非结构变更）安全，但脆弱；建议先收集键名再改。`isSensitiveTextInput`（`:275-280`）用原始键名对照 `TEXT_INPUT_KEYS` 未做规范化，且 `SENSITIVE_KEYS` 不覆盖点分键（`user.password`）。当前工具入参为扁平 snake_case，风险低。
2. **steering 队列竞态**：`enqueueInput`（`computeIfAbsent(...).add`）与 `drainInputs`（`poll` 后 `remove(id, queue)`）竞态时，最后一条之后 Add 会被连同队列一起丢弃（丢一条 steer，无泄漏）。先 `remove` 再 drain，或按队列同步。
3. **`/events [n]` 未实现**：帮助文本宣称（`:1119`），分发处忽略（`:1100`）。`print_events` 固定 `after=0&limit=200` 打印最后 20 条；事件超过 200 时展示的是**最旧一页的尾部**而非最新步骤。
4. **静默的旗标组合**：`-i` 忽略 `--ndjson`（交互分支在计算 ndjson 前即返回）；`--ndjson` 不带 `--follow` 也被静默忽略。应告警或在文档标注。另外 steer 后任务未结束时不会重新打印提示符（`prompt_pending = self.current.is_none()`），事件行之间用户是"盲打"。
5. **`LlmStatusReporterTest.assertDetectedViaIsHonest`** 在 `detectedVia == test_file_backed` 时直接 early-return，导致任何跑过 e2e 的机器上诚实性断言被整体跳过。建议保留断言，仅跳过 provider 相关检查。
6. **文档缺口**：`README.md:519`、`README.zh.md:526` 的 `agent run` 行仍只描述 `--wait`/`--wait-timeout`，未含 `--follow`/`--ndjson`/`-i`（AGENTS.md 文档规则要求根 README 同步）。其余位置均已更新。
7. **文档小瑕疵**：`references/agent.md` 示例 `#2 browser_snapshot()`；渲染器对空参数省略 `()`（`format_tool_arguments`），示例与实际输出不符。
8. **覆盖率缺口**：runner 测试使用 mock agent，`recordRunEvent`（脱敏的实际接线）与 `RobustBrowserAgent` 的 `runEventListener`/`steeringSource` 接线无任何测试覆盖；仅 `AgentToolCallLoop.onBeforeIteration` 被测。建议补"假 agent 经 listener 发事件"的 runner 测试，或聚焦 `RobustBrowserAgentEvent` 发射的测试。
9. **CLI 跟随器无输出模式测试**——一个紧跟器级测试即可同时抓住 P1-1 与 P1-2。

---

## 六、建议修复顺序

1. P1-1（解析错误 → `tolerate_transient`）与 P1-2（`Renderer::print` 双门控 + ndjson 提示），都是小改动，配一个跟随器输出模式测试。
2. P2-1（时间窗预算）、P2-2（session 副作用）、P2-3（终态推导）、P2-5（REPL 韧性）、P2-6（404 消歧）。
3. 其余作为后续项；README 两处为单行修改。

---

## 七、修复记录（2026-10-23 23:15，`feat/agent-run-interactive`）

P1、P2 全部修复，P3 只保留"steer 后不重打提示符"这一条 UX 取舍（见文末）。

| 编号 | 处理 | 位置 |
|---|---|---|
| P1-1 | 事件页解析失败改走 `tolerate_transient`：游标不动、不中断；REPL 轮询错误只打印并释放任务，不再带崩会话。新增"坏页 → 好页"跟随器单测（本地 TCP stub）。 | `agent_interactive.rs` |
| P1-2 | `Renderer::print` 同时受 `--quiet` 与全局 `--json` 门控（与 `cli_println!` 对齐）；NDJSON 下重试/降级/状态进度行全部静默（新增 `notice_enabled` 门控）；`--follow --json` 补 `json_field("raw", …)`。新增 e2e：fallback 路径下 `--ndjson` 每行仍是 JSON。 | `agent_interactive.rs`、`main.rs`、`main.rs:json` |
| P2-1 | 失败预算由"30 次"改为 5 分钟时间窗（`TRANSIENT_FAILURE_WINDOW`），一次成功响应即复位，能扛 JVM 重启。 | `agent_interactive.rs` |
| P2-2 | `getStatus` 的 agent 分支不再调用 `ensureAgentRunner`：先探已有 runner → page fallback → `statusFromDisk`。跟随 `-s work` 任务不再凭空创建 DEFAULT 会话（及其压缩任务）。 | `UserCommandExecutor.kt` |
| P2-3 | 状态行缺失时由最后一条终态事件（`task.done/failed/cancelled`）推导 `done` 与 status word，避免无上限跟随器永不结束。 | `CommandController.kt` |
| P2-4 | `--follow` 的完成/取消/超时都与 `--wait` 一致地写 `update_async_task_status`。 | `agent_interactive.rs` |
| P2-5 | 提交失败、轮询失败都只打印并保留会话（包括 `-i` 的初始任务）。 | `agent_interactive.rs` |
| P2-6 | 404 不再一律当"旧后端"：先探测状态，`statusCode==404` 或消息含 "No task found" → 明确报"任务不存在"并终止，且**不**打印降级提示。 | `agent_interactive.rs` |
| P3-1 | 脱敏器先收集键名再改写（不再边迭代边 `put`）；`isSensitiveKey` 取点分键最后一段；文本输入键名走规范化比较。 | `AgentTaskEvents.kt` |
| P3-2 | `enqueueInput` / `drainInputs` 共用 `inputLock`，消除"入队落在已摘除队列"的丢消息竞态。 | `StatefulAgentRunner.kt` |
| P3-3 | `/events [n]` 真正实现：按当前任务游标取**最新** n 条，而不是最旧一页；非法参数给用法提示。 | `agent_interactive.rs` |
| P3-4 | `-i --ndjson`、`--ndjson` 不带 `--follow` 会在 stderr 明说被忽略。 | `main.rs` |
| P3-5 | `assertDetectedViaIsHonest` 在 test LLM 下仍断言 `configured == true`，不再整体跳过。 | `LlmStatusReporterTest` |
| P3-6 | 根 `README.md` / `README.zh.md` 的 `agent run` 行补齐 `--follow` / `--ndjson` / `-i`。 | README |
| P3-7 | 示例 `#2 browser_snapshot()` → `#2 browser_snapshot`（渲染器对空参数省略括号）。 | `references/agent.md` |
| P3-8 | 新增 runner 级测试 `runEventsAreSanitized`：经 `recordRunEvent` 的脱敏接线（密码 selector 的输入不进事件日志）。 | `StatefulAgentRunnerTest` |
| P3-9 | 新增测试：坏页重试不中断、输出门控谓词、404 判定、NDJSON fallback 纯度（e2e）。 | 单测 + e2e |

**验证（修复后）**

| 检查 | 结果 |
|---|---|
| `cargo test --bin browser4-cli` | 1902 passed / 0 failed（新增 3 个跟随器测试） |
| `--scenario 'test_e2e_agent_run*'`（mock） | 14/14，含新增 `..._ndjson_stays_json_in_fallback` |
| Kotlin 定向（agent-tools / rest） | agent-tools 38、rest 20（controller 12 + LLM status 8）全绿 |
| 真后端（`--force-rebuild-bundle`，file-backed LLM） | `agent run … --follow` ok，4.2 s，事件流端到端通过 |

未改的一条：steer 后任务仍在运行时不再重打 `agent> ` 提示符（避免与事件流争行），
用户可继续盲打或 `/cancel`；这是有意的终端取舍，帮助文本已说明"跑着时继续输入 = steering"。

## 附录 A：`LlmStatusReporterTest.reportShouldSayNotConfigured` 失败归因

现象：`expected: <false> but was: <true>`（`LlmStatusReporterTest.kt:194`），8 个用例仅此失败。

归因：**本机环境导出了 `DEEPSEEK_API_KEY`**。用 Java 单文件探针（`ChatModelFactory.INSTANCE.isModelConfigured(conf, verbose=false)`，配置 `llm.provider.deny.list=LLM_API_KEY,OPENROUTER_API_KEY,VOLCENGINE_API_KEY,OPENAI_API_KEY` 且 `OPENAI_API_KEY=""`）实测返回 `true`——denial list 影响的是"选键"，不影响 `isModelConfigured`。变更前的公式 `configured = factorySaysConfigured || usable.isNotEmpty()` 同样会得到 `true`，所以该测试在本机无论是否应用本 diff 都会失败，属于预先存在的环境性失败，与本次变更无关。可通过 `assumeFalse`/denial-aware 配置让测试环境无关。

## 附录 B：评审过程产物（已清理）

评审期间在仓库根目录产生的 `mvn-review-tests.log`、`cp-rest.txt` 与临时目录的探针 `Probe.java` 均已删除；`git status` 中没有评审引入的文件。

评审期间观察到的小事项（不阻塞）：
- `StatefulAgentRunner.kt` 导入顺序：`AgentRunEvent` 排在 `RobustBrowserAgent` 之后（字典序小瑕疵）。
- `enqueueInput` 对 `created`/`queued` 任务接受输入：CLI 会显示 "steering queued"，行为可接受但值得在 help 中标注。
- `recordLifecycle(create)` 为每个任务都建 1000 容量的日志对象（约 2KB/任务，Caffeine 上限 128），开销可忽略。
- `AgentRunEvent`（agentic）与 `AgentTaskEvent.TYPE_*`（agent-tools）的常量字符串跨模块重复（依赖方向不允许反向引用，属可接受的代价）；CLI 渲染器对未知 type 静默跳过，不会崩。
- `AgentToolCallLoop.onBeforeIteration` 在压缩之前注入 steering 消息：新注入的是最新消息，压缩裁剪不会误删首个轮次注入，行为正确。
