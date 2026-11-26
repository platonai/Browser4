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
| 步骤 | `GET /api/commands/{id}/events/stream` | SSE；按事件名注册监听（`AgentTaskEvent` 的类型清单写在页面 `SSE_EVENT_TYPES`） |
| 步骤兜底 | `GET /api/commands/{id}/events?after=…` | SSE 失败自动切换为游标轮询，界面显示"轮询"徽标 |
| 插话 | `POST /api/commands/{id}/input` | 与提交共用同一个输入框：运行中按 Enter 发 `{text, deliverAs: steer|followUp}` |
| 停止 | `POST /api/commands/{id}/cancel` | 页面二次确认 |
| 结果 | `GET /api/commands/{id}/result` | 渲染 结论 / 关键发现 / 字段 / 文件 / 问题 / 链接 / 工作目录 / 建议的下一步 |
| 成本 | `GET /api/commands/{id}/stats` | 轮数、工具调用、tokens、缓存命中 |
| 状态 | `GET /api/commands/{id}/status` | 终态与错误码；`?task=` 恢复时也用它 |
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

## 5. 验证

- **契约测试** `AgentConsolePageTest`：页面被打包、只调用已存在的端点、无外链资源、关键控件齐全、只有一个输入框（没有 `steerInput`/`steerBox`，也没有固定三步引导）：Maven surefire 4/4 通过（2026-11-23 改版后复跑）。
- **重定向测试** `DefaultControllerTest`：`/agent` → `/agent.html`（2/2 通过）。
- **CLI 单测**：`the_banner_lists_only_non_default_knobs` 断言横幅第三行带 `/agent.html?session=<encoded id>`；完整 `cargo test --bin browser4-cli` 为 2337 passed / 0 failed。
- **浏览器 E2E（ego-browser + mock 后端）**：提交 → SSE 步骤 → 插话 → 结果；停止；SSE 500 时自动轮询仍能完成；`?task=` 恢复；任务不存在；后端未启动的提交错误；模型输出里的 `<img onerror>` / `<script>` 被当作纯文本（0 个注入节点）；连续两个任务不串结果；`file://` 打开也能连本地后端；控制台无未捕获异常。
- **真后端烟测（standalone `Browser4.jar`，端口 18183）**：`/agent.html` 200、`/agent` 302；提交真实任务后页面经 SSE 收到真实的 `goal.pinned` / `tool.call` / `tool.result` / `run.usage` / `gate.evaluated` / `task.done`，并渲染出真实的结果与 `/stats`（1 轮 · 4 次工具调用 · 58.1k tokens · 缓存命中 82%），控制台无未捕获异常。该次任务的业务结论是"b4 的 CLI 启动器在 Windows 上被直接 CreateProcess 调用（error=193）"——这是 backend/tooling 的既有问题，与页面无关；页面正确地把它渲染成了失败证据与下一步建议。
  - 环境备注：同一台机器上默认线程栈会触发 `StackOverflowError`（正则回溯），用 `-Xss16m` 启动后任务正常完成；与页面无关。
- **会话侧边栏（2026-11-23）**：`AgentSessionGroupingTest` 6/6（工作区分组、临时工作区、归属规则、任务排序、Windows 路径归一、上下文恢复）、`AgentSessionsServiceTest` 3/3（两源合并、2s 复用缓存、注册表不可读时返回空树而不是 500、未知会话 404）、`AgentConsolePageTest` 6/6（侧边栏控件/端点/存储键契约）；页面内联脚本通过 `node --check`。
- **左右结构 + 工作区下拉（2026-11-23 晚）**：`CommandControllerTest` 27/27（新增：`/plain?workspace=` 透传成 `Path`、非法工作区在提交前 400 且不触达 executor）；`AgentConsolePageTest` 8/8（新增左右结构断言、工作区控件与 `&workspace=` 断言）；`node --check` 通过、60 个 `$('id')` 全部有声明、`<div>` 开闭平衡。

## 6. 已知限制

- 仅登记当前 `AgentTaskEvent` 事件类型；未来新增类型会被静默忽略（设计上应兼容），需要显示时同步更新 `SSE_EVENT_TYPES`。
- 侧边栏只列出**本后端**记得的会话（任务行 + 会话快照，均受 TTL 限制）；换后端地址后看到的是那个后端的会话。
- 工作区分组只认 `agent run --workspace` 指定的目录：`--coding` 的隔离 worktree、以及没指定工作区的每任务目录都归到"临时工作区"（任务行里仍显示各自的真实目录）。
- 侧边栏的对话轮次是后端**跨任务会话记忆**的投影（压缩后的记忆另列），不是逐条完整聊天记录；恢复后是只读浏览，接着写才会产生新任务。
- 未接入审批端点（`/api/agent/{taskId}/access`），因此按无人值守策略提交。
- 与本地 REST API 同一信任边界（无鉴权）。页面自身用 CSP 禁止外部资源、所有模型输出走 `textContent` 渲染；不要把后端端口暴露到不可信网络。
