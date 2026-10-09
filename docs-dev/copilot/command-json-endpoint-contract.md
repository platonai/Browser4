# /api/commands/json 契约修复记录

> 日期：2026-09-28 · 来源：`CommandController#submitJsonCommand()` 代码审查（含后续 P2 项）。
> 范围：P1×2 + P2×2 + `/plain` 对齐，全部配门禁内单测（`CommandControllerTest` 6、`UserCommandExecutorTest` 2、
> `CommandToolExecutorTest` 8）；其余按文末"未修"清单拆独立提交。
>
> 追加：2026-10-07 第五轮（C 批抽取）——见文末「第五轮」。本轮**不动路由、不动响应体**。

## 第一轮：P1-1 async 响应契约三方矛盾 → 202 + JSON 对象

改前：类级 `produces = application/json`，但 async 分支体是裸 `String`（任务 id）。`StringHttpMessageConverter`
在转换器链最前，会以协商出的 `application/json` 写出**不带引号**的裸 id —— 严格 JSON 客户端解析必然失败。
仓库内对同一响应有四种说法（两个 KDoc 互相矛盾、类级 produces、Rust mock `mod.rs:1490` 返回带引号的 JSON 串、
CLI `http.rs:688` 双向兜底），没有一条断言钉死。

- `CommandController.submitJsonCommand`：async 分支返回 `202 Accepted` + `CommandSubmitResponse(id)`
  （类型化 DTO ⇒ Jackson 接管 ⇒ 内容类型与体必然一致），显式 `.contentType(APPLICATION_JSON)`。
- 新增 `rest/api/entities/CommandModels.kt: CommandSubmitResponse`。
- KDoc 重写：明确 sync=200+CommandStatus（HTTP 状态与命令结果分离）、async=202+`{"id":...}`、400 的形状。
- `browser4-rest-tests/.../CommandControllerSSETest.kt`：helper 由 `removeSurrounding("\"")` 改为解析 `{"id":...}`
  （仍容忍裸串），并把 POST 断言收紧到 `isAccepted()`。

## 第一轮：P1-2 无校验 + 无异常出口 → 校验前置，失败不再分配资源

改前：`ensurePageVisitor` → `getOrCreateSession` 在**任何校验之前**执行（`UserCommandExecutor.kt:77-92`），
URL 合法性检查深埋在 `StatefulPageVisitor.kt:173`。于是空/非法 url 会先建 session（首次访问拉起浏览器）再失败，
HTTP 仍是 200；真正逃出 `doVisit` 的异常（如 `sessionId="swarm"` 撞 `PulsarSessionManager.kt:222` 的 `require`）
则返回 Spring 默认 500 结构，与 `CommandStatus` 契约不符。

- `CommandController.submitJsonCommand:98-103`：先 `trim` + `URLUtils.isStandard` 校验，非法则
  `400` + `CommandStatus`（`statusCode=400`、`processState=completed`、`message="Invalid URL: '<原值>'"`），
  完全不触碰 executor。
- 校验用的是 `StatefulPageVisitor` 自己那个谓词，因此**不会拒绝任何此前能访问的 url**，只是把校验提前。

## 第一轮：P2-1 sessionId 只认 body → 支持 query 兜底

body 的 `sessionId` 优先，缺省时取 `?sessionId=`，再缺省 `DEFAULT_SESSION_ID`；两者都有且冲突时 WARN
（沿用 `submitPlainCommand` 对 `async`/`mode` 冲突的既有做法）。此前 `?sessionId=` 被静默忽略，
而 status/result/stream 三个端点都用 query，调用方容易踩到"提交到默认会话、轮询到别处 404"。

## 第一轮：顺带清理

`sessionManager` 注入但从未使用 → 删除；`commandExecutor` 改 `private val`；补类级 KDoc。

## 第二轮：P2-3 取消语义（如实报告，而非新造能力）

原先记的是"page 任务无法取消"。查证后发现**不可取消是既定设计**：`docs-dev/copilot/mcp-interface-hardening-plan.md:248`
明确写了"页面加载类任务无法中途打断，就如实报 false"。硬做协程取消并不能中断已下发的 CDP 页面加载，
`doVisit` 的 `finally { status.done() }` 还会把状态写成正常完成——那才是真正的说谎。

于是这轮只修"不如实"的部分：

- **假话**：`POST /api/commands/{id}/cancel` 对一个**正在运行**的 page 任务回答
  `"task not running or unknown"`（`CommandController.kt:253` 原文），而 CLI 会把这句直接打给用户
  （`cli/browser4-cli/src/main.rs:12190-12194`）。现在区分三态：`cancelled=true` /
  `page visit tasks run to completion and cannot be cancelled` / `task not running or unknown`。
  判据是新的 `UserCommandExecutor.isPageVisitTask(id)`（读 `taskOwner`），并在 `cancelAgentTask`
  的 KDoc 里写明"page visit 不可取消"的原因。
- **MCP 侧**：`command.cancel` 原先只回 `cancelled=false`，调用方无从区分"还在跑的 page 任务"与
  "已结束的任务"。现在补 `reason` 字段（`cancelReason`，page 优先于 finished），并更新工具描述/help。
  该字段不违反 `TaskEnvelopes.STATUS_SCHEMA`（校验器支持的子集只有 type/required/properties/enum，
  不检查 `additionalProperties`）。
- 测试：`CommandControllerTest` 新增三态断言；`CommandToolExecutorTest` 新增"live page visit"用例，
  并给既有"已完成任务"用例补上 `reason` 断言。

## 第三轮：P2-4 同步访问的线程与并发

改前 `UserCommandExecutor.executePageVisitCommand` 直接在调用者上下文里跑整个访问：Spring MVC 下这意味着一个 servlet worker
被按住整趟访问（CI 记录过 180s+），而访问内部的页面加载又会 `withContext(Dispatchers.Default)`
（`LoadComponent.kt:176,197`）——并发数不受限时，每个请求线程都能往 Default 池里塞一个阻塞任务，把共享该池的其它协程一起饿死。

- 三个同步入口（`executePageVisitCommand` 的 3 参 / 2 参 / String 变体）统一改为
  `withContext(commandDispatcher)`，即 executor 早就为异步提交准备的 `Dispatchers.IO.limitedParallelism(...)`；
- 魔法数 10 提为有文档的常量 `UserCommandExecutor.MAX_CONCURRENT_COMMANDS`：**同步访问与后台提交共享这一个上限**，
  调用者线程立即释放，同时在飞访问数有界；
- 事件绑定不受影响：`PulsarEventBus.withServerSideEventHandlers` 用的是 `ThreadContextElement`
  （`PulsarEventBus.kt:65-69,119`），随协程跨线程传播，不是裸 ThreadLocal。

测试（新增 `UserCommandExecutorTest`，`@Tag("Unit") @Tag("Fast")`）：mock `PulsarSessionManager` 并在访问第一步停下，
断言 (1) 访问不在调用者线程上执行（去掉 `withContext` 即失败）；(2) 12 个并发请求下同时进入的访问数恰为
`MAX_CONCURRENT_COMMANDS`（去掉上限即失败）。

## 第四轮：`/api/commands/plain` 对齐（收口 P1-1）

`/json` 修好后，同一控制器里的 `/plain` 还留着同一个缺陷：async 分支返回裸 `String`，类级 `produces = application/json`
却声明 JSON。两条路由表达同一件事，形状必须一致。

- `CommandController.submitPlainCommand`：async 分支改为 `202 Accepted` + `CommandSubmitResponse(id)` + 显式
  `contentType(APPLICATION_JSON)`；KDoc 与 `submitJsonCommand` 对齐（sync=200+CommandStatus）。
  `async`/`mode` 的弃用与冲突告警逻辑不变。
- 删掉第一轮在 `/json` 里补的那条 INFO：`UserCommandExecutor.submitPageVisitCommand` 本来就打了同样的提交日志，
  白添一行重复日志（DoD 的"不引入高噪日志"）。
- `cli/browser4-cli/tests/e2e/mod.rs`：mock 的 `/api/commands/plain` 由 `200 OK` + `"task-id"` 改为
  `202 Accepted` + `{"id":"task-id"}`，与真实服务端一致。（该路由 CLI 实际不调用——CLI 走 MCP `command_run`，
  由 `mod.rs:1335-1360` 喂 `plain_commands` 断言；改它是为了 mock 保真。）
- `CommandControllerE2ETest.submitPlainCommandAsync`：解析 `{"id":...}`（仍容忍裸串）并把 POST 断言收紧到
  `isAccepted()`，与 `CommandControllerSSETest` 的孪生 helper 一致。
- 新增两条门禁内单测：`/plain` async=202+JSON 对象（与 `/json` 同形状）、`/plain` sync=200+CommandStatus。

## 验证

```bash
# 单测（PR 门禁口径：Unit/Fast，无需 Chrome）
./mvnw.cmd -o -pl browser4-rest -am test \
    "-Dtest=UserCommandExecutorTest,CommandControllerTest,CommandToolExecutorTest,ToolContractMatrixTest" \
    "-Dsurefire.failIfNoSpecifiedTests=false"
# → Tests run: 26, Failures: 0, Errors: 0 — BUILD SUCCESS

# 编译校验 rest-tests（SSE/E2E 助手改动 + 契约变更影响面）
./mvnw.cmd -o -pl browser4-tests/browser4-rest-tests -am test-compile "-DrunRestTests=true"
# → BUILD SUCCESS

# Rust e2e harness（mock 响应形状改动）
cd cli/browser4-cli && cargo test --test e2e --no-run
# → Finished `test` profile
```

`/api/commands*` 此前只有 `E2ETest`/`Slow` 标签的用例，两个门禁都不跑；现在 async 形状、非法 url 不分配资源、
取消三态、同步访问的线程与并发上限都进了门禁。

## 第五轮（2026-10-07）：C 批抽取 —— sessionId / 信封 / SSE 桥

起因：评估"把 `api/x`（`ScrapeController`）、`api/scrape`（`PageScrapeController`）、`api/commands`（本文件的主角）
三个采集 controller 合并成一个"时，结论是**类不该合、契约该统一**——三者不共享任务模型、会话语义与响应形状，
物理合并会在启动期撞 `Ambiguous mapping`（`{id}/status|result|stream` 与根 `POST` 在两个前缀上同形）。
于是先做**只在共享规则上收敛**的这一批：路由与响应体一律不动。

### 5-1 `sessionId`：空白 = 未指定（新增 `rest/api/support/SessionResolution.kt`）

改前两边各写一份，且**规则不一致**：`CommandController` 用 `sessionId ?: DEFAULT_SESSION_ID`，于是 `?sessionId=`
（空串）会被当成**真的会话 id** —— `ensurePageVisitor("")` 去建一个调用方从未打开过的会话；而 `PageScrapeService`
用 `takeIf { it.isNotBlank() }`，空白即拒。第一轮把 `?sessionId=` 从"被静默忽略"改成"被采纳"，本轮补上它该有的判据。

- `SessionResolution.firstNamed(...)` / `firstNamedOrDefault(...)`：会话 id 的判据是**非空白**，`null`/`""`/`"   "` 一律视为"未指定"。
- `CommandController` 五处（`resolveSessionId`、`submitPlainCommand`、`getStatus`、`getResult`、`streamEvents`）统一走它。
- 冲突 WARN 收紧为"**两侧都真的指定了**才算冲突"：否则空白 body + `?sessionId=s1` 会先 WARN 说"用 body 的值"、
  实际却用 `s1` —— 一条会骗人的日志。
- `PageScrapeService` 行为不变（缺省仍拒），只是判据改为共享；两条面的**答案**差异（默认 vs 拒绝）保持并写进 KDoc。

### 5-2 `/api/scrape` 信封（新增 `rest/api/support/ScrapeEnvelopes.kt`）

该面 5 个站点（成功 ×2、400、`strict` 失败、404）本来各拼一遍 map，共享两条规则——键顺序、以及"message 缺失即空串，
永不 null"——但没有任何地方钉住。收敛为 `success(data)` 与 `failure(error, message, vararg details)`，键顺序由测试锁死。
`/api/x` 的 400 体**不并进来**：它压根没有 `success` 键，合并只能靠 flag 掩盖差异（而它本就在下线候选名单上）。

### 5-3 `Flow → SSE` 桥（新增 `rest/api/support/ServerSentEvents.kt`）

`ScrapeService.streamEvents` 与 `CommandController.streamEvents` 各写一遍 create → collect → dispose。

- 抽为 `Flow<T>.toServerSentEvents(scope, logger, map)`；**事件形状留在调用点**：`/api/x` 发响应自身的 `id`/`event`，
  `/api/commands` 只发 `data`（其 JS 客户端只解 data），差异因此仍在调用点可见。
- **顺带修一个被抽取暴露的真 bug**：原来的 `onCompletion { sink.complete() }` 排在 `.catch` **之前**，状态流失败时
  先 `complete`、再 `sink.error`，后者被已终止的 sink 丢弃 —— `/api/commands/{id}/stream` 会把"任务状态流炸了"
  作为**正常结束**发给客户端，错误只留在日志里。现改为 `onCompletion { cause -> if (cause == null) sink.complete() }`，
  失败交给 `catch` 上报（且仍由 `catch` 吞掉，避免在 scope 里变成未捕获异常）。`/api/x` 原本是对的（它在 `onEach`
  里见 `isDone` 就 `complete`），本轮之后两条流行为一致。

### 新测试（均 `Unit` + `Fast`，PR 门禁内）

`SessionResolutionTest` 3、`ScrapeEnvelopesTest` 4、`ServerSentEventsTest` 4（顺序与完成、两种事件形状、
失败上报、dispose 取消轮询），`CommandControllerTest` 6 → 8（空白 sessionId 不再落到 `""`；body 覆盖 query）。

```bash
./mvnw.cmd -B --no-transfer-progress -pl browser4-rest -Dtest="SessionResolutionTest,ScrapeEnvelopesTest,ServerSentEventsTest,CommandControllerTest,PageScrapeControllerTest,ScrapeControllerTest,SystemStatusControllerTest,PageScrapeServiceTest" -DfailIfNoTests=false test
# → Tests run: 70, Failures: 0, Errors: 0 — BUILD SUCCESS

./mvnw.cmd -B --no-transfer-progress -pl browser4-rest -Dtest="PageScrapeToolExecutorTest,CommandToolExecutorTest,UserCommandExecutorTest,MCPToolControllerTest,ArtifactStoreTest,ArgumentNormalizersTest" -DfailIfNoTests=false test
# → Tests run: 138, Failures: 0, Errors: 0 — BUILD SUCCESS
```

未跑：`E2E`/`Slow`/`Integration` 标签的用例（`CommandControllerSSETest` 等，两个门禁都不跑；SSE 的行为变更因此只有
单测覆盖）与 Rust e2e（本轮无路由改动）。

## 未修（建议独立提交）

| # | 问题 | 要点 |
|---|---|---|
| — | `PageVisitRequest.id` 被静默忽略 | 现已写进 KDoc；真正支持需 `StatefulPageVisitor.create(id)` |
| — | 同步请求也写 `statusCache`（10k/2h TinyLFU） | 高频同步流量可驱逐仍在轮询中的 async 状态 |
| — | 任意 body `sessionId` 即建会话、REST 路径无限流、全仓无 Spring Security | 仅 4h 空闲回收兜底 |
| — | 异步提交的 `ensurePageVisitor` 仍在请求线程 | `submitPageVisitCommand` 需同步返回 id，只有新建会话时才会真阻塞 |
| — | `/plain` 的空命令仍走 202+id，轮询才知道 400 | 与 `/json` 的"先校验再分配"不同；未改是怕破坏既有轮询语义 |
| — | SSE 桥是手写的 `Flux.create`，而仓库已依赖 `kotlinx-coroutines-reactor` | 换 `Flow.asFlux()` 还能再删一层，但会改变缓冲与取消机制，需一次真机 SSE 验证 |
| — | SSE 的失败路径没有 E2E 覆盖 | 第五轮的错误上报只有 `ServerSentEventsTest` 单测钉住；`CommandControllerSSETest` 是 `Slow`/`E2E` 标签，两个门禁都不跑 |
