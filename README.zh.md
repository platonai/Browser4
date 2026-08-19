# 🤖 Browser4

[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)

---

[English](README.md) | 简体中文 | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**目录**
- [🤖 Browser4](#-browser4)
  - [🌟 项目简介](#-项目简介)
    - [✨ 核心能力](#-核心能力)
  - [快速开始](#快速开始)
  - [🧭 工具选择指南](#-工具选择指南)
    - [如何与页面交互](#如何与页面交互)
    - [如何提取数据](#如何提取数据)
    - [如何批量处理页面](#如何批量处理页面)
    - [如何把 HTML 转成电子表格——零 Token](#如何把-html-转成电子表格零-token)
  - [📦 安装](#-安装)
  - [💡 面向人的 CLI 指南](#-面向人的-cli-指南)
    - [快速上手](#快速上手)
    - [心智模型](#心智模型)
    - [全局选项](#全局选项)
    - [命令列表前需要理解的概念](#命令列表前需要理解的概念)
    - [完整命令参考](#完整命令参考)
    - [超时环境变量](#超时环境变量)
    - [状态持久化](#状态持久化)
  - [🚀 从源码构建](#-从源码构建)
  - [架构](#架构)
  - [📦 模块概览](#-模块概览)
  - [🧪 测试夹具服务器（MockSite）](#-测试夹具服务器mocksite)
  - [🤝 支持与社区](#-支持与社区)
  - [📜 文档](#-文档)
  - [🔧 代理配置](#-代理配置---解锁网站访问)
  - [许可证](#许可证)
<!-- /TOC -->

## 🌟 项目简介

💖 **Browser4：一个快速、智能、可扩展、适用于多种场景的智能体浏览器** 💖

## 🌟 简介

💖 **Browser4 — 面向 AI Agent 的新一代智能浏览器引擎，连接网页、数据与自动化任务。** 💖

### ✨ 核心能力

* 🤖 **Agent Browser** — 为 AI Agent 提供自主浏览、网页操作和自动化执行能力。
* 🧠 **ML 智能提取** — 通过机器学习理解网页结构，无需消耗 LLM Token，即可从复杂页面提取结构化数据。
* ⚡ **高性能架构** — 协程安全设计，支持单机每天 10 万～20 万复杂网页访问。
* 🧬 **智能数据管线** — 融合 LLM、ML、X-SQL 与选择器，实现复杂网页的数据提取、清洗与经验复用。
* 📦 **企业级自动化平台** — 支持大规模爬取、CDP 原生控制、批处理、有状态浏览、插件扩展等能力。

## 快速开始

把下面这段说明粘贴给你喜欢的 AI 智能体（如 claude、codex、workbuddy 或 openclaw）并执行：

```
Read https://browser4.io/SKILL.md and install browser4-cli (if not installed) for browser automation to perform the following task:

1. Open the browser in headed mode (`open --headed`) so the window is visible — this is a human-facing demo
2. go to amazon.com
3. search for pens to draw on whiteboards
4. compare the first 4 ones
5. write the result to a markdown file
```

## 🧭 工具选择指南

根据任务类型选择最合适的工具：

### 如何与页面交互

```
需要与页面交互？
├─ 需要打开或恢复浏览器会话？→ open [url] 或 goto <url>
├─ 想先看当前哪些元素可点击 / 可输入？→ snapshot -i --boxes
├─ 需要点击按钮、链接、复选框或菜单项？→ click <ref>
├─ 需要填写表单并替换已有文本？→ fill <ref> "<text>"
├─ 需要像真人一样继续输入，或者发送 Enter / Tab？→ type / press
├─ 需要从下拉框中选择值？→ select <ref> <value>
├─ 需要悬停、拖拽、滚动，或直接使用鼠标？→ hover / drag / scroll / mouse*
├─ 需要在下一步前等待页面稳定？
│  ├─ 等元素出现？→ wait <ref|selector>
│  ├─ 等文本出现？→ wait --text "..."
│  ├─ 等 URL 变化？→ wait --url "**/target"
│  └─ 等加载 / 网络请求完成？→ wait --load networkidle
├─ 需要确认动作之后页面发生了什么变化？→ snapshot、get 或 eval
└─ 需要高效重复很多 UI 步骤？→ batch "goto ..." "click ..." "fill ..."
```

典型交互流程：

```bash
browser4-cli goto https://example.com/login
browser4-cli snapshot -i --boxes
browser4-cli fill e3 "user@example.com"
browser4-cli fill e4 "secret" --submit
browser4-cli wait --load networkidle
browser4-cli snapshot -i
```

### 如何提取数据

```
需要从页面提取数据？
├─ 页面需要先点击、填写、滚动？→ snapshot + refs，再提取
├─ 静态页面，只取一个字段？→ htmlsnapshot get text "<selector>"
├─ 静态页面，获取某字段所有匹配项？→ htmlsnapshot get all text "<selector>"
├─ 静态页面，需要相关联的多字段（每个条目的标题+价格+链接）？
│  → htmlsnapshot query --sql @query.sql
├─ 需要处理实时 JS / 复杂 DOM 逻辑？→ eval --json
├─ 自然语言需求（“找到商品价格”）？→ extract（需要 LLM key）
└─ 大规模、多页面处理？→ crawl 或 swarm 搭配 --sql
```

### 如何批量处理页面

```
需要处理多页面？
├─ 单个列表页（搜索结果页）？→ htmlsnapshot query + DOM_LOAD_AND_SELECT
├─ 已知 URL 列表（在文件中）？→ crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ 从起始 URL 开始递归抓取？→ crawl <url> --out-link-selector "..." --depth N
├─ 需要并行执行（高吞吐）？→ swarm create → swarm query --seed-file ...
├─ 需要周期性监控（如每小时检查一次）？→ loop -- eval "..." -i 3600
└─ 只是脚本里处理少量 URL？
   → for url in ...; do browser4-cli goto "$url"; ... done
```

### 如何把 HTML 转成电子表格——零 Token

[WebMiner](https://github.com/platonai/web-miner) 会对下载下来的 HTML 文件做机器学习聚类，生成结构化电子表格和交互式报告——**不消耗 LLM token，全部本地运行。**

```
已经有 HTML 文件，想要结构化数据，而且不想花 token？
├─ < 1,000 页（小中规模）？→ WebMiner Free（SMILE ML 引擎）
│  java -jar scent-miner.jar all ./pages/
│  → 交互式 HTML 报告 + Excel 电子表格，本地运行，零成本
├─ > 1,000 页（生产规模）？→ WebMiner Commercial（Apache Spark ML）
│  同样是 encode → cluster → views 流程，但可分布式扩展到多台机器
└─ 还需要先获取页面？
   ├─ 单页下载：browser4-cli htmlsnapshot export
   ├─ 批量下载：browser4-cli crawl --seed-file urls.txt --depth 0
   └─ 高吞吐：browser4-cli swarm create → swarm query --seed-file ...
       然后把 HTML 目录交给 WebMiner
```

> **Pipeline：** `encode`（HTML → 特征向量 → CSV）→ `cluster`（KMeans，自动检测 K）→ `views`（HTML 报告 + Excel）。免费版使用 [SMILE](https://haifengl.github.io/) ML 库进行单机聚类（< 1,000 页）。需要 JDK 17+。安装说明见 [web-miner](https://github.com/platonai/web-miner)。

---

## 📦 安装

手动安装是可选的，因为 AI 智能体在阅读 SKILL 后通常可以自行完成安装。

通过 npm 全局安装 browser4-cli（需要 Node.js）：

```shell
npm install -g browser4-cli
browser4-cli install
```

或者用单条命令直接引导安装原生二进制。脚本随后会自动安装 Browser4 后端（运行时包）——全新机器执行 `browser4-cli install`，已安装后端则执行 `browser4-cli upgrade` 升级到最新版（可用 `--skip-backend` 跳过此步骤）：

**Windows（PowerShell）：**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
```

**Linux / macOS（bash）：**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
```

## 💡 面向人的 CLI 指南

`browser4-cli` 不只是智能体后端，它本身也是一个适合人类直接使用的浏览器自动化命令行工具。你可以用它驱动真实浏览器、检查页面状态、提取结构化数据、运行 X-SQL、编排 crawl / swarm 任务、管理服务端插件与 skills，也可以把长任务交给内建的 AI 能力处理。

如果你想看嵌入给智能体使用的说明，请查看 [skills/browser4-cli/SKILL.md](skills/browser4-cli/SKILL.md)。这一节是面向人的参考手册。

### 快速上手

```bash
# 打开浏览器会话（默认无头模式，添加 --headed 可显示窗口）
browser4-cli open https://browser4.io

# 或者显式打开可视浏览器：
browser4-cli open --headed https://browser4.io

# 检查页面并获取元素 ref
browser4-cli snapshot --boxes

# 使用 snapshot 输出中的 ref 进行交互
browser4-cli click e15
browser4-cli fill e16 "Browser4" --submit

# 从当前页面实时提取数据
browser4-cli get text "h1"

# 捕获静态 DOM 快照，用于重复提取
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#main-content"
browser4-cli htmlsnapshot query --sql @query.sql

# 保存输出
browser4-cli screenshot --full-page --filename page.jpg
browser4-cli pdf --filename page.pdf
```

### 心智模型

1. **以会话为中心**：命令默认作用于当前浏览器会话；需要隔离状态时使用 `-s <name>`。
2. **两种页面视图**：`snapshot` 用于交互式工作，提供 `e15` 这样的元素 ref；`htmlsnapshot` 用于 DOM / X-SQL 提取，基于 CSS 选择器。
3. **交互式提取 vs 静态提取**：页面需要先操作时用 `click`、`fill`、`type`、`press`、`wait`；需要结构化提取时优先用 `htmlsnapshot query`。
4. **同步命令 vs 异步任务**：`agent`、`swarm`、`crawl`、异步 chat 一类命令会返回任务 ID，后续再查询状态和结果。

### 全局选项

这些标志可以放在任何命令之前。

| 标志 | 说明 |
|---|---|
| `-h`, `--help [command\|category]` | 显示顶层帮助、分类帮助或某个命令的详细帮助 |
| `--help-json` | 输出机器可读的命令参考 JSON |
| `-v`, `--version` | 打印 CLI 版本 |
| `-s`, `--session <name>` | 使用命名会话，而不是默认会话 |
| `--server <url>` | 覆盖 Browser4 服务端 URL |
| `--timeout <seconds>` | 覆盖当前命令的 HTTP 超时时间 |
| `--proxy <url>` | 安装 / 下载运行时使用的代理 |
| `--json` | 只输出机器可读 JSON |
| `--pretty` | 美化 JSON 输出 |
| `-q`, `--quiet` | 隐藏正常的人类可读输出 |
| `-tip`, `--show-tip` | 每条命令后在 stderr 输出相关提示 |

### 命令列表前需要理解的概念

#### 元素 ref 与 CSS 选择器

- `snapshot` 会返回可访问性树中的 ref，例如 `e5`、`e12`、`e42`
- 大多数交互命令同时接受 snapshot ref 和 CSS 选择器
- `htmlsnapshot` 系列命令使用 CSS 选择器，不使用可访问性 ref

#### `snapshot` 与 `htmlsnapshot`

| 工具 | 适用场景 | 输入模型 | 输出模型 |
|---|---|---|---|
| `snapshot` | 点击、输入、查找可交互元素 | 实时可访问性树 | `e15` 这类 ref |
| `htmlsnapshot` | DOM 检查、CSS 提取、X-SQL | 已存储的 HTML 快照 | CSS 选择器和查询结果 |

#### LLM 配置

`extract`、`summarize`、`chat`、`agent run` 以及 X-SQL 的 `llm_*` 函数都需要 LLM 提供商的 API key。

| 提供商 | 环境变量 |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY`, `OPENROUTER_MODEL_NAME`, `OPENROUTER_BASE_URL` |
| Volcengine | `VOLCENGINE_API_KEY`, `VOLCENGINE_MODEL_NAME`, `VOLCENGINE_BASE_URL` |
| OpenAI-compatible | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |
| Aliyun Qwen | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

### 完整命令参考

#### 会话生命周期与服务端管理

| 命令 | 说明 |
|---|---|
| `open [url]` | 打开浏览器会话，或重新连接已有会话。**默认为无头模式。** 支持 `--headed`（可视窗口）、`--headless`、`--profile <path>`、`--profile-mode <DEFAULT\|SYSTEM_DEFAULT\|SEQUENTIAL\|TEMPORARY>`、`--interact-level <FASTEST\|FAST\|DEFAULT>`。 |
| `attach` | 通过 CDP 或 Browser4 扩展附加到现有浏览器。支持 `--cdp <url\|port\|channel>` 与远程 endpoint 选项。 |
| `close` | 关闭当前活动浏览器会话。 |
| `list` | 列出浏览器会话及其状态和下次打开行为。支持 `--all`。 |
| `session-default <name>` | 把一个命名会话设为默认未命名会话。 |
| `close-all` | 关闭所有会话，但不停止后端。 |
| `kill-all` | 强制停止后端以及 Browser4 管理的浏览器进程。 |
| `stop` | 优雅停止 Browser4 服务。 |
| `status` | 显示服务版本、端口和健康状态。 |
| `doctor` | 运行诊断：构建信息、LLM 状态、陈旧 daemon 清理、可选修复。支持 `--verbose` 与 `--fix`。 |
| `doctor log [name]` | 列出、查看、tail 或 grep 后端日志文件。支持 `--tail`、grep 风格参数，以及 `doctor log <name> grep <pattern>`。 |
| `doctor metrics [filter]` | 列出、过滤或 grep 后端指标。支持 `doctor metrics grep <pattern>`。 |
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
```

#### 导航

| 命令 | 说明 |
|---|---|
| `goto <url>` | 导航到 URL；如果需要会自动打开 / 重连会话。 |
| `go-back` | 在浏览器历史中后退。 |
| `go-forward` | 在浏览器历史中前进。 |
| `reload` | 刷新当前页面。 |

#### 核心交互

除非另有说明，所有交互命令都接受 snapshot ref（如 `e15`）或 CSS 选择器。多数命令还支持 `--no-snapshot`，用于跳过动作执行后的自动可访问性快照。

| 命令 | 说明 |
|---|---|
| `click <ref> [button]` | 点击元素。支持 `--modifiers`、`--follow`、`--auto-dismiss-dialogs`。 |
| `dblclick <ref> [button]` | 双击元素。支持 `--modifiers`、`--follow`、`--auto-dismiss-dialogs`。 |
| `hover <ref>` | 悬停到元素上。 |
| `fill <ref> <text>` | 清空并填写可编辑字段。支持 `--submit`、`--verify`。 |
| `type <text> [ref]` | 向当前焦点元素或指定目标元素输入文本。支持 `--submit`、`--verify`、`--focus`、`--interactable-timeout`。 |
| `press <key> [ref]` | 向当前焦点元素或指定目标元素发送按键。支持 `--verify`、`--follow`。 |
| `select <ref> <value>` | 选择下拉框值。支持 `--verify`。 |
| `check <ref>` | 勾选复选框或单选框。 |
| `uncheck <ref>` | 取消勾选复选框或单选框。 |
| `drag <startRef> <endRef>` | 从一个元素拖放到另一个元素。 |
| `wait [target]` | 等待 selector/ref、时长、文本、URL 模式、页面加载状态或 JavaScript 表达式。支持 `--timeout`、`--text`、`--url`、`--load`、`--fn`。 |

`wait --load` 接受 `domcontentloaded`、`load` 和 `networkidle`。

```bash
browser4-cli click e8 --follow
browser4-cli fill e4 "john@example.com" --submit
browser4-cli type "Browser4" e7 --verify
browser4-cli wait --text "Success"
browser4-cli wait --load networkidle
```

#### 键盘与鼠标

| 命令 | 说明 |
|---|---|
| `keydown <key>` | 按下并保持某个键。 |
| `keyup <key>` | 释放某个已按住的键。 |
| `mousemove <x> <y>` | 把鼠标移动到页面 / 屏幕坐标。 |
| `mousedown [button]` | 按下鼠标按键。 |
| `mouseup [button]` | 释放鼠标按键。 |
| `mousewheel <dx> <dy>` | 用滚轮 delta 进行滚动。 |
| `scroll <direction> <pixels>` | 按 `up`、`down`、`left` 或 `right` 滚动页面。 |

#### 页面检查与实时提取

| 命令 | 说明 |
|---|---|
| `snapshot` | 捕获可访问性树快照。支持 `--boxes`、`-i/--interactive`、`-u/--urls`、`-c/--compact`、`--no-compact`、`-d/--depth`、`-l/--limit`、`-s/--selector`、`--raw`、`--stdout`、`-vp/--viewport`、`--filename`。 |
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

#### HTML 快照与 X-SQL 提取

`htmlsnapshot` 会捕获并存储原始 DOM 快照，是 Browser4 结构化提取工作流的核心。

| 命令 | 说明 |
|---|---|
| `htmlsnapshot` | `htmlsnapshot capture` 的简写。 |
| `htmlsnapshot capture` | 捕获并存储静态 HTML 快照，同时返回页面和交互元素的元数据。 |
| `htmlsnapshot get <field> [selector] [name]` | 从已存储快照中提取第一个匹配项的 `text`、`html` 或 `attr`。 |
| `htmlsnapshot get all <field> [selector] [name]` | 从已存储快照中提取全部匹配值。支持 `--offset` 和 `--limit`。 |
| `htmlsnapshot query [url]` | 运行 X-SQL。支持 `--sql <query\|@file>`、`--sql-stdin`、`--sql-base64`、结果分页和提取导向输出选项。 |
| `htmlsnapshot export` | 把已存储 HTML 导出到文件。支持位置参数文件路径或 `--file <path>`，以及 `--clean`。 |
| `htmlsnapshot summary` | 生成压缩版 Web Page Summary Index（WPSI）。 |
| `htmlsnapshot grep <pattern>` | 用 grep 风格参数搜索已存储 HTML。 |
| `htmlsnapshot inspect [selector]` | 发现重复 DOM 模式和候选选择器。支持 `--max`、`--depth`、`--stdin`、`--selector-base64`。 |

重要规则：

- 需要 ref 和交互时用 `snapshot`
- 需要重复 DOM 提取时用 `htmlsnapshot`
- 推荐使用 `htmlsnapshot query --sql @query.sql`，避免 shell 转义问题
- 需要关联型列表提取时，优先使用 `htmlsnapshot query`，而不是多次 `get all`

```bash
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#productTitle"
browser4-cli htmlsnapshot get all text ".result-title" --offset 10 --limit 5
browser4-cli htmlsnapshot inspect ".s-result-item" --depth 6 --max 20
browser4-cli htmlsnapshot export --file page.html --clean
browser4-cli htmlsnapshot query --sql @query.sql
```

深入了解 X-SQL 可参见 [skills/browser4-cli/references/htmlsnapshot.md](skills/browser4-cli/references/htmlsnapshot.md) 与 [skills/browser4-cli/references/x-sql-dom-load-select.md](skills/browser4-cli/references/x-sql-dom-load-select.md)。

#### 截图与 PDF

| 命令 | 说明 |
|---|---|
| `screenshot [ref]` | 对页面或元素截图。支持 `--filename`、`--full-page`、`--viewport`。 |
| `pdf` | 将当前页面保存为 PDF。支持 `--filename`。 |

#### 标签页

| 命令 | 说明 |
|---|---|
| `tab-list` | 列出打开的标签页及其索引、标题、URL；配合 `--json` 可获得完整 GUID。 |
| `tab-new [url]` | 打开新标签页，可选同时导航到 URL。 |
| `tab-close [index]` | 按索引关闭标签页；支持 `--guid <guid>`。 |
| `tab-select <index>` | 按索引切换标签页；支持 `--guid <guid>`。 |

#### 浏览器存储与本地页面数据

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

#### AI 提取、chat 与自主 agent 任务

这些命令需要 LLM key。

| 命令 | 说明 |
|---|---|
| `extract <instruction>` | 从当前页面提取结构化数据。支持 `--schema <json\|@file>`、`--filename`、`--raw`、`--stdout`。 |
| `summarize [instruction]` | 总结当前页面内容。支持 `--selector`、`--filename`、`--raw`、`--stdout`。 |
| `chat <message>` | 发送纯 AI chat 请求，不自动追加浏览器上下文。 |
| `chat-result <id>` | 获取异步 chat 任务结果。 |
| `agent run <task>` | 提交一个自主浏览器任务，并立即获得任务 ID。 |
| `agent status <id>` | 查询运行中的任务状态。 |
| `agent result <id>` | 获取已完成任务的结果。 |
| `agent list` | 列出已跟踪的 agent 任务及其状态。 |

```bash
browser4-cli extract "product name, price, rating"
browser4-cli extract "contacts" --schema @schema.json
browser4-cli summarize --selector "#reviews"
browser4-cli agent run "Go to amazon.com, compare the first 3 keyboards, write a summary"
browser4-cli agent status agent-task-1
```

#### Batch 与 loop 自动化

| 命令 | 说明 |
|---|---|
| `batch [command...]` | 在一次调用中执行多条命令。支持 `--bail` 与从 stdin 读取命令数组的 `--json`。 |
| `loop [task]` | 周期性运行一个任务。支持 `--name`、`-i/--interval`、`-n/--count`、`-t/--timeout`、`--shell`、`--list`、`--pause`、`--resume`、`--pause-all`、`--resume-all`、`--stop`、`--stop-all`、`--status`、`--history`、`--keep-state`。 |

可用于 batch 的命令：

```text
goto  go-back  go-forward  reload  press  type  keydown  keyup
click  dblclick  hover  fill  select  check  uncheck  drag
mousemove  mousedown  mouseup  mousewheel  scroll  wait
get  eval  snapshot  screenshot  pdf  dialog-accept  dialog-dismiss
resize  tab-list  tab-new  tab-close  tab-select
```

```bash
browser4-cli batch --bail "goto https://example.com" "snapshot" "screenshot"
browser4-cli loop "load https://example.com and extract the title" -i 300 -n 10
browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60
browser4-cli loop --list
```

#### 用于规模化处理的 Swarm 与 Crawl

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
| `crawl [url]` | 从 URL 或 seed file 开始抓取。支持 `--seed-file`、`--sql`、`--sql-stdin`、`--sql-base64`、`--format`、`--output`、`-d/--depth`、`-ol/--out-link-selector`、`-olp/--out-link-pattern`、`-tl/--top-links`、`-a/--args`、`--refresh`、`--parse`、`--expires`、`-p/--priority`、`--page-load-timeout`、`--ignore-url-query`、`--no-norm`、`--readonly`、`-bg/--background`。 |
| `crawl status <id>` | 查询 crawl 任务状态。 |
| `crawl result <id>` | 获取 crawl 结果。 |
| `crawl cancel <id>` | 取消运行中的 crawl 任务。 |
| `crawl clear` | 删除处于终态的 crawl 任务；支持扩展清理选项。 |
| `crawl list` | 列出已跟踪的 crawl 任务。 |

```bash
browser4-cli swarm create --max-open-tabs 12 --display-mode HEADLESS
browser4-cli swarm query --seed-file urls.txt --sql @query.sql --refresh
browser4-cli crawl "https://example.com" --depth 2 --out-link-selector "a[href]"
browser4-cli crawl list
```

#### 内置 skill 文件 与 已安装运行时 skill

Browser4 中有两套不同的 “skill” 表面：

1. **`skills ...`**：管理随 CLI 一起打包、嵌入式分发的 skill 文档。
2. **`skill-*`**：管理由后端暴露的、已安装运行时 skill。

##### CLI 内置 skills

| 命令 | 说明 |
|---|---|
| `skills` | 列出内置 skill 名称。 |
| `skills list` | 与 `skills` 等价。 |
| `skills get <name>` | 打印某个 skill 的 `SKILL.md`。支持 `--full` 与 `--all`。 |
| `skills path [name]` | 打印内置 skill 目录路径。 |
| `skills unpack [dest]` | 将内置 skill 文件解包到目录。 |

##### 已安装运行时 skills

| 命令 | 说明 |
|---|---|
| `skill-list` | 列出已安装的后端 skill。 |
| `skill-info <id>` | 显示 skill 详细元数据。 |
| `skill-install <path>` | 从包含 `SKILL.md` 的目录安装一个 skill。支持 `--overwrite`。 |
| `skill-uninstall <id>` | 按 ID 删除 skill。 |
| `skill-reload <id>` | 从源目录重新加载 skill。 |

#### 渐进式经验记忆

这些命令作用于 Browser4 的学习型经验存储。

| 命令 | 说明 |
|---|---|
| `experience save <url> <trace>` | 保存任务执行轨迹。支持 `--outcome`、`--intent`、`--task-type`。 |
| `experience query <url>` | 查询某个 URL / 域名已知的选择器、阻塞因素和提示。支持 `--intent`。 |
| `experience list` | 列出已存储的经验条目。支持 `--filter`、`--intent-filter`、`--page`、`--page-size`。 |
| `experience deep-learn <url> <intent>` | 对已存储轨迹做更深入分析。支持 `--force`。 |

#### 插件

插件是运行在服务端的 JAR 扩展，用于扩展 Browser4 能力。

| 命令 | 说明 |
|---|---|
| `plugin list` | 列出已安装插件。 |
| `plugin info <name>` | 显示插件详情。 |
| `plugin install <file>` | 从本地 JAR 文件安装插件。支持 `--replace`。 |
| `plugin remove <name>` | 删除插件。支持 `-y`、`--yes`。 |

#### 高级 / 当前隐藏命令

这些命令实际存在于 CLI 中，但默认 public help 不会展示。

| 命令 | 说明 |
|---|---|
| `upload <ref> <file>` | 向文件输入框上传一个或多个文件。 |
| `act <description>` | 实验性自然语言动作翻译器：把自然语言转换成浏览器命令并立即执行。 |

### 超时环境变量

| 变量 | 默认值 | 用途 |
|---|---:|---|
| `BROWSER4_CLI_HTTP_TIMEOUT_SECS` | `30` | 大多数命令 |
| `BROWSER4_CLI_INPUT_TIMEOUT_SECS` | `90` | `type`、`fill` 及其他较慢的输入流程 |
| `BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS` | `120` | `goto`、`reload`、`go-back`、`go-forward` |

```bash
export BROWSER4_CLI_INPUT_TIMEOUT_SECS=180
export BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS=300
```

### 状态持久化

除非显式覆盖，CLI 状态保存在 `~/.browser4` 下：

- 默认会话：`~/.browser4/cli-state.json`
- 命名会话：`~/.browser4/sessions/<name>.json`
- loops：`~/.browser4/loops/<name>.json`

运行时 bundle 则单独保存在平台惯例的应用数据目录中，因此清理会话状态不会导致重新下载 Browser4 本体。

---

## 🚀 从源码构建

**前置要求：** Git、JDK 17+（推荐 21+）、Chrome/Chromium，以及 PowerShell 7（Linux/macOS 需要）。完整前置条件表、平台差异工具和 Chrome 自动探测路径请见 [Build from Source](docs/build-from-source.md)。

1. **克隆仓库**
   ```shell
   git clone https://github.com/platonai/Browser4.git
   cd Browser4
   ```

2. **配置你的 LLM API key**

   > 编辑 [application.properties](application.properties) 并添加 API key，或者通过环境变量配置。支持的提供商和变量名见上文 [LLM 配置](#llm-配置)。

3. **构建项目**
   ```shell
   ./mvnw -DskipTests
   ```

4. **构建并运行 CLI（源码方式）**
   ```shell
   # 构建 Rust CLI（需要 Rust toolchain）
   cd cli/browser4-cli && cargo build --release

   # 或直接运行而不安装：
   cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help

   # 加上 --quiet 可隐藏 Cargo 构建状态输出：
   cargo run --quiet --manifest-path cli/browser4-cli/Cargo.toml -- <command>

   # 或全局安装：
   cd cli/browser4-cli && cargo install --path .
   ```
   > Windows 上可在命令前加 `chcp 65001 >nul &&`，以获得正确的 UTF-8 输出。
   > 完整平台说明请见 [Build from Source](docs/build-from-source.md)。

   **开发模式包装脚本（无需安装）：** 仓库根目录提供自动按需构建的包装脚本。可使用 `./b4w.ps1 <command>`（PowerShell）、`./b4w.sh <command>`（Git Bash / Linux / macOS）或 `./b4w.bat <command>`（CMD），参数与已安装的 `browser4-cli` 完全一致。

---

🎬 YouTube:
[![Watch the video](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 Bilibili:
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

## 架构

```
browser4-cli (Rust) ──MCP over HTTP──▶ browser4-rest (Kotlin/Spring) ──▶ PulsarWebDriver (Kotlin/CDP)
```

- **CLI**（`cli/browser4-cli`）— 原生 Rust 二进制，通过 MCP tool call 与后端通信
- **Backend**（`browser4-rest`）— Spring Boot 服务，负责把 MCP 工具请求分发给浏览器驱动
- **Browser driver**（`browser4-core/browser4-browser`）— 对 Chrome DevTools Protocol 的封装
- **Agent tools**（`browser4-agentic`）— 把 MCP 工具名映射到浏览器自动化方法

## 📦 模块概览

| 模块 | 说明 |
|---|---|
| `cli/browser4-cli` | Rust CLI——快速、原生的浏览器自动化二进制 |
| `skills/browser4-cli` | AI 智能体 skill 定义（SKILL.md） |
| `browser4-core` | 核心引擎：会话、调度、DOM、浏览器控制 |
| `browser4-dependencies` | BOM 与依赖版本对齐 |
| `browser4-tools` | 运维工具与启动辅助 |
| `browser4-agentic` | AI agent、MCP 集成、skill 注册 |
| `browser4-agent-tools` | 高层 agent 工具：抓取、爬取、有状态页面交互 |
| `browser4-rest` | Spring Boot REST 层与命令端点 |
| `browser4-apps/browser4-standalone` | 产品打包——统一启动器（`target/Browser4.jar`） |
| `examples/browser4-examples` | 可运行示例与演示 |
| `browser4-tests` | E2E、集成与场景测试套件 |
| `cdp-protocol` | Chrome DevTools Protocol JSON 定义 |
| `coworker/` | 内置 AI 协作助手 |

---

## 🧪 测试夹具服务器（MockSite）

Browser4 自带一个轻量级 **MockSite** 服务器，用于提供静态 HTML 测试页和演示页。可在仓库根目录启动：

**Windows：** `./bin/test.ps1 mock-site -Dmock.site.port=18080`
**Linux/macOS：** `./bin/test.sh mock-site -Dmock.site.port=18080`

关键演示页面位于 `http://localhost:18080/generated/`。完整页面列表、环境变量、Python 回退方案和基于 Maven 的启动方式请见 [MockSite](docs/mocksite.md)。测试分类体系与标签系统请见 [Test Taxonomy](docs/TESTING.md)。

---

## 🤝 支持与社区

欢迎加入社区，获取支持、提出反馈并参与协作！

- **GitHub Discussions**：与开发者和用户交流
- **Issue Tracker**：报告 bug 或提出功能需求
- **Social Media**：关注项目动态和更新

欢迎贡献代码和文档！详情见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 📜 文档

完整文档位于仓库的 `docs/` 目录，也可访问我们的 [GitHub Pages 站点](https://platonai.github.io/browser4/)。

---

## 🔧 代理配置 - 解锁网站访问

<details>

把环境变量 `PROXY_ROTATION_URL` 设置为代理服务商提供的轮换 URL：

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

每次访问这个轮换 URL 时，它都应返回一个或多个新的代理 IP。
如果你需要这种 URL，请联系你的代理服务商。

</details>

---

## 许可证

Apache 2.0 License。详见 [LICENSE](LICENSE)。
