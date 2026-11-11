# Pulsar 注解序列化：活 DOM 零污染（`vi`/`lz`/`tp`/`data-error`）

- **来源**：本文原为 `20261109-mouse-pointer-issues-and-pitfalls.md` 的 §13（2026-11-10 第二批修改的记录），2026-11-10 拆分独立成文（待办 P4-1）。该文只保留一句交叉引用。
- **主题范围**：`compute()`/注解写出路径与 HTML 序列化。**与鼠标/坐标主题无关**；鼠标与盒坐标问题见 `20261109-mouse-pointer-issues-and-pitfalls.md`，待办见 `20261110-mouse-pointer-todo.md`。
- **落地状态**：改动在基库 `browser4base` 工作区（`4.11.31-SNAPSHOT`），Browser4 侧仅同步了 vendored `dom_rect.js` 相关注释与测试；接线步骤见 §5。运行时是否生效按源文档 §0.1.1 的三态表判断。

> 2026-11-10 第二批修改。目标：`#PulsarScriptSection`、`#PulsarMetaInformation`、`body[data-error="0"]`、`lz`、`tp` **不再写入活 DOM**，页面脚本与 MutationObserver 无法检测。其中 script/input/`data-error` 只在导出 HTML 中存在；`lz` 只在**注解序列化输出**（`getAnnotatedHTML()` / `getAnnotatedOuterHTML()`，即 `pageSource` / `outerHTML` 的返回）中存在，htmlsnapshot 家族解析时仍按原策略剥离 `lz`/`tp`；`tp` 不序列化。

## 1. 改动清单

| 文件 | 改动 |
|---|---|
| 基库 `pulsar-core/pulsar-browser/src/main/resources/js/__pulsar_utils__.js` | `writeData()` 改为把 JSON 存入 `_captureDataJson`；`generateMetadata()` 改为把 domain/view-port/date-time/timestamp 存入 `_captureMeta`；`compute()` 用内存 `_computeDone` 标记替代 `body[data-error]` 守卫，不再 append 任何节点；`__updateStat()` 的 `lz`/`tp` 改存 `_viDataMap`（`_setNodeFlag`），不再 `setAttribute`；移除 `removeAttribute("tp")` 清理；`serializeAnnotatedHTML()` 负责输出 `body data-error="0"`、合成 `<script id="PulsarScriptSection">` 与 `<input id="PulsarMetaInformation">`、以及每元素的 `lz`（与既有 `vi`/`tvN` 同一套 WeakMap 注入）；`tp` 保持 runtime-internal，不输出 |
| 基库测试 `dom_rect.test.js` | 新增 2 个用例：`compute()` 后活 DOM 无 5 类注解且 MutationObserver 零记录；`serializeAnnotatedHTML()` 仍包含 script/input/`data-error`/`lz`，不含 `tp`，而活 DOM 依旧干净 |
| 基库测试 `viewport_dimensions.test.js` | `generateMetadata()` 的断言从"活 DOM 的 `#PulsarMetaInformation` 属性"改为"`_captureMeta` 内存值 + 无 DOM 节点" |
| Browser4 `HTMLSnapshotToolExecutor.kt` | 保留 `stripRuntimeTextMarkers()` / `RUNTIME_TEXT_MARKER_ATTRIBUTES`；`parseSnapshotPage()` 继续剥离 `lz`/`tp`（它们不再存在于活 DOM，但仍是 runtime bookkeeping，不应进入 htmlsnapshot 导出） |
| Browser4 测试 `HTMLSnapshotToolExecutorTest.kt` | 恢复"lz/tp 被剥离、`vi`/`normalizedURI` 保留"用例（前提改为"由序列化器写出，不在活 DOM"） |
| Browser4 `Browser4WebDriver.kt` / `browser4-core/browser4-protocol/.../protocol/browser/emulator/util/HtmlIntegrityChecker.kt` | 更新 KDoc/注释：注解由序列化器输出，活 DOM 不被修改；不再 `window.stop()` |

## 2. 行为

| 视图 | 修改前 | 修改后 |
|---|---|---|
| 活 DOM 节点 | `compute()` 后追加 `<script id=PulsarScriptSection>`、`<input id=PulsarMetaInformation>` | **不存在**（`getElementById` 为 null，`document.scripts`/`querySelectorAll('input')` 不增加） |
| 活 DOM 属性 | `body[data-error="0"]`、元素 `lz`/`tp` | **不存在**（MutationObserver 属性/子节点记录为零） |
| 页面脚本 | 可读 `__pulsar__Data`（内联脚本执行）、`#PulsarMetaInformation` 属性 | 不可见；页面 world 无脚本执行（无 CSP violation、无全局词法绑定） |
| 导出 HTML | `pageSource`/`outerHTML`/`htmlsnapshot`/`export` 都包含 script、input、`data-error`、`lz`（`tp` 通常已被删） | `pageSource`/`outerHTML`（**注解序列化器输出，未经 Jsoup 解析**：`pageSource() → getAnnotatedHTML()`、`outerHTML() → getAnnotatedOuterHTML()`，基库 `PulsarWebDriver.kt:2117-2123 / 1870-1879`）：包含 script（`__pulsar__Data`）、input（domain/view-port/date-time/timestamp）、`body data-error="0"`、每元素 `lz="1"`、`vi`/`tvN`；`tp` **不输出**。注意 `_viDataComputed` 为 false 时 `getAnnotatedHTML()` 会回退到原生 `outerHTML`，此时不含任何注解（`__pulsar_utils__.js:1410-1414`）。`htmlsnapshot`/`export`（解析路径）：继续剥离 `lz`/`tp`，保留 script/input/`data-error`/`vi`；JsoupParser 继续补 normalizedURI/href/normURL/normalizedUrl/label/taskId/taskTime 属性 |
| 下游消费者 | `HtmlIntegrityChecker` 读 `data-error`；`PageSummaryIndexService` 读 `#PulsarMetaInformation` | 不变（都从捕获 HTML 读取） |
| 客户端脚本兼容性 | 可依赖活 DOM 中的 script/input 节点 | 这些节点不再存在；`writeData()`/`generateMetadata()` 名字保留但改为内存操作 |

2026-11-10 后续（M14 修复，见源文档 §12）追加：`serializeAnnotatedHTML()` 写出的 `<input id="PulsarMetaInformation">` 增加 `box-space="document"` 属性，标注同一份 HTML 里 `vi` 盒的坐标空间；旧文档没有该属性，应按 body 相对解释。

## 3. 关键取舍（已确认）

- **`lz`/`tp` 保持剥离（用户决定）**：`HTMLSnapshotToolExecutor.parseSnapshotPage()` 继续调用 `stripRuntimeTextMarkers()`；`tp` 不再由序列化器输出（与旧行为一致：`compute()` 在序列化前就把它删了）；`lz` 仅为保持 `pageSource` 旧行为而由序列化器输出，进入 htmlsnapshot 解析路径时被剥离。因此"导出中存在"的承诺只覆盖 script/input/`data-error`（以及 `vi`/`tvN`），不覆盖 `lz`/`tp`。
- **`vi` 冻结（M14）**：注解不再污染 DOM；`vi` 的布局失效重算已在同一批接线中补上（`_computeDone` + `layoutVersion`，见源文档 §12、P3-1）。
- **`get box` 竞态（I4）与 `boxSpace`（I5）**：已在同一次接线中修复（`boundingBox` 解耦滚动、`boxSpace`/`schemaVersion` 输出），细节见源文档 §9.7 与待办 P2-1/P2-3。

## 4. 验证

- **基库 Jest**：`cd pulsar-core/pulsar-browser/src/test/js && npx jest --ci` → **8 suites / 143 tests passed**（新增 2 个 compute/serializer 用例；`viewport_dimensions` 的 4 个旧断言已按内存语义改写）。2026-11-10 的 M14 补充后在 `dom_rect.test.js` 又加了 3 个 compute 用例（重算/失效/无 MutationObserver），总量见待办清单回填。
- **基库编译**：`mvnw.cmd -o -pl pulsar-core/pulsar-browser -am -DskipTests compile` → **exit 0**；`target/classes/js/__pulsar_utils__.js` 与源文件哈希一致。
- **Browser4 编译**：`mvnw.cmd -o -pl browser4-rest -am -DskipTests test-compile` → **exit 0**。
- **Browser4 测试**：`mvnw.cmd -o -pl browser4-rest -am -Dtest=HTMLSnapshotToolExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test` → **48 tests / 0 failures / 0 errors，BUILD SUCCESS**（恢复 `stripRuntimeTextMarkers` 后重跑）。
- **真实浏览器 e2e**：接线（基库 install + `browser4-base.version` 升级 + 重建 bundle）后按 §5 的断言执行。
- **未覆盖的检测面**：本改动只消除这 5 类注解在活 DOM 中的痕迹；`stealth.js` 的页面 world 补丁、`highlight`/`#b4debug-highlights`、`vitals` 注入的 CDN `<script>` 仍按各自机制可见/可检测；console 兜底补丁只在隔离 world 生效，页面不可见，见 §6/§7。

## 5. 接线

与源文档 §12.5 相同：基库 `mvn install` → Browser4 `browser4-base.version` 升级 → 重建 bundle。生效后可用以下断言验收（真实页面）：

1. `htmlsnapshot` 前后 `eval "document.getElementById('PulsarScriptSection') === null && document.getElementById('PulsarMetaInformation') === null && !document.body.hasAttribute('data-error') && document.querySelectorAll('[lz],[tp]').length === 0"` → `true`；
2. 同一页面 `htmlsnapshot grep --raw-html "PulsarScriptSection|PulsarMetaInformation|data-error"` → 有命中；
3. `htmlsnapshot get outerhtml "#PulsarMetaInformation"` → 返回隐藏 input（含 view-port、box-space 等属性）；
4. `htmlsnapshot get html "body"` / `export` → **不含** `lz=`/`tp=`（解析时已剥离），但仍含 script/input/`data-error`/`vi`；未解析的 `pageSource`（注解序列化输出）可有 `lz=`、不会有 `tp=`。

## 6. 剩余检测面（不在本次范围）

| 面 | 仍可检测 | 说明 |
|---|---|---|
| `stealth.js` 页面 world 补丁 | 是（页面脚本） | `window.chrome`/`navigator.webdriver`/`outerWidth`/`contentWindow` 等改的是 JS 环境，肉眼与 DOM 不可见；`browser.stealth.enabled=false` 可关闭 |
| `highlight` / Agent 覆盖层 | 是（截图/元素样式） | CLI `highlight` 写元素 inline `outline` + `data-b4-highlight`；Agent `drawOverlay` 画 `#b4debug-highlights`；仅在命令触发时出现 |
| console 兜底补丁 | 否（页面 world 不可见） | CDP console 不可用时，在**隔离 world** 替换 `console.*` 并写 `window.__b4_console`；页面主 world 的 `console` 不受影响，因此页面脚本看不到这个补丁，但它也抓不到页面自己的日志（详见 §7） |
| `vitals` | 是（DOM + 网络） | 向 `<head>` 注入 unpkg 的 `<script>` |
| 滚动 / 焦点 / 视口 | 是（状态） | `get box` 的 `scrollIntoView`（4.11.31 起仅对不可见元素触发）、元素截图 `scrollToTop`、`highlight` 平滑滚动、`resize`/device metrics |
| `compute()` 的布局读取 | 理论（时序侧信道） | 只读 `getComputedStyle`/`getBoundingClientRect`，无 DOM 写；无 DOM 痕迹，但耗时模式可被统计 |

## 7. console 兜底补丁（`consoleMessagesJs`）

- **位置**：`Browser4WebDriverScripts.kt:260-290`（`consoleMessagesJs`）、`:293`（`consoleClearJs`）；调用点 `Browser4WebDriver.consoleMessages()` / `consoleClear()` 的 fallback，以及 `BrowserTabToolExecutor.kt:2867/2877`。
- **触发**：`browser.console.capture=false`（`B4Constants.CONSOLE_CAPTURE_CDP`），或 transport 无法启用 `Console`/`Log` 域、`ensureConsoleCapture()` 失败时。CDP 可用时优先 `Console.messageAdded` / `Log.entryAdded`，完全不碰页面。
- **做什么**（在 **隔离 world** 执行；`JsHandler.evaluateValueDetail` 默认 `JsWorld.ISOLATED`）：
  1. `window.__b4_console = window.__b4_console || []`，`window.__b4_console_intercepted = true`；
  2. 包装 `console.log/warn/error/info/debug`：参数转字符串（对象走 `JSON.stringify`）后 push `{level,text,timestamp}`，再调用原函数；
  3. 按 level 阈值过滤后 `JSON.stringify` 返回；`consoleClearJs` 只把数组清空。
- **关键性质/坑**：
  - **只对隔离 world 生效**：页面主 world 的 `console` 是另一个全局对象，页面自己的 `console.log` 不会被记录。该 fallback 不是"页面 console"的等价替代，只能看到通过 `evaluate` 在隔离 world 执行的代码的日志；只有 isolated context 不存在、`JsHandler` 回退到 main world 时才可能碰到页面日志（那种状态下抓取本身也不可靠）。
  - **可检测性**：对页面 world **不可见**（所以不算页面检测面）；但在隔离 world 内 `console.log.toString()` 不再是 native，且多了 `window.__b4_console`/`__b4_console_intercepted`。
  - **丢日志/改语义**：安装前已发生的日志抓不到；对象只保留 `JSON.stringify` 结果（DOM 节点→`{}`，循环引用会抛 `TypeError`，`BigInt` 会抛），`%s/%d/%c` 格式串不解析，多参数以空格拼接。
  - **重复安装**：`__b4_console_intercepted` 防重；`consoleClear()` 只清数组、不恢复原生 `console`。
  - **不修改 DOM**：只改隔离 world 的 `window`/`console`，因此不在本文的"活 DOM 零污染"清单里。
