# MCP 接口层加固开发计划（需求 1–14）

> 日期：2026-09-13（Phase 3 更新 2026-09-14） · 分支基准：`feat/mcp-channel-parity`（已含 P0–P5 与 refreshTools 修复）
> 范围：标准 MCP server（A，`browser4-agentic`，8088）+ 私有 dispatcher（B，`browser4-rest`，8888/18182）+ 其背后的 REST 端点
> 依据：本计划全部条目来自**实跑观测**（见 §1），不是纸面推演

---

## 1. 现状与既有结论（实测）

> **Phase 0 已完成（2026-09-13，提交 `f6692f123c` + 本阶段后续提交）**：
> - `0.1` schema 参数描述改为 `Arg.description ?: Arg.expression`，并输出按类型渲染的 `default`；`crawl_submit` 现在给出 `required: ["url"]`、`depth: {description: "depth: Int = 1", default: 1}`。
> - `0.2` `ToolSpec.expression` 不再打印 `Arg(name=…)`；`help{crawl,submit}` → `crawl.submit(url: String, depth: Int = 1, args: String = "")`。顺带修掉更严重的既有缺陷：`extractInterface` 把「无默认值」写成空串，导致**所有源码生成的参数都被标成可选**（`required` 恒空）。新增 `ToolSpecSnapshotRegenerator`（`-DregenerateSpecSnapshots=true`）重生成 `code-mirror` 快照。
> - `0.3` 新增 `ToolTargetResolver`（agentic）+ `CustomToolTargets`（rest，控制器与标准 server 共用）；`ToolExecutor.requiresReceiver` 区分「服务自持」执行器；`command` 域改为经 `CustomToolRegistry` 直接派发。实测：`memory_search`/`experience_list`/`crawl_status`/`command_status`/`webdb_normalize`/`experience_query`/`html_snapshot_summary`/`skill_list` 均可执行（此前只有 `skill_*`）。
> - `0.4`（验证中发现的阻断项）标准 server 此前**只透传已声明参数**，而 `tab.navigate` 声明的是 `userTypedUrl` → 客户端发 `{"url": …}` 被丢弃，最基本的导航在 A 上不可用。现改为「声明参数按类型转换 + 其余原样透传」，与 B 对齐；实测 `navigate{"url"}` → `title` → `html_snapshot_summary` 全链路通过。
> - `0.6` `ToolSpecLint` + `ToolSpecLintTest` 进测试：错误级（空描述、签名泄漏、重复签名、kebab `cliName`）必须为零；当前报告 **117 个生成 spec / 175 条文档告警**，即 Phase 1 的量化待办。

仍在的缺口，构成本计划后续阶段的前置项：

已修复（本分支）：`2cc9936865`（插件域晚注册导致 A 少 26 个工具）、`2e191e8e59`（stateless 传输）、`913e3cf82d`（工具面/命名/结果/会话对齐）。

仍在的缺口，构成本计划的前置项：

| # | 缺口 | 证据 | 对应需求 |
|---|---|---|---|
| G1 | schema 里 property description **就是参数名** | `crawl_submit.url.description == "url"` | 1 |
| G2 | `ToolSpec.expression` 用 `Arg.toString()` 拼签名 → `help` 输出 `Arg(name=url, type=String, defaultValue=null)`；并已污染提交的 `code-mirror/driver-tool-call-specs.json` | 实跑 `help{crawl,submit}`；`Models.kt:96-100` | 1, 2 |
| G3 | A 公告 26 个插件域工具，**只有 `skill_*`（11）能执行**，其余报 `no target object is available` | 实跑 6 个域探测 | 4, 3 |
| G4 | 同一工具两通道参数行为不一致：A 只透传**已声明**参数（`crawl.sql/urls` 传不进），B 全量透传 | `Browser4MCPServer.buildArgsMap` vs `MCPToolController.dispatchToCustomExecutor` | 5, 3 |
| G5 | ✅ 已修（`ceaa98a691`）：B 的 `/mcp/tools` 曾「首探即缓存」整个列表，首探发生在会话创建前 → 工具面永远停在 83。现静态段缓存、会话段每次请求合并；实测 `open_session` 前 83 → 后 **275**（+192 个 `navigate`/`click`/`title`… ） | 实跑对比 | 10 |
| G6 | 别名/命名/渲染曾有三份副本漂移（已建 `McpToolNames`/`ToolResultTextRenderer`，仍有 2 个别名因缺 canonical spec 注册不上） | `browser_is_enabled`/`browser_dialog_status` 缺席 | 1, 3 |
| G7 | 错误是自由文本（`ERROR: xxx failed: ...`），无稳定错误码；HTTP 恒 200 | A/B 实测 | 4 |
| G8 | 无参数校验层/无返回值校验（`ToolSpec.returnType` 是字符串，未用于校验） | 代码 | 5, 6 |
| G9 | 无调用日志规范（B 把参数直接拼进 INFO）、无 per-tool 指标、无限流、无结果缓存、A 无批量 | 代码 + 实跑 | 7–14 |
| G9a | ✅ 已修（Phase 3）：日志规范 + 脱敏 + `requestId` 贯通 + per-tool 指标 + `/api/mcp/stats` | `ToolInvocationLogger`、`ToolMetrics`、`McpStatsController` | 7, 8 |
| G9b | 仍缺：限流、结果缓存、A 批量 | — | 9–14 |

---

## 2. 核心设计决策（先定，否则后 14 项各写各的）

**D1 · `ToolSpec` 成为接口契约的唯一真源。**
文档、JSON Schema、CLI help、参数校验、缓存键、限流策略、指标标签、契约测试用例，**全部由它生成**。这直接满足需求 1「不再依赖 WebDriver 文档」：`@MCP` 扫描只作为 tab 域的**引导值**，其文档字段必须被仓库内显式 spec 覆盖，CI 用 lint 兜底。

```kotlin
data class ToolSpec(
    // —— 既有 ——
    val domain: String, val method: String,
    val arguments: List<Arg>, val returnType: String,
    val description: String?, val help: String?, val cliName: String?,
    // —— 新增 ——
    val outputSchema: JsonNode? = null,          // 需求 6
    val examples: List<ToolExample> = emptyList(), // 需求 2
    val errorCodes: Set<ToolErrorCode> = emptySet(), // 需求 4
    val cache: CachePolicy? = null,              // 需求 10/13
    val rateLimit: RateLimitPolicy? = null,      // 需求 9
    val async: AsyncPolicy? = null,              // 需求 11
    val batchable: Boolean = true,               // 需求 12
    val sensitiveArgs: Set<String> = emptySet(), // 需求 7
)

data class Arg(
    val name: String, val type: String, val defaultValue: String? = null,
    val description: String? = null,             // 需求 1：取代“描述=参数名”
    val constraints: ArgConstraints? = null,     // 需求 5：enum/min/max/pattern/长度
    val sensitive: Boolean = false,              // 需求 7
)
```

**D2 · 共享内核，两通道只做适配。**
在 `browser4-agentic` 落 6 个共享组件，A（`Browser4MCPServer`）与 B（`MCPToolController`）都调用，禁止各写一份：
`ToolSpecValidator`（5）、`ToolResultValidator`（6）、`ToolInvocationLogger`（7）、`ToolMetrics`（8/14）、`ToolRateLimiter`（9）、`ToolResultCache`（10/13）、`BatchExecutor`（12）。
契约测试断言「同一输入 → 两通道同一输出/同一错误码」，直接封堵 G4 类漂移。

**D3 · 兼容优先的落地顺序。**
错误码/日志/校验先以「附加信息」形式上线（`ERROR: [CODE] msg` 保留旧前缀、JSON-RPC 结构不变），破坏性变更（未知参数从"静默丢弃"改"报错"、输出校验从 warn 改 fail）放在各自阶段的最后一步，并配 feature flag。

---

## 3. 阶段与需求映射

### Phase 0 · 前置止血（已完成，见 §1 摘要）

| 任务 | 对应缺口 | 状态 |
|---|---|---|
| 0.1 修 schema 参数描述与默认值 | G1 | ✅ |
| 0.2 修 `ToolSpec.expression` + 重生成快照（含 `extractInterface` 空默认值） | G2 | ✅ |
| 0.3 `ToolTargetResolver` + `requiresReceiver` + command 派发 | G3 | ✅ |
| 0.4 未知参数策略（A 与 B 对齐：声明参数转换 + 其余透传） | G4 | ✅（严格校验/未知参数报错留给 Phase 2 的 `mcp.strictArgs`） |
| 0.5 修 B 的工具列表缓存 | G5 | ⏳ 未做（Phase 4 缓存项一并处理） |
| 0.6 spec lint | G1/G2/G6 | ✅ |

### Phase 1 · 文档与示例（需求 1、2；主体已完成 2026-09-13，提交 `c639994e29`）

**已落地**
- `ToolSpecGenerator` 从镜像 `WebDriver.kt` 的 KDoc 提取 `@param` 说明（≈170 条）写入 `Arg.description`，并把 KDoc 代码块提取为 `ToolSpec.examples`；生成的 spec 快照同时携带两者，JAR 离线回退时文档依旧完整。
- `ToolDocGenerator` 生成 `docs/mcp-tools.md`（137 工具 / 9 域：签名、参数表含类型/必填/默认/含义、返回值、示例、全文）与 `docs/mcp-tools.json`（机器可读）；`ToolDocGeneratorTest` 作为漂移门禁（重生成必须零 diff）。
- `help` 输出统一为「签名 + 参数含义 + 示例 + 作者补充」，tab 域不再只回 KDoc 散文；`Server.instructions` 明确指引 `help` / `skill_doc`。
- 新增 `ToolExample`；**可执行示例**（带 args）会写进 MCP 工具描述（`tools/list` 唯一能携带示例的位置）。已authoring：crawl（3）、command（3）、webdb（2）、skill 管理（5）；tab 域为 KDoc 文档片段。
- 量化结果：lint 文档告警 **175 → 26**（其中 13 条为合法重载提示），真实缺口 12 条参数缺说明 + 1 条缺 help。

**剩余**
- tab 域 117 个生成 spec 的**可执行示例覆盖 0/117**（lint 已在报告该指标）——按需为高频工具（navigate/click/fill/press/screenshot/ariaSnapshot/query…）补 args 示例。
- 需求 2.3 CLI 侧 `--help --examples`（`help.rs`/`tips.rs`）未做。
- 需求 1.1 的「CI 比对显式覆盖集 == @MCP 扫描集」未做（当前靠 KDoc 提取 + 快照 + 文档门禁保证一致性）。

**需求 1 — 优化接口文档，直接提供接口文档，不再依赖 WebDriver 文档**
- 1.1 全量 spec 补齐：tab 域 114 个 `@MCP` 方法的文档字段改为仓库内显式覆盖（新增 `WebDriverToolSpecOverrides.kt`），CI 比对"覆盖集 == 扫描集"。
- 1.2 `ToolDocGenerator`：从注册表生成
  - `docs/mcp-tools.md`（按域分节：签名、参数表含默认值/约束、返回值、错误码、示例、限流/缓存/异步语义）
  - `mcp-tools.json`（机器可读，供 CLI/IDE/网关消费），并入 `browser4-resources`
  - 生成物纳入版本控制，CI 校验"重生成无 diff"（防漂移）
- 1.3 `GET /mcp/tools/specs` 扩展为返回完整契约（含 help/examples/outputSchema/errorCodes），CLI 启动时消费，替代 `commands.rs` 里手写的 option 帮助。
- 1.4 `Server.instructions` 增加一句：`Call help {domain, method} for exact signatures; skill_doc {name} for bundled references.`
- **验收**：新接入的 MCP 客户端**只读 `tools/list` + `help`** 即可正确调用任一工具；文档生成无 diff；@MCP 扫描失败（JAR/CI）时文档依旧完整。

**需求 2 — 增加接口示例**
- 2.1 `ToolExample(title, args, expectedShape, notes)` 落到 spec；每域至少 3 个（含 1 个错误示例）。
- 2.2 三处呈现：JSON Schema `examples`、`help` 文本追加 `e.g.`、`mcp-tools.md` 的 Example 段。
- 2.3 CLI：`browser4-cli crawl submit --help --examples`（`help.rs`/`tips.rs` 同步）。
- **验收**：示例**可执行**——Phase 6 的契约测试直接以示例作为 happy-path 入参（文档漂移即测试失败）。

### Phase 2 · 校验与错误码（需求 4、5、6 全部完成 2026-09-13）

**4 / 5 已完成**（提交 `547f5c7e1e`）
- `ToolErrorCode`：14 个稳定码，每个带 `retryable` / `httpStatus` / `hint`；`ToolErrorMapper` 按异常类型 + 消息（含 cause 链）分类，且匹配执行器既有措辞 → 老失败路径无需改写即获得错误码。
- 呈现：A 在结果 `_meta` 给 `{errorCode, retryable, hint}`，文本为 `ERROR: [CODE] …`；B 在响应里加 `errorCode` 字段，文本前缀一致；`docs/mcp-tools.md` 生成错误码表。向后兼容（额外字段 + 相同前缀）。
- `ToolSpecValidator`：两通道分发**前**统一校验——缺必填（`MISSING_REQUIRED_ARG`，消息回显 Phase 1 的精确签名）、类型错（`INVALID_ARGUMENT`）、未声明参数（仅 `-Dmcp.strictArgs=true` 时报 `UNKNOWN_ARGUMENT`）。默认放行未声明参数是刻意的（执行器确实读取 crawl 的 `sql`/`urls`）。`-Dmcp.validateArgs=false` 可整体关闭。

**6 已完成**（提交 `fb94d30871`）
- `ToolSpec.outputSchema`（JSON Schema 文本）+ `ToolSpec.task`（`TaskPolicy`：status/result/cancel 工具 + 轮询间隔），已声明于 `crawl.submit`、`command.run`；`tools/list` 通过 MCP `Tool.outputSchema` 对外公布。
- 任务类工具在两通道都返回统一信封 `{taskId, status, pollAfterMs, statusTool, resultTool}` 作为 `structuredContent`，**文本仍是裸 task id**（不破坏 CLI/脚本）；本身返回 JSON 的工具（如 `experience_query`）也带结构化结果。
- `ToolResultValidator`：受控 JSON-Schema 子集（`type`/`required`/`properties`/`items`/`enum`）+ 任务信封校验，**不引第三方 schema 引擎**；违规按 JSON 路径记日志并按工具计数（`-Dmcp.validateResults=warn` 默认，`error` 使调用失败，测试用后者）。B 侧只报告不失败（载荷已产生）。
- 文档：参考文档新增每个工具的结果 schema 与长任务契约。

**本次发现的真实缺口（已记录，未静默绕过）**
- `crawl.status` / `crawl.result` 目前把 `CrawlResponse` **包装成文本** `{type, description}` 返回，而非 JSON → 在结果渲染器改为输出 JSON 之前无法为它们声明 outputSchema（否则每次调用都会校验失败）。这属于 Phase 6 的收口项。
- 未知工具名由 SDK 在协议层拒绝（`Tool x not found`），不走我们的 `UNKNOWN_TOOL` 码；该码用于「已注册但无法解析」的场景。

**需求 4 — 完善接口错误码**
- 4.1 `ToolErrorCode` 枚举（稳定字符串）：`INVALID_ARGUMENT`、`MISSING_REQUIRED_ARG`、`UNKNOWN_TOOL`、`UNKNOWN_ARGUMENT`、`SESSION_NOT_FOUND`、`SESSION_UNHEALTHY`、`TARGET_UNAVAILABLE`（G3 场景）、`RATE_LIMITED`（9）、`TIMEOUT`、`CDP_ERROR`、`UPSTREAM_ERROR`、`CONFLICT`、`CANCELLED`（11）、`INTERNAL`；每码带 `retryable`、`httpStatus`、`hint`。
- 4.2 异常 → 码映射表（`TcException`/`IllegalArgumentException`/超时/限流/目标缺失），A 与 B 共用。
- 4.3 呈现：tool result `_meta.errorCode` + 文本前缀 `[CODE]`（保留 `ERROR:` 兼容）；协议级失败映射到 JSON-RPC `error.code`；REST 端点返回真实 HTTP 状态码（不再恒 200）。
- **验收**：契约测试对每条失败路径断言 `errorCode`；`docs/mcp-tools.md` 列出全部码；CLI 对 `RATE_LIMITED`/`SESSION_UNHEALTHY` 给可执行提示。

**需求 5 — 增加接口参数校验**
- 5.1 `ToolSpecValidator`：required/default/type/enum/range/pattern/长度/未知参数策略；在**分发前**统一执行（两通道同一份）。
- 5.2 校验失败不进入 executor（保证幂等重试安全），错误码 `INVALID_ARGUMENT`/`MISSING_REQUIRED_ARG`/`UNKNOWN_ARGUMENT`，并回显**期望签名**（来自 D1，正好用上 Phase 1 的文档）。
- 5.3 破坏性切换放最后：未知参数从 warn → error，配 `mcp.strictArgs` flag（灰度一周）。
- **验收**：同一非法输入在 A/B 返回同码同文案；`crawl_submit {url}`（缺 depth）成功用默认值、`{depth:"abc"}` 报 `INVALID_ARGUMENT`；未声明的 `sql/urls` 合法化（G4 关闭）。

**需求 6 — 增加接口返回值校验**
- 6.1 `ToolSpec.outputSchema`（JSON Schema）落地，并填入 MCP `Tool.outputSchema`，成功响应同时返回 `structuredContent`（SDK 0.15 支持）以保留类型。
- 6.2 `ToolResultValidator`：dev/test **fail loud**，prod 首版 warn + 指标 `tool.result.schema_violation`，观察一周后按域转 fail。
- 6.3 统一结果信封：任务类工具固定 `{taskId, status, pollAfterMs, statusTool}`（与需求 11 对齐）。
- **验收**：一次全量契约测试后 `schema_violation == 0`；任务类工具信封一致。

### Phase 3 · 可观测（需求 7、8；代码完成 2026-09-14）

**已落地**
- **需求 7（日志）**
  - `ToolInvocationLogger`（`browser4-agentic/.../agentic/tools/`）：一次调用恰好两条结构化日志——入口 `tool.call start requestId=… channel=A|B tool=… session=… args=[…]`，出口 `tool.call done|failed … durationMs=… outcome=… retryable=… resultChars=…`。A（标准 MCP server）与 B（`/mcp/call-tool`）调用**同一份**渲染器，字段顺序、措辞、脱敏规则完全一致。
  - 脱敏：名字命中 `token/password/secret/apikey/authorization/cookie/credential/session/state/content/file/path/signature/private/key`（大小写无关、子串匹配）→ `***`；字符串超过 48 字符 → `len=N sha256=<前8位>`；集合 → `list(size=N)`，Map → `map(keys=…)`。**参数正文永不入 INFO 日志**。
  - `requestId` 贯通：A 由服务端生成并回填 `result._meta.requestId`；B 生成并回 `X-Request-Id` 响应头。两边都写入 MDC（`requestId`），嵌套日志同 id。
  - 异常路径也收口：两通道的调度都包了 `catch (Throwable)` 兜底，失败也写日志、记指标、复位 in-flight 计数，然后原样抛出。
- **需求 8（监控）**
  - 复用仓库既有的 `agentic/observability/ToolMetrics`（此前**从未被生产代码调用**）：`tool.calls.total`、`tool.calls.success|failure[.by.name]`、`tool.errors.by.code{tool_name,error_code}`、`tool.execution.duration[.by.name]{tool_name}`（`publishPercentiles(0.5,0.95,0.99)`）、`tool.active.calls` gauge、`tool.validation.failures[.by.type]`。标签基数有界：`tool_name` 是启动时注册的闭集，`error_code` 是 14 值枚举，绝不写入原始消息/会话号/参数值。
  - 删除了重构期误建的第二套指标（`agentic/tools/ToolMetrics.kt` + `rest/config/McpMicrometerBridge.kt`），避免同名双注册；`ToolMetrics.recordToolCall` 增加 `errorCode` 维度。
  - `ToolMetrics.bindTo(MeterRegistry)`：Spring 启动时（`rest/config/McpToolMetricsConfiguration`）把指标**重绑到应用自己的注册表**，否则 `/actuator/metrics` 只能看到 JVM 指标而看不到 `tool.*`。没有 `MeterRegistry` bean 的瘦部署自动退回独立注册表（`/api/mcp/stats` 依旧可用）。
  - `GET /api/mcp/stats?top=N`（`rest/api/controller/McpStatsController`）：`totalCalls / totalFailures / failureRate / inflight / distinctTools / validationFailures / resultSchemaViolations / errorCodes / registry / prometheus / slowest / tools`；每个工具给 `calls / failures / successRate / p50Ms / p95Ms / p99Ms / maxMs / errorCodes`，未调用过的工具延迟为 `null` 而不是误导性的 `0`。用 Micrometer 非废弃 API（`takeSnapshot().percentileValues()`）。
  - `MetricsConfig.scrape()/close()` 改为容错：`micrometer-registry-prometheus` 是可选的，缺类时 `is PrometheusMeterRegistry` 会抛 `NoClassDefFoundError`，现以 `runCatching` 兜底，`scrapeOf(registry)` / `isPrometheus(registry)` 供 stats 端点判断。
- **测试**：`ToolInvocationLoggerTest`（脱敏/截断/集合摘要/id 唯一）、`observability/ToolMetricsTest`（按名计数、错误码维度、成功调用不写错误码、per-tool 延迟表、gauge 注册）、`McpStatsControllerTest`（真实数据、错误码归因、字段齐全、top 边界）、`McpToolMetricsConfigurationTest`（有/无 Spring 注册表两条路径）。

**实跑验收中发现并修复的 P0（需求 5 校验引入的回归，提交 `9e4b4cbeb3`）**
- 现象：A 通道 `navigate {"url": "https://example.com"}` 返回 `ERROR: [MISSING_REQUIRED_ARG] missing required argument 'entry' for tab.navigate(entry: NavigateEntry)` —— 最基础的导航在标准 server 上不可用；B 通道同样调用却通过（B 的字段名前缀归一化后未命中该校验）。
- 根因：`ToolSpecGenerator` 镜像上游 `WebDriver.kt` 时，**同名重载取最后一个**。`tab.navigate` 的最后一个重载是 `navigate(entry: NavigateEntry)`，于是 schema 对外声明了一个 MCP 客户端**根本无法构造**的对象参数；而 `BrowserTabToolExecutor.callFunctionOn` 真正读的是 `url`（或 `rawUrl`+`pageUrl`）。Phase 0.4 的「未声明参数原样透传」让这个错配一直隐形，Phase 2 的必填校验把它变成硬失败。
- 影响面（实测 117 个生成 spec 中 10 个方法）：`navigate(NavigateEntry)`、`screenshot(RectD)`、`ariaSnapshot(AriaSnapshotOptions)`、`delay(Duration)`、`waitForPage/waitForFunction/waitForNavigation/waitForSelector(timeout: Duration, action: suspend ())`。
- 修复：在 `BrowserTabToolExecutor` 为这 8 个方法写**显式 spec**（与 `when (functionName)` 分支里的真实参数名一致：`url` / `selector`+`timeoutMillis` / `oldUrl` / `pageUrl` / `pageFunction` / `millis` / `selector|fullPage|viewport` / ariaSnapshot 的 8 个扁平选项），并补文档；`docs/mcp-tools.md` / `.json` 重新生成（137 工具）。
- 防复发：`ToolSpecLint` 新增规则「必填参数类型必须是 JSON 可表达类型」，`ToolSpecLintTest.advertisedSpecsAreCallable` 对**实际公告的 spec 集合**做零容忍断言。原始生成 spec 里仍有 13 处（10 方法）非 JSON 参数，作为 WARNING 计数（`117 specs / 39 warnings`）——它们已被显式覆盖，但生成器层面的「重载择优选 JSON 友好签名」尚未做，属 Phase 1 的 1.1 收口项。
- 实跑证据：修复后 A `navigate{"url"}` → `title` = `Example Domain`；日志 `tool.call start/done requestId=A-c3d2ab48-0001 tool=navigate durationMs=2456 outcome=OK`。

**需求 8.4 — SLO 与告警阈值（文档化，Prometheus 规则可直接抄）**

| 指标 | SLO | 告警阈值 | 处置 |
|---|---|---|---|
| `tool.execution.duration.by.name` p95 | 浏览器动作 < 3s；`crawl_*`/`swarm_*`/`command_*` 提交 < 1.5s | 持续 5 分钟 p95 > 3s | 看 `slowest` TopN；多半是 CDP 阻塞或页面重试 |
| `tool.calls.failure / tool.calls.total` | < 1% | 5 分钟窗口 > 5% | 按 `tool.errors.by.code` 拆分 |
| `tool.errors.by.code{error_code="TARGET_UNAVAILABLE"}` | = 0 | > 0 即告警 | 域已注册但无绑定 receiver（G3 哨兵） |
| `tool.errors.by.code{error_code="INTERNAL"}` | = 0 | > 0 即告警 | 拿 `requestId` 去日志里 grep 两条 `tool.call` 行 |
| `tool.errors.by.code{error_code="SESSION_UNHEALTHY"}` | < 0.5% | > 1% | 浏览器崩溃/被杀，检查会话回收 |
| `tool.active.calls` | < 16 | 持续 5 分钟 > 32 | 长任务堆积，看 Phase 4 背压 |
| `tool.validation.failures.by.type` | < 2% | 5 分钟 > 10% | 客户端契约漂移，回看 `docs/mcp-tools.md` 签名 |
| 结果 schema 违规（`/api/mcp/stats` 的 `resultSchemaViolations`） | = 0 | > 0 即告警 | 工具返回值与 `outputSchema` 不一致（需求 6） |

告警标签一律带 `tool_name` + `error_code`，**不要**用 `requestId`/`sessionId` 当标签（高基数）。

**B 通道校验影子模式（2026-09-14，需求 3/5 的前置）**
- 背景：B 此前只校验「自定义域」工具（`CustomToolRegistry` 里的 spec），`tab_*`/`browser_*` 等内置域完全不过校验——同一非法输入 A 拒绝、B 放行。
- 做法：新增 `-Dmcp.validateBuiltinArgs=shadow|error|off`，默认 **shadow**。内置域的 spec 由**已存在的会话**解析（`liveSpecOf`，绝不为校验而开会话），命中违规时只记结构化 WARNING + 计数器，**不改调用结果**；插件/业务域（仓库内自著的 spec）照旧强制拒绝；`error` 才与 A 同码拒绝。
- 为什么不是直接强制：内置 spec 镜像上游 `WebDriver` 接口，错配是**我们的**问题而不是客户端的问题（`navigate` 已经证明过一次）。先观测、后拒绝，与需求 9 的灰度策略一致。
- 观测口径：日志 `mcp.validation.shadow channel=… tool=… spec=… codes=… args=… details=…`；指标 `tool.validation.shadow.violations{tool_name,validation_type}`（与「已拒绝」的 `tool.validation.failures` **分开计数**，避免把放行的调用算成失败）；`/api/mcp/stats` 新增 `validationShadowViolations`（总量 + 每工具）。
- **上线 20 分钟就抓到 3 个真 P0**（A 通道当时正在硬拒绝）：
  | 工具 | 曾公告的签名 | 执行器实际读取 | 影响 |
  |---|---|---|---|
  | `tab.click` | `click(selector, modifier)`，`modifier` 必填 | `selector` + 可选 `count`/`modifier`/`button`/`autoDismissDialogs` | 最常用的点击在 A 上不可用 |
  | `tab.dblclick` | 同上 | `selector` + 可选 `modifier` | 同上 |
  | `tab.evaluateValue`（别名 `browser_evaluate`，即 CLI `eval`） | `(selector, functionDeclaration)` 两者必填 | `expression`，或 `selector`+`functionDeclaration` | CLI 的 `eval` 在 A 上不可用 |
  三者在 `BrowserTabToolExecutor` 补显式契约后，实测 A：`navigate`/`click`/`dblclick`/`eval`/`evaluate_value`/`aria_snapshot`/`screenshot` 全部成功返回。
- 27 个高频 tab 工具重扫：**零**新增 shadow 违规；`/api/mcp/stats` 实测 `validationShadowViolations=5`（来自刻意的空参数探测）、`validationFailures=0`（未把放行调用算成失败）。
- 收口条件：把 `-Dmcp.validateBuiltinArgs=error` 作为 A/B 同码的目标状态，前提是连续观察期内 shadow 计数为 0。

**同一轮影子扫描暴露的第二类问题：合法调用被「过严」的 spec 拒绝（已修）**
- 机制：`ToolSpec.Arg(name, "String", null)` 里第三个参数是 `defaultValue`，**`null` 表示必填**（Phase 0 修好的语义）；「可选但无默认值」必须写成 `Arg(name, "String?", "null")`。部分手写 spec 用了前一种写法，而执行器读的是 `required = false`，于是校验层把执行器本来能处理的调用挡在门外——两个通道都挡（自定义域在 B 是强制校验的）。
- 实跑证据（B/A 都返回 `MISSING_REQUIRED_ARG`）：`experience_list` 要求 `filter`+`intent_filter`（执行器 `handleList` 两个都是 `required = false`）；`memory_search` 要求 `agent`；`memory_read` 要求 `seq`。
- 修复后实测（两通道）：`experience_list {}` → `{"total":0,…,"entries":[]}`、`memory_search {query}` → hits、`memory_read {taskId}` → `{"taskId":"t1"}`。
- 回归护栏：`MemoryToolExecutorTest.optionalArgumentsAreNotRequired`、`ExperienceToolExecutorTest.listFiltersAreOptional` 直接对 spec 跑 `ToolSpecValidator`，把「最小参数调用必须合法」钉在测试里。
- **检测手段的边界**：影子模式只覆盖内置域；自定义域的同类问题只能靠「最小参数扫一遍全部工具、把 `MISSING_REQUIRED_ARG` 逐条与执行器源码对照」发现。这正是 Phase 6 契约矩阵（以 `examples` 作 happy-path 入参）要自动化的事。
- 顺带澄清一处易混：异步信封给的是 `taskId` **值**，而状态工具的**参数名**由各自 schema 决定（`crawl.status(id)` / `command.status(id)` 用 `id`，`memory.read(taskId)` 用 `taskId`）——客户端应读 schema 而不是照抄信封字段名。已写入 `docs/mcp-tools.md` 的说明段。

**已知缺口（诚实记录）**
- **B 通道只校验「自定义域」工具**：`MCPToolController.validateArguments` 取的是 `CustomToolRegistry` 执行器的 spec，因此 `tab_*`/`browser_*` 等内置域在 B 上曾完全不过校验——同一非法输入 A 拒绝、B 放行，需求 3/5 的「两通道同码」尚未真正成立。**已通过影子模式解决观测问题（见上）**，但默认仍是「观测不拒绝」：把 `-Dmcp.validateBuiltinArgs=error` 打开才是 B 与 A 同码的完成态，需等 shadow 计数连续为 0。
- 运行时包内**没有** `micrometer-registry-prometheus`（在 `browser4-agentic/pom.xml` 里是 `optional`）→ 线上 `/actuator/prometheus` 返回 404，本轮以 `/api/mcp/stats` + `/actuator/metrics`（实测 `tool.*` 10 个指标可见）作为查询面。需要 Prometheus 抓取时把该依赖以非 optional 引入运行时包即可，代码侧无需改动（`MetricsConfig.isPrometheus` 会自动转为 `true`）。
- 需求 8.2 的 **OTel span**（`mcp.tool.call` → `agent.execute` → `cdp.*`）本轮未做；现有 `agentic` 侧 OTel 依赖仍是可选的，接入前先确认 bundle 是否携带 OTel SDK。
- `session.active` / `async.queue.depth` 指标未加（依赖 Phase 4/5 的队列实现）。

**需求 7 — 增加接口日志记录**
- 7.1 `ToolInvocationLogger`：一进一出两条结构化日志，字段 `requestId / channel(A|B) / tool / domain.method / sessionId / durationMs / outcomeCode / cached / stepIndex`。（`cached`/`stepIndex` 待 Phase 4/5 引入后追加）
- 7.2 脱敏与截断：`sensitiveArgs`（cookie/token/password/apiKey/storage-state/文件内容）→ `***`；大 payload 只记长度与 sha256 前 8 位。
- 7.3 `requestId` 贯通：B 已有 `X-Request-Id`，A 从 `_meta`/`Mcp-Request-Id` 取或生成，写入 MDC 供排障关联。
- **验收**：A/B 日志结构一致；脱敏单测；INFO 日志中不出现参数正文；同一 requestId 可从入口追到 CDP 调用。

**需求 8 — 增加接口监控**
- 8.1 Micrometer 指标（A/B 共用注册表）：`tool.calls{tool,domain,outcome}`、`tool.duration{tool}`(p50/p95/p99)、`tool.inflight`、`tool.errors{code}`、`session.active`、`async.queue.depth`。
- 8.2 OTel span（browser4-agentic 已有可选 OTel 依赖）：`mcp.tool.call` → 子 span `agent.execute` / `cdp.*`；属性含 domain/method/sessionId/outcomeCode。
- 8.3 `GET /api/mcp/stats`（按工具/域的汇总 + TopN 慢调用），`/actuator/metrics` 直出。
- 8.4 告警阈值文档化：p95 > 3s、错误率 > 5%、`TARGET_UNAVAILABLE` > 0（G3 未修完时的哨兵）。
- **验收**：契约测试断言计数器递增；stats 端点返回真实数据；SLO 文档入库。

### Phase 4 · 保护与性能（需求 9 已完成 2026-09-14；需求 10 进行中，10.3/G5 已完成）

**需求 10 进展（10.3 = G5 已修，提交 `ceaa98a691`）**
- `/mcp/tools` 拆成两段：**静态段**（会话生命周期工具 + 前端别名 + 插件域）枚举一次即缓存；**会话段**（会话 agent 的 tab/system 工具）**每次请求合并**。读取会话段是只读的（`getAllSessions()` 不会创建会话），所以当初加缓存要避免的「探活 → 建会话 → 启动浏览器 → 关闭」循环不会回来。
- 实测：`open_session` 之前 `83` 个工具，之后 `275` 个（新增 `navigate`/`click`/`reload`/`title`/`current_url`… 共 192 个）。
- 回归测试：`MCPToolControllerTest.the tool list grows when a session appears`（同一控制器实例，先无会话后有会话，断言静态段保留 + 会话工具出现）。
- **未做**：10.1 `ToolResultCache`（`(sessionId, tool, canonicalArgs, specVersion)` 键、TTL、`_meta.cached/ageMs`）、10.2 状态变更失效、10.4 逃生门与 `/api/mcp/cache/stats`。

**需求 9 — 限流（已完成）**
- `ToolRateLimiter`（`agentic/tools/`）：令牌桶，**两个作用域**——
  - `session:<sessionId|->:<domain>`：单会话速率 = 工具限额；
  - `global:<domain>`：聚合速率 = 工具限额 × `-Dmcp.rateLimit.globalMultiplier`（默认 4），防止多会话并发把后端打满。
  单会话先撞自己的桶，多会话则撞全局桶（实测 `scope=session:a:tab` / `scope=global:tab` 分别命中）。
- `ToolRateLimitPolicy`：限额**从域/方法推导**，不写进 137 份 spec——浏览器动作 10/s（burst 20）、任务提交类（`submit`/`run`/`start`…）0.2/s（burst 2）、本地工作（`coding`/`fs`/`system`）5/s（burst 10）、只读方法（`status`/`result`/`list`/`get`/`title`/`current…`）不限。`ToolSpec.rateLimit` 与 `-Dmcp.rateLimit.overrides="click=5/10;crawl=0.5/3;webdb=off"` 可覆盖（按工具名或域）。
- 灰度：`-Dmcp.rateLimit.mode=shadow|error|off`，默认 **shadow**（与校验一致：先观测、后拒绝；风险表里「限流默认值误伤批处理」的对策）。
- 语义：**校验之后、分发之前**取令牌——非法调用不花令牌，被限流的调用在浏览器上什么都没发生（重试幂等）。被拒绝时返回 `RATE_LIMITED` + `retryAfterMs`（A 在 `_meta.retryAfterMs`，B 在响应体 `retryAfterMs` 字段），文案由 `Decision.rejectionMessage()` 统一生成。
- 指标：`tool.rate.limits{tool_name,kind=rejected|shadow}`、`tool.rate.limits.by.scope{scope_type,kind}`；`/api/mcp/stats` 新增 `rateLimit{mode,rejected,shadowed}` 与每工具 `rateLimited`/`rateLimitShadowed`——「影子计数」与「真实拒绝」分开，避免把放行调用算成拒绝。
- 内存安全：桶按需创建，超过 512 个且空闲 10 分钟即回收；会话关闭可 `forgetSession`。实测 600 个会话后仍保持有界。
- 测试：`ToolRateLimiterTest`（12 项：限额推导/覆盖解析/burst→补充/被拒调用不花令牌/会话隔离与全局上限/三种模式/桶回收/线名），A 通道 `Browser4MCPServerTest`（拒绝+`_meta.retryAfterMs`+未分发；只读工具不限流），B 通道 `MCPToolControllerTest`（拒绝+`retryAfterMs`+未分发；shadow 不拒绝且计数）。
- 实跑证据（`-Dmcp.rateLimit.mode=error`）：
  - 默认档 40 并发 `browser_click` → 放行 16 / 拒绝 24，`ERROR: [RATE_LIMITED] rate limit exceeded for click (limit 10.0/s burst 20); retry after 45 ms`；日志 `tool.rate.limit tool=click scope=session:1b239abf…:tab outcome=REJECTED`。
  - `-Dmcp.rateLimit.overrides=click=0.5/2` → 第 1、2 次通过，第 3~5 次拒绝（`retryAfterMs` 407/356/332 递减），`/api/mcp/stats` 显示 `rateLimit{mode=error,rejected=3}` 且 `browser_click{rateLimited=3}` 与调用计数同名列（B 用客户端发来的工具名上报，别名不再各记一行）。
- 顺带修的既有问题：并发点击时 CDP 报 `No node with given id found`（DOM 节点 id 失效）原被归类 `INTERNAL`，现归 `CDP_ERROR`（retryable），客户端据此重试而不是当成服务端 bug。

**需求 9 遗留**：CLI 侧针对 `RATE_LIMITED` 的提示语（`help.rs`/`tips.rs`）未做；服务端已给出 `retryAfterMs` 与 `hint`。

**顺带发现的测试基础设施缺陷（已修）**：JUnit 5 会**静默跳过**返回非 `Unit` 的 `@Test` 方法。`= runBlocking { … }` 若最后一句是 `Mockito.verify(…)`（返回 mock）就会命中——全量跑出的告警显示 3 个 `BrowserTabToolExecutorTest` 的 frame 用例从未执行过，其中一个断言早已过时（期望 `frameSwitch requires 'frame'`，实际消息是 `Missing required parameter 'frame' for frameSwitch`）。已补齐 `Unit` 并顺手把断言改成校验稳定错误码（`MISSING_REQUIRED_ARG`），3 个用例恢复执行且全绿。**新增测试时务必确认方法返回 `Unit`**。

**需求 9 — 增加接口限流**
- 9.1 `ToolRateLimiter`：令牌桶，维度 `(sessionId, domain)` + 全局上限；策略来自 spec `rateLimit`，默认分档：浏览器动作 10/s（burst 20）、`crawl/swarm/command` 提交 0.2/s、`coding/fs` 5/s、只读状态类不限。
- 9.2 拒绝时返回 `RATE_LIMITED` + `retryAfterMs`（4.1 已定义），CLI 提示"稍后重试/降低批量并发"。
- 9.3 异步队列背压：队列超阈值直接拒绝而非无限堆积。
- **验收**：第 N+1 次调用返回 `RATE_LIMITED`；配置可覆盖；只读工具不受限。

**需求 10 — 增加接口缓存**
- 10.1 `ToolResultCache`：键 `(sessionId, tool, canonicalArgs, specVersion)`，仅对 spec 标记 `cacheable` 的幂等读（snapshot/current_url/title/query/readability/status）生效，TTL 由 spec 给；返回 `_meta.cached/ageMs`。
- 10.2 失效：同会话内任何**状态变更**工具（navigate/click/fill/press/scroll…）清空该会话读缓存；会话关闭即清。
- 10.3 复用并修 G5：`/mcp/tools` 只缓存静态段。
- 10.4 逃生门：`cache:false` 参数、`-Dmcp.cache.enabled=false`、`/api/mcp/cache/stats` 与手动清理端点。
- **验收**：重复同参只读调用第二次命中缓存；变更后立即失效；TTL 生效；缓存键含 specVersion（改文档即失效）。

**需求 11 — 增加接口异步处理**
- 11.1 统一异步契约：`<domain>_submit → {taskId,status,pollAfterMs,statusTool}` + `<domain>_status` + `<domain>_result` + `<domain>_cancel`；crawl/swarm/command/extract 现状已接近，补齐 `cancel` 与统一信封。
- 11.2 长任务进度：优先用 MCP 进度通知（SDK 支持即接 `notifications/progress`/`subscriptions/listen`），暂不可用时降级为 `status` 里的 `progress` 字段。
- 11.3 超时与租约：任务 TTL、取消传播（取消 → 断 CDP/停爬取）、僵尸任务回收（复用 `AsyncTaskCache` + 定时清理）。
- **验收**：e2e 提交 → 轮询 → 取消 全绿；取消后资源释放可观测；进度字段单调。

### Phase 5 · 批处理（需求 12、13、14；1.5 周）

**需求 12 — 增加接口批量处理**
- 12.1 把 B 的 `handleCommandBatch` 逻辑抽成共享 `BatchExecutor`（agentic），B 保留 `command_batch` 兼容名，A 新增 `batch_run` 工具（MCP 无批量原语，必须以工具暴露）。
- 12.2 语义统一：`steps:[{id?,tool,args}]`、`bail`、默认串行（浏览器状态敏感），只读工具可选 `concurrency≤4`；每步返回 `{id,ok,errorCode,durationMs,text}`；部分失败语义写入文档。
- **验收**：同一 `steps` 在 A(`batch_run`) 与 B(`command_batch`) 结果逐字段一致（契约测试）；`bail` 生效；step id 回显。

**需求 13 — 增加接口批量处理结果缓存**
- 13.1 批量结果缓存键 `hash(steps) + sessionId + specVersion`，仅当**所有步骤**均为 `cacheable` 时缓存；TTL 取步骤最小值。
- 13.2 回放/取回：`batch_get {batchId}`（缓存或历史）、`batch_replay`（仅幂等批次）；批次内每步复用需求 10 的单项缓存（避免重复劳动）。
- 13.3 容量治理：LRU + 最大条目/字节上限，淘汰指标上报。
- **验收**：同一只读批次第二次调用 `cached=true` 且耗时下降；含状态变更的批次**永不**缓存；上限触发淘汰且不泄漏。

**需求 14 — 增加接口批量处理结果监控**
- 14.1 批次指标：`batch.calls`、`batch.steps`、`batch.failures{code}`、`batch.bail`、`batch.duration`、`batch.cache.hit`、`batch.step.duration{stepIndex}`。
- 14.2 trace：批次一个父 span，每步一个子 span（含 `stepIndex/tool/outcome`），失败步标红并带 `errorCode`。
- 14.3 日志：每批次一条结构化汇总（步数/失败数/是否 bail/缓存命中/耗时分布）；`GET /api/mcp/batch/stats` 与慢批次 TopN。
- 14.4 告警：批次失败率 > 10% 或单步 p95 > 2s。
- **验收**：一次 5 步批次在 trace 中可见 1 父 5 子；指标与日志字段齐全；告警规则文档化。

### Phase 6 · 测试用例（需求 3；贯穿全程，最后 1 周收口）

- 6.1 **契约矩阵**（自动生成，覆盖 A 全部 248 + B 全部 83）：
  | 用例 | 输入 | 断言 |
  |---|---|---|
  | happy | spec.examples[0] | 成功 + outputSchema 通过 + 计数/日志产生 |
  | 缺必填 | 去掉 required 参数 | `MISSING_REQUIRED_ARG` |
  | 类型错 | `depth:"abc"` | `INVALID_ARGUMENT` + 期望签名回显 |
  | 未知参数 | 多传一个字段 | 按策略：`UNKNOWN_ARGUMENT` 或忽略 |
  | 会话错 | `sessionId:"nope"` | `SESSION_NOT_FOUND` |
  | 限流 | 连打 N+1 次 | `RATE_LIMITED` + `retryAfterMs` |
  | 缓存 | 同参两次只读 | 第二次 `cached=true` 且状态变更后失效 |
  | 批量 | 3 步含失败 | 逐字段与 B 一致 + `bail` 行为 |
- 6.2 分层落位（遵循 `docs/TESTING.md` tag 约定）：`Unit/Fast`（校验器、缓存、限流、文档生成、lint）进 PR 门禁；`RequiresBrowser/E2E`（真实 A HTTP + CLI e2e + 夹具）进 CI。
- 6.3 复用既有资产：`MCPToolControllerTest`(80)、`McpHttpServerE2ETest`(11)、`browser4-tests/browser4-rest-tests/MCPToolControllerE2ETest`、CLI `mock_server.rs` + `scenarios/*`、fixture `static/b4/*`。
- 6.4 新增门禁：`bin/test.ps1 mcp` 扩为 `mcp-contract`；文档 diff 校验；spec lint。
- **验收**：契约矩阵 100% 覆盖公布工具；PR 门禁 < 5 min；CI 全量绿。

---

## 4. 里程碑与工作量

| Phase | 内容 | 需求 | 人日 | 依赖 |
|---|---|---|---|---|
| 0 | 前置止血 + lint | —（G1–G6） | 5 | — |
| 1 | 文档 + 示例 | 1, 2 | 7 | P0 |
| 2 | 错误码 + 参数/返回值校验 | 4, 5, 6 | 8 | P0, P1（用文档回显签名） |
| 3 | 日志 + 监控 | 7, 8 | 5 | P2（错误码作为指标标签） |
| 4 | 限流 + 缓存 + 异步 | 9, 10, 11 | 10 | P2, P3 |
| 5 | 批量 + 批量缓存 + 批量监控 | 12, 13, 14 | 8 | P4（复用缓存/限流/指标） |
| 6 | 契约测试收口 | 3 | 5（与各阶段并行） | 全程 |
| **合计** | | | **≈ 43 人日（单人约 9 周）** | 关键路径 0→1→2→4→5 |

可两人并行：一人主 Phase 1/2（契约与校验），一人主 Phase 3/4（可观测与保护），Phase 5 合并。

---

## 5. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 改 schema/文档影响 LLM 提示与 CLI 解析 | 回归面大 | 生成物纳入版本控制 + 快照 diff 门禁；`skills/browser4-cli/*`、`help.rs`、`tips.rs`、README 同步（遵循仓库文档更新规则） |
| 严格参数校验/返回值校验打断现有调用 | 线上行为变化 | 灰度 flag（`mcp.strictArgs`、`mcp.validateResults`），先 warn 后 fail，指标驱动决策 |
| 缓存/限流引入"看不见的行为" | 难排障 | 响应 `_meta` 暴露 `cached/rateLimited/retryAfterMs`；两个逃生门；缓存键含 specVersion |
| G3（target 解析）改动跨 agentic/rest | 结构风险 | 复用 B 现成 receiver 解析逻辑，先做"无 target 不公告"的保守版，再补全绑定 |
| 异步/进度依赖 SDK 对 2026-07-28 tasks 扩展的支持 | 进度可能受阻 | 应用层 `taskId` 契约先行（今天可用），SDK 支持后再切协议级 |
| 契约测试需要真实浏览器 | CI 时长 | 按 tag 分层：PR 跑 Unit/Fast，CI 跑 RequiresBrowser；夹具复用 `MockBrowser4Server` |
| 限流默认值不合实际工作负载 | 误伤批处理/爬取 | 默认保守 + 全量可配 + 首周只观测不拒绝（shadow 模式） |

---

## 6. 完成定义（Definition of Done）

- 需求 1：新客户端仅凭 `tools/list` + `help` 即可正确调用任一工具；`docs/mcp-tools.md` 生成无 diff；零 WebDriver 文档依赖（lint 兜底）。
- 需求 2：每工具 ≥1 可执行示例，且示例即契约测试入参。
- 需求 3：248(A)+83(B) 工具 × 8 类用例矩阵全绿，PR 门禁 <5 min。
- 需求 4：所有失败路径有稳定 `errorCode` + retryable 语义，A/B 一致。
- 需求 5/6：同一非法输入两通道同码；全量契约跑完 `schema_violation == 0`。
- 需求 7/8：日志结构化且脱敏；每工具指标 + trace 可见；`/api/mcp/stats` 有真实数据。（**代码 + 单测已完成**；OTel span 与运行时 Prometheus 抓取见 Phase 3「已知缺口」）
- 需求 9/10/11：限流可复现拒绝、缓存命中与失效可验证、长任务可轮询可取消。
- 需求 12/13/14：A/B 批量结果逐字段一致；幂等批次可缓存回放；批次指标/span/日志齐全。

---

## 7. 立即可开工的三件事（本周）

1. **Phase 0.1 + 0.2**（半天）：修参数描述与 `ToolSpec.expression`，重生成快照 —— 直接消灭 `help` 输出里的 `Arg(name=...)`，这是"接口文档"最刺眼的一处。
2. **Phase 0.3**（2 天）：`ToolTargetResolver`，让已公告的 26 个插件域工具中 15 个不再"看得见调不动"。
3. **Phase 0.6**（1 天）：`ToolSpecLintTest` 进 PR 门禁，防止后续 14 项改造中文档再次腐化。
