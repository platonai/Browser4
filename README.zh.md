# 🤖 Browser4

<p align="center">
  <a href="https://github.com/platonai/Browser4/actions/workflows/ci.yml"><img alt="CI build" src="https://img.shields.io/github/actions/workflow/status/platonai/Browser4/ci.yml?branch=main&style=flat-square"></a>
  <a href="https://github.com/platonai/Browser4/releases"><img alt="Release" src="https://img.shields.io/github/v/release/platonai/Browser4?style=flat-square"></a>
  <a href="https://github.com/platonai/Browser4"><img alt="Stars" src="https://img.shields.io/github/stars/platonai/Browser4?style=flat-square"></a>
  <a href="https://central.sonatype.com/artifact/ai.platon.pulsar/browser4-core"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/ai.platon.pulsar/browser4-core?style=flat-square"></a>
  <a href="https://www.npmjs.com/package/browser4-cli"><img alt="npm" src="https://img.shields.io/npm/v/browser4-cli?style=flat-square"></a>
  <a href="https://browser4.io"><img alt="Website" src="https://img.shields.io/website?url=https%3A%2F%2Fbrowser4.io&style=flat-square"></a>
  <a href="https://github.com/platonai/Browser4"><img alt="Top language" src="https://img.shields.io/github/languages/top/platonai/Browser4?style=flat-square"></a>
  <a href="https://github.com/platonai/Browser4/blob/main/LICENSE"><img alt="License" src="https://img.shields.io/badge/license-APACHE2-green?style=flat-square"></a>
</p>

---

[English](README.md) | 简体中文 | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

> ⭐ **Browser4 对你有帮助？** 运行 `browser4-cli star` —— 已登录 GitHub CLI（`gh`）时，确认一次即由它代你加星（`--gh` 跳过确认）；否则它会用你已登录的浏览器打开本仓库，Star 由你自己点。

<!-- TOC -->
**目录**
- [🤖 Browser4](#-browser4)
  - [🌟 项目简介](#-项目简介)
    - [✨ 核心能力](#-核心能力)
  - [快速开始](#快速开始)
  - [DeepSeek Harness 集成](#deepseek-harness-集成)
  - [🧭 工具选择指南](#-工具选择指南)
    - [如何与页面交互](#如何与页面交互)
    - [如何提取数据](#如何提取数据)
    - [如何批量处理页面](#如何批量处理页面)
    - [如何把 HTML 转成电子表格——零 LLM Token](#如何把-html-转成电子表格零-llm-token)
  - [📦 安装](#-安装)
  - [💡 面向人的 CLI 指南](#-面向人的-cli-指南)
    - [快速上手](#快速上手)
    - [心智模型](#心智模型)
    - [全局选项](#全局选项)
    - [命令列表前需要理解的概念](#命令列表前需要理解的概念)
    - [常用命令速查](#常用命令速查)
    - [超时环境变量](#超时环境变量)
    - [状态持久化](#状态持久化)
  - [🚀 从源码构建](#-从源码构建)
  - [架构](#架构)
  - [📦 模块概览](#-模块概览)
  - [🧩 编程内核（browser4-coding）](#-编程内核browser4-coding)
  - [🧪 测试夹具服务器（MockSite）](#-测试夹具服务器mocksite)
  - [🤝 支持与社区](#-支持与社区)
  - [📜 文档](#-文档)
  - [🔧 代理配置](#-代理配置---解锁网站访问)
  - [许可证](#许可证)
  - [友链](#友链)
<!-- /TOC -->

## 🌟 项目简介

💖 **Browser4 — 面向 AI Agent 的新一代智能浏览器引擎，连接网页、数据与自动化任务。** 💖

### ✨ 核心能力

* 🤖 **Agent Browser** — 通过 Rust CLI、MCP 与 Agent 后端，让 AI Agent 和人类都能驱动真实浏览器：导航、点击、填写、快照、批处理与循环。
* 🧬 **零 Token 提取** — 用 X-SQL + CSS 选择器从实时页面或存储的 HTML 快照中确定性提取；WebMiner 机器学习聚类把 HTML 语料整理成电子表格与报告视图，全程不消耗 LLM Token。
* 🧠 **混合智能** — 融合 LLM 提取、ML 聚类、X-SQL 与渐进式经验库，复用已习得的选择器与障碍处理经验。
* ⚡ **高性能架构** — 协程安全、CDP 原生，面向单机每天 10 万～20 万复杂网页访问设计，可经 swarm/crawl 横向扩展。
* 📦 **企业级自动化平台** — 大规模爬取（swarm/crawl）、批处理/循环任务、有状态会话、插件、运行时 skills、浏览器扩展，以及可选的 MCP-over-HTTP 端点。
* 🛠️ **编程 Agent 内核** — 50+ 个 `coding.*` 工具（沙箱 shell/文件系统、脚手架、校验、自开发），让 Agent 既能构建 Browser4 工件，也能开发 Browser4 本身。

## 快速开始

把下面这段说明粘贴给你喜欢的 AI 智能体（如 dsh、claude、codex、workbuddy 或 openclaw）并执行：

```
Read https://browser4.io/SKILL.md, install or upgrade browser4-cli for browser automation, perform the following task:

1. 用有头模式打开浏览器（`open --headed`），让窗口可见——这是演示给人看的
2. 打开 amazon.com
3. 搜索可以在白板上书写用的笔
4. 对比前 4 个结果
5. 把结果写入一个 Markdown 文件
```

任务条目用中文描述同样可行——agent 只需要读懂 SKILL，之后的指令用什么语言都可以。

`https://browser4.io/SKILL.md` 是本仓库 [skills/browser4-cli/SKILL.md](skills/browser4-cli/SKILL.md) 的发布副本；已经克隆仓库时，建议直接让 agent 读这个本地路径，这样它看到的版本与当前代码一致。

## DeepSeek Harness 集成

https://github.com/platonai/dsh-browser4

```
dsh plugin --profile web add dsh-browser4                  # npm registry
dsh plugin --profile web add github:platonai/dsh-browser4  # GitHub
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
└─ 需要连续执行多个 UI 步骤？→ batch "goto ..." "click ..." "fill ..."（步骤在客户端逐条执行）
```

页面内容嵌在 `<iframe>` 中（支付表单、编辑器、小组件）时，使用内置的 frame 切换：`frames` 列出 frame 树，`frame "<iframe 选择器>"` 把后续元素命令的作用域切到该 iframe（同源 iframe 完整支持），`frame main` 回到主文档——无需手写 `contentDocument` eval。

典型交互流程：

```bash
browser4-cli goto https://example.com/login
browser4-cli snapshot -i --boxes
browser4-cli fill e3 "user@example.com"
browser4-cli fill e4 "secret" --submit
browser4-cli wait --load networkidle
browser4-cli snapshot -i
# iframe 较多的页面：
browser4-cli frame "#pay-frame"
browser4-cli fill "#card-number" "4111 1111 1111 1111"
browser4-cli frame main
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
├─ 一页要多种产出（正文 + 链接 + 图片 + 表格）？
│  → scrape --formats "markdown,links,images"   # 一次抓取，全部产出
├─ 要截图或 PDF **文件**落到本机？
│  → scrape --formats "screenshot,pdf" --output ./out
├─ 自然语言需求（“找到商品价格”）？→ extract（需要 LLM key）
└─ 大规模、多页面处理？→ crawl 或 swarm 搭配 --sql
```

### 如何批量处理页面

```
需要处理多页面？
├─ 单个列表页（搜索结果页）？→ htmlsnapshot query + LOAD_AND_SELECT
├─ 已知 URL 列表（在文件中）？→ crawl --seed-file urls.txt --depth 0 --sql @query.sql
├─ 从起始 URL 开始递归抓取？→ crawl <url> --out-link-selector "..." --depth N
├─ 需要并行执行（高吞吐）？→ swarm create → swarm query --seed-file ...
├─ 需要周期性监控（如每小时检查一次）？→ loop -i 3600 -- eval "..."
└─ 只是脚本里处理少量 URL？
   → for url in ...; do browser4-cli goto "$url"; ... done
```

### 如何把 HTML 转成电子表格——零 LLM Token

[WebMiner](https://github.com/platonai/web-miner) 会对下载下来的 HTML 文件做机器学习聚类，生成结构化电子表格和交互式报告——**不消耗 LLM Token，全部本地运行。** webminer 是 Browser4 CLI 的一等公民：`browser4-cli webminer install` + `browser4-cli webminer all <html-dir>` 即可跑完整流程，无需 PowerShell。

```
已经有 HTML 文件，想要结构化数据，而且不想花 LLM Token？
├─ < 1,000 页（小中规模）？→ WebMiner Free（SMILE ML 引擎）
│  browser4-cli webminer install
│  browser4-cli webminer all ./pages/
│  → 交互式 HTML 报告 + Excel 电子表格，本地运行，零成本
├─ > 1,000 页（生产规模）？→ WebMiner Commercial（Apache Spark ML）
│  同样是 encode → cluster → views 流程，但可分布式扩展到多台机器
└─ 还需要先获取页面？
   ├─ 单页下载：browser4-cli htmlsnapshot export
   ├─ 批量下载：browser4-cli crawl --seed-file urls.txt --depth 0
   └─ 高吞吐：browser4-cli swarm create → swarm query --seed-file ...
       然后把 HTML 目录交给 WebMiner
```

> **Pipeline：** `encode`（HTML → 特征向量 → CSV）→ `cluster`（KMeans，自动检测 K）→ `views`（HTML 报告 + Excel）。免费版使用 [SMILE](https://haifengl.github.io/) ML 库进行单机聚类（< 1,000 页）。需要 JDK 17+（自动检测）。用法见 [web-miner](https://github.com/platonai/web-miner) 与 `browser4-cli help webminer`。

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

# 先把页面写入页面库，再从这份副本中提取
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
| `-h`, `--help [command\|category]` | 显示顶层帮助、分类帮助或某个命令的详细帮助。`--help category:<name>` 始终列出某个分类（分类名同时是命令时用这种写法，例如 `category:install`）；`--help hidden:<prefix>` 列出隐藏命令家族（`hidden:code`、`hidden:config`）。帮助宽度跟随 `COLUMNS`（限制在 80–160） |
| `--help-json` | 输出机器可读的命令参考 JSON |
| `-v`, `--version` | 打印 CLI 版本 |
| `-s`, `--session <name>` | 使用命名会话，而不是默认会话。这是**全局**标志，必须写在命令**之前**（`browser4-cli -s job-42 snapshot`）——写在命令之后会被当作位置参数而报错 |
| `--server <url>` | 覆盖 Browser4 服务端 URL |
| `--timeout <seconds>` | 覆盖当前命令的 HTTP 超时时间 |
| `--proxy <url>` | 安装 / 下载运行时使用的代理 |
| `--json` | 只输出机器可读 JSON |
| `--pretty` | 美化 JSON 输出 |
| `-q`, `--quiet` | 隐藏正常的人类可读输出 |
| `--show-tip`, `-tip` | 每条命令后在 stderr 输出相关提示 |

### 命令列表前需要理解的概念

#### 元素 ref 与 CSS 选择器

- `snapshot` 会返回可访问性树中的 ref，例如 `e5`、`e12`、`e42`
- 大多数交互命令同时接受 snapshot ref 和 CSS 选择器
- `htmlsnapshot` 系列命令使用 CSS 选择器，不使用可访问性 ref

#### `snapshot` 与 `htmlsnapshot`

| 工具 | 适用场景 | 输入模型 | 输出模型 |
|---|---|---|---|
| `snapshot` | 点击、输入、查找可交互元素 | 实时可访问性树 | `e15` 这类 ref |
| `htmlsnapshot` | DOM 检查、CSS 提取、X-SQL | 活动页的最新快照（每条命令都先 capture 活动页，再在快照上操作） | CSS 选择器和查询结果 |

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

> 只配置**一个**提供商。当存在多个提供商 key 时，按 Browser4 自己的提供商顺序先命中的那个生效——
> 该顺序声明在 `browser4-core/browser4-resources/src/main/resources/config/llm/providers.json`，
> 因此残留的 `DEEPSEEK_API_KEY` 会静默压过 `OPENAI_*`，而 `OPENAI_API_KEY` 会压过 `GROQ_API_KEY`。
> `browser4-cli doctor` 会显示最终生效的 key（`Selected key`）和后端实际请求的模型
> （`Active model`）。详见 [LLM 配置](docs/config.md#which-provider-is-used)。

### 常用命令速查

任意单条命令都可用 `browser4-cli --help <command>` 查看参数；**[完整 CLI 命令参考](docs/cli-reference.zh.md)** 才是覆盖全部命令、选项与平台差异的权威清单。下表是日常高频子集。

| 领域 | 命令 |
|---|---|
| 会话与服务端 | `open`、`attach`、`close`、`list`、`status`、`doctor`、`stop`、`kill all`、`install`、`upgrade`、`uninstall` |
| 导航 | `goto`、`go back`、`go forward`、`reload` |
| 交互 | `snapshot`、`click`、`dblclick`、`hover`、`fill`、`type`、`press`、`select`、`check`、`uncheck`、`drag`、`upload`、`wait`、`frames`、`frame` |
| 键盘与鼠标 | `keydown`、`keyup`、`mousemove`、`mousedown`、`mouseup`、`mousewheel`、`scroll` |
| 检查与提取 | `get`、`eval`、`console`、`cdp`、`screenshot`、`pdf`、`htmlsnapshot`、`generate locator`、`resize`、`dialog accept`、`dialog dismiss` |
| 标签页与存储 | `tab list`、`tab new`、`tab close`、`tab select`、`state save`、`state load`、`cookie`、`localstorage`、`sessionstorage`、`webdb` |
| AI（需要 LLM key） | `extract`、`summarize`、`chat`、`chat result`、`agent run`、`agent status`、`agent result`、`agent list` |
| 规模化自动化 | `batch`、`loop`、`swarm`、`crawl`、`webminer` |
| 网络 | `network requests`、`network request`、`network har`、`network route`、`network unroute` |
| 插件、工具与 skills | `plugin`、`tool list`、`tool call`、`skills`、`skill-*` |
| 经验记忆 | `experience save`、`experience query`、`experience list`、`experience deep-learn` |

交互命令接受 `snapshot` ref（如 `e15`）或 CSS 选择器，多数还支持 `--no-snapshot` 跳过动作后的自动快照。

```bash
browser4-cli open --headed https://example.com     # 默认是无头模式
browser4-cli snapshot -i --boxes                   # 取得后续步骤要用的 ref
browser4-cli fill e4 "john@example.com" --submit
browser4-cli htmlsnapshot query --sql @query.sql
browser4-cli batch --bail "goto https://example.com" "snapshot" "screenshot"
browser4-cli crawl "https://example.com" --depth 2 --out-link-selector "a[href]"
browser4-cli agent run "compare the first 3 keyboards" --wait
```

四条最容易踩的规则：

- **`htmlsnapshot` 读的是活页面。** 每次读取都先捕获活动标签页，所以页面变化后直接重跑即可；想读页面库里的旧快照就加 `--expires 1d`，标签页完全不动。
- **`batch` 在客户端执行。** 每一步都是一次独立后端调用，行为与单命令一致；失败步骤会报出，除非给 `--bail` 否则继续执行，只要有步骤失败退出码即为 `5`。
- **`networkidle` 不等于"结果已渲染"。** 它只证明网络安静了；延迟渲染的内容请轮询元素（`wait "<result-selector>"`）。
- **`--proxy` 是下载代理。** 只作用于 install/upgrade，不会让页面流量走代理；页面流量请按 [IP 代理](docs/ip-proxy.zh.md) 配置。

### 超时环境变量

| 变量 | 默认值 | 用途 |
|---|---:|---|
| `BROWSER4_CLI_HTTP_TIMEOUT_SECS` | `30` | 大多数命令 |
| `BROWSER4_CLI_INPUT_TIMEOUT_SECS` | `90` | `type`、`fill` 及其他较慢的输入流程 |
| `BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS` | `120` | `goto`、`reload`、`go back`、`go forward` |

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

**前置要求：** Git、JDK 25+（Eclipse Temurin）、Chrome/Chromium，以及 PowerShell 7（Linux/macOS 需要）。完整前置条件表、平台差异工具和 Chrome 自动探测路径请见 [Build from Source](docs/build-from-source.md)。

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

   **开发模式包装脚本（无需安装）：** 仓库根目录提供自动按需构建的包装脚本。可使用 `./b4w.ps1 <command>`（PowerShell）、`./b4w.sh <command>`（Git Bash / Linux / macOS）或 `./b4w.bat <command>`（CMD），参数与已安装的 `browser4-cli` 完全一致。开发模式只运行当前检出的代码：若本地 runtime bundle 是用**不同的项目版本**构建的，命令会直接拒绝启动（并给出原因、bundle 的版本与构建时间以及重建命令），而不是悄悄测试过期的后端；若只是检出目录里的源码看起来比 bundle 的 jar 更新，命令会先重新构建再启动——参见 [CLI 安装与升级](docs/cli-install-upgrade.md#dev-mode-the-backend-must-match-the-checkout)。

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
- **编程内核**（`browser4-coding`）— 轻依赖 agent 工具箱（沙箱 shell/文件系统、脚手架、校验、自身开发工具），见[下文](#-编程内核browser4-coding)

## 📦 模块概览

| 模块 | 说明 |
|---|---|
| `cli/browser4-cli` | Rust CLI——快速、原生的浏览器自动化二进制 |
| `skills/browser4-cli` | AI 智能体 skill 定义（SKILL.md） |
| `browser4-core` | 核心引擎：会话、调度、DOM、浏览器控制 |
| `browser4-dependencies` | BOM 与依赖版本对齐 |
| `browser4-tools` | 运维工具与启动辅助 |
| `browser4-agentic` | AI agent、MCP 集成、skill 注册 |
| `browser4-coding` | 编程内核——沙箱 shell/fs、工件脚手架与校验、自身开发工具（50+ 个 `coding.*` 工具） |
| `browser4-agent-tools` | 高层 agent 工具：抓取、爬取、有状态页面交互 |
| `browser4-rest` | Spring Boot REST 层与命令端点 |
| `browser4-apps/browser4-standalone` | 产品打包——统一启动器（`target/Browser4.jar`） |
| `examples/browser4-examples` | 可运行示例与演示 |
| `browser4-tests` | E2E、集成与场景测试套件 |
| `cdp-protocol` | Chrome DevTools Protocol JSON 定义 |
| `coworker/` | 内置 AI 协作助手 |

---

## 🧩 编程内核（browser4-coding）

`browser4-coding` 是轻依赖的编程内核：让 AI agent 既能创建 Browser4 工件，也能**开发 Browser4 自身**。它独立于 `browser4-agentic` 与 `pulsar-common`（仅依赖 SLF4J + Jackson + 协程），可供非 agent 宿主复用；重后端（LSP 服务器、kotlin-compiler-embeddable）仅在运行时探测，默认不加载不下载。

`coding` 域共 **50+ 个工具**，分四组：

| 分组 | 数量 | 要点 |
|---|---|---|
| Shell 与文件系统 | 32 | 沙箱 `coding.shell`（命令白名单）+ `runCode` 代码片段执行、快照编辑原语 + `revert`、`diff`（Myers/Patience）、仓库治理保护（`coding.protect`） |
| 工件创作与校验 | 5 | `scaffold`（plugin/skill/js/script）、`scaffoldToDir`、`scaffoldFlow`（多文件开发流）、`scaffoldFromExample`（反陈旧活模板，目录模式 + 词干派生改名）、`validate`（含 `repo-consistency`） |
| 自身开发 | 13 | `mvnBuild`（结构化诊断）、`ktSymbols`/`ktReferences`/`ktInheritance`/`classInfo`（零依赖 Kotlin 分析）、`impact` + `moduleGraph`（实时 pom 图谱）、`devTask` + `resumeDevTask`（AGENTS.md 流程 + 执行）、`verify`（确定性门禁裁决）、`trapCheck`（CDP 陷阱）、`tokenStats`/`estimateTokens`（上下文预算） |
| LSP | 4 | 按需 `diagnostics`/`symbols`/`references`/`lspServers`（ts/js/py/rs，服务器缺失时优雅降级） |

**通用能力 vs 项目专用**：内核按"机制 vs 数据"分层——diff、沙箱、LSP 客户端、Kotlin 分析、Maven 通道与 pom 图谱扫描均为通用可移植；脚手架、校验器、`ModuleMap`、`CdpTrapCheck` 与治理默认清单编码了 Browser4 约定，复用到其他项目时需重写这一层。

- 工具全量参考与工作流：`skills/browser4-coding/SKILL.md`
- 开发 Browser4 自身：`skills/browser4-dev/SKILL.md`
- 四类工件对照示例（真实实现 vs 脚手架输出）：`docs-dev/copilot/examples/`
- 评估总结（P1–P7）：`docs-dev/copilot/browser4-programming-support-eval.md`

---

## 🧪 测试夹具服务器（MockSite）

Browser4 自带一个轻量级 **MockSite** 服务器，用于提供静态 HTML 测试页和演示页。可在仓库根目录启动：

**Windows（pwsh —— PowerShell 7+）：** `./bin/test.ps1 mock-site -Dmock.site.port=18080`
**Linux/macOS：** `./bin/test.sh mock-site -Dmock.site.port=18080`

> **首次运行（Maven 缓存为空）需要 10–15 分钟**（实测约 13 分钟），期间终端没有任何输出：
> 脚本会依次安装 `browser4-dependencies`，再安装 `browser4-rest` 及其约 25 个模块，每个阶段
> 完成后才打印一条 `[PASS]` 横幅。构建产物缓存之后启动很快；等待期间不要杀掉进程，也不要
> 同时启动第二个 Maven 构建。

关键演示页面位于 `http://localhost:18080/generated/`。完整页面列表、环境变量、Python 回退方案和基于 Maven 的启动方式请见 [MockSite](docs/mocksite.md)。测试分类体系与标签系统请见 [Test Taxonomy](docs/TESTING.md)。

---

## 🤝 支持与社区

欢迎加入社区，获取支持、提出反馈并参与协作！

- **GitHub Discussions**：与开发者和用户交流
- **Issue Tracker**：报告 bug 或提出功能需求
- **Social Media**：关注项目动态和更新

欢迎贡献代码和文档！详情见 [CONTRIBUTING.md](CONTRIBUTING.md)。

### 联系我们

- 💬 WeChat: galaxyeye
- 🌐 Weibo: [galaxyeye](https://weibo.com/galaxyeye)
- 📧 Email: galaxyeye@live.cn, ivincent.zhang@gmail.com
- 🐦 Twitter: galaxyeye8

<div style="display: flex;">
  <img src="docs/images/wechat-author.png" width="300" height="365" alt="WeChat QR Code" />
</div>

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

单条固定代理（`--proxy-server`）、代理认证、轮换端点必须返回的格式、以及出口 IP 的验证方法，
见 **[docs/ip-proxy.zh.md](docs/ip-proxy.zh.md)**（[English](docs/ip-proxy.md)）。
注意 `browser4-cli --proxy` 是**下载**代理，不会让页面流量走代理。

</details>

---

## 许可证

Apache 2.0 License。详见 [LICENSE](LICENSE)。

## 友链

* [omdsh-dev/dsh-data-agent](https://github.com/omdsh-dev/dsh-data-agent) - DSH Data Agent · 用对话分析数据
* [ccch1mneyyy/dsh-TUI](https://github.com/ccch1mneyyy/dsh-TUI) - DSH TUI · 一个美观且实用的 Claude Code 风格 TUI 插件


