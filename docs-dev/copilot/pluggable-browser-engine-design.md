# 可拔插浏览器引擎（Browser Engine SPI）设计方案 — 以 Lightpanda 为例

> 状态：设计稿（未实现）。目标读者：维护 driver / launch funnel / MCP 工具链的人。
> 跟踪 issue：[#646](https://github.com/platonai/Browser4/issues/646)。
> 关联文档：[browser-behavior-modules.md](../../docs/browser-behavior-modules.md)（本文完全对齐它的模块划分与
> ServiceLoader 选型风格）、[env-purity-switch-design.md](env-purity-switch-design.md)（同样以
> `DefaultBrowserFactory` 为唯一漏斗的先例）、[config.md](../../docs/config.md)、[TESTING.md](../../docs/TESTING.md)。

## 0. 结论（先看这三句）

1. **不要为 Lightpanda 写一个新的 `WebDriver` 实现。** `ai.platon.pulsar.api.WebDriver` 有 400+ 个方法，
   而 `BrowserTabToolExecutor` / `AgentToolManager` 里共有 **25 处 `is/as? Browser4WebDriver` + 13 处
   `is/as? PulsarWebDriver`** 的类型判定。换一个 driver 类，这些判定会**静默走 fallback 分支**——
   项目最忌讳的那种"看起来成功、实际降级"。
2. **把"支持一个新浏览器"拆成两个可独立插拔的维度**：**引擎（engine）** = 怎么启动、怎么连、有什么能力；
   **协议（protocol）** = CDP 客户端，继续共用 `ChromeImpl` / `CdpWsRpc` / `DirectChromeProtocol`。
   Lightpanda 说 CDP，所以它只需要一个新**引擎**，不需要新协议栈。
3. **能力是数据，不是类型；不支持就显式报错，永远不返回空壳结果。**
   Lightpanda 没有布局/渲染（无 bbox、无 `Emulation`、无截图），这些能力必须在会话建立时就变成
   可查询的 `EngineCapabilities`，并在工具层变成可读的错误或明确标注的 DOM 降级路径。

---

## 1. 现状：一个新的浏览器要过 6 道关

| # | 关卡 | 现在的实现 | Lightpanda 会撞到什么 |
|---|---|---|---|
| 1 | 进程启动 | `ChromeLauncher.launch(binary, ChromeOptions)`：拼 `--remote-debugging-port` / `--user-data-dir` / `--headless=new`，等 `DevToolsActivePort` 文件或 `DevTools listening on ws://` 日志行 | Lightpanda 是 `lightpanda serve --host --port`，没有这些 switch、不写 `DevToolsActivePort` |
| 2 | 通道选择 | `DefaultBrowserFactory.launch`（`browser4-protocol`）读 `browser.channel`，在 `mapOf(BrowserType.PULSAR_CHROME to ..., "ego-lite" -> EgoLiteBrowserLauncher())` 里挑 launcher | 通道名与会话能力都卡在 `B4Constants` 的 `chrome / ego-lite` 白名单里（`B4Constants.kt:121-134, 228-231`） |
| 3 | 浏览器类型 | `BrowserType` 是上游封闭枚举：`NATIVE/PULSAR_CHROME/PLAYWRIGHT_CHROME`；`BrowserProfile.create(BrowserType.PULSAR_CHROME, dir)` 在会话路径里硬编码 | 无处安放 `LIGHTPANDA`，除非改上游库 |
| 4 | 握手 | `ChromeImpl(host, port)`：`GET /json/version` → `ChromeVersion`，`GET /json/list` → `BrowserTab[]`，每 tab 一条 WS | 基本能过；风险在 `ChromeVersion` 字段宽松度（见 `browser4-dependencies/pom.xml:569-576` 记录过的 "Unrecognized field" 事故） |
| 5 | 驱动装配 | `AbstractPulsarSession.createBoundDriver`：`driver as PulsarWebDriver` → `Browser4WebDriver.from(...)`（`AbstractPulsarSession.kt:346-350, 406-410`） | 可行——`Browser4WebDriver` 只需要 `(tab, protocol, PulsarBrowser)`；`PulsarBrowser(port, id, settings)` 这个 attach 构造器就够 |
| 6 | 能力假设 | `Browser4WebDriver` 的可信点击/拖拽/滚动/截图/`Emulation`/ARIA 快照，全部假定"有布局、有渲染、有 AX 树" | 直接崩：Lightpanda 不支持 `Page.captureScreenshot`、`Emulation.*`、computed style、`getBoundingClientRect` |

**结论**：第 5 关其实已经通了（`attach --cdp` 就是这么工作的），真正缺的是 1/2/3（启动与选型）和 6（能力）。
所以方案的重心不是"再写一个驱动"，而是**引擎选型 + 能力协商**。

### 1.1 非目标（本方案明确不做）

- **非 CDP 引擎的 transport**（Playwright / WebDriver-BiDi / Lightpanda 自有 HTTP API）：SPI 不阻挡它们
  （`BrowserEngine.launch` 返回 `Browser`，不是 `PulsarBrowser`），但本期只交付 `CHROMIUM` 与
  `CDP_NON_CHROMIUM` 两个家族。
- **用 Lightpanda 替换默认引擎**：默认仍是 Chrome，Lightpanda 是 opt-in 通道。
- **为 Lightpanda 补齐缺失能力**（例如用它的 `LP` 域接 AX 树）：后续独立评估，不阻塞本方案。
- **修改上游 `pulsar-browser`**：`BrowserType`、`ChromeLauncher`、`PulsarWebDriver` 一律不 fork。

---

## 2. 设计原则

1. **引擎决定"是不是 Chrome"，协议层只认 CDP。**
   `EngineFamily = CHROMIUM | CDP_NON_CHROMIUM | NATIVE`。Chromium 家族（Chrome/Edge/ego-lite）复用
   `ChromeLauncher`；`CDP_NON_CHROMIUM`（Lightpanda，将来的 Firefox-over-BiDi 亦可归此类走另建 transport）
   复用 `ChromeImpl` + `CdpWsRpc`，只换进程启动与能力表。
2. **能力是数据。** 会话绑定后立刻可查 `session.engineCapabilities`；判定条件是
   `caps.supports(Capability.LAYOUT)`，永远不是 `driver is XxxDriver`。这样 38 处类型判定继续成立
   （会话仍然拿到 `Browser4WebDriver`），只是**驱动内部行为**按能力分支。
3. **探测，不要相信文档。** Lightpanda 每周都在变：`GET /json/protocol` 自述 + 一次 fixture 实测，
   结果缓存为 `ProbeReport`（key = engine id + version + 二进制哈希）。文档里的矩阵只作兜底。
4. **不支持 = 显式失败。** 三条出路：等价降级（DOM click）/ 明确标注的降级（返回 `mode: "dom"`）/
   typed error（`unsupported_capability`）。**没有"返回空数组"这一条。**

### 2.1 备选方案与取舍

| 方案 | 好处 | 致命点 | 结论 |
|---|---|---|---|
| A. 新写 `LightpandaWebDriver : WebDriver` | 概念最"干净" | `WebDriver` 400+ 方法；25 处 `Browser4WebDriver` + 13 处 `PulsarWebDriver` 判定全部走 fallback；每个 driver 修复要写两遍 | 否 |
| B. 给 `BrowserType` 加 `LIGHTPANDA`，fork `ChromeLauncher` | 改动集中 | `BrowserType` 在上游 jar 里（封闭枚举）；fork 意味着两处维护 | 否（改为本仓库内部 `engineId: String`） |
| C. 只支持 `attach --cdp`（不负责启动） | 几乎零成本，**今天就能用** | 用户得自己起进程；没有能力协商；`--channel`/`open` 不可用 | 作为 P3 里程碑，不作为终态 |
| **D. 引擎 SPI + 能力矩阵（本方案）** | 复用 CDP 协议栈与 `Browser4WebDriver`；新增浏览器 = 新增 jar；降级可见可测 | 需要一次能力贯通重构（P1/P2） | **采用** |

---

## 3. SPI 模块布局

对齐 `browser4-behavior-*` 的三段式（api / default / optional-provider）：

```
browser4-core/
  browser4-engine-api/          # 仅契约 + 注册表，无实现（依赖 pulsar-browser、browser4-common）
    ai.platon.browser4.engine.BrowserEngine          # 引擎契约
    ai.platon.browser4.engine.BrowserEngines         # ServiceLoader 注册表（对齐 BrowserBehavior）
    ai.platon.browser4.engine.EngineCapabilities     # 能力矩阵（纯数据）
    ai.platon.browser4.engine.EngineLaunchRequest    # 启动入参
    ai.platon.browser4.engine.ManagedBrowserProcess  # 进程句柄（pid/cmdline/killTree）
  browser4-engine-chromium/     # 今天的 Chrome + ego-lite，原样搬过来
    ChromiumEngine（id `chrome`）、EgoLiteEngine（id `ego-lite`）
    META-INF/services/...                            # ServiceLoader 注册
  browser4-engine-lightpanda/   # 可选模块，不随发行包默认打包
    LightpandaEngine（id `lightpanda`）+ LightpandaCapabilities + LightpandaProbe
    META-INF/services/...
```

依赖方向（关键：SPI 只被**编译期**引用，实现是**运行期**依赖）：

```
browser4-protocol (DefaultBrowserFactory)
      │ compile
      ▼
browser4-engine-api ──compile──▶ pulsar-browser (Browser/PulsarBrowser/ChromeImpl)
      ▲ runtime
      ├── browser4-engine-chromium
      └── browser4-engine-lightpanda     (可选，缺失时 registry 只报告已安装引擎)
```

这样"加一个引擎"= 加一个 jar，和 `browser.behavior.stealth.provider` 的替换机制完全一致；
`browser4-protocol` 一行 `if (channel == "lightpanda")` 都不需要。

---

## 4. 关键类型

```kotlin
package ai.platon.browser4.engine

/** 引擎家族：决定复用哪条启动/连接路径。 */
enum class EngineFamily { CHROMIUM, CDP_NON_CHROMIUM, NATIVE }

/** 一个可启动、可连接、可自述能力的浏览器引擎。 */
interface BrowserEngine {
    /** 稳定 id，同时是 `browser.channel` 的合法值：chrome / ego-lite / lightpanda / ... */
    val id: String
    val family: EngineFamily
    /** 与 BrowserBehavior 一致：id 未配置时取 priority 最高者，同优先级按 id 排序保证稳定。 */
    val priority: Int get() = 0

    /** 是否安装/可用；不可用时给出人类可读原因（`open --channel lightpanda` 的报错就来自这里）。 */
    fun availability(settings: BrowserSettings): EngineAvailability

    /** 启动并接管一个浏览器进程，返回一个**可驱动的** Browser。 */
    fun launch(request: EngineLaunchRequest): Browser

    /** 附着到已在跑的端点（`attach --cdp`），不接管进程。 */
    fun attach(endpoint: EngineEndpoint, settings: BrowserSettings): Browser

    /** 探测并返回能力矩阵；实现应缓存，probe 失败要显式报错而不是返回"全不支持"。 */
    fun capabilities(version: String?): EngineCapabilities
}

/** 安装状态；`Unavailable.reason` 直接进 `open --channel <id>` 的报错。 */
sealed interface EngineAvailability {
    data class Available(val detail: String?) : EngineAvailability
    data class Unavailable(val reason: String) : EngineAvailability
}

/** 一个 CDP 端点；`browserWebSocketUrl` 用于只提供 browser-level WS 的端点（attach 已有此路径）。 */
data class EngineEndpoint(val host: String, val port: Int, val browserWebSocketUrl: String? = null)
```

```kotlin
/** 三态能力状态：支持 / 有等价降级 / 不支持。不要用 Boolean，降级路径需要被描述。 */
sealed interface CapabilityState {
    data object Supported : CapabilityState
    data class Degraded(val mode: String, val note: String) : CapabilityState
    data class Unsupported(val reason: String, val alternative: String? = null) : CapabilityState
}

/** 能力枚举就是"Browser4 到底依赖浏览器的什么"。每一项都要能指到代码里的消费点。 */
enum class Capability {
    DOM, JS_EVALUATE, DOCUMENT_START_SCRIPTS,
    NETWORK_EVENTS, REQUEST_INTERCEPTION, COOKIES, DOM_STORAGE,
    FRAMES, TABS,
    TRUSTED_INPUT,          // Input.* 是否真的产生可信事件（含命中测试）
    LAYOUT,                 // getBoundingClientRect / box model / isVisible
    SCREENSHOT, PDF,
    VIEWPORT_EMULATION,     // Emulation.setDeviceMetricsOverride 等
    ACCESSIBILITY_TREE,     // Accessibility.getFullAXTree
    DIALOGS, DOWNLOADS, HAR,
    USER_AGENT_OVERRIDE, PROFILES, PROCESS_OWNED,
}

data class EngineCapabilities(
    val engineId: String,
    val version: String?,
    val caps: Map<Capability, CapabilityState>,
    val probedAt: Instant,
    val source: String,                 // "declared" | "json-protocol" | "fixture-probe"
) {
    fun supports(c: Capability) = caps[c] is CapabilityState.Supported
    fun capability(c: Capability) = caps[c] ?: CapabilityState.Unsupported("not reported by engine")
}
```

```kotlin
/**
 * 启动入参。
 *
 * [options] 的类型名带 `Chrome` 是上游的历史包袱，它其实是**通用启动参数袋**
 * （headless / proxy / UA / rawArguments / additionalArguments）。Chromium 引擎整包用；
 * 非 Chromium 引擎只读通用字段（headless、proxy、UA、extra args），忽略 Chromium 专有项，
 * 这也让 stealth provider 的 `applyLaunchOptions` 保持唯一入口。
 */
data class EngineLaunchRequest(
    val browserId: BrowserId,          // 已含 userDataDir / profile mode
    val launcherOptions: LauncherOptions,
    val options: ChromeOptions,        // 已被 stealth provider 改写过的那一份
    val port: Int?,                    // 引擎自己分配；null 表示引擎决定
    val env: Map<String, String> = emptyMap(),
)
```

注册表（照抄 `BrowserBehavior` 的选型规则，含额外 class loader 注册与 `reset()`）：

```kotlin
object BrowserEngines {
    fun installed(): List<BrowserEngine>
    fun resolve(channel: String?, config: ImmutableConfig): BrowserEngine   // 未知 id → 响亮报错，列出已装引擎
    fun identify(browserProduct: String?): String?                          // "Lightpanda/1.2" → "lightpanda"
    fun registerProviderClassLoader(loader: ClassLoader)
    fun reset()
}
```

---

## 5. 三个接入点（尽量小的 diff）

### 5.1 启动漏斗：`DefaultBrowserFactory.launch`

```kotlin
// 之前：channel 白名单 + launcher map
// 之后：
val stealth = BrowserBehavior.stealth(launcherOptions.settings.config)
stealth.applyLaunchOptions(StealthLaunchContext(launchOptions, launcherOptions.settings, channel))
val engine = BrowserEngines.resolve(channel, launcherOptions.settings.config)   // 未知通道在此响亮失败
return engine.launch(EngineLaunchRequest(browserId, launcherOptions, launchOptions))
```

* `chrome`/`ego-lite` 变成 `EgoLiteEngine`/`ChromiumEngine`，行为零变化（它们内部还是调
  `ChromeLauncher` + `PulsarBrowser(browserId, service, settings, chromeLauncher)`，见
  `EgoLiteBrowserLauncher.kt:151-171`）。
* 未知通道的报错保持今天的语义，只是把"Supported channels: chrome, ego-lite"改为**已安装引擎列表**。

### 5.2 会话绑定：`AbstractPulsarSession.createBoundDriver`

今天 `driver as PulsarWebDriver` 是硬 cast。改成：

```kotlin
val raw = browser.newDriver()
val pulsarDriver = raw as? PulsarWebDriver
    ?: throw WebDriverUnavailableException(
        "Engine '${browserEngineId}' returned ${raw::class.simpleName}, which is not a CDP-backed driver. " +
        "Only CDP engines are supported today (see docs-dev/copilot/pluggable-browser-engine-design.md)."
    )
val b4 = Browser4WebDriver.from(pulsarDriver)
b4.attachEngine(engine.capabilities(...))       // 会话级能力，供工具层查询
```

`attachEngine` 只是 `Browser4WebDriver` 上的一个方法（不引入新类型）：能力挂在 driver 上
（`Browser4WebDriver.capabilities`），由它在创建时从 `PulsarBrowser` 的 `data` 或 `BrowserSettings` 里取。

### 5.3 附着：`PulsarSessionManager.createAttachedSession`

已经有的 `verifyCdpEndpoint`（`/json/version` + `/json`）保留，补两步：

```kotlin
val engineId = BrowserEngines.identify(verification.browser) ?: "unknown"   // "Lightpanda/.." → lightpanda
session.browserIdentity = BrowserIdentity.parse(verification.browser)       // 新增 lightpanda 分支
session.engineId = engineId
session.engineCapabilities = BrowserEngines.resolve(engineId, conf).capabilities(verification.browser)
```

`BrowserIdentity.parse`（`ManagedSession.kt:144-169`）加一条 `Lightpanda/` → family `lightpanda`，
这样 `attach --cdp lightpanda` 的 family 校验（`channelMismatch`）才有东西可比。

---

## 6. Lightpanda 适配器怎么做

### 6.1 事实（外部资料，仅作起点，实测为准）

| 项 | 状态 |
|---|---|
| 启动方式 | `lightpanda serve --host 127.0.0.1 --port 9222`（长驻 CDP 服务器，无 `--user-data-dir`） |
| 支持 | `Page`(navigate/reload/getFrameTree)、`Runtime`、`DOM`、`Network`(enable/cookies)、`Target`、`Input`(key/mouse)、`Fetch` |
| 部分支持 | `CSS`(无 computed style)、`DOMStorage`、`Log` |
| 不支持 | `Emulation.*`、`Page.captureScreenshot`、`Page.printToPDF`、`Overlay`、`Accessibility`(CDP)、`getBoundingClientRect`、`requestAnimationFrame` |
| 自述 | `GET /json/protocol` 返回 CDP protocol JSON（可作为能力推导输入） |

来源：[Lightpanda CDP 兼容矩阵](https://github.com/Mathews-Tom/armory/blob/main/skills/lightpanda-browser/references/compatibility.md)、
[`serve` 命令文档](https://lightpanda.io/docs/reference/cli/serve)、
[Input 域实现](https://deepwiki.com/lightpanda-io/browser/5.1.5-input-domain)。
许可证 AGPL-3.0 → **必须进程外驱动**（不得静态链接），这正好和本方案的"引擎自带进程"路线一致。

### 6.2 v0：先打分，不写代码（0.5–1 天，强烈建议先做）

```bash
lightpanda serve --host 127.0.0.1 --port 9222 &
browser4-cli attach --cdp http://127.0.0.1:9222
browser4-cli goto https://example.com
browser4-cli snapshot -v 0        # DOM/JS 路径是否活
browser4-cli htmlsnapshot get     # 序列化路径
browser4-cli click "#a"           # 可信点击会不会因为无布局而错点
browser4-cli screenshot           # 预期失败，记录错误文本
```

这一步产出**真实的能力事实**（哪些调用抛什么错），是后面 `LightpandaCapabilities` 的输入。
不要跳过它直接写 SPI——否则会按文档写一张和现实不符的矩阵。

### 6.3 v1：`LightpandaEngine`

```kotlin
class LightpandaEngine : BrowserEngine {
    override val id = "lightpanda"
    override val family = EngineFamily.CDP_NON_CHROMIUM

    override fun availability(settings: BrowserSettings): EngineAvailability {
        val bin = settings.config.get("lightpanda.path")?.let(Path::of)
            ?: LightpandaBrowsers.searchPath()          // PATH / ~/.cache/lightpanda / 安装目录
        return if (bin != null && Files.isExecutable(bin)) EngineAvailability.Available(bin.toString())
        else EngineAvailability.Unavailable(
            "lightpanda binary not found. Install it or set -Dlightpanda.path=<binary>."
        )
    }

    override fun launch(request: EngineLaunchRequest): Browser {
        val port = request.port ?: freePort()                       // ServerSocket(0) 取空闲端口，冲突重试一次
        val process = LightpandaProcess.start(binary, port, request) // serve --host 127.0.0.1 --port <port>
        waitForCdp(port, timeout = request.launcherOptions.startupWaitTime)  // GET /json/version 轮询
        val browser = PulsarBrowser(port, request.browserId, request.launcherOptions.settings)  // attach 构造器
        processRegistry.put(browser, process)                       // close/关 JVM 时 killTree
        return browser
    }

    override fun capabilities(version: String?) =
        LightpandaCapabilities.probeOrCache(version)                // 见 6.4
}
```

要点：

* **端口**：不能用端口 0（没有 `DevToolsActivePort` 回读机制），必须自己挑并处理抢占。
* **进程**：`BrowserProcessReaper` 今天依赖两件事——`<contextDir>/launcher.pid` 标记，
  以及"命令行里含 `--user-data-dir=<contextDir>`"这条身份校验（
  `BrowserProcessReaper.kt:262-298`）。`lightpanda serve` 没有 `--user-data-dir`，**身份校验永远为假，
  reaper 会拒绝杀掉自己启动的僵尸进程**。所以要么由 `ManagedBrowserProcess` 提供引擎自己的
  匹配谓词（命令行含引擎二进制路径 + 端口），要么让引擎写自己的 pid 标记文件并在匹配时放行。
* **`Browser4WebDriver.from()` 的前置检查**：`RuntimeScriptContract.verify()` 只校验 vendored JS 与
  jar 一致（与浏览器无关，安全）；但 `NetworkObserver.preRegister()` / `RouteManager.preRegister()`
  会 `Network.enable` 并抢 CDP 事件槽——在 `NETWORK_EVENTS` 能力未确认前必须跳过，否则驱动创建即失败。

### 6.4 v2：能力探测与降级落点

```
LightpandaCapabilities.probe():
  1) GET /json/protocol  → 与 REQUIRED_COMMANDS 求交，得出"声明能力"
  2) fixture 实测（static/b4/lightpanda-probe.html）：
       Runtime.evaluate / DOM.getDocument / Page.navigate + loadEventFired
       Input.dispatchMouseEvent → 页面上 event.isTrusted? 命中元素对不对?
       Page.captureScreenshot → 期望 error，记录 code
       Emulation.setDeviceMetricsOverride → 同上
  3) 结果写入 <app data>/engine/lightpanda-<version>-<hash>.json，命中即复用
```

**降级落点表**（这是"支持 Lightpanda"真正的工程量，不是启动）：

| 工具/驱动方法 | Chrome | Lightpanda 策略 |
|---|---|---|
| `navigate` / `eval` / `htmlsnapshot` / `snapshot`(DOM) | 原路径 | 原路径 |
| `click` / `dblclick` / `hover` | 可信 CDP 输入，元素中心 + 抖动 | `TRUSTED_INPUT=Degraded` → DOM `el.click()`；结果里带 `mode: "dom"`，并在 stderr 提示不可信 |
| `fill` / `type` / `press` | CDP 键盘 | `Input` 可用则原路径，否则 `execCommand('insertText')` 降级（`typeAuto` 已有 `exec` 分支） |
| `drag` | 可信拖拽序列 | `LAYOUT=Unsupported` → 显式 `unsupported_capability`（不能瞎点） |
| `screenshot` / `pdf` | 原路径 | `unsupported_capability` + `alternative: "open --channel chrome"` |
| ARIA snapshot（AX 树） | `Accessibility.getFullAXTree` | `ACCESSIBILITY_TREE=Unsupported` → 回退 DOM 快照并标注（Lightpanda 用自己的 AX 端点，可后续接） |
| `device` / `viewport` / `media` | `Emulation.*` | `unsupported_capability` |
| `network route` | `Fetch`/`Network` | `Network` 支持则可用，`REQUEST_INTERCEPTION` 探测为准 |
| HAR | CDP 事件聚合 | `HAR=Unsupported`（除非 `Network` 事件齐备） |
| stealth 页面注入 | `Page.addScriptToEvaluateOnNewDocument` | `DOCUMENT_START_SCRIPTS=Unsupported` → 跳过并**只告警一次**；同时把 `stealthAppliesTo("lightpanda")` 设为 `false`（无渲染 → 无指纹可骗，注入只会增加可探测面） |

驱动内部的分支点写成一处策略对象，而不是散落 if：

```kotlin
class CapabilityPolicy(val caps: EngineCapabilities) {
    val preferDomClick get() = !caps.supports(Capability.TRUSTED_INPUT) || !caps.supports(Capability.LAYOUT)

    fun state(c: Capability): CapabilityState = caps.capability(c)

    /** Unsupported → 抛 typed 异常（带 engine id / 能力名 / 替代建议），Degraded → 返回降级模式名。 */
    fun requireSupported(c: Capability, action: String): String? { ... }
}
```

---

## 7. 能力闸门放在哪（单点 + 纵深）

1. **单点**：`AgentToolManager.execute()` —— AGENTS.md 记录的"所有 MCP 工具调用的唯一漏斗"。
   加一张 `method → Set<Capability>` 表（默认空 = 不拦，fail-open），命中不可用能力时抛
   `UnsupportedCapabilityException`，由 MCP 层转成稳定错误码：

```json
{ "error": { "code": "unsupported_capability",
             "message": "screenshot requires SCREENSHOT, which engine 'lightpanda' does not provide.",
             "data": { "engine": "lightpanda", "capability": "SCREENSHOT",
                       "alternative": "open --channel chrome (or attach --cdp to a Chromium browser)" } } }
```

2. **纵深**：`Browser4WebDriver` 的 override 内部再判一次——因为工具层之外还有 `PulsarSession`
   直接调用驱动的路径（crawl / htmlsnapshot / agent-tools）。
3. **可见性**：`GET /mcp/tools` 的 **session 段**（每请求合并，注意别动缓存的 static 段）按当前会话能力过滤
   或用 `requires` 标注；`ToolSpec` 加一个 `requires: Set<String>? = null` 字段即可
   （`Models.kt:44` 已有 `cacheable`/`task`/`rateLimit` 这类可选元数据的先例）。
4. **诊断**：`browser4-cli doctor --json` / `status` 输出
   `{ "engine": "lightpanda", "version": "1.2.3", "capabilities": { "LAYOUT": "unsupported", ... } }`，
   让"为什么这个命令不能用"不需要读日志。

---

## 8. 配置与 CLI 面

```properties
# 新增：默认引擎（会话能力 channel / --channel 仍然覆盖它）
browser.engine=chrome
# 兼容：browser.channel 保留为用户可见别名，值域从"两个名字"变成"已安装引擎 id"
# browser.channel=lightpanda

# Lightpanda 专有
# lightpanda.path=C:\tools\lightpanda.exe
# lightpanda.args=--log-level=info
# lightpanda.probe.cache=true
```

CLI 改动点（都在已有位置，不新增命令）：

| 位置 | 改动 |
|---|---|
| `commands.rs:835-843` | `--channel` 归一化表去掉硬编码 `chrome / ego-lite`，改成"原样下传 + 后端校验"（后端本来就要校验） |
| `help.rs` / `SKILL.md` | 通道列表改为"以 `doctor` 报告的已安装引擎为准" |
| `attach --cdp <url 或 channel>` | 新增 `lightpanda` 通道：默认端口 + 不读 `DevToolsActivePort`（走 `--cdp http://127.0.0.1:9222`） |
| `open --channel lightpanda` | 无需新代码，走 5.1 的引擎解析 |

---

## 9. 测试与 CI

| 层 | 测什么 | 放哪 |
|---|---|---|
| 单元 | `BrowserEngines.resolve`（未知 id 报错、优先级、classloader 注册）、`EngineCapabilities.supports`、能力闸门表、`BrowserIdentity.parse("Lightpanda/..")` | `browser4-engine-api/src/test`，`browser4-rest` 既有 `BrowserIdentityTest` / `CdpEndpointVerificationTest` 旁 |
| 集成（无真浏览器） | 伪 CDP 服务器（Rust/PS 均可，参考 `docs-dev/fake-mcp-server.ps1` 的做法）：断言启动握手、`/json/version` 字段缺失时的容错、`Unsupported` 路径确实抛 typed 错误 | `browser4-engine-lightpanda/src/test` |
| 契约 | **能力矩阵 golden 文件**：`engine-capabilities.lightpanda.json` 快照。Lightpanda 升级导致能力翻转时，diff 必须人工确认 | 同上 |
| E2E | fixture 页面 `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/lightpanda-probe.html`（可信点击 vs DOM 点击、遮挡、iframe），`requires_browser4: true` + `@Tag("RequiresLightpanda")` | nightly（下载二进制），PR 门禁跳过 |
| CI | 新增 tag `RequiresLightpanda` 到 `docs/TESTING.md` 的排除表；nightly 单独 job | `.github/workflows/nightly.yml` |

按 AGENTS.md 的 "Direct CDP methods: mandatory review gates"，`click`/`fill` 在 Lightpanda 上的
DOM 降级必须走 fixture + 真浏览器验证，单测不算完成。

---

## 10. 风险与取舍

| 风险 | 说明与对策 |
|---|---|
| **能力矩阵随版本漂移** | 不硬编码矩阵，用 `/json/protocol` + fixture 探测 + golden 快照；版本变化触发重探 |
| **`BrowserType` 是上游封闭枚举** | 不扩上游枚举。新增 `engineId: String`（本仓库），`BrowserType` 继续用于 profile 目录/tab 命名的兼容字段；`BrowserProfile.create(BrowserType.PULSAR_CHROME, ...)` 改为 `create(engine.defaultBrowserType, ...)` |
| **`ChromeVersion` 解析严格** | 历史事故（`browser4-dependencies/pom.xml:569-576`）说明它会因未知字段炸掉导航。引擎侧必须先容错解析，再决定是否降级 |
| **`PulsarBrowser` 是 final 且要求 `ChromeService`** | 用 attach 构造器 `PulsarBrowser(port, id, settings)`，进程归引擎管；不 fork 上游类 |
| **进程回收靠 `--user-data-dir` 认得自己** | Lightpanda 命令行没有这个参数 → `commandsThisProfile` 恒假 → 僵尸进程不会被回收。必须扩成引擎提供的匹配谓词，否则并发跑久了会攒进程 |
| **无布局 = Browser4 的核心价值打折** | 明确产品语言：Lightpanda 定位"抓取/无渲染自动化"（htmlsnapshot、eval、表单、crawl），不做视觉断言/截图/拖拽。不要用降级伪装成 Chrome |
| **AGPL-3.0** | 只做进程外调用（exec + CDP），不链接、不内嵌、不随包分发；`browser4-engine-lightpanda` 不进默认发行包 |
| **反检测** | 无渲染引擎没有指纹面，注入 stealth 只会增加可探测特征 → `stealthAppliesTo` 对其为 `false`（与 ego-lite 同一机制，`B4Constants.kt:121-134`） |

---

## 11. 落地顺序（每步都可独立发布）

| PR | 内容 | 验收 |
|---|---|---|
| **P0（spike，1 天）** | 手工 `lightpanda serve` + `attach --cdp` 打分，产出实测矩阵 | 一页报告：哪些命令 OK、哪些报什么错 |
| **P1（纯重构，无行为变化）** | 新增 `browser4-engine-api` + `browser4-engine-chromium`；`DefaultBrowserFactory` 改为 registry 解析；chrome/ego-lite 注册 | 全量 `mvn -DskipTests` + `EgoLiteBrowserLauncherTest`/`PulsarBrowserFactoryTest` 绿；`browser4-cli doctor` 打印 engine=chrome |
| **P2** | `EngineCapabilities` 贯通到 driver + `AgentToolManager` 闸门 + `unsupported_capability` 错误码 + `ToolSpec.requires` | 新单测；chrome 会话能力全 Supported，行为零变化（e2e 回归） |
| **P3** | `attach --cdp` 的引擎识别（`identify` + `BrowserIdentity` + `channelMismatch`）——**这一步之后 Lightpanda 已可手工 attach 使用** | `browser4-cli attach --cdp http://127.0.0.1:9222` 后 `goto/eval/htmlsnapshot` 通过；`screenshot` 返回 `unsupported_capability` |
| **P4** | `LightpandaEngine`：进程、端口、探测、`ManagedBrowserProcess`、reaper 适配 | 集成测试（伪 CDP）+ golden 矩阵 |
| **P5** | `open --channel lightpanda`、help/SKILL/docs、nightly e2e | `@RequiresLightpanda` 用例 + 文档更新（README/SKILL/help 全套） |

---

## 12. 验收清单（对齐 Definition of Done）

- [ ] 未安装 Lightpanda 时，`open --channel lightpanda` 报错信息包含 `availability` 的原因和修复方法
- [ ] 安装后 `goto` / `eval` / `snapshot` / `htmlsnapshot` / `fill` / `click` 全通，且 `click` 结果标明 `mode`
- [ ] `screenshot` / `drag` / `device` 返回 `unsupported_capability`，**不是**空结果、不是 500、不是栈
- [ ] `doctor --json` 能看到 engine + version + 能力矩阵
- [ ] Chrome 会话的行为与今天**逐字节一致**（P1/P2 是纯重构，跑一遍 e2e 全绿）
- [ ] 新增 tag、golden 矩阵、fixture 页面、`docs/TESTING.md` 排除表同步更新
- [ ] 没有把 Lightpanda 二进制或 AGPL 代码打进发行包
