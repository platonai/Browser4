# 微博 → x.com 复盘改进方案（2026-09-10 lessons → 源码/机制改进）

- 日期：2026-09-11
- 输入：`D:\tmp\weibo2x\lessons-weibo-to-x-2026-09-10.md`（复盘教训）
- 原则：**站点无关问题 → 直接改源码；站点相关问题 → 经验复用机制承载；X 独有/无成熟解法 → 仅记录/提 issue**
- 结论速览：
  - 修源码：① attach/重连/status/list 明确浏览器身份（含扩展握手 UA 采集）；② 正式化并增强已存在但被隐藏的 `upload` 链路（backendNodeId、错误语义、CLI UX）；③ type 长文本可靠性（通用问题：多策略 + 回读校验）；④ AX snapshot 截断/弹窗缺失修复（P0 在仓库内：CLI 分页 + dialog 守卫；P2 在上游 pulsar-browser）；⑤ 经验机制补齐"教训写入"通道（含新 PUBLISH Intent）
  - 只记录不修：X 4 图上限、X 回复编辑器 remount（站点相关 → 经验 + 文档）
  - 站点相关（微博数据获取类）→ 经验记录 + 可复用 skill（不在本仓库代码改）

---

## 1. 连错浏览器：连接身份必须显式可见（源码修复）

### 现状（代码证据）
- CLI `attach --extension` 成功后打印 `Relay endpoint: … (browser channel: {channel})` + `format_session_opened_message`（main.rs ~1817-1830），channel 只是**用户请求值**，不代表扩展实际连上的浏览器。
- 断连自动重连路径（`with_session_recover`/`recover_stale`，main.rs ~1103-1200；`resolve_attached_session_id` ~1442-1520）复用/重建 session 时**不校验实际连接的浏览器身份**——本机 Chrome/Edge 都装扩展时，中继可能被另一个浏览器的扩展抢占 → 复盘第 1 条事故。
- `list` 的 Connection 列（`connection_label`，main.rs ~3931-3950）显示 `Extension ({state.browser_channel})`——同样是**本地记录的请求 channel**，不是真实身份。
- `status`（handle_status，main.rs ~18422）只显示 CLI/服务器健康，**无会话连接信息**。
- 后端：`createExtensionAttachedSession`（PulsarSessionManager.kt）建 session 返回 `wsEndpoint`，扩展随后经 WebSocket 连上；握手是否携带浏览器身份待子代理 B 确认（若不带：后端需加握手字段或扩展连接探测）。

### 机制事实（子代理 B 调研，2026-09-11）
- 后端不存 channel：`createExtensionAttachedSession`（PulsarSessionManager.kt:668-718）channel 只进日志（712-715）；MCPToolController.kt:513-515 还把 channel 从 capabilities 过滤掉。
- 扩展握手**零身份**：extension.initialized params 为空（chrome-extension/src/protocolHandlers.ts:77-82；扩展源码在本仓库 chrome-extension/）。
- **免费身份源**：后端 `ExtensionWebSocketHandler.afterConnectionEstablished`（ExtensionWebSocketHandler.kt:47-76）可读 `WebSocketSession.getHandshakeHeaders()` 的 **User-Agent**（Edg/ 品牌可区分 Chrome/Edge）与 Origin；目前只打 remote 日志未读未存。
- CLI"重连"语义混乱：main.rs:1130-1159 `with_session_paginated` 恢复走 `open_session`（**全新 Browser4 托管浏览器**，静默、无提示）；`resolve_attached_session_id`（1450-1518）复用原 sessionId；真正重连靠重跑 attach。串线入口是 `open_url_in_browser` fallback 链（2028-2091：Edge 失败→Chrome→cmd start 默认浏览器）+ 用户在哪个浏览器打开 connect URL。
- attach 打印/`connection_label` 用 `state.browser_channel`（main.rs:1825-1831、3941-3947）＝**请求参数当事实**；`check_session_ready` 只回 ready/healthy（MCPToolController.kt:555-572），`list_sessions` 无浏览器字段（448-469）。

### 改动方案（附一手代码锚点）
1. **后端通用前置**：ManagedSession.kt:18-28 增加 `attachChannel`/`browserIdentity` 字段；`createExtensionAttachedSession` 把 channel 存入；`PulsarSessionManager.recordExtensionIdentity(sessionId, ua/browser)`。后续所有展示面从同一后端字段取数。
2. **真实身份采集（零扩展改动首选）**：`ExtensionWebSocketHandler.afterConnectionEstablished`（.kt:47-76）读 `handshakeHeaders["User-Agent"]`（Edg/ 品牌可辨 Chrome/Edge）+ Origin，解析为浏览器身份；兜底：在已绑 tab 上 `Runtime.evaluate navigator.userAgent`（about:blank 不可伪造）。扩展配合方案（更强）：`protocolHandlers.ts:77-82` 的 extension.initialized 增身份字段或新发 `extension.hello`（jar 侧对未知消息仅 debug 日志，向后兼容）；注意 dist 未入库需 npm build。
3. **后端出口**：`check_session_ready`（MCPToolController.kt:555-572）返回加 `browserName/requestedChannel`；`list_sessions`（448-469）每项加 browser/channel。
4. **CLI attach 成功点**（main.rs ~1884-1890）：打印 `Connected to: <actual_browser>`；实际 ≠ 请求 channel 时醒目告警（请求 msedge 实际 Chrome 的登录态风险提示）。`check_session_ready` 解析处（1871-1897/1465-1475/4265-4291）封装统一小函数。
5. **重连可见化**：`with_session_paginated`（main.rs:1130-1159）对 ExtensionAttached/CdpAttached 禁止静默走 `open_session` 兜底，或至少打印"扩展会话已失效→已切换全新 Browser4 会话"；`resolve_attached_session_id`（1477-1479）复用分支回显实际浏览器（"重新连接到 Edge(msedge)"）。
6. **list/status**：`connection_label`（main.rs:3931-3950）优先用后端 browser 字段（`Extension (Edge 128)`），本地 channel 作请求值展示（如 `Extension (请求 msedge/实际 Chrome)` 告警态）；BackendSessionRecord（4183-4193）+ parse（4297-4339）加字段；`status`（handle_status ~18422）加当前会话小节（kind/Connection/真实浏览器/当前页/健康）。
7. **attach --cdp**：verify 已探测 browser（PulsarSessionManager ~488-491）——透出到 attach 成功消息（main.rs ~1989）。
8. **其他明确身份的位置（brainstorm）**：`open`/`-s` 切换会话头；`--json` 输出增 `connection.actual_browser`；"未登录"类错误提示补"先 `list` 核对 Connection"。
9. **防串线（可选加深）**：一次性 nonce 随 connect URL 下发、扩展握手回传校验；或后端比对"请求 channel 推导品牌 vs 实测品牌"，不一致拒绝绑定。
10. **文档/tips**：tips.rs TIPS_ATTACH（~295-302）、help.rs attach/list/status、SKILL.md 附加章节补"attach 后三步身份验证"。

### 其他需要明确身份/上下文的地方（brainstorm 结果）
- `attach --cdp`：打印端点的 Browser.getVersion 产品名（Chrome/Edge/版本），已有 endpoint 校验，顺手透出身份。
- `open`/会话切换（-s）时打印当前 session 的连接类型与浏览器。
- 快照/操作报错提示语中涉及"未登录"时，提示先 `list` 核对 Connection（把复盘教训转为 inline 提醒）。
- 每次命令的会话头（若有）或 `--json` 输出加 `connection` 字段。

---

## 2. CDP nodeId 跨命令失效 → 正式化并增强已有的 upload 链路（源码修复）

### 判断与重要现状（子代理 C 调研，2026-09-11）
- nodeId 仅在同一 CDP 会话内有效、backendNodeId 跨会话稳定属 CDP 通用常识；**不做"cdp 命令保持会话"类改造**，改为文档化。
- **重大发现：上传能力其实已全链路存在但被隐藏**：CLI `upload`（commands.rs:1469-1486，hidden:true）→ MCP `browser_file_upload` → 别名 `upload`（MCPToolController.kt FRONTEND_TOOL_NAME_ALIASES:108-152 之 126、ToolAliases.kt:24、AgenticCliRunner.kt:571-610 三份需同步）→ BrowserTabToolExecutor case "upload"（1263-1268）→ 外部库 pulsar-browser `PulsarWebDriver.upload`（.test-sessions/pulsar-src 只读快照 1256-1261：`rpc.invokeOnElement` 内 resolve+`DOM.setFileInputFiles(nodeId)` 一次 driver 调用完成）。已有真实浏览器 e2e（browser.rs:1145-1152、MCPToolControllerE2ETest:480-492）与 fixture（static/b4/mcp-tool-controller-interactive-fixture.html `#file-input`）。
- 与 agent-browser 参考差异：参考用 objectId→describeNode→**backendNodeId**→setFileInputFiles(backendNodeId)（agent-browser browser.rs:1942-1982）；库内现用 nodeId（单 RPC 内安全，但跨调用/iframe/DOM 变更稳健性差）。
- 现有 UX/门禁缺口：hidden 不可发现；upload 不在 no_snapshot_commands() 且无 --no-snapshot → 每次上传后自动跑 a11y 快照；CLI 只支持单文件；元素不存在/非 file input 时**静默成功**（违反 AGENTS.md 直接 CDP 方法门禁第 2 条）。

### 改动方案
1. **文档化（help/SKILL/reference）**：cdp 命令按调用执行、DOM nodeId 不跨调用，元素定位用 ref/backendNodeId；上传请用 `upload`。
2. **CLI 正式化**（commands.rs:1469-1486）：`hidden:false`；加 `--no-snapshot`（并/或加入 no_snapshot_commands()）；`tool_params_fn` 文件路径 canonicalize 为绝对路径并预检存在（Windows 分隔符由 std::fs::canonicalize 处理）；保持 `upload <ref> <file>` 单字命令（与 fill/type 同族，符合 AGENTS.md；不建议改成 `file upload` 空格形式——纯换皮且破坏现有测试与 mock）。
3. **driver 增强（方案 A+，仓库内 Browser4WebDriver.kt 覆写 upload）**：对齐 agent-browser 整条链——invokeOnElement → describeNode 取 backendNodeId → `setFileInputFiles(backendNodeId, paths)`；同时堵三个静默缺口：① 元素不存在 → 显式抛错（invokeOnElement 现返回 null 静默）；② 目标非 `<input type=file>` → 校验后清晰报错；③ 文件不存在 → 执行器预校验 + CLI 预检。
4. **多文件**：executor/tool 层已支持 `paths: List<String>`，CLI 端支持 file 可多值/或 paths 数组，测试覆盖 uploadCount==2。
5. **风险点记录**：三份别名 map 需同步（ToolAliases.kt 疑似死代码）；ToolSpecGenerator 只扫描库内 WebDriver 接口（在 Browser4WebDriver 新增 @MCP 不自动出 spec，需手写 ToolSpec+case，故走覆写而非新方法）；extension 中继对 CDP 方法无白名单（只锁 5 个 chrome.* 包装）但 typed 域 stub、只能 invoke 泛型路径 → **需真机验证 attach --extension 下 upload**；路径解析在后端机器（远端部署时客户端文件不可达，文档明示）。
6. **测试**：commands.rs unit（params/canonicalize/文件校验）→ ArgumentNormalizers/executor test → browser.rs e2e（多文件/非 file-input 报错/不存在文件）→ mock_server.rs → MCPToolControllerE2ETest；fixture 复用/新增。
7. **文档**：SKILL.md（现 grep=0 提及 upload）、help.rs/tips.rs。

---

## 3. 长文本输入：普遍问题 → 代码增强；X 专属问题 → issue/经验（源码修复 + 记录）

### 网络调研结论
- **普遍性**：非 x.com 独有。证据：
  - Playwright 官方文档与社区普遍报告 `type()` 逐键输入在富文本/框架编辑器丢字、不触发事件，成熟解法为加大 delay、`fill()`、`keyboard.insertText()`（一次整段）、或 evaluate 直写值（[runebook 归纳](https://runebook.dev/zh/docs/playwright/api/class-elementhandle/element-handle-type)）。
  - Puppeteer 长期 issue：#5629（Firefox insertText 未实现）、#3396（日文等字符 type 失败 → insertText 兜底）——字符集/键盘布局层面丢字是已知问题。
  - 富文本编辑器（ProseMirror/DraftJS/Slate 类：Medium、Notion、X…）是**一类**普遍难点：DOM 只是视图，编辑器内部状态模型不认合成事件/内联赋值；CDP 直接操作各有成败（[Medium CDP 发布失败复盘](https://dev.to/atlasforge_dev/why-i-couldnt-publish-on-medium-with-chrome-devtools-protocol-35cm)、[X 上"打了字没发出去"的原因分析](https://dev.to/ottoautomaton/your-ai-agent-typed-the-tweet-and-clicked-post-but-nothing-was-sent-heres-why-3gco)）。
- **成熟解法**：无单一银弹；业界组合 = 多策略（整段 insertText / execCommand('insertText') / 剪贴板粘贴 / 逐键慢速）+ **输入后回读校验**。Playwright insertText 整段输入被广泛验证（<textarea>/input 与多数 contenteditable），而 execCommand 整段插入在 X composer 被本次复盘证实有效。
- 结论：**在 Browser4 源码内增强 type（通用改进），X 的 remount 等专属细节记经验、不特殊处理**。

### 改动方案（锚点已核实，改动集中在 Browser4WebDriver + 执行器 + CLI 参数 + 文档）
1. **现状**：`type` 按码点逐字 `Input.insertText` + 90-240ms 延迟（Browser4WebDriver.typeSafe ~1232-1258）；执行器 case（BrowserTabToolExecutor.kt:1035-1126，允许参数 selector/text/submit/timeoutMillis）；CLI type 命令在 commands.rs。无整段批量模式、无回读校验。
2. **新增插入策略参数**：type 工具/命令支持 `method: auto|chars|exec`（可选）：auto 默认（≤150 字符走现状逐字；超长或多行走 exec 批量；失败自动降级重试一次），`exec` = 页面内整段 `document.execCommand('insertText')`（focus 元素后插入，input/textarea/contenteditable 均适用——本次复盘在 X composer 验证有效），`chars` = 强制现状逐字。
3. **回读校验（默认开，防静默丢字）**：插入后读元素文本（evaluateValue：input/textarea 取 value、contenteditable 取 innerText）与期望比对；不一致抛明确错误（含差异摘要）或自动 exec 重试一次；`verify=false` 可关（兼容旧行为）。此为通用改进：对普通表单输入更快更稳，对富文本编辑器（DraftJS/ProseMirror 类：X、Medium、Notion 等）提供 exec/整段路径。
4. **driver 层**：Browser4WebDriver.kt 新增 `typeExec(text, selector)`（focus + execCommand insertText + 读回文本）与 `typeChars`/readBack 辅助（复用 evaluateValue 定位，支持 CSS/XPath/backend 定位符）。
5. **X 独有部分（不修源码，记录）**：单帖 4 图上限静默拒绝（→ experience blocker，X 产品约束）；reply 编辑器 remount 丢内容（→ experience 教训：等页面完全加载 + exec 整段 + 校验）。长文本类通用结论在 help/SKILL 文档化（建议 exec 模式/分段/回读）。
6. **测试**：executor 单测（method 选择逻辑、verify 失败语义）→ e2e fixture（长文本 contenteditable + input）真实浏览器验证两种模式。

## 4. AX snapshot 截断 / dialog 缺失 / DOM 中段缺失（子代理 A 根因报告已收，2026-09-11）

### 根因结论（上游 vs 本仓库边界）
- aria snapshot 实现在外部依赖 pulsar-browser（browser4-base 4.11.12，root pom.xml:736；jar 全类字符串扫描**无 64KB/60KB 常量**）；Browser4 内可改：REST/agentic 选项与守卫、CLI 打印；数据采集层需改上游 browser4base 或升 base。
- 实现链：CLI handle_snapshot（main.rs:5269-5560，--stdout/raw 打印走 paginate_output 默认 2000 行/页，:5429-5450）→ browser_snapshot→aria_snapshot（MCPToolController.kt:110）→ BrowserTabToolExecutor ariaSnapshot 分支（:1281-1308，options：compact=true、depth=-1、boxes=true、limit→maxNodes=-1）→ driver.ariaSnapshot（上游 PulsarWebDriver.kt:1543-1555，**失败 `?: ""` 静默**）→ PageHandler.kt:130-167（每次现抓、selector/viewports/renderedAriaSnapshot）→ CDPSnapshotService（AX=Accessibility.getFullAXTree 一次抓取 :172；DOM=单次 getDocument(depth≈999999,pierce=true) :177/DomTreeHandler.kt:35-68；合并→渲染）。
- **"约 64KB 截断"**：全链路无字节截断常量，最可能是 CLI `--stdout/--raw` 分页（2000 行 ≈ 32B/行 ≈ 64KB 巧合）或数据采集不全导致文本自然中断。**验证：数截断处行数（2000 且有 footer 提示＝分页）；curl 直 POST /mcp/call-tool 对比字节数排除传输层。**
- **dialog 内容缺失**：渲染/合并层无 role=dialog 过滤（AriaSnapshotRenderer.kt 仅 compact 折叠 generic 容器）；根因在数据采集——AX 一次性抓取且**只在空树时重试**（AccessibilityHandler.kt:27-109，动态弹层晚于抓取即丢）；iframe 弹层帧标注虚假（stampFrameId 恒等 :138-140、每 frame 重复取主帧树 :65）；原生 JS dialog（alert/confirm）打开时 ariaSnapshot 等 CDP 读操作排队永不完成（Browser4WebDriver.kt:2031-2045）且工具路径**无守卫** + 上游 `?: ""` 静默 → 空输出。
- **DOM 中段缺失**：`cdp DOM.getDocument` 直通 Chrome 无加工；ariaSnapshot 内部单次大文档 getDocument 不可靠（DevTools 惯例是增量 requestChildNodes）——需运行时实证。

### 修复方案（按优先级）
- **P0（本仓库内）**
  1. CLI 分页语义：`--stdout/--raw` 超页默认截断（main.rs:5429-5450）——改为默认全量或显式 footer 提示 `--all`（commands.rs:1649 页大小、help.rs ~1910）。
  2. ariaSnapshot 工具前加 native-dialog 守卫：调用 driver.requireNoPendingDialog()（Browser4WebDriver.kt:2037-2045 已有 API），把"卡死/空串"变显式报错。
  3. 大页面使用提示/文档：`-v`/`--depth`/`--no-boxes`/`--selector`/`--limit` 约束（--no-boxes 约省一半体积）。
- **P1（本仓库内）**：CLI snapshot 暴露 `--limit`→maxNodes（server 端已支持，executor :1295 已透传）。
- **P2（上游 pulsar-browser，改 browser4base 后升 base）**：
  1. AccessibilityHandler：真按 frame/session 取 AX、实现 stampFrameId；重试/等待条件从"空树"改为"关键内容缺失/树大小异常"（可轮询 role=dialog 出现）。
  2. DomTreeHandler/CDPSnapshotService：大文档完整性校验（返回节点数 vs document.all.length），必要时增量 DOM.requestChildNodes 补齐；去掉 PulsarWebDriver.kt:1544/1550/1555 的 `?: ""` 静默。
  3. （若实证单次全量受限）DOM 采集改分段/增量。
- 测试：commands.rs snapshot 用例、MCPToolControllerE2ETest.kt、browser4-tests fixture（弹窗/大 DOM 页）。
- 验收：大 DOM 页 snapshot 输出不再意外中断（或显式提示分页）；弹窗打开时快照给出明确报错而非空串/卡死；dialog 内容在静态弹层场景可解析。

---

## 5. 微博数据类问题（站点相关，不修代码）

- 虚拟滚动漏帖 → 用官方 JSON API（mymblog）、DOM 仅旁证；`pic_infos`/`/large/` 原图改写；`text_raw` 清洗零宽字符等 → 全部走**经验记录 + 可复用 skill**（§6/§7），与复盘建议一致。

---

## 6. 工程细节（自主决定：文档 + 脚本资产，不改核心代码）

1. PowerShell 引号地狱 → 已是 `eval --file` 工作流；在 skill 文档固化"JS 写文件 + eval --file"规范（仓库已支持该命令）。
2. 每次 pwsh 全新进程（无状态）→ 长循环/大批量操作避免逐键，改为页面内 execCommand/整段替换；属于**调用方实践**，文档化即可。
3. 断点续跑 `state.json` 检查点 → 属任务流程资产：沉淀到 skill 的 SOP（§7），不进产品代码。
4. 若可行：把 `Paste-ImageToComposer` 类封装写成 skill 内脚本（browser4-cli 生态内脚本资产），供下次直接调用。

---

## 7. 经验复用机制评估与补齐（源码修复：机制缺口）

### 现状（代码证据）
- 机制 = experience 域工具（save/query/list/deep_learn），CLI 命令齐全（commands.rs ~4409-4503）。
- 知识模型 `KnowledgeFacts`（KnowledgeFacts.kt）：selectors / interactionHints / knownBlockers / antiPatterns 字段齐全，KnowledgeStore 支持读写/序列化（KnowledgeStore.kt ~456、597-620），query 结果可返回。
- **缺口 1：知识字段无写入方**——fast-learning save 只存 TraceRecord + 建空 HYPOTHESIS facts（ExperienceToolExecutor.kt ~193-200）；deep_learn 只填 pageType（~296-307）。selectors/hints/blockers 从未被代码填充 → 复盘结论（选择器、4 图上限、execCommand 优先等）**没有录入通道**。
- **缺口 2：Intent 分类电商导向**（IntentModels.kt：BUY/SEARCH/BOOK/LOGIN/CHECKOUT/EXTRACT/COMPARE/DOWNLOAD/READ/FILL_FORM/MONITOR/OTHER），"发帖/发布到 X" 会被关键词误分到 READ（post/story/blog 同属 READ 词表）→ 经验检索错位。
- **缺口 3**：复盘型知识 = "替代数据源（JSON API）、不该做什么（滚动）、容量上限、上传姿势（剪贴板粘贴）"，与模型现有"选择器 + 页面 blocker"视角不完全对齐；需要自由文本教训字段。

### 补齐方案（代码改动，最小集）
1. **知识写入通道（机制最大缺口）**：现 pipeline 中 KnowledgeFacts 的 selectors/interactionHints/knownBlockers/antiPatterns **从未被任何写入方填充**（save 只存 TraceRecord+建空 hypothesis facts：ExperienceToolExecutor.kt:193-200；deep_learn 只填 pageType：296-307；KnowledgeStore 仅序列化/读取：456、597-620）。改动：`experience_save`（工具 ToolSpec + executor handleSave + CLI experience-save CommandDef commands.rs:4410-4435）支持可选 `facts` JSON 参数（selectors/interaction_hints/known_blockers/anti_patterns 子集，BlockerInfo 结构见 KnowledgeFacts.kt:108-114），merge 进/新建 KnowledgeFacts（HYPOTHESIS 起步，后续 save/deep_learn 命中提升）——这样复盘式教训（"用 mymblog JSON 接口别滚虚拟滚动 DOM"、"X 单帖≤4 图且静默拒绝"、"图片粘贴后须校验预览计数"、"execCommand insertText 整段优于逐字 type"）可被下次 experience_query 自动召回。
2. **Intent 扩展**：IntentModels.kt 加 `PUBLISH`（displayName "Publish"；canonicalActions 如 navigate/compose/insert_text/upload/click；关键词 publish/post/tweet/compose/发帖/发布/上传媒体），否则 "post to x.com" 类意图被 READ 词表（含 post/story）误吞（IntentModels.kt:122）。TaskType（ExperienceModels.kt:13-36）可加 publish_post 对应项。
3. **落盘本次教训（实施阶段做）**：用新通道保存 x.com/weibo.com 两域 entries（intent=publish/post content）：weibo 域——JSON 数据源/字段坑/原图改写（antiPatterns+interactionHints）；x.com 域——composer 流程选择器（[data-testid="tweetButton"] 等）、4 图上限 blocker、粘贴上传姿势、reply remount 教训（blockers/hints）。
4. 一致性：若采纳 `facts` 参数，同时更新 ArgumentNormalizers（snake→camel）、ExperienceToolExecutorTest/ExtendedTest、commands.rs 测试与 SKILL 文档（experience 域）。

## 8. 任务验收与执行顺序（建议）

1. 本方案已定稿（2026-09-11，A/B/C 三个子代理调研报告全部收编：§1/§2/§3/§4/§7 均已落到文件:行号）。
2. 实施顺序（依赖最少的先行，每项遵循 AGENTS.md 清单：commands.rs → controller alias → 后端工具 → 文档 → 测试）：
   - P0：AX snapshot 仓库内修复（§4-P0：CLI 分页提示 + ariaSnapshot native-dialog 守卫 + CLI --limit 暴露，独立、收益大）
   - P0：upload 正式化 + driver 增强（§2：hidden:false、--no-snapshot、路径预检、backendNodeId 覆写与显式报错）
   - P1：attach/重连/status/list 浏览器身份显示（§1，跨 CLI+后端+扩展，需后端会话字段先行）
   - P1：type 长文本增强（§3：method auto/exec + 回读校验）
   - P2：经验机制补齐（§7：save 支持 facts 写入 + PUBLISH Intent）→ 用新通道把本次 weibo2x 教训落盘（§5/§6），并沉淀可复用 skill
   - P2：上游 pulsar-browser 项（§4-P2：AX 按 frame/增量采集、去静默）→ browser4base 改动后升 base 版本
