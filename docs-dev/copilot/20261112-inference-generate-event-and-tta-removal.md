# 删除 ContextToAction.generate 的遗留：两个推理事件常量与 TextToAction（2026-11-12）

> 来源：`20261112-act-removal.md` §5 第 1 项（"`ContextToAction.generate(messages, context)` 没有生产调用方"）。
> 前置：工作区里已有一笔未提交的改动删掉了 `ContextToAction.generate`（`act` 下线后的残留入口）。
> 范围：全部改动留在工作区，本轮结束时统一提交。

## 0. 结论

`ContextToAction.generate(messages, context)` 是 `ContextToAction` 里最后一个"生成动作"入口，删掉它之后：

1. 它独占的两个私有回调 `onWillGenerate` / `onDidGenerate` 变成孤儿 → 删除；
2. 两个事件常量 `ContextToAction.ON_WILL_GENERATE` / `ON_DID_GENERATE` 唯一发射点就在那两个回调里 → 删除（连同 `getAllEventTypes()` 列表项与 `TaskEnvelopes` 的进度文案映射）；
3. `ContextToAction.tta` 唯一使用者就是 `generate` → 字段删除；顺带 `TextToAction` 整类失去全部生产消费方；
4. `TextToAction` 的两个 `@Deprecated("Use the CLI tool-loop engine path instead")` 方法
   （`modelResponseToActionDescription` / `toActionDescription` / `toObserveElement`）复核后确认**只有自己的测试在用** → 整类删除，连同只覆盖它的 `TextToActionParsingTest`；
5. 解析用的 DTO `ModelObserveResponseComplete` / `ModelObserveResponseElements` / `ModelObserveResponseElement` 与常量
   `OBSERVE_RESPONSE_ELEMENT_SCHEMA` 随之无引用 → 删除。
   （`OBSERVE_RESPONSE_COMPLETE_SCHEMA` **保留**，`PromptBuilder` 仍在用。）

## 1. 消费方矩阵（删除前）

| 符号 | 生产消费方 | 仅测试消费方 | 处置 |
|---|---|---|---|
| `ContextToAction.generate(messages, context)` | 无（`act` 下线后已无调用方） | 无 | 删（本轮前置改动） |
| `ContextToAction.onWillGenerate` / `onDidGenerate`（private） | 无（只被 `generate` 调用） | — | 删 |
| `AgenticEvents.ContextToAction.ON_WILL_GENERATE` / `ON_DID_GENERATE` | 无（唯一发射点在上述回调） | `EventBusObservabilityTest`、`EventBusObservabilityExample`、3 个 e2e 测试 | 删（连同引用改造） |
| `ContextToAction.tta` | 无（只被 `generate` 用） | — | 删 |
| `TextToAction.modelResponseToActionDescription`（`@Deprecated`） | 无 | `TextToActionParsingTest` | 删（整类） |
| `TextToAction.normalizeActionDescription` / `normalizeObserveElement` | 无（只被上面的方法用） | 间接 | 删 |
| `TextToAction.toActionDescription` / `toObserveElement`（`@Deprecated`，companion） | 无（只被 `modelResponseToActionDescription0` 用） | — | 删 |
| `ModelObserveResponseComplete` / `...Elements` / `...Element` | 无（只被 `TextToAction` 反序列化） | — | 删 |
| `OBSERVE_RESPONSE_ELEMENT_SCHEMA` | 无（`PromptBuilder` 的 import 在上一轮已删） | — | 删 |
| `OBSERVE_RESPONSE_COMPLETE_SCHEMA` | `PromptBuilder`（taskComplete 段） | — | **保留** |
| `ObserveElement`（`agentic.model`）/ `ActionDescription.observeElements` | 生产仍在用（`AgentStateManager` 等） | 多个单测 | **保留** |

> 可达性验证：`git grep` 全仓库后，`TextToAction` / `ModelObserveResponse*` / 两个事件常量在 `browser4-agentic`、`browser4-rest`、`browser4-agent-tools`、`examples`、`cli` 中都没有生产引用；e2e 侧只有 3 个测试类的事件监听。

## 2. 改动清单

| 文件 | 处置 |
|---|---|
| `browser4-agentic/.../inference/action/ContextToAction.kt` | 删 `generate(...)`（前置工作区改动）、`tta` 字段、`onWillGenerate` / `onDidGenerate`；清掉随之无用的 import（`AgentEventBus`、`AgenticEvents`、`ActionDescription`、`ExecutionContext`、`brief`、`EventBus`） |
| `browser4-agentic/.../event/AgenticEvents.kt` | 删 `object ContextToAction`（两个常量）与 `getAllEventTypes()` 里的两条 |
| `browser4-agentic/.../tools/TaskEnvelopes.kt` | 删 `EVENT_MESSAGES` 的 `onWillGenerate` / `onDidGenerate` 两条进度文案（没有事件会再触发它们） |
| `browser4-agentic/.../inference/action/TextToAction.kt` | **整文件删除**（`@Deprecated` 解析链，无生产消费方） |
| `browser4-agentic/.../inference/action/Models.kt` | 删 `ModelObserveResponseComplete` / `ModelObserveResponseElements` / `ModelObserveResponseElement` / `OBSERVE_RESPONSE_ELEMENT_SCHEMA`；保留 `OBSERVE_RESPONSE_COMPLETE_SCHEMA` 与 `AgentAction` |
| `browser4-agentic/src/test/.../action/TextToActionParsingTest.kt` | **删除**（只覆盖被删的解析链） |
| `browser4-agentic/src/test/.../observability/EventBusObservabilityTest.kt` | 删 `testContextToActionGenerateEvents`（只是把两个已不存在的事件类型发一遍） |
| `browser4-agentic/src/test/.../observability/examples/EventBusObservabilityExample.kt` | Example 2/3 改用存活事件：性能统计 `ON_WILL_RUN`/`ON_DID_RUN`，结果校验用 `ON_DID_RUN` 的 `ActResult`；cleanup 列表同步 |
| `browser4-tests/.../agent/AgentE2ETest.kt` | 删 `ON_DID_GENERATE` 监听（含 `runStepCount`、`MAX_TEST_STEPS` 强制完成 hack）与对应断言；"至少一步"改用 `history.totalSteps > 0` |
| `browser4-tests/.../skills/SkillInstallE2ETest.kt` | 同上 |
| `browser4-tests/.../skills/SkillRegistrationAndInvocationE2ETest.kt` | 不再靠事件取 `ActionDescription`，改为从 `history.states.mapNotNull { it.actionDescription }` 读取（skill 相关 toolCall 断言保持不变） |

## 3. 顺带修掉的既有红测

`AgentE2ETest.testEventHandlersLogProgressCorrectly` 与 `SkillInstallE2ETest.testAgentEventTrackingForSkillSearchTask`
都断言 `runStepCount > 0`，而 `runStepCount` 只由 `ON_DID_GENERATE` 递增 —— 该事件在 `act` 下线后就没有发射点了，
两个用例只能靠"LLM 未配置时 skip"过。本轮改为断言 `history.totalSteps > 0`（工具循环按步记录状态），断言重新有了真实含义。

> 两个类都标了 `@Disabled("ManualOnly")`，所以不在 PR/CI 门禁里跑。

## 4. 未纳入本轮

1. `PerceptiveAgent.ON_WILL_ACT` / `ON_DID_ACT`：常量仍在、`BasicBrowserAgent.onWillAct/onDidAct` 仍在，但**已经没有调用方**
   （`RobustBrowserAgent` 只发 `ON_WILL_RUN`/`ON_DID_RUN` 与节流的 `ON_TOOL_EXECUTED`）。同理 `TaskEnvelopes` 里
   `onWillAct`/`onDidAct`/`onToolExecuted` 的文案。要么把这套 act 事件接回工具循环，要么下一轮一起删 —— 需要先决定
   "per-step 可观测性"用什么事件表达。
2. `ContextToAction.generateResponseRawLegacy*`（TEXT 模式的旧路径）：仍有 `toolExposeMode == TEXT` 分支可达，保留。
3. `ToolCallResult.isSuccess` 语义取反（`Models.kt`，无生产调用方），仍是遗留清理项。

## 5. 验证

| 检查 | 结果 |
|---|---|
| `.\mvnw.cmd -o -q -pl browser4-agentic -DskipTests test-compile` | BUILD SUCCESS（exit 0） |
| `.\mvnw.cmd -o -pl browser4-agentic test -Dtest=EventBusObservabilityTest` | **11 tests / 0 failures** |
| `.\mvnw.cmd -o -pl browser4-agentic test -Dtest=TaskEnvelopesTest,PromptBuilderHistoryStrategyTest` | **12 tests / 0 failures** |
| `.\mvnw.cmd -o -Pall-modules -pl :browser4-e2e-tests -DskipTests test-compile` | BUILD SUCCESS |
| `git grep` 复查 `ON_WILL_GENERATE` / `ON_DID_GENERATE` / `ContextToAction.` / `TextToAction` / `ModelObserveResponse` | 只剩 `AgenticSession.kt` 的一行历史注释（描述 `act` 下线），无代码引用 |
