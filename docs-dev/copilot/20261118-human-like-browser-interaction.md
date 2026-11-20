# Browser4 浏览器操作的拟人（human-like）机制

- **日期**：2026-11-18
- **范围**：CLI/MCP → `Browser4WebDriver` → `pulsar-browser` 基库 → CDP 这条链上，所有"让浏览器操作看起来像人"的机制：时间节奏、指针轨迹、事件可信度、键盘输入、拖拽、滚动/阅读、环境指纹，以及保证它们不被改坏的工程门禁。
- **方法**：源码阅读（本仓库 + 基库 `pulsar-browser:4.11.32` 的 `-sources.jar`）+ 单测/e2e 用例核对。所有行号取自本次阅读时的文件快照。
- **口径**：**本仓库**指 `browser4-core/browser4-browser/.../Browser4WebDriver.kt` 等文件；**基库**指外部依赖 `ai.platon:pulsar-browser:4.11.32`（版本 pin 见 `browser4-dependencies/pom.xml:612`），其源码不在本仓库，行号来自 sources jar，升级后可能漂移，引用时以符号名为准。
- **状态**：现行实现（非提案）。文中标注的"已有测试"指仓库内实际存在的单测/e2e。

---

## 0. 结论速览

### 0.1 拟人不是一个开关，而是四层叠加 + 一层治理

```
┌ 时间层：延迟桶 InteractSettings.DEFAULT_DELAY_POLICY   ← --interact-level
├ 轨迹层：分段移动 + 贝塞尔弯曲 + smoothstep 缓动 + ±2px 落点抖动
├ 事件层：trusted CDP 输入（isTrusted=true）+ 一次性探针验证 + DOM 降级
├ 环境层：UA 削减 / AutomationControlled / 焦点仿真 / 条件 stealth.js / WebRTC
└ 治理层：AGENTS.md 评审门禁 + CdpTrapCheck + 单测 + e2e 夹具
```

四层各自解决不同的检测面：

| 层 | 检测面 | 不做的后果 |
|---|---|---|
| 时间 | 事件时间戳分布（等间隔 = 机器） | 固定延迟、0ms 连发成为指纹 |
| 轨迹 | `mousemove` 序列（瞬移、直线、匀速） | 一次跳跃几百像素，鼠标追踪脚本直接打分 |
| 事件 | `event.isTrusted`、`contextmenu`/`auxclick` 是否存在 | 合成事件被一行 `isTrusted` 判断识破 |
| 环境 | `navigator.*`、`document.hasFocus()`、client hints | `HeadlessChrome`、`webdriver:true`、`hidden` 是硬判定 |
| 治理 | —— | 后来者重构时把上述行为改回默认 |

### 0.2 关键数字（一处查全）

| 项 | 值 | 位置 |
|---|---|---|
| 指针步数 | `round(distance/20)`，夹取 **3..30** | `Browser4WebDriver.pointerMoveSteps`（`Browser4WebDriver.kt:362`） |
| 每步间隔 | **8–16 ms**（`Random.nextLong(8, 17)`） | `Browser4WebDriver.glidePointerTo`（`:1056`） |
| 弯曲触发距离 | **≥40 px**（更短保持直线） | 基库 `Mouse.MIN_CURVE_DISTANCE` |
| 弯曲幅度 | `min(15% × 距离, 60px)` 的垂直偏移 | 基库 `Mouse.MAX_CURVE_OFFSET_RATIO/MAX_CURVE_OFFSET_PX` |
| 路径节奏 | smoothstep `t²(3−2t)` + 每步 **±35%** 抖动 | 基库 `Mouse.easeInOut/jitteredDelayMillis` |
| 落点抖动 | **±2 px**，再按元素盒夹取（内缩 1 px） | `Browser4WebDriver.POINTER_JITTER_PX`（`:450`） |
| 点击按住时长 | `click` 桶 **90–180 ms** | `clickTrusted`（`:1404`） |
| 逐字符键入 | `type` 桶 **90–240 ms**，补充平面字符 **×2** | `typeSafe`（`:1678-1681`） |
| 按键按住 | `press` 桶 **120–260 ms**，且 `Keyboard` 强制 ≥60 ms | `press`（`:1610`）、基库 `Keyboard.press` |
| 拖拽点抖动 | **±2 px**；`top/bottom` 模式**不抖动** | `dragAt`（`:3220-3221`） |
| 拖拽间隔 | **120–300 ms**（`Random.nextLong(120, 301)`） | `randomDragDelayMillis`（`:3390`） |
| 中途呼吸 | `gap` 桶 **650–1100 ms** | 基库 `AbstractWebDriver.gap()` |

### 0.3 一句话原则

**拟人的目的是"让页面看到的行为和人一致"，但优先级永远低于"不要撒谎"**：宁可降级到合成 DOM 事件（可检测但确实落在目标上），也不在未验证的坐标上按下；宁可报错，也不静默 no-op。这条原则在 §8 展开。

---

## 1. 时间层：按动作类型分桶的随机延迟

### 1.1 延迟桶表

基库 `ai.platon.pulsar.api.InteractSettings.DEFAULT_DELAY_POLICY`：

| 桶 | 默认 (ms) | 语义 |
|---|---|---|
| `gap` | 650–1100 | 高层动作之间、重试循环之间的呼吸 |
| `click` | 90–180 | mousePressed→mouseReleased 的按住时长；多击轮间隔 |
| `press` | 120–260 | 单次按键按住时长（传给 `Keyboard.press`，其内部再夹 ≥60） |
| `type` | 90–240 | 逐字符 `Input.insertText` 间隔 |
| `fill` | 130–280 | 填完之后 `gap("fill")` 的停顿 |
| `delete` | 80–180 | 清空文本时逐次退格 / `Control+A`+`Delete` |
| `keyUpDown` | 70–170 | 保留桶，当前无直接引用 |
| `mouseWheel` | 180–420 | 连续滚轮 tick 之间 |
| `dragAndDrop` | 260–650 | 拖拽序列内（基库 `Mouse.dragAndDrop`） |
| `waitForNavigation` | 250–700 | 等 URL 变化的轮询间隔 |
| `waitForSelector` | 200–600 | 等元素出现的轮询间隔 |
| `waitUntil` | 220–650 | 通用轮询（默认重载实际用 `"waitUtil"` 拼写并回落到 default） |
| `default` / `""` | 200–600 | 未知动作的主/次回落 |

### 1.2 解析顺序与钳制

`AbstractWebDriver.randomDelayMillis(action, fallback = 500..1000)`（基库 `AbstractWebDriver.kt:550`）：

1. `delayPolicy[action]` → 2. `delayPolicy["default"]` → 3. 调用方 `fallback`
4. 兜底校验：`range.first <= 0 || range.last > 10000` 视为非法，直接用 `fallback`

`delayPolicy` 是 `browser.settings.interactSettings.generateRestrictedDelayPolicy()`（`AbstractWebDriver.kt:228`），会先把每个桶钳制进 `minDelayMillis..maxDelayMillis`（默认 **50..2000**，`InteractSettings.kt:71/76`）。因此：**任何桶的上界超过 2000 ms 都会被削到 2000**；FASTEST 预设会把 `minDelayMillis` 降到 10，否则它那些 10 ms 的桶会被钳回 50。

### 1.3 预设与 `--interact-level` 映射

`DelayPreset` 四档（`InteractSettings.applyDelayPreset`）：

| 预设 | 代表值 | 用途 |
|---|---|---|
| `FASTEST` | 全部 **10..10** | 只给测试；`FASTEST_DELAY_POLICY` |
| `FAST` | click 60–120、type 60–160、gap 350–700 | 追求速度的采集 |
| `DEFAULT` | 见 1.1 | 默认 |
| `STEALTH` | gap 900–1600、click 120–260、type 140–320、dragAndDrop 420–1100 | "更像人"，更慢 |

`InteractLevel`（`ai.platon.pulsar.common.browser.InteractLevel`）七档 → 预设的映射（`InteractSettings.create`）：

```
FASTEST                          → DelayPreset.FASTEST
FASTER, FAST                     → DelayPreset.FAST
DEFAULT                          → DelayPreset.DEFAULT
GOOD_DATA, BETTER_DATA, BEST_DATA → DelayPreset.STEALTH
```

同档还一起换**交互画像**（滚动位置、滚动次数、滚动间隔、`bringToFront`、超时），见 §6。

### 1.4 端到端链路

```
browser4-cli open --interact-level FAST
  → params["interactLevel"] = "FAST"                     (commands.rs:819)
  → caps["interactLevel"]                                (main.rs:1360)
  → PulsarSettings.parseInteractLevel(capabilities)       (browser4-skeleton/PulsarSettings.kt:177)
  → InteractSettings.create(level)                       (:143；内部已 applyDelayPreset)
  → browser.settings.interactSettings
  → driver.delayPolicy → randomDelayMillis(action)
```

CLI 选项定义：`commands.rs:779`（`open`）。另一条入口是任务参数 `-ilv` / `-interactLevel` / `--interact-level`，经 `LoadOptions` 的 `InteractLevelConverter` 解析（`browser4-skeleton/.../LoadOptions.kt:634`、`JCommandConverters.kt:107`），供 crawl/swarm 这类带 `LoadOptions` 的任务使用。

### 1.5 为什么节奏也属于可靠性

`Input.insertText` 之间 0 ms（或早期试过的固定 10 ms）会在 Docker headless 里**丢 `input` 事件**——字符到达了页面却没触发 `input`，`syncState()` 不被调用。基库 `PulsarWebDriver.fill` 的注释完整记录了这段排查（`PulsarWebDriver.kt:1492-1515`），最终结论是"复用 `type` 桶"，即**拟人节奏同时是稳定性的正确取值**。这条也写进了 `AGENTS.md` 的已知 CDP 坑与 `CdpTrapCheck` 的建议。

---

## 2. 轨迹层：指针不许瞬移

基库 `PulsarWebDriver.mouseMove` 默认 `steps = 1`：一次跳跃几百像素，页面只收到一个 `mousemove`，这是鼠标轨迹检测直接打分的特征。仓库覆盖了整条移动路径。

### 2.1 步数与节拍（仓库侧）

`Browser4WebDriver.mouseMove`（`:1035`）→ `glidePointerTo`（`:1056`）：

```kotlin
val distance = hypot(x - m.currentX, y - m.currentY)
m.moveTo(x, y, steps = pointerMoveSteps(distance), delayMillis = Random.nextLong(8, 17))
```

- 步数 `pointerMoveSteps = (distance / 20).roundToInt().coerceIn(3, 30)`：约每 20 px 一步，短跳至少 3 步（否则短距离等于没分段），跨屏最多 30 步
- 每步 8–16 ms 随机 → 跨屏移动约 100–500 ms，页面看到的是一条真实的分段轨迹
- 驱动 inactive 时**抛 `IllegalWebDriverStateException`**，而不是像上游那样静默跳过（静默 success 无法被调用方重试）

单测：`Browser4WebDriverTest.pointerMoveStepsScalesWithDistance`（140→7、400→20）与 `pointerMoveStepsClampsTheRange`（0→3、39→3、600→30、100000→30），`Browser4WebDriverTest.kt:711/718`。

### 2.2 路径与速度曲线（基库 `Mouse.moveTo`）

`ai.platon.pulsar.chrome.protocol.EmulationHandler.kt`：

- **路径**：二次贝塞尔，控制点在中点沿**垂直方向**随机偏移。偏移上限 `min(distance × 0.15, 60 px)`；**距离 <40 px 不弯**（人的短距离修正本来就是直的，几个像素还要甩一下反而假）
- **速度**：smoothstep `easeInOut(t) = t²(3−2t)`，两端速度为 0、中段最快，近似 Fitts 定律的指向运动
- **抖动**：每步延迟再 `× Random.nextDouble(0.65, 1.35)`，且 ≥1 ms——时间戳不是节拍器
- **精度**：最后一个采样点恒等于目标坐标，所以点击/拖拽落点不受曲线影响

### 2.3 落点抖动

`jitteredPointerPosition`（`Browser4WebDriverParsers.kt:226`）→ 点击前把指针放到元素中心 **±2 px** 内：

- 上限按元素盒收缩：`min(jitter, min(width, height)/2 − 1)`，保证仍落在元素内部（`:hover` 状态是这次移动存在的理由）
- 元素盒未知（0）或太小时**保留精确中心**——宁可坐标可重复，也不能把指针甩出元素
- 只用于 click 家族；**显式坐标的 `mousemove` 永不抖动**（该命令的语义就是"把指针放在这里"）
- 抖动是随机的、按调用变化：`jitteredPointerPositionVariesBetweenCalls`（20 次调用结果不恒定，且每次偏移都在 ±2 px 内）

**关键配套**：trusted 点击必须在**hover 点**按下（`trustedClickPoint`，`:1333`），不能在元素中心按。因为 Chrome 会先把指针移到按下坐标，若按中心就会反向发一个 `mousemove` 回到正中心，抖动被抹掉、每次点同一个像素。按下前还会在**该点**重做一次命中测试（`hitAt`，`:1346`），所以"抖动"与"不许点到覆盖层"并不冲突。

### 2.4 显式 pacing 逃生门

`mousemove --steps N --duration MS` 走 `mouse_move_at` → 基库 `mouseMoveAt`，**显式接管**：

```
stepCount    = steps > 0 ? steps : max(1, min(30, distance/20))
perStepDelay = durationMillis > 0 ? max(1, durationMillis / stepCount) : 50
```

调用方要"猛甩一下"（3 大步）或"慢慢滑"（长 duration）时，驱动不再套用默认拟人路径。CLI 选项：`commands.rs:1687-1688`，文案见 `help.rs:1885`。`--space document` 还会让引擎在**派发前**读 `scrollY` 换算坐标，避免调用方用过期滚动位置自己减。

### 2.5 滚轮

仓库覆盖了两个 `mouseWheel` 重载（`:945`、`:972`）：

1. 有元素目标时，先 `glidePointerTo` 再发 wheel——不允许"瞬移后立刻一串 wheel"，那是和点击一样的机器人轨迹
2. 超时按滚动距离缩放：`max(5s, 5s + 1s/1000px)`，上限 30 s
3. CDP wheel 撞上 `crbug.com/444929150` 竞态时，降级方案**先派发可取消的合成 `WheelEvent`，只有没人 `preventDefault()` 才滚动**（`wheelFallbackJs`，`Browser4WebDriverScripts.kt:216`）：
   - 真滚轮 = 先 fire 可取消 `wheel` 事件，再由默认动作滚动
   - 上游降级是 `scrollBy()` 静默滚动：页面看到"内容动了但一个 wheel 事件都没有"，这个组合没有真人能产生

---

## 3. 事件层：优先 trusted 输入，且必须被证明送达

### 3.1 为什么必须 trusted

上游在 Windows 上用 `emulator.click(..., dispatchCdpMouseEvents = false)` + `dispatchDomClick`，理由是"CDP press/release 在 Windows headless 不触发 DOM click"。实测（Chrome 153 / Windows headless）`Input.dispatchMouseEvent` 的 `mousePressed+mouseReleased` 能产生 `mousedown`/`mouseup`/`click` 且 `isTrusted: true`，于是这个 workaround 已过时：**合成事件的 `isTrusted === false`，一个蜜罐按钮或反爬信标就能识破**。

### 3.2 点击序列（`clickTrusted`，`:1376`）

```
Input.dispatchMouseEvent(mousePressed)   ← 带 x/y、button、buttons 位域、clickCount
delay(randomDelayMillis("click"))        ← 按住 90–180 ms
Input.dispatchMouseEvent(mouseReleased)  ← buttons = 0
（count>1 时再 delay 一个 click 桶，然后下一轮）
```

- `buttons` 位域：左 1、右 2、中 4（`press` 时置位，`release` 时清零）
- 多击用递增 `clickCount`（1..count），轮间有真人间隔，不是 0 ms 爆发

### 3.3 一次性探针验证

`Input.dispatchMouseEvent` 不返回投递结果，所以**每驱动第一次**点击后要做验证：

| 步骤 | 实现 |
|---|---|
| 安装 | `trustedClickProbeInstallJs`（`Browser4WebDriverScripts.kt:328`）：用 `Object.defineProperty` 定义**不可枚举**的 `window.__b4_trusted_click_probe__`，以捕获阶段监听 `mousedown/mouseup/click`，只累加 `event.isTrusted` |
| 读取 | `trustedClickProbeReadJs`（`:347`）：摘掉监听、`delete` 全局，返回 `"trusted"` / `"none"` / `"missing"` |

三种结果的处理（`Browser4WebDriver.kt:1435-1457`）：

- `trusted` → 缓存"本 session 支持"，后续不再验证
- `none` → 缓存"不支持"，**本 session 其余点击走 DOM 路径**（绝不重复派发第二次）
- `missing`（页面把探针删了/覆盖了）→ 视为已送达并记 warning，下次再探——"第二次点击"是更糟的失败模式
- 探针**装不上**（`evaluate` 失败）→ 直接返回 false，走 DOM 路径，不冒险

单测：`trustedClickProbeIsHiddenAndRemoved`（`Browser4WebDriverTest.kt:798`）。

### 3.4 按住与半途失败

- 已发出 ≥1 个事件后失败 → **抛 `WebDriverException`**，说明"页面可能处于半按状态"并附原始错误；不走重试（重试会双发）
- 一个都没发出 → 记 warn、返回 false，调用方降级到 DOM 点击

### 3.5 非左键（右键/中键/back/forward）

`click(selector, count, button)`（`:855`）对非左键同样**先移动指针到抖动点再 trusted 派发**：

- 只有 trusted 路径能产生完整真实序列：右键 `mousedown → contextmenu → mouseup`，中键 `auxclick`
- 帧内元素或命中测试不通过 → `clickNonLeftOnDomPath`（`:899`）退回上游合成 `mousedown`/`mouseup`（`isTrusted:false`、且 Chrome 不会派生 `contextmenu`）——"点得中但可检测"优于"按在覆盖层上"
- 前后都做 dialog 处理（`dismissAllPending` / `drainAutoDismiss`）

### 3.6 降级矩阵（什么时候放弃拟人）

| 情况 | 走 trusted | 走 DOM | 原因 |
|---|---|---|---|
| 普通可命中元素 | ✅ | —— | 首选 |
| 元素在 iframe 内 | ❌ | ✅ | `getBoundingClientRect` 是帧相对坐标，`Input.*` 是主帧视口坐标，直接换算必错 |
| 命中点是覆盖层 / `pointer-events:none` / 布局漂移 | ❌ | ✅ | trusted 会点到"最上面的那个元素"，DOM 派发始终落在目标上 |
| 本 session 已被探针证明"trusted 不投递" | ❌ | ✅ | 避免空点 |
| 带修饰键（Ctrl/Shift/…） | ✅（上游本就 CDP） | —— | 修饰键位掩码无 DOM 等价物 |
| 选择器根本解析不到 | —— | —— | 先 probe，**直接报错**，不打印成功 |

表格对应实现：`canClickWithTrustedInput`（`Browser4WebDriverParsers.kt:263`）、`failIfPointerTargetMissing`（`:1194`）、`dblclick`（`:1490`）、`click(selector, modifier)`（`:1308`）。

### 3.7 `hover` / `focus` / `check` / `uncheck` 的存在性探针

上游这些方法"解析不到就静默返回"，CLI 会打印 `✓ Hovered ...`。仓库统一加 `failIfPointerTargetMissing`：

- 探针就是读命令用的同一条定位路径（`evaluateValue(selector, "function(){ return this != null; }")`），所以 `get`/`fill` 能解析的选择器这里也能
- **探针本身失败**（传输/session/执行上下文错误）只记 debug 并继续——这是"无法判断"，不能破坏一个本来可用的操作
- 只有"探针跑通且明确说元素不存在"才抛错

### 3.8 对话框不让 CLI 挂死

`withDialogWatch`（`:1234`）：点击放到 `clickWatchScope` 后台执行，前台每 150 ms `peekPendingDialog()`：

- 开了自动关闭 → 立刻 `acceptDialog()`，被阻塞的点击继续完成，整体成功
- 没开 → 抛出可操作错误（"点击打开了原生 confirm 对话框并挂起，跑 `dialog-accept` 让它继续，不要重跑点击"），**但不取消**那个被 park 的点击——用户处理完对话框后，那一次点击照样落地

这不是拟人，但同属"交互真实性"：真人点击被 `alert()` 拦住时，页面就是停在那个状态。

### 3.9 已知边界：`mousedown` / `mouseup` 命令是合成的

CLI 的 `mousedown`/`mouseup` 走基库 `PulsarWebDriver.mouseDown/mouseUp`（`PulsarWebDriver.kt:1026`），其 KDoc 明确：事件在页面内合成，**`isTrusted` 为 false，且不执行默认动作**（不聚焦、不开始选择/拖拽、不设 `:active`）。**使用建议**：需要浏览器默认动作（聚焦、选择、`:active`）时用 `click`，不要用 `mousedown`+`mouseup` 自己拼——那拼出来的一定是合成事件。

---

## 4. 键盘层：真物理键 + 逐字符节奏 + 光标位置

### 4.1 物理键事件（基库 `Keyboard`）

`EmulationHandler.kt` 的 `Keyboard`：

- 每个可打印字符：`keydown`（**不带 text** → 实际发 `rawKeyDown`）→ `Input.insertText` 插入 → `keyup`
- 为什么 keydown 不带 text：`Input.dispatchKeyEvent(KEY_DOWN)` 带非空 `text` 会自行插入字符，再叠加 `insertText` 就**插入两次**（`press("a")` 得到 "aa"，Chrome 156 实测）。所以插入只留 `insertText` 一个入口
- 键事件携带完整字段：`code` / `windowsVirtualKeyCode` / `location` / `isKeypad` / `autoRepeat`（按住重复检测）/ `modifiers` 位掩码
- **修饰键有状态**：`pressedModifiers` 集合跟踪按住状态，`keyDown("Control")` 后 `press("a")` 的事件真的带 `ctrlKey:true`；组合键 `Control+Shift+Tab` 按序按下、逆序释放
- **按住时长 ≥60 ms**（`press(key, delayMillis)` 内部 `coerceAtLeast(60)`），且按键按下与抬起之间不插别的动作
- Shift 字符通过布局的 `shifted` 条目解析：按住 Shift 时 `type("a")` 得到 `A` 且 `key` 报 `A`

仓库侧 `Browser4WebDriver` 补的两点（`:1574-1591`）：`keyDown`/`keyUp`/`press` 共用一个 `Keyboard` 实例——上游 `keyDown` 走无状态 JS 事件、而 `press` 读的是**另一个私有** `Keyboard` 的修饰键状态，导致按住 Control 后再 `press("a")` 不带 `ctrlKey`。

### 4.2 逐字符节奏与特殊字符

- `type`/`fill`：每字符 `delay(randomDelayMillis("type"))` = 90–240 ms
- 仓库 `typeSafe`（`:1659`）：按 **code point** 遍历（上游 `charAt()` 会把代理对切成半个字符，`Input.insertText` 直接失败）；**补充平面字符延迟 ×2**（emoji/CJK 扩展区），给渲染与输入法链更多时间
- 工具层对非 `Browser4WebDriver` 的驱动有一份等价内联实现（`BrowserTabToolExecutor.kt:1779-1791`），保证降级路径的节奏一致

### 4.3 光标位置

`DOM.focus()` + `Input.dispatchMouseEvent(click)` 可能把光标留在位置 0（`AGENTS.md` 已知坑）。仓库的处理是**按操作语义区分**：

- `press(key, selector)`：仅当 `key` 是**单个可打印字符**时才 `setSelectionRange(99999, 99999)` 把光标推到末尾；`Home/End/ArrowLeft/Delete` 等导航键**保持光标位置**，所以 `Home → Delete` 这类链式操作行为正确（`:1617-1640`）
- `type`/`fill`（基库）：先 click 右侧 + `setSelectionRange(99999, 99999)`，保证追加而非前插

### 4.4 `fill` 是"逐字符键入"，不是 `value=` 赋值

基库 `fill`（`PulsarWebDriver.kt:1464`）：focus → `clear()`（`Control+A`+`Delete`，或按当前长度逐次退格，每次用 `delete` 桶）→ click 右侧 → 光标到末尾 → 逐字符 `type` → `gap("fill")`。

- 这条路径会产生真实的 `keydown/input/keyup` 链，页面的事件监听、自动补全会响应
- `setProperty` 类工具走 `value=` 直写，**不产生键盘事件**——`TabToolExamples.kt:309` 明确写"Prefer `fill` for human-like typing"
- `fill` 仍有"键入没落地"的兜底：只有当目标**本可容纳文本且仍为空**时才回退 `setValueViaJs`，避免对 masked/readonly/contenteditable 误判

### 4.5 `type --method exec` 是刻意的非拟人快路

`typeAuto`（`:1721`）：

| method | 行为 | 何时用 |
|---|---|---|
| `chars` | 逐 code point `insertText` + `type` 桶节奏 | 默认；需要键盘事件链 |
| `exec` | 一次 `document.execCommand('insertText')` 灌入全文 | 长文本 / 多行、编辑器接受 `beforeinput` 事务 |
| `auto` | 长（>150 字符）或多行且目标可容纳 → `exec`，否则 `chars` | 默认策略 |

- `exec` 的 KDoc 写明"**no keyboard event chain**"：快捷键、自动补全这类依赖 key 事件的站点可能不响应
- 阈值 `TYPE_EXEC_LONG_THRESHOLD = 150`，理由写在常量注释里：150 字符按 90–240 ms/字符 ≈ 13–36 s
- 驱动的 `verify` 打开时会回读并比对（NBSP→空格、CRLF→LF、去尾空白），不匹配直接抛错——**键入从不静默失败**。注意 CLI 的 `type --verify` 只是**stderr 上的建议性报告**（选项描述原文如此），严格读回比对属于工具调用参数（`typeAuto(verify = true)`）

---

## 5. 拖拽层

`dragAt`（`Browser4WebDriver.kt:3171`）在页面内合成整条 `dragstart → dragenter → dragover → drop → dragend` 序列，并做人化：

| 项 | 中心模式 | `top`/`bottom` 模式 |
|---|---|---|
| 起点/落点抖动 | ±2 px | **不抖动** |
| 事件间隔数 | 4 段 | 6 段（含 2 个中间 `dragover` 扫掠） |
| 间隔取值 | `Random.nextLong(120, 301)` ms | 同 |
| 落点来源 | 解析时的 rect | **派发瞬间从页面内实时 rect 读** |

设计取舍：

- 为什么 `top/bottom` 不抖动：±2 px 在边界线上就是"插前还是插后"掷硬币，而且抖动可能把落点甩出边缘区——定位插入要求确定性，这里**放弃拟人换正确性**
- 中间 `dragover` 扫掠（t≈0.34 / 0.67，见 `buildDragSequenceScript`，`Browser4WebDriverScripts.kt:464`）让跟踪 dragenter/dragover 转换的库看到"接近过程"，而不是一步跳到落点
- **帧内元素直接报错**（不静默拖错）：`getBoundingClientRect` 与 `Input.*` 坐标系不同
- **前置校验暴露真实原因**：目标被覆盖、元素在拖拽过程中被移动 → 页面脚本返回 `{ok:false, error}`，原样抛给用户（`dragScriptErrorMessage`），不被重试包装吞掉
- 重试安全：只有**瞬时 CDP 错误**才手动重试，且先用 DOM 位置指纹判断"上一次其实已经拖成了"，避免重复拖
- **已知上限**（KDoc 写明）：合成事件 `isTrusted=false`，SortableJS / react-dnd 这类门控 `isTrusted` 的库不会响应。这是浏览器级限制，driver 无法修复

---

## 6. 滚动与"阅读"

### 6.1 交互画像（`InteractSettings`）

`--interact-level` 除了换延迟桶，还换**页面级阅读行为**：

| 字段 | DEFAULT | GOOD_DATA / BETTER_DATA / BEST_DATA |
|---|---|---|
| `initScrollPositions` | `"0.3,0.75"` | GOOD/BETTER `"0.3,0.75,0.4,0.5"`；BEST `"0.3,0.75,0.3,0.5,0.75"` |
| `autoScrollCount` | 1 | 2 / 3 / 5 |
| `scrollInterval` | 500 ms | 1 s |
| `bringToFront` | false | true（部分站点只有前台才出懒加载） |

位置是**页面总高度的百分比**，逐个 `driver.scrollToMiddle(position)` + 停顿（`InteractiveBrowserEmulator.scrollOnPage`，`:1091`）。

### 6.2 滚动次数与位置本身也随机

`InteractSettings.buildScrollPositions()`（`InteractSettings.kt:193`）：

- 基础位置来自 `initScrollPositions`
- 额外追加 `(autoScrollCount + Random.nextInt(3) − 1).coerceAtLeast(1)` 个位置，比例 `0.6 / 0.7 / 0.8`（上限 0.8）

即：**同一档位每次访问的滚动序列不完全相同**，不是"滚到 30%、75% 各停 500 ms"的固定剧本。

页面就绪等待也带一次初始滚动（`INITIAL_SCROLL_WHEN_SCROLL_ENABLED = 5`，`InteractiveBrowserEmulator.kt:1182`），让首屏按需内容有机会出现。

### 6.3 导航后的等待不是固定 sleep

`waitForNavigationSettled`（`:1867`）：

1. 200 ms 观察窗
2. URL 已变 → 等 `body` 出现（上限 10 s）+ 1 s settle
3. URL 未变（reload / 同 URL / fragment / SPA）→ 轮询 `document.readyState` 直到 `complete`（上限 30 s）

刻意不用 `waitForNavigation`：无参重载的谓词是 `"" != currentUrl()`，页面只要有 URL 就立刻返回，等于没等。

---

## 7. 环境层：让"环境"也不像机器人

| 机制 | 位置 | 要点 |
|---|---|---|
| **UA 削减** | `Browser4UserAgent.kt:61/76/124` | **仅 headless** 加 `--user-agent`，把 `HeadlessChrome/<v>` 换成 `Chrome/<major>.0.0.0`（与 UA 缩减后的真实 Chrome 不可区分）；headed 原生就是削减形式，硬加会让 `userAgentData.getHighEntropyValues(['fullVersionList'])` 变空，反而被判"无法识别版本"。显式 `browser.launch.user.agent` 优先，其中的 `Headless` 会被修复而不是信任 |
| **`navigator.webdriver`** | 基库启动参数 | 强制 `--disable-blink-features=AutomationControlled` → 报告 `false` |
| **条件 stealth 载荷** | `browser4-core/.../resources/js/stealth.js:74` | 仓库用同名资源**阴影**基库的 puppeteer-extra 规避包，整体包在 `if (navigator.webdriver === true) { … }` 里；`PageWorldStealthPayloadTest` 钉死 "必须以此为开头"。理由：规避手法（`Function.prototype.toString` Proxy、`iframe.contentWindow` 规避）本身是 native-tampering 检测的 1.0 级证据；真浏览器（`webdriver=false`）没打过补丁就不可能被抓 tampering，CDP 场景下隐藏 `webdriver` 值这个代价 |
| **焦点/可见性** | `Browser4WebDriver.ensureFocusEmulation`（`:2363`） | 每 tab **一次** `Emulation.setFocusEmulationEnabled{enabled:true}`（Playwright 同款）：CDP 建的 tab 从不成为窗口选中页，会报 `hasFocus=false` / `hidden` / `hidden=true`（ipfighter 的 `windowFocus` 规则正是 `document.hasFocus()`）。**不激活 tab**，所以池化浏览器里并发 session 不互相抢焦点；传输层不支持时记一次 warning 并保留真实状态 |
| **WebRTC** | `browser4-core/browser4-protocol/.../browser/impl/EgoLiteBrowserLauncher.kt` | 托管 ego 启动时写 profile 的 `webrtc.ip_handling_policy=disable_non_proxied_udp`，候选地址不漏宿主真实 IP（Chromium 156 已忽略 `--force-webrtc-ip-handling-policy`，只有 preference 生效） |
| **指纹浏览器让位** | `B4Constants.stealthAppliesTo`（`B4Constants.kt:132`） | `ego-lite` 通道（别名 `egolite`/`ego lite`/`ego`）**自动跳过**整个 stealth 层：它自己管指纹，叠上去只增加脚印。**拟人输入照旧** |
| **控制台抓取** | `browser.console.capture`（`B4Constants.kt:30`） | 优先走 CDP `Console` 域，**不替换页面 `console.*`**——页面用 `String(console.log)` 一眼看穿的补丁只在传输层不支持时兜底 |
| **扩展继承** | `browser.extensions.inherit`（`B4Constants.kt:76`） | 可选 `--disable-extensions`：活跃扩展会把 `chrome.runtime` 骨架装进主世界，ipfighter 记 prototype leak（这不是 stealth 开关，不影响自动化功能） |
| **主开关** | `browser.stealth.enabled`（`B4Constants.kt:106`） | 关掉 = 不注册页面世界载荷 + 不改 UA。**拟人输入不受影响**——那是交互风格，不是隐身补丁 |
| **显示模式自检** | `skills/browser4-cli/references/browser-modes.md` §2 | `open --headed` 后校验"真有个可见窗口"：`VISIBLE` / `HIDDEN` / `UNKNOWN` 三态，"查不到"既不算成功也不算告警 |

---

## 8. 失败语义：拟人的前提是"别撒谎"

这一层的设计原则与 `AGENTS.md` 的四条强制门禁一致（反检测、负向影响、跨平台、真实网页验证；任何绕过通用路径直接发 CDP、或合成 JS 事件的方法都必须满足）：

1. **不许静默失败**：`mouseMove` inactive 抛异常（上游返回）；`hover`/`check`/`focus` 解析不到就报错（上游静默）；`fill`/`type` 有读回校验
2. **错误信息要暴露真因**：拖拽的"目标被覆盖/元素已移动"原样抛出；trusted 点击部分派发后抛"页面可能处于半按状态"，并**绕开重试**
3. **重试必须幂等**：trusted 点击未验证成功前不重复派发（`missing` 态宁可当成已送达）；拖拽重试前用 DOM 位置指纹判断是否已生效
4. **降级要降到"正确"而不是"更隐蔽"**：帧内 / 遮挡 / 已证不支持 → 退回 DOM 派发，点击落在目标元素上，而不是按坐标盲点
5. **探针失败 ≠ 目标不存在**：探针自身报错时继续走正常路径，只有"探针跑通且说没有"才失败

---

## 9. 治理层：防止后来者把拟人改没

| 载体 | 内容 |
|---|---|
| `AGENTS.md` §"Direct CDP methods: mandatory review gates" | 四条硬门禁：① 反检测（`isTrusted`、延迟随机化、坐标抖动、完整 trusted 事件上下文）② 负向影响（不静默失败、错误暴露真因、重试幂等、别 regression 上游错误语义）③ 跨平台（headless/GUI/Docker/CI、异步布局、CDP 版本差异要有兜底或显式报错）④ 真实网页验证（必须加 fixture + 跑 e2e，mock 不算） |
| `browser4-coding/CdpTrapCheck.kt` | 把三个 CDP 坑编码成关键词→建议：`insertText`→用 `randomDelayMillis("type")`；`DOM.focus`→`setSelectionRange`；`mouseWheel`/`dispatchMouseEvent`→被动 wheel 监听器。**关键词只放危险构造**，修好后不会持续误报（`CdpTrapCheckTest` 钉住这一点） |
| 单测 `Browser4WebDriverTest.kt` | 抖动：盒内 / 小元素收缩 / 盒未知保中心 / 20 次调用不恒定（`:632/644/656/666/692`）；步数：距离缩放与夹取（`:711/718`）；探针：不可枚举且用完删除（`:798`）；拖拽中心解析、命中位、frame 标记 |
| e2e `browser.rs` | `test_e2e_mouse_pointer_jitter`（`:4468`）：连点同一元素——指针位置必须变、按压点必须等于 hover 点（±1 px）、抖动范围 ≤±2 px；`test_e2e_mouse_trusted_click`（`:4581`）：click / dblclick / contextmenu / auxclick 全部 `isTrusted=true` |
| e2e `stealth.rs` | `test_e2e_stealth_navigator_invariants`（`:170`）：UA 无 `Headless`、client hints 一致、焦点/可见性读数；`test_e2e_stealth_detector_sweep`（`:323`）：第三方检测站裁决。二者需 `--enable-stealth-scenario` 显式开启（`scenarios/mod.rs:3234` 注释），`--enable-all` 不会顺带打开，避免 nightly 反复打第三方服务 |
| fixture | `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/mcp-tool-controller-mouse-fixture.html`：记录 `isTrusted`、每次 `mousemove` 坐标、按下坐标、`contextmenu`/`auxclick` 次数 |

---

## 10. 旋钮总表

| 旋钮 | 作用 | 备注 |
|---|---|---|
| `open --interact-level FASTEST…BEST_DATA`（`-ilv`/`-interactLevel`） | 换交互画像 + 延迟预设；**最有效的"别像机器人"旋钮** | 会话创建时固定 |
| `mousemove --steps N --duration MS` | 显式接管移动节奏 | 不传则用距离推导的拟人路径 |
| `mousemove --space document` | 文档坐标，引擎派发前换算 | 用于 `snapshot`/`htmlsnapshot` 的 box |
| `type --method auto\|chars\|exec` | `chars` 拟人；`exec` 一次性 bulk（无 key 事件链） | `typeAuto` |
| `browser.stealth.enabled` | 环境层总开关（UA 削减 + 页面世界载荷） | **不影响**拟人输入 |
| `browser.focus.emulation` | 是否仿真"页面在前台被聚焦" | 关掉会保留后台节流/懒加载真实行为 |
| `browser.launch.user.agent(.stealth)` | 显式 UA；或关闭 headless UA 削减 | 显式值优先，`Headless` 会被修复 |
| `browser.console.capture` | 走 CDP 还是页面世界补丁 | 默认 CDP |
| `browser.extensions.inherit` | 是否继承用户扩展 | 关闭 = 加 `--disable-extensions` |

---

## 11. 边界与已知取舍（别过度承诺）

1. **`isTrusted` 只能救走 CDP 的操作**：帧内元素、命中测试失败的目标只能退回合成 DOM 事件（`click` 家族、拖拽都有这条底线）。
2. **拖拽的合成事件链**在门控 `isTrusted` 的库（SortableJS、react-dnd）上仍不响应。
3. **`mousedown`/`mouseup` 命令是页面内合成的**（`isTrusted=false`，无默认动作），要默认动作请用 `click`。
4. **UA 削减有代价**：headless 下 `--user-agent` 会让 `fullVersionList` 变空（检测站给 0.5 而非硬判）；headed 不加。
5. **CDP 场景的 stealth 载荷自带 tampering 证据**（`toString` Proxy、iframe 规避），这是"隐藏 `webdriver` 更值"的**有意交易**，写在 `js/stealth.js` 头部。
6. **`type --method exec` 不产生键盘事件链**，快捷键/自动补全会失效。
7. **拟人不等于绕过一切**：强风控（Cloudflare / DataDome / PerimeterX）仍可能拦截；项目给的路径是：`--interact-level` 升高 → `attach --cdp` / `attach --extension` 用真实登录态 → 报告不可达。`skills/browser4-cli/SKILL.md` 要求"改动一次就停"，不在模式间来回摇摆。
8. **延迟桶上界被 2000 ms 钳制**（见 §1.2）：自定义桶写 `3000..5000` 不会生效，会被削到 2000。

---

## 12. 代码索引

### 12.1 本仓库

| 文件 | 符号 | 行 |
|---|---|---|
| `browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt` | `pointerMoveSteps` | 362 |
| 同上 | `POINTER_JITTER_PX` / `POINTER_JITTER_EDGE_INSET_PX` | 450 / 453 |
| 同上 | `click(selector, count, button)` | 855 |
| 同上 | `clickNonLeftOnDomPath` | 899 |
| 同上 | `mouseWheel(delta)` / `mouseWheel(selector, …)` | 945 / 972 |
| 同上 | `mouseMove` / `glidePointerTo` | 1035 / 1056 |
| 同上 | `movePointerToClickTarget` | 1129 |
| 同上 | `failIfPointerTargetMissing` | 1194 |
| 同上 | `withDialogWatch` | 1234 |
| 同上 | `click(selector, count)` / `click(selector, modifier)` | 1289 / 1308 |
| 同上 | `trustedClickPoint` / `hitAt` | 1333 / 1346 |
| 同上 | `clickTrusted` / `mouseEvent` | 1376 / 1461 |
| 同上 | `dblclick` / `hover` / `check` / `focus` | 1490 / 1515 / 1530 / 1552 |
| 同上 | `keyboard` / `keyDown` / `keyUp` / `press` | 1574 / 1581 / 1589 / 1607 |
| 同上 | `typeSafe` / `typeAuto` / `verifyTypedText` | 1659 / 1721 / 1772 |
| 同上 | `fillSafe` | 1828 |
| 同上 | `waitForNavigationSettled` | 1867 |
| 同上 | `ensureFocusEmulation` | 2363 |
| 同上 | `drag` / `dragAt` | 3140 / 3171 |
| 同上 | `randomOffset` / `randomDragDelayMillis` | 3387 / 3390 |
| `.../chrome/Browser4WebDriverParsers.kt` | `jitteredPointerPosition` | 226 |
| 同上 | `canClickWithTrustedInput` | 263 |
| `.../chrome/Browser4WebDriverScripts.kt` | `wheelFallbackJs` | 216 |
| 同上 | `trustedClickProbeInstallJs` / `trustedClickProbeReadJs` | 328 / 347 |
| 同上 | `buildDragSequenceScript` | 464 |
| `.../chrome/Browser4UserAgent.kt` | `reducedUserAgentOrNull` / `resolveOrNull` / `applyTo` | 61 / 76 / 124 |
| `browser4-core/browser4-common/.../B4Constants.kt` | `CONSOLE_CAPTURE_CDP` / `FOCUS_EMULATION` / `EXTENSIONS_INHERIT` / `STEALTH_ENABLED` / `stealthAppliesTo` | 30 / 57 / 76 / 106 / 132 |
| `browser4-core/browser4-browser/src/main/resources/js/stealth.js` | 条件守卫 `if (navigator.webdriver === true)` | 74 |
| `browser4-core/browser4-protocol/.../InteractiveBrowserEmulator.kt` | `scrollOnPage` | 1091 |
| `browser4-agentic/.../BrowserTabToolExecutor.kt` | 工具层键入节奏回退 | 1779 |
| `cli/browser4-cli/src/commands.rs` | `open --interact-level` / `mousemove --steps/--duration` | 779 / 1687 |
| `cli/browser4-cli/src/main.rs` | `interactLevel` → capability | 1360 |

### 12.2 基库（`pulsar-browser:4.11.32`，源码不在本仓库）

| 文件 | 符号 |
|---|---|
| `ai/platon/pulsar/api/InteractSettings.kt` | `DEFAULT_DELAY_POLICY`、`FAST_DELAY_POLICY`、`FASTEST_DELAY_POLICY`、`STEALTH_DELAY_POLICY`、`applyDelayPreset`、`generateRestrictedDelayPolicy`、`buildScrollPositions`、`create(level)` |
| `ai/platon/pulsar/api/AbstractWebDriver.kt` | `delayPolicy`、`randomDelayMillis`、`gap()`/`gap(type)`/`gap(millis)`、`waitUntil` |
| `ai/platon/pulsar/chrome/protocol/EmulationHandler.kt` | `Mouse`（`click`/`moveTo`/`down`/`up`/`wheel`）、常量 `MIN_CURVE_DISTANCE`/`MAX_CURVE_OFFSET_RATIO`/`MAX_CURVE_OFFSET_PX`、`easeInOut`、`jitteredDelayMillis`、`movementPath`、`Keyboard`（`type`/`typePrintableChar`/`delete`/`press`/`pressShiftedPrintableChar`） |
| `ai/platon/pulsar/chrome/PulsarWebDriver.kt` | `mouseMove`、`mouseMoveAt`（显式 pacing）、`mouseDown`/`mouseUp`（页面合成）、`hover`、`click`（含修饰键）、`type`、`fill`、`clear`、`press`、`scrollTo`、`dragAndDrop` |

---

## 13. 如何自证

```bash
cd cli/browser4-cli

# 指针抖动：位置要变、按压点 = hover 点、范围 ≤ ±2px（需真实后端）
cargo test --test e2e -- --scenario=test_e2e_mouse_pointer_jitter

# trusted 点击：click/dblclick/contextmenu/auxclick 全为 isTrusted=true
cargo test --test e2e -- --scenario=test_e2e_mouse_trusted_click

# 环境层：UA / client hints / 焦点·可见性（需公网 + 显式开启；--group=stealth 等价）
cargo test --test e2e -- --scenario=test_e2e_stealth_navigator_invariants --enable-stealth-scenario
```

```bash
# 纯逻辑单测（无需浏览器；假定本机已构建过一次依赖）
mvn test -pl browser4-core/browser4-browser -Dtest=Browser4WebDriverTest
mvn test -pl browser4-coding -Dtest=CdpTrapCheckTest
```

手动观察：

```bash
browser4-cli open --interact-level BEST_DATA https://example.com   # 更慢更"人"的节奏
browser4-cli mousemove 640 400                                     # 8–16ms/步的分段轨迹
browser4-cli mousemove 640 400 --steps 3 --duration 600            # 显式接管：3 步 600ms
browser4-cli type "hello" "#q" --method chars                      # 90–240ms/字符（text 在前，ref 可选在后）
```

---

## 14. 维护提示

- 改任何"直接发 CDP / 合成 JS 事件"的方法前，先读 `AGENTS.md` 的 mandatory review gates（四条）。
- 相关文档：`docs-dev/copilot/20261109-mouse-pointer-issues-and-pitfalls.md`（指针坐标空间与历史坑清单，偏"问题视角"）、`skills/browser4-cli/references/browser-modes.md` §2/§4（面向使用者的模式与旋钮说明）。
- 新增可调时延一律走 `randomDelayMillis("<bucket>")`，不要在代码里写字面量——`CdpTrapCheck` 会把 `insertText` 与硬编码小延迟同时出现的改动提示出来。
- 改基库分辨率相关行为（`Mouse.moveTo`、`Keyboard`）需要升 `browser4-base.version`，并在 `browser4-dependencies/pom.xml` 的注释块里记录"这一版带了什么"，现有注释即按此惯例维护。
- 新增拟人行为必须配 fixture + e2e（真实浏览器），mock 不算通过——这是第四条门禁的原文要求。
