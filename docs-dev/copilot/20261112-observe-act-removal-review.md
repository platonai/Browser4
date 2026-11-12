# 审查：下线 observe-act 引擎（工作区未提交改动）

- 审查日期：2026-11-12
- 审查基线：`96a34bc773`（`revert(agent): drop @Deprecated from the PerceptiveAgent.run chain`）
- 审查范围：工作区 9 个已修改文件，`9 files changed, 85 insertions(+), 690 deletions(-)`
- 验证方式：真实执行 `mvnw.cmd -o -pl browser4-agentic -DskipTests clean compile`，**BUILD FAILURE**（见 §1）

## 0. 结论

**不能合入。** 改动方向正确（彻底下线 observe-act 引擎、只保留 CLI tool loop），但：

1. **`browser4-agentic` 主源码编译失败** —— 删了 `ObserveOptions`，却只删了入口，整条 observe 管线还在引用它；
2. **测试源码与 `examples` 模块也会编译失败**（`onWillObserve` / `agent.act(...)`）；
3. **留了一个运行期地雷**：`BasicBrowserAgent.run(action)` 以 `TODO()` 收尾，且被 `run(task)` 转发；
4. **漏了必须一起提交的生成物**：`code-mirror/agent-tool-call-specs.json` 与 `PerceptiveAgent.kt.txt` 仍宣传 `agent.observe` / `agent.act`，JAR 离线 fallback 会向模型兜售不存在的工具。

## 1. P0 — 阻塞项

### 1.1 主源码编译失败（实测）

```
$ mvnw.cmd -o -pl browser4-agentic -DskipTests clean compile
[ERROR] AgentStateManager.kt:5:33   Unresolved reference 'ObserveOptions'.
[ERROR] AgentStateManager.kt:114:28  Overload resolution ambiguity between candidates:
        suspend fun buildInitExecutionContext(action: ActionOptions, event: String)
        suspend fun buildInitExecutionContext(options: ObserveOptions, event: String)
[ERROR] AgentStateManager.kt:130:51  Unresolved reference 'ObserveOptions'.
[ERROR] AgentStateManager.kt:180:18  Unresolved reference 'ObserveOptions'.
[ERROR] AgentStateManager.kt:183:35  Unresolved reference 'instruction'.
[ERROR] ExecutionContext.kt:3:33     Unresolved reference 'ObserveOptions'.
[ERROR] ExecutionContext.kt:92:14    Unresolved reference 'ObserveOptions'.
[ERROR] ExecutionContext.kt:99:32    Unresolved reference 'returnAction'.
[INFO] BUILD FAILURE
```

| 位置 | 处理 |
|---|---|
| `browser4-agentic/.../inference/AgentStateManager.kt:5` | 删 `import ai.platon.pulsar.agentic.ObserveOptions` |
| `browser4-agentic/.../inference/AgentStateManager.kt:130-136` | 删 `getOrCreateActiveContext(options: ObserveOptions, event)` 整个重载 |
| `browser4-agentic/.../inference/AgentStateManager.kt:172-186` | 删 `buildInitExecutionContext(options: ObserveOptions, event)` 及其 KDoc |
| `browser4-agentic/.../model/ExecutionContext.kt:3` | 删 import |
| `browser4-agentic/.../model/ExecutionContext.kt:86-105` | 删 `createObserveParams(...)`（含 `@Deprecated` 注解） |

> 注：`mvnw.cmd -pl browser4-agentic compile`（增量、未 clean）会误报 `BUILD SUCCESS`——Kotlin 增量编译没有重新检查这些文件。必须 `clean compile` 才能看到真实状态。

### 1.2 测试源码编译失败

| 文件 | 失效点 |
|---|---|
| `browser4-agentic/src/test/.../event/AgentEventBusTest.kt` | `import ai.platon.pulsar.agentic.ObserveOptions`；`handlers1/handlers2.onWillObserve.addLast`（298/303）；`handlers1.onWillObserve.invoke(ObserveOptions())`（312） |
| `browser4-tests/browser4-e2e-tests/.../skills/SkillInstallE2ETest.kt:88` | `agentFlowHandlers?.onWillObserve?.addLast { options -> options.instruction }` |
| `browser4-tests/browser4-e2e-tests/.../agent/AgentE2ETest.kt:178` | 同上 |

处置：改用 `onWillAct` / `ActionOptions`，或删除这两个仅演示 observe 事件的用例。

### 1.3 `examples` 模块编译失败（`-Pall-modules`）

5 个文件仍调用已从接口删除的 `PerceptiveAgent.act(...)`：

- `examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/examples/fuse/Workflow.kt`
- `examples/.../fuse/FusedActs.kt`
- `examples/.../fuse/SummarizePage.kt`
- `examples/.../llm/TalkToLivePage.kt`
- `examples/.../agent/Scroll.kt`

处置：改为 `agent.run(...)` / `command.run`，或直接删除这些示例。注意 CI 的 `all-main-modules` 不含 `examples`，所以 CI 不会报错，但本地 `-Pall-modules` 会断。

## 2. P1 — 必须修

### 2.1 `BasicBrowserAgent.run(action)` 用 `TODO()` 收尾是运行期地雷

```kotlin
// BasicBrowserAgent.kt:168
override suspend fun run(task: String): AgentHistory {
    val actionOptions = ActionOptions(action = task, multiAct = true)
    return run(actionOptions)          // ← 转发到下面的 TODO
}

// BasicBrowserAgent.kt:173
protected open suspend fun run(action: ActionOptions): AgentHistory {
    TODO("NOT IMPLEMENTED")
}
```

问题：

- `TODO()` 抛 `NotImplementedError`（`Error`，不是 `Exception`），会**穿透** `StatefulAgentRunner` / `resolveProblemInCoroutine` 里所有 `catch (e: Exception)`，直接崩进程且没有可读错误。
- `BasicBrowserAgent` 是 `open` 且实现 `PerceptiveAgent`，任何持有基类引用的调用都会命中（`examples/.../skills/ScrapingSkills.kt:52` 就是 `BasicBrowserAgent` 类型）。
- `run(task)` 不再走 `onWillRun/onDidRun`，也不再设置 `_lastRunSessionId`，与 `RobustBrowserAgent` 语义分叉。
- 与上一提交 `96a34bc773` 的决定冲突：该提交刚撤销 `PerceptiveAgent.run` 链上的 `@Deprecated`。

建议：删掉基类的 `run(task)` / `run(action)`，把 `run(task)` 声明为 `abstract`（唯一实现 `RobustBrowserAgent` 已具备）；最低限度也要换成带说明的 `UnsupportedOperationException`。

### 2.2 漏了重新生成的提交物（离线 fallback 会宣传不存在的工具）

- `browser4-core/browser4-resources/src/main/resources/code-mirror/agent-tool-call-specs.json` 仍是 **5 条**：`observe` / `summarize` / `act` / `extract` / `extract`，应为 3 条。
- `code-mirror/PerceptiveAgent.kt.txt` 仍含 `ObserveOptions`（137）、`observe`（248/255/266）、`act`（289/299/309）。

后果：无源码的 JAR 走 JSON fallback（`ToolSpecGenerator.loadAgentToolSpecsFromJson`），把 `agent.observe` / `agent.act` 当作可用工具下发给模型，实际执行得到 `Unsupported agent method: observe`。

重新生成：

```bash
mvn -pl browser4-agentic test -Dtest=ToolSpecSnapshotRegenerator -DregenerateSpecSnapshots=true
```

测试盲区：`SourceCodeToToolCallTest` 只有 driver 侧「mirror 与 JSON fallback 一致」的断言（`webDriverSpecsFromMirroredSourceMatchBundledJson`），agent 侧没有对称用例，所以这种漂移 CI 抓不到。建议补一个 `perceptiveAgentSpecsFromMirroredSourceMatchBundledJson`。

### 2.3 遗留死代码与过期文案

删了调用方，但被调用方还在：

| 位置 | 说明 |
|---|---|
| `RobustBrowserAgent.kt:2310 handleObserveException` | private，已无调用者 |
| `BasicBrowserAgent.kt:45 snapshotService` | 无使用者；且会 `getOrCreateBoundDriver()` 触发惰性启动浏览器 |
| `InferenceEngine.kt:27 snapshotService` | 随之无使用者 |
| `Models.kt ActionDescription.toActResult()` | 无调用者 |
| `BasicBrowserAgent` 未用 import | `ActResultHelper`、`ToolSpecification`、`withTimeout`、`TimeoutCancellationException`、`MessageFormat`、`kotlin.time.Duration.Companion.milliseconds` |
| `InferenceEngine` 未用 import | `ExecutionContext`、`ActionDescription` |
| observe 提示词管线（整条成孤儿） | `ExecutionContext.createObserveActParams`、`InferencePromptBuilder.buildObserveMessages`、`PromptBuilder.buildSingleObserveMessageListAll` / `buildSingleObserveSystemPrompt` / `buildObserveActToolUsePrompt` / `OBSERVE_GUIDE_*`、`ObserveParams.fromAct|returnAction`、`PromptBuilder.buildMultistepAgentMessageListAll`（仅被上述链与一个测试使用） |

过期文案（会误导模型与排障）：

- `tools/specs/ToolSpecification.kt:45`：仍写 `agent.observe(instruction: String) // observe the current page following the instruction`
- `event/AgentEventBus.kt:44`：示例 `agent.observe(options)`
- `event/AgenticEvents.kt:30-39`：`ON_WILL_OBSERVE` / `ON_DID_OBSERVE` 常量已无发射方
- `tools/TaskEnvelopes.kt:176-177`：仍映射 `onWillObserve` / `onDidObserve`
- `inference/AgentStateManager.kt:148`：报错文案 `call act(action: ActionOptions) first!` 指向已删除的 API
- `PerceptiveAgent.kt` 中 `ActionOptions` 的 KDoc：`each act() call creates a new step/context`

### 2.4 `application.properties` 的注释不该这样删

被删注释称「`browser4.agent.runEngine` 与 `engine` 参数都已不存在」，但 `CommandToolExecutor` 里 **`engine` 参数仍在，且会显式拒绝 `observe-act`**（`REMOVED_ENGINE_VALUES = setOf("observe-act", "observe_act", "v1", "legacy")`，并有 `CommandToolExecutorTest` 覆盖）。删注释会让用户失去这条提示。

处置：保留并改成准确措辞（如「`engine` 是兼容参数，只接受 `cli`，`observe-act` 会被拒绝」），或等真正移除该参数时一并删。

## 3. P2 — 风格与发布说明

1. **格式化噪声淹没 diff**：`RobustBrowserAgent.kt` 约 70 行是纯重排（字符串续行缩进 4→8、`when` 分支间插空行、`logger.info` 折行）。`git diff --stat` 192 行 vs `git diff -w --stat` 122 行。`.editorconfig` 没有 Kotlin 缩进约定，这些改动与文件内未改动部分（如 2663 行仍是 4 空格续行）自相矛盾。建议 revert 掉，只留语义改动。
2. **无关清理可拆出**：`Models.kt` 通配符 import 改显式、`Models.kt` 里 `cliOptions` 拆行、`MemoryRecallService.kt` 删一个空行。
3. **破坏性公共 API 变更需要发布说明**（AGENTS.md DoD：Documentation updated for public behavior changes）：删除 `PerceptiveAgent.observe/act/run(ActionOptions)`、`ObserveOptions`、`ObserveResult`、`AgentFlowEventHandlers.onWillObserve/onDidObserve`。已确认 `docs/`、`skills/`、`README*` 目前没有引用这些 API，因此只需一条迁移说明：`observe/act → command.run / agent.run`，`onWillObserve/onDidObserve → onWillAct/onDidAct`。

## 4. 收尾顺序（建议）

1. 删掉 `ObserveOptions` 的 4 处残留引用 + 3 个测试文件 + 5 个示例 → `clean compile` 通过；
2. 处理 `BasicBrowserAgent.run` 的 `TODO`（改 abstract 或显式异常）；
3. 重新生成 `agent-tool-call-specs.json` 与 `PerceptiveAgent.kt.txt`（并补 agent 侧快照一致性测试）；
4. 清死代码与过期文案（§2.3），修 `application.properties` 注释；
5. revert 格式化噪声；
6. 验证：
   ```bash
   mvnw.cmd -o -pl browser4-agentic clean compile
   mvnw.cmd -o -pl browser4-agentic test -Dtest='ToolSpec*Test,AgentEventBusTest,AgentStateManagerTest'
   mvnw.cmd -o -Pall-modules -DskipTests compile     # 覆盖 examples
   ```

## 5. 附：改动清单（审查基线 `96a34bc773`）

```
application.properties                                     |   4 -
browser4-agentic/.../PerceptiveAgent.kt                    | 120 -------
browser4-agentic/.../agents/BasicBrowserAgent.kt           | 354 +--------------------
browser4-agentic/.../agents/RobustBrowserAgent.kt          | 192 +++++------
browser4-agentic/.../event/AgentEventHandlers.kt           |  11 +-
browser4-agentic/.../event/detail/DefaultAgentEventHandlers.kt |   4 -
browser4-agentic/.../inference/InferenceEngine.kt          |  53 ---
browser4-agentic/.../memory/MemoryRecallService.kt         |   1 -
browser4-agentic/.../model/Models.kt                       |  36 +--
9 files changed, 85 insertions(+), 690 deletions(-)
```
