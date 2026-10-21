# 浏览器接入可行性评估（Browser4 4.14.x，候选：Steel / Browserless / Browserbase / Hyperbrowser / veilbrowser / Nstbrowser 等）

> 评估日期：2026-08-17
> 关联文档：`docs-dev/copilot/cloakbrowser-support-eval.md`（CloakBrowser 接入评估，本地二进制路径）
> 数据源：Browser4 源码（`cli/browser4-cli/src/main.rs` 的 `resolve_cdp_endpoint` / `handle_attach`、`browser4-rest/.../PulsarSessionManager.createAttachedSession`、`SessionKind`）、pulsar-browser jar（`PulsarBrowser`/`ChromeImpl`）、各候选官方仓库与文档

## 1. 总体结论

| 候选 | 形态 | 接入方式 | 可行性 | 工作量 | 主要约束 |
|---|---|---|---|---|---|
| **Steel Browser** | 自托管浏览器沙箱服务（Docker，Apache-2.0） | `attach --cdp http://localhost:9223` | ✅ 高（推荐） | ~0 代码，Docker 部署 | 浏览器生命周期归 Steel，非 Browser4 |
| **Browserless** | 自托管 CDP 服务（Docker，开源双许可） | `attach --cdp http://localhost:3000` | ✅ 高 | ~0 代码，Docker 部署 | 同上；非商用免费 |
| **Nstbrowser（本地版）** | 指纹浏览器客户端（Chromium 系） | 本地 `-Dchrome.path` 或 attach 其 CDP 端口 | ✅ 中 | ~0 代码 | 闭源、需账号；指纹面向爬虫 |
| **Browserbase / Hyperbrowser（云）** | 云浏览器 API | `attach --cdp <远程 URL>` + **本地端口隧道** | 🟡 中（有隧道前提） | 隧道部署（cloudflared/ssh -L） | 云依赖、按量付费；契约要求本机端口 |
| **veilbrowser** | **TS 库**（裸 CDP 驱动真 Chrome，非浏览器） | 不适用（Kotlin 栈无法用 TS 库） | ⛔ 不适用 | — | 借鉴理念：裸 CDP + a11y ref + 人类化输入 = Browser4 已同向 |
| **fuse-browser / stealth-chrome-devtools-mcp** | MCP 服务型（驱动浏览器） | 不直接对接（Browser4 自有 MCP 协议） | ⛔ 不适用 | — | 可当"浏览器提供者"外包给 agent 框架 |
| **Camoufox** | Firefox fork（无 CDP） | 不兼容 CDP 栈 | ⛔ 排除 | — | 需 Marionette/Playwright 后端（Browser4 未装配） |
| **Agent Browser Protocol (ABP)** | Chromium fork（内置 MCP/REST，**拒绝 CDP**） | 不兼容 | ⛔ 排除 | — | 理念重合（给 AI 的浏览器）但协议不同 |
| **Thermoptic** | HTTP stealth 代理 | 非浏览器、无 CDP | ⛔ 排除 | — | — |
| **BrowserOS neo** | agentic 桌面浏览器（AGPL） | 非 CDP 服务形态 | ⛔ 排除 | — | 走 MCP 连 agent harness |

**核心结论：Browser4 对外部浏览器有两条零代码通道，且都已在 4.14 实现并测试覆盖：**
1. **本地二进制**：`-Dchrome.path=<可执行文件>`（CloakBrowser 路径，已 POC 验证下载/校验/注入链路）
2. **外部 CDP**：`attach --cdp <endpoint>`（Steel / Browserless / 任何暴露本机 CDP 端口的浏览器）

两条路径都**不需要改 Kotlin/Rust 代码**——接入成本主要是"候选服务的部署"而非开发。

## 2. 接入契约（决定可行性的关键）

`attach --cdp` 的完整链路（源码证据）：

```
CLI: resolve_cdp_endpoint(raw)          // main.rs:1346
     http://host:port 直通；ws://wss:// 转 http；裸端口 "9222" / "host:9222" 自动补全
CLI: handle_attach → POST /api  attach_browser  { cdpEndpoint }   // main.rs:1480
Server: MCPToolController "attach_browser" → createAttachedSession  // MCPToolController.kt:305
Server: PulsarSessionManager.createAttachedSession(cdpEndpoint, ...) // PulsarSessionManager.kt:274
        port = parsePortFromEndpoint(cdpEndpoint)
        PulsarBrowser(port, settings)   // ← 关键：只用一个 TCP 端口
Server: PulsarBrowser → ChromeImpl(port) → CDP HTTP 端口（/json/version、/json/list）→ 全套驱动
```

**契约 = 服务器进程本机可达的、暴露标准 CDP HTTP 接口（/json/version 等）的 TCP 端口。**

推论：
- **自托管服务（Steel 9223 / Browserless 3000）**：Docker 端口映射到 localhost → 直接满足，`attach --cdp http://localhost:9223` 即可。
- **云服务（Browserbase/Hyperbrowser）**：CDP 端点在远端，`PulsarBrowser(port)` 连的是本机端口 → **必须做本地端口隧道**（`cloudflared access tcp` / `ssh -L` / Browserbase 官方 CLI 的 `--cdp` 输出配合隧道），否则 attach 失败。
- **attach 后的功能完整度**：驱动层与自启浏览器完全同构（同一 `ChromeImpl`→`BrowserProtocol`→`Browser4WebDriver`），open/type/click/mousewheel/eval/screenshot 全部可用。差异仅两点：① 浏览器生命周期归外部（`browser4-cli stop` 不会关掉外部浏览器）；② attached 会话断线后不自动重建（`SessionKind.CDP_ATTACHED` 语义，需重新 attach）。

## 3. 分项评估

### 3.1 Steel Browser —— 推荐（开源 AI 原生 + CDP 直连）
- **形态**：Docker 自托管浏览器沙箱 API（Apache-2.0，7.5k★，2026-08 活跃）；session 管理、指纹注入、请求日志、UI 调试台。
- **CDP 证据**：`docker-compose.yml` 暴露 `9223:9223`，`CDP_DOMAIN=localhost:9223`；`api/src/services/cdp/cdp.service.ts` 用 `http-proxy` 把 Chromium 调试接口代理到该端口（源码：`getDebuggerUrl`/`getDebuggerWsUrl`）。即 **9223 是本机可访问的 CDP 端口**。
- **接入**：`browser4-cli attach --cdp http://localhost:9223`（或先 `open` 再按 session 接入）。
- **优点**：开源可审计、自带 stealth/指纹注入（比 CloakBrowser 的 C++ 补丁浅但开箱即用）、session 隔离适合多任务、Apache-2.0 可商用自托管。
- **风险**：Steel 内部用 Puppeteer 管理浏览器，其指纹注入是 JS 层（`fingerprint-injector`）——隐身强度弱于 CloakBrowser 的 C++ 补丁；attach 会话生命周期不归 Browser4。

### 3.2 Browserless —— 推荐（最标准 CDP 服务）
- **形态**：Docker 起 headless browser 服务（开源双许可，13.6k★）；3000 端口即 CDP 端点（`/json/version` 返回 `webSocketDebuggerUrl`，Puppeteer/Playwright 均以 `ws://localhost:3000` 直连）。
- **接入**：`attach --cdp http://localhost:3000`。
- **优点**：标准 CDP、部署最简单（单容器）、非商用免费；有并发/资源管理。
- **风险**：无 stealth（原生 Chrome）；非商用限制。

### 3.3 Browserbase / Hyperbrowser（云）—— 有条件可行
- **形态**：云浏览器 API；Browserbase `connect --cdp` / Hyperbrowser CDP 会话均返回远程 CDP 端点。
- **接入**：远程端点 + 本地隧道（cloudflared/ssh）→ `attach --cdp http://127.0.0.1:<隧道端口>`。
- **优点**：免运维、弹性、官方 Playwright/Puppeteer 集成成熟。
- **风险**：云依赖、费用、数据出境；隧道增加了拓扑复杂度与延迟；`PulsarBrowser` 连本机端口的契约意味着隧道是硬前提。

### 3.4 Nstbrowser（本地版）—— 可行（同 CloakBrowser 类）
- **形态**：指纹浏览器客户端（Chromium 系，国内团队，面向爬虫/AI），支持本地 CDP 端口与 Playwright connect；云 API 亦提供 CDP。
- **接入**：本地客户端暴露 CDP 端口 → `attach --cdp`；或 `-Dchrome.path` 指向其内核。
- **风险**：闭源、需注册/额度、指纹引擎为注入式；商业条款需确认。

### 3.5 veilbrowser —— 不适用（理念借鉴）
- **形态**：`@achamm/veilbrowser`（MIT，TS 库，零依赖），裸 CDP 驱动**真 Chrome**，主打 a11y-tree ref、人类化输入、FedCM 支持、防端口扫描。
- **结论**：不是浏览器，是 CDP 客户端——与 Browser4 的 `PulsarWebDriver`（同样是裸 CDP 客户端）职能重叠，且 TS 库无法挂进 Kotlin 栈。**但理念验证了 Browser4 的方向**（裸 CDP + a11y refs + 行为级输入），其「attach 到已登录 profile」正是 Browser4 `attach --cdp chrome` 的用法。

### 3.6 fuse-browser / stealth-chrome-devtools-mcp —— 不直接对接
- MCP 服务型（把浏览器动作暴露为 MCP tools），面向 Claude/Codex 等 MCP 宿主；Browser4 的自有 MCP 协议（CLI→REST）与其不互通。若未来 Browser4 支持标准 MCP 客户端接入，可重新评估。

### 3.7 排除项
- **Camoufox**：Firefox fork、C++ 级反指纹，但走 Marionette 无 CDP → 需要 playwright 协议后端（Browser4 当前运行时未装配 `pulsar-protocol-playwright`）。
- **ABP (Agent Browser Protocol)**：Chromium fork 内置 MCP+REST、明确"无 CDP"——协议不兼容，但可作为"浏览器内嵌 agent 协议"的参照。
- **Thermoptic**：HTTP stealth 代理，非浏览器。
- **BrowserOS neo**：agentic 桌面浏览器，面向 Claude/Cowork/Codex 的用户侧浏览器，非可编程 CDP 服务。

## 4. 验证路径（POC 步骤，按推荐序）

```powershell
# Steel（需先 docker compose up -d，9223 暴露）
browser4-cli attach --cdp http://localhost:9223
browser4-cli open "https://example.com"
browser4-cli eval --json "navigator.userAgent"     # 确认是 Steel 的 Chromium

# Browserless（docker run -p 3000:3000 ghcr.io/browserless/chromium）
browser4-cli attach --cdp http://localhost:3000
browser4-cli open <url>  →  type / click / mousewheel / screenshot 全链路

# 云（Browserbase 示例，端口隧道）
cloudflared access tcp --hostname <browserbase-cdp> --url 127.0.0.1:9223 &
browser4-cli attach --cdp http://127.0.0.1:9223
```

验证要点：attach 后跑一遍 `open→type→click→mousewheel→eval→screenshot`（可复用 `bin/setup-cloakbrowser.ps1` 的 POC 步骤模板，仅把"重启服务器"换成"起外部服务 + attach"）。

## 5. 推荐组合

| 场景 | 推荐 |
|---|---|
| 开源自托管 + AI 原生 + CDP | **Steel**（Apache-2.0）→ `attach --cdp :9223` |
| 最简部署的 CDP 服务 | **Browserless** → `attach --cdp :3000` |
| 最强隐身（需 C++ 级补丁） | **CloakBrowser** → `-Dchrome.path`（已评估） |
| 云托管、免运维 | Browserbase/Hyperbrowser + 隧道 |
| 反检测多开（闭源可接受） | Nstbrowser / 商业指纹浏览器 |

**开发工作量**：以上全部 ≈ 0 Kotlin/Rust 改动（两通道均已实现）；若要把"外部浏览器"做成正式一等公民（新增 `BrowserType`、配置文件化的 attach 模板、`browser4-cli attach --cdp <service>` 快捷命令），约 1–2 人日（主要是 CLI 便利化与文档）。

## 6. 参考
- Steel：https://github.com/steel-dev/steel-browser （docker-compose.yml：9223/CDP_DOMAIN；api/src/services/cdp/cdp.service.ts：http-proxy 代理调试接口）
- Browserless：https://github.com/browserless/browserless
- Browserbase / Hyperbrowser：云文档（CDP connect）
- veilbrowser：https://github.com/acunningham-ship-it/veilbrowser
- ABP：https://github.com/theredsix/agent-browser-protocol
- Browser4 侧源码：`cli/browser4-cli/src/main.rs`（resolve_cdp_endpoint/handle_attach）、`browser4-rest/.../session/PulsarSessionManager.kt`（createAttachedSession）、`.../session/SessionKind.kt`（CDP_ATTACHED）
