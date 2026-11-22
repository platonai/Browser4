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
| 提交 | `POST /api/commands/plain?async=true&sessionId=…`（`text/plain`） | `UserCommandExecutor.submitPlainCommand` → 与 `command_run` 相同的 agent runner；返回 `{id}` |
| 步骤 | `GET /api/commands/{id}/events/stream` | SSE；按事件名注册监听（`AgentTaskEvent` 的类型清单写在页面 `SSE_EVENT_TYPES`） |
| 步骤兜底 | `GET /api/commands/{id}/events?after=…` | SSE 失败自动切换为游标轮询，界面显示"轮询"徽标 |
| 插话 | `POST /api/commands/{id}/input` | `{text, deliverAs: steer|followUp}` |
| 停止 | `POST /api/commands/{id}/cancel` | 页面二次确认 |
| 结果 | `GET /api/commands/{id}/result` | 渲染 结论 / 关键发现 / 字段 / 文件 / 问题 / 链接 / 工作目录 / 建议的下一步 |
| 成本 | `GET /api/commands/{id}/stats` | 轮数、工具调用、tokens、缓存命中 |
| 状态 | `GET /api/commands/{id}/status` | 终态与错误码；`?task=` 恢复时也用它 |

## 3. 与 `agent run -i` 的会话打通

- `-i` 的横幅第三行打印 `web console: <backend>/agent.html?session=<id>  ·  same session`（`agent_interactive.rs::banner_lines`）。
- 页面读 `?session=`，也支持 `?task=<id>` 直接跟一个已有任务；提交成功后把两者写回 URL（`history.replaceState`），刷新不丢。
- 会话 ID 可由页面生成（`web-xxxx`）并可复制回终端 `-s`；同一会话的上下文由后端 `contextKey=sessionId` 持久化，网页与终端共享。

## 4. 面向新手的交互设计

- 顶部三步引导 + 示例任务一键填入；`Ctrl+Enter` 开始。
- 运行中主输入框锁定，下方出现"插一句话"输入（说明"不打断、下一轮生效"），并提供"等当前步骤结束后再继续"。
- 状态用自然语言（排队中/运行中/已完成/失败/已停止），非 JSON；失败附 `failureHint`（缺 LLM key、预算、NOOP、需审批、后端未启动）。
- 20 秒无新步骤显示"还在运行，不是卡死"；首次运行提示 10–60 秒启动浏览器。
- 结果建议按钮可一键填回任务框，接着在同一会话追问。
- 后端未连接时显示横幅，直接给出 `browser4-cli open …` / `b4w.ps1 open …` 启动方式。
- 所有后端文本通过 `textContent`/DOM API 渲染，**不用** `innerHTML` 拼模型输出（防 XSS）；无外部资源，`file://` 打开时也能跨域连本地后端（`@CrossOrigin`）。

## 5. 验证

- **契约测试** `AgentConsolePageTest`：页面被打包、只调用已存在的端点、无外链资源、关键控件齐全（Maven surefire：3/3 通过）。
- **重定向测试** `DefaultControllerTest`：`/agent` → `/agent.html`（2/2 通过）。
- **CLI 单测**：`the_banner_lists_only_non_default_knobs` 断言横幅第三行带 `/agent.html?session=<encoded id>`；完整 `cargo test --bin browser4-cli` 为 2337 passed / 0 failed。
- **浏览器 E2E（ego-browser + mock 后端）**：提交 → SSE 步骤 → 插话 → 结果；停止；SSE 500 时自动轮询仍能完成；`?task=` 恢复；任务不存在；后端未启动的提交错误；模型输出里的 `<img onerror>` / `<script>` 被当作纯文本（0 个注入节点）；连续两个任务不串结果；`file://` 打开也能连本地后端；控制台无未捕获异常。
- **真后端烟测（standalone `Browser4.jar`，端口 18183）**：`/agent.html` 200、`/agent` 302；提交真实任务后页面经 SSE 收到真实的 `goal.pinned` / `tool.call` / `tool.result` / `run.usage` / `gate.evaluated` / `task.done`，并渲染出真实的结果与 `/stats`（1 轮 · 4 次工具调用 · 58.1k tokens · 缓存命中 82%），控制台无未捕获异常。该次任务的业务结论是"b4 的 CLI 启动器在 Windows 上被直接 CreateProcess 调用（error=193）"——这是 backend/tooling 的既有问题，与页面无关；页面正确地把它渲染成了失败证据与下一步建议。
  - 环境备注：同一台机器上默认线程栈会触发 `StackOverflowError`（正则回溯），用 `-Xss16m` 启动后任务正常完成；与页面无关。

## 6. 已知限制

- 仅登记当前 `AgentTaskEvent` 事件类型；未来新增类型会被静默忽略（设计上应兼容），需要显示时同步更新 `SSE_EVENT_TYPES`。
- 无任务历史列表：`?task=`/输入任务 ID 才能查看旧任务。
- 未接入审批端点（`/api/agent/{taskId}/access`），因此按无人值守策略提交。
- 与本地 REST API 同一信任边界（无鉴权）。页面自身用 CSP 禁止外部资源、所有模型输出走 `textContent` 渲染；不要把后端端口暴露到不可信网络。
