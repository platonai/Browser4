# 完整 CLI 命令参考

[English](cli-reference.md) | 简体中文

> `browser4-cli` 的完整命令与选项参考。项目 [README](../README.zh.md) 只保留速查表，
> 下面的内容才是权威清单。`browser4-cli --help <command>` 会从二进制打印同样的细节，
> `browser4-cli --help-json` 则以 JSON 输出。

## 会话生命周期与服务端管理

| 命令 | 说明 |
|---|---|
| `open [url]` | 打开浏览器会话，或重新连接已有会话。**默认为无头模式。** 支持 `--headed`（可视窗口）、`--headless`、`--profile <path>`、`--profile-mode <DEFAULT\|SYSTEM_DEFAULT\|SEQUENTIAL\|TEMPORARY>`、`--interact-level <FASTEST\|FAST\|DEFAULT>`。**注意：** `SYSTEM_DEFAULT` 已废弃，Chrome ≥ 143 不支持——复用系统浏览器状态请用 `attach` + `state-save`/`state-load`（见 [browser-state-import.md](../skills/browser4-cli/references/browser-state-import.md)）。 |
| `attach` | 通过 CDP 或 Browser4 扩展附加到现有浏览器。支持 `--cdp <url\|port\|channel>` 与远程 endpoint 选项。channel 名会依次从浏览器的 `DevToolsActivePort`、`--remote-debugging-port` 与监听端口收集候选，并绑定第一个能承载页面的端点——走 `/json`，或走 Chrome 内置 `chrome://inspect/#remote-debugging` 开关发布的浏览器级 WebSocket（也可直接 `attach --cdp ws://127.0.0.1:<port>/devtools/browser/<uuid>`）。成功附加后 CLI 会打印实际连接的浏览器（`Connected browser: …` / `Attached to … at …`），当实际浏览器与请求的 channel 不符（如请求 msedge 却连到 Chrome）时会输出 ⚠ 告警——请在驱动会话前核对。 |
| `close` | 关闭当前活动浏览器会话。 |
| `list` | 列出浏览器会话及其状态和下次打开行为。Connection 列优先显示后端上报的真实浏览器，并在 channel 冲突时标注（如 `requested msedge · actual Google Chrome`）。支持 `--all`：额外加一列 Workspace 并纳入其它 checkout 持久化的会话（`*` 标记当前所在 workspace；其它 workspace 的行显示 Unknown / `-` / Refresh，因为当前后端无法校验它们）。 |
| `session-default <name>` | 把一个命名会话设为默认未命名会话。 |
| `close-all` | 关闭所有会话，但不停止后端。 |
| `kill-all` | 强制停止后端以及 Browser4 管理的浏览器进程。 |
| `stop` | 优雅停止 Browser4 服务。 |
| `status` | 显示服务版本、端口、健康状态，以及 Web 状态面板地址（`http://<server>:18182/status`）。存在活动会话时还会打印当前会话小节：Name / Session ID / Status / Display / Connection / Next open。 |
| `doctor` | 运行诊断：构建信息、LLM 状态（含实际读取的配置文件、生效的 provider key 与实际请求的模型）、陈旧 daemon 清理、可选修复。支持 `--verbose` 与 `--fix`；`--fix` 还会写入一份带注释的 LLM 配置模板。 |
| `doctor log [name]` | 列出、查看、tail 或 grep 后端日志文件。支持 `--tail`、grep 风格参数，以及 `doctor log <name> grep <pattern>`。 |
| `doctor metrics [filter]` | 列出、过滤或 grep 后端指标。支持 `doctor metrics grep <pattern>`。 |
| `doctor status [--section <name>] [--verbose]` | 在终端分层显示状态面板聚合报告：默认显示概要层，`--verbose` 显示完整明细，`--section` 钻取单个报告（health、build、runtime、llm、sessions、pulsar-sessions、swarm、url-pool、browsers、drivers、privacy、plugins、skills、metrics、logs），`--json` 输出机器可读 JSON。 |
| `delete-data` | 删除会话数据。 |
| `install` | 安装 Browser4 运行时 bundle。支持 `--tag <version>` 与 `--force`。 |
| `upgrade` | 升级 CLI / 运行时 bundle。支持 `--tag <version>` 与 `--force`。 |
| `uninstall` | 删除全局安装和运行时数据。支持 `-y`、`--yes`、`--dry-run`。 |

```bash
browser4-cli open --headed https://example.com
browser4-cli attach --cdp chrome
browser4-cli doctor --verbose
browser4-cli doctor log server.log --tail
browser4-cli doctor metrics grep request
browser4-cli doctor status --section skills --verbose
```

**Web 状态面板：** 在浏览器打开 `http://127.0.0.1:18182/status` 即可查看实时仪表盘
（健康状态、版本、JVM/运行时、LLM 配置、会话、**Pulsar 会话**——SDK 身份、上下文与主循环
状态、**swarm**——swarm 会话及任务汇总、**URL 池**——按优先级缓存的排队/实时/延迟数量、
浏览器与打开的标签页——每个会话的浏览器/驱动绑定与标签页数量，可点击按需加载实时标签页
明细，数据来自 `GET /api/system/tabs`——驱动池、插件（加载/启用状态与 SDK 兼容性）、
指标、日志文件；自动刷新，可用 `?refresh=<ms>` 调整间隔）。面板数据来自聚合端点
`GET /api/system/status`；原有单个端点（`/api/system/health`、`/api/system/build`、
`/api/doctor/llm-status`、`/api/doctor/metrics`、`/api/doctor/log-files`、`/api/plugins`、
`/api/skills`）继续可用。`browser4-cli plugin-list` 也会报告每个已安装插件的加载/启用状态与
SDK 版本；同样的报告也可以在终端中通过 `browser4-cli doctor status` 分层查看。

**页面截图：** 打开 `http://127.0.0.1:18182/pages.html` 可以网格形式查看所有会话中打开的网页。
每个会话的活动页自动截图（点击截图可重新截取）；非活动页显示占位图，点击后截取展示；
SWARM 会话的所有页面仅显示占位图。截图采用**异步加载**——后端在后台截取（截取中返回
`202 Accepted` + `Retry-After`，完成后返回缓存的 `image/png`），面板不会阻塞等待截图。
由 `GET /api/pages` 与 `GET /api/pages/{sessionId}/{guid}/screenshot.png` 提供数据
（`?refresh=1` 强制重新截取）。

## 导航

| 命令 | 说明 |
|---|---|
| `goto <url>` | 导航到 URL；如果需要会自动打开 / 重连会话。 |
| `go-back` | 在浏览器历史中后退。 |
| `go-forward` | 在浏览器历史中前进。 |
| `reload` | 刷新当前页面。 |

## 核心交互

除非另有说明，所有交互命令都接受 snapshot ref（如 `e15`）或 CSS 选择器。多数命令还支持 `--no-snapshot`，用于跳过动作执行后的自动可访问性快照。

| 命令 | 说明 |
|---|---|
| `click <ref> [button]` | 点击元素。支持 `--modifiers`、`--follow`、`--auto-dismiss-dialogs`。 |
| `dblclick <ref> [button]` | 双击元素。支持 `--modifiers`、`--follow`、`--auto-dismiss-dialogs`。 |
| `hover <ref>` | 悬停到元素上。 |
| `fill <ref> <text>` | 清空并填写可编辑字段。支持 `--submit`、`--verify`。 |
| `type <text> [ref]` | 向当前焦点元素或指定目标元素输入文本。支持 `--submit`、`--verify`、`--focus`、`--interactable-timeout`，以及 `--method auto\|chars\|exec`（需同时给目标 ref：`auto` 默认——短文本逐字符输入，长文本/多行文本对 textarea/contenteditable 通过一次 `execCommand('insertText')` 批量插入；`chars` 强制逐字符；`exec` 强制批量插入）。 |
| `press <key> [ref]` | 向当前焦点元素或指定目标元素发送按键。支持 `--verify`、`--follow`。 |
| `select <ref> <value>` | 选择下拉框值。支持 `--verify`。 |
| `check <ref>` | 勾选复选框或单选框。 |
| `uncheck <ref>` | 取消勾选复选框或单选框。 |
| `drag <startRef> <endRef>` | 从一个元素拖放到另一个元素。 |
| `wait [target]` | 等待 selector/ref、时长、文本、URL 模式、页面加载状态或 JavaScript 表达式。支持 `--timeout`、`--text`、`--url`、`--load`、`--fn`。 |
| `upload <ref> <file> [file...]` | 把本地文件上传到页面的文件输入框。目标必须是 `<input type="file">`（其他元素会报错）；文件路径须能被浏览器进程读取——本地模式下即本机，且空文件/不存在的文件会被拒绝并提示；远程后端时路径在后端主机上解析。支持 `--no-snapshot`。 |

`wait --load` 接受 `domcontentloaded`、`load` 和 `networkidle`。`networkidle` 只证明网络已安静，**不**代表延迟渲染的结果已经出现；结果类页面请改为轮询结果元素：`wait "<result-selector>"`。

```bash
browser4-cli click e8 --follow
browser4-cli fill e4 "john@example.com" --submit
browser4-cli type "Browser4" e7 --verify
browser4-cli wait --text "Success"
browser4-cli wait --load networkidle
```

## 键盘与鼠标

| 命令 | 说明 |
|---|---|
| `keydown <key>` | 按下并保持某个键。 |
| `keyup <key>` | 释放某个已按住的键。 |
| `mousemove <x> <y>` | 把鼠标移动到主框架视口左上角起算的 CSS 像素 `(x, y)`。这不是桌面/屏幕坐标，也不会移动操作系统光标；越界表示"离开视口"，而不是"移到浏览器窗口外"。页面滚动后坐标即失效。 |
| `mousedown [button]` | 按下鼠标按键。 |
| `mouseup [button]` | 释放鼠标按键。 |
| `mousewheel <dx> <dy>` | 用滚轮 delta 进行滚动。 |
| `scroll <direction> <pixels>` | 按 `up`、`down`、`left` 或 `right` 滚动页面。 |

## 页面检查与实时提取

| 命令 | 说明 |
|---|---|
| `snapshot` | 捕获可访问性树快照。支持 `--boxes`/`--no-boxes`、`-i/--interactive`、`-u/--urls`、`-c/--compact`、`--no-compact`、`-d/--depth`、`--selector`（无短选项——`-s` 是全局 session 标志）、`--raw`、`--stdout`、`-v/--viewport`、`--auto-diff`、`-b/--brief`、`--filename`。`--stdout`/`--raw` 输出默认按 2000 行/页截断分页——截断时若 stdout 是管道会追加一行 `# … output truncated: showing N of M lines …` 提示，完整 footer 走 stderr；需要完整树请用 `--all` 或 `--page-size 0`，超大页面建议用 `-v N`/`--depth`/`--selector`/`--no-boxes` 约束。 |
| `snapshot grep <pattern>` | 用 grep 风格参数搜索保存的 / 当前 snapshot YAML，例如 `-i`、`-v`、`-c`、`-l`、`-F`、`-w`、`-A`、`-B`、`-C`、`--selector`、`--page`、`--page-size`、`--all`。 |
| `snapshot list` | 列出保存的快照文件及其时间戳、大小。 |
| `snapshot clean` | 删除旧快照文件。支持 `--dry-run`。 |
| `get <mode> <selector> [name]` | 从实时页面元素中提取 `text`、`html`、`box`、`styles`、`property` 或 `attr`。 |
| `eval [expression] [ref]` | 在页面或某元素上执行 JavaScript。支持 `--file`、`--stdin`、`--base64`、`--await`、`--wait-selector`、`--json`。 |
| `console [min-level]` | 列出浏览器控制台消息。支持 `--clear`。 |
| `cdp <method>` | 发送任意 Chrome DevTools Protocol 命令。支持 `--json <params>`。 |
| `generate-locator <ref>` | 为 snapshot ref 或已有选择器生成最佳 CSS selector。 |
| `resize <width> <height>` | 调整浏览器窗口尺寸。 |
| `dialog-accept [prompt]` | 接受 alert / confirm / prompt 对话框，并可填写 prompt 内容。 |
| `dialog-dismiss` | 关闭 alert / confirm / prompt 对话框。 |

`get` 支持的 mode：

| 模式 | 含义 | 示例 |
|---|---|---|
| `text` | 可见文本 | `browser4-cli get text ".price"` |
| `html` | inner HTML | `browser4-cli get html "#main"` |
| `box` | 边界框 | `browser4-cli get box "#hero"` |
| `styles` | 计算后的样式 | `browser4-cli get styles e9` |
| `property` | DOM 属性值 | `browser4-cli get property "input" value` |
| `attr` | HTML attribute 值 | `browser4-cli get attr "a" href` |

```bash
browser4-cli snapshot -i --boxes
browser4-cli snapshot grep -C 2 "button"
browser4-cli eval "document.title"
browser4-cli eval --file script.js --await
browser4-cli console warn
browser4-cli cdp Runtime.evaluate --json '{"expression":"document.title"}'
```

## HTML 快照与 X-SQL 提取

`htmlsnapshot` 会把活动标签页正在显示的页面序列化并生成快照，是 Browser4 结构化提取工作流的核心。**htmlsnapshot 家族中每条命令都先 capture 活动页取得最新快照，然后在这份快照上操作**：capture 返回元数据，读命令消费这份快照——所以读取看到的已经是页面此刻的状态（表单提交结果、SPA 更新、`eval` 改动），无需额外先执行一次 capture。读命令也可以用 `--expires <dur>`（默认 `0s`，即活动页）改读**页面库中的快照**：只要库内副本比该时间窗更新就读它，完全不碰标签页——`htmlsnapshot get text ".price" --expires 1d` 就是在上一版快照上操作。指向别的 URL 的命令（`readability <url>`、`query --url <url>`）读的是该 URL 自己的库内副本，库里没有时做独立只读加载；标签页没有打开的 URL 无法 capture，因此该标签页的文档绝不会被记到它名下（`--expires` 不管这条路径——它本来就是只读库的）。

| 命令 | 说明 |
|---|---|
| `htmlsnapshot` | `htmlsnapshot capture` 的简写。 |
| `htmlsnapshot capture` | 序列化活动标签页的页面并写入页面库，同时返回页面和交互元素的元数据（覆盖该标签页规范化 URL 对应的旧记录）。 |
| `htmlsnapshot get <field> [selector] [name]` | 从活动页的最新快照中提取第一个匹配项的 `text`、`textcontent`、`html` 或 `attr`。支持 `--expires <dur>`。 |
| `htmlsnapshot get all <field> [selector] [name]` | 从活动页的最新快照中提取全部匹配值。支持 `--offset`、`--limit` 和 `--expires <dur>`。 |
| `htmlsnapshot query [url]` | 对活动页的最新快照（先 capture 再查询）或指定 URL 的库内页面运行 X-SQL。支持 `--sql <query\|@file>`、`--sql-stdin`、`--sql-base64`、`--expires <dur>`、结果分页和提取导向输出选项。 |
| `htmlsnapshot export` | 把活动页的最新快照的 HTML 导出到文件。支持位置参数文件路径或 `--file <path>`，以及 `--clean` 和 `--expires <dur>`。 |
| `htmlsnapshot summary [--algorithm <id>]` | 基于活动页的最新快照生成压缩版 Web Page Summary Index（WPSI）；插件可贡献额外的算法 id。支持 `--expires <dur>`。 |
| `htmlsnapshot algorithms` | 列出已安装的摘要算法（内置 `wpsi` 及插件贡献的 id）。无需打开页面。 |
| `htmlsnapshot grep <pattern>` | 用 grep 风格参数搜索活动页最新快照的 HTML。支持 `--expires <dur>`。 |
| `htmlsnapshot inspect [selector]` | 在活动页的最新快照上发现重复 DOM 模式和候选选择器。支持 `--max`、`--depth`、`--stdin`、`--selector-base64`、`--expires <dur>`。 |
| `htmlsnapshot readability [url]` | 用 Readability 式启发式算法提取正文：不给 URL 时读活动页的最新快照；给了 URL 则读该 URL 自己的库内副本，绝不会把当前标签页的文档记到该 URL 名下。支持 `--text-only`、`--expires <dur>` 与分页。 |

重要规则：

- 需要 ref 和交互时用 `snapshot`
- 需要重复 DOM 提取时用 `htmlsnapshot`；读命令每次都会先捕获活动页，所以页面在标签页里变化后直接重跑读命令即可
- 想在库里已有的快照上操作（而不是活动页）时，给读命令加 `--expires <dur>`：`--expires 1d` 读库内副本且不序列化标签页，`--expires 0s`（默认）就是活动页
- 推荐使用 `htmlsnapshot query --sql @query.sql`，避免 shell 转义问题
- 需要关联型列表提取时，优先使用 `htmlsnapshot query`，而不是多次 `get all`
- 需要一步提取正文（无需手写选择器）时，用 `htmlsnapshot readability`

```bash
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#productTitle"
browser4-cli htmlsnapshot get all text ".result-title" --offset 10 --limit 5
browser4-cli htmlsnapshot inspect ".s-result-item" --depth 6 --max 20
browser4-cli htmlsnapshot export --file page.html --clean
browser4-cli htmlsnapshot query --sql @query.sql
browser4-cli htmlsnapshot readability --text-only --all
```

深入了解 X-SQL 可参见 [skills/browser4-cli/references/htmlsnapshot.md](../skills/browser4-cli/references/htmlsnapshot.md) 与 [skills/browser4-cli/references/x-sql-dom-load-select.md](../skills/browser4-cli/references/x-sql-dom-load-select.md)。

## 截图与 PDF

| 命令 | 说明 |
|---|---|
| `screenshot [ref]` | 对页面或元素截图。支持 `--filename`、`--full-page`、`--viewport`。 |
| `pdf` | 将当前页面保存为 PDF。支持 `--filename`。 |

## 标签页

| 命令 | 说明 |
|---|---|
| `tab-list` | 列出打开的标签页及其索引、标题、URL；配合 `--json` 可获得完整 GUID。 |
| `tab-new [url]` | 打开新标签页，可选同时导航到 URL。 |
| `tab-close [index]` | 按索引关闭标签页；支持 `--guid <guid>`。 |
| `tab-select <index>` | 按索引切换标签页；支持 `--guid <guid>`。 |

## 浏览器存储与本地页面数据

| 命令 | 说明 |
|---|---|
| `state-save [filename]` | 把 cookies 和 localStorage 保存为 JSON 文件。 |
| `state-load <filename>` | 从 JSON 文件恢复 cookies 和 localStorage。 |
| `cookie-list` | 列出 cookies。支持 `--domain`、`--path`。 |
| `cookie-get <name>` | 按名称获取 cookie。 |
| `cookie-set <name> <value>` | 设置 cookie。支持 `--domain`、`--path`、`--expires`、`--httpOnly`、`--secure`、`--sameSite`。 |
| `cookie-delete <name>` | 按名称删除 cookie。支持 `--domain`、`--path`。 |
| `cookie-clear` | 清空所有 cookies。 |
| `localstorage-list` | 列出 localStorage 项。 |
| `localstorage-get <key>` | 读取 localStorage 键。 |
| `localstorage-set <key> <value>` | 设置 localStorage 键。 |
| `localstorage-delete <key>` | 删除 localStorage 键。 |
| `localstorage-clear` | 清空 localStorage。 |
| `sessionstorage-list` | 列出 sessionStorage 项。 |
| `sessionstorage-get <key>` | 读取 sessionStorage 键。 |
| `sessionstorage-set <key> <value>` | 设置 sessionStorage 键。 |
| `sessionstorage-delete <key>` | 删除 sessionStorage 键。 |
| `sessionstorage-clear` | 清空 sessionStorage。 |
| `webdb export <dir>` | 把 Browser4 web database 中的页面导出到本地目录。 |
| `webdb normalize <url>` | 把 URL 规范化为 web database key 格式。 |

## AI 提取、chat 与自主 agent 任务

这些命令需要 LLM key。

| 命令 | 说明 |
|---|---|
| `extract <instruction>` | 从当前页面提取结构化数据。支持 `--schema <json\|@file>`、`--filename`、`--raw`、`--stdout`。 |
| `summarize [instruction]` | 总结当前页面内容。支持 `--selector`、`--filename`、`--raw`、`--stdout`。 |
| `chat <message>` | 发送纯 AI chat 请求，不自动追加浏览器上下文。 |
| `chat-result <id>` | 获取异步 chat 任务结果。 |
| `agent run <task>` | 提交一个自主浏览器任务，并立即获得任务 ID。支持 `--wait`（阻塞等待结果）和 `--wait-timeout <秒>`（默认 600）。`--follow` 实时输出每一步（工具调用、模型发言、慢/失败工具）并以结果收尾——Ctrl+C 会取消任务；`--ndjson` 把同一流输出为每行一个 JSON 对象。`-i` 进入交互会话，任务运行中键入的内容会注入该任务（steering，绝不会被排成第二个任务）；`/stats` 报告本次运行的 LLM 轮次/请求数、token 消耗（provider 上报时含 prompt cache 读取/写入量与命中率）与工具调用；每任务预算按**缓存未命中** token 计（默认 5M，prompt cache 读取不计费），预算耗尽时可直接选择“继续”（预算翻倍），每个任务结束 `-i` 会预测 2~3 条编号的后续**任务**（优先模型自己给出的后续建议，其次从结果里推导：未解决的问题、待评审/测试/提交的文件、可深挖的发现），而不是内部命令；同一 session 内任务互相继承上下文——每个任务都带上前序任务的压缩 `## Session context` 以及你中途的修正（修正后的目标覆盖原目标，当前指令优先级最高；`browser4.agent.session.contextTokens` 控制预算，`/new` 重新开始上下文）；`/result [--json] [--full]` 可重新打印上次结果（`--json` 美化 JSON，`--full` 不再折叠）。后端抖动或某个步骤无法解析都不会中断实时输出：`-i` 会继续跟随仍在运行的任务，并跳过那一条无法解析的步骤。任务类型默认为浏览器任务；`--coding` 显式标记纯编码任务（不做自动判定）。`--access <预设>`（`full`/`write`/`read-build`/`read`）可收窄该任务的编码工具权限，任务结束后复位；`--unrestricted` 则以**你自己的 OS 权限**运行该任务（full 预设、无工作区/受保护路径护栏、不施加 OS 沙箱，shell 自身的规则也一并放开：命令白名单（`for` 循环、内联脚本在任何模式下都原样交给平台 shell 执行；该开关额外放开 PowerShell cmdlet 这类白名单外程序）），按任务审计、`--resume` 会继承；但 OS 凭据目录（`~/.ssh`、`~/.aws`、`~/.gnupg`、`~/.config/gcloud`）以及会杀死或重建后端的命令仍然拒绝；`--add-dir <目录>` 为该任务额外授权一个**已存在**的目录（可重复；可读、可写、可作为 shell 工作目录——`--access` 仍限制能否写入，凭据目录始终拒绝，`--resume` 会继承该授权；`-i` 会话内可用 `/add-dir <目录>` 随时追加、`/dirs [clear]` 列出或清空）；被拒的步骤不是死路——`coding.requestAccess` 会登记一个 pending 请求且不阻塞任务，操作员通过 `POST /api/agent/<taskId>/access/<requestId>/approve` 批准，而 `denyTools`、受保护路径与敏感路径永不批准；无 TTY 的任务（脚本、CI、提交式任务）默认派生 `full` 并允许出站网络，而非沿用服务端预设（`browser4.agent.access.unattended` 可收窄） |
| `agent status <id>` | 查询任务状态——共享状态信封（`status`：queued/running/done/failed/cancelled，另有 `statusCode`、`message` 与 agent 步数）。 |
| `agent result <id>` | 获取已完成任务的结果：摘要、`keyFindings`、`filesChanged`，以及产物所在的 workspace 目录。人类可读输出按段落渲染（`--json` 打印原始信封）。 |
| `agent list` | 列出已跟踪的 agent 任务（状态与 `agent status` 一致，每次调用都会向服务端刷新）；`--clear` 可移除已结束的任务。 |

```bash
browser4-cli extract "product name, price, rating"
browser4-cli extract "contacts" --schema @schema.json
browser4-cli summarize --selector "#reviews"
browser4-cli agent run "Go to amazon.com, compare the first 3 keyboards, write a summary"
browser4-cli agent status agent-task-1
```

## Batch 与 loop 自动化

| 命令 | 说明 |
|---|---|
| `batch [command...]` | 在一次调用中执行多条命令。步骤在客户端逐条执行——每步一次后端调用，走与等价单命令完全相同的路径。支持 `--bail`（首个失败步骤即停）与从 stdin 读取命令数组的 `--json`；退出码 5 表示至少有一个步骤失败。 |
| `loop [task]` | 周期性运行一个任务。支持 `--name`、`-i/--interval`、`-n/--count`、`-t/--timeout`、`--shell`、`--list`、`--pause`、`--resume`、`--pause-all`、`--resume-all`、`--stop`、`--stop-all`、`--status`、`--history`、`--keep-state`。 |

可用于 batch 的命令：

```text
goto  go-back  go-forward  reload  press  key  keyboard  type  keydown  keyup
click  dblclick  hover  fill  select  upload  check  uncheck  drag  focus
mousemove  mousedown  mouseup  mousewheel  scroll  wait  get  eval
snapshot  screenshot  pdf  dialog-accept  dialog-dismiss  resize
tab-list  tab-new  window-new  tab-close  tab-select  frame
htmlsnapshot  htmlsnapshot-capture  htmlsnapshot-get  htmlsnapshot-get-all
```

```bash
browser4-cli batch --bail "goto https://example.com" "snapshot" "screenshot"
browser4-cli loop "load https://example.com and extract the title" -i 300 -n 10
browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60
browser4-cli loop --list
```

batch 的每个步骤都在客户端编译并执行，走的是与等价单命令相同的代码路径，
因此步骤的行为与单独执行时一致：必要时自动开会话、恢复焦点元素与鼠标位置、
遵守 `--timeout` 与 `--verify`，并各自输出快照。步骤**不会**被打包成一个后端
请求——每一步都是一次独立调用。失败的步骤会在 stderr 上以
`Batch command N failed (<command>): <reason>` 报出，批次继续执行后续步骤，
除非显式传了 `--bail`。

## 网络检查、HAR 录制与请求路由

查看页面实际加载了哪些网络请求（XHR/fetch、状态码、请求头、响应体），
录制可被 Chrome DevTools 导入的 HAR 1.2 归档，并对匹配的请求做路由
（mock 响应或中止）。完整指南见
[`skills/browser4-cli/references/network.md`](../skills/browser4-cli/references/network.md)。

| 命令 | 说明 |
|---|---|
| `network requests` | 列出已跟踪的请求。支持 `--filter`、`--type`、`--method`、`--status`（`200`、`2xx`、`400-499`）、`--clear`。 |
| `network request <id>` | 单个请求的完整详情：请求/响应头、时序与响应体（按需拉取）。 |
| `network har start [--content <mode>]` | 开始 HAR 录制。内容模式：`none`、`text` 或 `all`（二进制 base64）。 |
| `network har stop [path]` | 停止录制并输出 HAR JSON；给出路径时写入 `.har` 文件。 |
| `network route <pattern> --body <text>\|--abort` | 拦截匹配的请求（mock 响应或让其失败），基于 CDP Fetch。支持 `--content-type`、`--resource-type`。 |
| `network unroute [pattern]` | 移除路由；不带 pattern 时完全关闭拦截。 |

```bash
browser4-cli network requests --filter api --status 2xx
browser4-cli network har start --content text
browser4-cli network har stop ./capture.har
browser4-cli network route "**/api/users" --body '{"users":[]}' --content-type application/json
```

## 用于规模化处理的 Swarm 与 Crawl

`co` 前缀可以作为 `swarm` 的别名使用。

| 命令 | 说明 |
|---|---|
| `swarm create` | 创建并行抓取会话。支持 `--profile-mode`、`--max-open-tabs`、`--max-browser-contexts`、`--display-mode`。 |
| `swarm submit [url]` | 提交 URL 或 X-SQL payload 作为作业。支持 `--seed-file`、`--sql`、`--deadline`、`--expires`、`--refresh`、`--parse`。 |
| `swarm query <url>` | 对一个或多个已加载页面提交 X-SQL 提取任务。支持 `--sql`、`--seed-file`、`--deadline`、`--expires`、`--refresh`。 |
| `swarm status <id>` | 查询 swarm 任务状态。 |
| `swarm result <id>` | 获取已完成的 swarm 任务结果。 |
| `swarm list` | 列出已跟踪的 swarm 任务。 |
| `swarm close` | 关闭 swarm 会话并释放浏览器资源。 |
| `crawl [url]` | 从 URL 或 seed file 开始抓取（每个种子必须是绝对 http(s) URL，非法值会在提交前被拒绝）。支持 `--seed-file`、`--sql`、`--sql-stdin`、`--sql-base64`、`--format`、`--output`、`-d/--depth`、`-ol/--out-link-selector`、`-olp/--out-link-pattern`、`-tl/--top-links`、`-a/--args`、`--refresh`、`--parse`、`--expires`、`-p/--priority`、`--page-load-timeout`、`--ignore-url-query`、`--no-norm`、`--readonly`、`--args-stdin`、`--parallel`、`--timeout`、`--verbose`、`-bg/--background`。 |
| `crawl status <id>` | 查询 crawl 任务状态。 |
| `crawl result <id>` | 获取 crawl 结果。 |
| `crawl cancel <id>` | 取消运行中的 crawl 任务（检查点会保留，因此之后仍可续传）。 |
| `crawl resume <id>` | 从检查点续传被中断的 crawl（断点续传）：任务 ID 不变，已成功抓取的 URL 不会重复请求。`--retry-failed` 可重试终态失败 URL；后端启动时自动续传默认关闭（`crawl.autoResume`）。详见 [Crawl checkpoint & resume](crawl-checkpoint-resume.md)。 |
| `crawl clear` | 删除处于终态的 crawl 任务；支持扩展清理选项。仍有剩余工作的检查点会被保留，直到 `crawl clear --all`。 |
| `crawl list` | 列出已跟踪的 crawl 任务（`--status interrupted` 可筛出可续传的任务）。 |

```bash
browser4-cli swarm create --max-open-tabs 12 --display-mode HEADLESS
browser4-cli swarm query --seed-file urls.txt --sql @query.sql --refresh
browser4-cli crawl "https://example.com" --depth 2 --out-link-selector "a[href]"
browser4-cli crawl list
browser4-cli crawl resume <task-id>    # 续传被中断的 crawl
```

## WebMiner——HTML 转电子表格，零 LLM Token

WebMiner 基于下载到本地的 JAR（`scent-miner.jar`）运行，无需 Browser4 服务端。完整流程见 [如何把 HTML 转成电子表格](../README.zh.md#如何把-html-转成电子表格零-llm-token)。

| 命令 | 说明 |
|---|---|
| `webminer install [version]` | 下载并安装 webminer 发行版到 `~/.scent/webminer`，校验 SHA-256（GitHub Releases，带 OSS 镜像回退）。支持 `--force`。 |
| `webminer update` | 将 webminer 更新到最新发行版。 |
| `webminer version` | 打印已安装的 webminer 版本。 |
| `webminer uninstall` | 移除已安装的 webminer。 |
| `webminer run-example` | 运行内置示例。 |
| `webminer all <html-dir>` | 对一个 HTML 文件目录运行完整 pipeline（encode → cluster → views）。支持 `--max-files`、`--output`、`--resume`。 |
| `webminer views <result-dir>` | 从已有聚类结果目录重建交互式视图（`<project>.html`、xlsx、json）。 |

```bash
browser4-cli webminer install
browser4-cli webminer all ./pages/
```

## 内置 skill 文件 与 已安装运行时 skill

Browser4 中有两套不同的 “skill” 表面：

1. **`skills ...`**：管理随 CLI 一起打包、嵌入式分发的 skill 文档。
2. **`skill-*`**：管理由后端暴露的、已安装运行时 skill。

### CLI 内置 skills

| 命令 | 说明 |
|---|---|
| `skills` | 列出内置 skill 名称。 |
| `skills list` | 与 `skills` 等价。 |
| `skills get <name>` | 打印某个 skill 的 `SKILL.md`。支持 `--full` 与 `--all`。 |
| `skills path [name]` | 打印内置 skill 目录路径。 |
| `skills unpack [dest]` | 将内置 skill 文件解包到目录。 |

### 已安装运行时 skills

| 命令 | 说明 |
|---|---|
| `skill-list` | 列出已安装的后端 skill。 |
| `skill-info <id>` | 显示 skill 详细元数据。 |
| `skill-install <path>` | 从包含 `SKILL.md` 的目录安装一个 skill。支持 `--overwrite`。 |
| `skill-uninstall <id>` | 按 ID 删除 skill。 |
| `skill-reload <id>` | 从源目录重新加载 skill。 |

## 渐进式经验记忆

这些命令作用于 Browser4 的学习型经验存储。

| 命令 | 说明 |
|---|---|
| `experience save <url> <trace>` | 保存任务执行轨迹。支持 `--outcome`、`--intent`、`--task-type`（规范任务类型，含 `publish_post`），以及 `--facts <inline JSON \| @file.json>`——把复盘式知识（`selectors` / `interaction_hints` / `known_blockers` / `anti_patterns`，camelCase 或 snake_case 键均可）合并进 (domain, intent) 的 facts 条目；当该条目为 VERIFIED（不可变）时合并会被拒绝。 |
| `experience query <url>` | 查询某个 URL / 域名已知的选择器、阻塞因素和提示。支持 `--intent`。 |
| `experience list` | 列出已存储的经验条目。支持 `--filter`、`--intent-filter`、`--page`、`--page-size`。 |
| `experience deep-learn <url> <intent>` | 基于已存储轨迹重建 (domain, intent) 知识并尝试晋升（不访问页面）。支持 `--force`。 |

## 插件

插件是运行在服务端的 JAR 扩展，用于扩展 Browser4 能力。开发模式的运行时
bundle 会随源码树一起打包首批插件：`browser4-swarm`、`browser4-images`、
`browser4-markdown`、`browser4-media`、`browser4-seo`（SEO 为显式启用：
`-Dbrowser4.plugins.enable=browser4-seo`）。`browser4-{captcha,pptx,profile-import,summary-demo}`
需要按需安装：

```bash
browser4-cli plugin install "browser4-plugins/browser4-pptx/target/browser4-pptx-$(cat VERSION).jar"
browser4-cli stop    # 下一条命令会自动重启开发后端
```

插件工具名是 `domain.method` 的 snake_case 形式
（`image.detectImages` → `image_detect_images`），用 `browser4-cli tool list`
（或 `GET /mcp/tools`）枚举；`plugin list` 列出的是已安装的插件 JAR，不是工具名。

| 命令 | 说明 |
|---|---|
| `plugin list` | 列出已安装插件。 |
| `plugin info <name>` | 显示插件详情。 |
| `plugin install <file>` | 从本地 JAR 文件安装插件。支持 `--replace`。 |
| `plugin remove <name>` | 删除插件。支持 `-y`、`--yes`。 |
| `tool list [--filter <substr>]` | 列出后端公布的全部 MCP 工具名（含插件工具）；`tool call <name> --json '{…}'` 调用其中之一。 |

## 已移除命令

| 命令 | 替代 |
|---|---|
| `act <description>` | 4.16 移除：服务端 `/api/act` 的一次性文本→工具调用翻译链路已删除（它会把失败的工具调用报成成功）。改用 `agent run "<task>" --wait`——CLI tool loop 是唯一的 agent 引擎。 |

