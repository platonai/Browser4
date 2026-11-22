# agent goal 模式：钉住目标，长跑不漂移

> 状态：已实现（2026-11-22）。关联：[#652](https://github.com/platonai/Browser4/issues/652)（监督面）、[#653](https://github.com/platonai/Browser4/issues/653)（P2：`b4.run` span / 结构化目标检查点）。
> 代码入口：`browser4-agentic/.../agents/AgentGoal.kt`、`GoalToolExecutor.kt`、`RobustBrowserAgent.cliAgent`；`browser4-agent-tools/.../StatefulAgentRunner`；`browser4-rest/.../CommandController`；`cli/browser4-cli/src/agent_interactive.rs`（`/goal`）。

## 1. 问题：长跑任务的目标漂移

`agent run` 的 oneshot 任务可能跑几十分钟、几十轮、上百次工具调用。这段时间里，模型看到的上下文一直在变：

- **压缩**：超过阈值后 `ToolLoopCompressor` 会把历史重写成固定小节的检查点，最早那条用户指令（也就是任务）**大概率不在里面**——它被"摘要"了；
- **重复摘要**：压缩会发生多次，每次都在上一次的检查点上再压；
- **修复轮次**：`coding.verify` 失败 → `RepairLoop` 塞进新的诊断；`filesChanged` 漏报 → `FinishReportRepair` 塞进新的告警；
- **工作记忆**：`memory.note` 写的临时结论、`coding.nudge`、预算告警都挤在尾部；
- **模型自己**：它会把"我现在在做的事"逐渐当成"这个任务"，尤其当原目标难以达成时——换一个更容易的目标并把旧的忘掉，是语言模型最省力的失败方式。

现在的完成闸门（`FinishReportGate`）检查的是**报告与证据是否一致**（有没有真的调用工具、报告的文件是不是真的改了、引用的 verdict 是不是真的跑过）。它不检查**做的事是不是当初要做的**。一个跑偏的 run 只要报告诚实地描述跑偏后做的事，就能通过。

所以缺的是三件事：目标**始终可见**、目标**只有操作者能改**、完成时**对照当初承诺的计划**。

## 2. 设计原则

1. **目标属于操作者，不属于模型。** 任务的目标就是提交任务时那句话，逐字保存。模型没有任何 API 能改它。
2. **目标是引擎状态，不是对话内容。** 它不进入消息历史，因此**压缩永远碰不到它**——每轮请求都由引擎重新拼一段"钉住的目标"在尾部。这是"跑多久都不漂移"的真正机制。
3. **模型可以声明计划，并只能申报进度。** 计划与目标分离：目标不可变，计划可演进，但每条计划的结局必须是 `done` 或"带理由的 `dropped`"。
4. **完成时结构校验，不做散文匹配。** 闸门只比结构化事实：计划里还有 `pending` 步骤 → 拒绝（先给修复轮），`dropped` 有理由 → 接受。
5. **非交互模式没有改目标的通道，因此目标不可变。** 交互模式里唯一的通道是操作者的 `/goal`，每次改动都留痕（`goal.changed`，`source=operator`）。

## 3. 模型

```kotlin
enum class GoalStepStatus { PENDING, DONE, DROPPED }

data class GoalStep(text, status, reason)          // reason 只对 DROPPED 有意义

data class AgentGoal(
    text,                    // 操作者的原话，逐字（≤ MAX_GOAL_CHARS）
    steps, doneWhen,         // 计划；doneWhen 是模型自己声明的验收条目
    source,                  // instruction | operator
    revision,                // 每次改目标 +1；计划变更不计入
    changedFrom, changedReason,
    updatedAt,
)
```

`AgentGoal.fromInstruction(instruction)` 是 oneshot 的默认构造——**不需要任何开关**：每个 `agent run` 任务都是 goal 任务。

`pinnedBlock(round)` 渲染尾部那段：

```
## Goal (pinned by the engine — it does not change)
<原话>

Progress: round 7 · plan 2/5 done, 1 dropped
Plan (update with goal.plan):
  ✔ 1. open the product page
  ▢ 2. extract the price
Done when:
  - the price is in the answer
Rule: every action must serve this goal. If it cannot be met, report it
(problems=[...] in system.taskComplete) — do not substitute an easier goal.
```

边界：目标 ≤ 800 字符，计划 ≤ 12 步，每步 ≤ 160 字符，整块 ≤ 1500 字符。超出的部分截断并说明，绝不因为模型写了 500 步把上下文撑爆。

## 4. 每轮钉住

`cliAgent` 的回合循环在构造本轮请求时（`roundMessages`）按顺序追加：

1. `goal.pinnedBlock(turn)` —— 目标 + 计划进度（引擎状态，每轮重算）；
2. `agentMemory.scratchpad.render()` —— 工作记忆；
3. `coding.nudge` / `goal.reassert` / 预算告警 —— 一次性纠偏。

它们都是**请求级**消息（不进 `messages`），所以：KV 前缀不受影响、压缩不会重写它们、它们永远在尾部（最强注意力位置）。目标块排在最后一段 working memory 之前，因为"要做什么"比"已经知道了什么"重要。

首次钉住时导出 `goal.pinned`（一次），计划变化时导出 `plan.update`。

## 5. 计划工具

```
goal.plan(
  steps: [{ text, status?, reason? }],
  doneWhen: [String]?,
)
```

- 领域 `goal`，方法 `plan`；与 `memory.*` 同一套机制（`CustomToolRegistry` 注册无状态执行器 + `agentToolManager.registerCustomTarget("goal", GoalToolTarget(state))` 绑定每个 agent 的目标状态）。
- 返回紧凑 JSON：`{"steps": 5, "done": 2, "pending": 3, "dropped": 0, "note": "..."}`。计划是引擎状态，模型不需要回读全文（它每轮都在尾部看到）。
- 每次成功更新导出 `plan.update`（`steps`、`done`、`pending`、`dropped`、`revision`），`agent supervise` 与 `/goal` 据此显示进度。
- **没有改变目标的入口**：参数里没有 `goal`。想改方向只有操作者的 `/goal`。模型认为目标无法达成时，正确动作是 `system.taskComplete(problems=[...])` 或把计划步骤标 `dropped` + 理由。

## 6. 长跑重申

每 `browser4.agent.goal.reassertEveryRounds`（默认 6）轮，额外追加一条 `goal.reassert` 消息：

```
⏱ goal.check (round 12): restate the goal in one line, name the plan step you are
working on, and drop any work that does not serve them before your next tool call.
```

同时导出 `nudge`（`kind=goal`）。它不进持久消息，所以只是本轮的额外提醒；作用是让"我现在在做的事"每几轮被强制对回目标一次。

## 7. 完成闸门：计划必须收尾

`FinishEvidence` 增加 `plan`（计划快照），`FinishReportGate` 增加规则 9 的输入：

- 有 `pending` 步骤 → `Verdict.pendingPlan`，违规文案列出前 5 条；
- 全部 `done` / `dropped`（`dropped` 必须有理由）→ 通过；
- 没声明过计划 → 不检查（老行为完全不变）。

处理策略（`FinishReportRepair`）：只由计划落下的违规 → 先给修复轮（把 pending 列表原样回给模型，说明它应该继续做、标 done，或标 dropped 并写理由）；修复轮用尽 → **拒绝**（不是 amend：未完成不是记账问题）。`DROPPED` 无理由 → 与 `pending` 同等对待。

配置：`-Dbrowser4.agent.finish.planCheck=reject|warn`（默认 `reject`）。

## 8. 操作者改目标

| 通道 | 形态 |
|---|---|
| CLI（交互） | `/goal` 看当前目标与计划；`/goal <text>` 改目标 |
| REST | `GET /api/commands/{id}/goal`、`POST /api/commands/{id}/goal` `{text}` |
| MCP | `command.goal(id, text?)` —— 无 text 是读，有 text 是改 |

改动路径：runner 把新目标塞进该任务的**目标队列**（与 steering 同级但类型不同），agent 在下一次回合边界取用：`goal.amend(text, source=operator)` → `revision+1` → 导出 `goal.changed`（含 `previous`、`reason=operator`）→ 并向模型注入一条用户消息"操作者把目标改成了…"。计划保留（它是过程，不是目标），所以模型会看到"目标变了、进度还在"。

**通道的不对称是"模型没路"，不是"任务没有通道"。** 非交互模式（`agent run "<task>"`、`command.run`）里没有操作者在场敲 `/goal`，但持 task id 的外部操作者仍可显式 `POST /api/commands/{id}/goal`（或 `command.goal(id, text)`）改一个正在跑的无人值守任务——#652 要求的"纯 MCP 监督者"正是靠它。真正被保证的是"运行内部无法换靶"：没有任何工具、没有任何提示词入口能让模型改目标。拒绝语义：未知 id、页面访问（无 goal）、任务已结束、空文本都返回 `accepted=false` + `message`（HTTP 200，`command.goal` 同理），因为"任务在你打字时刚好结束"是竞态而不是调用错误；只有 `id` 不存在时 REST 的读端点才是 404。

## 9. 监督面

- `AgentTaskStatus.goal` → `CommandStatus.goal`：`{text, source, revision, changedFrom, steps[], doneWhen[], pending, dropped, round, updatedAt}`。随状态行持久化，重启后仍可读（与 `failureDetail` 同级）。
- 事件：`goal.pinned`、`plan.update`、`goal.changed`（agentic 的 `AgentRunEvent` 与 agent-tools 的 `AgentTaskEvent` 同名，由 `decisionEventTypesAgreeAcrossLayers` 钉住）。
- `agent supervise` 面板多一行 `goal  <text 截断> · plan 2/5`；`/goal` 是它的详情版。
- `--ndjson` 原样携带新事件（不新增字段形态）。

## 10. 配置

| 属性 | 默认 | 作用 |
|---|---|---|
| `browser4.agent.goal.mode` | `pinned` | `off` 关闭钉住与计划闸门（回到 2026-11-22 之前的行为） |
| `browser4.agent.goal.maxChars` | `800` | 目标正文上限 |
| `browser4.agent.goal.maxSteps` | `12` | 计划步骤上限 |
| `browser4.agent.goal.reassertEveryRounds` | `6` | 重申间隔，`0` 关闭 |
| `browser4.agent.finish.planCheck` | `reject` | `warn` 只记日志 |

## 11. 实现记录

| 项 | 位置 |
|---|---|
| 目标/计划模型、渲染、边界 | `browser4-agentic/.../agents/AgentGoal.kt` |
| `goal.plan` 工具与每 agent 的状态绑定 | `browser4-agentic/.../agents/GoalToolExecutor.kt` |
| 每轮钉住、`goal.pinned`/`plan.update`/`goal.changed`/`nudge(goal)`、`goalSource` 取用 | `RobustBrowserAgent.cliAgent` |
| 规则 9（计划完整性）与修复策略 | `FinishReportGate.kt`、`FinishReportRepair.kt` |
| 状态信封与事件 | `browser4-agent-tools/.../Models.kt`、`AgentTaskEvents.kt`、`StatefulAgentRunner.kt` |
| REST/MCP | `CommandController.kt`、`UserCommandExecutor.kt`、`CommandToolExecutor.kt`、`TaskEnvelopes.kt` |
| CLI `/goal` | `cli/browser4-cli/src/agent_interactive.rs`（`show_goal` / `change_goal`）、`agent_goal.rs`（解析与渲染）、`http.rs`、`agent_supervise.rs` |
| 测试 | `AgentGoalTest`、`GoalToolExecutorTest`、`FinishReportGateTest`（计划规则）、`FinishReportRepairTest`（计划修复轮）、`StatefulAgentRunnerTest`（目标进信封/改目标）、`CommandControllerTest`（`/goal` 端点）、CLI 单测（`agent_goal`）与 e2e：`test_e2e_agent_run_interactive_reports_the_goal`、`test_e2e_agent_run_interactive_reports_a_refused_goal_change`、`test_e2e_agent_supervise_reports_progress_and_verdicts`（面板 `goal` 行与 `--json`） |

验证（2026-11-22，同次改动）：`browser4-agentic` 1885 项、`browser4-agent-tools` 313 项、`browser4-rest` 926 项；`cargo test --bin browser4-cli` 2337 项；e2e agent 组 48 个场景。全部通过，仅 `browser4-agentic` 的 `CodingToolExecutorTest.testDevTaskRunTests` / `testDevTaskVerifyTargetsNewPluginModule` 两个既有 Windows 环境失败（Mockito 对 `CodingAgentShell.executeRaw` 的 env 匹配，与本改动无关，改动前基线同样是这 2 项）。

## 12. 残余风险（诚实地写下来）

- **语义漂移判定不了。** 引擎能保证"目标每轮可见 + 只有操作者能改 + 承诺的计划必须收尾"，但不能判定**计划本身是否服务于目标**。一个模型可以声明一个与目标无关、自己又能完成的计划，然后"诚实地完成"它。下一层是 #653 part B 的结构化 `doneWhen` 检查（把验收条目变成可执行的检查而不仅是文字），以及把目标交给另一个模型做语义对账（监督者角色，而不是执行者）。
- **"不可变"只在运行内部成立。** 模型没有任何路径改目标；但无人值守任务并非"没人能改"——持 task id 的操作者可以显式 `POST /goal`（这正是需要中途改方向时的推荐做法，而不是重启任务），也用 `--follow` + `command.input` 干预。所以这里的保证要读成"运行不会自己换靶"，而不是"靶在任务生命周期内绝对不动"。
- **计划是可选的。** 不声明计划的 run 只有钉住 + 闸门的老规则；这保证向后兼容，也意味着"没有计划"的 run 少一层约束。要不要强制（`browser4.agent.goal.requirePlan`）留给后续：默认关闭，因为浏览类任务的计划往往一开始就写不准。
