# MCP 接口层加固开发计划（需求 1–14）

> 日期：2026-09-13 · 分支基准：`feat/mcp-channel-parity`（已含 P0–P5 与 refreshTools 修复）
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
| G5 | B 的 `/mcp/tools` **首探即缓存**：`open_session` 后仍返回 83，会话相关工具面永不出现 | 实跑对比 | 10 |
| G6 | 别名/命名/渲染曾有三份副本漂移（已建 `McpToolNames`/`ToolResultTextRenderer`，仍有 2 个别名因缺 canonical spec 注册不上） | `browser_is_enabled`/`browser_dialog_status` 缺席 | 1, 3 |
| G7 | 错误是自由文本（`ERROR: xxx failed: ...`），无稳定错误码；HTTP 恒 200 | A/B 实测 | 4 |
| G8 | 无参数校验层/无返回值校验（`ToolSpec.returnType` 是字符串，未用于校验） | 代码 | 5, 6 |
| G9 | 无调用日志规范（B 把参数直接拼进 INFO）、无 per-tool 指标、无限流、无结果缓存、A 无批量 | 代码 + 实跑 | 7–14 |

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

### Phase 3 · 可观测（需求 7、8；1 周）

**需求 7 — 增加接口日志记录**
- 7.1 `ToolInvocationLogger`：一进一出两条结构化日志，字段 `requestId / channel(A|B) / tool / domain.method / sessionId / durationMs / outcomeCode / cached / stepIndex`。
- 7.2 脱敏与截断：`sensitiveArgs`（cookie/token/password/apiKey/storage-state/文件内容）→ `***`；大 payload 只记长度与 sha256 前 8 位。
- 7.3 `requestId` 贯通：B 已有 `X-Request-Id`，A 从 `_meta`/`Mcp-Request-Id` 取或生成，写入 MDC 供排障关联。
- **验收**：A/B 日志结构一致；脱敏单测；INFO 日志中不出现参数正文；同一 requestId 可从入口追到 CDP 调用。

**需求 8 — 增加接口监控**
- 8.1 Micrometer 指标（A/B 共用注册表）：`tool.calls{tool,domain,outcome}`、`tool.duration{tool}`(p50/p95/p99)、`tool.inflight`、`tool.errors{code}`、`session.active`、`async.queue.depth`。
- 8.2 OTel span（browser4-agentic 已有可选 OTel 依赖）：`mcp.tool.call` → 子 span `agent.execute` / `cdp.*`；属性含 domain/method/sessionId/outcomeCode。
- 8.3 `GET /api/mcp/stats`（按工具/域的汇总 + TopN 慢调用），`/actuator/metrics` 直出。
- 8.4 告警阈值文档化：p95 > 3s、错误率 > 5%、`TARGET_UNAVAILABLE` > 0（G3 未修完时的哨兵）。
- **验收**：契约测试断言计数器递增；stats 端点返回真实数据；SLO 文档入库。

### Phase 4 · 保护与性能（需求 9、10、11；2 周）

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
- 需求 7/8：日志结构化且脱敏；每工具指标 + trace 可见；`/api/mcp/stats` 有真实数据。
- 需求 9/10/11：限流可复现拒绝、缓存命中与失效可验证、长任务可轮询可取消。
- 需求 12/13/14：A/B 批量结果逐字段一致；幂等批次可缓存回放；批次指标/span/日志齐全。

---

## 7. 立即可开工的三件事（本周）

1. **Phase 0.1 + 0.2**（半天）：修参数描述与 `ToolSpec.expression`，重生成快照 —— 直接消灭 `help` 输出里的 `Arg(name=...)`，这是"接口文档"最刺眼的一处。
2. **Phase 0.3**（2 天）：`ToolTargetResolver`，让已公告的 26 个插件域工具中 15 个不再"看得见调不动"。
3. **Phase 0.6**（1 天）：`ToolSpecLintTest` 进 PR 门禁，防止后续 14 项改造中文档再次腐化。
