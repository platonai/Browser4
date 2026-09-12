# 调研报告：支持 CDP 的指纹浏览器 × 面向 AI 的底层重写浏览器

> 调研日期：2026-09-10 ｜ 方式：桌面调研（web 检索 + 官方文档/一手信源抓取），未做实测
> 目的：为 Browser4/Pulsar（CDP 驱动的 agent 浏览器框架）评估两类外部生态——①可作为"自动化+身份隔离"后端的指纹浏览器；②面向 AI/Agent 的新一代浏览器与底层引擎，判断行业走向与本项目的关系。
> 可信度约定：以官方文档为一手信源；厂商营销语单独标注；凡抓取失败或无法核实处均明确列出（见附录 B），无编造内容。

---

## 摘要（TL;DR）

1. **"指纹浏览器支持 CDP"的真实含义**：绝大多数产品 = "自家本地 HTTP API 启动浏览器环境 → 返回一个调试端口/WebSocket 地址 → 你用 Puppeteer/Playwright/Selenium 去连"。浏览器侧的 CDP 实现通常是**白名单壳层**，不是完整协议；各家的连接方式互不兼容。
2. **指纹浏览器 ≠ 隐身**：CDP 自动化本身可被页面检测（`Runtime.enable` 调用副作用、注入脚本 sourceURL、utility world 名、合成事件 `isTrusted=false`、严格 CSP 下 `evaluate` 失效等）。多数指纹浏览器不修复自动化库的泄漏，需要 rebrowser-patches 这类补丁叠加。
3. **市场上三类玩家**：商业客户端派（Octo/AdsPower/BitBrowser/MoreLogin/Multilogin/GoLogin/Dolphin Anty/Kameleo/Incogniton/VMLogin/Hubstudio）、开源补丁派（rebrowser-patches/patchright/zendriver 等）、云托管 CDP 派（Scrapeless/GoLogin Cloud/Rebrowser Cloud）。三者可组合。
4. **"面向 AI 的底层重写浏览器"分两条路线**：路线 A = 人机共存的消费级 AI 浏览器（Comet、Dia），均为 **Chromium fork + 深度 AI 改造**，正在洗牌（OpenAI Atlas 只活了 292 天已死、谷歌 Project Mariner 退役）；路线 B = 2026-08-06 因 **Cloudflare Kitesurf** 发布而正式成形的 **Agent 运行时/执行层**——**唯一真正 from scratch 的玩家**（Rust→WASM、无 Chromium），且对外暴露 **CDP 兼容端点**。
5. **接口事实标准是 CDP + MCP，而不是新协议**：连从零自研的 Kitesurf 都选择暴露 CDP；MCP 已成为 agent 接入浏览器的标准姿势（本项目 CLI 本身就是 MCP over HTTP，同构）。
6. **协议层面**：Firefox ≥141 彻底移除 CDP（只剩 WebDriver BiDi）；Selenium 官方移除对 Firefox 的 CDP 支持；但 Chrome 生态短期不会离开 CDP，"Chrome=CDP、跨浏览器=BiDi"双轨长期并存。
7. **从零重写的引擎**（Ladybird 转 Rust、Servo）2026 年都还不是可用的 agent 自动化底座（无 CDP/BiDi 生态或能力面有限），但 Servo 已出现 servo-agent 这类"从零引擎 + WebDriver + MCP"的先锋案例。
8. **对本仓库的启示**：可把"返回标准 CDP 端点的指纹浏览器"当作可选浏览器后端接入现有 attach 路径；token 友好快照（本仓库 aria/htmlsnapshot 快照体系）正对应当前 agent 浏览器行业的核心优化方向；反爬纪律（AGENTS.md 中 isTrusted/随机延迟等 gate）与检测技术面完全对应，是既有优势。

---

# 第一部分　支持 CDP 的指纹浏览器

## 1.1 背景：指纹浏览器是什么，"支持 CDP"有哪几层

指纹浏览器（antidetect browser）的核心诉求是**身份隔离**：每个"环境/profile"伪造一套独立且自洽的浏览器指纹（Canvas/WebGL 噪声、字体、时区、语言、UA、硬件并发、WebRTC、音频等），配合独立出口代理，用于多账号运营、广告验证、市场调研等。主流实现是**自家编译的 Chromium 分支 + JS/引擎层指纹注入**（部分产品另维护 Firefox 分支，如 AdsPower FlowerBrowser、Multilogin Stealthfox）。

"支持 CDP"在实践中是三个层次：

| 层次 | 含义 | 典型 |
|---|---|---|
| A. 真·完整 CDP 端口 | 启动的浏览器直接开远程调试端口，全量协议可用 | 极少见（商业产品普遍不这么做） |
| B. 白名单 CDP 壳层 | 只实现常用域（Page/Runtime/DOM/Input/Network/Storage/Target 子集），其余方法返回空 | Octo Mobile（官方自曝，见 1.3） |
| C. 本地 API 编排 + 标准 CDP 端点 | 自家 HTTP API（localhost 端口）负责创建/启动 profile，返回调试端口或 WS 地址，之后 Puppeteer/Playwright/Selenium 直连**真 CDP** | AdsPower/BitBrowser/MoreLogin/Multilogin/GoLogin/Kameleo/Incogniton/VMLogin/Hubstudio 等绝大多数 |

**选型时该问"哪些 CDP 域可用"，而不是"支不支持 CDP"。**

## 1.2 市场格局：三派玩家

- **商业客户端派**：上述 11 家。自动化一律走"本地客户端/引擎 + HTTP API"，Puppeteer/Playwright/Selenium 直连调试端点。
- **开源补丁派**：rebrowser-patches（补自动化库的 CDP 泄漏，见 3.4）、patchright（Playwright fork）、zendriver-mcp（给 LLM agent 用的 CDP MCP 服务器）。不伪造指纹，只修"自动化痕迹"。
- **云托管派**：Scrapeless、GoLogin Cloud Browser、Rebrowser Cloud 等——直接卖一个 CDP WebSocket 端点（`wss://…?token&proxyCountry&fingerprint&profileId`），指纹 + 出口 IP + 无头环境全部服务端化。

## 1.3 产品总览表（2026-09-10，桌面核实）

CDP 直连：★ = 真 CDP 端口/WS 可直连；△ = 经自家本地 API/代理转发；— = 无/未证实

| 产品 | 公司/主体 | CDP 直连 | 官方自动化方式 | headless | 移动/Android | 价格档 | 官网/文档 |
|---|---|---|---|---|---|---|---|
| Octo Browser | 俄语区起家（2020-2026） | ★（移动端白名单式 CDP） | 自有 HTTP API 启动；Playwright `connectOverCDP` 官方示例 | 未证实 | 真机 App（Octo Mobile，iOS 有文档） | 订阅制，按档限 RPM 50/100/200+ | [docs.octobrowser.net](https://docs.octobrowser.net/en/api/cdp-automation/) |
| AdsPower | 广州标品软件 | ★（返回 ws.selenium / ws.puppeteer） | Local API :50325（限频 1 req/s） | 支持（no-UI/Linux） | UA 模拟；云手机由 DuoPlus 合作 | 免费档+付费 | [localapi-doc-zh.adspower.net](https://localapi-doc-zh.adspower.net/docs/SWZF3h) |
| BitBrowser（比特） | 中国 | ★（/browser/open 返回 ws + http + chromedriver 路径） | Local API，POST+JSON；文档站全量支持 .md（llms.txt） | 支持（args `--headless`） | ostype=Android/IOS；BitCloudPhone | 订阅制 | [doc.bitbrowser.net](https://doc.bitbrowser.net/zh/api-jie-kou-wen-dang/liu-lan-qi-jie-kou.md) |
| MoreLogin（摩尔） | 中国 | ★（/api/env/start 返回 debugPort） | Local API :40000；官方多语言示例 | 未证实 | 未证实 | 订阅制 | [guide.morelogin.com](https://guide.morelogin.com/api-reference/examples/puppeteer) |
| Multilogin | 爱沙尼亚（第三方口径，未证实） | ★（返回 port） | launcher.mlx.yt:45001 start?automation_type=…&headless_mode；CLI/Postman | 支持（headless_mode=true） | Android 云手机（ADB/Appium） | 高端；自动化需 Pro 10+ | [multilogin.org/help](https://multilogin.org/help/puppeteer-automation-example) |
| GoLogin | 欧美 | ★（云浏览器 WS URL 作 browserWSEndpoint） | 官方开源 Node/Python SDK；REST API | 支持 | UA 级 | 有免费档说法（未核实） | [gologin.com/docs](https://gologin.com/docs/api-reference/cloud-browser/getting-started) |
| Dolphin Anty | 波兰团队（第三方口径） | ★（官方建议 connectOverCDP） | 仅 REST API（无官方 SDK）；内置 Scenarios 录制 | 未证实 | 未证实 | 免费 10 profiles + 付费 | [scraping-wiki 条目](https://github.com/TheWebScrapingClub/scraping-wiki/blob/main/entities/dolphin-anty.md) |
| Kameleo | 匈牙利 | △★（Local API :5050 提供 ws://…/puppeteer/{profileId} 转发桥） | 官方 SDK（Python/C#/JS）+ Local API | 未证实 | SDK 按 device_type 检索 | 订阅制 | [github.com/kameleo-io/kameleo](https://github.com/kameleo-io/kameleo/blob/master/sdk/python/examples/connect_with_puppeteer/app.py) |
| Incogniton | WorkingGreen BV（荷兰，页脚官方） | ★（launch/puppeteer 返回 puppeteerUrl） | Local API :35000；官方 Node/Python SDK/CLI；Selenium grid | 支持（customArgs `--headless=new`） | 未证实 | 免费档+付费（API 功能分级） | [api-docs.incogniton.com](https://api-docs.incogniton.com/getting-started/introduction) |
| VMLogin | 中国 | ★（Local API :35000 返回 WebSocket/调试地址） | Local API + ExecuteScript + Selenium/Puppeteer 用例 | 支持（--headless / --no-startup-window） | 移动仿真 UA（官方承认 mobile 模式脚本失效） | 订阅制 | [vmlogin.cc/tutorial/6](https://www.vmlogin.cc/tutorial/6) |
| Hubstudio | 中国（"紫鸟国际版"说法未证实） | ★（返回 CDP 调试地址；字段名第三方写法 debuggerAddress，官方页未抓到正文） | Local/云端 API（app_id/app_secret），限频 100 req/min/接口；CLI；Linux Server 部署 | 支持 | 云手机能力重（ADB/App/RPA/TikTok 任务） | 订阅制 | [api-docs.hubstudio.cn](https://api-docs.hubstudio.cn/8331450m0) |
| rebrowser-patches | 开源 | —（补丁库，不改浏览器） | npx rebrowser-patches patch puppeteer-core/playwright | — | — | 免费开源 | [github.com/rebrowser/rebrowser-patches](https://raw.githubusercontent.com/rebrowser/rebrowser-patches/main/README.md) |
| zendriver-mcp | 开源（bituq） | —（MCP 服务器直连 CDP） | MCP 工具 96 个（DOM 快照省 ~96% token、Turnstile 求解等） | — | 设备模拟预设 | MIT | [github.com/bituq/zendriver-mcp](https://github.com/bituq/zendriver-mcp) |
| Scrapeless（云） | NST LABS TECH LTD | ★（wss://browser.scrapeless.com/api/v2/browser 单端点） | puppeteer.connect / connectOverCDP 指向同一端点 | 云端 | 无（服务端指纹含 UA/平台/时区） | 免费额度+按量 | [scrapeless.com](https://www.scrapeless.com/zh/blog/nodejs-undetected-fingerprint-browser) |

## 1.4 分产品要点（CDP/自动化视角）

**Octo Browser** —— 弱点自曝最全的官方文档
- 桌面端：自有 API 可为 Playwright/Puppeteer/Pyppeteer/Selenium/CDP 启动 profile，支持一次性 profile；API 按档限 RPM。
- 移动端（Octo Mobile，iOS 真机 App）：应用内 HTTP 服务固定端口 9222，返回 `webSocketDebuggerUrl`；官方示例 Playwright `chromium.connectOverCDP`。
- **官方自白（移动端）**：只实现 CDP 白名单方法（Page/Runtime/DOM/Input/Network/Storage/Target 子集），"未列出方法一律返回空结果（有意为之）"；严格 CSP/Trusted Types 站 `page.evaluate` 抛 EvalError（引擎无 inspector 特权、iOS 无法绕过）；iframe 内容不可访问；`page.route()` 不生效；`browser.newContext()` 明确报错。
- **输入事件官方承认不可全信**：iOS 文本输入走系统通道 `isTrusted:true`，但 keydown/press/up 与鼠标事件是合成事件，网站可用 `isTrusted` 识别。→ 这是"壳层 CDP + 合成事件"的最佳一手引证。

**AdsPower**
- Local API 默认 `local.adspower.net:50325`，官方限频：**所有接口合计 1 req/s**；支持 API Key 与无界面启动。
- 启动后返回 `ws.selenium`（Selenium debuggerAddress）与 `ws.puppeteer`（`ws://127.0.0.1:port/devtools/browser/…`）——"本地 API 只管生命周期，页面操作走真 CDP"。
- 内核：SunBrowser=Chromium、FlowerBrowser=Firefox 双内核；官方博客称 2025-05 升级 Chrome 136 → 2026-08 宣称 Chrome 151，更新约追平新版本/每季度级。
- 2026-08 上线 RPA 产品与 MCP/AI Agent（官方博客标题级证据）。

**BitBrowser（比特）**
- 文档质量高且全站可 .md 直取（llms.txt）。`/browser/open` 返回 `ws://127.0.0.1:<port>/devtools/browser/<uuid>`、http 端口、coreVersion（示例 '104'/'92'，**内核版本可选**）、chromedriver 路径——官方默认就是 CDP/WS + chromedriver 双通道。
- headless：官方示例 `args: ["--headless"]`。指纹对象极宽（canvas/webgl/audio/字体/时区/SSL 套件/防 MAC 泄漏/端口扫描保护等）。FAQ 官方警告：参数不规范会导致程序出错甚至闪退。

**MoreLogin（摩尔）**
- 官方示例：Local API :40000，`/api/env/start` 返回 debugPort，再 `puppeteer.connect({browserURL:'http://127.0.0.1:'+port})`。文档站提供多语言官方示例。
- 官方注明需本地客户端运行并登录。

**Multilogin**
- **自动化仅限 Mimic（Chromium）profile**：官方明示 Stealthfox（Firefox 系）不支持 Puppeteer —— "鱼与熊掌"的典型。
- 流程：云端 API 认证 token → 本地 launcher :45001 `start?automation_type=puppeteer|selenium|playwright&headless_mode=false` → 返回 data.port → `puppeteer.connect({browserURL})`；也可先 GET `/json/version` 取 webSocketDebuggerUrl。
- 自动化门槛：需 Pro 10 及以上套餐（FAQ 2026-08-26 更新）。

**GoLogin**
- 官方开源 SDK（npm gologin）`launch()` 直接返回可被 puppeteer 操作的 browser 对象。
- 云浏览器：给一个 connect URL（token+profileId），Puppeteer `connect({browserWSEndpoint})` 直连，profile/代理管理走 REST API。

**Dolphin Anty**：无官方 SDK、纯 REST API 管理 profile；自动化需自己写样板用 Playwright `connect_over_cdp`/Puppeteer 连运行中 profile；免费档 10 profiles。

**Kameleo**：Local API :5050 提供 **CDP 转发桥**（`ws://localhost:5050/puppeteer/{profileId}`），官方示例 pyppeteer connect；SDK 支持 Python/C#/Node；指纹检索按 device_type/browser_product 筛选，暗示多内核 base profile（细节未证实）。

**Incogniton**：Local API :35000；`POST /automation/launch/puppeteer` 返回 `puppeteerUrl` 再 connect；官方 headless 姿势 = customArgs `--headless=new`；含 Selenium grid、cookie robot、profile 云/本地同步；部分 API 功能需付费包。

**VMLogin**
- Local API :35000，接口偏"自家 API 化"（ExecuteScript base64 JS、findElement/sendKeys 等 Selenium 风格），但同时提供"IP 转 WebSocket 地址"与 puppeteer/selenium 远程调试配置（可设 0.0.0.0 监听 + 访问密码）。
- headless：`--headless`（有窗口）或 `--no-startup-window`（无窗口）两档。
- **官方承认的弱点**：移动仿真 mobile 模式下脚本无法生效，换 desktop 才能触发（tutorial/6）。指纹覆盖面宽（含模拟真人输入、HideWebdriver）。

**Hubstudio**：官方 API（app_id/app_secret），限频每接口 100 req/min；官方称可配合 Selenium/Puppeteer/Playwright；有 Linux Server 部署指南、CLI、多内核管理；云手机（ADB/App 安装/短信/截图/RPA/TikTok 任务）在 API 中占比很重。

**rebrowser-patches / zendriver-mcp / Scrapeless** 见 1.5 与 3.4。

## 1.5 技术面：指纹伪造与 CDP 自动化"泄漏"

- **指纹维度**（各产品覆盖度不同）：Canvas 噪声、WebGL 图像+元数据、audioContext 噪声、字体列表、时区/语言/UA/分辨率、hardwareConcurrency/deviceMemory、WebRTC、Geolocation、mediaDevices、speechVoices、plugins、clientRects 噪声、SSL 套件、电脑名/MAC 防泄漏、端口扫描保护等。
- **CDP 自动化可被页面检测的信号**（这就是"指纹浏览器不帮你隐身"的原因）：
  1. **`Runtime.enable` 副作用**：主流自动化库靠它拿 executionContextId，调用会产生页面可观察副作用（consoleAPICalled 相关，几行 JS 可测）。DataDome 研究员 Antoine Vastel 的文章（2024-06）指出该信号被反 bot 广泛使用；rebrowser README 称 Cloudflare/DataDome 等"所有大反 bot"都在用。
  2. 注入脚本的 `//# sourceURL=pptr:…`、utility world 名（puppeteer_util_…）。
  3. **合成事件 `isTrusted=false`**（Octo 官方自曝键盘事件不真实）。
  4. headless 的默认指纹（字体/canvas 行为、UA 特征）；`navigator.webdriver`。
  5. 严格 CSP/Trusted Types 站 `page.evaluate` 直接失败（Octo Mobile 与 Pulsar 的已知场景同源，见 AGENTS.md CDP 陷阱）。
- **免费检测点**（公开测试页）：kaliiiiiiiiii.github.io/brotector、deviceandbrowserinfo.com/are_you_a_bot、hmaker.github.io/selenium-detector、bot-detector.rebrowser.net。
- **反制悖论**：页面端看不到 CDP socket，但能通过协议副作用暴露自动化；改 console/Proxy、真开 DevTools 等手段又会引入 timing/Proxy 可测特征——猫鼠游戏永续。

## 1.6 关键结论（第一部分）

1. **"支持 CDP"≠全协议**：绝大多数是"本地 API 启动 + 白名单壳层/标准端点"；接入前必须确认**哪些 CDP 域可用**、frames/route/newContext/多 context 是否受限。
2. **接入姿势五花八门、互不兼容**：browserURL（MoreLogin/Multilogin/Incogniton/VMLogin）、browserWSEndpoint（GoLogin 云/Scrapeless/Kameleo 桥）、connectOverCDP（Octo/Dolphin）、Selenium debuggerAddress（AdsPower；BitBrowser 还给 chromedriver 路径）。同一脚本换产品必然要改连接层——这正是 MCP 化/适配层中间件（zendriver-mcp、各家 Agent/RPA）的市场空间。
3. **CDP 自动化本身可被检测，多数指纹浏览器不修**：需要 rebrowser-patches 类补丁叠加，且补丁只支持 Chrome、随库版本易碎。
4. **内核落后与多内核并存**：AdsPower 从 Chrome 136（2025-05）追到 Chrome 151（2026-08）；BitBrowser 用户自选 coreVersion；老内核 = 兼容性负担 + 可识别信号。Firefox 分支（Stealthfox/FlowerBrowser）牺牲自动化换取差异化。
5. **"无头"≠"无窗口"**：VMLogin 区分两档；headless 默认指纹本身可被检测；headless+自动化叠加时检测面最大。
6. **移动端是 2025-2026 新战场但坑最深**：真机路线（Octo Mobile）CSP 下 evaluate 残废、事件不真实；云手机路线（BitBrowser/Hubstudio/Multilogin）用 Android 容器+ADB 绕开假移动问题；VMLogin 官方承认 mobile 仿真模式下脚本失效。
7. **API 限流/配额是隐性成本**：AdsPower 1 req/s、Hubstudio 100 req/min、Octo RPM/RPH 配额、Multilogin 自动化 Pro 10+ —— 批量编排前必须做容量规划。
8. **云化 CDP 端点是第三条路**：一个 wss URL + token，指纹/出口 IP/无头环境服务端化，集成成本最低，但自控力与计费结构不同。

---

# 第二部分　面向 AI、底层重写的浏览器

## 2.1 赛道格局（2026-09）

2026 年 AI 浏览器赛道分化为"两条路线 + 一条存量暗线"：

- **路线 A｜人机共存的消费级 AI 浏览器**：Perplexity Comet、Atlassian 旗下 Dia 为代表，均为 Chromium fork + 深度 AI 改造。正在残酷洗牌：**OpenAI Atlas 已死**（2026-08-09 停服，仅 292 天）、**谷歌 Project Mariner 退役**（2026-05-04）、Arc 冻结（2025-05-27 起维护模式）。
- **路线 B｜Agent 运行时 / 执行层**：2026-08-06 因 **Cloudflare Kitesurf** 发布而正式成形——浏览器不再给人看，而是"机器可读 DOM 进、结构化数据出"的无头执行环境，跑在边缘计算上。同一周 **Hark** 拿 $700M A 轮做"每任务一台 VM"的对立路线。
- **暗线｜存量浏览器 AI 化 + 标准层釜底抽薪**：Chrome（Gemini/Auto Browse）、Edge（Copilot）、夸克/QQ 浏览器等；Google+微软推动 W3C **WebMCP**（"网站即工具"）。

**资本结论**：不再赌"浏览器入口产品"，而在赌"Agent 执行层 + 会话 + 计量"。

## 2.2 Cloudflare Kitesurf —— 第一个"Agent-native 浏览器运行时"（唯一 from scratch）

- **定位**：无头、无状态、为 AI Agent 而生、跑在 Workers V8 isolate 里的浏览器运行时——官方明言砍掉 tabs/主题/扩展/像素级渲染等"只有人需要的功能"，优化目标是 token 数、上下文、伸缩性与成本；生命周期 = 单次任务；"stateless、ephemeral、fully-isolated"。
- **引擎（从零自研）**：**完全没有 Chromium**，无人类像素渲染管线。Rust 编译为 WebAssembly 运行于 Workers 的 V8 isolate（页面内 JS 由 Rust 系 JS 引擎执行，CSDN 拆解称 Boa、无 JIT）。四组件信任边界：Engine（唯一对外组件，**CDP over WebSocket + REST**）、PageScript（Dynamic Workers 每页/跨进程 iframe 长驻 isolate）、PageRenderer（软光栅化出 JPEG/PNG/PDF，无 GPU）、SandboxOutbound（唯一网络出口，强制 CORS、注入浏览器形状请求头、每页独立 cookie 罐）——页面内容一律按不可信输入处理以抗 prompt injection。首次 commit（2026-05）到生产级 Beta 仅 12 周。
- **协议**：对外暴露 **CDP 端点**（`wss://…/browser-run/devtools/browser?browser=kitesurf`），现有 Puppeteer/Playwright/chrome-remote-interface 可零改动切换；另有 REST Quick Actions（截图/HTML 提取/PDF）；MCP 客户端通过官方给出的 chrome-devtools-mcp 配置即可接入。**即接口生态全面兼容 CDP，而非发明新协议**。
- **基准（官方自测）**：14-URL 语料 × 5，对比 Chromium 热池——CPU 省 3.1–3.8×、内存省 4.7–7.0×，但墙钟时间慢 1.7–1.8×；WPT 通过 235,000+ 子测试（DOM 97%、HTML 96%、Selection 99%、CORS 95%…），正确渲染 TodoMVC/Wikipedia/HN/Cloudflare 面板。
- **官方承认的不能做**：视频/WebGL、真实 TLS 指纹的 bot 挑战握手（中国市场即"403 之墙/极验"过不去）、需持久状态的长登录会话——官方建议这些场景仍用 Chromium 默认浏览器。
- **时间线/商业**：2026-08-06 Agents Week 发布（TechCrunch 2026-08-07 报道）；Beta 免费、per-account 限额，正式定价未公布；**开源仅在路线图上**（Forkast 2026-08-09，截至 2026-09-10 未见仓库，时间表未证实）。现有 Browser Run（Chromium）定价 $0.09/浏览器小时、每月 10 小时免费（第三方转引）。
- 来源：[Cloudflare 官方博客](https://blog.cloudflare.com/kitesurf/)、[Kitesurf 官方文档](https://developers.cloudflare.com/browser-run/kitesurf/)（2026-09-05 更新）、[TechCrunch](https://techcrunch.com/2026/08/07/cloudflare-launches-kitesurf-a-browser-built-for-ai-agents/)、[Forkast 分析](https://forkast.news/cloudflares-kitesurf-is-the-first-agent-native-browser-runtime-and-a-bet-on-owning-the-distribution-layer/)

## 2.3 Perplexity Comet —— 最完整的跨平台消费级 agentic 浏览器

- **引擎**：**Chromium 系 fork + 深度 AI 层，非自研引擎**（官方帮助中心有专门条目[What is Comet's Browser Engine?](https://www.perplexity.ai/help-center/comet/zh-CN/articles/11583798-what-is-comet-s-browser-engine)，但正文多次抓取失败；以 [SiliconANGLE 发布报道](https://siliconangle.com/2025/07/09/perplexity-introduces-comet-browser-ai-powered-automation-tools/)与 [Zenity Labs 逆向](https://labs.zenity.io/post/perplexity-comet-a-reversing-story)（2026-02-11）交叉证实）。
- **关键逆向发现（Zenity）**：agent 自动化**不在引擎层**，而是三件自研 Chrome 扩展：comet-agent（700KB service worker 的完整 RPC 系统，执行点击/输入等细粒度动作）、Comet（编排/侧栏/标签生命周期/历史）、Comet Web Resources（本地 CDN）。扩展不进 Chrome Web Store，经 perplexity.ai/rest/browser/update-crx 自更新。双通道：SSE 流（对话 UI）+ WebSocket `wss://www.perplexity.ai/agent`（自动化 RPC）。硬边界：禁 chrome:// 与 comet:// 内部页、禁 file://、域名黑名单。
- **AI 能力**：Agentic Search（读当前页/跨标签总结、比价、日程、研究）、Assistant（读）与 Agent（执行）分层、Voice Mode（2026-02）、可复用快捷指令；MCP 走官方 Sidecar 连接外部服务（Slack/GitHub/Asana/Linear/Notion/Atlassian/Gmail/Calendar/Shopify）。
- **CDP/MCP**：官方未宣传对外开放 CDP（未证实）；但社区已出现 [mcp-comet](https://github.com/OneStepAt4time/mcp-comet)（"Comet browser management via Chrome DevTools Protocol"）这类非官方 CDP 控制工具。
- **时间线/状态**：2025-02-24 宣布 → 2025-05 Mac 内测 → 2025-07-09 发布 Win+macOS（并入 Perplexity Max）→ 2026-03 免费开放 → iOS 2026-03、Android 跟进；四平台全量（2026-08）。第三方报道 2026-06 为 Comet 融 $200M、估值 $20B（[The Agent Report 转述](https://dev.to/docdavkitty/perplexity-raises-200m-for-comet-the-ai-browser-that-wants-to-be-the-agent-economys-front-door-2cm3)，官方未核实）。
- **法律红利**：2026-08-04 美国第九巡回上诉法院推翻针对 Comet 购物功能的 CFAA 禁令——"助手是工具不是人"（[Reuters](https://www.reuters.com/business/retail-consumer/amazon-loses-us-court-ban-perplexitys-ai-shopping-tools-2026-08-04/)）。
- **风险**：Zenity 的 PerplexedBrowser（零点击本地文件泄漏）、LayerX/Cato 攻击研究、Gartner"暂时封杀 AI 浏览器"建议。

## 2.4 Dia —— The Browser Company → Atlassian（"第一家 AI 浏览器公司卖了 43 亿"）

- **收购**：被 **Atlassian 以 6.1 亿美元现金收购（≈43 亿人民币）**，2025-09-04 宣布（[BusinessWire 官方公告](https://www.businesswire.com/news/home/20250904645125/en/)、[量子位/智源](https://hub.baai.ac.cn/view/48708)、[36氪](https://m.36kr.com/p/3453306741626246)），2025-10-21 交割；TBC 此前累计融资约 $1.28 亿。Josh Miller 称在 Atlassian 旗下独立运营继续开发 Dia；Arc 已于 2025-05-27 进入维护模式。
- **引擎**：**不是 from scratch——是 Chromium 深度改造**（与 Arc 同源），改造集中在 UI/交互 + AI 层而非渲染引擎。早期弱化标签机制，2026-05 补回侧栏/垂直标签；Chrome 扩展是"二等公民"（手动重装、部分 user-agent 嗅探扩展不兼容）。公开资料中查无官方 "context hub" 架构名词（未证实）；上下文卖点 = 针对 SaaS 优化、标签页带上下文、AI 技能 + 个人"工作记忆"。
- **AI 能力**：对话式 AI 侧栏（总结/起草/跨标签上下文）；技能系统 200+ 预设工具；免费层限量，Dia Pro $20/月（2025-08-06 推出）。
- **自动化开放程度**：作为"给人用"的产品，**未见 CDP 端口、官方 MCP/SDK、headless 服务（未证实有）**。
- **时间线**：2025-06-11 内测 → **2025-10-10 结束邀请制、macOS 全量开放**（[9to5Mac/品玩](https://www.pingwest.com/w/308154)；注意公开报道时间为 2025-10，非 2026）→ 截至 2026-06 仍仅 macOS 14+ Apple Silicon，Windows 只有 waitlist（[SuperchargeBrowser 核验](https://www.superchargebrowser.com/library/dia-browser-vs-chrome-extensions/)）。2026-08 Atlassian Team '26 继续把 Dia 与 Rovo/Teamwork Graph 绑定推广。
- 参考：[TechCrunch（Dia beta）](https://techcrunch.com/2025/06/11/the-browser-company-launches-its-ai-first-browser-dia-in-beta/)、[Dia 技术深读（everettjf，2026-01）](https://everettjf.github.io/2026/01/02/dia-analyze/)

## 2.5 OpenAI Atlas —— 失败案例（重要参考系）

- **是什么**：把 ChatGPT 嵌进浏览器的 AI 原生浏览器：地址栏问 ChatGPT、Ask ChatGPT 侧栏、Browser Memories（浏览历史语义化）、Agent Mode（多步任务）。
- **架构**：Chromium 底层 + 自研 **OWL（OpenAI Web Layer）**：把 Chromium browser process 与用 SwiftUI/AppKit/Metal 重建的原生 UI 分离——属"Chromium 深度魔改 + 原生 UI 重写"，非引擎级自研。
- **时间线**：2025-10-21 发布 → 2026-03-10 最后 Release Notes → 2026-04 传将整合 ChatGPT/Codex → **2026-07-09 宣布下线（~30 天迁移）→ 2026-08-09 停服，共存 292 天**。
- **失败原因**（[36氪/机器之心](https://www.36kr.com/p/3933512701410438)、[界面新闻](https://www.jiemian.com/article/14924819.html)）：① 完整浏览器工程负担（IME/历史/1Password/扩展/密码/Cookies/DevTools/安全更新）远超 AI 功能；② Agent 不稳（The Verge 实测加购 10 分钟 vs Comet 2 分钟）；③ prompt injection 结构性风险；④ 仅 Apple Silicon Mac，其余平台到停服未成；⑤ 迁移成本 vs Chrome 68–70% 存量份额（StatCounter 2026）；⑥ 第三方估 MAU 仅 ~1100 万（Q1 2026）vs ChatGPT 8 亿周活。
- **结局与启示**：能力并入 ChatGPT 桌面端/Chrome 侧栏与 Codex + Operator（[OpenAI 帮助中心](https://help.openai.com/zh-hant-hk/articles/20001371-evolving-atlas-into-chatgpt-for-browser-based-agentic-work)）——"独立 AI 浏览器=下一代大众入口"叙事被证伪，**从入口退回工具/生产工具定位**。

## 2.6 其他玩家与动向

- **Hark**（Brett Adcock，Figure 创始人）："给 Agent 一台专属 VM 电脑"的对立路线（浏览器+文件系统+终端，坐标/按键级操作）；$700M A 轮、约 $6B 估值；宣称 Online-Mind2Web 97.7 分与按 token 定价——厂商自证，eWeek 等质疑无第三方复测（[TechCrunch](https://techcrunch.com/2026/08/05/hark-previews-its-browser-use-agent-for-completing-tasks/)、[eWeek](https://www.eweek.com/news/hark-handoff-browser-agent-benchmark-claims/) 抓取 403 仅取标题）。
- **托管 headless Chromium 云**：Browserbase（2025-06 融 $40M B 轮，约 $300M 估值）、Steel、Browser Use（$17M）。Kitesurf 若兑现 3–7× 成本优势，直接威胁其"按 Chrome 虚拟机"定价。
- **谷歌**：Project Mariner 2026-05-04 关停，能力并入 Gemini Agent；Chrome 2026-02 上线 Auto Browse（多步任务）；与微软在 W3C 推 **WebMCP**（网站注册为结构化工具 `navigator.modelContext.registerTool()`、浏览器内继承登录态、免 OAuth；Chrome 149 Origin Trial；W3C 评审批评安全/隐私章节空白）。
- **微软**：Edge Copilot Mode 等把 AI 塞进存量浏览器。
- **中国厂商（一句话带过）**：夸克 AI、QQ 浏览器 QBot（混元+DeepSeek）、360 AI 浏览器、字节豆包桌面浏览器+任务模式（2026-07-15）、美团 Tabbit（GN06）、智谱 AutoGLM 2.0 ——普遍走"存量浏览器+AI"或"安卓云机"路线而非自研引擎；监管（拟人化办法 2026-07-15 施行、广州互联网法院 2026-04-30 双重授权裁定）把 C 端 AI 上网压向"工作助手"合规缝隙。

## 2.7 框架视角：Agent 原生浏览器与"运行时层"的 2026 分岔（[CSDN 深度文](https://blog.csdn.net/summerliyang/article/details/163618159)，AI 辅助个人专栏，数字建议回查一手）

1. **分水岭**：Kitesurf（2026-08-06）标志浏览器从"应用层"沉到"基础设施层"；战场 = 浏览器运行时（引擎、会话、网络出口、计量）。
2. **数据**：Cloudflare CEO 2026-06 称 agentic/bot 流量约占 HTML 请求 57.5%（机器首超人类）；HUMAN Security 称浏览器类 Agent 占 agentic 流量约 71%；单 agent 任务可达 5000 页 vs 人类 5 页。
3. **成本经济学**：token 才是大头——单动作成本约 $0.53（约 $0.12 算力 + $0.38 token）；无障碍树方案 15k–40k token/动作 vs 结构化 DOM 可压到几千。计量四层：模型层按 token、平台层按 agent-min（Operator 2.0 $0.30、Comet $0.18）、基建层按会话/小时（Browser Run $0.09/h）、任务层按结果（成品脚本 $0.03 vs 通用 LLM agent $3.41，价差 113×）——"按干活计费取代按席位计费"（WaaS）。
4. **安全/法律**：prompt injection 已成事件级威胁（LayerX BioShocking 骗取 6 款 AI 浏览器登录态、Zenity PerplexedBrowser 触及 1Password）；EU AI Act §50 2026-08 起要求 bot 自曝；Cloudflare Pay Per Crawl 私测"Agent 身份 + 按爬付费"。
5. **中国视角**：Kitesurf 式运行时撞"403 之墙"（TLS 指纹/极验）与合规墙；本土走云手机/云电脑路线；商业化出口 = 金融（2026 H1 招标 97 条、银行占 48.5%）与政务（数据不出域+信创+审计）。

---

# 第三部分　底层引擎与协议标准（"底层重写"的另一层含义）

## 3.1 Ladybird —— 引擎级"重写"样本（但非 agent 底座）

- **转 Rust 时间线**：官方公告 **2026-02-23**（[Ladybird adopts Rust, with help from AI](https://ladybird.org/posts/adopting-rust/)；[LWN 报道](https://lwn.net/Articles/1059812/)）。背景：2024 押注 Swift，Swift 跨平台与 C++ interop 未达预期被移除；改 Rust 理由 = 生态成熟、内存安全。
- **纠偏**：**不是整体重写**——官方明言"C++ 继续开发引擎，Rust 移植是长期并行支线"，由 core team 管控。
- **AI 辅助开发的标志性案例**：LibJS 用 Claude Code + Codex 人控翻译（数百小提示、多模型对抗性审查），约 2.5 万行 Rust、两周完成；test262 52,898 项 + 回归 12,461 项零回归。到 2026-07 样式系统/布局引擎大部分迁 Rust；CSS parsing/DOM/painting 未动。
- **2026-09 状态**：目标 Alpha 2026（Linux/macOS），截至 8 月报未发布；7 月 WPT 约 207.9 万子测试通过，第三方称引擎排名第 4；DevTools 暂用 **Firefox DevTools 客户端及协议过渡——即尚无自有自动化/远程控制协议**（无 WebDriver/CDP/BiDi，agent 生态为零，短期不适合做 agent 底座）。
- **治理插曲**：2026-06-05 官方宣布停止接受公开 PR、仅维护者提交（媒体归因于 AI agent 刷 GitHub 声誉，二手转述）；资金靠捐赠（Platinum $100k/年：FUTO、Shopify、Cloudflare 等），声明 18 个月 runway。
- 来源：[官方公告](https://ladybird.org/posts/adopting-rust/)、[LWN](https://lwn.net/Articles/1059812/)、[7 月月报](https://ladybird.org/newsletter/2026-07-31/)、[dev.to 分析](https://dev.to/jasondevlab/ladybird-just-closed-its-doors-to-public-prs-ai-reputation-farming-is-why-5f13)

## 3.2 Servo —— 从零 Rust 引擎 + WebDriver + MCP 的先锋组合

- **定位与治理**：Linux Foundation Europe 项目，"轻量高性能、供嵌入应用的替代引擎"（非消费级浏览器）。2025-10 起月度发布；**2026-04-13 servo crate v0.1.0 上 crates.io** + 半年节奏 LTS；**2026-08-31 v0.5.0**（[官方博客](https://servo.org/blog/2026/08/31/july-in-servo/)、[0.1.0 发布](https://servo.org/blog/2026/04/13/servo-0.1.0-release/)）。
- **2026 成熟度**：DuckDuckGo 首页可渲染、Gumroad 大部分页面接近完美；2D canvas 多线程（帧率最高 +55%）、文本渲染最快 10x、WebGPU/IndexedDB/a11y 增量、Android 10+；**内建 W3C WebDriver 持续完善**；仍"每月 breaking change"，官方不讳言离通用浏览器尚远。捐赠约 $7.8k/月。
- **servo-agent 案例**（[README 一手](https://raw.githubusercontent.com/parker-brown-family/servo-agent/main/README.md)）：agent(MCP) → servo-agent → **Servo 内建 W3C WebDriver** → headless servoshell。核心增值 `read_page`：渲染后 DOM 蒸馏成干净 markdown，自测 5 站平均 **~59× 压缩**（rfc-editor 2MB→12KB）；以 MCP server（stdio）提供 13+ 工具；动机自述：CDP+Chromium 栈"重、难插桩、易被 bot 检测"，Servo 内存安全、可端到端拥有——这是"从零引擎 + 标准 WebDriver + MCP"的完整 agent 底座雏形，但能力面仍有限（需自备 servoshell、引擎月度变动）。

## 3.3 WebDriver BiDi vs CDP —— 协议态势（2026-09）

- **Mozilla/Firefox（最强证据）**：Firefox 源码文档明确"**CDP 支持已终结，WebDriver BiDi 是唯一可用协议**"，`remote.active-protocols` 偏好项随 CDP 支持结束在 **Firefox 141** 移除（[官方文档](https://firefox-source-docs.mozilla.org/remote/Prefs.html)）。即 Firefox ≥141 无 CDP。
- **Selenium**：2025 官方博客 "Removing ChromeDevTools Support For Firefox"，[PR #17849](https://github.com/SeleniumHQ/selenium/pull/17849) 在绑定层阻止对 Firefox 的 CDP 访问；2026 起 BiDi 为推荐通道（[QASkills 参考](https://qaskills.sh/blog/selenium-webdriver-bidi-2026-official-reference)，第三方），但承认极底层 Chromium 特性仍需 CDP fallback。
- **Chrome/Edge**：原生实现 BiDi，但默认生态仍是 CDP。**WebKit**：2026 年起 GLib 端口按 [WebDriver BiDi 系列 bug](https://bugs.webkit.org/show_bug.cgi?id=308461) 推进，早期；Safari/iOS BiDi 状态未证实。
- **工具迁移**：Puppeteer Firefox 默认即走 BiDi、Chrome 默认仍 CDP（显式 `protocol:'webDriverBiDi'`）；BiDi 缺口（抛 UnsupportedOperation）：emulations、createCDPSession、Coverage/Tracing/无障碍、drag 系列、Service Worker 拦截等——**重能力场景仍绑 Chrome+CDP**。
- **结论**：BiDi 消除了"跨浏览器自动化"对 CDP 的依赖（Firefox 侧被强制），但 **agent 自动化短期不会脱离 CDP**："Chrome=CDP 为主、跨浏览器=BiDi"双轨长期并存。

## 3.4 开源"抗检测 CDP"生态（与第一部分衔接）

- **rebrowser-patches 原理（一手纠偏）**：patch 的是 **Puppeteer/Playwright 的 JS 库源码**（`npx rebrowser-patches patch --packageName puppeteer-core`），**不是 Chrome 二进制**。核心修 CDP `Runtime.enable` 泄漏（主流库靠它拿 executionContextId，调用触发页面可检测副作用）：默认不发 Runtime.enable，用 addBinding（默认）/alwaysIsolated/enableDisable 三模式手工取 context id；另把 `//# sourceURL=pptr:…` 与 utility world 名去特征化。配套 drop-in 包 rebrowser-puppeteer/playwright、检测页 bot-detector.rebrowser.net。
- **限制（自述）**：脆弱，库一变即失效，**每次 npm install 后需重跑 patch**；完整测试版本停在 puppeteer 24.8.1（2025-05-06）/playwright 1.52.0（2025-04-17）；只支持 Chrome；Windows 需 Git 的 patch.exe；page.pause() 冲突；非万能（还需代理/UA/canvas/WebGL 指纹）。
- **2026 生态**：Rebrowser 云浏览器（面向 AI agents/抓取）；patchright（Playwright fork）；Camoufox（Firefox 系反检测）；npm 出现 mcp-patchright/mcp-camoufox —— **"抗检测浏览器"正被包装成 agent 工具链标准件**。
- 来源：[README](https://raw.githubusercontent.com/rebrowser/rebrowser-patches/main/README.md)、[原理博客](https://rebrowser.net/blog/how-to-fix-runtime-enable-cdp-detection-of-puppeteer-playwright-and-other-automation-libraries-61740)、[DataDome 原文](https://datadome.co/threat-research/how-new-headless-chrome-the-cdp-signal-are-impacting-bot-detection/)（403 未直读，被上述博客转引）

---

# 第四部分　对 Browser4 / Pulsar 的启示

结合本仓库事实（PulsarWebDriver 包装 CDP；Chrome 以 `--remote-debugging-port=0` 启动（`cli/browser4-cli/src/managed_processes.rs`）；`daemon.rs` 支持扫描运行中进程的 `--remote-debugging-port=N` 并**附加到已启动的浏览器**；`PulsarSessionManager` 亦有 attach 提示；快照体系含 a11y snapshot / htmlsnapshot summary；CLI 经 MCP over HTTP 与后端通信）：

1. **指纹浏览器可作为可选"浏览器后端"接入**：凡是返回标准 CDP 端点（ws/devtools/browser 或 127.0.0.1:port）的产品（AdsPower、BitBrowser、MoreLogin、Multilogin、Octo、Kameleo、GoLogin、Incogniton、VMLogin、Hubstudio，乃至 Scrapeless 云端 wss）都可复用现有 attach 路径。但须过三关：①**白名单/域差异**——Pulsar 依赖的 CDP 域若不在其壳层内（如 Octo Mobile 只支持 Target/Page/Runtime/DOM/Input/Network/Storage 子集），需降级或拒绝；②**生命周期外置**——profile 由各家客户端管理，Pulsar 需要"启动→等端口→attach→回收"适配层与重试（官方普遍限频：AdsPower 1 req/s 等）；③**内核版本差异**——老内核（coreVersion 92/104）与新版 CDP 方法缺失需探测兜底。若需求只是"会话隔离 + 独立指纹"，普通 Chrome + 独立 user-data-dir + 出口代理已覆盖大部分低端场景；若需要**抗关联级别的指纹伪造**（canvas/WebGL/字体等内核级伪造），纯 CDP 层做不全，才值得接商业指纹浏览器。
2. **反检测纪律是本仓库已有资产**：AGENTS.md 中关于 isTrusted、随机延迟（90–240ms）、passive wheel listener、setSelectionRange 的 gate，与第三方检测面（合成事件、固定时序、Runtime.enable 类可观察调用）完全对应；继续避免引入可观察的 CDP 副作用调用、保持 evaluate 注入去特征化（sourceURL 等），就是在给自动化"降噪"。已知的严格 CSP 站 evaluate 失败（Octo Mobile 自曝同因）应保留为显式错误而非静默失败。
3. **Agent 化趋势与产品形态吻合**：① 本仓库的"token 友好"快照（a11y snapshot、htmlsnapshot summary、eval 输出）正对应当前 agent 浏览器行业的头号优化方向（对标 servo-agent read_page 的 ~59× 压缩与 Comet/Playwright MCP 的 DOM 蒸馏）；② CLI↔后端走 **MCP over HTTP**，与行业"CDP 端点 + chrome-devtools-mcp 接入"的姿势同构，未来接 MCP 客户端/被 MCP 客户端调用都顺；③ 若要进军"Agent 执行环境"形态（对标 Kitesurf/无头运行时），CDP 兼容端点、会话级隔离、审计日志、按会话/agent-分钟计量是入场券——仓库已有 session 调度与 profile 模式（DEFAULT/SEQUENTIAL/TEMPORARY）基础。
4. **协议双轨意味着保持抽象**：Firefox ≥141 无 CDP、Selenium 已移除 Firefox CDP；若未来要支持 Firefox/WebKit 后端，需预留 WebDriver BiDi 抽象（本仓库 CDP 域调用应集中收敛，避免散落硬编码 CDP 调用）。Chrome 主场短期不变，BiDi 迁移不是紧迫项，但"CDP 域使用清单 + 能力探测"是低成本保险。
5. **对"自研引擎"路线保持观察而非押注**：Kitesurf 证明无 Chromium 引擎 12 周可行，但三大不能做（视频/WebGL、真实 TLS 指纹反爬、长登录会话）恰是高价值自动化场景刚需；Ladybird 无自动化协议、Servo WebDriver 能力面有限。结论：短期内"Chromium 内核 + CDP + 反检测纪律"仍是 agent 浏览器的主航道，自研引擎作为第二曲线观察（Servo 系 + WebDriver + MCP 组合值得跟踪）。
6. **风险与合规**：CDP 自动化可被检测是物理现实（无银弹）；商业指纹浏览器本身处于灰色地带（多账号/反爬用途），接入前需评估服务条款与当地法规（EU AI Act §50 bot 自曝、中国拟人化办法/双重授权口径）。

---

# 附录

## A. 术语速查

| 术语 | 含义 |
|---|---|
| CDP | Chrome DevTools Protocol，Chrome/Chromium 的自动化与调试协议 |
| WebDriver BiDi | W3C 标准化双向协议，Mozilla/Selenium 力推的 CDP 跨浏览器替代 |
| 指纹浏览器 / antidetect browser | 每环境伪造自洽浏览器指纹 + 身份隔离的浏览器 |
| connectOverCDP / browserWSEndpoint | Puppeteer/Playwright 两种 CDP 连接姿势（HTTP 调试端点 / WS 端点） |
| MCP | Model Context Protocol，agent 工具接入标准（本仓库 CLI↔后端即 MCP over HTTP） |
| WebMCP | Google/微软推的 W3C 提案：网站注册为结构化工具、浏览器内继承登录态 |

## B. 未证实/抓取失败清单（诚实声明）

- Perplexity 帮助中心 Comet 引擎条目正文多次抓取失败（以 SiliconANGLE + Zenity 逆向交叉证实 Chromium 系）。
- Dia "context hub" 官方架构名词查无出处；Dia 未见 CDP/MCP/SDK。
- Kitesurf 开源时间表（仅 Forkast 称在路线图，未见仓库）；其基准/WPT 为厂商自测。
- Comet $200M@$20B 融资为第三方报道；$0.18/agent-min、Browser Run $0.09/h、Operator $0.30/agent-min 等计费数字均为二手转引。
- Hark 97.7 分 / 0.8s 与定价为厂商自证，eWeek 质疑无第三方复测。
- DataDome 原文 403、Security Boulevard CDP 注入检测文两次 fetch 失败、知乎 403、SegmentFault 404、Phoronix/eWeek 等 403（仅取标题/二手引用）；Multilogin 注册地与 Octo 公司注册地、Hubstudio"紫鸟国际版"说法、GoLogin/MoreLogin 免费档等为第三方口径。
- Ladybird alpha 发布状态（截至 8 月报推断未发）、是否用 GitHub Sponsors、Safari/iOS BiDi 支持、Playwright 官方 BiDi 现状——未证实。
- 所有指纹/性能/压缩率数字均未经本报告实测；后续建议：真机跑通 2-3 家产品的 Local API + Puppeteer 连接，实测白名单域清单与端到端延迟。

## C. 主要参考链接

- 官方文档：Octo CDP 自动化 <https://docs.octobrowser.net/en/api/cdp-automation/> ｜ AdsPower Local API <https://localapi-doc-zh.adspower.net/docs/SWZF3h> ｜ BitBrowser 接口 <https://doc.bitbrowser.net/zh/api-jie-kou-wen-dang/liu-lan-qi-jie-kou.md> ｜ Multilogin Puppeteer 示例 <https://multilogin.org/help/puppeteer-automation-example> ｜ Kameleo 示例 <https://github.com/kameleo-io/kameleo/blob/master/sdk/python/examples/connect_with_puppeteer/app.py> ｜ Incogniton API <https://api-docs.incogniton.com/getting-started/introduction> ｜ VMLogin <https://www.vmlogin.cc/tutorial/6> ｜ Hubstudio <https://api-docs.hubstudio.cn/8331450m0> ｜ GoLogin <https://gologin.com/docs/api-reference/cloud-browser/getting-started>
- AI 浏览器：Kitesurf 官方博客/文档 <https://blog.cloudflare.com/kitesurf/> <https://developers.cloudflare.com/browser-run/kitesurf/> ｜ Comet 引擎条目 <https://www.perplexity.ai/help-center/comet/en/articles/11583798-what-is-comet-s-browser-engine> ｜ Zenity 逆向 <https://labs.zenity.io/post/perplexity-comet-a-reversing-story> ｜ BusinessWire（Atlassian 收购）<https://www.businesswire.com/news/home/20250904645125/en/> ｜ Atlas 复盘（36氪）<https://www.36kr.com/p/3933512701410438> ｜ 界面新闻 <https://www.jiemian.com/article/14924819.html> ｜ CSDN 分岔文 <https://blog.csdn.net/summerliyang/article/details/163618159>
- 引擎/标准：Ladybird Rust 公告 <https://ladybird.org/posts/adopting-rust/> ｜ LWN <https://lwn.net/Articles/1059812/> ｜ Servo 7 月报 <https://servo.org/blog/2026/08/31/july-in-servo/> ｜ servo-agent <https://raw.githubusercontent.com/parker-brown-family/servo-agent/main/README.md> ｜ Firefox CDP 终结官方文档 <https://firefox-source-docs.mozilla.org/remote/Prefs.html> ｜ Selenium PR <https://github.com/SeleniumHQ/selenium/pull/17849>
- 反检测：rebrowser-patches README <https://raw.githubusercontent.com/rebrowser/rebrowser-patches/main/README.md> ｜ zendriver-mcp <https://github.com/bituq/zendriver-mcp> ｜ Scrapeless <https://docs.scrapeless.com/en/scraping-browser/quickstart/introduction/>
