# Browser4 内置 frame 切换 — 设计文档（已实现）

> 状态: **已实现并通过验证**（2026-09-03：base 单测、workspace Kotlin 单测、Rust 单测、真实浏览器 e2e `test_e2e_frame_switch_commands` 全绿）
> 目标仓库: workspace `D:\workspace\Browser4\Browser4-4.14-feat` (4.14.x)
> 参考: agent-browser（`D:\codebase\browser-automation\agent-browser`）内置 frame 命令
> 基础库（必要时）: `D:\workspace\Browser4\browser4base` pulsar-browser 4.11.x

## 背景

- Browser4 目前没有内置 frame 切换命令。
- 同域 iframe：只能靠 `eval` 手工 `contentDocument.querySelector(...)`。
- 跨域 iframe：需要手工 `cdp Target.attachToTarget ...`，CDP 熟练度要求高。
- agent-browser 提供 `frame <selector|@ref|main>` 命令 + `frame_switch`/`frame_main` MCP 工具，
  切换后 CSS selector 类命令（click/fill/wait/gettext...）与 snapshot 作用域进入该 frame；
  OOPIF（跨域）通过 Target.setAutoAttach flatten 获得 frame session 后按 session 路由。

## 目标 UX（对齐 agent-browser，落到 Browser4 栈）

CLI（browser4-cli，spaced 风格注意）：
- `frames`             —— 列出当前页 frame 树（序号/name/url/深度/是否激活）
- `frame <target>`     —— 切换到 iframe（CSS selector / frame name / url 子串 / "main"）
- 切换后：CLI 的 selector 类交互命令与 `eval` 作用域进入该 frame；
  `frame main` 回到主 frame；导航（goto/open/reload）后自动回到主 frame（frame 上下文失效）。

Agent/MCP 工具：
- `tab.frame_switch(frame)` / `tab.frame_main()`（或独立 domain），名称对齐现有 alias 约定。

## 关键架构事实（侦察结论摘要，待补齐）

- 运行时 driver = Browser4WebDriver（workspace, browser4-browser），继承 pulsar-browser 的
  PulsarWebDriver（base jar ai.platon.pulsar:pulsar-browser:4.11.10）。
- WebDriver 接口 + PulsarWebDriver 实现 + DOMHandler/RobustRPC 解析管线在 **browser4base** 仓库；
  Browser4WebDriver 自带 private RobustRPC、可访问 page(PageHandler)/browserProtocol/keyboard。
- tab domain 工具由 BrowserTabToolExecutor 显式 when 分发 + ToolSpecGenerator 从 WebDriver.kt
  源码解析 @MCP 方法生成 spec（sources jar 或 code-mirror 镜像）。
- CDPSnapshotService（base）已按 frameId 合并 AX 树/内容文档，DOM 解析与 Input 事件仍在主 frame。
- CLI：commands.rs CommandDef（kebab name + browser_* MCP tool name + tool_params_fn），
  main.rs no_snapshot_commands()、help.rs/tips.rs、skills/browser4-cli/SKILL.md。

## agent-browser 机制侦察结论（已到位）

1. 命令面：裸命令 `frame <selector|main>`（`frame main` → action `mainframe`）；MCP 仅
   `frame_switch(frame)` 与 `frame_main()`；**无 frames 列表工具**。
2. handle_frame 解析顺序：ref(@eN) → DOM.describeNode(contentDocument.frameId, 回退 node.frameId)，
   节点须 IFRAME/FRAME；CSS selector → 顶部 doc querySelector 取 iframe 的 name||id||src 回 frame
   树精确匹配；name/url → 树递归（name 精确 / url contains）。失败报 "Frame not found"。
3. 状态生命周期：active_frame_id 仅由 frame 切换设置；清除于 mainframe / navigate / tab 操作；
   **reload/back/forward 不清**；无逐命令 stale 校验（靠 detachedToTarget + 导航清理）。
   element.rs 有全局 ACTIVE_FRAME 镜像（模块级函数收不到 DaemonState），每命令分发前同步。
4. 影响面：active frame 只影响 CSS 选择器解析根（element 命令）、wait、getbyrole 系、snapshot
   作用域（ref 带 frame 上下文，无需切换即可用）；**裸 eval 恒在主 frame**。
5. OOPIF：Target.setAutoAttach(autoAttach:true, waitForDebuggerOnStart:true, flatten:true)
   （browser 级与 page 级各一次）；attachedToTarget 中 OOPIF 的 frameId==targetId →
   iframe_sessions[frameId]=sessionId；同源 = 不在 iframe_sessions → DOM.getFrameOwner/
   contentDocument 路径。
6. 坐标/输入：同进程 frame → iframe owner objectId + this.contentDocument 查询 + frameElement
   rect 累加换算顶层视口坐标，发父 session；OOPIF → frame session 本地坐标。
7. 文档化已知限制：跨域 iframe 若父页不放行 AX，快照不可见；可 switch 进入显式操作。

## Browser4 现状侦察结论

- 运行时 driver = Browser4WebDriver（workspace），自带 private RobustRPC + page(PageHandler)；
  base 的 CSS/XPath 解析全部基于 **顶部 document**（DOM.querySelector on document root）；
  fbn locator 在 DOMHandler 解析时 frameId 被丢弃（只取 backendNodeId，顶部 session resolveNode）。
- snapshot 管线（base CDPSnapshotService）已按 frameId 组织 AX（axTreeByFrameId），DOM 树合并
  contentDocument（同源？）—— 待 base 侦察报告确认跨域处理与 ref 生成。
- tab domain 工具执行 = BrowserTabToolExecutor 显式 when + ToolSpecGenerator 从 WebDriver.kt 源码
  生成 specs（sources jar/code-mirror 镜像，运行期自动重写 driver-tool-call-specs.json）。
- CLI：commands.rs CommandDef；main.rs no_snapshot_commands()；post-command 自动快照可豁免。
- fixtures：browser4-tests/pulsar-tests-common/.../static/assets/frames/*.html 已有 iframe 页面
  （one-frame/two-frames/nested-frames/frameset/lazy-frame），b4/*.html 为交互夹具。

## 最终决定（v1）

### 关键事实（base 侦察结论）
- 传输：每 tab 两条 WS（browser 级仅 Target.*；page 级其余）。**全库无 setAutoAttach/attachToTarget/flatten/per-frame session**。
- 所有 CSS/XPath/DOM/AX 解析以主 frame 为根（DOM.getDocument() 无 pierce 参数？实际 pierce=true 用于 snapshot DOM 树，但 querySelector 基于根 document node）；`fbn:` frameId 只是标注，不路由。
- AX "per frame" 收集是假的（对每个 frameId 调同一棵无参 getFullAXTree）；**OOPIF/跨域内容完全不可达**；同进程 iframe 内容经 DOM pierce + contentDocument 可达（snapshot 已做坐标偏移合并）。
- JsHandler：isolated world（contextId）优先，主 world 回退；IsolatedWorldManager 支持按 frameId 建 world 并缓存 contextId。
- DOMHandler/RobustRPC/JsHandler/导航钩子都在 base → **同源 frame 作用域必须在 base 做**；Browser4WebDriver 是 workspace 扩展点（自带 RobustRPC/keyboard）。
- base e2e 已有 @Ignore("inner iframe features are postponed") —— iframe 交互支持确实是空缺。

### 方案（base 最小侵入 + workspace 全栈）
**基础库 pulsar-browser（browser4base，分支 feat/frame-switch off 4.11.x）**：
1. WebDriver 接口新增 @MCP 方法（KDoc @mcp 段落）：
   - `frameList(): List<FrameInfo>`（新模型 FrameInfo: id/name/url/parentId/depth）
   - `frameSwitch(target: String): FrameInfo`（target 解析顺序：精确 frameId → iframe CSS selector（顶层文档中）→ frame name 精确 → url 子串；树递归）
   - `frameMain(): Unit`（回主 frame）
2. PulsarWebDriver 持有 frame 状态（activeFrame），主 frame 导航时复位（onFrameNavigated0 已有主 frame 钩子）；
3. 同源 frame 作用域：DOMHandler/RobustRPC 解析链在 activeFrame 存在时把 CSS/XPath 解析根改到该 frame 的 document：
   - 用 DOM pierce：getDocument(pierce=true) 找目标 frame 的 document node → querySelector 于该 document → 返回 NodeRef（与现有 backend/fbn 相同的 NodeRef 语义，后续 click/fill/type/box/scroll 全部复用现有主流程，坐标语义经 e2e 实测确认）；
   - 或按 frame 建 isolated world + contextId 求值（evaluate(contextId) 已有能力）——实现时二选一/组合，以真实浏览器 e2e 为准。
4. **跨域/OOPIF：v1 明确限制**（**e2e 实测修正**：无 Target.setAutoAttach 时 Page.getFrameTree **不含** OOPIF，原"可列出/可 switch"假设不成立——真实行为是 `frames` 看不到跨域 frame、`frame <target>` 报 "Frame not found"（无 site isolation 的 Chrome 上可列出但作用域操作报 "not reachable"）。v1 不引入传输层 session 改造；代码保留"可选中但作用域不可达"的防御路径（FrameManager 单测覆盖），待后续 flatten session 层支持真正的 OOPIF。
5. eval 保持主 frame（agent-browser 同款语义，避免内部轮询逻辑被 frame 作用域破坏）。

**workspace（Browser4-4.14-feat，分支 feat/frame-switch off 4.14.x）**：
1. CLI：`frames`（列表，含 active 标记）、`frame <target|main>`（spaced 裸命令），no_snapshot_commands() 豁免，help/tips 更新；
2. tab 工具：BrowserTabToolExecutor when-case（frameList/frameSwitch/frameMain，走 driver 接口）+ ToolSpec 由 ToolSpecGenerator 自动生成（需重新生成 code-mirror 的 WebDriver.kt.txt 与 driver-tool-call-specs.json）；
3. MCPToolController：FRONTEND_TOOL_NAME_ALIASES + resolveMcpToolCall 显式映射（browser_frame_list/frame_list 等）；
4. 本地构建：browser4-base.version 覆盖到 4.11.10-SNAPSHOT（先 mvn install base）；
5. 测试：Rust commands.rs 单测、base DOMHandler/解析单测、e2e 场景（b4/frame fixture + 同源 iframe 交互断言 + 导航复位 + main 复位 + 嵌套 frame + 错误路径）；文档更新（skills/SKILL.md、references、README 提及）。

### 验收场景（e2e，真实浏览器）
1. goto 页面（含同名/ID iframe 表单）→ `frames` 列出 iframe → `frame "#pay"` → `click "#submit"`/`fill "#card"` 作用于 frame 内元素 → frame 内 state-log 变化断言。
2. `frame main` 后主文档操作不受影响；goto 后自动回主 frame（stale 复位）。
3. `frame byname`/url 子串切换；frame 内 waitForSelector/isVisible 作用域。
4. 错误路径：不存在的 frame → "Frame not found"；跨域 frame 作用域操作 → 明确错误信息。

## 候选方案（存档，已否决）

### A. workspace-only
Browser4WebDriver 内实现 frame 状态 + 作用域；WebDriver 接口不动 → 基础库零改动。
- frame 树/切换解析：executeCdpCommand("Page.getFrameTree") + evaluate 解析 iframe 元素
  （同源 contentDocument 链；跨源需 frame session —— 见机制侦察）。
- 同源 frame 内元素交互：iframe owner objectId + callFunctionOn(contentDocument.querySelector)
  → DOM.requestNode → backend:NN 定位符 → 复用现有 invokeOnElement 流程；
  点击坐标沿 frameElement.getBoundingClientRect 累加换算到顶层视口。
- **否决原因**：DOMHandler/RobustRPC/JsHandler 的解析全部在 base 内部且 Browser4WebDriver 无法
  拦截每个 selector 方法（rpc 等 private，需逐方法重写 ~40 个 selector 入口），跨 frame 原语
  （pierce 文档树/contextId/world）也都在 base。

### B. base 深度方案（不采用）
传输层引入 Target.setAutoAttach(flatten)/attachToTarget/每 frame session 路由
（ChromeDevToolsImpl/EventDispatcher/BrowserProtocol 三层改造）：
- 支持跨域 OOPIF 交互，但改动面最大、回归风险高；v1 不引入，跨域走明确错误 + 文档限制。

## 交付物清单
1. 驱动层：frame 状态/列表/切换/回主 frame API（base WebDriver 接口 + PulsarWebDriver）
2. 工具层：BrowserTabToolExecutor case + ToolSpec（自动生成）+ MCPToolController alias/advertise
3. CLI：commands.rs CommandDef（frame、frames）+ no_snapshot_commands + help/tips
4. 测试：unit（Rust commands tests、Kotlin 单测）+ e2e 场景（新 fixture frames 页面）
5. 文档：skills/browser4-cli/SKILL.md、references、README(s)
6. 版本联动：browser4base 分支 + workspace browser4-base.version 本地覆盖（4.11.10-SNAPSHOT）

## agent-browser 行为对齐复查（2026-09-03，源码级对比 v0.36.0）

结论：核心语义一致；差异分三类（详见对话记录，摘要如下）。

**一致**：`frame <target|main>` 命令形态；作用域只影响元素定位类命令（click/fill/wait/getbyrole/读系），`eval` 恒主 frame；切换失败保留原作用域并响亮报错；导航/切 tab 后作用域失效。

**我方增强（有意为之，已 e2e）**：`frames` 列表命令（agent-browser 无）；frame id / name / url 子串目标（agent-browser 的 name/url 分支是 CLI 不可达死代码）；**嵌套 iframe 可用 selector 逐层切换**（agent-browser 只在顶层文档解析 CSS）；无名 iframe 正确归属（agent-browser 用 name 匹配树，无名 iframe 会错配主 frame）；reload/back/forward 自动复位（agent-browser 保留 stale frameId 直到命令报错）。

**对齐补齐（本轮新增）**：`frame <ref>`——快照元素 ref（`eN` / `backend:N` / `fbn:...`）指向 `<iframe>` 时经 `DOM.describeNode` 取 contentDocument.frameId 激活（agent-browser 的 ref 优先解析）；非 iframe ref 与 stale ref 均响亮报错。另发现并修复：`cdp` 命令未列入 no_snapshot_commands，导致每次 cdp 后的自动快照使 DOM nodeId 失效、破坏连续 CDP 调用链——已加入免快照名单。

**仍为 v1 差距（文档化）**：跨域/OOPIF 无支持（agent-browser 用 Target.setAutoAttach flatten session 完整支持，v1 需 base 深度方案 B）；frame 内 XPath 不支持（agent-browser 支持 `xpath=` 于 frame 文档求值）。
