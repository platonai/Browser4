# 审查：`GenericAgenticSession#act()` 全流程

- 审查日期：2026-11-12
- 审查基线：`da2f3fec43`（`refactor(agent): remove the observe-act engine, keep only the CLI tool loop`）
- 审查范围：`AgenticSession.act()` 从 CLI / REST 入口到浏览器动作落地的整条链路（不含 `PerceptiveAgent.run` 的 CLI tool loop）
- 审查方式：静态调用链追踪 + API 签名核对（`javap` 校验 `pulsar-browser:4.11.32` 的 `AbstractWebDriver#getSnapshotService` / `SnapshotService#getBrowserUseState`）+ 现有测试覆盖核查
- 前置背景：上一轮 `20261112-observe-act-removal.md` §2 P1-2.3 明确把 `SessionActExecutor` / `TextToAction` 这条 `session.act(...)` 链路"留待单独清理"。本文就是那笔账。
- 处置：已按 **路线 A** 落地，实施记录见 `20261112-act-removal.md`。

## 0. 结论

**这条链路目前是一个"看着成功、其实失败"的黑洞，且与仓库里已经成熟的那条链（`ContextToAction` + tool loop）在容错、超时、观测三个维度上全面落后。**

- `act()` 是 `@Deprecated("Not used anymore")` 的实现，却是 `browser4-cli act`（隐藏命令，无 e2e 覆盖）与 `/api/act` 的唯一实现——注释与事实相反；
- **P0**：工具调用失败被 `ActService` 丢掉 `evaluate.exception`，REST 返回 `success=true`，CLI 打印 `performAct` 并 exit 0；
- **P0**：模型响应解析用裸 `jacksonObjectMapper()`，既不做代码围栏剥离、也不允许多余字段，而"同仓库另一条链"的容错实现与单测（`TextToActionParsingTest`）根本不在这条链上；
- **P1**：忽略调用方已经拿在手里的 `driver`（crawl/load 回调里会作用到别的页或新开浏览器）、固定 `DEFAULT` 会话（`-s` 被丢弃）、无超时、无 DOM settle、无会话锁、无事件/历史/日志；
- 全链路**零测试**（`ActController` / `ActService` / `SessionActExecutor` 都没有测试类；CLI 侧 `e2e_coverage: Excluded`）。

## 1. 全流程（实际执行顺序）

```
browser4-cli act "<desc>"                            cli/main.rs:33350（早于通用解析，description = 其余 positional 拼接）
  └─ http::execute_act_command                        http.rs:800   POST /api/act，只有 {"description"}；60s 客户端超时
       └─ ActController.act                           ActController.kt:26   blank 校验；异常 → 200 + success=false
            └─ ActService.translateAndExecute         ActService.kt:26
                 └─ sessionManager.getOrCreateSession(DEFAULT_SESSION_ID).agenticSession   ← 会话写死 "DEFAULT"
                      └─ GenericAgenticSession.act    AgenticSession.kt:111
                           └─ executor (by lazy)      AgenticSession.kt:109
                                └─ SessionActExecutor(session)          SessionActExecutor.kt:18-22
                                     └─ session.getOrCreateBoundDriver() ← 构造期立即绑定/新建 driver
                                └─ performActs(action)                  SessionActExecutor.kt:47-55  @Deprecated
                                     ├─ TextToAction(conf)                              TextToAction.kt:31
                                     ├─ generateActions(action, driver)                 TextToAction.kt:49-99 @Deprecated
                                     │    ├─ SnapshotService.getBrowserUseState(全量 options)   ← 无 DOM settle、无 try/catch
                                     │    ├─ ToolCallSpecificationRenderer.render(includeCustomDomains=true)  ← 域广告
                                     │    ├─ PromptTemplate(SINGLE_WEB_DRIVER_ACTION_GENERATION_PROMPT)
                                     │    ├─ chatModel.call(systemMessage="", userMessage)      ← system 为空
                                     │    ├─ jacksonObjectMapper().readValue(content)           ← 零容错
                                     │    └─ toActionDescription(...).toActionDescriptions()    ← 一次响应可含 N 个动作
                                     └─ actions.map { performAct(it) }                  SessionActExecutor.kt:30-45 @Deprecated
                                          ├─ domain=="tab"     → callFunctionOn(tc, driver)
                                          ├─ domain=="browser" → callFunctionOn(tc, driver.browser)
                                          └─ else              → throw IllegalArgumentException("❓ Unsupported domain")
                                               └─ AbstractToolExecutor.callFunctionOn      异常 → 包进 TcEvaluate.exception（不抛）
```

另外两个生产入口，调用形态相同但**丢弃返回值**（失败完全静默）：

| 入口 | 位置 | 形态 |
|---|---|---|
| 采集/抓取的回调动作 | `ScrapeService.kt:44-54` | `be.onPageReadyActions.addLast { _, _ -> actions.forEach { session.act(it) } }` |
| REST load 的回调动作 | `LoadService.kt:44-49, 66-76` | `actions.forEach { session.act(it) }`（同样忽略 handler 给的 `page`/`driver`） |
| 示例 | `examples/.../llm/TalkToLivePage.kt:35` | `session.act(action)` |

## 2. P0 — 必须先修

### 2.1 工具调用失败被上报为成功（`success=true` + 输出 `performAct`）

`AbstractToolExecutor.callFunctionOn` 的设计是**错误入包**：任何工具异常都变成 `TcEvaluate(pseudoExpression, e, help)`，不抛（`AbstractToolExecutor.kt:191-195`）。`performAct` 又给它套一个 `message = "performAct"` 的常量（`SessionActExecutor.kt:40-44`）。

而 `ActService` 只渲染 `description` / `value` / `message`，**从不看 `evaluate.exception`**：

```kotlin
// ActService.kt:36-46
results.joinToString("\n") { result ->
    val desc = result.evaluate.description      // 失败构造器里是 null
    val value = result.evaluate.value           // click 返回 Unit → null
    val message = result.message                // 恒为 "performAct"
    when {
        !desc.isNullOrBlank() -> desc
        value != null -> value.toString()
        !message.isNullOrBlank() -> message     // ← 成功和失败都走到这里
        else -> "(done)"
    }
}
```

后果（可对照源码直接推导，无需跑服务）：

| 场景 | REST 响应 | CLI 输出 |
|---|---|---|
| `act "click the login button"` 成功（`tab.click` → Unit） | `{success:true, output:"performAct"}` | `performAct`，exit 0 |
| 同一个描述但元素不存在（`click requires 'selector'` 或 driver 抛错） | `{success:true, output:"performAct"}` | `performAct`，exit 0 |
| 模型返回 `{"elements": []}` | `{success:true, output:"(no actions performed)"}` | `(no actions performed)`，exit 0 |

即：**这条命令对用户是可用的假象**——唯一能看到的失败是"整条链抛异常"（解析失败/未知域），工具级失败 100% 静默。`DetailedActResult.success` 与 `ToolCallResult.evaluate.exception` 里明明有信息，只是没人读。

修法（最小）：

```kotlin
// ActService
val failures = results.filter { it.evaluate.exception != null }
if (failures.isNotEmpty()) {
    val detail = failures.joinToString("; ") { it.evaluate.exception.toString() }
    throw IllegalStateException("act failed for $description: $detail")
}
return results.joinToString("\n") { r ->
    r.evaluate.exception?.message ?: r.evaluate.description ?: r.evaluate.value?.toString() ?: r.expression
}
```

`message = "performAct"` 应改成工具调用的 `pseudoExpression`（`result.expression` 已有），否则用户永远看不到"到底点了个什么"。

### 2.2 模型响应解析对真实输出零容错，且容错实现与单测都不在这条链上

`generateActions` 用的是裸 Jackson：

```kotlin
// TextToAction.kt:94-96
val mapper = jacksonObjectMapper()
val elements: ModelObserveResponseElements = mapper.readValue(content)
```

- `jacksonObjectMapper()` 只是 `ObjectMapper().registerKotlinModule()`，`FAIL_ON_UNKNOWN_PROPERTIES` **仍是默认 true**；`ModelObserveResponseElements` 只有一个 `elements` 字段（`inference/action/Models.kt:79-86`）。模型多输出一个 `"taskComplete": false`、`"thinking"`、`"summary"` 就直接 `UnrecognizedPropertyException`。
- 没有代码围栏剥离。同文件里 `reviseModelResponse`（`TextToAction.kt:156-187`）专门处理 `[{"elements` / `<output_act>` 这类包装，**但只被 `modelResponseToActionDescription` 调用，而后者只被 `ContextToAction` 和单测调用**（`ContextToAction.kt:324`）。也就是说：为这条链路写的容错代码，这条链路自己没用到。
- 同样被绕过的还有 `modelResponseToActionDescription` 的 `taskComplete` 分支（`TextToAction.kt:130-145`）和 `normalizeActionDescription`（`ref` → `selector` 修订、`node`/`cssSelector` 富化）。当前提示词的 schema 只给了 `domain/method/arguments`（`OBSERVE_RESPONSE_ELEMENT_SCHEMA`），所以暂时没炸；但 `ModelObserveResponseElement` 明确有 `ref` 字段，模型给了 `ref` 就会被静默忽略，最终以 `click requires 'selector'` 的形式失败（再叠加 2.1 变成"成功"）。
- 空响应（模型拒答/被截断）→ `readValue("")` → `MismatchedInputException`，直接上抛到 `ActController`，用户看到 Jackson 原文。
- `TextToActionParsingTest`（4 个用例）全部打在 `modelResponseToActionDescription` 上，对 `act()` 这条真实路径**零覆盖**——测试绿不代表这条链能跑。

修法：`generateActions` 改成走 `reviseModelResponse` → `modelResponseToActionDescription(instruction, agentState, response)`（或至少 `mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false)` + 围栏剥离），让两条链共用一套解析与测试。

## 3. P1 — 必须修

### 3.1 忽略调用方已经持有的 `driver`/`page`，会作用到错误的页

`SessionActExecutor` 的第二个构造参数在**构造期**就 `session.getOrCreateBoundDriver()`（`SessionActExecutor.kt:18-22`），此后所有动作都打在这个"会话当前绑定的 driver"上。而 `boundDriver` 是会话级单值 bean（`AbstractPulsarSession.kt:176`、`427`）。

问题在采集场景：`onPageReadyActions` / `onBrowserLaunchedActions` 的回调签名是 `(page, driver) -> ...`，**手里已经有这一页的 driver**，却写成 `session.act(it)`：

- 并发抓取时 `boundDriver` 可能是**别的页**（最后绑定的那个）→ 动作打到错误页面；
- 会话尚未绑定 driver 时，`act()` 会 `createBoundDriver()` **新开一个标签页甚至新启一个浏览器**，而这一页的 driver 就在回调参数里；
- 附着会话（`browser.launch.policy=attached`，`AbstractPulsarSession.kt:312-324`）在 attach 未连通时，`act()` 会**抛异常中断整条 crawl**，而 callback 的 `driver` 其实是可用的。

修法：`performActs(action, driver: WebDriver = session.getOrCreateBoundDriver())`，三个调用点把 `driver` 传进去。这条改动同时消掉"`by lazy` 持有旧 driver"的问题（见 3.5）。

### 3.2 REST 入口写死 `DEFAULT` 会话，CLI 的 `-s` 被静默丢弃

`ActService.kt:27` 固定 `DEFAULT_SESSION_ID`（`"DEFAULT"`，`B4Constants.kt:211`）；`ActRequest`（`ActController.kt:9`）只有 `description`；CLI 侧 `handle_act`（`main.rs:16414`）也只传 description，`act` 又在通用参数解析之前被截获（`main.rs:33350-33370`），`-s/--session` 完全没被读取。

于是 `browser4-cli -s news act "click login"` 会打到 `DEFAULT` 会话的页面——**点击了另一个浏览器上下文**。`help.rs:2146` 还写着 "Executes the command immediately against your current browser session"，与实现相反。

修法：`ActRequest(description, sessionId: String? = null)`，CLI 传 `global.session_id`，`ActService` 用 `sessionId ?: DEFAULT_SESSION_ID`。

### 3.3 无超时、无 DOM settle、无快照降级（与同仓库成熟链对比）

| 能力 | `act()` 路径 | 现代路径 `AgentStateManager.getBrowserUseState`（`AgentStateManager.kt:492-539`） |
|---|---|---|
| 未绑定 driver | 直接 `createBoundDriver()`（启浏览器） | `boundDriver == null` → `BrowserUseState.DUMMY`（真惰性） |
| DOM settle | 无 | `pageStateTracker.waitForDOMSettle()` |
| 超时 | 无（`generateActions` 与 `performActs` 都没有 `withTimeout`） | `withTimeout(30_000)` |
| 快照异常 | 直接上抛，`act` 失败 | `catch` → 降级 `DUMMY` + `logger.warn` |
| 编码任务 | 无短路 | `codingMode` → `DUMMY` |

`spring.mvc.async.request-timeout` 在仓库里没有配置（`*.properties` 无匹配），`/api/act` 的服务端唯一deadline 是客户端 60s（`http.rs:22`）；LLM 卡住时服务端协程继续跑、快照与 token 照烧。`chatModelConf` 还把输入上限强制抬到 900k tokens（`ChatModelConfig.kt:17`），单次失败的成本很高。

修法：`performActs` 外层 `withTimeout(60_000)`；快照段照抄 `AgentStateManager` 的 degrade 逻辑。

### 3.4 不加会话锁，与 MCP 工具调用并发穿插

`ManagedSession` 提供了 `mutex` + `withLock`（`ManagedSession.kt:42, 50-51`），`MCPToolController.kt:1001`、`PagesController.kt:158`、`HTMLSnapshotToolExecutor`、`WebDbToolExecutor` 都在用。`ActService` 没有：

```kotlin
// ActService.kt:27-30 —— 直接拿 session 就干
val session = sessionManager.getOrCreateSession(DEFAULT_SESSION_ID).agenticSession
val results = session.act(description)
```

两个并发 `act`（或一个 `act` + 一个 `browser_click`）会在同一 driver 上交错快照与点击。而且因为 3.2 写死同一会话，冲突门槛比想象的低。

修法：`managed.withLock { session.act(description) }`（需要 `ActService` 拿 `ManagedSession` 而不是 `.agenticSession`）。

### 3.5 一个动作抛异常 → 已执行的动作结果全丢

`actions.map { performAct(it) }`（`SessionActExecutor.kt:54`）没有 per-action 的 try/catch；`performAct` 的 `else -> throw IllegalArgumentException("❓ Unsupported domain")`（`SessionActExecutor.kt:37`）会中断整个 map。一次响应里第 1 个动作成功、第 2 个域不支持 → 用户只看到一条 `❓ Unsupported domain: agent`，第 1 个动作"白做且不可见"（副作用已发生，返回值丢失）。

同时这条边界很容易被踩到：`ToolCallSpecificationRenderer.render(includeCustomDomains = true)` 会把硬编码 spec（含 `agent.extract` / `agent.summarize` / `system.help`，`ToolSpecification.kt:42-48`）以及 `AgentToolManager.init` 注册的 `coding` / `b4` 域（`AgentToolManager.kt:214-221`）一起塞进 prompt，而 `performAct` 只认 `tab`/`browser`（且大小写敏感，别处用的是 `equals("tab", true)`）。提示词额外要求 "`domain` must be `tab`"（`PromptBuilder.kt:195`）只是软约束。

修法（二选一）：
- 收紧：`render(includeCustomDomains = false)` + `customDomainFilter = { it == "tab" }`，让 prompt 与实际能力一致；
- 放开：把域分派交给 `AgentToolManager` 的完整 executor 表，而不是三个硬编码分支。

并把 `map` 改成 `mapNotNull { runCatching { performAct(it) }.getOrElse { e -> ToolCallResult(TcEvaluate(it.pseudoExpression, e), ...) } }`，保证"部分成功"可见。

### 3.6 零可观测：无事件、无历史、无日志、无指标

`SessionActExecutor` 全文 56 行没有一行日志/事件（读一遍即可确认）。对比：
- `ContextToAction.generate` 有 `onWillGenerate` / `onDidGenerate`；
- `BasicBrowserAgent` 有 `ON_WILL_ACT` / `ON_DID_ACT` + `EventBus.emit`；
- `InferenceLogger` / `StructuredAgentLogger` / `ToolTracing.withChildSpan` 全在这条链之外。

所以 `act()` 的动作在 SSE、`history.jsonl`、`tool.calls{tool,domain,outcome}` 指标里**完全不存在**；出问题时只有 `AbstractToolExecutor` 那行 `logger.warn`。唯一一行日志是 `ActService.kt:29` 的 `"Executing act: {}"`——没有结果、没有耗时、没有 token。

## 4. P2 — 应修

1. **`ToolCallResult.isSuccess` 语义取反**：`Models.kt:374` `val isSuccess get() = evaluate.exception != null`（`TcEvaluate.success` 是 `exception == null`）。目前无生产调用方，属于埋着的雷，改成 `evaluate.exception == null`。
2. **`@Deprecated("Not used anymore")` 挂在活路径上**：`SessionActExecutor` 的两个方法（`SessionActExecutor.kt:30, 47`）与 `TextToAction.generateActions`（`TextToAction.kt:49`）都带着这行注解，但 `AgenticSession.act` 没标、且是 CLI/REST 唯一实现。这让"它已经不用了"成为错误共识（上一轮清理正是据此把它留下的）。要么删链，要么把注解改成准确的 `@Deprecated("Legacy one-shot path; prefer the CLI tool loop")` 并给 `AgenticSession.act` 也加上。
3. **空 system 消息**：`generateActions` 只 `messages.addUser(...)`，`systemMessages()` 为空 → `chatModel.call("", userMessage)`（`TextToAction.kt:86-92`）。对空 system 严格的服务商会直接 400。
4. **生产路径调用测试工厂**：`TextToAction.chatModel`（`TextToAction.kt:39-40`）先查 `TestChatModelFactory`。只要进程里存在 `BROWSER4_TEST_LLM_RESPONSE_FILE`/`_DIR`（或同名 system property），**线上所有 act 都返回固定文件内容**。`file:///` 式的误配置会变成安全事故，而不是测试能力。建议只在该测试工厂的显式开关（如 `browser4.test.llm.enabled=true`）下启用，或挪到测试源集。
5. **死代码 + 类初始化副作用**：`TextToAction` 的 `baseDir = AppPaths.get("tta")` 与 `init { Files.createDirectories(baseDir) }`（`TextToAction.kt:267-271`）——`baseDir` 无人使用，类加载即写磁盘，目录不可写时成为 `ExceptionInInitializerError`。删。
6. **CLI 文案与能力不符**：`commands.rs:5436` description 写 "Translates plain text to a browser4-cli command and runs it immediately"（现在翻译的是 tool call，不是 CLI 命令）；`help.rs:2142` 同样；`help.rs:2158` 的例子 `act "take a screenshot and save it as my-screen.png"` **不可能成功**（`tab.screenshot` 都不在硬编码 spec 里，更没有落盘能力）。
7. **三份重复实现**：`AbstractAgenticQLSession` / `BasicAgenticSession` / `GenericAgenticSession` 各自复制 `companionAgent` + `executor` + `act`（`AgenticSession.kt:58-62, 89-93, 107-111`）。建议抽 `AgenticSessionSupport`（组合优于继承，因为三者基类不同）。
8. **零测试**：`ActController` / `ActService` / `SessionActExecutor` 没有测试类；CLI 侧 `act` 是 `hidden: true` + `e2e_coverage: E2eCoverage::Excluded`（`commands.rs:5436` 附近的 `CommandDef`）。若保留这条链，至少要有一个 REST 层用例断言"工具失败 → `success=false`"。

## 5. 两条路线

### 路线 A（推荐）：下线 `act()`，与上一轮方向一致

上一轮已经把 `PerceptiveAgent.observe/act` 删掉，只留 CLI tool loop；`session.act` 是同一类遗留物，且现状是"假成功"。下线清单：

| 删除 | 替代 |
|---|---|
| `AgenticSession.act` + 三份实现 + `SessionActExecutor` | `command.run` / `agent.run`（CLI tool loop） |
| `TextToAction.generateActions`（保留 `modelResponseToActionDescription`，`ContextToAction` 还在用） | — |
| `/api/act` + `ActController` + `ActService` | `POST /mcp/call` 的 `command.run` |
| CLI `act` 命令（含 help/描述/早处理分支） | `browser4-cli agent run "<desc>"` |
| `LoadService` / `ScrapeService` 的 `session.act` 回调 | `agent.run`，或先用"动作列表 → 单工具调用"的同步执行器 |
| `examples/.../TalkToLivePage.kt` | 上一轮改示例的同款做法 |

### 路线 B（`/api/act` 必须保留）：把引擎换成成熟那套

1. 用 `AgentStateManager` 建单步 `ExecutionContext`（拿到 DOM settle / 30s 超时 / 降级 / coding 短路）；
2. 用 `ContextToAction.generate(messages, context)` 解析（容错 + 围栏修复 + `taskComplete` 分支 + `normalizeActionDescription`）；
3. 用 `AgentToolManager` 的完整 executor 表执行（不动手写 `tab`/`browser` 分支）；
4. `ActService` 返回结构化结果（`[{expression, value, error}]`），失败即 `success=false`；
5. 补 REST 用例（成功 / 工具失败 / 模型输出不合法 / 并发两次 act）。

## 6. 验证清单（改完后）

```bash
# 1. 编译（上一轮的坑：-pl 不带 -am 会读到 m2 里的旧 JAR）
.\mvnw.cmd -o -pl browser4-agentic -am -DskipTests clean compile
.\mvnw.cmd -o -pl browser4-rest -am -DskipTests test-compile

# 2. 定向测试
.\mvnw.cmd -o -pl browser4-agentic -am test -Dtest='TextToActionParsingTest,ToolCallSpecificationRendererTest,AgentStateManagerTest'

# 3. 新增的 act 用例（路线 B 必须；路线 A 换成 404/迁移断言）
.\mvnw.cmd -o -pl browser4-rest -am test -Dtest='ActControllerTest'

# 4. CLI 侧（改文案/删命令时）
cd cli/browser4-cli && cargo test --bin browser4-cli
cargo test --test e2e -- --scenario '*act*'    # 目前无此场景；若保留 act 建议补一个
```

## 7. 证据索引

| 结论 | 证据 |
|---|---|
| `act()` 实现即 deprecated 方法 | `AgenticSession.kt:109-111` → `SessionActExecutor.kt:30,47` |
| 失败入包不抛 | `AbstractToolExecutor.kt:177-195` |
| 失败被丢弃 | `ActService.kt:36-46`（无 `exception` 分支） |
| 恒输出 `performAct` | `SessionActExecutor.kt:40-44` + `ActService.kt:42-43` |
| 裸 Jackson / 无围栏修复 | `TextToAction.kt:94-96` vs `TextToAction.kt:156-187` + `ContextToAction.kt:324` |
| 解析单测打错入口 | `TextToActionParsingTest.kt:15`（调 `modelResponseToActionDescription`） |
| 域广告 ≠ 执行能力 | `TextToAction.kt:71` + `ToolSpecification.kt:42-48` + `AgentToolManager.kt:214-221` vs `SessionActExecutor.kt:34-38` |
| 忽略回调 driver | `SessionActExecutor.kt:18-22` + `ScrapeService.kt:44-54` + `AbstractPulsarSession.kt:176` |
| 会话写死 | `ActService.kt:27` + `ActController.kt:9` + `main.rs:33350-33370` |
| 无超时/无 settle/无降级 | `TextToAction.kt:50-99` vs `AgentStateManager.kt:492-539` |
| 无会话锁 | `ManagedSession.kt:50-51` 有，`ActService.kt:27-30` 未用 |
| 无事件/历史 | `SessionActExecutor.kt`（全 56 行无日志/事件） |
| `isSuccess` 取反 | `Models.kt:374` |
| 测试工厂进生产 | `TextToAction.kt:39-40` + `TestChatModelFactory.kt:37` |
| 死代码 + 初始化副作用 | `TextToAction.kt:267-271` |
| CLI 文案与能力不符 | `commands.rs:5436`、`help.rs:2142,2158` |
| 零测试/无 e2e | `commands.rs`（`hidden: true`, `E2eCoverage::Excluded`）+ 无 `ActControllerTest`/`ActServiceTest` |
