# Headless 隐身改进计划（4.13.x / pulsar-browser 4.11.16）

> 状态：**P0 已落地并端到端验证**（2026-09-21）· 范围：headless 反检测 · 关联证据：
> `coworker/tasks/issues/draft/20260916-174704-Bot stealth check.*`、`20260920-104452-Bot stealth check.*`、
> `docs-dev/research/2026-09-10-cdp-antidetect-and-ai-native-browsers.md`
> 上游缺陷：<https://github.com/platonai/Browser4base/issues/11>

## 实施进展（2026-09-21）

| 项 | 状态 | 落地位置 / 证据 |
|---|---|---|
| R1 UA 去 `HeadlessChrome` | ✅ 已修 | `HeadlessUserAgent.kt`（browser4-browser）+ `AbstractPulsarSession.createBoundDriver` 注入 `--user-agent`；实测五个 scope 全部 `Chrome/153.0.0.0` |
| R2 主世界 vs Worker 矛盾 | ✅ 已修 | 新 `js/stealth.js`（extract-stealth-evasions + 2.11.2，剔除 `hardwareConcurrency`/`languages`/`webgl.vendor`）；实测各 scope 均报主机真值 20/zh-CN |
| R3 `language ∉ languages` | ✅ 已修 | 同上（不再伪造 languages）；实测 `zh-CN ∈ [zh-CN, zh]` |
| R4 `Error.prepareStackTrace` 自曝 | ✅ 已修 | 重生成后该块消失；实测自有属性不存在 |
| R7/R8 屏幕 vs 视口 | ✅ 已修 | `Browser4WebDriver.applyHeadlessScreenMetrics`：导航后用 `screenWidth/screenHeight` 重下 metrics（视口几何不变）；GUI/attach 会话跳过 |
| R7 `--hide-scrollbars`、窗口 chrome 缺失 | ⏳ 上游 | `innerWidth - clientWidth == 0` 仍是可测信号；需放开 flag，见 issue #11 §6 |
| R9 `Runtime.enable`、R10 死缝、注入重复注册 | ⏳ 上游 | issue #11 §4/§5/§8 |
| R11 文档 | ✅ 已更新 | `skills/browser4-cli/references/browser-modes.md`、`docs/config.md` |
| 门禁 | ✅ 已落地 | fixture：`static/b4/stealth-probe-fixture.html` + `browser4-stealth-sw.js`；e2e 场景 `test_e2e_stealth_consistency` 通过（25.7s）；单测 `HeadlessUserAgentTest` |

**实测结论（Chrome 153.0.8010.52，真实 CLI 会话，`htmlsnapshot get text "#state-log"`）**

```
ok   reachableScopes = ["iframe","worker","shared","sw"]
ok   user-agent-has-no-headless-token = true
ok   user-agent-consistent-across-scopes = true      # main / iframe / worker / shared / sw
ok   cores-consistent-across-scopes = true           # 全部 20（旧版本主世界为 4）
ok   languages-consistent-across-scopes = true       # 全部 zh-CN / zh
ok   language-in-languages = true
ok   user-agent-brand-version-consistent = "pass"
ok   webdriver-false = true
ok   prepare-stack-trace-not-defined = true
ok   screen-not-smaller-than-window = true           # screen 1920x1200 ≥ inner 1920x1080
ok   outer-not-smaller-than-inner = true
ok   window-chrome-present = true                    # outer 1165 > inner 1080
FAIL scrollbar-has-width = false                     # --hide-scrollbars → issue #11 §6（唯一残留）
```

已知时序细节：会话**首次**导航时，`applyHeadlessScreenMetrics` 可能晚于该页的 load 事件（`open` 路径比 `goto` 多一层等待），第二次导航起稳定生效；这与既有报告里"首次加载补丁未生效"是同一类注入时序问题（issue #11 §4）。


**机制实验结论**（决定了实现方式，`Emulation` 域在 Chrome 153 上的传播范围）

| 机制 | main | iframe | dedicated | shared | service worker | `Sec-CH-UA*` |
|---|---|---|---|---|---|---|
| `--user-agent` 启动参数 | ✅ | ✅ | ✅ | ✅ | ✅ | 保留 |
| `Emulation.setUserAgentOverride({userAgent})` | ✅ | ✅ | ✅ | ❌ | ❌ | **清空** |
| 同上 + `userAgentMetadata` | ✅ | ✅ | ✅ | ❌ | ❌ | 保留 |
| `Emulation.setHardwareConcurrencyOverride` | ✅ | — | ❌ 仍 20 | ❌ | ❌ | — |
| `Emulation.setTimezoneOverride` | ✅ | — | ✅ | ✅ | — | — |
| `Emulation.setLocaleOverride` | ❌ 不改 `navigator.language` | — | ❌ | ❌ | — | — |

→ 只有**启动参数**能覆盖全部 JS scope；主世界 JS 补丁与 `Emulation` 都到不了 shared/service worker，因此"伪造一个 worker 能看到的值"必然自相矛盾。

## 0. 结论先行

当前 headless 隐身差的**不是"伪装得不够像人"，而是三件事叠加**：

1. **最大的那个特征根本没盖**：Chrome 153 在 `--headless` 下 UA 里带 `HeadlessChrome/153.0.0.0`，
   而 UA-CH 的 `brands` 说 "Google Chrome 153"（本机实测，见附录 A）。Browser4 全链路没有任何 UA 覆盖
   （全库 `setUserAgentOverride` = 0 命中；启动参数无 `--user-agent`）。这是确定性、零成本可检测的一票否决信号。
2. **伪装自相矛盾**：注入的 `js/stealth.js` 是 **puppeteer-extra-plugin-stealth v2.9.0（2021 年）默认配置**的
   静态打包，硬编码 `hardwareConcurrency: 4`、`languages: [] → ['en-US','en']`，且**只在主世界生效**。
   于是主世界报 4 核 / en-US，Worker 与 ServiceWorker 报真实的 20 核 / zh-CN ——
   deviceandbrowserinfo 的**唯一** true 信号就是它（`hasInconsistentWorkerValues`），直接 `isBot: true`；
   同一上下文里 `navigator.language = zh-CN` 又不在 `navigator.languages = ['en-US','en']` 中。
   **主世界 JS 补丁在结构上永远覆盖不到 Worker/ServiceWorker 全局**，所以"伪造一个假值"必然制造跨上下文矛盾。
3. **没有门禁**：唯一相关的 `BotDetectionE2ETest` 是 `@Disabled` 且全部用 `Assumptions.assumeTrue`
   （不可能失败）；`FingerprintApplicationIT` 只打印不比对；真实判据只存在于一次人工 coworker 任务里。
   于是每次回归都表现为"感觉极差"，而不是"第 3 项断言红了"。

> 归因提醒：Google `/sorry/` reCAPTCHA 是**出口 IP（VPN/机房段）**问题，不是指纹问题
> （报告里已给出 egress IP 证据）。改指纹之前先固定出口 IP，否则测量无意义。

---

## 1. 根因清单（按 收益/成本 排序）

| # | 问题 | 证据（file:line 为 pulsar-browser 4.11.16 提取源） | 页面可观测性 |
|---|---|---|---|
| R1 | UA 含 `HeadlessChrome/<ver>`，与 UA-CH brands 自相矛盾 | 本机实测（附录 A）；无 `--user-agent`（`Options.kt:65-123`）；无 `Emulation/Network.setUserAgentOverride`（全库 0 命中） | 任何读 UA 的检测点 |
| R2 | 主世界伪造值 vs Worker 真实值（4/20 核、en-US/zh-CN） | `js/stealth.js` L41-56（`{"hardwareConcurrency":4}` / `{"languages":[]}`）；注入仅 page world：`PulsarWebDriver.kt:2250-2258`、`DirectChromeProtocol.kt:264-271`；无 `Target.setAutoAttach` | 跨上下文互校（deviceandbrowserinfo / incolumitas） |
| R3 | `navigator.language(zh-CN)` ∉ `navigator.languages(['en-US','en'])` | 同上 + 启动无 `--lang`（`Options.kt:65-123` 无该参数） | 单上下文自校验 |
| R4 | 自研块把 `Error.prepareStackTrace` 定义成 **non-writable + non-configurable** | `js/stealth.js` L121-130（上游 v2.5.1–v2.11.2 都没有，是 Browser4 自己加的）；本机实测 stock Chrome 153 **没有**这个自有属性 → 一行代码即可判定，且不可恢复 | 一行 JS |
| R5 | 几何不自洽：`innerWidth/Height` 钉死 1920×1080，`screen.*` 从不覆盖 | `PulsarWebDriver.kt:1977-1983`、`:1044-1051`（两处调用都省略 `screenWidth/screenHeight`；`DirectChromeProtocol.kt:442-461` 支持却没用）；`DEFAULT_VIEWPORT = 1920x1080`（pulsar-common `AppConstants.java:136`，无配置键） | inner > screen（1680 屏上必现） |
| R6 | `window.outerdimensions` 补丁制造 `outerWidth == innerWidth`、`outerHeight = innerHeight + 85` | `js/stealth.js` L91-100（`windowFrame = 85`）；本机实测 stock headless `OUTER=0x0` | 一行 JS |
| R7 | `--hide-scrollbars` 强制 → `innerWidth - clientWidth == 0` | `ChromeDefaults.kt:24` + `Options.kt:73-74`；且因 `toList()` 优先级规则（`Options.kt:209-240`）**配置无法关掉** | 滚动条宽度检测 |
| R8 | WebGL 报伪造的 `Intel Inc. / Intel Iris OpenGL Engine`，真实适配器是软件渲染 | `js/stealth.js` L83-90（默认值）；本机实测真实 renderer = `ANGLE (Microsoft, Microsoft Basic Render Driver …)`；`DISABLE_GPU=true`（`ChromeDefaults.kt:26`） | WebGL/GPU 一致性 |
| R9 | `Runtime.enable` **每次导航**都调用（经典 CDP 泄漏）；页面 console 还被本仓库打补丁 | `PulsarWebDriver.kt:1942-1948`（`navigate()` :276-278 每导航触发）；`Browser4WebDriver.kt:359-392` 写 `window.__b4_console` / `__b4_console_intercepted` | 反 bot 的 Runtime.enable 探针 |
| R10 | 身份档案（UA/screen/hardware/WebGL/canvas/geoTime/locale/tz）**完全没有接上线** | `fingerprintApplier`（`PulsarWebDriver.kt:183`，调用点 `:201` 在 `init` 里 → **构造期恒为 null，死缝**）；主仓库 grep = 0 命中；`userAgentOverride`（`AbstractBrowser.kt:41`）零读取；`DEFAULT_USER_AGENT="Browser4 Agent/1.0"` 不上线 | —（能力缺失） |
| R11 | 无 `--lang` / locale / timezone / Accept-Language / CH 对齐；`Function.prototype.toString` 被全局 Proxy；无 `sourceURL` 去特征化；isolated world 名固定 | `Options.kt`（无 lang）；`ChromeDefaults.kt:99`；`js/stealth.js` L1-L130；`IsolatedWorldManager.kt:34`（`__browser4_runtime__`） | 中低 |

**已具备但未使用的 CDP 能力**（`cdp-protocol/browser_protocol.json`，说明"引擎层覆盖"是可做的）：

| CDP 方法 | 行 | 作用 |
|---|---|---|
| `Emulation.setHardwareConcurrencyOverride` | 11537 | 渲染层覆盖核数（有机会传播到 worker） |
| `Emulation.setUserAgentOverride`（含 `acceptLanguage`/`platform`/`userAgentMetadata`） | 11548 | UA + CH 头 + `navigator.userAgentData` 一次性自洽 |
| `Emulation.setLocaleOverride` | 11468 | locale |
| `Emulation.setTimezoneOverride` | 11481 | 时区 |
| `Emulation.setDeviceMetricsOverride`（`screenWidth/Height`、`positionX/Y`） | 10967 | 视口 + 屏幕一起对齐 |
| `Emulation.setAutomationOverride` | 11578 | 自动化标志 |

---

## 2. 目标与验收标准

**硬门（必须自动化，进门禁）** —— 全部在本地 fixture 上断言，不依赖外网：

1. UA 不含 `HeadlessChrome`，且 `navigator.userAgentData.brands` 与 UA 版本自洽；
2. `main / iframe / Worker / SharedWorker / ServiceWorker` 五处的
   `hardwareConcurrency`、`languages`、`language`、`platform`、`userAgent` 全部一致；
3. `navigator.language ∈ navigator.languages`；
4. `screen.width ≥ innerWidth`、`outerWidth > innerWidth`、`innerWidth - clientWidth > 0`、DPR 与档案一致；
5. `Object.getOwnPropertyDescriptor(Error,'prepareStackTrace') === undefined`（或不含非配置属性）；
6. `navigator.webdriver === false`；
7. locale/timezone 与档案一致，且与 `Accept-Language` 不冲突。

**软门（半自动，人工触发/夜间跑）**：5 家公开检测点里 4 家未检出；
`deviceandbrowserinfo.isBot === false`；incolumitas 无 worker 类 FAIL。

**明确非目标**：Google reCAPTCHA（IP 层）、TLS 指纹、内核级 canvas/WebGL 噪声
（需要补丁版 Chromium 或商业指纹浏览器，走 `attach --cdp` 路线，另立议题）。

---

## 3. 分阶段实施

### P0 —— 半天～1 天：止血（盖住 HeadlessChrome + 消灭最响的矛盾）

**P0-1 UA 去特征化（最高收益 / 最低成本）**

- 今日即可用（零代码）：`browser.launch.chrome.args` 注入
  `--user-agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<真实版本>.0.0.0 Safari/537.36"`。
  可行性依据：`Options.kt` 里**没有** `user-agent` 这个 `@ChromeParameter`，因此 raw 参数不会被程序值覆盖
  （`Options.kt:225-240`）；本机实测该覆盖对主世界**和 Worker 同时生效**，且与 CH brands 不冲突（附录 A）。
- 正解（上游一行 + 一处 CDP）：启动时从 `chrome.version.userAgent` 派生 UA
  （把 `HeadlessChrome/x.y.z.0` 替换为 `Chrome/x.y.z.0`，Playwright 同款做法），
  并调用 `Emulation.setUserAgentOverride(ua, acceptLanguage, platform, userAgentMetadata)`
  让 UA / CH 头 / `navigator.userAgentData` 三方一致。
- 文档同步：`skills/browser4-cli/references/browser-modes.md:158-160` 现在写的是"不做 UA 轮换"，
  需改成"剔除 HeadlessChrome，但不做随机轮换（轮换本身可检测）"。

**P0-2 让各 JS 上下文对同一份身份达成一致**

两条路线，**先做实验再选**（实验见 P3-0，结论决定走 A 还是 B）：

- **路线 A（保守，推荐先落地）**：不再伪造 `hardwareConcurrency` / `languages` 两个值，
  让主世界与 Worker 都报主机真值（20 核 / zh-CN，自洽）；locale 用 `--lang` 统一。
  落地手段三选一：
  1. 上游改 `js/stealth.js`（删两个 evasion 或从配置取值）——最干净；
  2. 本仓库 `browser4-browser/src/main/resources/js/stealth.js` 覆盖 jar 内同名资源
     （`ResourceLoader.kt:25,156` 走 `contextClassLoader`，取**第一个匹配**）——
     **需先做 10 分钟 shadow 验证**（放 marker 后 `./b4w.ps1 eval "window.__marker"`）；
  3. 外部注入缝 `~/.browser4/browser/js/preload/page-world/*.js`
     （`DualWorldScriptLoader.loadExternalResource`，在 stealth.js **之后**拼接、不混淆）——
     只能"增量打补丁"，无法还原被 Proxy 掉的 getter，因此单独用它解决不了 R2。
- **路线 B（保留伪造身份，供多账号隔离）**：保持 stealth.js 不动，用引擎层覆盖把各 scope 拉到同一份档案：
  `Emulation.setHardwareConcurrencyOverride(N)` + `setUserAgentOverride(acceptLanguage='en-US,en')`
  → 主世界 JS 报 4/en-US，worker 报引擎值 4/en-US，一致。
  **前提是实测确认覆盖会传播到 dedicated/shared/service worker**（P3-0）。

**P0-3 删掉自曝块**：移除自研的 `Error.prepareStackTrace` 块（`js/stealth.js` L121-130）。
`window.cdc_*` 清理块（L111-119）针对的是 ChromeDriver 而非 CDP，留着无害，可一并删。

**P0 落地路径（本仓库内、不等上游）**：
`Browser4WebDriver`（本仓库唯一的 production 源文件）在每个生产会话里都会被换入
（`AbstractPulsarSession.kt:278,315-318`、`PulsarSessionManager.kt:907-912`、`AgentToolManager.kt:429-430`），
因此可以在其中：
- 在 `Browser4WebDriver` 的 `init {}`（或 `override suspend fun navigate`，`PulsarWebDriver.navigate` 是 override → open）
  里用 `executeCdpCommand("Emulation.*", …)` 一次性下发 UA/locale/tz/核数/屏幕覆盖（构造后、首次导航前生效）；
- 注意 `fingerprintApplier` 是**死缝**（`init` 里调用，构造期恒 null），不要复用它，需新增显式 hook 或直接调用。

### P1 —— 2~4 天：把"身份档案"接上电

1. **Fingerprint → 引擎层映射**：UA+CH、locale、timezone、screen/DPR、hardwareConcurrency；
   每项都要有"能力探测 + 显式降级"（老内核缺 `setHardwareConcurrencyOverride` 时，
   退回"不伪造"而不是"只伪造主世界"）。
2. **启动前自检（loud fail）**：扩展现有 `FingerprintValidator`（pulsar-common，已有
   mobile↔maxTouchPoints、desktop↔touch 规则）为跨上下文一致性校验：
   `screen ≥ window`、`language ∈ languages`、`UA ↔ CH ↔ platform` 同源、WebGL/OS 匹配；不合格直接拒绝启动会话。
3. **默认档案与主机同源**：`ScreenParameters` 默认 1920×1080 dpr 1.0，与主机（如 1680 宽）冲突时，
   要么按主机派生，要么把 `screen.*`/DPR/`availWidth` 一起覆盖成自洽的 1920×1080。
4. **清理死代码/接线**：`fingerprintApplier`、`userAgentOverride`、`enableUserAgentOverriding`
   （`BrowserSettings.kt:565`）——要么接线要么删，避免下一个接手的人以为它在生效。
5. **给 `--disable-gpu` 一个显式开关**（当前 `DISABLE_GPU=true` 且优先级规则让配置无法覆盖），
   有 GPU 的机器不禁用，软件渲染机器如实记录（R8 在纯软件环境不可修，但必须**可知**）。
6. **`--hide-scrollbars` 同理**：上游放开关，默认也不应隐藏（真实 Chrome 有滚动条）。

### P2 —— 2~3 天：CDP 噪声与 headless 特征

1. `Runtime.enable`（`PulsarWebDriver.kt:1942-1948`）：驱动其实不需要它拿 executionContextId
   （用的是 `Page.createIsolatedWorld` 返回值 + 自缓存，`IsolatedWorldManager.kt:78-95`、`JsHandler.kt:37-39`），
   把它改成按需/可关；确需时参照 rebrowser-patches 的 addBinding 方案。
2. 页面 console 补丁（`Browser4WebDriver.kt:359-392`）：`window.__b4_console*` 改为不可枚举随机名，
   或改走 CDP 侧收集，别在页面上留可枚举全局。
3. `--hide-scrollbars` / `--mute-audio` / `--no-startup-window` 等"人不会这么用"的标志带来的
   页面可观测差异逐项评估（R7）。
4. 去特征化清单：注入脚本 `sourceURL`、isolated world 名（`__browser4_runtime__`）、
   `Function.prototype.toString` Proxy（上游设计中不可轻易移除，但要知道它在暴露面里）。
5. 行为层（AGENTS.md 的 gate 延伸）：点击坐标加 jitter、补 `mousemove` 轨迹
   （现状 `emulator.click(..., position = "center")` 固定中心）。

### P3 —— 1~2 天：把度量变成门禁

**P3-0（前置实验，半天）**：用新 fixture 实测
`Emulation.setHardwareConcurrencyOverride` 与 `setUserAgentOverride(acceptLanguage)`
**是否传播到 dedicated / shared / service worker**。结论决定 P0-2 走 A 还是 B。

1. **fixture**：`browser4-tests/pulsar-tests-common/src/main/resources/static/b4/stealth-probe-fixture.html`
   —— 采样 main/iframe/Worker/SharedWorker/ServiceWorker 的 navigator + screen + UA-CH + WebGL +
   `prepareStackTrace` + `document.hasFocus`，JSON 写入 `#state-log`（沿用现有 fixture 约定）。
2. **Rust e2e 场景**（`cli/browser4-cli/tests/e2e/`）：
   `constants.rs`（PATH/FILE/TITLE）→ `mod.rs`（`FixturePages` 字段、`start()` 预加载、`serve_fixture_request` 路由、`E2ECtx::stealth_url()`）
   → `scenarios/browser.rs` 的 `test_e2e_stealth_*` → `scenarios/mod.rs` 注册
   （`requires_browser4: true`、`group: Some("stealth")`）。
3. **Kotlin 侧**：把 `FingerprintApplicationIT` 从"打印指纹期望值"改成"断言浏览器值 == 档案值"；
   顺手处理 `BotDetectionE2ETest` 的假断言问题（它带 `BotDetection` 而非 `E2E` 标签，
   一旦去掉 `@Disabled` 会在 ci.yml 被调度）。
4. **生产脚本**：`browser4-tests/tests-production/verify-stealth.ps1`（5 家服务 → JSON 报告 → 与基线 diff；
   遵循"不得依赖 git/Maven/源码"的约定；`ManualOnly`）。
5. **诊断可见性**：`doctor`/`status` 输出"本次生效的 stealth 配置 + egress IP/ASN"。
   IP 是 Google `/sorry/` 的真因，不可见就永远会被误判成指纹问题。

---

## 4. 验收矩阵

| 检查项 | 现状 | 目标 | 验证方式 |
|---|---|---|---|
| UA | `HeadlessChrome/153.0.0.0` | 无 `HeadlessChrome`，与 CH 一致 | fixture 断言 + curl HTTP 头 |
| 跨上下文一致性 | 4/20 核、en-US/zh-CN | 五处全一致 | fixture 断言（deviceandbrowserinfo 复现） |
| `language ∈ languages` | 否 | 是 | fixture 断言 |
| 几何 | inner 1920 > screen 1680；outer==inner | screen ≥ inner、outer > inner、有滚动条 | fixture 断言 |
| `Error.prepareStackTrace` | 自有非配置属性 | 不存在 | fixture 断言 |
| `navigator.webdriver` | false（已 OK） | false | fixture 断言 |
| `Runtime.enable` | 每导航调用 | 按需/可关 | 代码审查 + Runtime.enable 探针页 |
| 真实站点 | isBot:true（2/5 检出） | 4/5 未检出 | `verify-stealth.ps1` + 基线 diff |
| 回归门 | 无（@Disabled + assumeTrue） | CI 有断言 | ci.yml 跑 `RequiresBrowser` 组 |

---

## 5. 风险与取舍

- **上游依赖**：核心改动在外部库 `pulsar-browser`（`browser4-base.version=4.11.16`）。
  P0 已给出"本仓库内"的过渡路径（配置注入 `--user-agent`/`--lang` + `Browser4WebDriver.executeCdpCommand` +
  可能存在的资源覆盖），但 **P1 的启动参数类改动（`--disable-gpu`/`--hide-scrollbars`）只能上游做**。
- **一致但真实 vs 伪造且自洽**：前者立刻可做、零风险，代价是没有身份隔离；
  后者才有多账号价值，但必须逐层验证（主世界 / Worker / SW / HTTP 头 / CH）。
- **真要内核级指纹隔离**：正确姿势是接商业指纹浏览器或补丁 Chromium（Camoufox/Patchright）的 CDP 端点，
  仓库已有 `attach --cdp` 与调研报告（`docs-dev/research/2026-09-10-*`），不该手搓 JS 补丁。
- **性能**：P2 动 `Runtime.enable` 会影响 evaluate 路径，需跑基准（<5% 门槛）。
- **合规**：EU AI Act §50 的 bot 自曝要求、目标站 ToS、中国拟人化办法——需要产品侧确认口径。

---

## 6. 需要拍板的两点

1. **哲学选择**：A"停止伪造值，只做一致的真实"（推荐，P0 即可验收）
   还是 B"引擎层全面伪造"（P1，需逐层实测传播性）？
2. **上游怎么走**：直接在 `pulsar-browser` 改并发版（4.11.17），
   还是先在 browser4 里用"配置注入 + `Browser4WebDriver` 扩展点 + 资源覆盖"扛着并登记技术债？

---

## 附录 A：本机实测（Chrome 153.0.8010.52，Windows，2026-09-21）

命令：`chrome --headless --disable-gpu --no-sandbox --dump-dom <probe.html>`

| 项 | stock headless | 加 `--user-agent=<Chrome UA> --lang=en-US` |
|---|---|---|
| `navigator.userAgent` | `… HeadlessChrome/153.0.0.0 Safari/537.36` | `… Chrome/153.0.0.0 Safari/537.36` |
| `userAgentData.brands` | `Google Chrome 153`（与 UA 矛盾！） | `Google Chrome 153`（一致） |
| Worker 的 UA | 同主世界（含 HeadlessChrome） | 同主世界（无 HeadlessChrome）**→ 覆盖会传播到 Worker** |
| `navigator.language(s)` | 主世界 zh-CN；Worker zh-CN | 主世界 en-US；Worker en-US **→ 也会传播** |
| `hardwareConcurrency` | 主世界 20；Worker 20（一致） | — |
| `outerWidth/Height` | `0x0` | — |
| `innerWidth - clientWidth` | 0（`--hide-scrollbars`） | — |
| `navigator.plugins / mimeTypes` | 5 / 2 | — |
| WebGL renderer | `ANGLE (Microsoft, Microsoft Basic Render Driver (0x0000008C) Direct3D11 …, D3D11)` | — |
| `Error.prepareStackTrace` 自有属性 | **不存在**（stealth.js 的 defineProperty 会造出一个不可配置属性） | — |

结论：**stock headless 的主世界与 Worker 本身是自洽的**（都报主机真值）；
当前的矛盾完全是 `js/stealth.js` 只在主世界改写 `hardwareConcurrency`/`languages` 造成的。

## 附录 B：关键证据索引

| 主题 | 位置 |
|---|---|
| stealth.js 版本与 evasion 清单 | `pulsar-browser-4.11.16.jar!js/stealth.js`（13 个 evasion，v2.9.0 默认参数） |
| 注入点（仅 page world） | `PulsarWebDriver.kt:2250-2258`、`DirectChromeProtocol.kt:264-271`、`DualWorldScriptLoader.kt:38-56` |
| 启动参数构造 | `Options.kt:65-123,209-254`、`ChromeDefaults.kt:22-49`、`BrowserSettings.kt:623-653` |
| 几何覆盖 | `PulsarWebDriver.kt:1977-1983,1044-1051`；`DirectChromeProtocol.kt:442-461` |
| CDP 泄漏 | `PulsarWebDriver.kt:1942-1948`；页面 console 补丁 `Browser4WebDriver.kt:359-392` |
| 死缝/未接线 | `PulsarWebDriver.kt:183,200-202`；`AbstractBrowser.kt:41`；`BrowserSettings.kt:565` |
| 仓库内实现点 | `Browser4WebDriver.kt`（`from()`/`init`/`executeCdpCommand`）；换入路径 `AbstractPulsarSession.kt:278,315-318`、`PulsarSessionManager.kt:907-912` |
| 现有测试 | `BotDetectionE2ETest.kt`（@Disabled）、`FingerprintApplicationIT.kt`（只打印） |
| 人工测量的真实结论 | `coworker/tasks/issues/draft/20260916-174704-*`、`20260920-104452-*` |
