# 下线 observe-act 引擎：审查项修复记录（2026-11-12）

> 依据：`20261112-observe-act-removal-review.md`（P0 3 项 / P1 4 项 / P2 3 项）
> 基线：`96a34bc773`（`revert(agent): drop @Deprecated from the PerceptiveAgent.run chain`）
> 范围约束：不提交（`git add` / `commit` / `push` 均未执行），全部改动留在工作区。

## 0. 结论

审查文档里的 10 项全部落地，`observe-act` 引擎（`PerceptiveAgent.observe/act/run(ActionOptions)`、
`ObserveOptions`、`ObserveResult`、observe 提示词链路、observe 事件常量）已从主源码、测试源码、
`examples` 与 code-mirror 快照中彻底移除。唯一保留的引擎是 CLI tool loop（`RobustBrowserAgent`）。

## 1. 迁移说明（破坏性公共 API 变更）

| 已删除 | 替代 |
|---|---|
| `PerceptiveAgent.observe(instruction)` / `observe(ObserveOptions)` | `command.run`（`browser4-cli agent run`，MCP `command_run`） |
| `PerceptiveAgent.act(action: String)` / `act(ActionOptions)` / `act(ObserveResult)` | 同上（CLI tool loop 内部按轮执行工具） |
| `PerceptiveAgent.run(action: ActionOptions)` | `PerceptiveAgent.run(task: String)`（经由 `command.run`） |
| `ObserveOptions`、`ObserveResult`、`BasicBrowserAgent.ObserveActResult` | 无（随引擎一起删除） |
| `AgentFlowEventHandlers.onWillObserve` / `onDidObserve` | `onWillAct` / `onDidAct` |
| `AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE/ON_DID_OBSERVE`、`AgenticEvents.InferenceEngine.ON_WILL_OBSERVE/ON_DID_OBSERVE` | `ON_WILL_ACT` / `ON_DID_ACT`、`ON_WILL_EXTRACT` / `ON_DID_EXTRACT` |
| `ObserveParams`（`fromAct` / `returnAction`）、`ExecutionContext.createObserveParams/createObserveActParams`、`InferencePromptBuilder.buildObserveMessages` | 无（observe 提示词链路整体删除） |

`agent` 域对外可用的 MCP 工具现在只有 `agent.summarize` 与 `agent.extract`（两个 `extract` 重载）。

`command.run` 的 `engine` 参数保留为兼容参数：只接受 `cli` / `cli-tool-loop`，
`observe-act` / `observe_act` / `v1` / `legacy` 会被显式拒绝（`CommandToolExecutor`）。
`application.properties` 的注释已按此改写（审查 §2.4）。

## 2. 逐项处置

### P0-1.1 主源码编译失败（ObserveOptions 残留）

- `inference/AgentStateManager.kt`：删 `ObserveOptions` import、observe 重载 `getOrCreateActiveContext`、
  `buildInitExecutionContext(options: ObserveOptions, …)`；`getActiveContext()` 报错文案
  `call act(action: ActionOptions) first!` → `call run(action: ActionOptions) first!`。
- `model/ExecutionContext.kt`：删 import、`createObserveParams(...)`、`createObserveActParams(...)`。
- 结果：`mvnw.cmd -o -pl browser4-agentic clean compile` 通过（见 §4）。

### P0-1.2 测试源码编译失败

- `AgentEventBusTest`：链式处理用例改用 `onWillAct` + `ActionOptions(action = "test")`；
  通用事件用例的 `onWillObserve` 字面量改为 `onWillAct`。
- `EventBusObservabilityTest`：删除 `testPerceptiveAgentObserveEvents`；原
  `testInferenceEngineObserveEvents`（实际测的是 `ContextToAction` 事件）重命名为
  `testContextToActionGenerateEvents`。
- `EventBusObservabilityExample`：事件计数/日志/指标示例改注册 `onWillRun` / `onWillAct` / `onWillExtract`，
  `MetricsCollector` 的 observe 计时改为 run 计时。
- `SkillInstallE2ETest` / `AgentE2ETest`：删 `onWillObserve` handler、observe 事件注册与
  `observeCount` 计数，日志改为只报 step/act 计数。
- `TaskEnvelopesTest`：`onWillObserve` 断言改为 `onWillAct`。

### P0-1.3 examples 模块编译失败

| 文件 | 处置 |
|---|---|
| `examples/.../fuse/Workflow.kt` | `agent.act(...)` → `agent.run(...)` |
| `examples/.../fuse/FusedActs.kt` | 每个 `agent.act(...)` → `agent.run(...)`，打印 `history.finalResult` |
| `examples/.../fuse/SummarizePage.kt` | `agent.act("Extract the full text…")` → `agent.run(...)`，摘要文本取自 `history.finalResult?.toolCallResult` |
| `examples/.../agent/Scroll.kt` | 手写 `act` 循环 → 单次 `agent.run(action)` |

`examples/.../llm/TalkToLivePage.kt` 用的是 `session.act(...)`（`SessionActExecutor` / `TextToAction`
这条独立的文本→动作链路），不属于 `PerceptiveAgent.act`，编译通过，未改动。

### P1-2.1 `BasicBrowserAgent.run(action)` 的 `TODO()` 地雷

按审查首选方案处理：`BasicBrowserAgent` 改为 `abstract`，删除 `run(task)` / `run(action)` 实现，
只留 `abstract override suspend fun run(task: String)`；`RobustBrowserAgent.run(action)` 去掉 `override`
改为 `private`（唯一实现、唯一调用者都在该类内）。于是"基类引用调用命中 TODO"从运行期地雷变成编译期错误。

### P1-2.2 重新生成 code-mirror 快照

```bash
mvn -o -pl browser4-agentic -am test -Dtest=ToolSpecSnapshotRegenerator -DregenerateSpecSnapshots=true \
    -Dsurefire.failIfNoSpecifiedTests=false
```

- `code-mirror/agent-tool-call-specs.json`：5 条 → **3 条**（`summarize`、`extract`、`extract`），
  `observe` / `act` 条目删除。
- `code-mirror/PerceptiveAgent.kt.txt`：与当前源码镜像一致（`ObserveOptions` / `ObserveResult` /
  `observe` / `act` 全部消失）。
- `code-mirror/driver-tool-call-specs.json` 无变化（118 条，未漂移）。

> 坑：`-pl browser4-agentic`（不带 `-am`）时 `browser4-common` / `browser4-resources` 来自本地仓库 JAR，
> `B4ProjectUtils.isInJar()` 为真 → 源文件镜像不会刷新，`readSourceFileFromResource` 读到旧快照，
> 重新生成会"成功"但内容不变（本轮实测）。必须带 `-am` 走 reactor classpath，或先手工同步
> `code-mirror/PerceptiveAgent.kt.txt` 再生成。

新增对称测试 `SourceCodeToToolCallTest.perceptiveAgentSpecsFromMirroredSourceMatchBundledJson`
（镜像源码生成的 specs 必须逐字节等于 bundled JSON，与 driver 侧
`webDriverSpecsFromMirroredSourceMatchBundledJson` 对称）——以后丢快照 CI 能抓到。

### P1-2.3 死代码与过期文案

| 位置 | 处置 |
|---|---|
| `RobustBrowserAgent.handleObserveException` | 删 |
| `BasicBrowserAgent.snapshotService`、`InferenceEngine.snapshotService`（无使用者，且会触发惰性启动浏览器） | 删 |
| `DetailedActResult.toActResult()`（审查写作 `ActionDescription.toActResult()`）、`ActionDescription.toObserveResults()` | 删 |
| `BasicBrowserAgent` 未用 import（`ActResultHelper`、`ToolSpecification`、`withTimeout`、`TimeoutCancellationException`、`MessageFormat`、`milliseconds`、`AgentMessageList`、`Pson`、`printlnPro`） | 删 |
| `InferenceEngine` 未用 import（`ActionDescription`、`ExecutionContext`、`AbstractWebDriver`、`SnapshotService`）、`session` 字段 | 删 |
| observe 提示词链路 | `PromptBuilder.buildMultistepAgentMessageListAll` / `buildSingleObserveMessageListAll` / `buildSingleObserveSystemPrompt` / `buildObserveActToolUsePrompt` / `OBSERVE_GUIDE_*` / `initObserveUserInstruction` / `buildBrowserStateMessageForBrowserInteraction`、`InferencePromptBuilder.buildObserveMessages`、`ObserveParams` 全部删除 |
| `ToolSpecification.kt` 的 `agent.observe(...)` 示例行 | 删 |
| `AgentEventBus.kt` KDoc 示例 `agent.observe(options)` / `onWillObserve` | 改为 `agent.run(task)` / `onWillAct` |
| `AgenticEvents.kt` 的 observe 事件常量（`PerceptiveAgent` + `InferenceEngine`，均已无发射方）+ `getAllEventTypes()` 条目 | 删 |
| `TaskEnvelopes.kt` 的 `onWillObserve` / `onDidObserve` 进度文案 | 删 |
| `AgentStateManager.getActiveContext()` 报错文案 | 改为 `run(action: ActionOptions)` |
| `PerceptiveAgent.ActionOptions` KDoc（`each act() call …`） | 改为 chained step |
| `AgentEventHandlers` / `DefaultAgentEventHandlers` KDoc（observe and act） | 改为 act |

保留但已成为"仅测试可达"的边界（本轮刻意不动，留待单独清理）：

- `PromptBuilder.buildMultistepMessageListStart` / `buildOperatorSystemPrompt` / `buildUserRequestMessage` /
  `buildBrowserVisionInfo` / `buildPrevToolCallResultMessage`：审查只点名 `buildMultistepAgentMessageListAll`；
  继续删除会连带 `MainSystemPrompt.kt`（`buildMainSystemPromptV1`）及其测试，属于下一次清理的范围。
  `PromptBuilderBlankMessageTest` 已改为覆盖 `buildMultistepMessageListStart`（P0.1 空白消息回归仍被守住）。
  → **已在 2026-11-12 清理**：`MainSystemPrompt.kt` 整文件、`PromptBuilder` 的 companion object 与
  `buildOperatorSystemPrompt` / `buildMultistepMessageListStart` 连同 3 个测试类已删除，见
  `20261112-legacy-system-prompt-removal.md`（`buildUserRequestMessage` / `buildBrowserVisionInfo` /
  `buildPrevToolCallResultMessage` 成为孤儿，仍留在代码里待定）。
- `AgentStateManager.getOrCreateActiveContext(action: ActionOptions, …)`：`act` 删除后无生产调用方，
  但审查明确只删 observe 重载，保留。
- `SessionActExecutor` / `TextToAction`（`session.act(...)` 的文本→动作链路）本身已标 `@Deprecated`，
  与 `PerceptiveAgent.act` 不是同一条 API，未纳入本轮。

### P1-2.4 `application.properties` 注释

保留该注释并改为准确措辞：`browser4.agent.runEngine` 已不存在；`engine` 参数是兼容参数，只接受
`cli`(`cli-tool-loop`)，已移除的 `observe-act` 取值会被显式拒绝。

### P2-3.1 格式化噪声

`git checkout` 还原 `RobustBrowserAgent.kt` 后只做语义删除：该文件现在 `85 deletions`、无重排
（审查时为 192 行 diff / `-w` 122 行）。`MemoryRecallService.kt` 的空行改动一并还原（文件回到 HEAD）。

### P2-3.2 无关清理

`Models.kt` 的通配符 import、`cliOptions` 拆行均已还原；本轮 `Models.kt` 只删死代码（2 个方法 + 1 个 import）。

### P2-3.3 发布说明

即本文 §1 的迁移表。`docs/`、`skills/`、`README*` 经确认没有引用这些 API，只有
`skills/browser4-cli/references/config.md` 里「legacy observe-act loop 与 CLI tool loop 两个引擎」
的过期表述改成了「只有 CLI tool loop 一个引擎」。

## 3. 改动清单

```
 application.properties                                     |   5 +-
 browser4-core/.../code-mirror/agent-tool-call-specs.json   |  30 -
 browser4-core/.../code-mirror/PerceptiveAgent.kt.txt       | 130 +-
 browser4-agentic/.../PerceptiveAgent.kt                    | 130 +-
 browser4-agentic/.../agents/BasicBrowserAgent.kt           | 391 +--------
 browser4-agentic/.../agents/RobustBrowserAgent.kt          |  85 +-
 browser4-agentic/.../event/AgentEventBus.kt                |   8 +-
 browser4-agentic/.../event/AgentEventHandlers.kt           |  33 +-
 browser4-agentic/.../event/AgenticEvents.kt                |  29 -
 browser4-agentic/.../event/detail/DefaultAgentEventHandlers.kt | 6 +-
 browser4-agentic/.../inference/AgentStateManager.kt        |  33 +-
 browser4-agentic/.../inference/InferenceEngine.kt          |  63 -
 browser4-agentic/.../inference/InferenceParams.kt          |  20 -
 browser4-agentic/.../inference/InferencePromptBuilder.kt   |  10 -
 browser4-agentic/.../inference/PromptBuilder.kt            | 258 +--
 browser4-agentic/.../model/ExecutionContext.kt             |  45 +-
 browser4-agentic/.../model/Models.kt                       |  35 -
 browser4-agentic/.../tools/TaskEnvelopes.kt                |   2 -
 browser4-agentic/.../tools/specs/ToolSpecification.kt      |   1 -
 browser4-agentic/src/test/...（6 个测试）
 browser4-tests/.../AgentE2ETest.kt                         |  39 +-
 browser4-tests/.../SkillInstallE2ETest.kt                  |  39 +-
 examples/...（4 个示例）
 skills/browser4-cli/references/config.md                   |   2 +-
 32 files changed, 138 insertions(+), 1438 deletions(-)
```

## 4. 验证

| 命令 | 结果 |
|---|---|
| `mvnw.cmd -o -pl browser4-agentic clean compile`（审查 §4 第 1 步） | BUILD SUCCESS |
| `mvnw.cmd -o -pl browser4-agentic -am -DskipTests test-compile` | BUILD SUCCESS |
| `mvnw.cmd -o -pl browser4-agentic test -Dtest='ToolSpec*Test,AgentEventBusTest,AgentStateManagerTest'`（审查 §4 第 2 步，带 `-am`） | 62 tests / 0 failures |
| `… -Dtest='SourceCodeToToolCallTest,AgentEventBusTest,AgentStateManagerTest,PromptBuilderBlankMessageTest,PromptBuilderHistoryStrategyTest,TaskEnvelopesTest,EventBusObservabilityTest'`（带 `-am`） | 58 tests / 0 failures |
| `mvnw.cmd -o -Pall-modules -DskipTests compile`（审查 §4 第 3 步，全模块含 examples/apps/tests） | BUILD SUCCESS（all-modules 全绿） |
| `mvnw.cmd -o -Pall-modules -pl examples/browser4-examples -am -DskipTests compile` | BUILD SUCCESS |
| `mvnw.cmd -o -Pall-test-modules -pl browser4-tests/browser4-e2e-tests -am -DskipTests test-compile` | BUILD SUCCESS |
| `mvnw.cmd -o -pl browser4-rest,browser4-agent-tools -am -DskipTests test-compile` | BUILD SUCCESS |
| `mvnw.cmd -o -pl browser4-agentic -am test -Dtest=RobustBrowserAgentTest,RobustBrowserAgentMemoryWiringTest` | 12 tests / 0 failures |
| `grep -r 'ObserveOptions\|onWillObserve\|ObserveParams'`（*.kt） | 无残留 |

未执行：真实浏览器 e2e（`cargo test --test e2e`，需要 backend + Chrome）与 Rust 侧 `cargo test`
——本轮改动不触及浏览器行为与 CLI 协议，只删 Kotlin API / 提示词 / 快照。Rust 源码里没有
`ObserveOptions` / `onWillObserve` 引用；`cli/browser4-cli/target/**/skills_data.rs` 里的命中项是 `skills/` 文档的构建期生成物，源文件 `skills/browser4-cli/references/config.md` 的过期表述本轮
已改成「只有 CLI tool loop 一个引擎」。
