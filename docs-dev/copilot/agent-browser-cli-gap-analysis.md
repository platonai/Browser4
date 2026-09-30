# agent-browser CLI 命令差距分析（对照 Browser4 4.14）

> 分析日期：2026-08-17
> 数据源：`D:\codebase\browser-automation\agent-browser\cli\src\commands.rs`（5741 行，`is_top_level_command` 全量清单 + 各 parse 分支）、Browser4 `cli/browser4-cli/src/commands.rs`（154 个 CommandDef）、`ToolAliases.kt`、`BrowserTabToolExecutor.kt`、`MCPToolController.kt`

## 1. 总体结论

- agent-browser 约 **90 个顶层命令 / 120 个 daemon action**；Browser4 CLI 已有 **154 个命令**，且多出大量 agent-browser 没有的能力（crawl/swarm/code-*/htmlsnapshot/webdb/experience 等）。
- **核心交互面重合度很高**：导航、点击/输入、键鼠、等待、截图/PDF/snapshot/eval、tab、cookie、storage、dialog、console、batch、doctor/install/upgrade/skills/plugins/chat 全部已覆盖。
- 真实缺口约 **20 个命令族**，可分三档：
  - **A 档（薄封装，后端已有或 eval/cdp 可达）**：约 12 项，2–3 人周
  - **B 档（需新增 CDP 能力）**：约 7 项，4–6 人周
  - **C 档（重型/生态绑定，建议逐项决策）**：约 5 项，单项 1–6 周不等
- **全量对齐约 10–14 人周**；但 A 档即可消除约 80% 的实际使用差距，建议先做 A 档。

## 2. 已覆盖（无需动作）

| agent-browser 命令 | Browser4 对应 |
|---|---|
| open / goto / navigate / back / forward / reload | open / goto / go-back / go-forward / reload |
| click / dblclick / fill / type / hover / check / uncheck / select / drag / upload | 同名 |
| press / key / keydown / keyup / keyboard | press / keydown / keyup |
| mouse move/down/up/wheel | mousemove / mousedown / mouseup / mousewheel |
| scroll / wait / screenshot / pdf / snapshot / eval | 同名（wait 支持 --text/--url/--load/--fn） |
| close / quit / exit | close / close-all / kill-all |
| dialog accept/dismiss/status | dialog-accept / dialog-dismiss（status 缺，见 A 档） |
| cookies / storage local/session | cookie-* / localstorage-* / sessionstorage-* |
| tab new/list/switch/close | tab-new / tab-list / tab-select / tab-close |
| confirm / deny | dialog-accept / dialog-dismiss |
| console / errors | console（min-level 过滤；errors 仅差 sugar 别名） |
| state save/load/list… / session | state-save / state-load / session-default |
| inspect（元素信息） | page-info / generate-locator（部分等价） |
| connect | attach |
| get text/html/attr/value/box/styles/url/title/count | get（mode 参数，子模式基本齐全） |
| find role/text/label/… | generate-locator（定位器生成等价） |
| batch / doctor / install / upgrade / skills / plugin(s) / chat | batch / doctor* / install / upgrade / skill-* / plugin-* / chat |
| mcp | Browser4 本身即 MCP over HTTP 后端；仅缺 CLI 端 stdio 桥（C 档可选） |
| set viewport | resize |

**关键去风险因素**：Browser4 已有 `cdp` 命令（任意 CDP method 直通 `execute_cdp_command`）和带 `--await/--file/--base64` 的 `eval`。多数缺口可以先用它们手工达成，再逐步产品化。

## 3. A 档缺口：薄封装（低风险，2–3 人周）

后端能力已存在（BrowserTabToolExecutor 已有 focus/visible/exists 分支）或可用一次 eval/cdp 调用实现，主要是 CLI 暴露 + 别名 + 测试：

| 命令 | 说明 | 估算 |
|---|---|---|
| `focus <sel>` | executor 已有 focus，仅缺 CLI 命令 | 0.5d |
| `is visible/enabled/checked <sel>` | executor 已有 visible；补 enabled/checked 断言 | 1d |
| `scrollintoview <sel>` | eval 一行（`el.scrollIntoView()`） | 0.5d |
| `pushstate <url>` | eval 一行（`history.pushState`） | 0.5d |
| `highlight <sel>` | eval 注入 outline overlay | 1d |
| `vitals` / `web-vitals` | eval 注入 web-vitals 库取 LCP/CLS/INP | 1–2d |
| `set geo / offline / headers / media / device` | CDP `Emulation.set*` / `Network.*` 直通封装 | 3–5d |
| `window new` | 新窗口（等价 tab-new + window 特性） | 0.5d |
| `profiles list` | open --profile 已有，仅列目录 | 0.5d |
| `errors` 别名 | = console --min-level error | 0.5d |
| `key`/`keyboard` 别名 | = press | 0.5d |
| `diff snapshot` | `snapshot_diff.rs` 已在 CLI 内部实现，暴露成命令 | 1d |
| `dialog status` | 查询 pending dialog | 0.5d |

每项均走 AGENTS.md 标准路径：commands.rs CommandDef → ToolAliases → executor 分支 → 单测/e2e。风险主要在参数命名一致性与 batch 支持声明，均为已知坑。

## 4. B 档缺口：需新增后端 CDP 能力（中风险，4–6 人周）

| 命令族 | 实现路径 | 估算 | 风险 |
|---|---|---|---|
| `download` + `wait --download` | `Browser.setDownloadBehavior` + `Page.downloadWillBegin/Progress` 事件流 | 3–5d | 下载目录管理、headless 差异 |
| `frame <sel>` / `mainframe` | eval 路由到子 frame（CDP `Page.createIsolatedWorld` / frameId 路由） | 3–5d | **依赖 AbstractWebDriver（来自 pulsar 依赖 jar，源不在本仓库）**，可能需在 BrowserTabToolExecutor 层绕行 |
| `tap` / `swipe` / `device` | `Input.dispatchTouchEvent` + `Emulation.setDeviceMetricsOverride`（touch 特性） | 3–5d | 移动事件时序、crbug 类竞态 |
| `clipboard read/write/copy/paste` | `navigator.clipboard` + 权限授予；CLI 侧系统剪贴板 | 2–3d | 跨平台剪贴板（Windows/macOS 差异）、权限策略 |
| `profiler start/stop` | CDP `Profiler` 域，输出 cpuprofile 文件 | 2–3d | 低 |
| `record start/stop/restart`（webm 视频） | `Page.startScreencast` 帧流 → webm 封装 | 5–8d | **Rust 侧需引入编码/mux 依赖**，增大二进制；帧丢失与性能 |
| `stream enable/disable/status` | screencast 帧推送到 CLI 渲染 | 3–5d | 进程间流式传输协议（现有 HTTP 请求-响应模式需扩展） |

注：`record`/`stream` 与 Browser4「轻量 CLI」取向有张力（新增 Rust crate），建议放最后或砍掉。

## 5. C 档缺口：重型/生态绑定（逐项产品决策）

| 命令族 | 规模 | 风险/决策点 |
|---|---|---|
| `network route/unroute/requests/har` | 2–4 周 | CDP `Fetch` 域请求拦截。**全局改 CDP 会话行为，可能影响现有抓取链路**；需会话级作用域 + 拦截竞态处理。HAR 录制还需事件聚合。是 scrape 场景高价值项，但复杂度最高 |
| `read`（reader 模式 markdown） | 1–2 周 | agent-browser read.rs 1913 行：readability 提取 + `--llms`/`--outline`/`--filter`/`--require-md`。Browser4 的 `extract` 是 LLM 指令式，不同定位。需前端提取算法 + 大量站点适配测试 |
| `auth save/login/list/show/delete` | 1–2 周 | 凭据存储是**安全敏感项**：需 OS keychain 或加密存储、脱敏日志、审计。credential-provider 插件协议另算 |
| `trace start/stop` | 3–6 周 | Playwright 兼容 trace（ZIP 内含快照/截图/动作日志）。除非明确要 Playwright Viewer 兼容，否则投入产出比低 |
| `react tree/inspect/renders/suspense` | 1–2 周 | React fiber 内部钩子，版本耦合，niche |
| `dashboard` | — | agent-browser 自有 Web UI 产品形态，与 Browser4 产品线不同，**建议不做** |
| CLI 端 `mcp` stdio 桥 | 3–5d | 若目标是让 Claude Code 等直接把 CLI 当 MCP server 用，值得；否则跳过 |

## 6. 风险汇总

1. **pulsar 依赖边界**：`AbstractWebDriver`/`WebDriver.kt` 源自外部依赖（ToolSpecGenerator 从 resource 读取）。新增 tab 级能力优先落在仓库内的 `BrowserTabToolExecutor`（browser4-agentic，1711 行，自持 CDP 调用路径），避免动驱动接口。
2. **网络拦截的全局副作用**（C 档 network）：必须做成会话级 opt-in，回归测试要覆盖现有 crawl/抓取流程。
3. **凭据安全**（C 档 auth）：明文落盘是红线；需 keychain 抽象 + 日志脱敏 + 独立审计。
4. **CLI 体积/依赖**（record/stream）：webm 编码 crate 会显著增大 Rust 二进制，与轻量定位冲突。
5. **既有坑位复现**：新命令常踩「缺 backend 别名、漏 sessionId、漏 no_snapshot_commands()/batch_supported、元素参数名不一致」（AGENTS.md 已列）；A 档批量添加时按 checklist 逐条过即可控。

## 7. 工作计划（修订版：插件优先 + 价值排序）

> 修订依据：① 高价值排序（network/download/read 为前三）；② 插件化可行性验证（`ToolMount` + `CustomToolRegistry`，参照 browser4-markdown 样板）；③ 关键发现——`browser4-markdown` 插件已有 `markdown.fetch(url)`（HTTP 直抓转 md），即 `read` 的核心逻辑。

### Sprint 1（第 1 周）：核心胶水批 —— 零后端风险
纯 CLI 胶水命令，全部走标准 checklist（CommandDef → ToolAliases → executor 分支 → 单测/e2e）：
`focus`、`is visible/enabled/checked`、`errors` 别名、`key/keyboard` 别名、`diff snapshot`（snapshot_diff.rs 已在）、`dialog status`、`window new`、`scrollintoview`、`pushstate`、`highlight`（eval overlay）、`profiles list`
- 产出：兼容面立涨，批内互不阻塞，可并行
- 风险：仅 AGENTS.md 已列的已知坑（别名/sessionId/batch_supported）

### Sprint 2（第 2 周）：read —— 扩展 browser4-markdown 插件
不新建插件，在现有 `browser4-markdown` 上补：
- 内容协商（`Accept: text/markdown` 优先，服务器直出 md 零解析）
- `llms.txt` / `llms-full.txt` 就近发现（index/full 两模式）
- `outline`（标题大纲）、`filter`（section 过滤）、`allowed-domains` 白名单（agent 防 SSRF）
- CLI 侧新增 `read` 命令 + flags
- 成本约原估一半；`markdown.convert/crawl` 现有用户零影响

### Sprint 3（第 3 周）：download —— 核心（小而完整）
- `Browser.setDownloadBehavior` + `Page.downloadWillBegin/Progress` 事件流
- `download` 命令 + `wait --download [path]`
- **关键依赖**：确认 `AbstractWebDriver` 是否暴露 CDP 事件注册口；若无，本周在核心开最小事件口子（Sprint 5 的 network 插件复用）
- 覆盖 headless 差异与下载目录管理测试

### Sprint 4（第 4–5 周）：emulation + profiler 插件（一个 jar 打包）
- `set geo/offline/headers/media/device`（CDP `Emulation.*`/`Network.*` 直通）
- `profiler start/stop`（CDP `Profiler` 域，输出 .cpuprofile）
- `vitals`（eval 注入 web-vitals）；`react` 顺手可选（同 eval 模式，niche 不单独排期）
- CLI 侧 `set` 族命令（纯胶水）
- 全程不碰核心，AOT cache 无影响

### Sprint 5（第 6 周+）：network 插件 —— 先 RFC 半周
- CDP `Fetch` 域：`route/unroute/requests`（HAR 录制后置，route+日志已覆盖 80% 场景）
- 硬性要求：**会话级 opt-in**、卸载即恢复、回归现有 crawl/抓取链路
- 风险最高、价值也最高，独立插件隔离故障域

### 独立立项（不进主线排期）
- **auth 插件**：安全敏感（keychain/加密/日志脱敏/审计），单独立项 + 安全评审
- **CLI stdio MCP 桥**：3–5 天，待 Claude Code 类集成需求明确再启动

### 明确砍掉 / 冻结
| 项 | 理由 |
|---|---|
| trace | 3–6 周，无 Playwright Viewer 兼容需求则投入产出比最低；插件无中心拦截点 |
| dashboard | 对方产品形态，与 Browser4 生态无关 |
| record/stream | 需 CLI↔daemon 流式协议 + Rust 编码依赖，与轻量 CLI 定位冲突 |
| clipboard | OS 侧工作在 Rust CLI，后端插件无意义；需求未验证 |
| frame | 需核心 driver 层 eval 路由；现有 iframe 场景可用 eval 变通 |
| tap/swipe/device 触控 | 移动端需求出现后再排 |

### 里程碑对照
| 周末 | 兼容面（占 90 命令族） | 新增能力亮点 |
|---|---|---|
| W1 | ~85% | 断言/焦点/高亮/大纲 diff |
| W2 | ~87% | **read（零 token 快路径）** |
| W3 | ~88% | **download 闭环** |
| W5 | ~92% | emulation 全家 + profiler + vitals |
| W6+ | ~94% | **network 拦截**（剩余为砍掉项） |

## 附：agent-browser 顶层命令全清单（90）

open goto navigate back forward reload read click dblclick fill type hover focus check uncheck select drag upload download press key keydown keyup keyboard scroll scrollintoview scrollinto wait screenshot pdf snapshot eval close quit exit inspect auth confirm deny connect stream get is find mouse set network storage cookies tab window frame dialog trace profiler record console errors highlight clipboard state tap swipe device diff batch react vitals web-vitals pushstate removeinitscript session mcp doctor install upgrade profiles skills dashboard plugin plugins chat
