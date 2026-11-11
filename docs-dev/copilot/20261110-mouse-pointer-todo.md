# 鼠标指针 / 盒坐标：待办清单（带复选框）

- **来源**：`20261109-mouse-pointer-issues-and-pitfalls.md`（§0.1 状态表、§2 M1–M15、§3 D1–D5、§4 T1–T3、§5、§9.7、§10.4、§11.7、§12.5–§12.7）
- **生成**：2026-11-10（与兄弟项目 `browser4base` 复查后）
- **约定**：`[x]` 已完成（附证据位置）· `[ ]` 待办 · 行尾括号内为**依赖**
- **进度（2026-11-11）**：**28/28 待办全部完成**（§0 的 12 项此前已完成）。基线库 `4.11.31-SNAPSHOT` 已 install、Browser4 已升版并重建 bundle，真实浏览器三组 e2e + 基库 e2e 通过；两侧改动已提交（Browser4 `2810db4ecc` · 基库 `6cc3ea31e`）。

> 本清单现在是这批改动的**唯一待办出处**：源文档的 §5 / §9.7 / §10.4 / §11.7 / §12.6 只保留"问题 → 待办项"映射（P4-2）。
> 运行时证据（bundle 脚本、CLI 实测、inspect 阈值分布）集中在源文档 §12.8。

---

## 0. 已完成（勿重复）

- [x] 坐标心智模型：`mousemove x y` = 主框架**视口 CSS 像素**，不动 OS 光标（源文档 §1；`commands.rs` 的 `mousemove` 定义、CDP `Input` 域字段说明）
- [x] 三套盒坐标实测 + 误差模型（`D/S/B/Δlayout`，`bodyRect` 缓存从不失效）（§1.1、§9.1、§9.2；`v4.11.30 dom_rect.js:173,193`）
- [x] 消费方影响面矩阵与"能否识别"三层结论（§9.3、§10.3）
- [x] 统一坐标系方案评估（结论：必要但不充分，4 类问题无法用单一坐标解决）（§11）
- [x] `vi` 绝对化：`getDocumentRect = client + scroll`，`NodeOps.getRect` 改用它（现已接线：bundle 内 `dom_rect.js` 7050 B）
- [x] `compute()` 去滚顶 + 去 `window.stop()`（现已接线，§12.8-B①）
- [x] `nScreen` / `queryClientRect` 空间语义澄清（`dom_rect.js:138-186`）
- [x] 基库 Jest 用例（`dom_rect.test.js`；本轮又加 3 个 compute/布局指纹用例，合计 **8 suites / 147 passed**）
- [x] Browser4 vendored `dom_rect.js` 与基库**逐字节同步**（SHA256 `41bf2ea3…0f2a0a`、7050 B）
- [x] §13 注解序列化：`#PulsarScriptSection`/`#PulsarMetaInformation`/`data-error`/`lz`/`tp` 不再写活 DOM（文档已拆分为 `20261109-pulsar-annotation-dom-pollution.md`）
- [x] 运行时三态取证：classpath 探测 + `isolated-world.gen.js` 实读（§0.1.1、§12.7；`ResourceLoader.kt:206-216`）
- [x] 源文档复查修订（I1–I5 定义、M14/M15 正文、术语/行号校正、落地状态）

---

## P0 — 解阻塞（不依赖基库接线）

- [x] **P0-1 提交全部未提交改动**
  位置：基库 6 文件 + 本轮新增（`api/WebDriver.kt`、`chrome/PulsarWebDriver.kt`、`chrome/protocol/EmulationHandler.kt`、`js/{dom_rect,node_ops,__pulsar_utils__}.js`、`test/js/{dom_rect,viewport_dimensions}.test.js`、`pulsar-tests/pulsar-e2e-tests/.../PulsarWebDriverInjectedJSTests.kt`、`InteractiveBrowserEmulator.kt`）；Browser4（`browser4-core/browser4-browser/src/main/resources/js/dom_rect.js`、`Browser4WebDriver.kt`、`RuntimeScriptContract.kt`、`browser4-common/.../model/Box.kt`、`Browser4WebDriverScripts.kt`、`browser4-agentic` 的 executor/spec/examples/parseNumber、`browser4-rest` 的 capture/inspect/controller、CLI、docs）
  验收：两侧 `git status` 干净（提交信息分别说明 "document coords / layout-version recompute / hover hit-test / coordinate contract" 与对应 Browser4 侧）。

- [x] **P0-2 M4：`mousemove` 数值校验前置**
  位置：`cli/browser4-cli/src/commands.rs` 的 `mousemove.tool_params_fn`（非数字 → `cli_error`，走 `CLI_ERROR_SENTINEL` → exit 2，**不派发**）；`main.rs` 的 `compile_batch_step` 也检查该 sentinel。
  验收证据：单测 `test_mousemove_rejects_non_numeric_{x,y}_before_dispatch`；e2e `test_e2e_mouse_coordinate_spaces` 第 5 步（exit≠0、错误含 "Invalid x for mousemove"、页面指针日志不变）。

- [x] **P0-3 M6：`hover`/`click`/`drag` 之后的指针状态**
  位置：`main.rs` 的 `invalidates_mouse_position()` + `clear_mouse_position()`，在分派末尾（standalone）与 `handle_batch` 每个成功步骤后调用；记录被作废而不是近似回填——驱动真实指针就在动作落点上，`mousedown` 直接使用它。
  验收证据：单测 `invalidates_mouse_position_covers_navigation_and_pointer_actions`；e2e `test_e2e_mouse_pointer_state` 第 1 步（`hover #hover-zone → mousedown` 的 `down.target == "hover-zone"` 且在元素矩形内）。

- [x] **P0-4 M7：导航后清空 `last_mouse_position`**
  位置：同一 `invalidates_mouse_position()`（`goto`/`reload`/`go-back`/`go-forward`/`tab-select`/`tab-close`/`window-new`/`open`）。
  验收证据：e2e `test_e2e_mouse_pointer_state` 第 2 步（`mousemove 300 300 → goto → mousedown`：新页 `moves == 0`，没有回放）。

- [x] **P0-5 M5：`mousemove` 支持 `--no-snapshot`**
  位置：`commands.rs` `mousemove.options` 新增 `no-snapshot`；`help.rs` 的 `mousemove` Notes/Examples 同步。
  验收证据：单测 `test_mousemove_declares_no_snapshot_option`；e2e 中 `mousemove … --no-snapshot` 均正常。

- [x] **P0-6 D1–D4 文档纠错**
  位置与改法：
  - `README.md` / `README.zh.md`：`mousemove` 行改为"视口 CSS 像素 / 不动 OS 光标 / 越界=离开视口 / 滚动后失效"。
  - `skills/browser4-cli/SKILL.md:246`：同一句语义 + `--space document` 与 `snapshot --viewport-boxes` 的指引。
  - `cli/browser4-cli/README.md`：`mousemove` 行重写（含 `--no-snapshot`）。
  - `help.rs`：新增 `mousemove` Notes（坐标空间、越界、滚动、`--space/--steps/--duration/--allow-outside`）。
  - `docs/mcp-tools.md`：由 ToolDocGenerator 重生成（147 工具）；`hover` 描述改成"先到左上 ~50px 再进元素内部固定点（无抖动、无遮挡检查）"（D2），`moveMouseTo` 的 `deltaX/deltaY` 改为"相对元素可点位置的偏移，正数向右/向下"（D3），示例数字改为 number（D4，`{"x": 320, "y": 180}`）。
  - 生成器修复：`ToolDocGenerator.exampleArgumentValue()` 对数字/布尔输出裸字面量、其余输出转义字符串；基库 `WebDriver.kt` 的 `mouseMove`/`hover`/`moveMouseTo`/`boundingBox` KDoc 补齐空间语义。

---

## P1 — 接线（把基库改动变成运行时事实）

- [x] **P1-1 基库 install**
  命令（实际执行）：`mvnw.cmd -o -pl pulsar-core/pulsar-browser -am -DskipTests install`，另执行全量 `mvnw.cmd -o -DskipTests install`（其它基库模块）与 `mvnw.cmd -o -pl pulsar-bom -DskipTests install`。
  验收：`pulsar-browser-4.11.31-SNAPSHOT.jar`（1 793 869 B）及 `pulsar-bom`、全部基库模块均在本地仓库。

- [x] **P1-2 升级基库版本**
  位置：`browser4-dependencies/pom.xml`：`<browser4-base.version>4.11.31-SNAPSHOT</browser4-base.version>`（含该批改动的说明注释）。

- [x] **P1-3 重建 runtime bundle**
  命令：`browser4-apps/browser4-bundle/build-runtime-bundle.ps1 -SkipMavenInstall`（Maven 侧先以 `-Pall-main-modules,asset-bundle` 安装）。
  验收：bundle（140.1 MB）内 `browser4-browser-4.16.5-SNAPSHOT.jar!/js/dom_rect.js` = **7050 B**、含 `getDocumentRect`、无 `bodyRect`。

- [x] **P1-4 确认注入了哪一版**（不启浏览器）
  位置：`…/app-data/tmp/browser4-pereg/report/browser/js/isolated-world.gen.js`（95 410 B）。
  验收：含 `getDocumentRect`、`_computeDone`、`_layoutMutationCount`、`box-space="document"`；**不含** `bodyRect`；`window.stop` 仅出现在注释。

- [x] **P1-5 行为核对（4 条）**
  夹具：`b4/mcp-tool-controller-mouse-fixture.html` + 自建 `body{margin:8px}` 页。
  验收：①capture 前后 `scrollY` 800→800；②`#click-target` capture `vi=29 57 93 33` == `snapshot --boxes [box=29,57,93,33]`；③滚到 800 再 capture，坐标仍 `29 57 93 33`；④margin 页 `vi.y=1208` == `getBoundingClientRect().top + scrollY`。详见源文档 §12.8-B。

- [x] **P1-6 "半套接线"防呆检查**
  位置：`browser4-core/browser4-browser/.../RuntimeScriptContract.kt`（驱动创建时 `verify()`），`Browser4WebDriver.init` 调用。
  验收：校验 `dom_rect.js` / `node_ops.js` / `__pulsar_utils__.js` 同代（`getDocumentRect` vs `bodyRect`/`getClientRect`，`_computeDone`）；混合或陈旧 bundle 直接抛错并给出重建指引；单测 `RuntimeScriptContractTest` 7/7（含"打包脚本自检"）。

---

## P2 — 契约与输出

- [x] **P2-1 D5/I5：输出带空间标记**
  位置：
  - capture JSON：`HTMLSnapshotToolExecutor.htmlSnapshotMetadataJson()` 新增 `boxSpace:"document"`、`schemaVersion:2`、`scrollTopAtCapture`（可读时），`capture()` 读取 `window.scrollY` 后传入。
  - `get box`：`BrowserTabToolExecutor.boundingBox` 返回 `Box(VIEWPORT,…).toWireMap()` → `{x,y,width,height,boxSpace:"viewport"}`。
  - X-SQL / 导出 HTML：序列化器在 `<input id="PulsarMetaInformation" … box-space="document">` 标注 `vi` 的空间；旧文档无该属性 = body 相对。
  - 契约类型：`browser4-common/.../model/Box.kt` 的 `BoxSpace`（`document|viewport|body`）+ `BoxContract`（`SCHEMA_VERSION=2`、字段名、`spaceOf()` 对旧载荷回退 `body`）。
  验收：`HTMLSnapshotToolExecutorTest` 断言字段；真实页 capture 实测 `boxSpace=document schemaVersion=2 scrollTopAtCapture=800`；`get box` 实测 `boxSpace:"viewport"`。

- [x] **P2-2 文档判别表落地**
  位置与内容：
  - `skills/browser4-cli/references/snapshot.md`：`[box=]` 为文档绝对坐标 + `--viewport-boxes` + 越界语义。
  - `references/x-sql.md`：`box=` 的文档空间、`box-space` 标记、旧数据 = body 相对。
  - `references/htmlsnapshot.md`：`vi` 文档坐标、capture 元数据字段、`lz/tp` 改为仅序列化。
  - `docs/aria-snapshots.md`：`[box=]` 空间段落（滚动不影响、布局变化会失效、越界=离开页面）。
  - `references/browser-modes.md`：指针坐标空间 + 越界 + 滚动失效 + `--space/--steps/--duration`。
  - `SKILL.md` / `README*.md` / `cli/browser4-cli/README.md` / `help.rs` / 重生成的 `docs/mcp-tools.md(.json)`。

- [x] **P2-3 I4：`get box` 与滚动解耦**
  位置：基库 `PulsarWebDriver.boundingBox`——先 `DOMHandler.queryLocator`（不滚）测量；已在视口内直接返回；不可见才 `scrollIntoViewIfNeeded` + `waitForScrollSettled` 再测。
  验收：e2e `test_htmlsnapshot_box_space`（可见元素测量后 `scrollY` 不变）；源文档 §12.8-B；KDoc 写明视口空间与副作用。

- [x] **P2-4 inspect 阈值复核**
  位置：`MCPToolController` 的三档 y 阈值（`<100` / `100..800` / `>2500`）。
  动作：接线后在真实页采样（`example.com`、`news.ycombinator.com`、`www.wikipedia.org`），分布见源文档 §12.8-C：`<100`=8（HN 顶部导航）、`100..800`=75+13、`>2500`=0。结论：文档坐标下分档语义仍成立，**不重标定**；唯一边界（example.com 单链接 y=806 落在正文带外）已记录。
  另：`MCPToolController` 的三处 `vi` 解析改为 `Box.parse(..., BoxSpace.DOCUMENT)`，阈值假设显式化；inspect 结果新增 `boxSpace:"document"`/`schemaVersion`。

- [x] **P2-5 内部坐标类型化**
  位置：新增 `browser4-common` 的 `Box(space,x,y,w,h)` / `BoxSpace` / `BoxContract`（`parse` 支持 base36/comma/space 三种 `vi` 格式，`format()`，`toViewport/toDocument`，`overlapsViewport`，`toWireMap`）。
  迁移清单：`MCPToolController`（3 处 `vi` 解析 → `Box(DOCUMENT)`）、`BrowserTabToolExecutor.boundingBox`（`Box(VIEWPORT)` 输出）、`HTMLSnapshotToolExecutor`（capture 级 `BoxContract` 字段、`box-space` 由序列化器写出）。
  验收：编译期跨空间需显式 `toViewport/toDocument`；单测 `Box` 解析/转换路径（见 `browser4-common` 测试）。

- [x] **P2-6 动作 API 支持 `space` 参数**
  位置：基库 `WebDriver.mouseMoveAt(x,y,space,steps,durationMillis)`（默认实现拒绝非 viewport）+ `PulsarWebDriver` 覆写（`document → viewport` 在派发前用 `SCROLL_POSITION_JS` 换算；`steps/durationMillis` 显式节奏）；`BrowserTabToolExecutor` 新增 `mouseMoveAt` 分发；CLI `mousemove --space/--steps/--duration`（有任一 → `mouse_move_at`）。
  验收：单测 `test_mousemove_document_space_uses_the_engine_conversion_tool`、`test_mousemove_steps_and_duration_use_the_pacing_tool`；**真实浏览器**（`scrollY=800`）：`mousemove 29 1342 --space document` → 页面 `clientY=542`（1342−800）；`mousemove 10 10 --steps 3 --duration 60` → 落点 (10,10)。文档与 `docs/mcp-tools.md` 的 `mouse_move_at` 一节同步。

---

## P3 — 时间正确性与测试

- [x] **P3-1 M14：`vi` 重算 / 失效检测**
  位置：基库 `__pulsar_utils__.js` 新增 `ensureLayoutObserver()`（MutationObserver 只读订阅，零 DOM 污染）、`layoutVersion()`（DOM 变更计数 + URL + 滚动/视口尺寸）、`isLayoutStale()`；`compute()` 守卫改为 `_computeDone && _computedLayoutVersion === layoutVersion()`；Browser4 `viDataStatusJs()` 改为按 `isLayoutStale()` 决定是否调用 `compute()`（旧基库回退旧守卫）。
  验收：Jest 3 个新用例（仅布局变化重算、尺寸指纹、无 MutationObserver 降级）；真实页 e2e 插入 400px 后 `vi.y` +441（§12.8-D T3）。

- [x] **P3-2 T3：空间一致性测试（4 组）**
  位置：Browser4 e2e `test_e2e_htmlsnapshot_box_space`（`cli/browser4-cli/tests/e2e/scenarios/browser.rs`）+ 基库 e2e `PulsarWebDriverInjectedJSTests#testViBoxesAreDocumentAbsoluteAndRefreshOnLayoutChange`；夹具 `mcp-tool-controller-mouse-fixture.html`（页面级指针日志 + `body{margin:8px}` 临时页）。
  验收（均为真浏览器）：①滚动后 `vi` 不变、capture 不动滚动；②布局变化后 `vi` 更新；③`vi.y == snapshot [box].y`；④`get box.y == vi.y − scrollY`。基库 e2e 1/1 通过（15.6s）。

- [x] **P3-3 T1：越界/负坐标/滚动后 e2e**
  位置：`browser.rs` `test_e2e_mouse_coordinate_spaces`（已注册到 `scenarios/mod.rs`，group `mouse`，level Extended）。
  验收：`(0,0)`、`(640,1200)` 触发 `mouseleave` 且事件坐标不超过视口、`(-20,-20)` exit 0、滚动后 `(200,200)` 不被偏移、`mousemove abc 200` exit≠0 且不派发 → 全部通过（30.4s）。

- [x] **P3-4 T2：指针状态 e2e**
  位置：同上 `test_e2e_mouse_pointer_state`。
  验收：`mousemove → hover B → mousedown` 按在 B（`down.target=="hover-zone"` 且在矩形内）；`mousemove → goto → mousedown` 新页无回放（`moves==0`）→ 通过（32.0s）。

- [x] **P3-5 M10：`hover` 遮挡告警**
  位置：基库 `EmulationHandler.hover` 新增只读命中测试 `occlusionAt()`（`elementFromPoint` + 自身/父子判定），被遮挡时 `logger.warn` 输出遮挡元素；失败不误报。
  验收：代码路径 + KDoc；`hover` 行为（落点/轨迹）不变。

- [x] **P3-6 M12：inactive 时告警**
  位置：`Browser4WebDriver.mouseMove`：不可用时抛 `IllegalWebDriverStateException`（原先静默 return），KDoc 明确"不再继承上游静默跳过"。
  验收：代码 + KDoc；与 `mouseWheel` 的失败语义一致。

- [x] **P3-7 可选参数**
  位置：`mousemove --steps/--duration`（→ `mouse_move_at` 的 `steps`/`durationMillis`）、`--allow-outside`（仅抑制越界提示，不改变派发；越界提示由 CLI 读取 `[innerWidth,innerHeight,scrollX,scrollY]` 后打印）、`snapshot --viewport-boxes`（`BrowserTabToolExecutor` 在渲染后按当前 `scrollY` 改写 `[box=]`）。
  验收：单测 `test_mousemove_rejects_non_positive_pacing`、`test_mousemove_allow_outside_stays_cli_side`；**真实浏览器**：越界不带 `--allow-outside` 打印 `ℹ️ … outside the 1280×900 viewport …`、带之静默，两者 exit 0；`snapshot --viewport-boxes` 把 `[box=29,1342,…]` 输出成 `[box=29,542,…]`（−scrollY 800）。帮助/文档（`help.rs`、`browser-modes.md`、`snapshot.md`）给出用法；默认行为不变（`mousemove` 仍走 `browser_mouse_move_xy`）。

---

## P4 — 工程 / 文档结构

- [x] **P4-1 拆分 §13 到独立文档**
  完成：新建 `docs-dev/copilot/20261109-pulsar-annotation-dom-pollution.md`（原 §13.1–§13.7 完整迁出并补 `box-space` 说明）；源文档 §13 只剩 3 行交叉引用。

- [x] **P4-2 合并待办表**
  完成：源文档 §5 改为"问题条目 → 待办项"映射表；§9.7 / §10.4 / §11.7 / §12.6 的清单收敛为一句指针，统一指向本清单。

- [x] **P4-3 `parseNumber` 静默归零合并跟踪**
  完成：`AgenticCliRunner.parseNumber(command, name, raw)` 对非数字抛错，文案与 Rust CLI 统一为 `Invalid <arg> for <command>: expected a number…`；`execute()`/`executeCommand()` 转为失败结果、不发工具调用；调用点覆盖 `mousemove`/`mousewheel`/`resize`/`tab-select`/`tab-close`。`AgenticCliRunnerTest` 97/97（含 3 个新用例）；`docs-dev/copilot/20261022-agentic-executor-review.md` 第 29 条已标注修复。

---

## 验证汇总（2026-11-11，可复现）

| 层 | 命令 | 结果 |
|---|---|---|
| 基库 Jest | `cd browser4base/pulsar-core/pulsar-browser/src/test/js && npx jest --ci` | 8 suites / **147 passed** |
| 基库 e2e | `mvnw.cmd -o -f pulsar-tests/pulsar-e2e-tests/pom.xml test -Dtest=PulsarWebDriverInjectedJSTests#testViBoxesAreDocumentAbsoluteAndRefreshOnLayoutChange` | 1/1 通过（真 Chrome，15.6s） |
| Browser4 单测 | `mvnw.cmd -o -pl browser4-core/browser4-browser test -Dtest=RuntimeScriptContractTest`；`-pl browser4-agentic -Dtest=AgenticCliRunnerTest`；`-pl browser4-rest -Dtest=HTMLSnapshotToolExecutorTest,InspectDocumentTest,HTMLSnapshotInspectHintsTest,HTMLSnapshotResultSchemaTest` | 7/7、97/97、100/100 |
| 文档漂移 | `mvnw.cmd -o -pl browser4-rest test -Dtest=ToolDocGeneratorTest,McpToolAliasParityTest` | 8/8（`docs/mcp-tools.*` 147 工具，无漂移） |
| Rust CLI 单测 | `cargo test --bin browser4-cli` | **2129 passed / 0 failed**（4 ignored）。其中 `daemon::tests::wmi_spawned_backend_survives_a_hostile_process_tree_kill` 依赖 agent-shell 标记或 `BROWSER4_WINDOWS_BACKEND_SPAWN=wmi`；普通桌面 shell 下设该变量后复跑，全量 2129/2129 |
| CLI e2e | `cargo test --test e2e -- --level=EXTENDED --scenario=… --max-failures=0`（三个新场景） | 3/3 通过（30.4s / 32.0s / 35.8s） |
| 运行时取证 | 读 `isolated-world.gen.js` + bundle jar 内脚本 | 见源文档 §12.8-A |

## 未做的事（明确不在本清单范围）

- `click` 坐标形式、`drag` 轨迹、`mousewheel` 的细节（源文档声明的不在范围项）。
- 基库发布/打 tag 与 Maven Central 发布：本清单只做本地 `install` + Browser4 升 SNAPSHOT；正式发布按 `bin/release/` 流程单独进行。
