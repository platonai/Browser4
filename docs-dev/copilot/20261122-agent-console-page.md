# Agent 控制台：`agent run -i` 的单文件网页版

> 状态：已实现（2026-11-22）。入口：`browser4-rest/src/main/resources/static/agent.html`（`http://<backend>/agent.html`，`/agent` 302 到它）。

## 1. 目标与边界

面向**终端用户**：已经会在终端里跑 `agent run`，但希望用浏览器看步骤、插话、停止、读结果，而不必记 CLI 参数或读 JSON。

刻意保持"最简单"：**单个静态 HTML**，无构建、无 npm、无 CDN、无框架，离线可用；只调用本机后端已有的 REST 接口。它**不是**完整控制台：

| 能力 | 网页 | 终端 `agent run -i` |
|---|---|---|
| 提交任务、看实时步骤、插话、停止、结果 | ✅ | ✅ |
| `/goal`、`/approve`、`/rewind`、`--resume` | ❌ | ✅ |
| 逐项权限审批 | ❌（按无人值守策略提交） | ✅ |

页面明确写出"不会弹逐项审批"：写文件/跑命令按 `browser4.agent.access.unattended` 执行（默认 full，工作区边界与受保护路径仍有效）；需要逐项确认时用终端 `agent run -i` 的 `/approve`。这样用户不会误以为网页在等自己批准。

## 2. 接口映射（与 CLI 同一套后端对象）

| 动作 | 接口 | 说明 |
|---|---|---|
| 提交 | `POST /api/commands/plain?async=true&sessionId=…[&workspace=…]`（`text/plain`） | `UserCommandExecutor.submitPlainCommand` → 与 `command_run` 相同的 agent runner；返回 `{id}`。`workspace` 由设置弹窗的工作区下拉框给出，后端用 `TaskWorkspaceOverride.resolve` 在**提交前**校验（不存在/非法 → `400`，body 仍是 `CommandStatus`） |
| 步骤 | `GET /api/commands/{id}/events/stream` | SSE；按事件名注册监听（`AgentTaskEvent` 的类型清单写在页面 `SSE_EVENT_TYPES`）。其中 `task.titled` 不是步骤：`text` 是模型给任务起的短标题，页面只把它写进任务面板的标题（`setTaskTitle`），不画行 |
| 步骤兜底 | `GET /api/commands/{id}/events?after=…` | SSE 失败自动切换为游标轮询，界面显示"轮询"徽标 |
| 插话 | `POST /api/commands/{id}/input` | 与提交共用同一个输入框：运行中按 Enter 发 `{text, deliverAs: steer|followUp}` |
| 停止 | `POST /api/commands/{id}/cancel` | 页面二次确认 |
| 结果 | `GET /api/commands/{id}/result` | 渲染 结局 hero / 结论 / 接着做 / 关键发现 / 字段 / 文件 / 问题 / 链接 / 工作目录 |
| 成本 | `GET /api/commands/{id}/stats` | 轮数、工具调用、tokens、缓存命中 |
| 状态 | `GET /api/commands/{id}/status` | 终态与错误码；`title` 是模型的任务标题；`?task=` 恢复时也用它 |
| 会话树 | `GET /api/agent/sessions` | 工作区 → 会话树（`AgentSessionsController`）；`?refresh=true` 跳过 2s 复用缓存 |
| 会话上下文 | `GET /api/agent/sessions/{sessionId}/context` | 该会话记得的对话轮次 + 任务列表；未知会话 404 |

## 3. 与 `agent run -i` 的会话打通

- `-i` 的横幅第三行打印 `web console: <backend>/agent.html?session=<id>  ·  same session`（`agent_interactive.rs::banner_lines`）。
- 页面读 `?session=`，也支持 `?task=<id>` 直接跟一个已有任务；提交成功后把两者写回 URL（`history.replaceState`），刷新不丢。
- 会话 ID 可由页面生成（`web-xxxx`）并可复制回终端 `-s`；同一会话的上下文由后端 `contextKey=sessionId` 持久化，网页与终端共享。

## 4. 布局（2026-11-23 改版）

- **单输入框，停靠在页面正下方**：任务输入与运行中插话共用 `#taskInput`。空闲时 Enter 换行、`Ctrl+Enter` 提交，按钮是"开始任务"；运行中输入框不禁用，`Enter` 直接发 `/input`（steer），`Shift+Enter` 换行，按钮变"发送"，旁边出现"等当前步骤结束后再继续"（followUp）。不再有单独的插话框。
- **执行过程是主内容，可收起**：`#runPanel` 占据主区域，标题行（"执行过程"）就是收起按钮，收起后只剩一行摘要（状态、步数、传输方式、耗时、任务 ID）。运行中可以随时收起。
- **结果接管主区域**：任务结束后自动收起执行过程并显示 `#resultCard`——顶部是结论大字号 + 状态徽标 + 统计 chips（耗时/轮数/工具调用/tokens/缓存命中），下面是分卡片的关键发现、字段表、写出文件、链接、工作目录；原始 JSON 折叠在最后。
- **建议下一步直接执行**：结果里的"接着做"按钮点击即用该文本 `POST /api/commands/plain`，不需要再复制到输入框确认；失败结果里的"重试这个任务"同样直接重跑。
- **引导不固定在页面里**：原来占位的"第 1 步 / 第 2 步 / 第 3 步"和固定提示条已删除。新手指引放在输入框上方的一条 `#tips` 里，每 7 秒淡出换一条（空闲与运行中各一套文案），不占据布局。
- **设置收进对话框**：会话、后端地址、查看已有任务从页面主体移到"⚙ 设置"弹窗（`<dialog>`），主区域只留任务相关内容；"不会逐项审批"的说明也放在弹窗里。
- **左右结构 + 空闲居中/开始后下沉（2026-11-23 晚）**：`<body>` 直接是「左栏 + 右栏」。左侧 `#sidebar` 是 `position: fixed` 的整列（工作区 → 会话树、自己的头部与「＋ 新会话」、树内独立滚动）；右侧 `.wrap` 是内容列（页头 + `main`），而 **composer 在 `main` 内部**。空闲时 `body` 没有 `composer-docked` 类：`main` 竖直居中，显示 `#idleHero`（logo + 标题 + 一句说明）与居中的 composer（最大宽 780，参考截图的中置对话框）；`#runPanel`/`#resultCard` 由 CSS 隐藏。开始任务、加载 `?task=`、点击会话恢复上下文、切换会话时都会加 `composer-docked`：composer 变 `position: fixed` 停靠在**右栏**底部（`left: var(--sidebar-w)`），`#idleHero` 隐藏，执行过程/结果占据主区域；「＋ 新会话」回到空闲形态。因为 composer 在 `main` 里，两种形态只用切换 body 类，DOM 不移动。
- **工作区下拉框在对话框（composer）里（2026-11-23 晚）**：composer 的控制行（`#composerControls`）左侧是「工作区」下拉（`#workspaceSelect`）+ 刷新按钮，右侧是提交按钮与 Ctrl+Enter 提示；选项来自 `/api/agent/sessions` 的 durable 工作区，「临时工作区」= 不传 `workspace`（每个任务自己的临时目录）。选择存 `b4.agent.web.workspace`，提交时作为 `&workspace=` 传给后端（后端提交前校验，不存在 → `400`）。点击会话时其上下文里的 `workspace` 被采纳为下一次提交的工作区（临时工作区则回到默认）。composer 底部另有一行小字显示当前会话（`#composerSession`），设置弹窗只保留会话/后端/已有任务三项。
- **左侧栏：工作区 → 会话（2026-11-23）**：`#sidebar` 按任务**实际使用的目录**分组——带 `agent run --workspace` 的会话归到那个目录，没指定的归到 **临时工作区**（每任务一个临时目录）。整栏可用页头 `☰` 或栏内 `‹` 收起，每个工作区独立折叠；两者都存 `localStorage`（`b4.agent.web.sidebar`，窄屏（<940px）首次默认收起）。单击一个会话即**切换**到它：写回 URL/`localStorage`/设置里的会话输入框，并调 `/context` 把该会话的对话轮次与任务列表恢复进执行过程面板；其中若最新任务还在跑，给一个"跟随运行中的任务"按钮。运行中的任务自成一个会话，所以切换会先要求停止（与设置弹窗同一规则）。树每 15s 刷新一次（隐藏标签页跳过），提交成功与任务结束时也会刷新。会话归属规则：一个会话归到它**最新那个指定了工作区的任务**，不会散落在多个分组里。
- 右侧"遇到问题"帮助面板整块删除；连接失败提示保留为顶部横幅（带"重新检测"）。
- 所有后端文本通过 `textContent`/DOM API 渲染，**不用** `innerHTML` 拼模型输出（防 XSS）；无外部资源，`file://` 打开时也能跨域连本地后端（`@CrossOrigin`）。

## 4.1 意图优先的执行过程与模型起名的任务标题（2026-11-28）

目标：运行中一眼看出"它现在打算做什么"，结束时一眼看出"做成了没有、结果是什么"，面板标题是人话而不是"执行过程"。

- **任务面板的标题是模型起的短标题**。后端在任务开始后（`task.started` 之后）用一次独立的小请求让模型起名（`StatefulAgentRunner.titleTaskAsync`，提示词与归一化是纯函数 `buildTaskTitlePrompt` / `normalizeTaskTitle`），把结果写进 `AgentTaskStatus.title`、落一条 `task.titled` 步骤；`CommandStatus.title` 与会话任务行的 `AgentSessionTaskView.title` 原样透出。它是**尽力而为的装饰**：跑在任务旁边、20 秒放弃、失败只记 debug，永远不会阻塞或拖垮任务；标题没到之前面板显示截成一行的任务原文（`showPendingTitle`，带微光），到了以后同时改写面板标题、结果卡的大标题与浏览器标签页（`setTaskTitle`）。`browser4.agent.title.enabled=false` 关掉这次请求（见 `docs/config.md`）。
- **执行过程默认只展示意图**：`工具调用` 开关默认关闭（`b4.agent.web.toolcalls` 只有显式存了 `on` 才开）。每条 `model.intent` 是一张卡片（左边框 + 较大字号 + "进行中 / 已做完 / 未完成"状态徽标），紧跟其后的 `tool.call` / `tool.result` 归入这张卡片名下并折叠：卡片底部一枚"▸ N 次工具调用"的折叠按钮，点开只展开这一组（`group-open`），其余组照旧收着；打开开关则一次全部展开，折叠按钮随之隐藏。没有意图可归属的工具调用（意图开关关着、或模型没写这一行）折在一个朴素的"工具调用"组头下，保证没有任何一行够不着。分组在新的意图、模型给出文字回答（`model.text`）、新一轮（`round.start`）、任务结束时关闭（`closeCurrentGroup`）。
- **两个开关都关**时，意图卡片缩成只剩折叠按钮的一行（`.steps.hide-intent.hide-toolcalls`），它的工具调用仍然可达；一条都还没折叠东西的意图卡片此时不显示。
- **轮次分隔线降为技术细节**：`round.start` 的"── 第 N 轮 ──"只在打开"技术细节"时出现，意图卡片自己带时间，故事由卡片讲。步数统计随之只数叙述行（意图 / 生命周期 / 模型文字），工具行与组头不算"步"。
- **结果卡以结局开头**（`renderResultHeading`）：左侧一个带颜色的大图标（✓ / ✕ / ⏹）+ 状态徽标 + **任务标题**（模型的短标题；没有时退回任务原文，颜色更淡），下面一行"任务：…"给出标题所指的原文；整张卡随结局变色（`is-failed` / `is-cancelled`）。操作按钮（再跑一次 / 复制文本 / 下载 .md / 复制 JSON）收到标题右侧。
- **结论是主角，只讲一遍**：`结论` 分区用更大的字号渲染**一份**主文本——模型自己的收尾文字（最后一条 `model.text`）优先，没有时用引擎的 `summary`；两者都有且说的不是一回事时，`summary` 以折叠的"任务摘要"跟在后面（`sameAnswer` 判断是否重复）。失败时结论改名为"中断前的输出"，排在红色失败提示之后。
- **失败提示先说原因**：标题"任务失败"+ 错误码 chip + 原因（可滚动）+ "可以这样处理：…" + "重试这个任务"。
- **其余证据两列排布**（`.result-grid`，`auto-fit minmax(420px, 1fr)`，窄屏回到单列）：关键发现、字段、写出的文件、遗留问题、链接、工作目录、耗时拆分；宽内容（SQL 结果、页面摘要、失败证据）跨两列。"接着做"紧跟结论，是读完结论之后要做的事。分区图标写在 CSS 的 `::before` 里，导出的文本拿到的是干净的标题。
- **导出**（复制文本 / 下载 .md）按新的结构走：`# 标题 — 状态`、任务原文、统计、各分区（含失败提示）；文件名用任务标题（`fileSafeTitle` 去掉非法字符），没有标题时退回任务 ID。
- 恢复会话上下文时，"这个会话的任务"列表的每一行用模型标题命名，鼠标悬停看任务原文（`task.title || task.instruction`）。

## 5. 验证

- **契约测试** `AgentConsolePageTest`：页面被打包、只调用已存在的端点、无外链资源、关键控件齐全、只有一个输入框（没有 `steerInput`/`steerBox`，也没有固定三步引导）：Maven surefire 4/4 通过（2026-11-23 改版后复跑）。
- **重定向测试** `DefaultControllerTest`：`/agent` → `/agent.html`（2/2 通过）。
- **CLI 单测**：`the_banner_lists_only_non_default_knobs` 断言横幅第三行带 `/agent.html?session=<encoded id>`；完整 `cargo test --bin browser4-cli` 为 2337 passed / 0 failed。
- **浏览器 E2E（ego-browser + mock 后端）**：提交 → SSE 步骤 → 插话 → 结果；停止；SSE 500 时自动轮询仍能完成；`?task=` 恢复；任务不存在；后端未启动的提交错误；模型输出里的 `<img onerror>` / `<script>` 被当作纯文本（0 个注入节点）；连续两个任务不串结果；`file://` 打开也能连本地后端；控制台无未捕获异常。
- **真后端烟测（standalone `Browser4.jar`，端口 18183）**：`/agent.html` 200、`/agent` 302；提交真实任务后页面经 SSE 收到真实的 `goal.pinned` / `tool.call` / `tool.result` / `run.usage` / `gate.evaluated` / `task.done`，并渲染出真实的结果与 `/stats`（1 轮 · 4 次工具调用 · 58.1k tokens · 缓存命中 82%），控制台无未捕获异常。该次任务的业务结论是"b4 的 CLI 启动器在 Windows 上被直接 CreateProcess 调用（error=193）"——这是 backend/tooling 的既有问题，与页面无关；页面正确地把它渲染成了失败证据与下一步建议。
  - 环境备注：同一台机器上默认线程栈会触发 `StackOverflowError`（正则回溯），用 `-Xss16m` 启动后任务正常完成；与页面无关。
- **会话侧边栏（2026-11-23）**：`AgentSessionGroupingTest` 6/6（工作区分组、临时工作区、归属规则、任务排序、Windows 路径归一、上下文恢复）、`AgentSessionsServiceTest` 3/3（两源合并、2s 复用缓存、注册表不可读时返回空树而不是 500、未知会话 404）、`AgentConsolePageTest` 6/6（侧边栏控件/端点/存储键契约）；页面内联脚本通过 `node --check`。
- **左右结构 + 工作区下拉（2026-11-23 晚）**：`CommandControllerTest` 27/27（新增：`/plain?workspace=` 透传成 `Path`、非法工作区在提交前 400 且不触达 executor）；`AgentConsolePageTest` 8/8（新增左右结构断言、工作区控件与 `&workspace=` 断言）；`node --check` 通过、60 个 `$('id')` 全部有声明、`<div>` 开闭平衡。
- **意图优先 + 模型标题（2026-11-28）**：
  - 单元与契约：`TaskTitleTest` 8/8（提示词；归一化——多行取首行、剥 `Title:` / `标题：` 前缀、引号与 Markdown 包裹、尾部句读、空白折叠、40 字截断），`AgentTaskStatusTest` 补"快照带 title"；`CommandStatusTitleTest` 2/2，`AgentSessionGroupingTest` 新增 `taskRowsCarryTheTitle`；`AgentConsolePageTest` 14/14（新增：工具调用默认折叠、模型标题、结果卡以结局开头、**提交路径调用的辅助函数必须声明在脚本作用域**——`submitTask` / `startTask` 里出现的每个自由函数名都要匹配 `^  function|const|let|class <name>`）；`CommandControllerTest` 27/27；内联脚本 `node --check` 通过。
  - 真实任务（dev 后端，都是"不用浏览器"的只读任务，原因见第 6 节）：
    - `c4942c76…`，经 `POST /api/commands/plain?async=true` 提交：58 秒 `task.done`（statusCode 200），9 条意图、20 次工具调用；第 5 秒收到 `task.titled` =「统计agent页面行数与函数」，`status.title` 与会话任务行的 `title` 一致。事件流的结构是"意图 → 这一步的工具调用"，页面按它分组。
    - `c78c3e60…`，**从页面实时提交**（`dev-server.js` 提供源码页面、`/api` 反代到同一后端）：25 秒完成，面板标题从任务原文变成「说明源码功能并列出函数名」，5 张意图卡片，11 次工具调用全部收在各自卡片下（首个折叠按钮「▸ 2 次工具调用」），结果卡「已完成」+ 结论 / 关键发现。
    - `9d96a2f1…`，重建 runtime bundle 并重启 dev 后端之后，在**真实入口**（`agent run -i` 横幅里的 `http://127.0.0.1:8282/agent.html`）上从页面提交：10 秒完成，标题「说明config.md用途」，1 张意图卡片 + 1 个折叠按钮，结果卡「已完成」；该地址返回的页面与源码文件逐字节一致。
  - 页面实测：恢复视图 `?session=…&task=c4942c76…` 的面板标题与结果标题都是模型标题，「任务：…」一行给出原文，耗时是事件里的 58 秒（不是打开页面的时刻）；展开前 0 行工具调用可见，点开第一组后可见 2 行。

## 6. 已知限制

- 仅登记当前 `AgentTaskEvent` 事件类型；未来新增类型会被静默忽略（设计上应兼容），需要显示时同步更新 `SSE_EVENT_TYPES`。
- 侧边栏只列出**本后端**记得的会话（任务行 + 会话快照，均受 TTL 限制）；换后端地址后看到的是那个后端的会话。
- 工作区分组只认 `agent run --workspace` 指定的目录：`--coding` 的隔离 worktree、以及没指定工作区的每任务目录都归到"临时工作区"（任务行里仍显示各自的真实目录）。
- 侧边栏的对话轮次是后端**跨任务会话记忆**的投影（压缩后的记忆另列），不是逐条完整聊天记录；恢复后是只读浏览，接着写才会产生新任务。
- 未接入审批端点（`/api/agent/{taskId}/access`），因此按无人值守策略提交。
- 与本地 REST API 同一信任边界（无鉴权）。页面自身用 CSP 禁止外部资源、所有模型输出走 `textContent` 渲染；不要把后端端口暴露到不可信网络。
- **任务标题是尽力而为**：模型不起名、20 秒超时或那次小请求失败时，面板保留截成一行的任务原文，只记一条 debug 日志；任务已经结束才到的标题不再追加 `task.titled` 步骤（避免覆盖终态行）。代价是每个任务开头多一次很小的 LLM 调用，`browser4.agent.title.enabled=false` 可关。
- **验证时本机起不了浏览器**：dev 后端（8282）与安装版 v4.16.13 的后端（18182）启动 Chrome 都报 `Failed to start chrome process. alive: false, exit code: 0`，所以 2026-11-28 的两次真实任务都是只读任务；需要浏览器的 `fcd04eac…` 拿到了标题但没有完成目标。直接启动同一版 Chrome（带/不带 `--do-not-de-elevate`、经 WMI）未复现，原因未定位，与页面改动无关。
- **顺手修掉的既有缺陷**：提交 `1efedae800` 把 `parseModelPick` / `MODEL_PICK_SEP` 定义在 `bindEvents` 的嵌套作用域里，而 `startTask` 在脚本作用域调用它，网页端提交任务必然报「提交失败：parseModelPick is not defined」。现提升到脚本作用域，并由 `AgentConsolePageTest.consolePageDeclaresTheSubmitHelpersInScriptScope` 守住。
- **小视口下输入区很高**：例如 1026×720 时停靠的输入区（提示条、输入框、工作区 / 模型 / 推理、示例任务）占去大半屏，结果卡要滚动才看得到。输入区本身这次没动。
- **运行中的后端不会自动换页面**：`agent.html` 打在 runtime bundle 的 `browser4-rest` jar 里，改完要 `./b4w.ps1 stop` 再用 `-Rebuild` 重启后端才会生效；日常调页面用 `node dev-server.js`（8765，`B4_BACKEND_PORT` 指向已启动的后端）。
