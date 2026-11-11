# 鼠标指针命令（mousemove / hover）：问题与坑清单

- **日期**：2026-11-09（分析）· **最后更新**：2026-11-11（P0–P4 修复完成并接线：基库 `4.11.31-SNAPSHOT` install → Browser4 升版 → 重建 bundle → 真实浏览器复核，见 §12.8）
- **范围**：`browser4-cli mousemove` / `hover` 及其后端链路（CLI → MCP → `Browser4WebDriver.mouseMove` / `hover` → `Mouse.moveTo` → CDP `Input.dispatchMouseEvent`）
- **方法**：源码阅读（本仓库 + 基库 `browser4base` 的 `v4.11.30` tag 与当前工作区）+ 本机 CLI 实测 + 运行时注入脚本（`isolated-world.gen.js`）与 bundle classpath 实测 + 基库 Jest 单测
- **不在范围**：`click` / `drag` / `mousewheel` 的细节（仅在解释副作用时引用）。§13（注解序列化）已拆分独立成文：`20261109-pulsar-annotation-dom-pollution.md`
- **状态**：2026-11-09 完成分析；2026-11-10 在基库实现 mousemove 静默失效修复；**2026-11-11 完成全部待办（`20261110-mouse-pointer-todo.md` P0–P4）并接线**：基库 install `4.11.31-SNAPSHOT` → Browser4 `browser4-base.version` 升版 → runtime bundle 重建 → 三组真实浏览器 e2e 通过（§12.8）。当前运行时 = §0.1.1 表"升级基库后"一态。
- **状态词**：`🕐 修复前` = 4.11.30 基线行为 · `✅ 已修` = 代码/文档已改并接线 · `✅ 已验证` = 真实浏览器实测通过（证据见条目与 §12.8）。

---

## 0. 结论速览

**坐标模型只有一种**：`mousemove <x> <y>` 的 x/y 是**主框架视口坐标系下的 CSS 像素**（视口左上角为原点）。它不是桌面屏幕坐标，也不会移动操作系统的真实光标。文档里"screen coordinates"的措辞是错的。

| 编号 | 问题 | 影响 | 严重度 |
|---|---|---|---|
| M1 | 没有 OS 级指针；"屏幕左上角 / 任务栏"类需求不可表达 | 需求被误解，命令看似成功 | 高 |
| M2 | `snapshot [box=]` 是**文档坐标**，`get box` 是**视口坐标**（`vi` 接线后归入文档，§12；`get box` 仍是视口） | 混用后指针整体偏 `scrollY` | 高 |
| M3 | `get box` 会先 `scrollIntoView`，且单次读数可能早于滚动提交 | 同一元素读两次数值不同，坐标对不上 | 高 |
| M4 | 非数字坐标在 standalone `mousemove` 中静默变成 `0` | 打错字变成"移到左上角"，无报错 | 高 |
| M5 | `mousemove` 无法关闭自动快照（无 `--no-snapshot`） | 每次移动额外一次快照，脚本变慢 | 中 |
| M6 | `hover`/`click` 移动指针但不写指针状态（`drag` 同理） | 之后的 `mousedown`/`mouseup`/`mousewheel` 先把指针弹回旧位置 | 高 |
| M7 | `goto` / `reload` / 切标签不清空 `last_mouse_position` | 新页面上第一次按键命令会回放上一页坐标 | 中高 |
| M8 | 视口外坐标不 clamp，但 Chrome 只派发到视口边缘 | 页面只看到 `mouseleave`，最终坐标永不出现 | 中 |
| M9 | "快速移动"不可控（3–30 步 × 8–16 ms，无速度参数） | 无法表达"猛地甩出去" | 低中 |
| M10 | `hover` 无命中测试（可静默落到遮挡层），且会滚动页面、`bringToFront` | 悬停"成功"但对象错了；抢焦点 | 中 |
| M11 | 显式 `mousemove` 不抖动 | 重复同一命令 = 同一像素指纹 | 低 |
| M12 | 驱动 inactive 时 `mouseMove` 静默跳过 | 无报错、无效果 | 低中 |
| M13 | CSS 像素 ≠ 物理像素；`resize` 改的是模拟视口不是窗口 | 用屏幕/截图坐标换算必错 | 中 |
| M14 | `vi` 在首次 capture 时算一次后冻结（`_viDataComputed`），布局变化不刷新 | SPA/懒加载/展开后 `interactiveElements[].box` 仍是旧布局 | 高 |
| M15 | 每个文档的首次 capture 强制 `window.scrollTo(0,0)` + `window.stop()`，不恢复（✅ 基库工作区已改，未接线，§12） | 读一次 htmlsnapshot 就把页面拉回顶部，后续视口坐标全换基准 | 高 |
| D1 | README / `mcp-tools.md` / SKILL 未写清坐标空间，且 README 写成 "screen/page coordinates" | 调用方按屏幕坐标设计命令 | 中 |
| D2 | `mcp-tools.md` 说 `hover` 移到"中心附近的随机位置"，实现是固定点 | 文档与实现不符 | 低 |
| D3 | `mcp-tools.md` 说 `move_mouse_to` 的 `deltaX` 是"向左的距离"，实现是加在元素点上（正数向右/下） | 方向搞反 | 低 |
| D4 | `mcp-tools.md` 示例把数字写成字符串（`{"x": "320"}`） | `--json` 透传时容易被拒或转义 | 低 |
| D5 | 没有文档说明盒坐标的空间差异（`vi` 空间已在基库工作区改掉、未接线，输出标记仍缺，§12） | 混用即错位 | 中 |
| T1 | E2E 只覆盖视口内坐标，无出视口/负坐标/滚动后用例 | 回归无保护 | 中 |
| T2 | 无"指针状态恢复"（跨 `goto` 的 `mousedown`）用例 | M6/M7 无保护 | 中 |
| T3 | 无"盒坐标一致性"用例（滚动 / 默认 body margin / 布局变化 / 二次 capture；修复前含三套空间） | M2/M14/M15 无保护 | 中 |

### 0.1 修复状态（2026-11-11 全部完成并接线后）

> M14/M15 的完整条目见 §2 末尾；"I1–I5"的编号对照见 §0.1.2；真实浏览器复核证据见 §12.8。

| 项 | 状态 | 说明 |
|---|---|---|
| `vi` 的坐标空间（§1.1 第 3 行；I1） | ✅ **已验证** | `vi` = client rect + scroll（文档绝对坐标）；bundle 内 `dom_rect.js` 7050 B 含 `getDocumentRect`，`body{margin:8px}` 页 `vi.y` 与 `getBoundingClientRect().top + scrollY` 完全一致（§12.8） |
| M15 首次 capture 滚顶 + `window.stop()`（I3） | ✅ **已验证** | `compute()` 不再 `scrollTo`/`stop`（注入脚本内仅注释残留）；`scrollY=800` 捕获前后不变（§12.8） |
| `nScreen` / `queryClientRect` 的空间语义 | ✅ 已修 | `nScreen` 基于文档坐标；`queryClientRect` 恢复真视口语义 |
| M14 `vi` 一次性冻结（I2） | ✅ **已验证** | `compute()` 增加 `layoutVersion`（DOM 变更计数 + 滚动/视口尺寸 + URL）与 `isLayoutStale()`；在元素上方插入 400px 后二次捕获 `vi.y` +441（§12.8） |
| M2/M3 `get box` 与 snapshot 空间不同、测量-滚动竞态（I4） | ✅ **已验证** | `boundingBox` 改为"可见不滚、不可见滚后等落定再测"；`get box` 输出 `boxSpace:"viewport"`，与 `vi − scrollY` 一致（§12.8） |
| M4–M13、D1–D4、T1–T3 | ✅ 已修/已验证 | P0/P1/P2/P3 各项，逐条证据见 `20261110-mouse-pointer-todo.md`；三组新 e2e 在真实浏览器通过（§12.8） |
| 改动落地状态 | ✅ 已提交 | 基库 `4.11.31-SNAPSHOT` install 成功（含 `pulsar-bom`），Browser4 `browser4-base.version` 升版并重建 bundle；两份仓库均提交（见待办 P0-1） |
| 在 Browser4 运行时生效 | ✅ 已验证 | 注入的 `isolated-world.gen.js` 含 `getDocumentRect`、`_computeDone`、`_layoutMutationCount`、`box-space`，不含 `bodyRect`，`window.stop` 仅出现在注释；驱动启动时 `RuntimeScriptContract` 校验通过（§12.8） |

#### 0.1.1 运行时三态（2026-11-10 复查；2026-11-11 起为第三态）

| 状态 | 注入的 `js/dom_rect.js` | `NodeOps.getRect` 空间 | `vi` 实际含义 | `compute()` 副作用 |
|---|---|---|---|---|
| 修复前：bundle 构建于 2026-11-08，pin `4.11.30` | 旧（jar 内 6165 B，含 `bodyRect`） | body 相对 | `D − B` | 滚顶 + `window.stop()`，不恢复 |
| 过渡：只重建 bundle（仍 pin `4.11.30`） | 新（vendored 7050 B，含 `getDocumentRect`） | **视口**（4.11.30 的 `node_ops.js` 仍调 `getClientRect`） | **视口坐标**，只因 `compute()` 先滚顶才在数值上 ≈ 文档坐标 | 同上（滚顶/`stop` 仍在） |
| **现状：升级基库 `4.11.31-SNAPSHOT` 并重建 bundle（2026-11-11）** | 新（与 jar 内一致，7050 B） | 文档 | `D`（真文档坐标） | 不滚顶、不 `stop`；布局变化时按 `layoutVersion` 重算 |

过渡态（只重建不升级）会**静默**产出视口坐标——这正是 `RuntimeScriptContract` 现在在驱动创建时直接失败的原因（P1-6）。历史证据与复现方法见 §12.7；接线后的实测见 §12.8。

#### 0.1.2 编号对照（I 系列此前全文引用但未定义）

| 编号 | 含义 | 相关章节 |
|---|---|---|
| I1 | `vi` 的坐标空间错误（body 相对，非文档绝对） | §1.1、§9.2、§12.2 |
| I2 | `vi` 每文档只算一次（冻结，布局变化不刷新） | §9.6、M14、§12.6 |
| I3 | 首次 capture 强制滚顶 + `window.stop()` | M15、§9.6、§12 |
| I4 | `get box` 测量与滚动耦合（先 `scrollIntoView` 再测量） | M3、§9.7、§12.6 |
| I5 | 坐标输出无空间标记（缺 `boxSpace`/`layoutVersion`） | D5、§10.3、§10.4 |

---

## 1. 正确的坐标心智模型

| 事实 | 依据 |
|---|---|
| CLI 参数直接进 `browser_mouse_move_xy`，无范围校验；类型校验发生在**执行之后**（`parse_number_arg`，见 M4） | `cli/browser4-cli/src/commands.rs:1594-1613`、`main.rs:15011-15022` |
| 后端 `mouseMove` → `driver.mouseMove(x, y)` | `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:2337-2345` |
| 驱动把移动做成滑行：步数 = 距离/20，clamp `3..30`，每步随机 `8..16 ms` | `browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:355, 1021-1041` |
| 底层 `Mouse`：*"operates in main-frame CSS pixels relative to the top-left corner of the viewport"* | `pulsar-browser 4.11.30` → `chrome/protocol/EmulationHandler.kt:210-216` |
| `moveTo`：二次贝塞尔（>40px 才弯，偏移 ≤15% 距离且 ≤60px）+ smoothstep 速度 + ±35% 间隔抖动；**终点严格等于 (x,y)** | 同上 `:250-303, 529-549` |
| 真正下发的只有 CDP `Input.dispatchMouseEvent {type: mouseMoved, x, y}` | `chrome/protocol/DirectChromeProtocol.kt:725-736` |
| CDP 规范：`x/y` 是 *"relative to the main frame's viewport in CSS pixels"*，`y=0` 是视口顶部；`dispatchMouseEvent` 只是 *"Dispatches a mouse event **to the page**"* | `cdp-protocol/browser_protocol.json` 的 `Input` 域字段说明（按名字引用，勿引行号——见 §10.2） |
| 仓库内没有任何 OS 级指针调用（无 `java.awt.Robot` / `MouseInfo` / `user32`；`java.awt` 只出现在图片/PPTX 插件） | 全仓 Kotlin 检索 |

由此推出：

- `mousemove 0 0` = **页面视口**左上角（浏览器工具栏之下、内容区第一个像素），不是显示器左上角。
- `mousemove 640 450` = 1280×900 视口中心，不是屏幕中心。
- `resize W H` 是 `Emulation.setDeviceMetricsOverride`（`pulsar-browser 4.11.30: chrome/PulsarWebDriver.kt:1304-1319`），改的是**模拟视口**，不是 OS 窗口。
- headed 模式下真实光标也不会移动：CDP 输入是"注入页面的事件"。

### 1.1 "盒坐标"：接线后两种，修复前三种，只重建 bundle 时又是第三种

> 2026-11-10 基库修复后，`vi` 与 `snapshot [box]` **同为文档绝对坐标**；仅 `get box` 仍是视口坐标。但**这只在基库 `4.11.31-SNAPSHOT` 接线之后成立**（§0.1.1）；若只重建 bundle、基库仍是 `4.11.30`，`vi` 其实是**视口坐标**，只因 `compute()` 先滚顶才在数值上像文档坐标。下表保留修复前的第三空间（body 相对），用于理解历史行为与旧数据——注意旧的 `bodyRect` 是**惰性缓存且从不失效**（`v4.11.30: js/dom_rect.js:173,193`）：若缓存在非零滚动位置被填过，历史 `vi` 会整体漂移一次，这是 §9.2 误差模型之外的一个独立误差源。

| 来源 | 坐标空间 | 证据 |
|---|---|---|
| `snapshot --boxes` 的 `[box=x,y,w,h]` | **文档/绝对坐标**（含滚动量） | CDP `DOMSnapshot.LayoutTreeSnapshot.bounds` = *"The absolute position bounding box"*（`browser_protocol.json:9829`）；ARIA 渲染直接用它（`pulsar-browser 4.11.30: api/snapshot/AriaSnapshotRenderer.kt:81,107-109`）；iframe 还会叠加父框偏移（`chrome/dom/CDPSnapshotService.kt:330, 417-427`）。实测：`link "首页" [box=0,2000,100,40]`，而该元素视口 `top=860`、`scrollY=1140` |
| `get box`（`bounding_box` → `DOM.getBoxModel`） | **视口坐标** | 实测：`scrollY=3110`、元素视口 `top=430` → `get box` 返回 `y=430`；另一例 `scrollY=1140`、文档 `y=2000` → 返回 `y=860` |
| `htmlsnapshot` 的 `vi="x y w h"` | **文档绝对坐标**（仅"基库工作区"成立；接线前旧运行时而"相对 body 边框盒"，重建 bundle 后为"视口坐标"，见 §0.1.1） | 基库工作区：`__pulsar_utils__.getDocumentRect = client rect + scroll`，`NodeOps.getRect` 使用它（§12）。修复前证据：`v4.11.30: js/dom_rect.js:172-184` 的 `rect − bodyRect`（`bodyRect` 惰性缓存）+ `compute()` 先 `scrollTo(0,0)`（`__pulsar_utils__.js:1510-1567`） |

`mousemove` 只吃视口坐标。把 `[box=]` 或 `vi`（**接线后**同为文档坐标；接线前/只重建 bundle 时都不是，见 §0.1.1）直接喂给 `mousemove`，页面一滚动就整体偏移 `scrollY`；把 `get box` 的视口坐标直接存档/跨会话复用，则会在滚动后失效。

---

## 2. 行为类问题

### M1 没有 OS 级指针，"移到屏幕左上角 / 任务栏"不可表达

- **现象**：`mousemove` 只向页面注入鼠标事件；桌面光标不动，`screenX/screenY` 也不代表显示器坐标（页面收到的是 `clientX/clientY`）。
- **影响**：任何"移出屏幕""移到任务栏""移动到显示器中心"的需求，只能退化成视口坐标；写成命令也不报错，容易被误认为已完成。
- **证据**：§1 的 CDP 规范 + 无 `Robot`/`user32`；实测事件里只有 `clientX/clientY`。
- **建议**：在 SKILL / `mcp-tools.md` 明确"坐标 = 视口 CSS 像素，非桌面坐标"；需要桌面操作时明确说明不支持。

### M2 `snapshot [box=]` 与 `get box` 坐标系不同

- **现象**：同一个元素，`snapshot --boxes` 给出 `y=2000`（文档坐标），`get box` 给出 `y=860`（视口坐标）；两者相差 `scrollY`。
- **影响**：用快照里的 box 计算 `mousemove` 坐标时，滚动后指针落点整体上移/下移 `scrollY`。这是最容易踩、也最难发现的一条。
- **证据**：见 §1.1 的实测与 CDP 定义。
- **建议**：文档写明两种空间；或给 `snapshot` 增加 `--viewport-boxes`；至少给 `get box` 输出加空间标注。

### M3 `get box` 会先滚动，且单次读数可能早于滚动提交

- **现象**：`boundingBox()` 先 `page.scrollIntoViewIfNeeded(selector)` 再测量（`pulsar-browser 4.11.30: chrome/PulsarWebDriver.kt:2034-2039`）。实测对首屏外元素连续两次读数：第一次 `y=3540`（滚动前视口，等于文档坐标），命令结束后 `scrollY` 从 0 变成 2909；第二次（元素已在视口内）`y=430`，与 `getBoundingClientRect()` 一致。
- **影响**：测量本身改变页面滚动状态；同一元素两次读数不同；把第一次读数喂给 `mousemove`，落点会偏。与 AGENTS.md 里"滚动提交是异步的"属同一类坑。
- **建议**：文档提示"先 `scroll`/`scrollintoview`，等稳定后测量"；或让 `bounding_box` 返回前轮询滚动落定。

### M4 非数字坐标静默变成 `0`

- **现象**：`tool_params_fn` 用 `get_number_value(args, "x").unwrap_or_else(|| json!(0))`（`commands.rs:1609-1610`），而 `get_number_value` 只接受 JSON number（`commands.rs:248`，`filter(|v| v.is_number())`）。所以 standalone `mousemove abc 200` 会带着 `x=0` 发出去；只有 `batch` 编译期会拒绝非数字（`main.rs:32038-32053`，错误信息 `Invalid x for mousemove ...`，见 `32038-32041` 的注释）。
- **影响**：打错字 → 指针悄悄移到左上角，退出码 0。与 AGENTS.md 的 "no silent failures" 相冲突。
- **建议**：把 batch 的数值校验提到 standalone 的参数解析/`tool_params_fn` 里，非数字直接报错。
- **补充（执行次序）**：`handle_mouse_move` 是**先执行、后解析**——`handle_tool_command` 在第 15011 行已经发出移动，`parse_number_arg` 到 15020 行才校验，失败时 `last_mouse_position` 也不会写（`main.rs:15011-15022`）。所以 M4 与 M6 是同一个"校验位置太晚"的缺陷，修复点应统一到执行前。

### M5 `mousemove` 无法关闭自动快照

- **现象**：`mousemove` 不在 `no_snapshot_commands()` 集合里（`main.rs:422` 起），命令执行完会跑 `post_command_snapshot`（定义在 `main.rs:1534`，调用点 `main.rs:34996`，条件 `!no_snap.contains(command) && !no_snap_flag`）；同时它的 `CommandDef.options` 为空（`commands.rs:1604`），传 `--no-snapshot` 直接报错。
- **实测**：`mousemove 0 0 --no-snapshot` → `error: unknown option --no-snapshot for 'mousemove'.` + 换行 `This command accepts no options.` + 帮助提示（构造于 `args.rs:537-556`），退出码 2；正常调用输出里有 `### Snapshot`。对比：`hover` 已声明该选项（`commands.rs:1835-1837`）。
- **影响**：连续做多次指针移动（尤其"快速"场景）时，每条命令额外一次快照调用，慢且刷屏。
- **建议**：给 `mousemove` 加 `--no-snapshot`（与 `hover` 对齐），或把 `mousemove` 放进 `no_snapshot_commands()`。

### M6 `hover`/`click`/`drag` 移动指针但不更新指针状态

- **现象**：`last_mouse_position` 只在 `handle_mouse_move` 成功后被写入（`main.rs:15022`）；`mousedown`/`mouseup`/`mousewheel` 执行前会先 `restore_mouse_position`（`main.rs:15033`，即再发一次 `browser_mouse_move_xy`）。
- **影响**：`hover e5` 之后执行 `mousedown`，指针会先被"恢复"到上一次 `mousemove` 的坐标（或没有记录时不动），**离开刚悬停的元素**再按下。任何"悬停后再 `mousedown`/`mouseup`/`mousewheel`"的组合都可能错位（`drag` 走自己的坐标路径，不受影响）。
- **证据**：两个函数定义在 `main.rs:14960`（`persist_mouse_position`）/ `14971`（`restore_mouse_position`），调用点只有 `main.rs:15022`（`mousemove` 成功后写入）与 `15033`（`mousedown`/`mouseup`/`mousewheel` 前恢复）。`hover`/`click`/`drag` 走的是通用工具分派（`main.rs:34132-34151`、批量路径 `32059-32062`），不经过这两个函数。
- **建议**：`hover`/`click`/`drag` 完成时把实际落点写回 state；或明确 `mousedown` 不恢复、由调用方自己 `mousemove`。

### M7 导航后不清空指针位置

- **现象**：`last_mouse_position` 只在 `open`/`attach`/切换 endpoint/会话失效时清空（`main.rs:1055, 1381, 2875, 3114, 3266`）；`goto`/`reload`/`tab-select` 都不清。
- **影响**：跳转到新页面后第一次 `mousedown`/`mouseup`/`mousewheel` 会把上一页的坐标回放成一次 `mousemove`——可能在新页面上触发 `mouseover`/`mouseleave` 或改变 `:hover`，甚至落在滚动条/关闭按钮上。
- **建议**：`goto`/`reload`/`tab-select`/`tab-close` 时清空（或在恢复前校验 URL 未变）。

### M8 视口外坐标：不 clamp，但页面只看到"离开"

- **现象**：CLI/驱动/`Mouse.moveTo` 全链路不校验范围（负坐标与 `y>innerHeight` 都能发）。实测 1280×900 视口下 `mousemove 640 1200`：页面收到的最大 `clientY=899`（视口底边），随后 `<html>` 上的 `mouseout`/`mouseleave`，最后 `#document` 的 `mouseleave`；请求的 `y=1200` 从未作为事件坐标出现。
- **影响**：(a) 可以把"指针离开页面"表达出来（这是最接近"移到任务栏"的可观测效果）；(b) 但不能把"指针在任务栏上"表达出来，也不能指望页面读到任务栏坐标；(c) 越界坐标不报错，容易被当成"移动成功"。
- **建议**：文档写明"越界 = 离开视口，不等于移动窗口外"；如需明确语义，考虑给 `mousemove` 加 `--allow-outside` 或在越界时提示。

### M9 "快速移动"不可控

- **现象**：速度由 `pointerMoveSteps` 固定推导（距离/20，clamp 3..30）× 每步 8–16 ms，即**名义**约 24 ms–480 ms；基库还会对每步间隔再做 ±35% 抖动（`v4.11.30: chrome/protocol/EmulationHandler.kt:544-550`，钳到 ≥1 ms），所以实际区间约 **16–650 ms**。`CommandDef` 没有速度/步数选项。4.11.30 起长距离路径还是曲线（`EmulationHandler.kt:250-303`）。
- **影响**：无法表达"快速甩出屏幕""瞬间移开"，只能大致近似；且每次都必然是一串 `mousemove` 事件。
- **建议**：如确需，"快速"场景文档给出"距离越短越快"的近似说明；或后续加 `--steps/--duration`。

### M10 `hover` 的静默与副作用

- **现象**：`hover` = `bringToFront()` + `scrollIntoView` + 一次"外部点"移动 + 一次元素点移动（`pulsar-browser 4.11.30: chrome/PulsarWebDriver.kt:1029-1035` → `chrome/protocol/EmulationHandler.kt:957-994`）。其中：
  - 先跳到元素框左上角 `−(50,50)`（下限 0），再落到元素内部点——**对左上角的"首页"链接，这一步会先经过 (0,0)**，可能瞬时 hover 到角上的固定元素（返回顶部按钮、悬浮广告）。
  - 不检查该点是否被其它元素遮挡（不像 `click` 有命中测试），被遮挡时会"成功"地悬停到遮挡元素上。
  - 会滚动页面并激活标签页（`bringToFront`），并发会话可能互相抢焦点。
- **实测**：链接框 `(10,10,32,24)`，`hover` 轨迹末尾是 `(0,0)` → `(26,11)`，最终落在元素内部点（内容框点，非包围盒几何中心）。
- **建议**：文档写明副作用；考虑对被遮挡目标给出告警（AGENTS.md 的"no silent failures"同样适用）；左上角场景建议直接 `mousemove`。

### M11 显式坐标不抖动

- **现象**：`browser-modes.md:261-267` 明确"传给 `mousemove` 的坐标不会被抖动——它表示'把指针精确放在这里'"；抖动只发生在 `click` 前的指针移动（±2 px，`Browser4WebDriver.kt:443`）。
- **影响**：脚本化地重复同一 `mousemove` 会产生完全相同的轨迹起点/终点，是可被页面统计的指纹。
- **建议**：反检测场景不要靠 `mousemove` 制造"人类感"，用 `click`/`hover` 或自行加随机偏移。

### M12 驱动 inactive 时静默跳过

- **现象**：`mouseMove` 内部 `val m = page.mouse.takeIf { isActive } ?: return@invokeOnPage`（`Browser4WebDriver.kt:1023`）——驱动不活跃就直接返回，CLI 仍报成功。
- **影响**：会话异常/页面已关时，"移动成功"是假象。
- **建议**：至少输出一条 warning；或在校验阶段判断 `isActive` 并报错。**注意**：该行上方的 KDoc 明确写着 *"Like the upstream, an inactive driver skips the move silently"*（`Browser4WebDriver.kt:1018`），即当前行为是**有意继承上游**——改行为时必须同步改 KDoc，否则会被当成回归。

### M13 CSS 像素与 DPR

- **现象**：坐标单位是 CSS 像素（CDP 规范），与物理像素、截图像素都可能不同（Windows 125%/150% 缩放、`devicePixelRatio>1`）。`resize` 只改模拟视口且会保留已有 `deviceScaleFactor`（`pulsar-browser 4.11.30: chrome/PulsarWebDriver.kt:1304-1319`）。
- **影响**：用截图/屏幕物理坐标换算 `mousemove` 会偏。
- **建议**：文档给出"用 `getBoundingClientRect()` 或 `get box`，不要用截图坐标"。

### M14 `vi` 每文档只算一次，布局变化不刷新

- **现象**：`compute()` 在每个文档里只真正执行一次——`Browser4WebDriverScripts.viDataStatusJs()` 只在 `u._viDataComputed !== true` 时调用它（`Browser4WebDriverScripts.kt:646-654`）；基库工作区把守卫从 `body[data-error]` 换成内存标记 `_computeDone`（`__pulsar_utils__.js:1582-1586`），"一次性"这件事没有变。
- **影响**：SPA `pushState`、懒加载、展开/排序之后再 capture，`interactiveElements[].box` 仍是首次布局的坐标（§9.6 实测：布局改造后仍是 `0 1200`，而同刻 `snapshot --boxes` 已是 `16,16`）。值本身"合法"，无法从数字判断已过期。
- **建议**：capture 元数据加 `layoutVersion`（DOM 版本号 / `scrollHeight` 哈希），失效即重算；见 §9.7 P1。
- **状态**：❌ 未修（基库工作区也未改），见 §12.6 第 2 条。

### M15 首次 capture 强制滚顶 + `window.stop()`，且不恢复

- **现象**：`v4.11.30` 的 `compute()` 在遍历前无条件执行 `window.scrollTo(0, 0)` 与 `window.stop()`（`__pulsar_utils__.js:1510-1530`），既不保存也不恢复滚动位置；`stop()` 还会中止在途资源加载。
- **影响**：读一次 `htmlsnapshot` 就把页面拉回顶部，之后所有视口坐标（`get box`、`mousemove`、`snapshot -v`）的基准都变了；懒加载/长轮询页面可能因此拿不到内容。实测 `scrollY` 800 → 0。
- **状态**：✅ **基库工作区已修**（两行直接删除，工作区 `__pulsar_utils__.js:1574-1626` 已无 `scrollTo`/`stop`），但**未接线**：Browser4 仍 pin `4.11.30`，当前 bundle 里还是旧脚本。

---

## 3. 文档类问题

### D1 坐标空间没有写清，且有一处写错

- `README.md:391` / `README.zh.md:397`：*"Move the mouse to screen/page coordinates" / "把鼠标移动到页面 / 屏幕坐标"* —— 源码只有视口坐标，没有屏幕坐标。
- `docs/mcp-tools.md:2578-2603`（`mouse_move`）：只写 "Moves the mouse to the position specified by x and y"，没有坐标空间。
- `skills/browser4-cli/SKILL.md:246`：只有 `mousemove 5 5` 清 hover 的例子，没有坐标空间与滚动相关的说明。
- `cli/browser4-cli/README.md:111`、`help.rs:4595-4596`（`browser4-cli mousemove 100 200` 示例）：同样没有空间说明。

**建议**：统一改成"CSS 像素，相对主框架视口左上角；越界即离开视口；不移动 OS 光标"，并在 SKILL 里补一条"滚动后坐标失效"的提示。

### D2 `hover` 的描述与实现不符

- `docs/mcp-tools.md:2314`：*"Move mouse to hover over a random position near the center of the element."*
- 实现是 `getInteractPoint(..., useRandomOffset = false)`（无随机偏移）、且先跳到元素框左上 `−(50,50)`（`EmulationHandler.kt:957-994`）。

**建议**：改为"先移到元素左上方约 50px 处，再移动到元素内部可点位置（无抖动）"。

### D3 `move_mouse_to` 的 `deltaX` 方向描述反了

- `docs/mcp-tools.md:2735`：`deltaX` —— *"The distance to the left of the element."*
- 实现：`PointD(point.x + deltaX, point.y + deltaY)`（`pulsar-browser 4.11.30: chrome/PulsarWebDriver.kt:1018`）——正数向右/下。

**建议**：改为"相对元素可点位置的偏移（正数向右/向下）"。

### D4 示例把数字写成字符串

- `docs/mcp-tools.md:2595`：`{"x": "320", "y": "180"}`。MCP 层可能容忍字符串，但 CLI 的 `get_number_value` 只认 JSON number（见 M4），风格也不一致。

**建议**：示例改成 `{"x": 320, "y": 180}`。

### D5 盒坐标没有空间说明（`vi` 部分已改，输出标记仍缺）

- 修复前：`[box=]`（文档坐标）、`get box`（视口坐标）、`vi`（相对 body）三套数字在文档里都没有空间说明（`skills/browser4-cli/references/snapshot.md:63`、`x-sql.md:196` 只说"CSS pixels"）。
- 2026-11-10：`vi` 在**基库工作区**改为文档坐标（§12，接线后生效），因此 `[box=]` 与 `vi` 届时同空间；但 **`get box` 仍是视口坐标**，三处文档仍只写 "CSS pixels"，capture JSON / X-SQL 仍无 `boxSpace` 字段。
- 注意：接线前（当前 bundle）`vi` 仍是 body 相对，别按本文新语义解析现有产物（§0.1.1）。
- 同时注意：`docs/mcp-tools.md` 的 `hover` / `move_mouse_to` 描述问题（D2/D3）与坐标空间无关，仍未修。

**建议**（待办）：在 snapshot / get / htmlsnapshot 三处各标注空间，并在 SKILL 的鼠标小节交叉引用；给 capture JSON 与 `get box` 增加 `boxSpace`（枚举必须含 `"body"`，见 §10.4）。

---

## 4. 测试缺口

### T1 没有视口外/滚动后的用例

- 现有 E2E 只用视口内坐标：`(5,5)`、`(10,10)`、`(100,100)`、`(120,120)`、`(200,200)`、`(500,500)`、`(150,600)`、`(150,740)`（`cli/browser4-cli/tests/e2e/scenarios/browser.rs:2345, 2297, 2571, 1261, 2529, 2580, 2210, 2235`）。
- 夹具定义了右下角 `#corner-br`（`browser4-tests/pulsar-tests-common/src/main/resources/static/b4/mcp-tool-controller-mouse-fixture.html:88`），但只测了左上角。
- 没有"页面滚动后 `mousemove` 落点仍正确"的用例。

**建议**：新增 `(0,0)`、负坐标、`y>innerHeight`、滚动后坐标、以及 `[box]` vs `get box` 一致性的用例；夹具加一个"滚动后目标"。

### T2 没有指针状态恢复的用例

- M6/M7 描述的行为（`hover` 后 `mousedown` 回跳、`goto` 后按键回放旧坐标）没有任何测试覆盖。

**建议**：加 e2e：`mousemove A` → `hover B` → `mousedown`，断言按下坐标在 B 上；`mousemove A` → `goto` 新页 → `mousedown`，断言新页收到的首个 `mousemove` 坐标。

### T3 没有"盒坐标一致性"的用例

- 修复前没有任何用例同时检查 `snapshot [box]`、`vi`、`get box` 三种输出的空间关系；也没有"滚动后 capture"与"布局变化后二次 capture"的断言——这正好是 M2、M14、M15 长期未被发现的原因。
- 基库 JS 侧在 §12 与注解拆分文档补了 10 个单测（`dom_rect.test.js` 11 → 21，覆盖 client/document rect、`compute()` 不滚动、序列化器输出），但**跨命令/跨模块**的一致性仍未覆盖。

**建议**（待办）：
1. 夹具：`1200px spacer + 目标元素 + body{margin:8px}` 的可滚动页面；
2. 滚动后 capture：断言 `vi` 不随滚动变化、`scrollY` 前后不变（⚠️ **前置条件：基库 `4.11.31-SNAPSHOT` 已接线**；只重建 bundle 时这条会失败，因为 `vi` 还是视口坐标）；
3. 布局变化后二次 capture：断言 `vi` 能反映新布局（当前会失败，正好证明 M14）；
4. 空间关系：`vi.y == snapshot [box].y`（同样以"已接线"为前置）、`get box.y == vi.y − scrollY`（同一时刻）；
5. 修复前数据的兼容用例：`vi` 与 `boxSpace:"body"` 的旧数据按 +bodyOffset 解释。
6. 承载位置：基库已有真实浏览器用例基座（`pulsar-tests/pulsar-e2e-tests/.../PulsarWebDriverInjectedJSTests.kt`，含 `getAnnotatedHTML`/`serializeAnnotatedHTML` 相关用例），"滚动后 capture / 二次 capture"可直接加在那里，不必等 Browser4 升级。

---

## 5. 修复建议（按文件）

> **待办已收敛**：按文件的修复建议现已统一到 [`20261110-mouse-pointer-todo.md`](20261110-mouse-pointer-todo.md)（P0–P4，每项含位置、依赖与验收）。本节 2026-11-09 的原始建议不再单独维护，只保留"问题 → 待办项"的映射：

| 问题条目 | 待办项 |
|---|---|
| M4 / M6 / M7 / M5 | P0-2 / P0-3 / P0-4 / P0-5 |
| D1–D4 | P0-6 |
| D5、I5 | P2-1 / P2-2 |
| M3、I4 | P2-3 |
| M14 / I2 | P3-1 |
| §10.4 第 2 条 / §11.7 第 2 条 | P2-5 / P2-6 |
| M10 / M12 / M8、M9 | P3-5 / P3-6 / P3-7 |
| T1 / T2 / T3 | P3-3 / P3-4 / P3-2 |

---

## 6. 四个动作的正确命令（速查）

```bash
# 视口左上角（不是显示器左上角；OS 光标不动）
browser4-cli mousemove 0 0

# "首页"链接附近：语义化命令是 hover（会先经过元素左上 50px 处）
browser4-cli snapshot -v 0
browser4-cli hover e12
browser4-cli hover "a[href='/']"

# 视口中心：先固定视口，再取一半；或读 innerWidth/innerHeight
browser4-cli resize 1280 900
browser4-cli mousemove 640 450
browser4-cli eval "JSON.stringify([innerWidth, innerHeight])"

# 离开页面（最接近"移出屏幕"）：页面会收到 mouseleave，任务栏本身不可表达
browser4-cli mousemove 640 1200
```

想"停在元素旁边而不是压住它"，CLI 没有偏移参数：用 `getBoundingClientRect()`（视口坐标）自己算，或走 MCP 的 `move_mouse_to`（`pulsar-browser 4.11.30: chrome/PulsarWebDriver.kt:1006-1026`，`selector + deltaX/deltaY`，注意 D3 的方向描述错误）。

---

## 7. 验证环境与方法

**源码**

- 仓库：`D:\workspace\Browser4\Browser4-4.16`（4.16.5-SNAPSHOT）
- 基础库：**分析基线** `pulsar-browser 4.11.30`（`browser4-dependencies/pom.xml:587`），从 Maven Central 取 `pulsar-browser-4.11.30-sources.jar` 阅读；**修复实现**在 `D:\workspace\Browser4\browser4base`（`4.11.31-SNAPSHOT`，同一份源码 + §12 的改动）。
- CDP 定义：仓库内 `cdp-protocol/browser_protocol.json`。

**实测**（2026-11-09 分析阶段；本机已装 CLI，dev 模式，允许使用旧 bundle `4.16.4-SNAPSHOT`，端口 8282；会话 `mouseresearch` 已 `close`，`mouseresearch2` 随 dev server 退出失效，均无遗留进程）

| 验证点 | 命令 | 结果 |
|---|---|---|
| 视口原点 | `resize 1280 900` + `mousemove 0 0` | 页面事件 `clientX/Y = 0,0`（target `HTML`） |
| 视口中心 | `mousemove 640 450` | 约 30 个弯曲轨迹点，末点精确 `(640,450)` |
| 越界 | `mousemove 640 1200` | 最大 `clientY=899`；随后 `mouseout/mouseleave`（`HTML`）与 `#document` 的 `mouseleave` |
| hover 落点 | 链接框 `(10,10,32,24)` + `hover "#home"` | 轨迹 `(0,0)` → `(26,11)`，最终落在元素内部点 |
| `[box]` vs `get box` | `scrollY=1140`、文档 `y=2000` | `snapshot` → `[box=0,2000,...]`；`get box` → `y=860` |
| `get box` 的滚动副作用 | 元素在首屏外、`scrollY=0` | 返回 `y=3540`，随后 `scrollY` 变 `2909`；再次调用返回 `y=430` |
| 负坐标/小数 | `mousemove -20 -20`、`mousemove 640.5 450.25` | 均被接受，退出码 0 |
| `--no-snapshot` | `mousemove 0 0 --no-snapshot` | `unknown option`，退出码 2 |

**未验证 / 需补测**

- `move_mouse_to` 经 `tool call` 的端到端行为（本机 dev server 生命周期不稳，未稳定复测；源码路径清楚：元素点 + delta）。
- iframe 内元素 `hover` 的坐标换算（`CDPSnapshotService` 有 iframe 偏移逻辑，未实测）。
- headed 模式下的真实光标（按 CDP 语义推断不动，未实测）。

**2026-11-10 基库变更的验证**（与上表不同批次）

- 单元测试：基库 Jest **141 passed**；编译：Maven 离线编译 **exit 0**；语法：`node --check` 通过。详见 §12.4。
- 真实浏览器 e2e：**未做**，原因与接线后的验证步骤见 §12.5。

---

## 8. 一句话总结

`mousemove` 是"往页面里注入视口坐标的鼠标事件"，不是桌面鼠标；真正需要小心的是**盒坐标空间混用**（修复前有三套；基库工作区改成"文档 vs 视口"两类，但**尚未接线**，当前运行时仍是最初的 body 相对，见 §0.1.1）、**滚动与量测互相影响**、以及**指针状态在 CLI 侧的持久化只覆盖 `mousemove` 本身**——这三类问题都不会报错，只会让落点悄悄偏掉。已改部分见 §12/§12.7，仍未修的 `vi` 冻结与 `get box` 竞态见 §9.7/§12.6。

---

## 9. 修复前的三套盒坐标不一致：副作用评估（历史记录）

> **时效说明（2026-11-10）**：§9.1–§9.6 是**修复前**（基库 4.11.30 + Browser4 4.16.5）的实测记录，作为问题证据保留。§12 已在**基库工作区**修掉其中两类——`vi` 改为文档绝对坐标、`compute()` 去掉滚顶与 `window.stop()`（尚未接线，运行时状态见 §0.1.1/§12.7）；因此：
> - §9.2 误差模型里的 `vi` 公式（`D₀ − B₀`）**只适用于修复前/旧数据**；现在 `vi = D`。
> - §9.6 第 1、2 条（滚顶、`stop()`）**已消除**；第 3、4 条（DOM 注入、一次性冻结）仍然存在。
> - `get box`（视口）与 `snapshot [box]`/`vi`（文档）之间的空间差异，以及 `get box` 的测量-滚动竞态，**尚未修复**。

### 9.1 实测：同一元素、同一时刻，三个互相矛盾的数字（修复前）

夹具（`file://` 本地页，`html,body{margin:8px}`，`div(height:1200px)` + `a#home 100×40` + `div(height:1500px)`）：

1. 滚动到 `scrollY=800`，元素视口 `top=416`、绝对 `y=1216`、body 边框盒绝对 `y=16`；
2. 第一次 `htmlsnapshot` 触发 `compute()`：返回 `[box=0 1200 100 40]`，**capture 结束后 `scrollY` 变成 0**（被强制滚顶，见 9.6）；
3. 把 spacer 高度改成 0（元素绝对 `y` 从 1216 变成 16），`scrollY` 被浏览器夹到 672，元素视口 `top=−656`；
4. 同一时刻对比三条命令：

| 命令 | 输出（y） | 坐标空间 | 相对"正确视口坐标"（−656）的偏差 |
|---|---|---|---|
| `snapshot --boxes` | `[box=16,16,100,40]` | **绝对文档坐标**，实时 | 偏 `S=672` |
| `htmlsnapshot` 的 `interactiveElements[].box`（即 `vi`） | `[box=0 1200 100 40]` | **相对 body**，且冻结在首次 capture 的布局 | 偏 **1856**（`Δlayout 1200 + S 672 − B 16`） |
| `get box` | `{"x":16,"y":-656,...}` | **视口坐标**（测量时 `scrollY=672`） | 返回瞬间为 0，但该调用随后把页面滚到顶（`scrollY→0`，元素到 `y=16`）→ **返回即过期，偏 672** |

补充实测（未改布局时，同一页面）：

| 场景 | `snapshot [box].y` | `vi.y` | `get box.y` | 说明 |
|---|---|---|---|---|
| 首次 capture 后（`scrollY=0`） | 1216 | 1200 | 1213（另一次读得 1216；3px 差异**原因未定**：可能是 `DOM.getBoxModel` 与 `getBoundingClientRect` 的取整/边框差异，也可能是测量-滚动竞态） | `vi` 比绝对坐标少 16 = `html` margin 8 + `body` margin 8 |
| 滚到 `scrollY=800` | 1216 | 1200 | 416（调用后页面到 786） | `get box` 测的是滚动前的视口 |

由此确认 `vi` 的基准是 **body 边框盒左上角**，不是文档原点，也不是视口：`vi = 元素绝对坐标 − body 绝对偏移`，且这个值在**每个文档的第一次 `compute()`** 时算出来（当时已被强制滚到顶），之后不再更新。

### 9.2 误差模型

设：`D` = 元素当前绝对 y，`S` = 当前 `scrollY`，`B` = body 边框盒绝对 y，`D₀/B₀/S₀` = 首次 capture 时的值（`S₀=0`，因为 `compute()` 先滚顶），`Δlayout = D₀ − D`。

> **`B` 的来源有一个隐藏前提**：旧实现把 `document.body.getBoundingClientRect()` 缓存在 `__pulsar_utils__.bodyRect` 上且**从不失效**（`v4.11.30: js/dom_rect.js:173,193`）。正常路径下 `compute()` 先滚顶，缓存是在 `S=0` 时填的，所以 `B` 就是绝对 y；但只要有更早的调用在滚动后填过缓存，`B` 实际是 `B_abs − S_cache`，下表公式要多减一个 `S_cache`。这正是"同一页面两次数值不同"的另一个可能来源（基库工作区已删除该缓存）。

| 输出 | 实际含义 | 当作 `mousemove` 坐标用时的误差 |
|---|---|---|
| `snapshot [box].y` | `D` | `S`（滚动量；本例 672） |
| `vi.y` | `D₀ − B₀` | `Δlayout + S − B`（本例 1200 + 672 − 16 = 1856） |
| `get box.y` | `D − S_measure` | `S_after − S_measure`（测量后页面被滚动的量；本例 672） |

规律：

- **不滚动时**：`snapshot` 正确（`S=0`），`vi` 恒偏 `B`（默认 UA 样式 8px；`html`+`body` 双 8px 则 16px），`get box` 正确。
- **滚动后**：三者的可用性完全取决于"谁在什么滚动位置测的"；没有任何输出字段能告诉你这一点。
- **页面改造/SPA 后**：`vi` 的误差还叠加 `Δlayout`（本例 1200px），且不会自愈。

### 9.3 谁在消费这些数字（影响面）

| 消费方 | 位置 | 用的空间 | 受影响方式 |
|---|---|---|---|
| `htmlsnapshot` capture 元数据 `interactiveElements[].box` | `browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:1547-1548`（`el.attr("vi")` → `put("box", box)`，原样输出） | body 相对、冻结 | 对外 API/CLI 直接给出"bounding box"，使用者会当成元素真实位置 |
| `htmlsnapshot inspect` 的版位加权（`100..800` 正文、`<100` 头部、`>2500` 页脚） | `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:2582-2597` | 假设绝对页面坐标 | body 偏移造成小漂移（8–16px），**vi 冻结造成大漂移**（旧布局 y 值）→ 头部/正文/页脚误判 |
| `inspect` 样本里的 `box` / 子节点 `box` | `MCPToolController.kt:2759, 2773` | body 相对、冻结 | 与 `snapshot` 的 box 比较时永远对不上 |
| PowerCSS `:expr(width>…, height>…)` 候选 | `MCPToolController.kt:2905-2931` | body 相对、冻结 | 用旧尺寸生成选择器 → 匹配不到或匹配到错误容器 |
| 摘要索引的卡片聚类/重叠判定 | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/workflow/parse/html/PageSummaryIndexService.kt:877-898, 986-989, 1131-1153` | body 相对、冻结 | 同一空间内比较时 body 偏移可抵消；**冻结值导致按旧布局聚类**（SPA 场景失真） |
| X-SQL / 导出 HTML 的 `box=` | `skills/browser4-cli/references/x-sql.md:196`（"printed verbatim from the runtime's `vi` data"）；`HTMLSnapshotToolExecutor.kt:1117` 保留 `vi` 属性 | body 相对、冻结 | 离线消费者无法知道空间，也无法还原 |
| Agent/脚本把 box 喂 `mousemove` | `mousemove` 期望视口坐标（本文 §1） | — | 落点偏移 `S − B + Δlayout`（本例最大 1856px，静默点错元素） |
| 文档/评审对照 | `snapshot.md:63`、`x-sql.md:196`、`skills/browser4-cli/references/htmlsnapshot.md:291`（`:38` 只写 "bounding box"）、`docs/aria-snapshots.md:48` 都没有空间说明 | — | 无法判断该信哪个数字（本例 16 vs 1200） |

### 9.4 触发条件与频率

| 触发条件 | 是否常见 | 后果 |
|---|---|---|
| 页面滚动过再用 `snapshot [box]` 当视口坐标 | 极常见 | 偏移 = `scrollY` |
| 页面有默认 `body{margin:8px}` 或布局有 body 偏移 | 极常见（未 reset 的页面） | `vi` 恒偏 8–16px，即使不滚动 |
| 每个文档的**第一次** `htmlsnapshot*`（含 `get`/`inspect`/`summary`/`query` 的隐式 capture） | 必然发生一次 | 强制滚动到顶、`window.stop()`、注入 `#PulsarMetaInformation` 与 `body[data-error]`（见 9.6） |
| SPA `pushState` / 懒加载 / 展开收起 / 列表排序后再 capture | 常见 | `vi` 不刷新 → 旧布局坐标 |
| `get box` 在元素不在视口内或滚动未落定时 | 常见 | 返回值与返回后的视口不一致 |
| 页面 `body` 带 `transform`/`zoom`/定位偏移 | 少见 | `vi` 的 body 基准任意漂移 |

### 9.5 严重度评估

| 维度 | 评级 | 理由 |
|---|---|---|
| 静默性 | **高** | 三个命令都退出码 0、无 warning；数字都"像"对的 |
| 频率 | **高** | 滚动 + 默认 body margin 几乎覆盖所有真实页面 |
| 单位误差量级 | **中–高** | 不滚动时 8–16px；滚动时 = `scrollY`（可达数千）；SPA 布局变化后 = `Δlayout`（本例 1200） |
| 后果 | **高** | 指针静默落到错误元素（`mousemove`/依赖 box 的 `click`）、提取/评分按旧布局、离线数据无法复原 |
| 可诊断性 | **低** | 文档未标空间、字段无空间标记、两命令给出矛盾值且都"合法" |
| 与仓库门禁冲突 | **是** | 违反 AGENTS.md "No silent failures" 与 "check failure modes, not just happy path" |

综合：**High / 高频**。它不是"数值略有出入"，而是"三套坐标系 × 两个时间点（实时 vs 首次 capture）"叠加。

### 9.6 已确认的附加副作用（同一 capture 路径，修复前）

`compute()`（`pulsar-browser 4.11.30: js/__pulsar_utils__.js:1510-1567`）在首次 capture 时除了算 `vi`，还会：

1. `window.scrollTo(0, 0)` —— **实测 `scrollY` 800 → 0，且不恢复**；读一次 htmlsnapshot，页面就回到顶部，后续 `get box`/`mousemove`/`snapshot -v` 的视口基准全部改变。**✅ 基库工作区已修（§12），接线后生效（§0.1.1）：`compute()` 不再调用它。**
2. `window.stop()` —— 中止在途资源加载（懒加载/长轮询页面可能因此拿不到内容）。**✅ 基库工作区已修（§12），接线后生效。**
3. `generateMetadata()` 注入 `<input type="hidden" id="PulsarMetaInformation">`，并在 `body` 上加 `data-error="0"` —— 实测两个 DOM 变更都真实存在，可能影响页面的 CSS/选择器/自身快照。**→ 已由注解拆分文档（原 §13）的第二批修改改为"仅序列化输出"（基库工作区，接线后生效），活 DOM 不再出现。**
4. `compute()` 是**一次性**的：`viDataStatusJs()` 只在 `_viDataComputed !== true` 时调用它（`browser4-core/browser4-browser/.../Browser4WebDriverScripts.kt:646-654`），`compute()` 自身还有早退守卫（`v4.11.30` 用 `body[data-error]`，`__pulsar_utils__.js:1520-1523`；工作区换成内存 `_computeDone`，`:1582-1586`）→ 后续 capture 直接复用旧 `vi`。**（仍未变，M14）**

实测证据（修复前）：布局改造后再执行 `htmlsnapshot`，`vi` 仍是 `0 1200`（旧布局），而同刻 `snapshot --boxes` 已是 `16,16`——同一个会话、同一个元素，两套数字相差 1184px。

### 9.7 修复建议（按优先级，含最新状态）

> **待办已收敛**：下表是分析阶段的修复建议（2026-11-09），现由 [`20261110-mouse-pointer-todo.md`](20261110-mouse-pointer-todo.md) 统一跟踪：
> `vi` 绝对化与 `compute()` 去副作用 → 已完成（§12）；`vi` 重算 → P3-1；`get box` 解耦 → P2-3；空间字段 → P2-1；一致性测试 → P3-2；`--viewport-boxes` → P3-7。

### 9.8 建议决策

- **ACCEPT**，但拆成两条独立改动，避免一次大改：
  1. **契约/文档 + `vi` 绝对化**（低风险，先做）；
  2. **`vi` 重算 + `compute()` 恢复滚动**（行为变更，需配套三空间测试与真实页面验证）。
- 在修好之前，对外建议统一写法：**鼠标/视口相关一律用 `getBoundingClientRect()`（并重读 `scrollY`），不要用 `htmlsnapshot` 的 box，也不要把 `snapshot [box]` 直接喂 `mousemove`**。
- 置信度：`vi` 的 body 基准、首次 capture 滚顶、`vi` 冻结三项——**高**（源码 + 实测双重证据）；消费方的实际业务损失——**中**（取决于调用方是否混用，未统计线上使用）。

---

## 10. 坐标系速查：文档坐标 vs 视口坐标（附消费方识别矩阵）

### 10.1 定义与换算

```
 文档（document / page / absolute）                视口（viewport / client）
 ┌───────────────────────────────────┐  scrollY  ┌───────────────────────┐
 │ (0,0) 文档原点                     │ ────────▶ │ (0,0) 布局视口左上角   │
 │   ┌─────────────────────────────┐ │           │   ┌─────────────────┐ │
 │   │ 元素绝对 y = D              │ │           │   │ 元素视口 y = D−S │ │
 │   └─────────────────────────────┘ │           │   └─────────────────┘ │
 └───────────────────────────────────┘           └───────────────────────┘
     vi（2026-11-10 起）= D；修复前 body 相对 = D − B（B = body 边框盒绝对 y）
```

- **视口坐标（client）**：原点 = **布局视口**左上角（`window.innerWidth × innerHeight` 描述的区域；滚动条在视口外；pinch-zoom 时视觉视口另算）。`getBoundingClientRect()`、`event.clientX/clientY`、`document.elementFromPoint()`、CDP `Input.dispatchMouseEvent` 都用它。
- **文档坐标（document / page / absolute）**：原点 = 文档左上角（内容滚动原点）。`event.pageX/pageY`、`window.scrollY` 描述的位置、CDP `DOMSnapshot` 布局 bounds、截图 clip、**`vi`（修复后）**都用它。
- **body 相对（修复前的 `vi`）**：`vi = 文档坐标 − body 边框盒偏移`。不是通用 Web 概念，是旧 `dom_rect.js` 的实现细节；**2026-11-10 起已移除**（§12）。旧数据/旧版基库仍按此空间解释。
- 换算：`doc = client + scroll`；`client = doc − scroll`；`bodyRel = doc − bodyOffset`（仅旧 `vi`）。三者单位都是 **CSS 像素（= CDP 的 dip）**；DPR / 页面 zoom 影响物理像素，不影响这三套数值。
- 易混点：视觉视口 ≠ 布局视口（`visualViewport.offsetTop` 只在 pinch-zoom 时非零）；`document.scrollingElement` 在 quirks 模式下可能是 `body`；iframe 内元素的坐标相对 **iframe 自己的视口**，跨 frame 需叠加 frame 偏移。

### 10.2 各输出的坐标空间（按来源判别）

| API / 输出 | 空间 | 依据 |
|---|---|---|
| `getBoundingClientRect()` / `clientX,Y` / `elementFromPoint` | 视口 | Web 标准 |
| CDP `Input.dispatchMouseEvent` 的 `x/y` | 视口 | `cdp-protocol/browser_protocol.json` 中 `Input` 域 `x`/`y` 字段：*"X coordinate of the event relative to the main frame's viewport in CSS pixels"*（本版文件在 13427-13432 / 13597-13602；**该书是生成文件，引行号会随 CDP 版本漂移，建议按"域.参数名"引用**） |
| `get box`（`bounding_box` → `DOM.getBoxModel`） | 视口 | 实测（协议未明文）：`scrollY=3110` 时返回 `y=430`，与 `getBoundingClientRect` 一致 |
| `snapshot --boxes` 的 `[box=…]` | 文档 | `DOMSnapshot.LayoutTreeSnapshot.bounds` = "absolute position bounding box"（`browser_protocol.json:9829`）；`-v N` 切片用 `scrollY + i*viewportHeight` 的文档区间（`pulsar-browser 4.11.30: chrome/protocol/PageHandler.kt:96-120`） |
| 截图 `screenshot -v N` 的 clip | 文档（按用法判定） | 代码用 `RectD(0, actualScrollY, w, h)`（`browser4-core/.../Browser4WebDriver.kt:2759-2761`）直接交给 CDP clip（dip）；能这样抓"第 N 屏"说明 clip 是页面坐标 |
| `htmlsnapshot` 的 `vi` / `interactiveElements[].box` / X-SQL `box=` | **文档**（仅"基库工作区"，接线后）；接线前 body 相对；只重建 bundle 则为**视口**（§0.1.1） | 基库工作区：`getDocumentRect = client + scroll`，`NodeOps.getRect` 使用它（§12）；修复前证据见 §1.1 |
| `mousemove` 期望的输入 | 视口 | 同上 CDP |

### 10.3 当前消费方能否准确识别？（结论：分三层，只有第一层能）

| 层次 | 能否识别 | 说明 |
|---|---|---|
| **引擎内部（按构造）** | **能** | 每个 API 固定一种空间，并有三处显式补偿：① `-v N` 切片把 `scrollY` 加回文档区间（`PageHandler.kt:96-120`）；② 视口截图 clip 写成 `(0, scrollY, w, h)`（`Browser4WebDriver.kt:2759-2761`）；③ 元素截图先 `scrollToTop` 再用 `getBoxModel` 的视口坐标当文档坐标（`ScreenshotHandler.calculateNodeClip`，基础库 4.11.30）。**代价**：③ 会改页面滚动位置 |
| **仓库内跨模块消费方** | **部分能 / 多数靠假设** | 能：`screenshotViewport`（+scrollY）、`ViewportRanges`（文档区间）、`nScreen`（基库工作区改为文档坐标，接线后生效）。仍是"假设"：`MCPToolController` inspect 用 `vi.y` 做 `<100` / `100..800` / `>2500` 分档（`MCPToolController.kt:2582-2598`）——接线后才会**与实现一致**，且阈值仍未重新校准；`HTMLSnapshotToolExecutor` 把 `vi` 原样当 `"box"` 输出（`:1547-1548`，接线后值才是文档坐标，输出仍无空间标记）。只做同空间比较：`PageSummaryIndexService`（冻结值仍不可救） |
| **外部消费方（CLI/JSON/HTML/X-SQL/agent）** | **不能** | 所有产物都是裸数字：`snapshot` 输出 `[box=16,16,100,40]`、capture JSON `"box":"0 1200 100 40"`、`get box` `{"x":16,"y":-656}`、X-SQL `box=<x> <y> <w> <h>`。没有 `boxSpace` 字段、没有 CLI 前缀、没有 JSON schema 标注；文档一律只写 "CSS pixels"。**接线后 `vi` 与 `snapshot [box]` 同空间，但两者都不是 `mousemove` 需要的视口坐标，仍需自行减 `scrollY`** |

**为什么"数值本身"判别不了**：当 `scrollY=0` 时 `doc == client`；修复前 body 无 margin 时 `vi == doc`。在"页面在顶部 + 无 body 偏移"这一最常见初始状态下数字**完全重合**，所以 demo、快照比对、人工抽查都会通过；一旦滚动或布局变化，差异才出现，而那时已经没有任何字段能说明差异来自哪种空间。修复后只剩"文档 vs 视口"两类，但 `get box` 与其余输出之间的差异仍然静默。

**运行时启发式也不可靠**：

- "y 落在 `[0, innerHeight]` 就是视口坐标" → 文档坐标在页面顶部时同样成立；
- "y 很大就是文档坐标" → 只对文档坐标成立，对冻结的 `vi`（旧布局）不成立；
- "和 `scrollY` 相加/相减能对齐就是同一套" → 需要先知道 `scrollY`，而 snapshot 头部只给 viewport 索引，不给 `scrollY` 数值。

### 10.4 让消费方"能识别"的最小改动

> **待办已收敛**：下列最小改动由 [`20261110-mouse-pointer-todo.md`](20261110-mouse-pointer-todo.md) 跟踪 —— 输出带空间 P2-1；值类型化 `Box(space,…)` P2-5；文档判别表 P2-2；空间一致性测试 P3-2。

---

## 11. 方案评估：统一到单一坐标系 + 辅助信息，能否解决所有不一致并保持语义？

### 11.1 方案假设与判定标准

**被评估方案（A）**：所有坐标输出统一到**文档坐标**；同时输出辅助信息（`scrollX/scrollY`、`capturedAt`、`layoutVersion`、viewport 尺寸等），让外部调用者自行换算成视口/屏幕坐标。

判定标准四条：

1. **空间歧义消除**：同一页面任意两个输出的坐标可直接比较；
2. **时间正确性**：测量得到的坐标在真正执行动作时仍然有效；
3. **语义保持**：现有字段、消费者、文档、测试不被悄悄改变；
4. **边界覆盖**：iframe / 缩放 / 固定元素 / 离线产物仍然可用。

### 11.2 覆盖度矩阵

| 问题类别 | A 能否解决 | 依据（本文此前证据） |
|---|---|---|
| 三套空间互相矛盾（`get box` / `[box]` / `vi`） | ✅ 能（`vi` 部分已在基库工作区落地，接线后生效，§12） | 统一后同一元素只有一个数字；接线后 `snapshot [box]` 与 `vi` 是同一文档空间，剩 `get box` 的视口语义（应保留，不应"统一掉"） |
| 跨工具/离线数据比较 | ✅ 能（大改善） | 文档坐标与滚动无关；`vi` 不再需要"首次 capture 的滚动位置"（§12） |
| 坐标数值随时间变旧（`vi` 冻结、SPA、懒加载） | ❌ 不能 | 冻结是"算一次不重算"（§9.6），旧布局的文档坐标依旧旧；空间统一不触发重算 |
| 测量与动作之间的竞态 | ❌ 不能 | 外部用"测量时的 `scrollY`"换算，动作时 `scroll` 可能已变；且 `click`/`hover` 自带 `scrollIntoView` 会再次改 `scroll`（§9.1、M3） |
| 测量本身的副作用（capture 滚顶、`get box` 滚动、元素截图 `scrollToTop`） | ⚠️ **部分** | `compute()` 的滚顶与 `stop()` **已去掉（§12）**；元素截图的 `scrollToTop`、`get box` 的 `scrollIntoViewIfNeeded` 仍在 |
| 固定 / sticky 元素 | ⚠️ 需额外定义 | 固定元素的视口位置恒定 → 文档坐标 = `scrollY + 常量`，随滚动变化；"文档坐标"对它们只有"测量时"的含义（见 §12.3） |
| iframe / pinch-zoom / 视觉视口 / DPR | ⚠️ 需额外信息 | 文档坐标是**每个 frame 各自**的；跨 frame 需偏移链；`visualViewport` 不是布局视口的平移 |
| 旧契约与现有消费者兼容 | ❌ 不能自动保证 | 外部 `vi` 契约已在 §12 变更（数值 +8–16px）；`get box` 若切换为文档坐标同样会变，且牵动截图补偿与容差 |

### 11.3 能解决的部分（为什么仍然值得做；✅ 标注已实施）

- ✅ **消除"同一元素三个数字"**（`vi` 部分已在基库工作区落地，接线后生效，§12）：`16 / 1200 / −656` 中的 `1200` 会变成 `1216`（文档坐标）；剩余差异是"文档 vs 视口"，属于有意义的两种语义，而不是三套混乱。
- ✅ **离线产物可比**：文档坐标与滚动无关，导出的 HTML/JSON、历史快照、跨会话对比都能直接对齐；视口坐标做不到（同一元素在不同滚动位置数值不同）。
- **简化下游逻辑**：`inspect` 的 y 分档、`snapshot diff` 的 ±15px 容差、截图 clip 都不需要再"猜空间"；阈值仍建议按新空间复核。
- **选文档坐标是正确基准**：`snapshot [box]`、`DOMSnapshot`、截图 clip 已经是文档坐标；选视口坐标会把"滚动位置"烧进产物，比现状更糟。

### 11.4 解决不了的部分（各自有独立根因）

1. **陈旧（时间问题）**：`vi` 在每个文档首次 `compute()` 后冻结（§9.6 实测：布局改造后仍是 `0 1200`）。统一空间不会让它重算；必须有"布局版本 + 失效检测/重算"。
2. **竞态（时序问题）**：实测 `get box` 返回 `−656` 后，命令自己把页面滚到了顶部（§9.1）；用测量时 scroll 换算的调用方，在动作时已经错。更根本的是：**外部调用者拿不到"派发动作那一刻"的滚动位置**，而 `click`/`hover` 还会在派发前 `scrollIntoView`。可靠做法只有两种——用 `ref/selector` 让引擎重新定位，或让动作 API 接受文档坐标并在**派发前原子换算**。
3. **副作用（状态问题）**：`compute()` 的 `scrollTo(0,0)`+`window.stop()` **已在基库工作区消除**（§12，接线后生效，§0.1.1）；但元素截图的 `scrollToTop`、`get box` 的 `scrollIntoViewIfNeeded` 仍在，辅助信息里的 `scrollAtMeasure` 仍需 `scrolledDuringMeasure` 才能定义。
4. **语义边界（几何问题）**：`position:fixed`/sticky（视口恒定 → 文档坐标随滚动变化）、CSS `transform`/动画、内部滚动容器（`overflow:auto`）、iframe（每 frame 一套文档坐标）。这些不能用"一种坐标 + 几个标量"完全表达。
5. **兼容性与契约（工程问题）**：见 11.5。

### 11.5 "原有语义正确"影响清单

> 结论：**直接替换坐标空间会改变契约**；要做到"语义不变"，只能新增字段并标注空间，分版本迁移。`vi` 的切换已在基库工作区落地（未接线，§0.1.1）——它原本就是"实现与文档都不一致"的 bug，但仍会改变外部数值。

| 字段 / 消费方 | 当前空间 | 切换为文档坐标后的影响 | 必要的配套改动 |
|---|---|---|---|
| `snapshot --boxes` `[box]` | 文档 | 不变（本来就要保持文档：`snapshot_diff` 的 ±15px 容差按文档空间匹配，`snapshot_diff.rs:74-77, 472`） | 无，但需在输出里标注空间 |
| `get box`（`bounding_box`） | 视口 | **契约变更**：`ScreenshotHandler.calculateNodeClip` 依赖"先 `scrollToTop` 再取视口坐标"（基础库 4.11.30），切换后该补偿变成双重偏移；外部脚本按视口语义解析会全错 | 同步改截图路径；新增 `boxDocument` 而非改旧字段；CLI/文档版本化 |
| `vi` / `interactiveElements[].box` / X-SQL `box=` | **文档**（仅基库工作区；接线后生效。接线前 body 相对，只重建 bundle 则为视口） | ✅ 已在基库工作区切换：数值从 `D − B` 变为 `D`（默认页面 +8–16px，body 有偏移时更多）；`inspect` 的阈值假设届时才成立，但分数会变；离线旧数据不可直接与新数据比较 | 输出加 `boxSpace`/`schemaVersion`；文档标注；旧数据按 `boxSpace:"body"` 解释 |
| `mousemove` 输入 | 视口（CDP 决定） | 不能改成文档坐标（协议只收视口）；只能加 `space` 参数由引擎换算 | 引擎在派发前换算；不要要求调用方自己减 scroll |
| 视口语义查询（"当前屏幕上有什么"） | 视口 | 文档坐标无法替代；`-v N` 切片、`processingViewport`、`hiddenTopHeight` 都是视口语义 | 保留视口语义字段，不能"统一掉" |
| 测试 / 夹具 | 混合 | `PageViewDeduperTest` 的 box 会被 strip（不受影响）；`BrowserTabToolExecutorTest:1515` mock 了 `boundingBox`；文档示例中的 `box=` 数值需重算（接线后 `vi` 会变） | 空间一致性测试 + 重新基线化示例 |

### 11.6 辅助信息的最小充分集

| 字段 | 用途 | 必需性 | 注意 |
|---|---|---|---|
| `space`（`document`） | 契约版本与旧数据区分 | 必需 | 即使"单一空间"也要保留，便于跨版本解析 |
| `scrollX/scrollY`（测量时） | 文档→视口换算 | 必需 | 只对"测量那一刻"有效 |
| `scrollBefore/scrollAfter` + `scrolledDuringMeasure` | 识别测量副作用 | 必需 | 首次 capture 的滚顶已去除（§12）；仍需覆盖元素截图与 `get box` 的滚动 |
| `capturedAt` + `layoutVersion`（DOM 版本/高度哈希） | 判新旧、判失效 | 必需 | 解决 `vi` 冻结与 SPA 场景 |
| `viewportWidth/Height` | 视口相关判断（是否在当前屏） | 必需 | 与 `scrollY` 一起才能还原视口 |
| `devicePixelRatio` / `pageZoom` / `visualViewport` 偏移 | dip 与视觉视口 | 条件必需 | 仅在缩放/移动端场景 |
| `framePath` / `frameOffsetX/Y` | iframe 内元素 | 条件必需 | 或直接声明"仅主框架" |
| `fixed/sticky` 标记 | 解释文档坐标为何随滚动变化 | 建议 | 固定元素只能给"测量时"的文档坐标 |
| `elementRef` / selector | 动作时重新定位 | **强烈建议** | 比任何坐标换算都可靠 |

**关键判断**：上表足以让外部**解释**和**比较**坐标，但不足以让外部**安全驱动输入**——后者需要"引擎在派发前用最新布局重新换算"，因为辅助信息天然滞后。

### 11.7 推荐组合（A + 标注 + 引擎内换算）

> **待办已收敛**：下列推荐组合由 [`20261110-mouse-pointer-todo.md`](20261110-mouse-pointer-todo.md) 跟踪 —— 输出侧空间字段 P2-1；输入侧 `space` P2-6；`vi` 失效重算 P3-1；`get box` 解耦 P2-3；版本化/迁移文档 P2-2；一致性测试 P3-2。

### 11.8 结论

| 判定 | 结论 |
|---|---|
| 能否解决**所有**不一致问题 | **不能**。A 只完全覆盖 2 类（空间歧义、跨工具比较），2 类需额外定义（固定/sticky、iframe/zoom），4 类不能（陈旧、测量-动作竞态、测量副作用、旧契约兼容）；每一类都有独立根因，必须分别修 |
| 能否**保证原有语义正确** | **不能自动保证**。`vi` 的切换已在 §12 落地（外部数值 +8–16px）；`get box` 若再切（视口→文档）会牵动截图补偿、inspect 阈值、diff 容差、外部脚本与文档示例。语义保持需要"新增字段 + 版本化迁移"，而不是直接替换旧字段 |
| 方案定位 | **必要但不充分**：作为"输出契约"的正确方向（文档坐标为基准），必须叠加"引擎内原子换算 + 布局版本 + 去副作用 + 版本化迁移"，才能同时满足一致性与语义正确 |
| 最小可行路径 | 先加 `boxSpace` + `layoutVersion`（零行为变更）→ 再把 `vi` 绝对化并修冻结/滚顶 → 最后让动作 API 接受文档坐标并在派发前换算 |
| **实施进度（2026-11-11 完成）** | `vi` 绝对化 ✅ 已接线（bundle 内 7050 B `dom_rect.js`）；`compute()` 去滚顶/去 `stop` ✅；`layoutVersion` 布局失效重算 ✅；`boxSpace`/`schemaVersion`/`scrollTopAtCapture` 输出 ✅；动作 API 的 `space`/`steps`/`durationMillis`（`mouseMoveAt`）✅；`get box` 解耦 + `boxSpace:"viewport"` ✅。证据见 §12.8，逐项回填见 `20261110-mouse-pointer-todo.md` |

---

## 12. 变更记录：基库 `vi` 简化 + `compute()` 去掉滚顶/`stop()`（基库工作区已实现，未接线）

> 实施位置：**基库仓库** `D:\workspace\Browser4\browser4base`（`4.11.31-SNAPSHOT`，branch `main`），并同步了 Browser4 仓库内 vendored 的 `dom_rect.js`。
> **落地状态（2026-11-10 复查）**：基库 6 个文件全部是**未提交的工作区修改**（`git status` 为 `M`），无 commit / tag；未 `mvn install`，本地仓库没有 `4.11.31-SNAPSHOT` 产物；Browser4 侧 vendored `dom_rect.js` 与相关 KDoc/测试同样未提交。
> 本节记录改了什么、为什么、以及对 §1–§11 结论的影响；"运行时到底生效了什么"见 §12.7。

### 12.1 改动清单

| 文件（基库 `browser4base`，4.11.31-SNAPSHOT） | 改动 |
|---|---|
| `pulsar-core/pulsar-browser/src/main/resources/js/dom_rect.js` | `getElementClientRect` / `getTextNodeClientRect` 改为**纯视口坐标**（直接返回 `getBoundingClientRect()`，删除 `bodyRect` 缓存与减法）；新增 `getDocumentRect(node)` = client rect + `scrollX/scrollY`；`queryClientRect` 改为走 `getClientRect`，保持"client"语义 |
| `.../js/node_ops.js` | `NodeOps.getRect` 改用 `__pulsar_utils__.getDocumentRect()` → `vi`、`tvN`、`nScreen`、可见性/溢出判断全部落在**文档坐标** |
| `.../js/__pulsar_utils__.js` | `compute()` 删除 `window.scrollTo(0, 0)` 与 `window.stop()`；注释说明 vi 已与滚动位置无关 |
| `.../src/test/js/dom_rect.test.js` | 新增 8 个用例：client rect 不随滚动、document rect 加滚动量、`false`/`null` 透传、`NodeOps.getRect` 文档坐标、`queryClientRect` 保持视口、`compute()` 不滚动/不 `stop()`；`beforeAll` 补载 `dom_style.js`（`compute()` 需要 `__pulsar_utils__.getComputedStyle`） |
| Browser4：`browser4-core/browser4-browser/src/main/resources/js/dom_rect.js` | 与基库**逐字节同步**（SHA256 相同，7050 B；vendored 覆盖必须跟随新契约，否则升级基库后 `NodeOps.getRect` 会调用不存在的 `getDocumentRect`） |
| 基库：`pulsar-core/pulsar-protocol/.../emulator/impl/InteractiveBrowserEmulator.kt` | 仅注释：说明 `compute()` 的目的从"向活 DOM 注入 `vi`"改为"供序列化器在导出 HTML 时写出 `vi`"（2026-11-10 复查补记，原清单遗漏） |

### 12.2 行为变化（对 §1–§11 结论的修订）

> 下表的"**已改**"= 基库工作区已改、**尚未接线**（§0.1.1）；接线前运行时仍是左列行为。

| 结论 | 修订后 |
|---|---|
| `vi` 是相对 body 的坐标（§1.1、I1） | **已改（基库工作区）**：`vi` 现在是相对文档的绝对坐标（`client + scroll`），不再受 body margin/偏移影响；数值通常 +8–16px（页面有 body 偏移时更多） |
| 首次 capture 强制滚顶、`window.stop()`（§9.6、M15、I3） | **已改（基库工作区）**：`compute()` 不再改变滚动位置、不再中止资源加载；页面状态保持不变 |
| `nScreen`/`__updateStat` 的"首屏"依赖调用时是否滚顶 | **已改（基库工作区）**：`nScreen` 基于文档坐标，任何滚动位置下语义一致 |
| `queryClientRect` 名不副实（返回 body 相对坐标） | **已改（基库工作区）**：返回真正的视口坐标 |
| 消费方对 `vi` 的假设 | `MCPToolController` inspect 的绝对 y 阈值（`<100`/`100..800`/`>2500`）将**与实现一致**（此前是"误打误撞"）；`PageSummaryIndexService` 的相对比较不受影响；`Level1FeatureCalculator`（`vi` → TOP/LEFT）将拿到真正的页面坐标 |
| `vi` 仍冻结、`get box` 仍可能滚动、输出仍无空间标记（I2/I4/I5） | **未变**：本次只做"简化 vi + 去副作用"，陈旧与竞态要另行修复（§9.7 P1） |

### 12.3 新实现的语义边界与已知限制

1. **`position:fixed` / sticky**：`compute()` 不再强制滚顶后，fixed 元素的 `vi = client（常量）+ 首次 capture 时的 scroll`。也就是说同一个 fixed 元素在不同滚动位置首次 capture 会得到不同的 `vi`；"文档坐标"对 fixed 元素只有**测量时**的含义（这是文档坐标的固有属性，不是本次引入的 bug，但以前被"强制滚顶"掩盖了）。消费方不应假设 fixed 元素的 `vi` 与滚动无关；后续可在 capture 元数据里标 `fixed:1` 或约定 `vi` 为 measure-time。
2. **内部滚动容器**：`vi` 的文档 y = `window.scrollY + client`，容器自身的滚动已包含在 client 里；由于 `vi` 仍是"一文档一次"（M14 未修），容器滚动后再 capture 不会更新。
3. **iframe**：特征遍历跳过 iframe 子树（原有行为），`vi` 只覆盖主文档。
4. **测量时点**：`getDocumentRect` 每次调用读取当前 `scrollY`；`compute()` 是同步遍历，不会中途变化。多次 capture 之间若页面滚动，冻结的旧 `vi` 仍是首次测量值（与 M14 一致）。
5. **DOM 副作用**：本节改动范围只到"去滚顶/去 `stop`/简化 vi"；`compute()` 里的 `writeData()`/`generateMetadata()`/`lz`/`tp` 当时仍写活 DOM，**已在注解拆分文档（原 §13）的第二批修改中改为内存存储 + 序列化器输出**（两批改动同属一次接线，别只接 §12 而漏 §13）。
6. **旧数据迁移**：`vi` 数值整体 +bodyOffset（默认 8–16px），旧的离线 HTML/JSON 仍按"相对 body"解释；跨版本比较需要按版本或 `boxSpace` 区分。

### 12.4 验证

- **单元测试**：`cd pulsar-core/pulsar-browser/src/test/js && npx jest --ci` → **141 passed / 8 suites**（改动前 133 个用例，新增 8 个）；`compute` 用例用 spy 断言 `window.scrollTo`/`window.stop` 未被调用，`getDocumentRect` 用例断言 `client + scroll`。基线可复现：`v4.11.30` tag 上 8 个 suite 合计 **133** 个用例，当前工作区 143 个（§12 的 +8 与注解拆分的 +2）。
- **语法**：`node --check` 通过（基库与 Browser4 vendored 副本）。
- **编译**：基库 `mvnw.cmd -o -pl pulsar-core/pulsar-browser -am -DskipTests compile` → **exit 0**；`target/classes/js/{dom_rect,node_ops,__pulsar_utils__}.js` 与源码哈希一致。
- **覆盖生效（classpath 实测）**：`js/dom_rect.js` 在 bundle 的 `-cp "lib\*"` 下命中 `browser4-browser-*.jar`（**vendored 覆盖确实成立**），而 `js/node_ops.js` 仍命中 `pulsar-browser-4.11.30.jar`——即"半套生效"，详见 §12.7。
- **未做**：真实浏览器 e2e。原因：运行时脚本注入在 **isolated world**，CLI 的 `eval`（主 world）看不到 `__pulsar_utils__`，无法在线覆盖验证；且 Browser4 仍 pin 基库 `4.11.30`。可用的离线替代证据是 `ScriptLoader` 生成的 `report/browser/js/isolated-world.gen.js`（§12.7）；真实浏览器验证可在基库 `pulsar-tests/pulsar-e2e-tests` 的 `PulsarWebDriverInjectedJSTests.kt` 里补（同一基座已有 `getAnnotatedHTML` 相关用例）。

### 12.5 接线顺序与验证步骤

1. **先确认现状**：看当前 runtime bundle 的 `lib/browser4-browser-*.jar` 里 `js/dom_rect.js` 是否含 `getDocumentRect`。2026-11-10 复查时它是**旧文件**（6165 B、含 `bodyRect`），构建时间 2026-11-08，早于 vendored 修改（2026-11-10 12:25）——也就是说当时连 vendored 覆盖都还没进 bundle。
2. **基库安装**：`mvnw.cmd -o -pl pulsar-core/pulsar-browser -am -DskipTests install`，确认本地仓库出现 `pulsar-browser-4.11.31-SNAPSHOT.jar`（或正式版）。
3. **Browser4 升级依赖**：`browser4-dependencies/pom.xml:587` 的 `browser4-base.version` → `4.11.31-SNAPSHOT`（或正式版），重建 runtime bundle（`--force-rebuild-bundle`）。
4. **确认注入了哪一版**（不启浏览器也能查）：`ScriptLoader` 会把最终脚本写到 `REPORT_DIR/browser/js/isolated-world.gen.js`（`DualWorldScriptLoader.reportScript()`），并在启动日志里打印 `Generated js: file://…`。该文件里应**同时**出现 `getDocumentRect` 与 `_computeDone`，且**不含** `bodyRect`、`window.stop()`。
5. **行为核对**（可滚动 fixture，如 `b4/mcp-tool-controller-mouse-fixture.html` 或自建 1200px spacer 页面）：
   - `htmlsnapshot` 前后 `eval "window.scrollY"` **不变**；
   - 同一元素的 `htmlsnapshot` box 与 `snapshot --boxes` 的 `[box]` **相等**（允许 1px 取整差）；
   - 滚到 `scrollY=800` 后重新打开/重算，box 仍为文档坐标、不随滚动变化；
   - 旧的 `body{margin:8px}` 页面：`vi.y` 比修复前大 8–16px。
6. **只做一半的自检**：如果重建了 bundle 但没升级基库，第 5 条会失败且 `vi` 变成**视口坐标**（滚动后数值变化）；如果升级了基库但 vendored 副本仍是旧文件，启动即 `getDocumentRect is not a function`。**vendored `dom_rect.js`、`node_ops.js`、`__pulsar_utils__.js` 三者必须同版本**（前两个在基库 jar，第三个由基库加载；Browser4 只覆盖 `dom_rect.js`）。

### 12.6 遗留与建议

> **待办已收敛**：[`20261110-mouse-pointer-todo.md`](20261110-mouse-pointer-todo.md) 已覆盖本节遗留项 —— 接线 P1-1…P1-4、`vi` 冻结 P3-1、`get box` 竞态 P2-3、空间标记 P2-1、inspect 阈值复核 P2-4。

### 12.7 复查（2026-11-10）：运行时到底生效了什么

本节是与兄弟项目 `browser4base` 对照后的实测结论，**修订 §0.1 原"`vi` 已绝对化"的说法**。

**证据 1：vendored 覆盖成立，但只覆盖"半套"。**
脚本由基库的 `DualWorldScriptLoader` / `ScriptLoader` 用 `ResourceLoader.readAllLines("js/…")` 加载，而 `ResourceLoader.getURLOrNull()` 只取**第一个**命中（`pulsar-common/.../ResourceLoader.kt:206-216`）。用 bundle 的真实 classpath（`java --class-path "…\lib\*"`）探测：

```text
dom_rect.js -> jar:…/lib/browser4-browser-4.16.4-SNAPSHOT.jar!/js/dom_rect.js
node_ops.js -> jar:…/lib/pulsar-browser-4.11.30.jar!/js/node_ops.js
```

即 Browser4 的 vendored `dom_rect.js` **确实盖住了**基库同名文件，而 `node_ops.js` 还是 4.11.30 的（它调 `__pulsar_utils__.getClientRect`，`node_ops.js:216`）。所以即便覆盖生效，`vi` 也只会变成**视口坐标**（新 `dom_rect.js` 的 `getElementClientRect` 是纯 `getBoundingClientRect()`）；`getDocumentRect` 在 4.11.30 运行时里**没有调用方**。

**证据 2：当前 bundle 里连新文件都还没有。**
`lib/browser4-browser-4.16.4-SNAPSHOT.jar`（构建于 2026-11-08）内的 `js/dom_rect.js` 仍是 6165 B 旧版（含 `bodyRect`、无 `getDocumentRect`）；最近一次运行（2026-11-10 17:22）生成的 `isolated-world.gen.js` 同样含 `bodyRect` 与 `window.stop()`，不含 `getDocumentRect`/`_computeDone`。所以截至复查，运行时行为与 4.11.30 基线一致，**vendored 修改尚未进入任何运行中的 bundle**。

**证据 3：基库侧实现与本文描述一致。**
`v4.11.30` 的 `compute()`（`__pulsar_utils__.js:1510-1530`）确实先 `scrollTo(0,0)` 再 `window.stop()`；工作区 `compute()`（`:1574-1626`）已无这两行、改用 `_computeDone`；`dom_rect.js` 的 `getElementClientRect` 由 `rect − bodyRect` 改为纯 client rect，并新增 `getDocumentRect`；`node_ops.js:216-222` 改调 `getDocumentRect`。

**结论**：`vi` 的"文档坐标"是**基库工作区的属性**，不是当前运行时的属性。在 §12.5 的接线（install → 升级 `browser4-base.version` → 重建 bundle）完成前，本文所有"现在 `vi` 是文档坐标"的表述都应读作"接线后将是"。后续复查请直接对照 §0.1.1 的三态表，不要只看基库工作区。

---

### 12.8 接线复核（2026-11-11）：运行时实测

> 对象：本仓库 `4.16.5-SNAPSHOT` + 基库 `4.11.31-SNAPSHOT`（本地 install）+ 重建后的 runtime bundle。方法：注入脚本取证 + CLI 真浏览器（headless Chrome）+ Rust e2e。

**A. 接线状态（代码/构建）**

| 检查 | 结果 |
|---|---|
| 基库 install | `pulsar-browser-4.11.31-SNAPSHOT.jar`（1 793 869 B）+ 全部基库模块与 `pulsar-bom` 已 install |
| Browser4 pin | `browser4-dependencies/pom.xml`：`browser4-base.version=4.11.31-SNAPSHOT` |
| bundle 内 vendored 脚本 | `browser4-browser-4.16.5-SNAPSHOT.jar!/js/dom_rect.js` = **7050 B**，含 `getDocumentRect`、不含 `bodyRect`（SHA256 `41bf2ea3…0f2a0a`，与基库源文件一致） |
| bundle 内基库脚本 | `pulsar-browser-4.11.31-SNAPSHOT.jar!/js/node_ops.js` 调 `getDocumentRect`；`__pulsar_utils__.js` 含 `_computeDone`/`_layoutMutationCount`/`box-space="document"` |
| 注入脚本实读 | `isolated-world.gen.js`（95 410 B）含 `getDocumentRect`/`_computeDone`/`_layoutMutationCount`/`box-space`，**不含** `bodyRect`；`window.stop` 仅出现在注释里 |
| 半套接线防呆 | `RuntimeScriptContract.verify()`（驱动创建时）校验三份脚本同代；单测 7 个通过；混合/陈旧 bundle 直接报错而不是静默降级 |

**B. 行为核对（CLI + 真实浏览器，夹具 `b4/mcp-tool-controller-mouse-fixture.html` 等）**

| 验收项 | 实测 |
|---|---|
| ① capture 不动滚动 | `scrollY=800` → `htmlsnapshot capture` → `scrollY=800`（`scrollTopAtCapture=800`） |
| ② capture box == snapshot box | `#click-target`：capture `vi=29 57 93 33`，`snapshot --boxes` `[box=29,57,93,33]`（完全一致） |
| ③ 滚动后重算仍为文档坐标 | 滚到 800 再 capture，`#click-target` 仍是 `29 57 93 33`（不随滚动变化） |
| ④ body margin 页 | `body{margin:8px}` + 1200px spacer：`vi.y=1208`，与 `getBoundingClientRect().top + scrollY = 1208` 完全一致（修复前 body 相对会少 8–16px） |
| `get box` 空间 | `{"x":29,"y":57,...,"boxSpace":"viewport"}`；元素已可见时不滚动，不可见时滚动后等待落定再测 |
| `--space document` | 滚到 `scrollY=800` 后 `mousemove 29 1342 --space document` → 页面收到 `clientY=542`（= 1342 − 800）：引擎在派发前换算 |
| `--steps/--duration` | `mousemove 10 10 --steps 3 --duration 60` → 落点精确 `(10,10)`，退出码 0 |
| `--allow-outside` | 越界时不打印提示；不带该 flag 时打印 `ℹ️  (640, 1200) is outside the 1280×900 viewport …`，两种都 exit 0（提示不影响派发） |
| `snapshot --viewport-boxes` | `#hover-zone` 默认 `[box=29,1342,204,64]`（文档）；加 flag 后 `[box=29,542,204,64]`（= 1342 − 800） |
| capture 元数据 | `boxSpace:"document"`、`schemaVersion:2`、`scrollTopAtCapture` 均存在 |

**C. inspect 阈值复核（P2-4）**

接线后 `vi.y` = 文档绝对 y（比旧 body 相对值大 8–16px）。真实页面采样（`htmlsnapshot capture` 的 `interactiveElements[].box`）：

| 页面 | 交互元素数 | `y<100` | `100..800` | `y>2500` | max y |
|---|---|---|---|---|---|
| `example.com` | 1 | 0 | 0（唯一链接 y=806，刚好越界） | 0 | 806 |
| `news.ycombinator.com` | 100 | 8（顶部导航） | 75（故事链接） | 0 | 971 |
| `www.wikipedia.org` | 13 | 0 | 13（门户链接） | 0 | 547 |

结论：`<100 / 100..800 / >2500` 三档在文档坐标下仍能正确区分"页头 / 正文 / 页脚"（HN 顶部导航落 `<100`、正文落 `100..800`）；无需重标定。唯一边界现象是 `example.com` 的单链接 y=806 刚好落在正文带之外（不会被打分，也不会被罚分），属阈值固有边界，已在 `20261110-mouse-pointer-todo.md` P2-4 记录。

**D. 真实浏览器 e2e（Rust，本仓库）**

| 场景 | 覆盖 | 结果 |
|---|---|---|
| `test_e2e_mouse_coordinate_spaces` | T1：`(0,0)`、越界 `(640,1200)`→`mouseleave` 且事件坐标被夹到视口内、负坐标退出码 0、滚动后坐标不偏移、非数字坐标不派发 | ✅ 30.4s |
| `test_e2e_mouse_pointer_state` | T2：`mousemove → hover → mousedown` 按在元素上；`mousemove → goto → mousedown` 不回放旧坐标（新页 `moves=0`） | ✅ 32.0s |
| `test_e2e_htmlsnapshot_box_space` | T3：capture 元数据空间标记、capture 不动滚动、`vi == snapshot box`、`get box == vi − scrollY`、布局变化后 `vi` 刷新（+441px） | ✅ 35.8s |

基库侧同类用例（`PulsarWebDriverInjectedJSTests#testViBoxesAreDocumentAbsoluteAndRefreshOnLayoutChange`）已编译通过；其运行时依赖基库 e2e 基座（真实浏览器 + 本地站点），作为独立 e2e 模块的回归保留。

**E. 其它门禁**

- 基库 Jest：8 suites / **147 passed**（`compute()` 重算、布局指纹、无 MutationObserver 降级、`box-space` 序列化）。
- Browser4 单测：`RuntimeScriptContractTest` 7/7；`AgenticCliRunnerTest` 97/97；`HTMLSnapshotToolExecutorTest` 等 100/100；`ToolDocGeneratorTest` + `McpToolAliasParityTest` 8/8（`docs/mcp-tools.md`/`.json` 147 工具，无漂移）。
- Rust CLI 单测：2129/2129（其中 `daemon::tests::wmi_spawned_backend_survives_a_hostile_process_tree_kill` 依赖 `BROWSER4_WINDOWS_BACKEND_SPAWN=wmi` 的宿主环境，属既有环境敏感用例，与本轮改动无关；置该变量后通过）。

---

## 13. 注解序列化（已拆出）

> 注解（`#PulsarScriptSection` / `#PulsarMetaInformation` / `body[data-error]` / `lz` / `tp`）的"活 DOM 零污染"改动与本文的鼠标/坐标主题无关，原文 §13.1–§13.7 已完整迁至
> [`20261109-pulsar-annotation-dom-pollution.md`](20261109-pulsar-annotation-dom-pollution.md)。
> 其接线与鼠标/坐标的接线是同一次（基库 `4.11.31-SNAPSHOT` → 重建 bundle），但两边的问题与验证互不依赖。

---

## 14. 修订记录

| 日期 | 变更 |
|---|---|
| 2026-11-09 | 初版：`mousemove`/`hover` 的行为与坐标空间、问题清单（M1–M13、D1–D5、T1–T2）、四个动作的正确命令、实测记录。 |
| 2026-11-10 | 新增 M14/M15/T3 与 §9（三套盒坐标副作用评估）、§10（坐标系速查与消费方识别）、§11（单一坐标系方案评估）、§12（基库修复记录与验证）。基库实现：`vi` 文档坐标、`compute()` 去滚顶/去 `window.stop()`、`getDocumentRect`、8 个 Jest 用例；Browser4 同步 vendored `dom_rect.js`。§0.1 增加修复状态；§1.1/§9/§10/§11 已按"修复前/修复后"标注。 |
| 2026-11-10（第二批） | §13：注解改为序列化注入——`#PulsarScriptSection`、`#PulsarMetaInformation`、`body[data-error]`、`lz`、`tp` 不再写入活 DOM；script/input/`data-error` 只由 `serializeAnnotatedHTML()` 输出到导出 HTML；`lz` 仍由序列化器输出但 htmlsnapshot 解析时剥离，`tp` 不序列化（保持原剥离策略）；Jest 143 passed，Browser4 `HTMLSnapshotToolExecutorTest` 48 passed。 |
| 2026-11-10（复查修订） | 与兄弟项目 `browser4base` 对照复查后修订：①新增 §0.1.1"运行时三态"与 §12.7（classpath 探测 + `isolated-world.gen.js` 证据）——**原"`vi` 已绝对化"不成立**，当前 bundle 里仍是旧 `dom_rect.js`；②补 §0.1.2 的 I1–I5 编号定义（此前 12 处引用但从未定义）；③补 §2 的 M14/M15 正文条目（此前只在 §0 总表出现）；④修正 §13.2"原始序列化"术语（`pageSource` 实为注解序列化器输出）与 `HtmlIntegrityChecker` 路径；⑤修正行号漂移（`commands.rs:1609-1610`、`main.rs:32038-32053`、`HTMLSnapshotToolExecutor.kt:1547-1548`、`browser_protocol.json` 改按名字引用）；⑥补"落地状态=未提交"、`bodyRect` 惰性缓存从不失效、M9 抖动区间、M12 与 KDoc 冲突、`boxSpace` 枚举含 `body`、T3 验收前置条件等。 |

> 阅读建议：要了解**当前**（4.11.30 基线 + 未接线的基库改动）行为，先看 §0.1.1 的三态表，再看 §1.1、§10.2、§12.7 与 §13；要了解修复**接通后**的行为，看 §12；要了解问题证据与历史实测，看 §9（已标注为修复前）。§13（注解序列化）已拆到 `20261109-pulsar-annotation-dom-pollution.md`，与鼠标主题无关。
