# 环境纯净度开关（禁用用户扩展）实现方案

> 定位：工程实现方案（团队整理），供评审。目标读者：Browser4 后端维护者。
> 事实依据：2026-10-14 对 ipfighter `chrome.runtime.sendMessage` "Prototype leak" 告警的归因取证（见 [browser4-antibot-detection-实测报告.md](../../../docs/marketing/0draft/capabilities/browser4-antibot-detection-实测报告.md) §5.2(3)）。
> 状态：**已实现，端到端验证完成（2026-10-14）——开关机制生效，但 §1.1 根因归因被 E2E 复核证伪**（见 §1.1 修正与 §4.1 实测）。主世界骨架的真实来源是驱动自身的注入机制，待独立调查。
> 合规前提：本开关服务于**环境纯净度**，不用于反检测、不用于规避风控。任何对外表述须遵守项目既有合规红线（不得宣称"反检测""防关联""过风控"）。

---

## 一、问题与目标

### 1.1 根因（已归因，2026-10-14；**E2E 复核证伪，见下方修正**）

| 环节 | 事实 |
|---|---|
| 扩展来源 | 会话浏览器的 profile（`.../context/groups/named/PULSAR_CHROME/cx.<uuid>/PULSAR_CHROME/`）自 prototype 目录**整目录复制**，连同本机用户扩展（WPS 金山文档、迅雷下载支持）一并进入 `Default/Extensions` |
| 注入机制 | 扩展 content script 注入 renderer 后，**Chromium 内建机制**在页面主世界安装 `chrome.runtime` 骨架（`sendMessage` 为 native code 且自带 `prototype`，`runtime.id` 为 undefined） |
| 触发判定 | ipfighter 判定 `chrome.runtime.sendMessage` 存在且自有 `prototype` → 报 `Prototype leak` |
| 反证 | 裸 headless Chrome（干净 profile + `--disable-extensions`）全程无 `chrome.runtime`，该站判 `N/A`（pass） |

关键性质：这**不是** Browser4 代码注入，也**不是** stealth 插件伪造；它是"浏览器里装了扩展"的自然结果。装了扩展的真实用户访问该站同样中招。

> **⚠️ 归因修正（2026-10-14 E2E 复核）**：上述归因**不成立**。开关落地后实测（`--disable-extensions` 已确认出现在 Chrome 命令行、CDP `Runtime.enable` 全程未见任何扩展 isolated world、用户扩展 WPS kdocs 未注入），主世界骨架**依然出现**。同一浏览器、同一 profile 的对照实验表明：
>
> | 页面导航方式 | 主世界骨架 | ipfighter sendMessage 判定 |
> |---|---|---|
> | **驱动导航**（正常会话流程，CLI open/goto） | **存在**（`object`，sendMessage 自有 prototype） | `Prototype leak` |
> | **原始 CDP 导航**（绕过驱动，直连 Chrome 调试端口） | **不存在**（`undefined`） | `N/A`（pass） |
> | example.com（驱动导航） | 不存在 | — |
>
> 即：骨架由 **Browser4 驱动自身的注入机制**触发（驱动操作 tab 时创建的 `__browser4_runtime__` isolated world 及其配套注入路径），与用户扩展**无关**。原始归因的对照实验（裸 Chrome 无扩展也无驱动）混淆了"无扩展"与"无驱动"两个变量。
>
> 影响：本开关仍按设计生效（禁用用户扩展、消除扩展 content script 这一环境痕迹），但**不能**消除主世界 `chrome.runtime` 骨架，§1.2 的目标与 §4.1 的 11/11 预期在驱动主导航的正常会话流程中**不会实现**。骨架的真实触发链（驱动注入机制 ↔ Chromium 主世界骨架安装）需独立调查。

### 1.2 目标

提供一个配置开关，使 **Browser4 启动的**浏览器不继承用户扩展，从而消除主世界的 `chrome.runtime` 骨架，提升会话环境的纯净度。

### 1.3 非目标（明确排除）

- 不做反检测：本开关不隐藏任何自动化特征（`navigator.webdriver`、CDP 泄漏、UA 等一律不动）。
- 不承诺通过任何风控：检测服务通过 ≠ 商业风控通过。
- 不改变 `attach` 语义：`attach --cdp` / `attach --extension` 连接的是用户**已在运行**的浏览器，不受本开关影响。

---

## 二、设计决策

### 2.1 为什么选择"启动参数层"

| 候选层面 | 可行性 | 结论 |
|---|---|---|
| profile 复制阶段过滤（不复制 `Default/Extensions`） | 复制逻辑在外部依赖 `BrowserFiles`（`computeRandomTmpContextDir` / `computeNextSequentialContextDir`），**仓库内不可干预** | ✗ 不采用 |
| profile 生成后清理扩展目录 | 同上，且需侵入 profile 生命周期，易与"prototype 继承"语义冲突 | ✗ 不采用（列为可选二级增强） |
| **启动参数追加 `--disable-extensions`** | 接入点是仓库内**唯一启动漏斗** `DefaultBrowserFactory.launch()`，且已有 `Browser4UserAgent.applyTo` 先例；`ChromeOptions.addArgument(key, value)` API 已确认存在 | ✓ **采用** |

选它的三个理由：**最小侵入**（一个对象 + 一处接线）、**覆盖全部启动路径**（session / pooled / legacy display-mode 都经过该漏斗）、**与现有 stealth 修复同构**（照抄 `Browser4UserAgent` 的"解析→应用→可被显式配置覆盖"模式）。

### 2.2 配置键与语义

```kotlin
/**
 * 是否为 Browser4 启动的浏览器保留用户本机扩展。
 *
 * `true`（默认）：保持现状——profile 自 prototype 复制，用户扩展随 profile 进入会话浏览器。
 * `false`：启动时追加 `--disable-extensions`，不加载任何用户扩展。
 *
 * 关闭后 Chromium 不会运行扩展的 content script，页面主世界不再出现
 * `chrome.runtime` 骨架（`sendMessage.hasOwnProperty("prototype")` 形态）。
 */
const val EXTENSIONS_INHERIT = "browser.extensions.inherit"
const val EXTENSIONS_INHERIT_DEFAULT = true
```

命名与 `browser.focus.emulation`、`browser.console.capture` 同族（均定义于 `B4Constants`）。**默认 `true`** 是刻意的向后兼容选择：扩展带来的登录态对部分采集任务是**有用能力**，不应默认剥夺；只有显式声明"需要纯净环境"才关闭。

### 2.3 为什么不用无条件 `--disable-extensions`

无条件禁用会有两个副作用，故必须做成开关：

1. 用户的扩展登录态（如某些站点的第三方登录辅助）会失效；
2. 未来若 Browser4 需要加载自有扩展，会被一并误伤（当前仓库无 `load-extension` 调用，暂不冲突，但需保留开关余地）。

---

## 三、实现步骤

### 3.1 定义常量（`browser4-common`）

文件：`browser4-core/browser4-common/src/main/kotlin/ai/platon/browser4/common/B4Constants.kt`

在 `FOCUS_EMULATION_DEFAULT` 之后追加：

```kotlin
    /**
     * Whether the user's own Chrome extensions are inherited by a Browser4-launched browser
     * (`true`, the default) or disabled (`false`).
     *
     * Extensions arrive through the browser profile: a privacy context copies the prototype
     * data dir, so the user's extensions live in `Default/Extensions` of every copied
     * profile. A live extension runs its content script in the page, and Chromium then
     * installs the `chrome.runtime` skeleton in the page's main world — a shape bot
     * detectors sample (ipfighter reports `chrome.runtime.sendMessage` as a prototype leak;
     * a plain Chrome 155 with a clean profile and `--disable-extensions` never exposes it).
     *
     * `false` adds `--disable-extensions` at the single launch funnel and leaves every
     * automation-facing feature untouched (it is not a stealth switch).
     */
    const val EXTENSIONS_INHERIT = "browser.extensions.inherit"

    /** Default of [EXTENSIONS_INHERIT]; pinned by B4ConstantsTest. */
    const val EXTENSIONS_INHERIT_DEFAULT = true
```

### 3.2 新增策略对象（`browser4-browser`）

文件（新建）：`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4Extensions.kt`

照 `Browser4UserAgent` 的结构与文档风格：

```kotlin
package ai.platon.pulsar.chrome

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.getLogger

/**
 * Applies the environment-purity policy for browser extensions at launch time.
 *
 * A browser profile inherits the user's extensions from the prototype data dir. When a live
 * extension injects its content script, Chromium installs the `chrome.runtime` skeleton in
 * the page's main world, which bot detectors sample. Setting `browser.extensions.inherit=false`
 * suppresses every user extension by flipping [ChromeOptions.disableExtensions], which the
 * pulsar-browser 4.11.24 library maps to the native `--disable-extensions` Chrome flag.
 *
 * This is deliberately *not* a stealth mechanism: it removes a footprint that comes from the
 * user's own browser configuration, and changes nothing about automation signals.
 *
 * Applied from the single funnel every launch goes through (see `DefaultBrowserFactory`), so
 * the policy holds for session launches, pooled launches and the legacy display-mode path.
 */
object Browser4Extensions {
    private val logger = getLogger(Browser4Extensions::class)

    /**
     * @return `true` when this call disabled extensions for the launch
     */
    fun applyTo(launchOptions: ChromeOptions, settings: BrowserSettings): Boolean {
        val inherit = settings.config.getBoolean(
            B4Constants.EXTENSIONS_INHERIT, B4Constants.EXTENSIONS_INHERIT_DEFAULT
        )
        if (inherit) {
            return false
        }

        // NOTE: pulsar-browser 4.11.24 exposes disableExtensions as a native Boolean
        // property on ChromeOptions; setting it is equivalent to --disable-extensions on the
        // command line and is reflected by ChromeOptions.toList().
        launchOptions.disableExtensions = true
        logger.info(
            "Environment purity is on ({} = false): user extensions are disabled for this launch",
            B4Constants.EXTENSIONS_INHERIT
        )
        return true
    }
}
```

> 实现备注：`settings.config` 是读取 `B4Constants` 布尔开关的既有入口（参照 `Browser4WebDriver` 读取 `FOCUS_EMULATION` 的写法）；`userDataDirOrNull()` 仅为日志用途，若 API 无该属性则删去日志末项。

### 3.3 接线（`browser4-protocol`）

文件：`browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/impl/DefaultBrowserFactory.kt`

在既有 `Browser4UserAgent.applyTo(...)` 之后、`getLauncher(...).launch(...)` 之前插入一行：

```kotlin
        Browser4UserAgent.applyTo(launchOptions, launcherOptions.settings)
        Browser4Extensions.applyTo(launchOptions, launcherOptions.settings)   // ← 新增
        return getLauncher(browserId.browserType).launch(browserId, launcherOptions, launchOptions)
```

顺序说明：两者互不影响（一个设置 UA，一个追加命令行 flag），但放在同一漏斗内可保证"不变量对所有启动路径成立"这一既有设计意图不被破坏。

### 3.4 测试

**单元测试（必须）**——pin 常量与默认值：

文件：`browser4-core/browser4-common/src/test/kotlin/ai/platon/browser4/common/B4ConstantsTest.kt`
在现有 `FOCUS_EMULATION` 断言旁追加 `EXTENSIONS_INHERIT` 的 key 与默认值断言（风格照现有用例）。

**策略单元测试（建议）**：

文件（新建）：`browser4-core/browser4-browser/src/test/kotlin/ai/platon/pulsar/chrome/Browser4ExtensionsTest.kt`

- 用例 1：`inherit=true`（默认）→ `applyTo` 返回 `false`，且不追加任何 flag。
- 用例 2：`inherit=false` → 返回 `true`，且 `launchOptions` 含 `--disable-extensions`。
- 用例 3：未配置时走默认值 `true`（向后兼容回归保护）。

测试构造 `ChromeOptions` 的方式参照 `Browser4UserAgentTest`（`ChromeOptions().apply { headless = true }`）；断言"是否含某 flag"通过 `ChromeOptions.toList()`（pulsar-browser 4.11.24 已确认暴露该方法）检查 `--disable-extensions` 是否在输出列表中。

**实现备注**：2026-10-14 实际实现时发现 pulsar-browser 4.11.24 的 `ChromeOptions` 已内建 `disableExtensions: Boolean` 属性，该属性直接映射 `--disable-extensions` flag 并被 `toList()` 正确反映。因此最终实现使用原生属性赋值（`launchOptions.disableExtensions = true`），而非设计初拟的 `addArgument`。这比 `addArgument` 更简洁且与 `toList()` 输出一致。

### 3.5 用户使用方式

```bash
# 默认（继承用户扩展，向后兼容）
browser4-cli -s job-42 open --headless "https://example.com"

# 关闭扩展继承（需要纯净环境的场景）
browser4-cli config set browser.extensions.inherit false
browser4-cli -s job-42 open --headless "https://example.com"

# 或在配置文件中设置：
# browser.extensions.inherit = false
```

> 待确认：`config set` 是否有配置白名单校验；若无，新键需一并登记到 CLI/后端的配置清单。CLI 侧如需新增专用 flag（如 `open --no-extensions`），须改主仓库 Rust 文件（`cli/` 无插件扩展点）。

### 3.6 可选二级增强（本期不实现）

在 profile 生成后、启动前清理 `Default/Extensions`、`Default/Local Extension Settings`、`Default/Extension State`，并从 `Secure Preferences` 移除 extensions 段。仅在"启动参数不足以覆盖的场景"（例如扩展仍是 profile 指纹的一部分）才需要。因涉及外部 `BrowserFiles` 的复制时机，列为后续独立评估项。

---

## 四、验证方案

### 4.1 端到端对照（决定性验证）

前置：`browser4-cli config set browser.extensions.inherit false`，新命名会话。
> 实测备注（2026-10-14）：`config set` 走 CLI 白名单（`server/timeout/proxy/session/extension_id` + 2 个 server keys），新键未登记会报 `Unknown config key`。本次验证通过用户配置文件 `~/.browser4/config/conf-enabled/application-private.properties` 写入 `browser.extensions.inherit=false`（backend 经 Spring Environment 读取），强制重建 runtime bundle（`BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1`）后生效。

1. 打开 `https://ipfighter.com/bot-detection`，等待判定渲染。
2. 读取 API Integrity 分组：预期从 **10/11** 收敛为 **11/11**（`chrome.runtime.sendMessage` 由 `Prototype leak` 变为通过）。
3. 引擎侧探针确认：`typeof window.chrome.runtime === "undefined"`（对照当前 `object`）。
4. 回归对照：确认 `navigator.webdriver`、WebGL、`hasFocus` 等其余项与开启前一致（本开关不应改变任何自动化特征）。

**实测结果（2026-10-14，会话 `envpurity`，headless Chrome 155，bundle 重建自含本开关的源码）：**

| 验证项 | 预期 | 实测 | 结论 |
|---|---|---|---|
| Chrome 命令行含 `--disable-extensions` | 是 | **是**（已核实进程 cmdline） | ✅ 开关机制生效 |
| 扩展 content script 注入 | 无 | **无**（CDP `Runtime.enable` 全程未见扩展 isolated world；profile 内 WPS kdocs 未运行） | ✅ |
| 引擎探针 `typeof chrome.runtime`（CLI eval，isolated world） | `undefined` | `undefined` | ✅（但该 world 本就看不到骨架，不构成证据） |
| 引擎探针（主世界，CDP 直连） | `undefined` | **驱动导航的页面 = `object`（骨架仍在）**；原始 CDP 导航的页面 = `undefined` | ❌ §1.2 预期未达成 |
| ipfighter `sendMessage` 判定（驱动导航页面） | `N/A`（pass） | **`Prototype leak`**（10/11 维持） | ❌ §4.1 预期未达成 |
| ipfighter `sendMessage` 判定（绕过驱动的页面） | — | **`N/A`（pass）**，51 项检查全部渲染 | 证实"无骨架 → pass"因果链 |
| 回归：`navigator.webdriver` / `hasFocus` / `maxTouchPoints` / `plugins` / `window.chrome` keys | 与基线一致 | `false` / `true` / `10` / `5` / `loadTimes,csi`——全部与 retest 基线一致 | ✅ 无回归 |

**结论**：开关本身（禁用用户扩展）按设计生效且无回归；但主世界骨架的真实来源是**驱动自身的注入机制**（详见 §1.1 归因修正），与扩展无关，故本开关**不能**消除 `Prototype leak`。消除骨架需另立课题调查驱动注入链（`__browser4_runtime__` isolated world 的创建路径），本设计文档的目标声明与预期效果视为**未达成**。

### 4.2 回归面

- `inherit=true` 时会话浏览器仍带用户扩展，行为与当前版本完全一致（默认值不变）。
- `attach --extension` / `attach --cdp` 连接用户已有浏览器时不受影响（不经过 `DefaultBrowserFactory`）。
- 确认关闭后 Browser4 自身功能无缺失（当前无自有扩展加载路径）。

---

## 五、风险与边界

| 项 | 说明 |
|---|---|
| 语义边界 | 本开关只移除"用户扩展导致的 `chrome.runtime` 骨架"这一项环境痕迹；**不改变** IP 层、TLS 指纹、行为层等任何信号，也不构成通过任何风控的承诺 |
| 能力损失 | 关闭后退出的扩展登录态不再可用——这是开关的**预期代价**，故默认 `true` |
| 与 `attach` 的关系 | `attach --extension` 连接用户**已运行**浏览器，不经过启动漏斗，因而天然不受影响（这是设计上要保住的性质） |
| API 依赖 | 依赖外部库 `ChromeOptions.addArgument` 的存在性与签名；实现第一步需确认（见 §3.4 手工确认项） |
| 外部依赖限制 | 更彻底的 profile 层过滤需改动外部 `BrowserFiles`，不在本仓库范围内 |
| 合规 | 对外表述必须遵守项目红线：不得宣称反检测/防关联/过风控；本开关定位为"环境纯净度"，供自有站点测试、已授权 QA 等合法场景使用 |

---

## 六、备选方案与不采纳理由

| 备选 | 不采纳原因 |
|---|---|
| 让 prototype 不存在（等价"无痕"） | prototype 存在与否由用户本机环境决定，不能作为可控开关；且会同时丢失用户希望保留的 profile 状态 |
| `browser.profile.mode=TEMPORARY` | 该模式是否复制 prototype 取决于外部实现，语义不精确，且影响面大于"仅禁用扩展" |
| 无条件 `--disable-extensions` | 剥夺所有用户的扩展登录态，破坏向后兼容（见 §2.3） |
| 在 profile 复制阶段过滤 | 复制逻辑在外部依赖，本仓库不可干预 |

---

## 七、附录：相关代码位置

| 位置 | 作用 |
|---|---|
| [B4Constants.kt](../../browser4-core/browser4-common/src/main/kotlin/ai/platon/browser4/common/B4Constants.kt) | 配置常量定义处（新增 `EXTENSIONS_INHERIT`） |
| [B4ConstantsTest.kt](../../browser4-core/browser4-common/src/test/kotlin/ai/platon/browser4/common/B4ConstantsTest.kt) | 常量与默认值 pin 测试 |
| [DefaultBrowserFactory.kt](../../browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/impl/DefaultBrowserFactory.kt) | 所有启动的唯一漏斗（接线点） |
| [Browser4UserAgent.kt](../../browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4UserAgent.kt) | 同构先例：启动前修改 `launchOptions` |
| [Browser4WebDriver.kt](../../browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt) | 消费 `B4Constants` 开关的既有范例（`FOCUS_EMULATION`） |
| [BrowserProfileGenerator.kt](../../browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/browser/privacy/BrowserProfileGenerator.kt) | profile 生成；KDoc 说明 prototype **整目录复制**（扩展来源） |
| [PulsarSessionManager.kt](../../browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt) | `attach --extension` 路径（说明为何不受影响） |
| [cloakbrowser-support-eval.md](cloakbrowser-support-eval.md) | 确认 `ChromeOptions.addArgument` API 存在 |

---

## 八、待办清单（按实现顺序）

1. ~~确认 `ChromeOptions.addArgument` 签名与参数字段可读性（决定 §3.4 断言强度）。~~ ✅ 2026-10-14 通过 javap 确认：pulsar-browser 4.11.24 的 `ChromeOptions` 已内建 `disableExtensions: Boolean` 属性，直接用原生属性赋值而非 `addArgument`。
2. ✅ 2026-10-14 新增 `EXTENSIONS_INHERIT` 常量 + `B4ConstantsTest` pin（6/6 测试过）。
3. ✅ 2026-10-14 新建 `Browser4Extensions.kt` 策略对象。
4. ✅ 2026-10-14 接线 `DefaultBrowserFactory.launch()`（编译通过）。
5. ✅ 2026-10-14 新增 `Browser4ExtensionsTest`（3/3 测试过）。
6. 端到端验证（§4.1）：ipfighter API Integrity 10/11 → 11/11，其余项无回归。✅ 2026-10-14 **已执行**——开关机制生效（`--disable-extensions` 落地、扩展未注入、各项无回归），但 **11/11 预期未实现**：主世界骨架与扩展无关，由驱动注入机制触发（见 §1.1 归因修正、§4.1 实测表）。
7. 确认 `config set` 是否需登记新键；如需 CLI 专用 flag，评估改 Rust 侧。✅ 2026-10-14 **已确认**：`config set` 走 CLI 白名单（[config.rs](../../cli/browser4-cli/src/config.rs) `VALID_CONFIG_KEYS`），`browser.extensions.inherit` 未登记、报 `Unknown config key`。若要让用户经 CLI 设置此键，需把它加入白名单（如走 `SERVER_CONFIG_KEYS` 路由到 `/api/config`，还需同步后端 `RuntimeConfigRegistry`）。本次 E2E 用配置文件路径绕过。
8. 文档与合规复核（对外表述不得越红线）。✅ 2026-10-14 **已复核**：本开关对外定位保持"环境纯净度/消除扩展遗留痕迹"；**不得**以"消除 Prototype leak / 通过 ipfighter"作为宣传点（E2E 已证其不成立），亦不得宣称反检测/防关联/过风控。
