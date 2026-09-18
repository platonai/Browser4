# 补充调研：Kitesurf 的同类与"从头打造"浏览器全景（2026-09-10）

> 姊妹篇：主报告见 `docs-dev/research/2026-09-10-cdp-antidetect-and-ai-native-browsers.md`
> 本补充回答追问："再查 Kitesurf 的同类产品、从头打造的浏览器，不止这些"——即把"agent 原生运行时 + 无 Chromium 自研引擎"这一族彻底铺开。
> 调研日期：2026-09-10；桌面核实；厂商自测数据均标注；未证实项见附录 B。

---

## 0. 一句话结论（本补充的增量发现）

1. **Kitesurf 不是孤例，而是一个正在成形的"产品族"**：开源自托管的 **Obscura**（Rust+V8+CDP，2026-04-13 建仓，8 月底 2.2 万+ stars）与自研渲染引擎的 **aginxbrowser**（Rust+V8+diting 引擎）是同一设计哲学的另外两个代表；甚至有第三方称 **Kitesurf 的第一个原型就是把 Obscura 移植到 Workers**（未获 Cloudflare 官方证实）。
2. **"从头打造"叙事需修正**：Kitesurf 的引擎并非单组织逐行自研，而是 **Blitz（DioxusLabs）+ Stylo（Firefox/Servo）+ Boa（Rust JS）+ Parley（文本整形）** 等开源 Rust 组件的快速拼装（12 周）；2026 年的"自研浏览器"更多是"Rust 组件级整合 + 自研渲染/编排"，真正的全新内核（如 Obscura/aginxbrowser 的组织级整合、Cursor 的 FastRender 实验）是少数。
3. **CDP 正在从"Chromium 调试协议"变成"agent 浏览器互操作层"**：新引擎们对外一律暴露 CDP 兼容端点 + MCP，甚至发明自定义 CDP 域（Obscura 的 `LP.getMarkdown`：DOM→Markdown）——与 WebDriver BiDi 的标准化路线分叉加速。
4. **新分化轴是"有没有渲染/截图管线"**：Obscura 类无渲染管线（不为像素存在）；Kitesurf 有软光栅（仅截图输出）；aginxbrowser 有自研软渲染（截图 opt-in）。视觉输入型 agent 任务的选择会不同。
5. **反爬分野依旧，但出现"缝合"尝试**：轻引擎的架构性弱点是过不了真实 TLS 指纹/挑战（Cloudflare 自己都承认）；aginxbrowser 声称用 BoringSSL 复刻 Chrome/Firefox/Safari/Edge 的 TLS 握手来补这一课（未独立验证）。受保护站点的 2026 主流方案仍是 **stealth Chromium 构建**（新增玩家 CloakBrowser：30k stars，C++ 源码级补丁 + humanize 行为模拟）。
6. **2026 又冒出新形态的"AI 造浏览器"实验**：Cursor 用数百 AI 智能体 168 小时写出 300 万行 Rust 浏览器（代号 FastRender，"勉强能用"，可渲染谷歌首页）——"引擎由 AI agent 编写"成为第三条叙事（来源为媒体/AI 生成报道，未见一手仓库，谨慎采信）。

---

## 1. 重大发现：Obscura —— Kitesurf 的开源同类/疑似前身

**基本盘**（[GitHub 主仓 h4ckf0r0day/obscura](https://github.com/h4ckf0r0day/obscura)，另有镜像 [miaomiao1992/obscura](https://github.com/miaomiao1992/obscura)；官网 obscura.sh，文档 docs.obscura.sh）：
- 定位："The open-source headless browser for AI agents and web scraping"，Apache-2.0，无 feature gating。
- 技术：**Rust 引擎 + 内嵌 V8**（真跑页面 JS）+ **CDP 兼容**（Puppeteer/Playwright drop-in），CLI（fetch/scrape）+ CDP server + **自带 MCP server**（browser_navigate/snapshot/click/fill/type/press_key/evaluate/wait_for/network_requests/console_messages/close 等 12 工具，stdio 或 HTTP）。
- 时间线/热度：2026-04-13 建仓（ProxyCove 口径）→ 2026-07-10 已过 1 万 stars（Scrapeless 评测文）→ 2026-08-27 达 2.2 万+（ProxyCove 口径）。Obscura Cloud（托管版：托管基础设施+住宅代理）waitlist 中。
- 官方自测对比表（README，厂商自证）：内存 30MB vs headless Chrome 200+MB；二进制 70MB vs 300+MB；页面加载 85ms vs ~500ms；启动"即时"vs ~2s；anti-detect built-in（`--stealth`：指纹随机化、`event.isTrusted=true`、`navigator.webdriver=undefined`、3,520 域 tracker 拦截）。
- 规模性基准（ProxyCove 转述）：33 场景中位数约快 21×、内存约 1/7；React 页面 4 workers 下 40 页/秒@112MB vs Chrome 3 页/秒@4.2GB；WPT "core"（DOM/HTML/URL/fetch）83.3%（318,916/382,891）。
- **CDP 实现域清单**（README 自曝——与主报告"白名单壳层"结论完全呼应）：Target、Page、Runtime、DOM、Network、Fetch（live 拦截）、IO、Storage、Input，外加**自定义域 `LP.getMarkdown`（DOM→Markdown 转换）**——"给 agent 的 token 友好原语"正被做成 CDP 协议扩展。
- **"Kitesurf 前身"说法**：ProxyCove（2026-08-27 评测文）称 "Cloudflare openly acknowledged that the first prototype of Kitesurf was a port of Obscura to Workers"——**第三方单方说法，未获 Cloudflare 官方文证实**，待考；若属实，"12 周 from scratch"叙事应改为"12 周把开源引擎集成上 Workers"。

## 2. Kitesurf 引擎拆解修正（它到底"从零"到什么程度）

- 按 ProxyCove 拆解 + CSDN 早前拆解，Kitesurf = Rust→WASM 编排 + **Blitz（DioxusLabs 的模块化 HTML/CSS 渲染引擎，Rust）+ Stylo（Firefox/Servo 的 CSS 引擎）+ Boa（Rust JS 引擎）+ Parley（文本整形）**；四组件沙箱（Engine/PageScript/PageRenderer/SandboxOutbound）是 Cloudflare 自己的集成与编排。
- 推论：2026 年"底层重写"的现实路线 = **复用 Rust 引擎组件拼装新栈**（Blitz/Stylo/Boa/Parley/Taffy 是事实上的"Rust 浏览器组件生态"），而非逐行重写引擎。真正自己写内核的组织极少（Obscura、aginxbrowser 的 diting、Cursor FastRender 是特例/实验）。
- 组件谱系参考：Blitz（[DioxusLabs/blitz](https://github.com/dioxuslabs/blitz)，"a radically modular HTML/CSS rendering engine"）、Stylo（Servo/Firefox）、Boa（[boa-dev](https://github.com/boa-dev/boa)）、Taffy（Dioxus 布局）。aginxbrowser 亦自述其 diting 引擎以 "Blitz/Stylo/Taffy lineage" 为参考实现。

## 3. 新发现的"无 Chromium"自研 agent 浏览器

### 3.1 Obscura（见第 1 节）——自托管版 Kitesurf
与 Kitesurf 的关键差异：**你自己部署、自己决定网络出口与代理**（Kitesurf 跑在 Cloudflare 网络，出口 IP/ASN 不可控——ProxyCove 批评点）；无渲染管线（**不为像素存在**，ProxyCove 明言 "no layout and rendering pipeline; it does not render images at all"，README 中亦无 screenshot 能力）。

### 3.2 aginxbrowser（作者 yinnho；[GitHub](https://github.com/yinnho/aginxbrowser)，Apache-2.0）
- **单 Rust 二进制 + 内嵌 V8 + 自研 "diting" CSS/layout/paint 渲染引擎**（无 Chromium）；HTTP API（33 端点）+ **原生 MCP（29 工具）** + **CDP bridge**（Playwright/Puppeteer/browser-use 一行 attach）；提供托管实例 browser.aginx.net 与 Docker/Homebrew/自编译。
- 与 Kitesurf 的差异（其 README 明说对标点）：**持久登录会话**（session_create/cookies 注入导出、`persistent:true` 重启不掉线）、**真实 TLS 指纹**（BoringSSL 复刻 Chrome145/Firefox133/Safari/Edge 完整握手，非只改 UA）、Cloudflare Turnstile 自动等待/2captcha、CAPTCHA 检测上报、中文搜索引擎生态（百度/搜狗/微信）+ CJK 渲染字体内置、fetch receipt（tier/redirect trail/content_hash/captcha_event——"每次抓取都有回执"）、SQLite 本地缓存（FTS5）。
- 自测基准（2026-08-28，20 固定页面）：到"agent 可用文本"快 7.6×（p50 532ms vs 4,053ms）、整进程内存约 10× 少（227MB vs ~2.1GB/页）；5/40 次 Chrome `--dump-dom` 无 DOM 而它零硬失败。**厂商自测，未独立复测。**
- 自述局限：重指纹认证页（WorkOS/Cloudflare 探测 plugins/WebGL 类）仍可能失败；截图功能 opt-in 且 diting 渲染为近似非像素级；百度文库不支持、知乎需 cookie。
- 治理理念：robots.txt 默认不 gate（实时查询定位非爬虫，可开关）、无站内爬取 API、内置预算（每域 20 页/分、每会话 200 页）——"agent 浏览器即基础设施"的产品化思考值得参考。

### 3.3 cursed_browser（作者 scosman；[GitHub](https://github.com/scosman/cursed_browser)）
- 概念原型："True AI-Native Browser"——**不实现 CSS/布局，让 VLM 看 HTML 直接"幻觉"出像素**；点击交互通过改 HTML 重新生成。半讽刺半实验，用于讨论"LLM 即渲染器"的边界（路线图自嘲 V2 是"LLM 每次加载页面时从零写一个新引擎"）。作为思想实验收录，非可用产品。

## 4. 易混淆/相关的非自研玩家（一句话定位，防混淆）

| 项目 | 引擎 | 说明 | 状态 |
|---|---|---|---|
| BrowserOS（Decentralised-AI，AGPL-3.0） | **Chromium** | 开源 agentic 浏览器，自称"开源版 Perplexity Comet"：本地 agent、自带 API key/Ollama、兼容 Chrome 扩展 | macOS/Win/Linux beta；README 自认 "only possible because of Chromium"→ 非 from scratch；YC 标识为项目自称（未核实） |
| CloakBrowser（[CloakHQ/CloakBrowser](https://github.com/CloakHQ/CloakBrowser)，30k stars） | **补丁版 Chromium（C++ 源码级）** | 2026-02 起爆火的 stealth 构建。**非完全开源**：wrapper（Python/JS/.NET）MIT 开源，但 Chromium 二进制**闭源**（BINARY-LICENSE：v146 及更早免费可用但禁止再分发；v148+ 需 Pro 订阅）。最新 v0.5.10（2026-09）：Chromium 151、**73 个 C++ 补丁**；免费档需 GitHub 登录密钥限 1 并发会话，旧 v146 免密钥。humanize（贝塞尔鼠标/逐字打字/滚动物理）、geoip 时区联动、cloakserve CDP server（支持 per-seed 指纹路由）；厂商宣称 reCAPTCHA v3 0.9（Pro，Tier B 未独立验证）。GUI 管理器（CloakBrowser Manager）MIT 开源 | 活跃；pim97 独立安全审计（2026-03）仅覆盖 Chromium145，9/9 通过但结论不迁移到新版 |
| FastRender（Cursor 实验，2026-01 报道） | **自研 Rust 渲染引擎 + 定制 JS VM** | 数百 AI agent（GPT-5.2-Codex）协作 168h 写 300 万行，"勉强能用"、可基本渲染谷歌首页；来源为 AI 生成新闻稿/媒体转述（DoNews 等），**未见一手仓库** | 传闻级信息，谨慎采信 |
| NetSurf（C，历史项目） | 自研（非 Rust 生态） | 从 RISC OS 走来的纯自研轻量浏览器，仍维护 | 与 agent 无关，仅作"自研引擎存在性"参照 |

## 5. 通用 from-scratch 引擎项目盘点（2026-09 状态更新）

| 项目 | 语言/组织 | 类型 | 自动化/协议 | 2026-09 状态 | 与 agent 关系 |
|---|---|---|---|---|---|
| **Servo** | Rust / Linux Foundation Europe | 通用/嵌入引擎 | 内建 W3C WebDriver；Firefox DevTools 协议 | v0.5.0（2026-08-31）、crates.io 0.1.0（2026-04） | 已有 servo-agent（WebDriver+MCP，DOM→markdown 蒸馏）先锋案例 |
| **Ladybird** | C++→并行 Rust 移植 / 非营利 | 通用浏览器 | 无（DevTools 暂借 Firefox 协议） | 目标 Alpha 2026 未发布；AI 辅助移植 LibJS 零回归 | 短期不适合 agent 底座；"AI 造引擎 + 防 AI-PR"双面样本 |
| **Gosub** | Rust / 社区志愿者（无商业背书，MIT） | 通用浏览器引擎 | 未见 CDP/WebDriver/BiDi | "very early stages"：HTML parser working（html5lib 近全过）；网络栈 sonar 可用（缺 HSTS/CORS/HTTP 缓存）；CSS3 PoC（或借 Stylo）；渲染管线 maturing（backend 无关：cairo/vello/skia，gtk/egui/winit 演示）；JS 早期（V8 可插拔）；**引擎基础层已支持多 tab/多 zone 容器化会话**（cookie/会话隔离，多账号同开是设计目标） | 无 agent 布局；其容器会话隔离设计值得注意（官网 [gosub.io/status](https://gosub.io/status/)、[FAQ](https://gosub.io/faq/)） |
| **Blitz** | Rust / DioxusLabs | HTML/CSS 渲染引擎（组件） | — | 模块化引擎组件 | 被 Kitesurf 采用（ProxyCove 口径）；"浏览器=组件拼装"路线的核心件 |
| **Boa / Stylo / Parley / Taffy** | Rust 生态 | JS 引擎 / CSS / 文本 / 布局组件 | — | 活跃 | Kitesurf/aginxbrowser 等新引擎的公共底座 |
| （参照）Kitesurf | Rust→WASM / Cloudflare | agent 运行时（含软光栅） | CDP+REST+MCP | Beta 免费，2026-08-06 | 见主报告 2.2 |
| （参照）Obscura / aginxbrowser | Rust+V8（+diting） | agent 运行时 | CDP（+自定义域）+MCP | 2026 新星 | 本补充 1/3.2 |
| （参照）Nusphere（Devine Lu Linvega） | 纯 C（自称） | 个人从零浏览器/引擎 | — | **未能核实**：仅见 Mastodon 只言片语（"written in pure C, runs on…"，2025-11 前后），无官网/仓库落地证据；注意与老牌 PHP IDE 公司 NuSphere（nusphere.com）同名，勿混淆 | 附录 B |

## 6. Kitesurf 同类横向对比表（agent 运行时族）

| 维度 | Kitesurf（Cloudflare） | Obscura（开源） | aginxbrowser（开源） | BrowserOS（对照，非自研） |
|---|---|---|---|---|
| 引擎 | Rust→WASM；Blitz+Stylo+Boa+Parley 拼装 | Rust+V8（自研内核整合） | Rust+V8+**自研 diting 渲染引擎** | Chromium |
| 渲染/截图 | PageRenderer 软光栅出 JPEG/PNG/PDF | **无渲染管线**（不为像素存在） | 自研软渲染截图（opt-in） | 完整 Chromium |
| 会话 | Durable Objects（短任务型；官方承认不支持长登录会话） | 无持久会话宣传（状态短） | **持久登录会话**（cookie 注入导出、重启不掉线） | 本地持久 |
| 对外接口 | CDP 端点 + REST Quick Actions + MCP（chrome-devtools-mcp） | CDP（含自定义 `LP.getMarkdown`）+ MCP + CLI | HTTP + 原生 MCP(29) + CDP bridge | 人用 UI |
| 反爬能力 | 官方承认：**过不了真实 TLS 指纹 bot 挑战** | built-in stealth（指纹随机化等，深度存疑） | 声称 BoringSSL 复刻四大浏览器 TLS 握手+Turnstile 等待（未独立验证） | — |
| 网络出口 | Cloudflare 网络（**不可自控出口 IP**） | 自托管（自控） | 自托管/托管（自控） | 自控 |
| 部署/许可 | 仅托管；Beta 免费；开源仅路线图 | Apache-2.0；二进制+Docker；Obscura Cloud waitlist | Apache-2.0；二进制/Homebrew/Docker/托管 | AGPL-3.0 |
| 时间线 | 2026-08-06 发布 | 2026-04-13 建仓；8 月底 2.2 万+ stars | 2026-08-28 基准自测（README 现行） | 2026 beta |
| 厂商自测亮点 | CPU/内存省 3.1–7×、墙钟慢 1.7–1.8×；WPT 23.5 万+ | ~30MB/实例、快约 21×、40 页/s@112MB；WPT core 83.3% | 快 7.6× 到可用文本、内存 ~10× 少 | — |

## 7. 增量判断（接主报告 2.7 与第四部分）

1. **"Kitesurf 同类"已经是一个设计流派**：agent-first 原语（DOM→Markdown、fetch 回执、预算与 robots 治理、token 友好输出）+ CDP 兼容端点 + MCP 原生 + 无人类 UI。选型时真正要回答的是四个问题：渲染管线要不要（视觉任务）？出口 IP 归谁（反爬/地域）？会话要不要持久（登录态任务）？跑在谁的网络上（数据合规）？——主报告 smfclearinghouse 的"轻引擎 + 重 Chromium 混合路由"是 2026 主流架构模式。
2. **从零叙事分级**：①组件拼装（Kitesurf，12 周可行）；②组织级新内核（Obscura/aginxbrowser）；③AI agent 编写引擎（Cursor FastRender，实验/传闻级）；④逐行手写经典路线（Servo/Ladybird/Gosub，年为单位）。投入与完整度成反比，"无 Chromium"≠"web 兼容"。
3. **CDP 生态自我演化值得关注**：Obscura 发明自定义 CDP 域做 DOM→Markdown，说明 agent 时代 CDP 已从调试协议变成互操作层；其能力面（白名单域）与主报告指纹浏览器"壳层 CDP"异曲同工——**"CDP 支持"的下一步是"CDP+agent 原语域"**。
4. **stealth Chromium 自托管成为第四派**：CloakBrowser（30k stars）/ Camoufox / Clearcote / Patchright 提供"补丁版 Chromium + 原生 Playwright/Puppeteer API + 自管 profile 与出口"，比商业指纹浏览器便宜、API 原生（CloakBrowser 二进制闭源是主要信任扣分项，开源替代选 Camoufox/Clearcote/Patchright）。对本项目：可替代/补充第一部分"指纹浏览器后端"方案，值得实测对比。
5. **对本仓库（Browser4/Pulsar）的增量启示**：
   - Obscura/aginxbrowser 类可作"轻量 fetch/无头层"后端候选：CDP 兼容 + MCP，与现有 attach 路径同构；但要先做 CDP 能力面探测（如 Obscura 无 Emulation/Overlay 等域、ginx 的 CDP bridge 覆盖面未明）。
   - "DOM→Markdown/token 蒸馏"已成为 agent 浏览器协议级原语（LP.getMarkdown）——本仓库快照体系（a11y/htmlsnapshot summary）可评估对齐为该原语的实现方。
   - 会话预算与治理（每域 20 页/分、每会话 200 页、robots 可选）应纳入多会话调度设计考量。
   - 反爬现实不变：轻引擎过不了 TLS 指纹挑战（架构性）；要"隐身"目前只有两条实证路线——补丁 Chromium（CloakBrowser 族）或商业指纹浏览器 + 真 CDP 客户端管理，且都需叠加出口代理质量。
   - 合规提示：stealth/指纹工具处于反爬灰色地带，接入前评估目标站 ToS 与当地法规（同主报告）。

---

## 附录 A：主要来源

- Obscura：GitHub <https://github.com/h4ckf0r0day/obscura>（README 一手：指标/CDP 域/MCP/stealth）、镜像 <https://github.com/miaomiao1992/obscura>、[Scrapeless 评测（2026-07-10）](https://www.scrapeless.com/en/blog/obscura-headless-browser)、[ProxyCove 三方对比（2026-08-27）](https://proxycove.com/en/blog/kitesurf-obscura-chromium-agentnye-brauzery-2026)（含 WPT/40页每秒数字与"Kitesurf 原型=Obscura 移植"说法）
- Kitesurf 组件拆解：ProxyCove（同上）、主报告引用的 [Kitesurf 官方文档](https://developers.cloudflare.com/browser-run/kitesurf/)、[TechCrunch](https://techcrunch.com/2026/08/07/cloudflare-launches-kitesurf-a-browser-built-for-ai-agents/)
- aginxbrowser：<https://github.com/yinnho/aginxbrowser>（README 一手，2026-08-28 基准）
- Blitz：<https://github.com/dioxuslabs/blitz>
- BrowserOS：<https://github.com/Decentralised-AI/BrowserOS>
- Gosub：<https://gosub.io/status/>、<https://gosub.io/faq/>、<https://github.com/gosub-io/gosub-engine>
- CloakBrowser：<https://github.com/CloakHQ/CloakBrowser>、[pim97 深度技术分析（2026-08-14 核验）](https://github.com/pim97/anti-detect-browser-tools-tech-comparison/blob/master/cloakbrowser.md)
- FastRender/Cursor：DoNews 转述 <https://www.donews.com/news/detail/4/6381077.html>、BAAI 智源 <https://hub.baai.ac.cn/view/51948>
- cursed_browser：<https://github.com/scosman/cursed_browser>
- 通用参照：主报告（Ladybird/Servo/engines 章节）与 [Wikipedia: Comparison of browser engines](https://en.wikipedia.org/wiki/Comparison_of_browser_engines)

## 附录 B：未证实/存疑清单（诚实声明）

- "Cloudflare 承认 Kitesurf 首个原型是 Obscura 移植到 Workers"：仅 ProxyCove 单方说法，未见 CF 官方文件证实。
- Cursor FastRender：全部信息来自 AI 生成新闻稿的中文转述（DoNews 文末自注"由开放的智能模型自动生成"）；未找到一手博客/仓库；"300 万行/168 小时/可渲染谷歌首页"均按传闻处理。
- Devine Lu Linvega 的"Nusphere"：仅两则 Mastodon 片段（[1](https://merveilles.town/@neauoire/105840485100372203)、[2](https://merveilles.town/@neauoire/114169139876531459)）与检索关联，无官网/仓库证据；且与 PHP IDE 公司 NuSphere 同名易混——本报告未将其列入正表。
- Obscura/aginxbrowser 全部性能与反爬数字为厂商自测（README/博客），无第三方复测；Obscura star 数（10k→22k）为两家第三方先后口径，未逐一核对 GitHub API。
- CloakBrowser 检测成绩（reCAPTCHA v3 0.9 等）为厂商宣称且限 Pro 二进制（Tier B）；独立审计仅覆盖旧版 Chromium145；其 30k stars 为 pim97 分析文档口径。
- BrowserOS 的 YC 背景、star/用户量未核实；其"本地隐私"叙事未做安全审计。
- Blitz 被 Kitesurf 采用为 ProxyCove 口径（Cloudflare 官方组件清单未逐字核对）；aginxbrowser 的 diting 与 Blitz/Stylo/Taffy 的"谱系"关系为其 README 自述。
