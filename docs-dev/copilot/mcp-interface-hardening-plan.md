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

### Phase 4 · 保护与性能（需求 9、10、11 均已完成 2026-09-14）

**需求 11 — 异步处理（已完成）**
- `TaskEnvelopes`（agentic）成为**唯一**信封与状态词表的来源：
  - 词表 `queued|running|done|failed|cancelled`，`normalise()` 把各域的状态词（`CREATED`/`OK`/`TIMEOUT`/`in_progress`/`Cancelled`…）映射进来，**未知状态一律 `failed`**（新状态不能看起来像健康）；
  - `SUBMIT_SCHEMA`（提交：`taskId/status/pollAfterMs/statusTool/resultTool/cancelTool`）与 `STATUS_SCHEMA`（轮询：再加 `progress/processed/total/elapsedMs/error`）；
  - `of(...)` 只在**有值**时写入字段（`null` 省略），`progress=0` 是真实测量、予以保留；`progress` 夹在 `0..1`。
- 三个域统一：
  - `crawl.submit/status/result/cancel`：`status`/`result` 现在返回 **JSON 信封**（此前返回对象 → 渲染成 `{type, description}` 文本，Phase 2 因此无法声明 `outputSchema`，这个缺口现已关闭）；`processed` = 已收页面数，`elapsedMs` 由 start/finish 计算；`crawl.cancel` 调 `CrawlService.cancel`；未知 id 直接终结为 `failed`（不再停在 `CREATED` 让客户端空转）。
  - `command.run/status/result/cancel`：`status`/`result` 包一层信封并保留域自身载荷（`result` 字段），`processed` = agent 已记录步数；`command.cancel` 调 `cancelAgentTask`，返回 `cancelled=true|false`（页面加载类任务无法中途打断，就如实报 false）。
- 11.3 超时/租约/僵尸回收：复用既有设施——`CrawlService` 每 5 分钟 `purgeExpiredTasks()`（默认 TTL 1 天）+ 磁盘恢复时跳过过期终态；`StatefulAgentRunner`/`StatefulPageVisitor` 自带 TTL 缓存（agent 任务 120 分钟）；取消经协程取消传播到 CDP/爬取。
- **实跑证据**（`b4-backend24/26.log`）：
  - `crawl_submit` → 文本裸 task id + `structuredContent {taskId,status:"running",pollAfterMs:1000,statusTool,resultTool,cancelTool}`；
  - `crawl_status` → `{"taskId":…,"status":"done","processed":0,"elapsedMs":39,"statusTool":"crawl_status","resultTool":"crawl_result","cancelTool":"crawl_cancel"}`；
  - 未知 id → `{"taskId":"does-not-exist","status":"failed","error":"Task not found: …"}`；已完成任务再 cancel → `cancelled=false` + `status=done`（如实报告，不报错）；
  - `command_run {command}`（不再要求 `noopLimit`/`engine`）→ task id + 信封；`command_status` → `running`；3 秒后 → `failed` 并带上真实原因（本机未配 LLM）；`command_cancel`/`command_result` 均返回同一信封。
- 测试：`TaskEnvelopesTest`（8：词表映射/终态集合/`null` 省略与 `0` 保留/夹取与空错误/两个 schema 解析且能拒绝越界状态），`CrawlToolExecutorTest`（7）与 `CommandToolExecutorTest`（7）覆盖 JSON 信封、cancel 语义、未知 id 终结、`outputSchema` 与实际产出对齐、TaskPolicy 完整。
- **本轮顺带修掉的真问题**
  1. `TaskEnvelopes` 里 `enum` 用 `${…joinToString { … }}` 拼在 raw string 中，模板被**静默截断**，生成的 schema 不是合法 JSON → 结果校验被**整体跳过**（正是「静默失效」那一类）。改为独立属性 + 新增断言 schema 可解析且枚举完整的测试。
  2. `command.run` 的 `noopLimit`/`engine` 被声明为必填而执行器按可选读取 → 校验层直接拒绝**任何** `command_run` 调用（实测复现 `MISSING_REQUIRED_ARG`）。已改为 `"Int?"/"String?"` + `"null"`。
  3. `CommandToolExecutor` 读 `sessionId` 却在自身 `validateArgs` 里不允许它 → 客户端带上 `sessionId` 就报 `Extraneous parameter 'sessionId'`。四个方法统一放行传输参数。
  4. B 通道**手写**了一份提交信封（漏了 `cancelTool`）→ 现改为复用 `ToolResultValidator.taskEnvelope`，并把 `cancelTool` 补进共享构建器；顺带修正 B 里 `pollAfterMs` 被转成字符串的问题（经 Jackson 往返，数字仍是数字）。
  5. `command.run`/`status`/`result` 补 `help`（lint 的文档告警相应减少）。

**需求 10 — 缓存（已完成）**
- `ToolCachePolicy`：**白名单**推导可缓存方法（缓存错东西就是正确性 bug，所以不做黑名单）——页面读 `tab.title/currentUrl/url/ariaSnapshot/exists/isVisible/isEnabled/isChecked/getText/getAttribute/dialogStatus/frameList` TTL 1s；任务状态 `crawl|command|swarm.status|result` TTL 500ms；**明确不缓存**：一切改页面的动作、大载荷（`html_snapshot_*`/`screenshot`/`pdf`，避免「带额外步骤的内存泄漏」）、以及带 `clear` 语义的 `consoleMessages`/`networkRequests`。`ToolSpec.cacheable`（三态：null=按策略/false=禁用/true=未知工具也能缓存）与 `cacheTtlMs`（0=禁用）可覆盖。
- `ToolResultCache`：键 `(sessionId, tool, canonicalArgs, specVersion)`——参数做**规范化**（排序、递归展开嵌套结构、剔除传输参数）后取 sha256 前 12 位，键长与载荷无关；`specVersion` 取 `expression|returnType|outputSchema` 的指纹，**改契约即失效**。
- **失效以状态变更为准，TTL 只是兜底**：任何不被策略视为幂等读的调用（点击/导航/输入/提交…）都会清空该会话的全部条目，**失败也清**（半途失败的点击同样可能动了页面）；会话关闭清该会话，`close_all_sessions`/`kill_all_sessions` 清全表。
- 逃生门（按调用者会想到的顺序）：单次调用 `cache:false`、部署级 `-Dmcp.cache.enabled=false`、工具级 `ToolSpec.cacheable`、运维级 `DELETE /api/mcp/cache`。
- 纯审计/观测：`tool.cache.access{tool_name,result}`、`tool.cache.invalidations`、`tool.cache.evictions`；`GET /api/mcp/cache/stats`（enabled/entries/hits/misses/hitRate/**defaultCacheableTools**）与 `DELETE /api/mcp/cache`；日志新增 `tool.cache hit tool=… ageMs=…`；`_meta.cached/ageMs`（B 为响应体 `cached`/`cacheAgeMs` 字段）。
- **实跑证据**（`b4-backend22.log`）：`title` 两次 → 第二次 `cached=True ageMs=45`；`cache:false` → 不走缓存且**调用成功**；随后的 `title` 得到刚刷新的值（`ageMs=111`）；`click` 之后的 `title` 不再命中；`/api/mcp/cache/stats` → `entries=1 hits=2 misses=2 hitRate=0.5`，默认可缓存工具 18 个；`DELETE /api/mcp/cache` → `cleared=true entries=0`。
- 测试：`ToolResultCacheTest`（13 项：白名单/覆盖/命中与年龄/TTL 过期/键含会话+工具+参数/参数规范化/失败不缓存/状态变更失效（含失败）/按会话隔离/`cache:false`/全局关闭/容量上限），A 通道 3 项、B 通道 3 项、stats 端点 2 项。
- **顺带修的真问题**：`cache:false` 原本会被**转发给执行器**，而执行器自带的 `validateArgs` 把它当 `Extraneous parameter` 拒绝——逃生门反而把要刷新的调用搞挂了。现在 `cache` 与 `sessionId` 一样属于**传输层参数**，在校验前剥离（A 的 `CONTROL_ARGS`、B 的 `normalizeToolArguments`），任何执行器都不会看到它。

**需求 10.3（G5）已完成**（提交 `ceaa98a691`）
- `/mcp/tools` 拆成两段：**静态段**（会话生命周期工具 + 前端别名 + 插件域）枚举一次即缓存；**会话段**（会话 agent 的 tab/system 工具）**每次请求合并**。读取会话段是只读的（`getAllSessions()` 不会创建会话），所以当初加缓存要避免的「探活 → 建会话 → 启动浏览器 → 关闭」循环不会回来。
- 实测：`open_session` 之前 `83` 个工具，之后 `275` 个（新增 `navigate`/`click`/`reload`/`title`/`current_url`… 共 192 个）。
- 回归测试：`MCPToolControllerTest.the tool list grows when a session appears`（同一控制器实例，先无会话后有会话，断言静态段保留 + 会话工具出现）。
- 10.1/10.2/10.4 见上方「需求 10 — 缓存（已完成）」，本阶段的 G5 只是其 10.3 一项。

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

### Phase 5 · 批处理（需求 12、13、14 均已完成 2026-09-14）

**需求 12 — 批量处理（已完成）**
- `BatchExecutor`（agentic）是**通道无关**的批处理内核：解析步骤、执行策略、每步信封、日志与指标都在这里，通道只提供 `stepRunner`（怎么真正跑一步）与 `readOnly`（哪些工具是只读的）。
- 步骤形状统一为 `[{id?, tool, args}]`，同时接受 CLI 的旧形状（`op`/`tool`/`arguments`）与 A 通道传结构化参数的约定（数组以 **JSON 文本** 形式送达 → `stepsArg()` 两种都解析）。
- **顺序保证**：默认**串行**（浏览器有状态，先点后读必须看到点击结果）；`concurrency>1` 仅在**全部步骤都是只读工具**时允许，否则显式拒绝并指出第一个会改状态的步骤（绝不静默重排）。
- 每步信封：`{index, id, tool, ok, durationMs, text?, errorCode?, cached?}`；批次信封：`{steps, failureCount, stoppedOnError, cached, cachedSteps, durationMs}`。被抛出的异常在步骤内被分类（`ToolErrorMapper`），与单次调用同一个错误码。
- A 新增 **`batch_run`**（MCP 无批量原语，必须以工具暴露）：`BatchToolExecutor` 通过 `ToolMount` 注册进 `CustomToolRegistry`，A（合并注册表）与 B（自定义域派发）都公告它；receiver 是会话的 `AgentToolManager`，`CustomToolTargets` 新增该分支并带「指定会话 → 任意活跃会话 → 通道默认会话」三级回退（A 自己的会话不在 REST 注册表里）。
- B 的 `command_batch` **保留兼容**：改走同一个 `BatchExecutor`，响应仍带 CLI 一直解析的字段（`sessionId`/`failureCount`/`stoppedOnError`/`results[].index|ok|durationMillis|text`），并**追加**共享字段（`id`/`tool`/`errorCode`/`cached`/`cachedSteps`/`durationMs`）。

**需求 13 — 批量缓存（已完成）**
- 只读批次第二次调用即命中缓存：实测 `cached=true, cachedSteps=2`，耗时 43ms → 5ms。
- 关键实现点：通道的缓存层在**派发器之上**，批处理步骤若直接走 `AgentToolManager` 就永远命不中。`BatchToolExecutor` 因此用**同一份缓存、同一套键与策略**做每步 get/put；而 `ToolCachePolicy` 新增 `selfManaged`（`batch.run`）概念——通道对这类工具**既不写也不失效**，否则批次结束后的一次失效会把刚刷新的读缓存全清掉。
- 含状态变更步骤的批次**永不缓存**，且该步骤本身会清空会话读缓存（实测：同样批次在 `click` 之后不再命中）。

**需求 14 — 批量监控（已完成）**
- 指标：`batch.calls` / `batch.calls.by.outcome{outcome}` / `batch.calls.bailed`、`batch.steps` / `batch.steps.by.kind{kind=requested|executed|failed|cached}`、`batch.duration` 计时器；每步自己的 `tool.*` 指标照常记录。
- 日志：`batch.run start steps=… bail=… concurrency=…`、`batch.step index=… id=… tool=… ok=… cached=… durationMs=…`、`batch.run done steps=… executed=… failures=… stoppedOnError=… cachedSteps=… durationMs=…`（`stepIndex` 落在批处理命名空间里，与通道的 `tool.call` 行通过 requestId/时间对应）。
- `GET /api/mcp/stats` 新增 `batch{calls,succeeded,failed,bailed,stepsRequested,stepsExecuted,stepsFailed,stepsCached}`。

**实跑证据**（`b4-backend27/28/30/31.log`）
- B 通道 `batch_run`（navigate+title+current_url）→ 逐字段信封；只读批次第二次 `cached=true cachedSteps=2`（39ms → 5ms）；`click` 之后同一批次不再命中；`concurrency=2` 混入 `click` → `ERROR: [INTERNAL] batch_run failed: concurrency=2 is only allowed for read-only batches; 1 step(s) can change state (e.g. 'click')`；未知工具 + `bail` → 单步 `errorCode=UNKNOWN_TOOL`、`stoppedOnError=true`。
- A 通道同一批次 → **完全相同的信封**，第二次同样 `cached=true cachedSteps=2`（43ms → 5ms）→ 需求 12 的「两通道逐字段一致」成立。
- 旧 `command_batch` → 兼容字段与新字段并存。
- `/api/mcp/stats` → `batch{calls:2, succeeded:2, stepsRequested:2, stepsExecuted:2, stepsCached:1}`。

**本轮顺带修掉的真问题**
1. `batch.calls`/`batch.steps` 既有无标签注册、又带标签注册 → Prometheus 直接拒绝该指标名（"requires that all meters with the same name have the same set of tag keys"）。已拆成「总数」与「`.by.*` 分组」两类固定标签的指标。
2. A 通道把结构化参数以 **JSON 文本** 下发（其既有约定），`batch_run` 只接受数组 → A 上批量完全不可用。`stepsArg()` 现在两种都解析（并补测试）。
3. A 通道自己的会话不在 REST 会话注册表里，`batch.run` 需要的 `AgentToolManager` 解析不到 → `CustomToolTargets` 增加三级回退，`McpHttpServerConfiguration` 把默认 agent 的 tool manager 传进去。
4. `ToolResultCacheTest` 里 `spec("batch","run")` 的断言写错（把 entries 减一当期望值），改为断言读缓存存活、批次不进缓存。

**Phase 5 遗留**：`tool.call` 行本身未带 `stepIndex`（批处理命名空间里有）；CLI 侧尚无 `batch` 子命令（服务端已可用）。

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

### Phase 6 · 测试用例（需求 3；契约矩阵已完成 2026-09-14）

**6.1 契约矩阵（已完成）**
- `ToolContractMatrixTest`（rest，`@Tag("Unit") @Tag("Fast")`）：对注册表里**每一个**公告工具跑 6 类通道无关用例，共 **140 工具 × 6 = 840** 项检查，外加 3 项全量断言：

  | 用例 | 输入 | 断言 |
  |---|---|---|
  | happy | 该 spec **自己的可执行示例**（没有示例则按类型合成必填入参） | 无违规 —— 文档即契约测试入参 |
  | 缺必填 | 去掉某个必填参数（跳过传输参数 `sessionId`/`cache`） | `MISSING_REQUIRED_ARG`，且消息**回显签名** |
  | 类型错 | 给数值/布尔参数传 `"abc"` | `INVALID_ARGUMENT` |
  | 未知参数 | 多传一个未声明字段 | 默认放行；strict 模式 `UNKNOWN_ARGUMENT` |
  | 传输参数 | 附带 `sessionId` / `cache` | 永不报为未知 |
  | 结果契约 | 声明的 `outputSchema` | 可解析为 JSON 对象且声明 `type=object` |
  | 命名与生命周期 | 名称唯一、`cliName` 空格形式、`task` 引用的 status/result/cancel 工具确实存在、无 `Arg(...)` 泄漏 | 全量断言 |

- 与既有资产的分工：**通道相关**的用例（真实 happy path、`SESSION_NOT_FOUND`、端到端限流、缓存回放、批量一致性）由 `Browser4MCPServerTest` / `MCPToolControllerTest` / `ToolRateLimiterTest` / `ToolResultCacheTest` / `BatchExecutorTest`+`BatchToolExecutorTest` 以及 e2e 承担；矩阵负责「公告出来的契约本身自洽且被同一个校验器执行」。
- 复用：`ToolRegistryFixture`（rest 测试源）统一提供「全部执行器 / 全部 spec」，`ToolDocGeneratorTest` 与矩阵共用同一份注册表视图，避免两处枚举漂移。

**需求 2 — 示例补齐（本轮进展 15/140 → 77/140）**
- `TabToolExamples`（agentic，独立文件，避免改动 2400 行的 tab 执行器结构）：为 **40 个**高频 tab 方法提供**可调用**示例（`navigate`/`click`/`fill`/`type`/`press`/`waitForSelector`/`evaluateValue`/`screenshot`/`ariaSnapshot`/`drag`/`mouseWheel`/`networkRoute`… 以及 `title`/`currentUrl`/`reload`/`pageSource`/`getCookies` 等无参读），由 `toolSpec.replaceExamples(...)` 覆盖生成 spec 的 KDoc 片段式示例。
- `ToolExample.runnable`（三态 `Boolean?`，默认 `null`，序列化时省略 → 快照零漂移）：无参工具的**空调用本身就是示例**，否则 `args = emptyMap()` 与「没写示例」无法区分。
- 其余补齐：`html_snapshot`（8）、`browser`（4）、`memory`（4）；`webdb_export` 示例补全必填 `outputDir`。
- **示例即契约测试**再次抓到 6 处「描述与校验相反」的真错配（矩阵报出、已修）：
  | 工具 | 声明为必填 | 实现 | 处理 |
  |---|---|---|---|
  | `browser.switchTab` / `closeTab` | `index` **和** `tabId` | 二者其一即可（`takeIf`/`nullable`） | 均改为可选并补说明 |
  | `memory.note` | `taskId` | `required = false` | 改可选 |
  | `html_snapshot.scrape` / `scrape_all` | `attrName` | 仅 `field=attr` 需要 | 改可选 |
  | `html_snapshot.query` | `url` | 「否则用当前页」 | 改可选 |
  | `html_snapshot.readability` | `url` | `required = false` | 改可选 |
- 矩阵新增**缺口清单**输出（按域统计缺示例的工具数），把「还要补多少」变成可读数字：当前 `tab 58 / experience 4 / skill 1`（tab 剩余为低频 setter/selector 家族）。
- **未做**：experience（4）与 skill（1）示例；tab 剩余 58 个低频方法。

**6.4 门禁（已完成）**
- `bin/test.ps1 mcp-contract` 新增（`mcp` 保持原样）：模块 `browser4-agentic + browser4-rest`，模式包含矩阵、文档漂移、lint、校验器、错误码、限流、缓存、批处理、任务信封、日志与指标、别名一致性。
- **实测：3 分 50 秒全绿**（agentic 98 项 + rest 12 项，含 `-am` 从源码构建 14 个模块），满足「PR 门禁 < 5 min」。
- 分层：本门禁全部是 `Unit/Fast`（无浏览器、无会话、无 LLM）；`RequiresBrowser/E2E` 仍由 CI 全量跑。

**矩阵首跑即抓到的真问题（均已修）**
1. `webdb_export` 的**已公布示例不可调用**（示例缺少必填的 `outputDir`）——「示例即契约」的第一次运行就命中。顺带修正其参数说明：执行器在缺失时是抛错的（`Missing required parameter 'urls'/'outputDir'`），原文却写着「omit 即导出全部 / 用服务端默认」，属**文档与实现相反**；现按实现改为「必填」并把示例补全。
2. `cache` 未列入 `ToolSpecValidator.DEFAULT_CONTEXT_ARGS`：两个通道都在校验前剥离它，但 strict 模式下若有路径没剥离就会被误报为未知参数。现已作为传输参数与 `sessionId` 同级（纵深防御）。
3. 矩阵自身两处期望写错（要求「必填工具 > 100」而实际 92；把 `sessionId` 当普通必填去删）——已修正为按传输参数语义断言。

**仍存的缺口（诚实记录）**
- **可执行示例覆盖 77/140**（本轮从 15 提升）：没有示例的工具，矩阵只能按类型合成入参，因此「声明为必填、执行器实际可选」这类错配（`experience_list`/`memory_search`/`command_run`/`webdb_export`/`browser.switchTab`/`html_snapshot.query` 都犯过）只有在写了示例的工具上才会被自动抓到——**本轮新增示例后立刻又抓到 6 处**。剩余 `tab 58`（低频 setter/selector 家族）+ `experience 4` + `skill 1`，矩阵的缺口清单会持续报数。
- 浏览器相关用例未进入 PR 门禁（按分层设计如此），依赖 CI 的 e2e 与实际浏览器。
- 6.3 里提到的 CLI `mock_server.rs`/`scenarios/*` 与 `browser4-tests/browser4-rest-tests` 未在本轮改造中扩展。

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
- 需求 2：每工具 ≥1 可执行示例，且示例即契约测试入参。（**现状 15/140**：矩阵已把「示例必须是可调用入参」变成断言，示例补齐后自动获得验收）
- 需求 3：工具 × 6 类**通道无关**用例矩阵全绿（140 × 6），通道相关用例（会话/限流/缓存/批量）由各阶段测试与 e2e 承担；`bin/test.ps1 mcp-contract` PR 门禁实测 3 分 50 秒 < 5 min。
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

---

## 8. 收尾轮记录（2026-09-15，接 `64576071be` 之后）

本轮把上一节列出的「仍存的缺口」逐条核实并做掉，全部以矩阵/门禁的实测数字收口：**142 工具 × 6 用例全绿，可执行示例 110/142，声明结果契约 10 个，lint 错误 0**。

### 8.1 修掉的真缺陷（本轮新增）

| # | 缺陷 | 证据 / 修法 |
|---|---|---|
| 1 | **`TabToolExamples` 的示例被显式 spec 覆盖而静默丢失**：`replaceExamples` 在构造函数里跑得太早（第 185 行），其后的显式 spec（第 186–652 行）整条替换掉 `toolSpec[...]`，写好的示例再也回不来 | 实测 7 个工具（`consoleMessages`/`saveStorageState`/`frameList`/`networkRequests`/`frameMain`/`consoleClear`/`harStop`）在文档里只剩 KDoc 片段。现移至 init 末尾 + `BrowserTabToolExecutorTest.everyWrittenTabExampleReachesTheSpec` 钉死（含「示例写到不存在的工具上」也要失败） |
| 2 | **两个「写了示例但没有工具」的死条目**：`getText`/`getAttribute` 从无 spec、也无别名，等价能力是 `selectFirstTextOrNull`/`selectFirstAttributeOrNull` | 由上一测试抓出，已删除条目而不是造两个新工具 |
| 3 | **`browser_is_enabled` / `browser_dialog_status` 解析不到工具**（G6 残留）：执行器 `when` 分支里有 `isEnabled`/`dialogStatus`，但镜像的 `WebDriver` 接口没声明，生成器因此没有 spec | 在 `BrowserTabToolExecutor` 补两条显式 spec；`ToolContractMatrixTest.frontendAliasesResolve` 现断言**每一条**前端别名都能解析到已公告工具（43 别名 → 0 孤儿） |
| 4 | **`experience` 域把可选参数声明成必填**（新一批示例抓到，属第 8 次同类）：`save` 的 `intent`/`task_type`/`facts`（执行器 `required = false`）、`query` 的 `intent` | 写 `experience_save` 示例后矩阵立刻报 `MISSING_REQUIRED_ARG`；按实现改为 `"String?"` + `"null"` |
| 5 | **`docs/mcp-tools.json` 丢掉 `runnable`**（机器可读契约的歧义）：`ToolDocGenerator` 手写 map 只输出 5 个字段，「无参调用本身即示例」与「压根没写示例」在 JSON 里都是 `args: {}` | JSON 增 `runnable`（三态，仅非 null 时输出）与派生字段 `executable`；Markdown 里无参示例渲染为 `- Page title: no arguments` 而不是空 bullet。新增 `ToolDocGeneratorTest.exampleProjectionIsLossless`（逐字段无损投影）与 `noArgumentExamplesAreRunnable` 两道门禁 |
| 6 | **B 通道批处理把参数正文写进 INFO 日志**（违反需求 7） | `MCPToolController.handleBatchTool` 原为 `logger.info("Calling batch tool step: $index $tool --k=v …")`；`batch.step`（结构化）已覆盖 index/tool/ok/耗时，故改为 DEBUG + `ToolInvocationLogger.renderArgs(...)`（脱敏/截断） |

### 8.2 需求完成度更新

- **需求 2（示例）**：**110/142**（本轮 77/140 → 110/142）。`experience`(4)、`skill`(1) 缺口清零；tab 剩余 **32** 个低频 setter/selector 家族。剩余缺口由矩阵按域持续报数。
- **需求 6（返回值校验）**：声明 `outputSchema` 的工具 **10** 个 —— 新增 `tab.dialogStatus`（`{pending,type,message}`，`type`/`message` 故意非必填，因为无驱动回退只答 `{pending:false}`）与 `batch.run`（schema 直接写在 `BatchExecutor.RESULT_SCHEMA`，紧邻它描述的 `BatchOutcome.toMap()`，避免两处漂移）。
- **需求 8（监控）**：`session.active`、`async.queue.depth` 两个 gauge **已落地**，取值由部署侧注入（`ToolMetrics.registerSessionCountSupplier` / `registerAsyncTaskCountSupplier`），并在 `bindTo` 时随 Spring 注册表重绑（否则 gauge 会继续写进没人抓取的独立注册表）。REST 侧 `McpToolMetricsConfiguration` 从 `PulsarSessionManager.getAllSessions().size` 与 `CrawlService.runningTaskCount()`（`jobStore` 只保留运行中的 job）取值；瘦部署缺 bean 时 gauge 不注册而不是谎报 0。
- **需求 7（日志）**：见 8.1 第 6 条。
- **需求 3/5（两通道同码）**：不变，B 侧内置域仍是 `shadow`，切 `error` 需等计数清零。

### 8.3 计划中两处经核实不成立的说法（已更正，不做无效改造）

- **「`tool.call` 行未带 `stepIndex`」**：两个通道的**批处理步骤根本不产生 `tool.call` 行** —— A 的步骤经 `BatchToolExecutor` 直接走 `AgentToolManager`，B 的步骤走 `executeAgentToolText`。可观测性由 `batch.step index/id/tool/ok/cached/durationMs` 承担，外层 `batch_run` 的 `tool.call` 带 `requestId`，两者已可对齐。因此不需要为 `tool.call` 增加 `stepIndex`（本轮改为修掉 B 的重复且未脱敏的 INFO 行）。
- **「CLI 侧尚无 `batch` 子命令」**：CLI 有 `batch`（`commands.rs:861`），只是走兼容名 `command_batch`（`http.rs:1015`），服务端已复用同一个 `BatchExecutor`；缺的是消费 `batch_run` 的新信封字段（`id`/`cached`/`cachedSteps`），属增强而非缺失。

### 8.4 门禁与 CI（核实结论）

- `bin/test.ps1 mcp-contract` 的 14 个测试类**已经**在 PR 门禁里执行：它们大多是未打标签的类（未打标签即不被 `excluded_groups` 排除），`ToolContractMatrixTest` 自带 `Unit/Fast`。因此**没有**在 `pr.yml` 里再加一步重复跑（会多花约 4 分钟），而是在 `pr.yml` 就地写明这条不变量，避免后人重复添加。
- 新增/强化的门禁：`exampleProjectionIsLossless`、`noArgumentExamplesAreRunnable`、`frontendAliasesResolve`、`everyWrittenTabExampleReachesTheSpec`、`dispatchedStateReadersAreAdvertised`、`supplierGaugesFollowTheSpringRegistry`。

### 8.5 本轮之后仍欠的事

1. tab 域 32 个低频方法的可执行示例（矩阵按域报数）。
2. 需求 6 的覆盖面：132 个工具仍无结果契约；下一步优先 JSON 信封类（`experience_query/list`、`memory_search/read`、`skill_list/info`）。
3. 需求 1.1 的「CI 比对显式覆盖集 == @MCP 扫描集」仍未做（现由 KDoc 提取 + 快照 + 文档漂移门禁 + lint 兜底）。
4. 需求 8.2 的 OTel span（`mcp.tool.call`）仍未接；`TracingUtils`/`OpenTelemetryConfig` 已有但生产路径无人调用。
5. `micrometer-registry-prometheus` 仍是 `optional`，`/actuator/prometheus` 需把它提为运行时依赖才可抓取（代码侧无需改动）。
6. CLI 侧 `--help --examples` 与 `RATE_LIMITED` 提示语**已由本轮并行实现**：`<cmd> --help --examples` 从 `/mcp/tools/specs` 拉取示例（后端不可达时退化为一行提示并 exit 0；无参示例渲染为 `no arguments`，片段示例渲染为代码块），失败提示按错误码给出可执行建议（`RATE_LIMITED` 带 `retryAfterMs`，`SESSION_UNHEALTHY`/`SESSION_NOT_FOUND` 各一条，`INTERNAL` 刻意静默）。`cargo test --bin browser4-cli` 实测 **1340 passed / 0 failed / 2 ignored**，`cargo build` 无告警。
