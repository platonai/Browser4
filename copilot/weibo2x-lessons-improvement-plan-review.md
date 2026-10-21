# 《微博 → x.com 复盘改进方案》审查报告

- 日期：2026-09-10
- 审查对象：[weibo2x-lessons-improvement-plan.md](./weibo2x-lessons-improvement-plan.md)
- 审查方式：对 §1/§2/§3/§4/§7 共约 50 条代码断言逐条对照真实源码取证（5 路并行核查）
- 总体评价：方案调研质量高——绝大多数锚点行号精确、机制判断成立，P0/P1/P2 仓库边界划分基本正确；但存在 **2 条事实性错误（会导致重复劳动或无效改动）**、若干处会导致返工的设计盲区，以及一个贯穿全篇的上游版本风险。

---

## 一、必须修正的事实性错误（阻断级）

### 1. §4-P0-2「ariaSnapshot 加 native-dialog 守卫」——守卫已上线，属重复建设

方案称"工具路径**无守卫** + 上游 `?: ""` 静默 → 空输出"。实际：

- `BrowserTabToolExecutor.callFunctionOn`（browser4-agentic/.../builtin/BrowserTabToolExecutor.kt:718-734）已对 `READ_PAGE_STATE_ACTIONS` 调用 `requireNoPendingDialog()`，**ariaSnapshot 正在守卫集合内**（DEFAULT_READ_PAGE_STATE_ACTIONS:27-38，第 30 行）；单工具与 batch 路径均覆盖（MCPToolController.kt:1026/1093、handleBatchTool:660）。守卫自 2026-08-05（commit 3843c6c65d4）存在。
- API 本身：Browser4WebDriver.kt:2036-2044，`public fun requireNoPendingDialog()`，无参，抛 `IllegalStateException`，消息明确指引 dialog-accept/dismiss。
- **真正的缺口方案没抓到**：`evaluate/eval/evaluateValue/evaluateDetail` **不在**守卫集合（被归入 DOM_AFFECTING_ACTIONS:43-56），而守卫处注释（711-713）自称 evaluate 已保护——注释与代码不一致，dialog 打开时 eval 仍会排队挂起；click/fill/type/press/hover/drag/scroll/check/upload/selectOption 等交互动作同样无守卫（Input.* 域是否挂起需真机验证，Runtime.evaluate 类必挂）。另有 `dialogStatus` 工具可查询（1323-1338）。
- **结论**：P0-2 应改写为"给 eval 家族补守卫 + 修正注释 + 评估交互动作"，而不是给 ariaSnapshot 加守卫。

### 2. §4-P1「CLI 暴露 `--limit`」——默认快照路径会被上游静默忽略

- CLI snapshot 现有参数集（commands.rs:1634-1652）确实**没有** `--limit`，新增成立；executor 也确实透传 `limit→maxNodes`（BrowserTabToolExecutor.kt:1295，白名单 1284）。
- 但上游**默认全树渲染器 AriaSnapshotRenderer 完全不读 `options.maxNodes`**；只有 `-v` 数字视口路径的 NanoAriaSnapshotRenderer（NanoAriaSnapshotRenderer.kt:10-16）实现了 maxNodes + 截断 footer。默认快照（不带 -v）走 AriaSnapshotRenderer（DomModels.kt:572-580）。
- 即：只改 CLI 会产生"传了 `--limit` 但默认模式无效、且无任何提示"的新静默问题——恰是方案自己最反对的失败模式。
- **结论**：此项必须连带改上游渲染器（归入 P2），或在 CLI 侧明确仅 `-v` 模式生效并给出提示。

### 3. §1 两处断言错位

- 方案称 attach 成功后打印 `Relay endpoint: … (browser channel: …)` + `format_session_opened_message`。实际：
  - Relay endpoint 行在 main.rs:1825-1831，**扩展连接之前**就打印（轮询 1846 才开始）；扩展连接成功实际打印 `Extension connected and healthy!` / `Session ready`（1885-1889）。
  - `format_session_opened_message`（main.rs:950-956）**从不在 extension 路径调用**，只用于 CDP attach（1990-1993）和新建/重试会话（1437、2187、2284）。
  - → 方案改动 4「attach 成功点打印实际浏览器」的挂载点必须在轮询成功处（1884-1889），挂 1827 行会打印尚未握手的"身份"。
- `check_session_ready`（555-572）与 `list_sessions`（448-469）实际都在 **MCPToolController.kt**（前者只回 `{ready, healthy}`；后者字段 sessionId/url/status/healthy/kind/ownsBrowser/createdAt/lastAccessedAt，无浏览器身份）；PulsarSessionManager.kt 同行号是 createAttachedSession / verifyCdpEndpoint。

### 4. §2「元素不存在/非 file input 时静默成功」——一半不准确

- 元素不存在 → **确属静默**：RobustRPC.kt:104-108 `if (node != null) block(node) else null`，选择器解析为 null 时整块跳过，invokeOnElement 返回 null，upload 返回 Unit，executor 不检查。驱动不健康时 invokeWithRetry 也返回 null（183-186）。
- 非 file input / 文件不存在 → **CDP 层会报错**：`DOM.setFileInputFiles` 协议面只面向 file input（cdp-protocol/browser_protocol.json:8341-8370，接受 backendNodeId），Chromium 对非 file 节点/不可打开路径返回 -32000；只是错误信息晦涩、且 RobustRPC 永久错误表不含此类消息 → 白跑两次重试后抛出。方案"三个静默缺口"应改为"**一个静默缺口（元素不存在）+ 两个错误语义差（非 file input / 文件不存在报错晦涩）**"。
- 两个会直接卡住实施的设计问题：
  - **`tool_params_fn` 签名不返回 Result**（commands.rs:124，`fn(&HashMap<String,Value>) -> Value`），全仓无任何一例在其中做文件系统校验。"canonicalize + 预检存在"无法按方案写法实现，必须走 main.rs 定制分发（有 `act`、`wait --download` 特判先例，main.rs:22074-22097），方案需写明选这条路。
  - **CLI 多文件当前走不通**：CLI 只有一个 `file` 位置参数，多余位置参数会被空格拼接进最后一个槽位（args.rs:399-428）——`upload e5 a.txt b.txt` 静默变成 `paths:["a.txt b.txt"]` 这一不存在的单路径。后端 `paths: List<String>` 就绪（BrowserTabToolExecutor.kt:1263-1268），CLI 侧需 act 式特判；按空白拆分对含空格 Windows 路径会出错。

### 5. §3「无回读校验」——CLI 层已有 opt-in 实现，前提过时

- type 命令（commands.rs:1151-1186，hidden:false、batch_supported:true）已有 `--verify`（及 `--focus`）选项；`verify_element_text()`（main.rs:10419-10475）在 type/fill 后经 browser_evaluate 回读（无 ref 读 document.activeElement），接线在 10539-10555，超时错误后也自动回读（10561-10579）。
- 但它**仅报告、不抛错、不重试**（输出 "PARTIALLY typed"/"mismatch"），且 **driver/executor/batch 路径完全没有**（batch 在服务端执行，CLI verify 不参与）。方案应改为"回读下沉 driver 层并强化语义"，而非"新增回读"。
- 现有实现两个必须避开的坑：① 全等比对 `actual == expected`（main.rs:10457）——type 是光标处**追加**，元素原有内容必误报；② contenteditable 读 `textContent`（10431）——`<br>`/块级换行丢失，多行必误报。方案提的 innerText 方向对，但还需空白/尾随换行归一化（`\u00a0`→空格、trim 末尾换行、`\r\n`→`\n`）+ 后缀比对（newValue === oldValue + text）。
- 其他勘误：MCP 工具名是 `browser_press_sequentially`（commands.rs:1169，别名到 type），不是 `browser_type`；对外 ToolSpec（BrowserTabToolExecutor.kt:182-198）只声明 text/selector，submit/timeoutMillis 被接受但未文档化——新增 method/verify 时 spec、两个 allowed(...)（1038、1100）、CLI options/tool_params_fn 必须同步（注意现在 `--verify`/`--focus` 根本不进 tool_params）。

### 6. §7 Intent 误分方向与平局陷阱

- 分类器是**纯英文关键词打分规则**（IntentModels.kt:71-136：canonicalActions 子串 +2、displayName +3、专属关键词 +4，maxByOrNull 取分最高，全 0 → OTHER；注释自承 "Phase 1 keyword match"）。**无任何中文词表**。
- 英文 "post to x" → READ 关键词块（:122 含 read/article/news/blog/post/story）+4 → 误分 READ **属实**；中文"发帖到 X"不命中任何词表 → 落 **OTHER**，不是方案说的"被 READ 误吞"（加中文关键词发帖/发布/上传媒体是对的，但现状描述对中文不成立）。
- **平局陷阱（方案未提）**：`maxByOrNull` 平局返回枚举顺序靠前者，READ 在 PUBLISH 之前。若不从 READ 词表删除 `post`（或不给 PUBLISH 更高权重），"post to x" 会 READ(+4)/PUBLISH(+4) 平局且 **READ 仍胜出**，改动静默失效。
- 方案给 PUBLISH 的 canonicalActions 含 `click`/`navigate` 等泛化词，而打分是子串包含（81-83）→ 任何含这些词的文本白送 PUBLISH +2，造成跨意图误分。canonicalActions 应只放 compose/insert_text/upload 等专属性动作。
- 两处必改同步点方案漏了：
  - `taskTypeToIntent` fallback 映射（ExperienceToolExecutor.kt:338-354）无 publish 分支——只传 `task_type=publish_post` 而无 `--intent` 仍落 OTHER；
  - ExperienceModelsExtendedTest.kt:436 硬断言 `assertEquals(12, TaskType.entries.size)`（424 还有遍历全部 entries 的测试），加 publish_post 必须改 13。

---

## 二、重要风险与遗漏（按章节）

### §1 浏览器身份显示

- **UA 判定顺序**：Edge（Chromium）UA 为 `... Chrome/138.0.0.0 Safari/537.36 Edg/138.0.0.0`——同时含 Chrome/Safari/Edg 品牌词；Android 是 `EdgA/`、iOS 是 `EdgiOS/`，旧 EdgeHTML 才是 `Edge/`。判定必须**先 Edg/EdgA/EdgiOS → Edge 家族，再 Chrome/Chromium → Chrome 家族**，否则 Edge 必误判为 Chrome。Brave(`Brave/`)/Opera(`OPR/`)/Vivaldi 均可装扩展且 UA 含 Chrome/，应保守归入 Chromium 并透传原始 UA。UA 可伪造，只防"意外连错"不防恶意冒充。
- **Origin 头不能区分品牌**：商店版扩展在 Chrome/Edge 中 ID 相同（main.rs:1659-1660），WS Origin 都是 `chrome-extension://jdcmdidbg...`；且 ExtensionWebSocketConfig.kt:39 `setAllowedOrigins("*")`。品牌判定只能靠 UA。
- **商店版扩展不随仓库更新**：chrome-extension/.gitignore 第 1 行就是 `dist/`，工作树无 dist，需 `npm run build`（package.json:19）。任何扩展协议改动（extension.initialized 加字段，protocolHandlers.ts:77-82 现为 `params: []` 零身份）对存量商店用户**完全无效**，必须走商店发版。→ **UA/Origin 头解析（ExtensionWebSocketHandler.kt:47-76，目前仅读 remoteAddress 打日志，handshakeHeaders 未读未存）零扩展改动、在 browser4-rest 内闭环，应明确为主路径**；协议改动仅作增强。
- **WS 握手无认证，nonce 比方案描述更必要**：pending 连接唯一凭据是路径里的 sessionId UUID（PulsarSessionManager.kt:685/705），pending 窗口 120 秒（677）；`BROWSER4_EXTENSION_TOKEN` 只用于 connect.html 的 UI 自动审批（authToken.tsx:104-110），**不随 WS 校验**。配合 `*` Origin，任何本地网页/进程知道 UUID 即可在窗口内冒连。建议 nonce 作为 WS 查询参数在 afterConnectionEstablished/onExtensionConnected 校验（nonce 防冒连、UA 防错品牌，二者不可互替）。
- **"先连先得"竞争**：onExtensionConnected 同步绑定（725-747），pending 槽位被首个连接者移除；错误浏览器先连后，正确浏览器再连会命中 "No pending extension connection"（727）被关闭。若要**强制**拒绝错误品牌，必须在绑定前比对 UA 并抛错关 WS（保留槽位等待正确浏览器）；事后告警则 CLI 打印点必须在轮询成功处。
- **CDP 路径同类问题被低估**：CDP attach 不经 ExtensionWebSocketHandler；后端 `verifyCdpEndpoint` 抓 `/json/version` 的 `Browser` 字段（PulsarSessionManager.kt:562-632，解析 590-594）**已探测到品牌但只写日志（488-491），未存 ManagedSession、未进 attach 响应**（attach 响应只回 `{sessionId}`，MCPToolController.kt:546-548）。且 CDP 路径**从不写 state.browser_channel**（main.rs:1974-1984 只设 cdp_endpoint），list 对 CDP 会话只显示 endpoint。只改 extension 侧则 CDP"连错"依旧不可见。CLI 侧渠道解析（daemon.rs:4003 起，进程扫描+端口探测）同样可能连错。
- **静默重建路径不止 with_session_paginated**：
  - with_session_paginated 恢复分支（main.rs:1149-1156）invalidate + create_session → open_session（909-946），后者无打印且把 kind 重置为 Browser4Launched、browser_channel=None（939-941）；
  - tab-new（2875）/tab-switch（3012）以 recover_stale=true 运行，命中 is_stale_session_error（"target closed" 等，http.rs:486-494）后对 attached 会话也会静默 open_session；
  - handle_open/handle_goto 导航失败重试也有 invalidate+create_session（2181-2187、2279-2284），正常情况被 resolve_attached_session_id（1305-1318）拦截，但改造时应一并核查。
  - 修复应在恢复分支按 `state.kind.is_attached()`（state.rs:45-51）分流，不能影响 Browser4Launched 的正常恢复。后端 resolveHealthySession（335-416）已保护 attached 会话不被隐式重建。
- **出口字段兼容性**：check_session_ready/list_sessions 加字段对旧 CLI 无害；新 CLI 对旧后端须容忍缺失。CLI parse_backend_session_records（4297-4339）目前连后端**已返回**的 kind/ownsBrowser（461-462）都丢弃，改造时一并解析；会话纯内存（sessions map，PulsarSessionManager.kt:114），后端重启身份丢失须回退本地 state。ManagedSession.kt:18-28 加 `var attachChannel/browserIdentity: String? = null` 对命名参数构造源码兼容、无持久化迁移负担。
- **其他**：extension 会话健康判定特殊（driver 离开 INIT 即可用，306-315），身份字段应为独立元数据不参与 healthy；断线重连旧 STOPPED 会话不被 idle reaper 回收（93-98、828-841），list 加身份后僵尸会话更显眼；Web 状态面板（SystemStatusController，status 指向 {base_url}/status，main.rs:18504）同样缺身份；ExtensionWebSocketHandler 无单测，PulsarSessionManagerTest.kt 是现成落点；文档面 tips.rs:295-302、skills/browser4-cli/references/attach.md 需同步。

### §2 upload 链路

- **别名"三份同步"应为"两处活代码 + 一处删死代码"**：
  - MCPToolController.kt FRONTEND_TOOL_NAME_ALIASES（108-152，upload 在 126）：线上分发主路径（就绪探测 364、工具列表过滤 1250、normalizeFrontendToolCall 1333）；
  - AgenticCliRunner.kt（571-610，upload 在 589）：活代码，JVM 内 agent 直连分发用（976）；已漂移，缺 browser_pdf_save、browser_generate_locator、browser_frame_list/switch/main 共 5 条；
  - ToolAliases.kt（6-44，upload 在 24）：**全仓零引用，确认为死代码**（被同包私有 val 遮蔽），且已漂移（缺 network_requests/network_request/network_route/network_unroute/har_start/har_stop 6 条）——建议删除或恢复引用。
- **backendNodeId 路径不能走 typed API**：BrowserProtocol.setFileInputFiles 只有 nodeId 形参（BrowserProtocol.kt:271、DirectChromeProtocol.kt:700-702）。必须走 `executeCdpCommand("DOM.setFileInputFiles", mapOf("files" to paths, "backendNodeId" to id))`（PulsarWebDriver.kt:420-425 公开，底层 remoteDevTools.invoke 泛型通道，DirectChromeProtocol.kt:961-963，直连与扩展中继均可达；CDP 协议支持 backendNodeId，browser_protocol.json:8358-8363）。
- **覆写可行性已验证**：PulsarWebDriver 是 open class（快照 L66）；Browser4WebDriver.kt（browser4-core/browser4-browser，open class : PulsarWebDriver，72-77）已自建 `private val rpc = RobustRPC(this)`（720-729，因上游 rpc 私有），已有 11 个覆写先例（selectOption/click/dblclick/press/drag/dialogAccept 等）；selectOption 覆写（2013-2017）的"探针→抛 IllegalArgumentException→super"范式可直接借用；describeNode 是 BrowserProtocol 公开方法（242-248），返回 Node 带 backendNodeId。注意 CSS/XPath 解析出的 NodeRef **backendNodeId=0**（DOMHandler.kt:403/459），describeNode 步骤确有必要；只有 backend 定位符才预填。生产会话均绑定 Browser4WebDriver（AgentToolManager.kt:506、PulsarSessionManager.kt:981），覆写对所有 MCP/agent 流量生效；库接口 upload 已有 @MCP（WebDriver.kt:2128-2136），覆写不影响 ToolSpecGenerator（其只读库 sources jar 的 @MCP，ToolSpecGenerator.kt:99-109/308-310）。
- **幂等**：setFileInputFiles 是覆盖式设置，块内重试安全。
- **路径拓扑陷阱（方案已提但需细化）**：读文件的是**浏览器进程所在机器**。CLI 预检只在 CLI/浏览器同机时有意义；远端 daemon / attach --extension 拓扑下浏览器在扩展宿主机器、后端可能在远端——后端做 Files.exists 预检反而可能误报，CLI 侧预检才是对的一侧。Windows canonicalize 产出 `\\?\` verbatim 路径并解析符号链接，发给远端 Linux 浏览器无意义。方案应按部署拓扑分别说明。
- **扩展中继真机验证（方案已提，成立）**：extension 侧白名单恰为 chrome.debugger.attach/detach/sendCommand、chrome.tabs.create/remove 5 个（protocolHandlers.ts:37-43），CDP 方法作为 sendCommand 参数透传无方法级白名单（52-65）；Kotlin 侧 ExtensionDevToolsService 所有 typed 域均 `error("not available via extension transport; use invoke()")`（216-253），仅泛型 invoke（59-113）可达。DOM 域启用时序、sendCommand 传文件路径语义无 e2e 覆盖，按 AGENTS.md CDP 审查门必须补 fixture + requires_browser4 e2e。
- **OOPIF/iframe 残留风险**：agent-browser 参考实现（不在本仓库，在 D:\codebase\browser-automation\agent-browser\cli\src\native\browser.rs:1942-1982）特意传 effective_session_id；backendNodeId 不能跨进程 iframe 定位（需正确 flat auto-attach 会话）。Browser4 有 `fbn:` frame 定位符，但根会话 CDP 能否触达 OOPIF 内 file input 未验证。
- **测试/契约问题**：mock_server.rs:6605 现有用例参数顺序写反（`upload <文件> "#file-input"`，契约是 `[ref, file]`，正确用法见 browser.rs:1146），mock 只校验错误透传故未暴露；commands.rs 无 upload 参数映射单测；e2e 只有 happy path（browser.rs:1145-1152）和 mock 错误透传，缺元素不存在/非 file input/文件不存在/多文件四类负向用例；fixture（mcp-tool-controller-interactive-fixture.html:33）只有单文件 input，无 multiple 属性、无 iframe/OOPIF、无非 file 靶子；MCPToolControllerE2ETest.kt:480-492 实际在 **browser4-tests/browser4-rest-tests** 模块（不是 browser4-rest/src/test）；upload batch_supported:true 但无 batch 用例；空参数时 `get_str(args,"file").unwrap_or_default()` 会把 `paths:[""]` 发出去。
- **文档义务**：upload 目前在 SKILL.md、help.rs 零提及（hidden 所致）；hidden:false 触发仓库 Documentation Update Rule（SKILL.md、README/README.zh、cli README、help.rs、tips.rs、references）。ArgDef 文案 "absolute paths"（复数）与单参数现实矛盾；ref 描述应与 fill/type 标准措辞对齐。

### §3 type 长文本增强

- **execCommand 废弃性**：`document.execCommand('insertText')` 已被 MDN 标记 deprecated（spec 停维但 Chrome/Edge/Firefox 仍可用，无移除时间表）。它返回 `boolean`——typeExec **必须检查返回值**并在 false 时降级，不能只靠回读发现失败。按 AGENTS.md CDP 审查门：execCommand 属 JS 合成编辑行为，**不产生任何 keydown/keypress/keyup 事件**（与 CDP Input.insertText 一样没有键盘事件链），其 beforeinput/input 事件在 Chrome 中受信状态与 CDP 路径有差异；依赖键盘事件序列的站点（快捷键、自动补全触发）两条路径都覆盖不到。"X composer 有效"是个例不能外推——exec 应定位为"尽力快速路径 + 失败回退 chars"。
- **非编辑元素盲区**：对普通非 contenteditable 的 div（即使 tabindex 可 focus），execCommand('insertText') **直接返回 false 无效果**；`<input>` 插入 `\n` 被静默忽略；只读/禁用元素无效。typeExec 必须先做可编辑性判断（`el.isContentEditable` 或 input/textarea 且非 disabled/readOnly；现成表达式在 BrowserTabToolExecutor.kt:965）。
- **选区/光标**：execCommand 在当前选区插入，需 focus 后 collapse 到末尾（input 用 setSelectionRange，现成代码 Browser4WebDriver.kt:1199-1201；contenteditable 用 Selection/range）。但 typeSafe 的 KDoc（1234-1239）特意保留光标位置以支持链式操作（ArrowLeft→type），exec 路径一律 collapse 到末尾会破坏该语义，需明确取舍。focus 可复用 invokeOnElement(focus=true)、page.focusOnSelector（920/951）或 JS el.focus()（fillValueJs 219）。
- **多行动机的代码核对**：现状 typeSafe（Browser4WebDriver.kt:1232-1258）对 `\n`/`\t` 等控制字符走 `press(charString)`（1244-1245）而非 insertText——"多行走 exec"动机成立；但多行插入 `<input>` 本身无意义（换行被吞），auto 按"含换行"切 exec 后回读会因换行丢失报不一致——需按元素类型区分（仅 textarea/contenteditable 允许多行 exec）。
- **React/富文本**：execCommand insertText 触发原生 input 事件，现代 React（onChange 监听 input）通常能感知（这正是社区常用绕过手段）；但 ProseMirror/Slate/Draft.js/CodeMirror 自有 beforeinput 事务处理，可能拒绝或改写插入。
- **分层建议**：150 字符阈值、auto 降级、回读都属输入机制，应放 **driver 层**（Browser4WebDriver 新增 typeAuto/typeExec/typeChars/readBack）：① REST 直连、agent tools、examples、batch 等非 CLI 调用方都受益；② 回读依赖 evaluateValue 本就在 driver 手边；③ executor 里 PulsarWebDriver 兜底重复代码（1051-1066）不应再膨胀。executor 仅透传 method/verify + 保留超时/submit 编排。
- **回读默认开要慎重**：driver 层默认 verify=on 会改变所有 MCP 调用方（含 batch）行为，归一化不完善就是批量误报。建议首版"回读但仅在 execCommand 返回 false/空值或明确不一致时抛错"，或失败语义分级（advisory vs hard fail），保留 verify=false。注意与 CLI 现有 `--verify`（opt-in 仅报告）的语义关系——后端默认开则 CLI 不传也校验，语义从"opt-in 报告"变"默认强制"，需明确两层关系。
- **submit 顺序约束（方案未提）**：现有 submit = type 后 press("Enter") → delay(300) → URL 未变则 submitFormFallback（1071-1080，fill 同构 1151-1166）。① **回读必须在 submit 之前**（Enter 可能触发导航，元素随页面销毁）；② exec 失败降级 chars 重试时 submit 只能在最终成功后执行一次；③ exec 后焦点是否留在目标元素需验证。
- **性能账**：逐字 150 字符 ≈ 13.5–36s（90-240ms/字），CLI --timeout 默认 30s（commands.rs:1179-1182）——阈值 150 与默认超时量级吻合，动机合理；但 chars 重试一次可能再次超时，重试耗时要计入预算。
- **反检测权衡**：逐字 insertText 保留 90-240ms 随机节奏（类人）；整段 execCommand 瞬时插入且无键盘事件，对有按键节奏分析的反爬站点是更明显的机器特征。auto 默认对短文本用 chars 恰好缓解，但方案应明示该安全权衡。仓库有静态守卫 CdpTrapCheck.kt:44-51 记录 insertText 竞速坑。
- **其他遗漏**：
  - batch 步骤的 verify 结果如何回传、失败是否中断后续步骤，方案未定义；
  - batch 编译时仅 keydown/keyup 注入 preFocusSelector（main.rs:21210-21221），type/fill 靠 executor 内部 focus——typeExec 依赖 focus 在 batch 场景由 invokeOnElement(focus=true) 保证，无需改 CLI，但应显式确认；
  - 全仓库无任何 paste/clipboard/execCommand 代码——"剪贴板粘贴"第三策略需从零实现（CDP Browser.grantPermissions + 焦点 + paste 键，跨平台权限坑多），方案应说明取舍或显式排除；
  - 无 composition（输入法）模拟，insertText/execCommand 都只提交已上屏文本、不产生 composition 事件，IME 场景两条路径均不支持，需注明限制；
  - 无 selector 的 type 分支（1099-1122）走原生 driver.type(text)（有代理对拆分 bug、无回读目标），auto/exec/verify 对该分支行为未定义（回读只能读 activeElement，CLI 侧已有此模式）；
  - fill 与 exec 型 type 语义边界需写明（fill=整段替换直接设 value/textContent，fillSafe/fillValueJs 1290-1292/201-219；exec type=光标处插入、触发 input、尊重 maxlength）；
  - 测试：键盘 fixture mcp-tool-controller-keyboard-fixture.html 已有 input/textarea/contenteditable/maxlength/readonly 靶子，可扩展事件序列记录（beforeinput/input/keydown 顺序与 isTrusted），按审查门必须真机 e2e。
  - 补充：randomDelayMillis 定义在外部依赖 pulsar-browser 中，仓库内以 AGENTS.md:36 与 CdpTrapCheck.kt:49 佐证 90-240ms 区间。

### §4 snapshot 截断 / dialog / DOM 缺失

- **"64KB 截断"推断不成立**：全链路无字节截断常量（全仓 .kt/.rs/.java 及 pulsar-src 扫描无 64KB/60KB 快照常量；命中项均无关）。`paginate_output`（main.rs:10175-10218）是**静默行切片**而非交互分页——无 TTY/is_terminal 判断、无按键等待，管道下 stdout 只得第 1 页 2000 行；footer 已存在且**已含 `--all`**（format_pagination_footer 10220-10227）但走 **stderr**（5446 eprintln），管道易丢。ARIA 行带 ref/box/name 典型 40–100+ 字符，2000 行约 80–200KB，"≈64KB 巧合"无依据。
- **缓解因素方案未提**：快照**文件始终全量落盘**（save_snapshot，main.rs:5371），截断只发生在 stdout；默认（非 raw）路径只给 30 行预览（5530-5555）；`--all`/`--json`/`--quiet` 经 skip_pagination（10260-10262）全部绕过；`--page-size 0` 已等于不限页但文档未写。→ P0-1 应缩小为"footer 通道/可见性修复 + 文档"（如管道场景 footer 提 stdout 或 stderr 强提示），不是"默认全量"（会淹没终端）。另注：snapshot footer 走 stderr 而 snapshot grep footer 走 stdout（10124），行为不一致；CLI 在 5290-5292 主动剥离 page/page-size/all 再发服务器，服务端分页（HTMLSnapshotUtils.kt:27-56）对 snapshot 是死代码路径；http 传输层无截断。
- **"dialog 缺失"更可能的直接原因（方案没排查到）**：
  - **paint-order 剪枝**：DOMStateBuilder.kt:159-162 + 288-338 对 `paintOrder > 1000` 的节点返回 `children = null`（整棵子树丢弃）；paintOrder 叠加量 = 层叠上下文深度 × 1000（CDPSnapshotService.kt:106）——**modal/dialog 恰是高 z-index/transform/opacity 层叠上下文，是被剪枝首要候选**。该 serializableTree 正是 `-v` 数字视口路径（PageHandler.kt:151-161）与 nano fallback 走的树；全树路径（AriaSnapshotRenderer 走 optimized 树）不受此剪枝影响。
  - **`<dialog>` 无 implicitRole 映射**：两个渲染器的 implicitRole（AriaSnapshotRenderer.kt:198-240）都没有原生 dialog 元素映射，AX role 缺失时 dialog 退化为 generic。
  - compact 不是元凶：compact 折叠 generic 容器时**提升子节点**（return children），role=dialog 不在折叠名单（shouldCompactNode 156-168、shouldCollapseGenericNode 281-292），dialog 内文本通常仍在。方案把 dialog 缺失归因于 compact/截断方向不准。
  - 真凶候选序：(a) -v 路径 paint-order 剪枝；(b) AX 帧抓取缺陷（下条）+ `<dialog>` 无 implicit role；(c) DOM 抓取/帧缺失误判。
- **AX 帧抓取缺陷比方案描述更糟**：AccessibilityHandler.kt:22-112，`repeat(5)` 重试仅在 allNodes 为空时（93-107，空则 sleep 250ms）——非空但残缺（丢帧/丢子树）永不重试；帧循环 64-91 内 **65 行 `bp.getFullAXTree(depth)` 根本没传 frameId**——对每个 frame 重复抓同一棵主帧树；`stampFrameId` 是恒等 no-op（138-140，`return node`）；去重键 `(frameId, nodeId)`（80）导致主帧节点按每 frame 重复装入。
  - CDP 层 `Accessibility.getFullAXTree` **确有可选 frameId 参数**（browser_protocol.json:417-443），但上游绑定 DirectChromeProtocol.kt:612-619 只发 depth、BrowserProtocol.kt:261 无 frameId 形参——"按 frame 取 AX"需先扩协议绑定；且 frameId 大概率只覆盖同进程 frame，OOPIF 是独立 target 仍需 Target.attachToTarget flatten 会话，AXNode 自身无 frameId 字段（274-350）。P2-1 工作量比方案写的大，必须真机/iframe fixture 验证。
  - stampFrameId 修法：用循环里已知 frameId 盖章（待绑定支持），或经 backendDOMNodeId→DOM 树 frameId 反查（DomTreeHandler.mapNode 81-166 已为每节点记 frameId）。
- **DOM 采集**：现状单发 `getDocument(depth=999999, pierce=true)`（DomTreeHandler.kt:35-68，maxDepth 默认 999999）；完整性校验可用 JS 读 `document.all.length` 对账，缺子树用 `DOM.requestChildNodes(nodeId, depth, pierce)` 增量补抓——方向合理。另有单文本节点 500 字符截断（DOMStateBuilder.kt:132/639-643）方案未提。
- **去 `?: ""`**：PulsarWebDriver.kt:1543-1556 三个 ariaSnapshot 重载均以 `invokeDeferredSilently(...) ?: ""` 结尾（1544/1550/1555）；RobustRPC.invokeDeferredSilently（254-263）捕获 ChromeRPCException 后仅日志/intercept 返回 null（失败数超阈才抛 IllegalWebDriverStateException）。改为显式异常方向正确，但需调整 PageHandler/调用链空安全；interceptChromeException 致命情况本就抛，可安全改为普通失败也抛 WebDriverException。
- **上游版本缺口（贯穿全篇）**：`.test-sessions/pulsar-src/` 是解压的 **pulsar-browser 4.11.2** source jar（pom.properties 证实），仓库依赖是 **browser4-base/pulsar-bom 4.11.12**（pom.xml:736，经 browser4-dependencies/pom.xml:75 导入）。方案所有上游行号与 4.11.2 全部对得上，但 4.11.12 可能已漂移；本机 ~/.m2 无 ai.platon 工件，`.test-sessions/pulsar-src-4.11.3/`、`pb41112/` 为空目录。**P2 全部项动手前必须先取得 4.11.12 source jar 复核行号/逻辑**。
- **agent 路径不对称**：agent 工具说明建议 `viewports="all"`（ToolSpecification.kt:29），而 ViewportSpec.parse("all") 返回 null（ViewportSpec.kt:24-27）→ 回退全树渲染器（不受剪枝但忽略 maxNodes）；agent 与 CLI 快照链路不对称，方案未覆盖。

### §7 经验机制

- **核心判断属实且实际更弱**：KnowledgeFacts 四字段（selectors:31/interactionHints:33/knownBlockers:34/antiPatterns:35，BlockerInfo 108-114）在生产代码中**零写入方**——handleSave 只 saveTrace+updateStats 并在 facts 不存在时 createHypothesis 空 facts（ExperienceToolExecutor.kt:193-200）；**deep_learn（262-326）不仅"只填 pageType"（296-307 copy taskType→pageType + promoteToVerified 310），它不调用任何 LLM、也不运行 htmlsnapshot/inspect**——ToolSpec 描述（96-99 "run analysis tools (htmlsnapshot summary, inspect)"）与 CLI 提示 "Running deep learning analysis"（main.rs:13121）均与实现不符，DeepLearnResult.selectorsFound 恒为 0。不要指望 deep_learn 后续"提炼"手工 facts；facts 充实只能靠 facts 写入通道本身。
- **持久化层零阻力但有陷阱**：存储是**文件级 YAML、手写 SnakeYAML Map 序列化 + 原子写**（KnowledgeStore.kt），**不是 Jackson**——KnowledgeFacts 上的 @JsonProperty 对落盘不起作用；若新增模型字段必须同步改 mapFromFacts（587-603）/mapToFacts（606-622）。好消息：现有四字段 YAML 往返已完整实现，facts 通道构造/merge KnowledgeFacts 后调 saveFacts（195-205）即可，**无需改序列化器**。
- **status 是枚举不是字符串**：VerificationStatus = HYPOTHESIS/CANDIDATE/VERIFIED/CONTESTED（IntentModels.kt:318-330），落盘写 `name.lowercase()`；createHypothesis 工厂在 KnowledgeFacts.kt:42-57。置信度在 ExperienceStats（51-63，派生值，首次 0.50），提升阈值 conf≥0.60 且 successes≥2→CANDIDATE、≥0.85 且 ≥5→VERIFIED（KnowledgeStore.kt:214-218）。手工 merge facts 不产生 successes 计数，状态停在 HYPOTHESIS，但 query L1 命中即返回、无 stats 时 tier 按 0.50/P3——仍会以 P3 hint 档被召回，机制可用。方案"HYPOTHESIS 置信度起步"把两套概念混为一谈。
- **facts 传输类型坑**：CLI 先例把 JSON 作为字符串传（trace 即如此，commands.rs:4429）；但 LLM 经 MCP 可能把 facts 传成结构化对象（args 中为 LinkedHashMap），paramString（AbstractToolExecutor.kt:130-143）的 `v.toString()` 会产出 `{a=b}` 式 JVM map 字符串，Jackson 解析必失败——executor 必须同时接受 String（readValue）与 Map/List（objectMapper.convertValue）。
- **嵌套键名不归一化**：DefaultArgumentNormalizer（ArgumentNormalizers.kt:7-70）只遍历 args 顶层 key 做 snake→camel；facts 内部 key（interaction_hints 等）不转换——DTO 解析需显式接受 snake_case（@JsonAlias 或双读，现有双读先例 140-141/243-244）。
- **@file 现状**：@file 约定存在但逐命令手写（--sql main.rs:8081、--schema 6964-6970、--selector 8968-8970、crawl --args 13657+），通用 tool_params_fn 调度路径不做 @file 展开；trace 至今不支持 @file。`--facts @file` 需 main.rs 新写解析；PowerShell 大内联 JSON 引号痛苦，建议直接做 @file。
- **VERIFIED 锁定无强制**：模型文档（KnowledgeFacts.kt:10-16）声明 VERIFIED 后 selectors LOCKED/immutable，但 saveFacts 无条件覆盖（195-205）。merge 必须自查 status==VERIFIED 时拒绝改写 selectors（hints/blockers 可否并入需定义），否则借 facts 通道可覆写已验证知识。
- **merge 竞态**：loadFacts 无锁、saveFacts 仅 domain 级 Mutex（196-202），read-modify-write 非原子；现有 check-then-create（196-200）已有此竞态，merge 会放大覆盖窗口。
- **落盘路径与 entry key（§7.3 实施需要）**：
  - 根目录硬编码相对路径 `knowledge/`（KnowledgeStore.kt:706 `Path.of("knowledge")`，随后端进程 CWD；docs/experience-memory.md:284 宣称的 `knowledge.dir` 配置项**代码中不存在**）；Spring Bean 在 ExperienceToolMountConfiguration.kt:29-33。
  - 布局：`facts/<domain>/<intent>.yaml`、`experience/<domain>/<intent>.yaml`（stats）、`traces/<domain>/<时间戳>-<intent>-<id8>.yaml`、`patterns/{families,categories,universal}/*.yaml`。
  - **entry key 是 (domain, intent)，不是 URL 模式**；domain = UrlNormalizer.extractDomain（去 www.，78-85）。本次两域落盘文件即 `knowledge/facts/weibo.com/publish.yaml` 与 `knowledge/facts/x.com/publish.yaml`（url_pattern 只是 facts 内字段，用于 query L2 兜底）。
- **query 检索机制**（KnowledgeStore.kt:256-292）：L1 精确 (domain, intentKey)；L2 同域任意 intent 的 url_pattern 匹配（firstOrNull，目录顺序不确定——同域多 intent 时可能错配，如 x.com 同时有 read/publish）；L3 patterns 跨站匹配（**目前无任何写入方，永不命中**）；L6 P5。PUBLISH 不生效则 publish 类查询永远命不中 publish facts。
- **自动沉淀孪生路径**：agent 自动 deposit 走 PemKnowledgeProvider.kt:98-104，与 handleSave 一样只建空 hypothesis（文件注释自承 "Keep in sync with ExperienceToolExecutor.handleSave"）。facts 通道只改 MCP 工具路径时，自动路径仍无法写 facts（自动路径无 facts 来源，逻辑上可接受，但方案应声明）。
- **测试面**：ExperienceToolExecutorTest.kt 与 ExperienceToolExecutorExtendedTest.kt 均在 browser4-agentic/src/test/kotlin/.../experience/；同目录还有 ExperienceModelsTest.kt、**ExperienceModelsExtendedTest.kt**（含 Intent 分类断言与 TaskType 12 硬断言 :436）、KnowledgeStoreTest/Extended/Concurrency/PersistenceFailureTest、UrlNormalizerTest；CLI 单测在 commands.rs:9002-9185；experience 命令 e2e_coverage: Excluded，无 e2e。
- **现有 SKILL 文档错误（更新时需纠错而非增补）**：skills/browser4-experience/SKILL.md §8 存储布局（`sites/<domain>.yaml`、`.traces/`、`.wal/`）与实现（traces/experience/facts/patterns）完全不符；:62 称失败时 "Failed selectors added to anti-patterns"（无此逻辑）；:56-59 称 save 接受 selectors/steps、:72 称 query 有 task_type 参数（均不存在）。docs/experience-memory.md 布局描述与实现一致。
- 小项：ExperienceListEntry.taskTypes 硬编码 emptyList()（KnowledgeStore.kt:335），list 输出不含 task type；merge 后 ExperienceSaveResult 不回显 facts 写入状态，建议在 result 中加字段以便 CLI/LLM 确认落盘。
- ToolSpec 是 executor 手写 map（ExperienceToolExecutor.kt:47-100 init 块），非扫描；experience 工具经 MCPToolController.extractDomain（889-906）按下划线拆 domain=experience + toolSpec keys 反查 method，无需 controller alias。新增 facts 参数改 3 处：① ToolSpec arguments（49-55，LLM 可见）；② handleSave 解析+merge；③ CLI commands.rs:4410-4435（OptionDef + tool_params_fn）；CLI handler 透传，纯字符串选项不用改 main.rs。

---

## 三、对执行顺序（方案 §8）的调整建议

1. **P0 清单修正**：
   - **删除**"ariaSnapshot native-dialog 守卫"（已存在），换成"eval/evaluate 家族补 dialog 守卫 + 修正 711-713 注释 + 评估交互动作是否需守卫"；
   - snapshot 项缩小为 pagination footer 可见性修复（footer 已含 --all，主要是 stderr/stdout 通道与文档），工作量比方案写的小；
   - upload 拆两步：CLI 正式化（hidden:false、--no-snapshot、空参/多参特判、文档）独立零风险先做；driver 覆写（backendNodeId 经 executeCdpCommand、元素不存在显式抛错、非 file input 预检报错）依赖真机验证 attach --extension，不宜同批。
2. **§1 仍为 P1，但重排内部顺序**：后端 ManagedSession 字段 + WS 握手 UA 采集（browser4-rest 内闭环、零扩展改动）先行，UA 判定注意 Edg 优先顺序；CDP 路径品牌透出顺手一起做（/json/version 数据已解析在手里，只需存储+回传）；CLI 打印点挂轮询成功处；静默 open_session 兜底按 is_attached() 分流；扩展协议改动与 nonce 单列、不阻塞主线。
3. **§3 实施前先补设计**：回读语义（advisory vs hard fail）、与现有 CLI --verify 的关系、回读在 submit 之前、driver/executor 分层（机制在 driver、编排 在 executor）四点定下来再写代码；否则批量误报与 submit 时序风险高。
4. **§7 两项可并行但都小**：facts 通道持久化零阻力、工程量集中在 executor merge（String/Map 双形态、VERIFIED 锁定、并发）+ CLI --facts @file；PUBLISH 务必连同 ① READ 词表移除 post（或权重差）、② canonicalActions 去泛化词、③ taskTypeToIntent 加 publish 分支、④ TaskType 12→13 硬断言测试 四处一起改，否则英文平局仍判 READ、中文仍落 OTHER。
5. **§4-P2（上游）前置动作**：先取得 pulsar-browser 4.11.12 source jar 复核全部上游行号；--limit/maxNodes 需上游 AriaSnapshotRenderer 支持才能在默认快照路径生效；dialog 问题先实证 paint-order 剪枝与 `<dialog>` implicit role 两个候选，再定上游改法。

## 四、文档自身勘误

- 上游源码快照 `.test-sessions/pulsar-src/` 版本为 **4.11.2**，仓库依赖为 **4.11.12**——建议在 §4 开头显式注明"行号基于 4.11.2 快照，P2 前以 4.11.12 source jar 复核"。
- agent-browser 参考实现不在本仓库（在 `D:\codebase\browser-automation\agent-browser\cli\src\native\browser.rs`），§2 引用时建议注明来源。
- §2 测试锚点 MCPToolControllerE2ETest.kt 实际在 **browser4-tests/browser4-rest-tests** 模块，不在 browser4-rest/src/test。
- §1 中 check_session_ready/list_sessions 的行号归属 MCPToolController.kt，不是 PulsarSessionManager.kt。

---

*审查方法：静态源码取证（5 路并行核查），未运行构建/测试；行号均以 2026-09-10 工作树为准。*
