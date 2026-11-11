# Issues: 盒坐标三套空间不一致 —— `vi` 相对 body、`snapshot [box]` 绝对文档、`get box` 视口（附首次 capture 副作用）

> **Source:** 2026-11-09 鼠标指针命令调研 + 本机 CLI 实测（dev bundle `4.16.4-SNAPSHOT`）+ 源码核对
> **Date:** 20261109-234500 · **Mode:** dev
> **配对文档:** `docs-dev/copilot/20261109-mouse-pointer-issues-and-pitfalls.md`（§1.1 三套盒坐标、§9 副作用评估、§12/§13 变更记录）
> **状态:** **已修复（2026-11-11 接线完成）**——基库 `4.11.31-SNAPSHOT` 实现 I1/I2/I3（`vi` 文档坐标、布局失效重算、`compute()` 去滚顶/去 `window.stop()`）；I4（`get box` 与滚动解耦 + 可见不滚）与 I5（`boxSpace`/`schemaVersion`/`scrollTopAtCapture` + `Box` 类型 + `RuntimeScriptContract` 防呆）同日完成；Browser4 升版并重建 bundle，三组真实浏览器 e2e 通过（证据：`docs-dev/copilot/20261109-mouse-pointer-issues-and-pitfalls.md` §12.8）。
> **代码基线:** 工作树 `4.16.5-SNAPSHOT`；基础库 `pulsar-browser 4.11.30`（`browser4-dependencies/pom.xml:587`）；实测用本机 dev bundle `4.16.4-SNAPSHOT`

---

## 0. 结论摘要

| 编号 | 问题 | 是产品缺陷吗 | 置信度 | 建议决策 |
|---|---|---|---|---|
| I1 | `vi` 是**相对 body 边框盒**的坐标（不是文档绝对坐标、也不是视口坐标），文档从未标注 | 是（契约 + 文档） | 高 | ACCEPT |
| I2 | `vi` 在每个文档**首次** `compute()` 时算一次后**冻结**，SPA/懒加载/展开后仍是旧布局 | 是（正确性） | 高 | ACCEPT |
| I3 | 首次 `htmlsnapshot*` 强制 `window.scrollTo(0,0)` + `window.stop()`，**不恢复滚动** | 部分是（副作用未文档化） | 高 | ACCEPT（恢复滚动） |
| I4 | `get box` 的测量与滚动竞态：返回值可能在命令结束前就已过期 | 是（可观测性） | 高 | ACCEPT |
| I5 | 三套 box 在文档、CLI 输出、capture 元数据里都没有坐标空间标记 | 是（文档） | 高 | ACCEPT |

**一句话**：同一个元素在同一时刻可以合法地给出 `16`（`snapshot [box]`，绝对文档）、`1200`（`vi`，相对 body 且是旧布局）、`-656`（`get box`，测量时视口，返回后页面已滚到顶）三个数字，三者都没有错误码、没有警告、文档还都只写 "CSS pixels"。混用即静默错位，最坏偏移 1856px。

> **进展（2026-11-10）**：I1 与 I3 已在基库实现——`vi` 改为"client rect + scroll"的文档绝对坐标，`compute()` 不再 `scrollTo(0,0)`、不再 `window.stop()`（Jest 141 passed、Maven 离线编译 exit 0）；Browser4 侧已同步 vendored `dom_rect.js`。要等基库 `4.11.31-SNAPSHOT` 安装并升级 `browser4-base.version` 后，Browser4 运行时才能同时拿到新的 `node_ops.js` / `__pulsar_utils__.js`。I2/I4/I5 的修复建议仍然有效。

---

## I1：`vi` 是相对 body 的坐标，不是文档坐标

**Severity:** High · **Category:** API 契约 / 文档 · **复现状态:** 已实测

### 现象

`htmlsnapshot` capture 元数据里的 `interactiveElements[].box`、导出 HTML 的 `vi` 属性、X-SQL 的 `box=` 全部来自同一个 `vi` 值。该值是 `getBoundingClientRect()` 减去 **body 边框盒**后的结果：

- 不加 CSS reset 的页面，默认 `body{margin:8px}` → `vi` 比绝对坐标恒少 8px；
- `html` 与 `body` 都有 margin（实测 `html,body{margin:8px}`）→ 恒少 16px；
- `body` 带 `transform`/`zoom`/定位偏移时，基准任意漂移。

### 证据

- `browser4-core/browser4-browser/src/main/resources/js/dom_rect.js:172-184`：

  ```js
  __pulsar_utils__.getElementClientRect = function(ele) {
      let bodyRect = this.bodyRect || (__pulsar_utils__.bodyRect = document.body.getBoundingClientRect());
      let r = ele.getBoundingClientRect();
      ...
      let top = r.top - bodyRect.top;
      let left = r.left - bodyRect.left;
  ```

- 调用链（基础库 4.11.30）：`js/feature_calculator.js:162`（`viProps.vi = nodeExt.formatDOMRect()`）→ `js/node_ops.js:216`（`__pulsar_utils__.getClientRect(element)`）→ `dom_rect.js`。
- 实测（`file://` 夹具，`html,body{margin:8px}`，spacer 1200px，`a#home` 100×40）：

  | 场景 | `snapshot [box].y` | `vi.y` | 差值 |
  |---|---|---|---|
  | 元素绝对 `y=1216`、body 绝对 `y=16` | 1216 | 1200 | 16 = body 偏移 |

### 影响

- 任何把 `vi` 当绝对坐标或视口坐标使用的调用方都会稳定偏 8–16px（默认页面），body 偏移更大时更多；
- 与 `snapshot --boxes` / `get box` 对照时永远对不上，诊断时无法判断谁对（实测同元素 1216 vs 1200）。

### 候选修复（未实施）

| 改动 | 位置 | 内容 |
|---|---|---|
| `vi` 绝对化 | `dom_rect.js:172-184`（及基础库同源文件） | 用 `r.top + window.scrollY`（或 `r.top - document.documentElement.getBoundingClientRect().top`）替代 `rect - bodyRect`；兼容期可同时输出 `viAbs` |
| 文档标注 | `skills/browser4-cli/references/snapshot.md:63`、`x-sql.md:196`、`htmlsnapshot.md:38`、`docs/aria-snapshots.md:48`、`README*.md` | 明确三种 box 的空间与差异 |
| 元数据标记 | `browser4-rest/.../HTMLSnapshotToolExecutor.kt:1549` | 在 capture JSON 增加 `boxSpace`（如 `"body"` / `"document"`） |

---

## I2：`vi` 首次 capture 后冻结，布局变化不刷新

**Severity:** High（SPA / 懒加载 / 折叠面板）· **Category:** 正确性 · **复现状态:** 已实测

### 现象

`vi` 只在文档第一次 `compute()` 时计算，之后任何 capture 都复用旧值：

- `Browser4WebDriverScripts.kt:646-654` 的 `viDataStatusJs()` 只在 `u._viDataComputed !== true` 时调用 `u.compute()`；
- `compute()` 自身还有 `document.body.hasAttribute('data-error')` 早退守卫（`pulsar-browser 4.11.30: js/__pulsar_utils__.js:1520-1523`），并在结束时写入 `data-error="0"`（1567 行）。

### 证据（实测）

1. 首次 capture（`scrollY=800`）：`[box=0 1200 100 40]`；
2. 把 1200px 的 spacer 高度改为 0（元素绝对 `y` 1216 → 16）；
3. 同一会话再执行 `htmlsnapshot`：仍是 `[box=0 1200 100 40]`（旧布局），而同刻 `snapshot --boxes` 已是 `[box=16,16,100,40]`。

### 影响

- `htmlsnapshot` 的 `interactiveElements[].box`、`inspect` 样本 `box`、X-SQL `box=` 在 SPA 路由（`pushState` 不换文档）、懒加载、展开/收起、列表排序后全部指向旧位置；
- `inspect` 的版位加权直接使用 `vi.y` 绝对阈值（`MCPToolController.kt:2582-2597`：`<100` 头部、`100..800` 正文、`>2500` 页脚）→ 按旧布局给页面元素打分；
- PowerCSS `:expr(width>…, height>…)` 候选来自旧尺寸（`MCPToolController.kt:2905-2931`）。

### 候选修复（未实施）

| 改动 | 位置 | 内容 |
|---|---|---|
| 每次 capture 重算 | `__pulsar_utils__.js`（`_viDataComputed`/`data-error`）+ `Browser4WebDriverScripts.kt:646-654` | capture 前失效 `vi` 并按当前布局重算（注意成本：全树遍历） |
| 失效检测 | 同上 | 用 `scrollHeight`/DOM 变更计数/内容哈希判断布局是否变化，仅变化时重算 |
| 降级方案 | `HTMLSnapshotToolExecutor.kt` | capture 元数据附 `viStale: true`（检测到布局变化时），让消费者知道不可信 |

---

## I3：首次 capture 强制滚到顶 + `window.stop()`，且不恢复

**Severity:** Medium-High · **Category:** 副作用 / 会话状态 · **复现状态:** 已实测

### 现象

`__pulsar_utils__.compute()`（`pulsar-browser 4.11.30: js/__pulsar_utils__.js:1510-1567`）在计算 `vi` 之前先 `window.scrollTo(0, 0)`（1527 行），并在 1530 行调用 `window.stop()`（中止在途资源加载）；函数结束时**没有恢复滚动位置**。

同一路径还会改动 DOM（实测确认）：

- `generateMetadata()` 向 `body` 追加 `<input type="hidden" id="PulsarMetaInformation">`（1561 行）；
- `document.body.setAttribute("data-error", "0")`（1567 行）。

### 证据（实测）

- capture 前 `scrollY=800`、元素视口 `top=416`；首次 `htmlsnapshot` 之后 `scrollY=0`、元素视口 `top=1216`；
- capture 后 `document.querySelector('input[type=hidden][id]')` 存在，`document.body` 有 `data-error="0"`。

### 影响

- 任何 `htmlsnapshot*` 读命令（`get`/`inspect`/`summary`/`query`/`grep`/`export` 都会先 capture）在**每个文档的第一次**调用时把页面拉回顶部，用户/agent 丢失滚动位置；
- 后续 `get box`、`mousemove`、`snapshot -v N` 的视口基准随之改变（测量值与命令前状态不一致）；
- `window.stop()` 可能让懒加载/长轮询页面拿不到内容；
- 注入的 `<input>` 与 `body[data-error]` 可能改变页面 CSS/选择器/自身快照。

### 候选修复（未实施）

| 改动 | 位置 | 内容 |
|---|---|---|
| 保存/恢复滚动 | `__pulsar_utils__.js:1510-1567` | `computeMetadata()` 在滚顶**之前**已记录 `scrollTop/scrollLeft`（`js/__pulsar_utils__.js:1448-1466`），计算结束后恢复即可 |
| `stop()` 可选 | 同上 | 由 capture 选项控制（默认关闭更安全），或仅在显式 `--stop-loading` 时调用 |
| 文档 | `htmlsnapshot.md` | 写明首次 capture 的滚动/加载副作用 |

---

## I4：`get box` 的测量与滚动竞态

**Severity:** Medium · **Category:** 可观测性 · **复现状态:** 已实测

### 现象

`bounding_box` 先 `page.scrollIntoViewIfNeeded(selector)` 再取 `DOM.getBoxModel`（`pulsar-browser 4.11.30: chrome/PulsarWebDriver.kt:2034-2039`）。实测返回的坐标是**测量时**的视口坐标，但命令在返回前后会把页面滚动，导致数值立即过期。

### 证据（实测）

- 场景 A：`scrollY=672`、元素实际视口 `y=-656`；`get box` 返回 `{"y":-656}`；命令结束后 `scrollY=0`、元素实际 `y=16` → 返回值偏差 672；
- 场景 B：`scrollY=800`、元素视口 `top=416`；`get box` 返回 `416`，命令结束后 `scrollY=786`（页面被滚动了 14px）；
- 场景 C（首屏外元素）：第一次 `get box` 返回 `3540`（滚动前视口），命令结束后 `scrollY` 从 0 变 2909；再读一次才返回 `430`（与 `getBoundingClientRect()` 一致）。

### 影响

"测量 → 移动/点击"两段式脚本会按旧视口坐标落点；重复测量同一元素可能得到不同数值；与 `snapshot [box]` 的差异无法归因。

### 候选修复（未实施）

- 已可见时不滚动；必须滚动时等落定后再测量；
- 或返回 `scrolledDuringMeasure` 标志，让调用方重新读取；
- 文档写明"`get box` 可能触发滚动，测量后请重读 `scrollY`"。

---

## I5：三套 box 都没有空间标记（文档 + 输出）

**Severity:** Medium · **Category:** 文档 · **复现状态:** 已核对

| 输出 | 文档说法 | 实际空间 |
|---|---|---|
| `snapshot --boxes` `[box=…]` | "bounding box in CSS pixels"（`skills/browser4-cli/references/snapshot.md:63`、`docs/aria-snapshots.md:48`） | 绝对文档坐标（CDP `DOMSnapshot.LayoutTreeSnapshot.bounds`，`cdp-protocol/browser_protocol.json:9828-9829`） |
| `htmlsnapshot` `box=` / `interactiveElements[].box` | "printed verbatim from the runtime's `vi` data"（`x-sql.md:196`）、"bounding box"（`htmlsnapshot.md:38`） | 相对 body，且冻结在首次 capture |
| `get box` | "Return the bounding box"（`docs/mcp-tools.md:1290-1314`） | 测量时视口坐标，且可能触发滚动 |

**建议**：三处文档各标注空间；capture 元数据与 CLI 输出加空间字段；`mousemove` 的文档里交叉引用（它只接受视口坐标）。

---

## 6. 测试建议（修复时同步补）

| 用例 | 断言 |
|---|---|
| 滚动后 capture | `vi` 与滚动位置无关（滚到 800 与 0 两次 capture 的 `vi` 相同） |
| 默认 body margin | `vi.y == ariaBox.y - bodyOffset`（bodyOffset = body 边框盒绝对 y） |
| 布局变化后二次 capture | 新 capture 的 `vi` 反映新布局（当前会失败，证明 I2） |
| 首次 capture 的滚动副作用 | capture 前 `scrollY=800` → capture 后仍应为 800（当前为 0，证明 I3） |
| `get box` 竞态 | 命令返回后立刻读 `scrollY` 与 `getBoundingClientRect()`，与返回坐标一致（当前会失败，证明 I4） |
| 三空间对照 | 同一元素同时取三种 box，断言三者关系符合文档定义 |

建议夹具：`browser4-tests/pulsar-tests-common/src/main/resources/static/b4/` 下新增"滚动 + body margin + 可折叠 spacer"页面。

---

## 7. 置信度与证据缺口

- **高**：I1（`vi` 的 body 基准）、I2（一次性计算）、I3（滚顶副作用）——源码 + 实测双重证据；
- **高**：I4（测量-滚动竞态）——三种场景实测复现；
- **中**：各消费方的实际业务损失——取决于是否有外部调用方混用 box 与 `mousemove`/截图坐标，本次未统计；
- **未覆盖**：iframe 内元素的 `vi`（`CDPSnapshotService` 有 iframe 偏移逻辑，未实测）；`body` 带 `transform` 时的基准漂移（按公式推断，未实测）。
