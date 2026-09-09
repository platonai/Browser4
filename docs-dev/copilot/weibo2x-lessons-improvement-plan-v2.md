# 微博 → x.com 复盘改进方案 v2（合并审查报告后定稿 + 开发计划）

- v1：`weibo2x-lessons-improvement-plan.md`（2026-09-11，含 A/B/C 调研）
- 审查：`weibo2x-lessons-improvement-plan-review.md`（2026-09-10，5 路取证，2 条阻断级事实错误 + 若干盲区）
- 本文件 = v1 + 审查修正，并给出**本轮执行范围**（在 Browser4-4.14 仓库内实施、含测试）与**延迟项**（需上游发版/外部依赖，记录理由）。行号以 2026-09-10/11 工作树为准。
- 原则不变：站点无关 → 改源码；站点相关 → 经验机制；X 独有 → 记录。

---

## §0 审查结论速览（相对 v1 的改动）

| v1 项 | 审查裁决 | v2 动作 |
|---|---|---|
| §4-P0 ariaSnapshot 加 dialog 守卫 | **已存在**（2026-08-05 commit 3843c6c，READ_PAGE_STATE_ACTIONS 含 ariaSnapshot，BrowserTabToolExecutor.kt:718-734）；真缺口是 **eval 家族无守卫 + 711-713 注释失实** | 改为"eval/evaluate 补守卫 + 修注释" |
| §4-P1 CLI 暴露 --limit | 默认渲染器 AriaSnapshotRenderer **不读 maxNodes**，只 -v nano 路径生效 | 本期不做 CLI --limit（避免新静默），文档说明 |
| §1 挂载点/行号 | Relay endpoint 在握手前打印；成功点=轮询处 1884-1889；check_session_ready/list_sessions 在 MCPToolController.kt | 按正确挂载点实施 |
| §2 三静默缺口 | 只有"元素不存在"是真静默；另两个是 CDP 报错晦涩；tool_params_fn 无 Result 不能做文件预检；CLI 多文件会被 args.rs 空格拼接 | CLI 走 main.rs 特判分发；驱动做三检查 |
| §3 回读 | CLI 已有 opt-in --verify（仅报告、不抛错）；driver/executor/batch 无；全等比对/读 textContent 有坑 | 下沉 driver + 语义分级 + 归一化后缀比对 |
| §7 Intent | 中文落 OTHER（非 READ）；英文 READ/PUBLISH **平局 READ 胜**；canonicalActions 泛化词白送分；taskTypeToIntent/测试硬断言漏改 | 四处同步改 |
| 上游行号 | v1 上游行号基于 4.11.2 快照；仓库依赖 4.11.12；本地 browser4base 活源码可用（A 已核对相关文件一致） | P2 上游项以 browser4base 活源码为准，本期**不**动上游代码 |

---

## §1 连错浏览器：连接身份显式可见（P1，本期实施）

### 事实修正（审查）
- 后端不存 channel：`createExtensionAttachedSession`（PulsarSessionManager.kt:668-718）channel 只进日志；MCPToolController.kt:513-515 过滤 channel。
- 扩展握手零身份：`extension.initialized params:[]`（chrome-extension/src/protocolHandlers.ts:77-82）；**商店版 dist 不入库**（.gitignore 首行 dist/），协议改动需商店发版 → **本期只走零扩展改动的 UA/Origin 头采集**（ExtensionWebSocketHandler.kt:47-76 现只打日志）。
- UA 判定：Edge UA 含 Chrome 词 → **先 Edg/EdgA/EdgiOS → Edge，再 Chrome/Chromium**；Brave/Opera/Vivaldi 保守归 Chromium 家族；透传原始 UA。Origin 不能辨品牌（扩展 ID 两浏览器相同 + allowedOrigins("*")）。
- 出口字段：check_session_ready（MCPToolController.kt:555-572，现只 {ready,healthy}）；list_sessions（448-469，现 sessionId/url/status/healthy/kind/ownsBrowser/createdAt/lastAccessedAt——**CLI parse 连 kind/ownsBrowser 都丢弃**，一并修）。
- CDP 路径：`verifyCdpEndpoint` 已解析 /json/version Browser（562-632）但只写日志（488-491）、不进 ManagedSession、不进 attach 响应（546-548 {sessionId}）；state.browser_channel 对 CDP 不设。
- 静默重建路径：with_session_paginated 恢复（1149-1156）、tab-new(2875)/tab-switch(3012) recover_stale=true、open/goto 重试（2181-2187/2279-2284）→ 按 `state.kind.is_attached()` 分流，attached 一律显式报错引导 re-attach，不静默开新 Browser4 会话。
- 会话纯内存（后端重启身份丢）；旧 CLI 对新增字段须容忍缺失，新 CLI 对旧后端同样。
- WS 无认证（pending 凭据仅路径 UUID + 120s 窗口 + Origin *）——nonce 防冒连单列延迟项。

### 实施清单（本期）
1. `ManagedSession.kt:18-28` 加 `var attachChannel: String? = null`、`var browserIdentity: BrowserIdentity? = null`（data：family/name/version/rawUa）。
2. `ExtensionWebSocketHandler.afterConnectionEstablished`：读 `handshakeHeaders["User-Agent"]`，调 `PulsarSessionManager.recordExtensionIdentity(sessionId, ua)`（含 UA 解析：Edg/EdgA/EdgiOS→Edge；Chrome→Chrome；其它含 Chromium 品牌 → Chromium-other；无匹配 → Unknown）。handshake 失败/解析失败不阻断绑定（记 Unknown + 原始 UA）。
3. `PulsarSessionManager`：createExtensionAttachedSession 存 channel 到 ManagedSession；recordExtensionIdentity 存 identity；暴露 getter。
4. `MCPToolController.kt`：check_session_ready 返回加 `requestedChannel`/`browserName`/`browserVersion`/`browserUa`；list_sessions 每项加 `channel`/`browserName`/`browserVersion`；CDP 路径把 verify 到的 Browser 存 ManagedSession 并随 attach 响应返回 `{sessionId, browser}`。
5. CLI：
   - attach 轮询成功点（main.rs:1884-1889）打印 `Connected to: <browserName/version>` + 请求 channel 不一致告警（比对按家族：请求 msedge vs 实际 Edge 家族 OK）。
   - `resolve_attached_session_id` 复用分支（1477-1479）回显身份。
   - `with_session_paginated`/相关恢复路径按 is_attached() 分流：attached 失效 → 明确报错 `attached session 已失效：browser4-cli attach --extension`（不再静默 open_session）。恢复成功打印"Using existing session（browser: …）"。
   - BackendSessionRecord + parse：加 browser/channel/kind/ownsBrowser；`connection_label`（3931-3950）优先后端身份（`Extension (Edge 138)` / `CDP (Chrome 138) …`）；不一致时 `Extension (请求 msedge/实际 Chrome)`。
   - handle_status 加"当前会话"小节。
   - attach --cdp 成功消息带品牌（main.rs:1989 区域）。
6. 测试：ExtensionWebSocketHandler 新单测（UA 解析/存储）、PulsarSessionManagerTest（channel 存储、list 字段）、CLI main.rs 单测（parse 新字段、connection_label、恢复分流、attach 打印含身份）、mock/e2e 相关回归。旧后端缺字段 → 解析容错（unwrap_or）。
7. 文档：help.rs attach/list/status、tips.rs TIPS_ATTACH、skills/browser4-cli/references/attach.md、SKILL.md。

### 延迟项（记录理由）
- 扩展协议加身份字段/extension.hello：需商店发版，本期不做。
- nonce 防冒连（WS 查询参数校验）：安全性增强，独立于身份显示，单列后续。
- Web 状态面板（SystemStatusController）身份显示：UI 侧，后续。

---

## §2 CDP nodeId / upload（P0，本期实施；driver 覆写含风险验证项）

### 事实修正（审查）
- upload 全链路已存在但 hidden:true：commands.rs:1469-1486 → browser_file_upload（别名：MCPToolController.kt:126 活代码、AgenticCliRunner.kt:589 活代码、**ToolAliases.kt:24 死代码全仓零引用→删除**）→ executor case（BrowserTabToolExecutor.kt:1263-1268）→ 上游 PulsarWebDriver.upload（invokeOnElement + setFileInputFiles(nodeId)）。已有 e2e（browser.rs:1145-1152、browser4-tests/browser4-rest-tests MCPToolControllerE2ETest:480-492）与 fixture（static/b4/mcp-tool-controller-interactive-fixture.html `#file-input`）。
- "静默缺口"勘误：① 元素不存在 → 真静默（RobustRPC.kt:104-108 null 跳过）；② 非 file input / 文件不存在 → CDP -32000 报错但晦涩且白跑两次重试 → 需预检/友好化；③ CLI `tool_params_fn` 签名 `fn(&HashMap)->Value` 无 Result，**文件预检必须走 main.rs 特判分发**（act 先例 main.rs:22074-22097）；④ CLI 多文件被 args.rs:399-428 空格拼接（`upload e5 a b` → `paths:["a b"]`），需 act 式特判。
- backendNodeId 覆写：`BrowserProtocol.setFileInputFiles` 只有 nodeId 形参 → 必须 `executeCdpCommand("DOM.setFileInputFiles", {files, backendNodeId})`（PulsarWebDriver.kt:420-425 公开；直连与扩展中继均可达）。NodeRef 的 backendNodeId 对 CSS/XPath 是 0（DOMHandler.kt:403/459）→ describeNode 必要。PulsarWebDriver open class、Browser4WebDriver 已有 rpc + 11 个覆写先例，selectOption 探针范式（2013-2017）可借用；覆写对所有 MCP/agent 流量生效（AgentToolManager.kt:506）；ToolSpec 由库 @MCP 扫描生成，覆写不影响。
- 扩展中继：CDP 方法无白名单（5 个 chrome.* 包装，protocolHandlers.ts:37-43）；typed 域 stub，只能 invoke 泛型路径 → **driver 覆写必须配真机 e2e（requires_browser4）验证**。
- mock_server.rs:6605 现有用例参数顺序写反（`upload <file> "#file-input"` vs 契约 `[ref, file]`）→ 修复。
- 路径拓扑：读文件的是浏览器进程所在机器；CLI 预检仅在 CLI=浏览器主机时有效（本地模式）；Windows canonicalize 的 `\\?\` 前缀发远端 Linux 无意义 → 文档说明部署拓扑限制；后端不做 Files.exists 预检（可能误报），改在**驱动层只做错误友好化**（把 CDP 报错翻译），CLI 本地模式做存在性预检。

### 实施清单（本期）
1. CLI 正式化（commands.rs:1469-1486）：
   - `hidden:false`；ArgDef 文案修正（ref 与 fill 对齐、file 单数）；加 `--no-snapshot` option 并接线（或并入 no_snapshot_commands）。
   - main.rs 特判（dispatch 层，参照 act）：`upload <ref> <file>...` 多文件支持（无空格拼接；不拆含空格路径），空 file 报错（不再发 `paths:[""]`）；本地模式文件存在性预检 + canonicalize（仅当 CLI 与浏览器同机即本地 daemon/attach 本机；远端 --endpoint 跳过并提示）。
   - 删除 ToolAliases.kt 死代码（或恢复引用——选择删除并在 controller/AgenticCliRunner 校验无引用）。
2. Driver 覆写（Browser4WebDriver.kt）`override suspend fun upload(selector, paths)`：
   - invokeOnElement 内：元素不存在 → 抛明确错误（探针范式）；非 `<input type=file>`（允许 hidden? file input 可能 hidden——按 type 判断即可，含 `[type=file]` 需 exists）→ 预检报错；describeNode 取 backendNodeId → executeCdpCommand setFileInputFiles(backendNodeId, files)；错误翻译：文件路径不可达/-32000 → 中文友好信息（提示绝对路径/文件位于浏览器主机）。
   - 幂等（覆盖式，块内重试安全）。
3. executor case 保持薄壳（错误已在 driver）。
4. 测试：commands.rs 单测（params 映射、多文件特判、空参）；main.rs 特判单测；mock_server.rs 修正参数顺序 + 负向（元素不存在/非 file input）；browser.rs e2e 增补（happy path 保留 + 多文件 + 负向）——**requires_browser4 真机项在能起后端时跑**；fixture 增 multiple 属性场景（可后置）；browser4-rest-tests MCPToolControllerE2ETest 增补负向。
5. 文档（Documentation Update Rule）：SKILL.md、help.rs、tips.rs、cli README、references/upload 说明（如无则建小节），README 如提及命令面。

---

## §3 长文本输入（P1，本期实施；先定设计再编码）

### 设计决策（依据审查，先定后写）
- **分层**：输入机制全部下沉 driver（Browser4WebDriver 新增 typeAuto/typeChars(现状改名或保留)/typeExec/readBackText/typeVerify），executor 只编排（method/verify 透传、submit 时序、timeout），CLI 只加参数。PulsarWebDriver 兜底分支不再膨胀。
- **策略**：`method=auto|chars|exec`（CLI/tool 参数，默认 auto）。
  - chars = 现状逐码点 insertText（90-240ms 节奏，保留光标语义）。
  - exec = 焦点元素内 `document.execCommand('insertText', false, text)` **整段**；返回 false 或文本未变化 → 立即抛错（建议 chars）或按 verify 校验；**不自动 chars 重试长文本**（超时预算）。
  - auto = 目标为 textarea/contenteditable 且（len>150 或含 \n）→ exec；否则 chars。`<input>` 永不 exec（\n 会被吞）。
- **可编辑性预检**：非 contenteditable/非 input/textarea、disabled/readOnly → 抛明确错误（现成表达式 BrowserTabToolExecutor.kt:965）；exec 前检查 `el.isContentEditable || (input/textarea && !disabled && !readOnly)`，execCommand 返回 false 即降级报错。
- **光标/选区**：exec 路径 focus 后 collapse 到末尾（input setSelectionRange(99999,99999) 现成 1199-1201；contenteditable 用 Selection collapse 到末尾）；**语义取舍明确**：exec 面向"整段灌入"场景，不承诺保留链式光标语义（chain 场景用 chars）；KDoc 写明。
- **回读**：driver `verify` 参数（默认 **false**，避免默认改变全部 MCP/batch 行为）；verify=true 时：
  - 先读 oldText（input/textarea.value 或 contenteditable innerText），插入后读 newText；
  - 归一化：`\u00a0`→空格、`\r\n`→`\n`、trim 尾部换行/空白；
  - **后缀比对** `newText === oldText + text`（chars 为追加语义）；exec 若编辑器改写/拒绝 → mismatch；
  - mismatch → `IllegalStateException`（含 old/expected/actual 摘要，建议 --method chars 或手工检查）；不重试。
  - exec 模式**隐含 verify=true**（execCommand 无逐字进度，静默失败风险最高）；chars 默认 false（CLI 可开）。
- **CLI --verify 语义**：保留现有 CLI 层"仅报告"行为（verify_element_text main.rs:10419-10475，opt-in）不动；新驱动 verify 通过 `--verify-strict`? —— 简化：CLI 新增 `--method`；新驱动校验由 `--verify` **同时透传** driver verify=true 且保持 CLI 报告逻辑（双保险）；文档写明两级语义。`--verify`/`--focus` 目前不进 tool_params → 本次把 `--verify` 接进 tool_params（新工具参数 verify），`--focus` 保持 CLI 本地（历史行为）。
  - 注：CLI 层 verify 的坑（全等比对/读 textContent）在 driver verify 里修（归一化+后缀），CLI 层仅报告不阻塞。
- **submit 顺序**：executor 编排保持 typeBlock（含 driver 内 verify）→ submit 一次；driver 抛错则 submit 不执行。
- **无 selector 分支**（1099-1122，driver.type(text)）：method/verify 仅当提供 selector 时可用；无 selector 传 method=exec/verify=true → 明确报错"需要 selector"（回读目标不明）。
- **限制文档化**：execCommand deprecated（Chrome/FF/Safari 仍可用、无移除时间表）、无键盘事件链（快捷键/自动补全站点无效）、无 IME composition 模拟、剪贴板粘贴策略本期不做（从零实现成本高）。
- **fill 边界**：fill=整段替换（fillSafe/fillValueJs），exec-type=光标处插入触发 input、尊重 maxlength——文档写明。

### 实施清单（本期）
1. Browser4WebDriver：readBackText(selector)（按元素类型取 value/innerText+归一化）、typeExec(selector,text,verify)、typeAuto(selector,text,method,verify)（内部复用 typeSafe/码点循环）；编辑性预检；错误消息。
2. executor type 分支（1035-1126）：allowed 增加 method/verify（两处 allowed 1038/1100 与无 selector 分支语义）；有 selector 分支在 b4Driver 时调 typeAuto(method,verify)；其余保留兜底。
3. CLI commands.rs type CommandDef：加 `--method` option（值 auto|chars|exec），tool_params 带 method；`--verify` 透传 verify=true（进 params）；help 文案。
4. ToolSpec（BrowserTabToolExecutor.kt:182-198）补 method/verify 声明。
5. 测试：driver/executor 单测（策略选择、可编辑性预检、exec 返回 false、verify 归一化/后缀比对/不匹配抛错）；fixture 扩展 keyboard fixture（input/textarea/contenteditable/maxlength/readonly + beforeinput/input 序列 + isTrusted）；e2e 真机（长文本两种模式、multiline、maxlength 截断、readonly 报错）。
6. 文档：SKILL.md type 小节、help.rs、references（长文本输入指南）。

---

## §4 AX snapshot（P0 仓库内 + 文档；上游 P2 本期不动）

### 事实修正（审查）
- 全链路无 64KB 常量（4.11.12 jar 全类扫描 + 全仓 .kt/.rs/.java）。
- paginate_output（main.rs:10175-10218）是**静默行切片**（2000 行/页，无 TTY 判断）；footer **已含 --all**（format_pagination_footer 10220-10227）但走 **stderr**（5446 eprintln）→ 管道/重定向下丢失；文件始终全量落盘（5371）；默认路径只 30 行预览（5530-5555）；`--all`/`--json`/`--quiet`/`--page-size 0` 绕过分页。
- ariaSnapshot **守卫已存在**（见 §0）；真缺口 = eval 家族。
- dialog 缺失候选（按可信序）：① -v 视口路径 paint-order 剪枝（DOMStateBuilder.kt:159-162/288-338，paintOrder>1000 → children=null；modal 高 z-index/transform/opacity 是候选；只影响 serializableTree/-v 与 nano fallback）；② AX 帧抓取缺陷（AccessibilityHandler：getFullAXTree 每帧同树不带 frameId、stampFrameId no-op、仅空树重试）+ `<dialog>` 无 implicitRole（AriaSnapshotRenderer.kt:198-240）→ 退化 generic；③ DOM 单发 getDocument(999999,pierce) 大文档不可靠。compact 不是元凶。
- CLI 5290-5292 主动剥离分页参数；服务端 HTMLSnapshotUtils 分页对 snapshot 是死路径。

### 实施清单（本期，仓库内）
1. **eval 家族 dialog 守卫**（归本节的 dialog 主题）：
   - BrowserTabToolExecutor：evaluate/evaluateValue/evaluateDetail（及 eval 入口）在 READ/写 DOM 前调用 requireNoPendingDialog()（DOM_AFFECTING_ACTIONS 是否豁免由调用语义定：先全部补守卫，若某评估用于读取 pending dialog 状态则白名单化——dialogStatus 工具保留直读）；修正 711-713 注释失实；交互动作（click/fill/type/press 等）守卫评估后按 Input 域实证再定（本期至少修注释与 eval，交互动作如无真机证据不擅自加，文档注明）。
   - 单测：pending dialog 时 eval 抛错、dialogStatus 可用。
2. **分页 footer 可见性**：
   - raw/--stdout 分页时 footer 双写 stderr+stdout？——**决策：管道场景 stdout 尾行加简短提示**（`… [--all 查看全部 N 行]`），stderr 完整 footer 保留（交互 TTY 原样）。理由：不破坏 stdout 机器可读？其实 stdout 加提示会破坏 JSON 输出——但 raw 模式本就是人类文本。snapshot grep footer 走 stdout(10124) 已不一致 → 统一为 stdout（grep 先例）。文档补 `--page-size 0`/`--all`。
   - 测试：paginate footer 断言（现有 27422-27508 区域扩展）。
3. **CLI 文档**：大页面约束建议（-v/--depth/--selector/--no-boxes）；说明 --limit 仅 -v 模式生效（本期不新增 --limit 以免静默无效）。
4. 大页面/弹窗 e2e fixture 增补（browser4-tests）视环境可跑则跑。

### 延迟项（上游 browser4base，需发版，本期不改，记录）：
- AccessibilityHandler 按 frame 取 AX + stampFrameId 实现 + 非空残缺重试（AX 缓存/弹层轮询）。
- AriaSnapshotRenderer 支持 maxNodes + `<dialog>` implicitRole 映射；DOM 增量 requestChildNodes + document.all.length 对账；nodeValue 500 截断放开；PulsarWebDriver `?: ""` 去静默（需改调用链空安全）；paint-order 剪枝策略复核。
- CLI --limit 需上游渲染器支持后才可全路径生效。

---

## §5 微博数据类（站点相关，不修代码）
同 v1：全部走经验记录（§7）+ 可复用 skill 沉淀（落盘动作见 §7 实施）。

## §6 工程细节（文档/资产）
同 v1；额外补：PowerShell `--facts @file` 已随 §7 CLI 支持；不新增产品代码。

---

## §7 经验复用机制（P2，本期实施）

### 事实修正（审查）
- 四字段（selectors/interactionHints/knownBlockers/antiPatterns）生产代码零写入方；**deep_learn 不跑任何分析工具/LLM**（描述失实，顺带修文案或代码——本期修 handleDeepLearn 描述/提示文案，行为不做大改）。
- 存储：文件级 YAML + 手写 SnakeYAML Map（mapFromFacts/mapToFacts），@JsonProperty 不影响落盘；四字段往返完整 → facts 通道**无需改序列化器**。
- 状态：status 枚举（HYPOTHESIS/CANDIDATE/VERIFIED/CONTESTED）；merge 出的 HYPOTHESIS 也会被 query L1 命中（tier P3 hint）→ 可用。VERIFIED 锁定无强制 → **merge 必须自查**：VERIFIED 时拒绝改 selectors（hints/blockers 允许并入需定义——决策：VERIFIED 也拒绝 selectors 与 antiPatterns 改写，仅允许 hints/blockers note 追加？为安全：VERIFIED 全部拒绝改写并返回 message 提示）——**决策：status==VERIFIED 时 facts merge 全拒（提示走 experience-save 新 trace）**，保守。
- merge 竞态：saveFacts 有 domain Mutex，loadFacts 无锁 → merge 读改写放同一域锁内（在 handleSave 中 domain 锁内 load+merge+save；或给 KnowledgeStore 加 synchronized merge 方法——决策：KnowledgeStore 增加 `suspend fun mergeFacts(domain, intent, factsPatch): KnowledgeFacts` 内部持域锁）。
- 传输双形态：facts 参数接受 String（readValue）或结构化 Map/List（convertValue）；嵌套 snake_case 不归一化 → DTO 字段加 @JsonAlias（interaction_hints 等，或手写双读）。dec：在 patch DTO 上用 @JsonAlias 双读。
- @file：CLI `--facts @file` 支持（main.rs 展开，参照 --sql/--selector 先例）。
- 自动沉淀孪生路径 PemKnowledgeProvider 不接 facts（无来源，文档声明）。
- 落盘 key：(domain, intent)，文件 knowledge/facts/<domain>/<intent>.yaml；根目录 knowledge/ 相对 CWD（配置项 knowledge.dir 不存在于代码——顺带把 docs/experience-memory.md:284 的错误声明修正或不动——本期不动存储路径，避免行为变更；文档纠错列入）。
- query L2 同域多 intent 的 url_pattern firstOrNull 顺序问题：本期不修（记录）。
- ExperienceSaveResult 增加 facts 回显字段（merged/status）。
- SKILL（skills/browser4-experience/SKILL.md）布局/行为描述与实现不符 → 本期修正文档（§8 布局、save/query 参数、失败 anti-patterns 说法）。

### 实施清单
1. `KnowledgeFactsPatch` DTO（domain/intent 外：selectors(Map<String,VerifiedSelector> 或简 {key: {primary,fallbacks?,note?}})/interaction_hints(List<String>)/known_blockers(List<{type,selector?,action?,note?}>)/anti_patterns(List<String>)；@JsonAlias 双读）。
2. KnowledgeStore.mergeFacts(domain, intent, patch)：域锁内 load/merge/save；VERIFIED 拒绝 selectors/antiPatterns 覆写；返回合并结果与拒绝说明；不产生 successes（状态停 HYPOTHESIS）。
3. experience_save：ToolSpec.Arg facts（String）；handleSave 解析双形态 → mergeFacts → result 加 `facts_merged/facts_status/facts_rejected`。
4. Intent：加 PUBLISH（displayName "Publish"；canonicalActions 只放 compose/insert_text/upload_image 等专属词；+4 关键词 publish/post_to/tweet/发帖/发布/发推/上传媒体/配图/带图）；**READ 词表移除 post**（避免平局）；中文关键词块（发帖/发布/推文/微博/搬运）。taskTypeToIntent 加 publish 分支（publish/post/x_post）。TaskType 加 PUBLISH_POST（ExperienceModels.kt）→ ExperienceModelsExtendedTest.kt:436 `12→13` 与遍历测试更新。
5. CLI experience-save：加 `--facts` option + `@file` 展开 + tool_params。
6. 测试：merge（新事实/追加/VERIFIED 拒绝/竞态域锁）、双形态解析、Intent 分类（"post to x"→PUBLISH、中文"发帖到 X"→PUBLISH、"read article"→READ 不回归）、taskTypeToIntent、CLI params。
7. handleDeepLearn 描述/提示文案修正（ToolSpec description、commands.rs description、main.rs:13121 提示）。
8. **落盘本次教训**：x.com（composer selectors、tweetButton、4 图上限 blocker、粘贴上传、remount 教训）与 weibo.com（mymblog API、字段坑、原图改写、零宽字符）各 publish/intent entries。
9. 文档：skills/browser4-experience/SKILL.md 纠错 + experience 域说明。

---

## §8 执行顺序 v2（本轮）

1. P0a：eval 家族 dialog 守卫 + 注释修正 + 单测（agentic）
2. P0b：snapshot 分页 footer/文档（CLI + 单测）
3. P0c：upload CLI 正式化 + ToolAliases 死代码删除 + mock 参数顺序修复 + 单测
4. P0d：upload driver 覆写 + executor + 单测（真机 e2e 环境允许则跑）
5. P1a：attach 身份（后端 → CLI → 测试 → 文档）
6. P1b：type 增强（driver → executor → CLI → fixture/测试）
7. P2a：experience facts 通道 + PUBLISH（含四处同步）+ 测试
8. P2b：经验落盘 + skill/文档纠错
9. 回归：cargo test --bin browser4-cli；mvn -pl browser4-agentic,browser4-rest,browser4-core/browser4-browser 相关模块测试；文档全量更新（README/SKILL/help/tips/references）
10. 收尾：本文件状态标注（完成/延迟项清单）

## §9 延迟/不做项登记（决策记录）
- 上游 pulsar-browser 变更（§4 延迟项）：browser4base 改后需发版+升 base，本期不实施，留 ticket。
- 扩展协议身份字段/extension.hello、商店发版：延迟。
- nonce WS 认证、SystemStatusController 面板、query L2 顺序、knowledge.dir 配置化：延迟/不做（记录理由）。
- 剪贴板粘贴第三策略、IME composition：明确排除并文档化。
- CLI --limit：等上游渲染器支持。

*状态：v2 定稿 2026-09-11；执行记录见 §10（随实施追加）。*

## §10 执行日志（2026-09-11 实施中，随进度更新）

- [x] P0a：eval 家族 dialog 守卫（DIALOG_GUARDED_ACTIONS = READ∪EVAL）+ 注释修正 + BrowserTabToolExecutorTest 三测试（guard×2/proceeds）→ agentic 全模块测试通过
- [x] P0b：snapshot raw/--stdout 截断时 stdout 镜像提示行（管道可见，TTY 仅 stderr）+ commands.rs 选项文案（--all/--page-size 0）
- [x] P0c：upload CLI 正式化——hidden:false、--no-snapshot、build_upload_args 多文件不拼接（args.rs+单测）、main.rs 路径预检/canonicalize（本地拓扑）、mock_server 参数顺序修复；删除死代码 ToolAliases.kt
- [x] P0d：Browser4WebDriver.upload 覆写（单 RPC：describeNode→backendNodeId→DOM.setFileInputFiles；元素缺失/非 file input/空路径显式报错；错误翻译）→ browser4-browser/rest 编译通过；真机 e2e（test_e2e_form_controls_and_exports）upload 通过
- [x] P0e：e2e 增补——fixture #file-input 加 multiple；browser.rs 场景加多文件/非 file-input 负向/缺失文件负向/200 字 exec 长文本 type 断言
- [x] P1a：attach 身份显示——后端 ManagedSession.attachChannel/browserIdentity + BrowserIdentity.parse（UA，Edg 优先）+ ExtensionWebSocketHandler 握手 UA 采集 + check_session_ready/list_sessions/attach 响应加字段 + CLI attach 轮询成功点打印/冲突告警 + CDP attach 身份 + with_session 恢复 attached 分流（不静默换新浏览器）+ connection_label_full/list/status 会话小节 + BackendSessionRecord 扩展解析；BrowserIdentityTest 等单测通过（rest 定向测试通过）
- [x] P1b：type 增强——Browser4WebDriver.typeAuto/typeExec/typeReadBack/verify（method auto|chars|exec、归一化后缀回读、exec 拒绝即报错）+ executor method/verify 透传 + ToolSpec + CLI --method（含语义校验需 ref）+ 文档化决策
- [x] P2a：经验机制——FactsPatch/mergeFacts（域锁原子、VERIFIED 拒绝、YAML 往返）+ experience_save facts 双形态解析 + 回显字段 + PUBLISH Intent（去 READ 'post'、中文词表）+ taskTypeToIntent publish + TaskType.PUBLISH_POST + CLI --facts @file + 测试（merge×4、save facts×3、Intent×3）→ agentic 定向+全模块通过
- [x] P2b：经验落盘（weibo.com/x.com entries）——用新 bundle 后端（18182）实跑：`experience save` x.com/publish（2 selectors/6 hints/2 blockers/3 anti）+ weibo.com/publish（5 hints/2 blockers/3 anti），facts_merged=true、P2 召回验证（query 返回 intent_match + selectors）；知识文件：D:\tmp\weibo2x\knowledge-run\knowledge\{facts,experience,traces}\{x.com,weibo.com}\publish.yaml
- [x] 文档更新（子代理完成 10 文件：skills/browser4-cli/SKILL.md+references(新 upload.md/snapshot.md/attach.md/quickstart.md)、skills/browser4-experience/SKILL.md 勘误、docs/experience-memory.md 勘误、README/README.zh/cli README×2）
- [x] 基线修复：help.rs public_command_name 补 44 个缺失 spaced 映射 + CATEGORY_TITLES 加 network → 两个既有失败测试修复（cargo 全量 1297 passed / 0 failed）
- [x] 验证矩阵（全绿）：
  - cargo test --bin browser4-cli：1297 passed（含新增 CLI 单测：channel_family_conflict / connection_label_full / parse identity / build_upload_args / 帮助映射）
  - mvn browser4-agentic（-am）：全模块测试通过（含 ExperienceModelsTest PUBLISH、KnowledgeStoreMergeFactsTest×4、save-facts×3、dialog 守卫×3）
  - mvn browser4-rest（-am）定向：BrowserIdentityTest / MCPToolControllerTest / PulsarSessionManagerTest 通过
  - 真机 e2e（本地新 bundle，内置模式）：test_e2e_form_controls_and_exports 通过——新 upload 覆写（单/多文件）、负向（非 file-input 明确报错、缺失文件 CLI 拦截）、exec 200 字长文本真实 Chrome 输入全部验证
  - mock e2e：test_upload_error_backend_failure 通过（参数顺序修复后）
- [x] 过程中自行决策记录：① 上传/负向错误在 RobustRPC 块内改抛 WebDriverException（否则被包装成通用文案，掩盖真实原因——真机验证发现并修复）；② 经验落盘/真机验证使用本地构建 runtime-bundle（此前 e2e 自动回退远程旧 bundle 导致“旧代码”假象）；③ CLI 本地残留 attach 状态干扰 → 用 -s manual 命名会话；④ 基线两个失败测试为历史遗留（network/code 帮助映射缺失），属文档/帮助缺陷一并修复
