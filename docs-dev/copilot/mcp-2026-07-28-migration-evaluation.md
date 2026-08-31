# Browser4 迁移 MCP 2026-07-28 规范评估

> 日期：2026-08（基于 [官方博客](https://blog.modelcontextprotocol.io/posts/2026-07-28/) 与 [官方 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog.md) 评估）
> 范围：`cli/browser4-cli`（Rust CLI）、`browser4-rest`（MCPToolController）、`browser4-agentic`（Browser4MCPServer / McpHttpServer）

---

## 一、结论摘要

**可以迁，值得迁，但分为两个独立工作面，风险与收益差异很大：**

1. **标准 MCP server（8088 端口，Kotlin SDK）必须迁**。当前它使用 `kotlin-sdk 0.8.1`，声明的协议版本是 `2025-06-18`（落后两代），并且走的是 **HTTP+SSE 传输**（`GET /mcp/sse` + `POST /mcp/message?sessionId=`）——该传输在 2026-07-28 规范中已被正式标记为 **Deprecated（12 个月弃用窗口）**。这是最紧迫、风险最低的迁移：SDK 0.15.0（与规范同日发布）已提供 `mcpStatelessStreamableHttp` 单 POST 端点，`main` 分支已含 `server/discover`、请求级 `_meta`（`RequestMeta`）、`resultType`、`ttlMs`/`cacheScope` 等 2026-07-28 类型，只是尚未定版发布。

2. **CLI ↔ 后端的 `/mcp/call-tool` 自定义 RPC 是"名义上的 MCP"，不是标准 MCP**（不是 JSON-RPC 2.0、无协议版本协商、无标准传输头）。它不会被规范"破坏"，是否迁移到真正的 MCP 是**产品决策**而非合规义务。好消息是 2026-07-28 的 stateless 核心让这条路径比以往任何时候都简单（无握手、无会话头、请求自描述），坏消息是 batch 命令、分页元数据、插件 CLI 规格等自定义语义需要重新表达。

3. **关键外部依赖风险**：Kotlin SDK 不是 Tier 1 SDK（TS/Python/Go/C# 已支持 2026-07-28，Rust SDK 为 beta），其 `main` 分支默认协议版本仍是 `2025-11-25`，stateless 类型标着 `@ExperimentalMcpApi`。**完整 2026-07-28 合规需要等 kotlin-sdk 的下一个发布**（或先迁 0.15.0 的 stateless 传输 + 2025-11-25 协议，作为过渡）。

---

## 二、2026-07-28 规范核心变化

来源：[官方博客](https://blog.modelcontextprotocol.io/posts/2026-07-28/) · [官方 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog.md)（对比上一版 2025-11-25）

| 变化 | 内容 | 对 browser4 的影响 |
|---|---|---|
| **无握手、无会话**（SEP-2575/2567） | 移除 `initialize`/`initialized` 与 `Mcp-Session-Id` 头；每次请求在 `_meta` 携带 `io.modelcontextprotocol/protocolVersion`、`clientCapabilities`、`clientInfo`；版本不匹配返回 `UnsupportedProtocolVersionError`；可选新增 `server/discover` RPC | **高**：McpHttpServer 的会话表、createSession、SSE 端点全部要换 |
| **Stateless Streamable HTTP** | 单 `POST /mcp`（也可 `MCP-Protocol-Version` 头固定版本）；GET 流与 `Last-Event-ID` 恢复被移除；断流后客户端用新 id 重发 | **高**：传输层重写 |
| **Header 路由**（SEP-2243） | POST 必须带 `Mcp-Method`、`Mcp-Name` 头，网关可按头路由；body 与头不一致返回 `-32001`（新码 `-32020`）`HeaderMismatchError`；新增 `x-mcp-header` 参数扩展 | 中：服务端按 SDK 实现即可；CLI 若迁标准协议需带头 |
| **列表结果可缓存**（SEP-2549） | `tools/list`、`prompts/list`、`resources/list`、`resources/read`、`resources/templates/list` 结果必须带 `ttlMs` 与 `cacheScope`；`tools/list` 应确定性排序 | 中：SDK 层支持后自动获得 |
| **MRTR**（SEP-2322） | 服务端发起请求（`sampling/createMessage`、`elicitation/create`、`roots/list`）改为 `resultType: "input_required"` + `inputRequests`，客户端重试原请求并附 `inputResponses`；所有结果必须带 `resultType`（缺失视为 `"complete"`） | 低：browser4 不用 sampling/elicitation/roots |
| **`subscriptions/listen`**（SEP-2575） | 替代旧的 GET 通知流与 `resources/subscribe`/`unsubscribe`；客户端按类型订阅变更通知 | 低：browser4 未用资源订阅 |
| **移除 `ping`、`logging/setLevel`、`notifications/roots/list_changed`** | 日志级别改为请求 `_meta` 里的 `io.modelcontextprotocol/logLevel` | 低 |
| **错误码调整** | `-32001`→`-32020`（HeaderMismatch）、`-32003`→`-32021`、`-32004`→`-32022`；资源未找到 `-32002`→`-32602` | 低 |
| **弃用**（SEP-2577/2596） | Roots、Sampling、Logging 弃用；**HTTP+SSE 传输正式弃用**（一年 offramp）；`includeContext` 值弃用 | **中**：当前传输恰是被弃用的那个 |
| **Tasks 移至扩展**（SEP-2663） | `io.modelcontextprotocol/tasks` 扩展：轮询式 `tasks/get`、新增 `tasks/update`，变更通知走 `subscriptions/listen` | 低（browser4 未用 tasks） |
| **授权加固**（SEP-2468/837/2352） | `iss` 校验（RFC 9207）、DCR `application_type`、凭据绑定 issuer；DCR 弃用转向 CIMD | 低（browser4 无 OAuth 端点） |
| **SDK 生态** | TS/Python/Go/C# 已支持；Rust SDK beta；Kotlin SDK（社区维护）在途 | **关键风险** |

---

## 三、Browser4 当前 MCP 实现盘点

### 3.1 标准 MCP server —— `browser4-agentic` + Ktor（端口 8088）

- `Browser4MCPServer.kt`：基于 `io.modelcontextprotocol.kotlin.sdk`（`kotlin-sdk 0.8.1`，声明协议版本 **2025-06-18**），只注册 tools 能力，从 `AgentToolManager` 动态发现 executor/spec 生成工具；handler 用 `addTool(...) { request -> ... }` 单参 lambda。
- `McpHttpServer.kt`：Ktor CIO + SSE，`GET /mcp/sse` 建流 → 每个连接一个 `SseServerTransport`（`ConcurrentHashMap<sessionId, transport>`）→ `POST /mcp/message?sessionId=...` 路由 → `server.createSession(transport)`。
  - 这是 **HTTP+SSE 传输**（2025-03-26 时代形态），2026-07-28 正式弃用。
- `McpHttpServerConfiguration.kt`（browser4-rest）：`mcp.http.enabled=true`（默认）时在 `ApplicationReadyEvent` 后启动，绑定 8088；获取一个 `BasicBrowserAgent` 会话（即 MCP server 全局绑定单一浏览器会话）。

### 3.2 自定义 RPC —— `MCPToolController`（Spring，端口 8182，路径 `/mcp`）

- `POST /mcp/call-tool`，body `{"tool": "...", "arguments": {...}}`（**非 JSON-RPC 2.0**，无 id/method 信封）。
- `GET /mcp/tools`、`GET /mcp/tools/specs`（CLI 启动时发现插件声明的命名命令）。
- 会话管理：`sessionId` 作为参数传入（`open_session`/`close_session`/`list_sessions`/`close_all_sessions`/`kill_all_sessions`），`requireSessionId()` 强制。
- 扩展语义：batch 命令（`op` 数组）、`_pagination` 分页元数据、`_meta` 之外的错误包装等。

### 3.3 CLI 客户端 —— `cli/browser4-cli/src/http.rs`

- `call_tool_with_timeout()`：`POST {base}/mcp/call-tool`，body `{"tool", "arguments"}`；带按工具的 HTTP 超时（`effective_timeout`、wait 工具 +5s buffer）、`normalize_refs`。
- `daemon.rs`/`main.rs`：`GET /mcp/tools`、`GET /mcp/tools/specs` 做工具/插件发现；batch 由 `compile_batch_request()` 生成 `op` 数组。

### 3.4 测试

- `McpHttpServerE2ETest.kt`（browser4-agentic）：覆盖 initialize、工具调用、多调用/会话复用（SSE 路径）。
- `MCPToolControllerTest` / `MCPToolControllerE2ETest` / `ArgumentNormalizersTest` 等（browser4-rest）。

---

## 四、差距分析

| 2026-07-28 要求 | browser4 现状 | 差距 |
|---|---|---|
| 协议版本 `2026-07-28` | SDK 0.8.1 声明 `2025-06-18` | **两代落后** |
| 无 `initialize` 握手 | `createSession` 走完整握手 | 高（SDK 升级后由 SDK 处理） |
| 无 `Mcp-Session-Id` | SSE 会话表 + `sessionId` query param | **高（传输层）** |
| 单 `POST /mcp`、无 GET 流 | `GET /mcp/sse` + `POST /mcp/message` | **高（传输层）** |
| `Mcp-Method`/`Mcp-Name` 头 | 无（SDK 0.15.0 client 已带头；server 在 main） | 中 |
| `server/discover` | 无（SDK main 已有 `DiscoverRequest`，未发布） | 中 |
| 请求 `_meta` 版本/能力/身份 | 无（SDK main 已有 `RequestMeta`，未发布） | 中 |
| 列表结果 `ttlMs`/`cacheScope`、确定性顺序 | 无 | 低（SDK 层） |
| 结果 `resultType` | 无（SDK main 已有） | 低 |
| `x-mcp-header` | 无 | 低（可选） |
| 弃用传输（HTTP+SSE） | 正在使用 | **合规必改** |
| CLI 标准 JSON-RPC | 自定义 `{tool, arguments}` | 产品决策（见 5.2） |
| 会话状态 | 协议会话（SSE）→ 应用会话（sessionId 参数）并存 | 迁移后收敛为**显式 handle 模式**（规范推荐） |

---

## 五、迁移方案

### 5.0 前置：SDK 版本策略（关键路径）

| 选项 | 说明 | 建议 |
|---|---|---|
| A. 等完整支持 | kotlin-sdk 发布完整 2026-07-28 版本（main 已含 discovery/RequestMeta/resultType/ttlMs，默认版本仍是 2025-11-25） | **首选终态** |
| B. 先迁 0.15.0 | 同日发布的 0.15.0 已含 `mcpStatelessStreamableHttp`（单 POST、无会话 id）、`Mcp-Method`/`Mcp-Name` client 头、并发 handler；协议版本 2025-11-25 | **推荐过渡**：先消除"弃用传输 + 两代落后"两个最大风险 |
| C. 保持 0.8.1 | 维持现状 | 不推荐：协议落后 + 传输弃用，12 个月后生态客户端陆续不兼容 |

`browser4-dependencies/pom.xml`：`<io.modelcontextprotocol.version>` `0.8.1` → `0.15.0`（过渡）→ 最新（终态）。

### 5.1 阶段一：标准 MCP server 迁移（必做，低风险）

**`McpHttpServer.kt`（browser4-agentic）——传输层重写：**

- 删除 `SseServerTransport`、`ConcurrentHashMap<String, SseServerTransport>` 会话表、`/sse` 路由、`/message?sessionId=` 路由。
- 改为单端点：`POST /mcp`。用 SDK 的 `Application.mcpStatelessStreamableHttp(path = "/mcp") { server }`（0.15.0，GET/DELETE 自动 405）；终态（SDK 完整支持后）切换为 stateless 协议模式，服务端无需任何会话簿。
- `start()`/`stop()`/`actualPort` 逻辑保留；`activeSessions` 语义消失（无长连接），改为请求计数或移除。
- 客户端接入 URL 从 `http://host:8088/mcp/sse` 变为 `http://host:8088/mcp` —— 需同步更新 `McpHttpServerConfiguration.kt` 的 KDoc、技能文档、测试。

**`Browser4MCPServer.kt`——SDK API 适配（0.8.1 → 0.15.0 的破坏性变更）：**

- handler 签名：`addTool(...) { request -> ... }` 的 lambda 接收者变为 `ClientConnection`（0.9.0 起引入 `RequestContext`，0.15.0 为 `ClientConnection`）——`{ request, ctx -> }` 形态，闭包内 `toolManager.execute(tc)` 逻辑不变。
- 0.15.0 起**重复工具名注册直接抛 `IllegalArgumentException`**（0.8.1 静默覆盖）：`registerToolsFromManager` 需先做名称去重/冲突日志，防止 executor 规格重叠导致启动失败。
- `ServerCapabilities.Tools(listChanged=false)` 等构造若在 0.15.0 有字段变更，按编译错误修正即可（工具能力未变）。
- 终态（2026-07-28 完整支持）：确认 `tools/list` 确定性排序（当前由 `LinkedHashMap`/注册序天然确定，需在列表生成处显式保证）、SDK 自动附带 `ttlMs`/`cacheScope`。

**`McpHttpServerE2ETest.kt`：**

- 删掉 initialize/SSE 会话复用用例，改为：单 POST 工具调用、无 `Mcp-Session-Id` 请求成功、`Mcp-Method`/`Mcp-Name` 头校验、并发请求、断流重发（新 id）。
- 终态补 `server/discover` 用例（supportedVersions 含 `2026-07-28`）、错误码（`UnsupportedProtocolVersionError`）用例。

### 5.2 阶段二：CLI 侧决策（产品决策，非合规义务）

**选项 A：保持自定义 `/mcp/call-tool`（推荐近期维持）**

- 优点：零 CLI 改动；batch、分页、插件工具规格等自定义语义不动；内部 RPC 不受规范演进影响。
- 缺点："MCP" 名不副实；外部客户端无法用标准方式直连 8182（但它们可以连 8088 标准端点，二者互补）。
- 配套：文档中把 `/mcp/call-tool` 明确标注为 browser4 私有 RPC，避免与标准 MCP 混淆。

**选项 B：CLI 迁移到标准 stateless MCP（中期可选，收益明确）**

- 2026-07-28 让这件事前所未有的简单：无握手、无会话头，每次请求带 `_meta`（protocolVersion + clientInfo）即可；`tools/list` 替代 `GET /mcp/tools`。
- 需要处理的自定义语义：
  - **batch 命令**：封装为单个工具（如 `browser4_run_batch`，参数为 op 数组）或保留 `/mcp/call-tool` 作为内部批处理通道；
  - **分页 `_pagination`**：作为私有 `_meta` 字段保留（标准允许 `_meta` 扩展），CLI 端解析逻辑不变；
  - **插件 CLI 工具规格**（`/mcp/tools/specs`）：这是 CLI 特有的发现通道，标准 MCP 无对应物，保留端点或作为工具 `browser4_list_cli_specs`；
  - **timeout 逻辑**（`effective_timeout`/wait 工具 +5s）：与协议无关，直接保留；客户端需支持 `notifications/cancelled` 或超时重试（stateless 下断流=重发新请求）。
- 会话状态：`sessionId` 收敛为规范推荐的**显式 handle 模式**——`open_session` 返回 handle，`browser_*` 工具接受 `sessionId` 参数（现状已如此，符合 SEP-2567 指引），协议层不再有会话。
- 收益：CLI 可用任意标准 MCP 客户端替代/互操作；8182 与 8088 两个端点合一；为集群/负载均衡部署铺路。

### 5.3 会话与状态

- 协议会话删除后，browser4 的浏览器会话（`PulsarSessionManager`）**不受影响**：它本来就是应用层状态，通过 `sessionId` 参数显式传递（规范明确推荐该模式："mint an explicit handle from a tool and have the model pass it back as an argument"）。
- 建议借机把 `sessionId` 错误信息（`ERROR_NO_ACTIVE_SESSION` 等）与 handle 语义对齐，并在 `open_session` 返回中提示 handle 用途。

### 5.4 可选增强（不做不阻塞）

- **MRTR/elicitation**：browser4 无采样需求；若未来做"危险操作二次确认"，用 MRTR（`resultType: "input_required"` + `inputResponses` 重试）而不是开长连接。
- **`subscriptions/listen`**：当前 `listChanged=false`，无订阅需求，跳过。
- **授权**：无 OAuth 端点，仅需关注客户端侧 `iss` 校验约定（若未来加 gateway）。
- **`x-mcp-header`**：CLI 若迁移，可把 `sessionId` 标为 `x-mcp-header` 参数，让网关按会话路由。

---

## 六、风险与注意事项

1. **SDK 依赖在途**：kotlin-sdk 完整 2026-07-28 支持未发布（main 分支 `LATEST_PROTOCOL_VERSION` 仍为 `2025-11-25`）。过渡期（0.15.0 + 2025-11-25）是"传输 stateless、协议仍会话"的混合形态，客户端（Claude Desktop 等）需支持 Streamable HTTP——大部分主流客户端已支持。**发布前务必跑 conformance 测试**（kotlin-sdk 已有 conformance 基础设施）。
2. **弃用窗口**：HTTP+SSE 有 12 个月 offramp，2027-07 前完成不紧迫，但宜早不宜迟（生态客户端会先切）。
3. **行为变化对用户可见**：`/mcp/sse` URL 消失 → 文档/技能/示例（`skills/`、`README`、`McpHttpServerConfiguration` KDoc、Claude Desktop 配置示例）必须同步更新。
4. **工具名冲突**：0.15.0 重复注册抛异常，动态注册路径需去重保护（回归测试覆盖）。
5. **测试范围**：按 AGENTS.md 门禁——传输层改动必须跑 `McpHttpServerE2ETest`（真实 HTTP）+ 现有 REST 测试回归；CLI 若迁移，跑 `cargo test --test e2e --scenario=...`。
6. **不要动 `MCPToolController` 的协议语义**：它是 CLI 主路径，任何改动需全量 e2e 回归；建议阶段二独立进行。
7. **版本管理**：`browser4-dependencies` BOM 中 `io.modelcontextprotocol.version` 改动需遵循"无任意版本变更"原则，记录理由。

---

## 七、参考链接

- 官方博客：[The 2026-07-28 Specification](https://blog.modelcontextprotocol.io/posts/2026-07-28/)
- 官方 changelog：[specification/2026-07-28/changelog.md](https://modelcontextprotocol.io/specification/2026-07-28/changelog.md)（含 SEP-2567/2575/2322/2243/2549/2663/2596/2468 等链接）
- 迁移解读：[Stateless protocol (MCP 2026-07-28)](https://github.com/giantswarm/muster/blob/main/docs/explanation/mcp-2026-07-28/01-stateless-protocol.md)、[Release timeline and validation](https://github.com/giantswarm/muster/blob/main/docs/explanation/mcp-2026-07-28/09-release-timeline.md)
- 社区迁移指南：[What's new in the MCP 2026-07-28 specification (Appwrite)](https://appwrite.io/blog/post/mcp-goes-stateless-in-the-2026-07-28-specification)、[MCP 2026-07-28: What Breaks and How to Migrate (cruxdigits)](https://cruxdigits.nl/blog/mcp-2026-07-28-migration/)、[Migrating to MCP 2026-07-28 (smfclearinghouse)](https://www.smfclearinghouse.com/guides/migrating-to-mcp-2026-07-28/)、[Evolving a Java MCP Server During MCP Specification Upgrades (Inside.java)](https://inside.java/2026/08/12/java-mcp-migration/)
- SDK：[kotlin-sdk releases](https://github.com/modelcontextprotocol/kotlin-sdk/releases)（0.15.0 为 2026-07-28 同日发布，含 `mcpStatelessStreamableHttp`）、[go-sdk v1.7.0](https://github.com/modelcontextprotocol/go-sdk/releases/tag/v1.7.0)
- 弃用清单：[deprecated features registry](https://modelcontextprotocol.io/specification/2026-07-28/deprecated)
