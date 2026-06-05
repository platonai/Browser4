# 🤖 Browser4

[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)

---

[English](README.md) | 简体中文 | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**目录**
- [🤖 Browser4](#-browser4)
    - [🌟 项目简介](#-项目简介)
        - [✨ 核心能力](#-核心能力)
    - [💡 使用示例](#-使用示例)
        - [快速入门](#快速入门)
        - [CLI 与技能 (SKILLS)](#cli-与技能-skills)
    - [高级命令](#高级命令)
        - [Agent 和 Swarm CLI](#agent-和-swarm-cli)
    - [🚀 Native API 快速开始](#-native-api-快速开始)
        - [自动提取](#自动提取)
    - [📦 模块概览](#-模块概览)
    - [🤝 支持与社区](#-支持与社区)
    - [📜 文档](#-文档)
    - [🔧 代理配置 - 解锁网站访问](#-代理配置---解锁网站访问)
    - [许可证](#许可证)
<!-- /TOC -->

## 🌟 项目简介

💖 **Browser4：为你的 AI 打造的闪电般快速、协程安全的浏览器引擎** 💖

### ✨ 核心能力

* 👽 **浏览器智能体** — 完全自主的浏览器智能体，能够推理、规划并端到端执行任务。
* 🤖 **浏览器自动化** — 高性能自动化，涵盖工作流、导航和数据提取。
* ⚙️ **机器学习智能体** — 在不消耗 token 的情况下学习复杂页面的字段结构。
* ⚡  **极致性能** — 完全协程安全；单机每天支持 10 万 ~ 20 万次复杂页面访问。
* 🧬 **数据提取** — 结合 LLM、ML 和选择器，在混乱的页面中提取干净的数据。

## 💡 使用示例

### 快速入门

只需让任何大语言模型（LLM）智能体使用 browser4-cli 来处理浏览器交互，它就能胜任像这样的复杂任务：

```shell
$prompt = @"
Install https://raw.githubusercontent.com/platonai/Browser4/refs/heads/main/cli/skill/SKILL.md and use browser4-cli and perform the following task:

1. go to amazon.com
2. search for pens to draw on whiteboards
3. compare the first 4 ones
4. write the result to a markdown file
"@

copilot --allow-all -p "$prompt"
# claude --dangerously-skip-permissions "$prompt"
```

### CLI 与技能 (SKILLS)

Browser4 CLI 是一个强大的命令行界面，用于直接控制浏览器和实现自动化，专为人类用户和 AI 智能体设计。它提供简洁的语法来执行复杂的浏览器交互，而无需编写代码。

Browser4 CLI 兼容 Playwright，支持导航、交互和数据提取等丰富的命令。它可以在脚本、终端会话中使用，也可以通过技能 (SKILLS) 集成到 AI 智能体中。

通过 npm 全局安装 browser4-cli：

```shell
npm install -g browser4-cli
```

```shell
# 打开 browser4（不导航到任何页面）
browser4-cli open

# 以 headed 或 headless 模式打开
browser4-cli open --headed https://browser4.io
browser4-cli open --headless https://browser4.io

# 导航到页面——如果没有活动会话则自动打开一个
browser4-cli goto https://playwright.dev

# 检查页面——注意可交互节点上的 eN 标签
browser4-cli snapshot

# 使用快照中的 ref 进行交互
browser4-cli click e15
browser4-cli type e15 "Hello World"
browser4-cli press e15 Enter
browser4-cli keydown Shift
browser4-cli mousemove 150 300
browser4-cli mousewheel 0 100
browser4-cli keyup Shift

# 截图并保存到磁盘
browser4-cli screenshot

# 使用自定义服务器 URL
browser4-cli open --server http://localhost:9090

# 在一个进程中执行多个命令
browser4-cli batch "goto https://playwright.dev" "snapshot"

# 在首个失败处停止批处理
browser4-cli batch --bail "goto https://playwright.dev" "click e1" "screenshot"

# 高级：通过 stdin 以 JSON 格式传入批处理命令
echo '[
  ["goto", "https://example.com/form-filling"],
  ["click", "#reset-btn"],
  ["fill", "#first-name", "Bob"],
  ["fill", "#last-name", "Smith"],
  ["fill", "#email", "bob@example.com"],
  ["select", "#country", "uk"],
  ["check", "#agree-terms"],
  ["click", "#submit-btn"]
]' | browser4-cli batch --json

# 完成后关闭会话
browser4-cli close
```

## 高级命令

以下命令有意从全局 `browser4-cli help` 概览中省略。
需要时显式查询它们：

```bash
browser4-cli help batch
browser4-cli help extract
browser4-cli help summarize
browser4-cli help agent run
browser4-cli help swarm create
```

Browser4 CLI 专为 AI 智能体通过技能 (SKILLS) + CLI 使用而设计。

[SKILL.md](cli/skill/SKILL.md)

### Agent 和 Swarm CLI

Browser4 CLI 提供两种高级接口，用于超越标准单步操作的复杂多步骤浏览器任务：

**Agent CLI**（`agent <subcommand>`）—— 提交自然语言任务，让 Browser4 的后端 AI 智能体自主规划和执行。智能体会对页面进行推理，决定采取哪些操作，并异步完成任务。适用于：有明确目标但不了解页面结构、需要多步骤探索而无须为每个动作编写脚本的场景。

**Swarm CLI**（`swarm <subcommand>`）—— 跨多个浏览器上下文编排并行抓取和结构化数据提取。专为高吞吐量任务打造：刷新精选 URL 列表、有监督的扇出式浏览、以及可重复执行的基于选择器的抓取。支持 X-SQL，可对已加载的网页进行结构化查询。

| 接口 | 模式 | 适用场景 |
|---|---|---|
| 标准命令 | 每次调用执行单个操作 | 你已知道确切的 ref/选择器，需要精确控制 |
| Agent CLI | 自然语言任务 → 自主执行 | 有目标但不了解页面结构；多步骤探索 |
| Swarm CLI | 并行上下文 + X-SQL 查询 | 高吞吐量抓取、跨多个页面进行结构化提取 |

#### Agent CLI 示例

提交自然语言任务，让后端智能体自主推理、规划和执行：

```shell
# 提交自主任务 — 立即返回任务 ID
browser4-cli agent run "打开 example.com，找到注册表单，用测试数据填写，然后截图"

# 使用返回的任务 ID 轮询进度
browser4-cli agent status agent-task-1

# 任务完成后读取最终结果
browser4-cli agent result agent-task-1
```

底层工作流程：
1. `agent run` 将任务发送到 Browser4 后端，后端会启动一个 AI 智能体，该智能体拥有工具访问权限（导航、点击、输入、快照、截图、提取、总结等）。
2. 智能体迭代探索页面，获取快照，决策操作，并逐步执行直到任务完成。
3. `agent status` 返回后端状态负载（通常是 JSON 格式，包含 `id`、`status`、`statusCode`、`processState`、`agentState`、`agentHistory`、`commandResult` 等字段）。
4. `agent result` 返回最终任务输出 — 根据任务类型可能是纯文本或结构化 JSON。

关键说明：
- `agent run` 是异步的：后端接受任务后立即返回。
- `agent run` 会在提交后快速探测，以便 LLM/API 密钥配置错误能及早发现。
- Agent 命令基于任务 ID，不需要活动的 CLI 浏览器会话槽。
- Agent 子命令不支持在 `batch` 模式中使用。

#### Swarm CLI 示例

创建 swarm 会话，提交 URL 进行抓取，然后批量收集结果：

```shell
# 1) 创建具有并行浏览器上下文的 swarm 抓取会话
browser4-cli swarm create \
  --profile-mode=TEMPORARY \
  --max-open-tabs=12 \
  --max-browser-contexts=3 \
  --display-mode=HEADLESS

# 2) 将 URL 提交为抓取任务（直接 URL + 种子文件）
browser4-cli swarm submit https://example.com/direct \
  --seed-file=./urls.txt \
  --refresh --parse --store-content

# 3) 轮询并获取结果
browser4-cli swarm status scrape-task-4
browser4-cli swarm result scrape-task-4
```

运行 X-SQL 查询从已加载的网页中提取结构化数据：

```shell
# 内联 X-SQL 查询
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
  FROM load_and_select(@url, 'body');
"

# 从文件读取查询
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql

# 带种子文件和加载选项
browser4-cli swarm query --sql @query.sql --seed-file=./urls.txt --refresh --parse
```

关键说明：
- 种子文件为纯文本格式，每行一个 URL；`#` 注释和空行将被忽略。
- `swarm submit` 和 `swarm query` 都支持 `--seed-file`、`--deadline`、`--expires`、`--refresh`、`--parse`、`--store-content`。
- 所有 swarm 命令都会返回任务 ID；使用 `swarm status` / `swarm result` 跟踪进度。
- 在 X-SQL 模板中使用 `@url` — 它会在服务器端替换为目标 URL。

---

## 🚀 从源码构建

**前置条件**：Java 17+

1. **克隆仓库**
   ```shell
   git clone https://github.com/platonai/Browser4.git
   cd Browser4
   ```

2. **配置你的 LLM API 密钥**

   > 编辑 [application.properties](application.properties) 并添加你的 API 密钥。

3. **构建项目**
   ```shell
   ./mvnw -DskipTests
   ```

---

🎬 YouTube：
[![观看视频](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 Bilibili：
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

### 自动提取

基于自监督/无监督机器学习的大规模、高精度字段发现与提取 — 无需 LLM API 调用，不消耗 token，确定性且快速。

**功能：**
- 高精度学习商品/详情页面上的每个可提取字段（通常数十到数百个）。
- 当 Browser4 在 GitHub 上获得 10K 星标时开源。

**为什么不仅仅使用 LLM？**
- LLM 提取会增加延迟、成本和 token 限制。
- 基于 ML 的自动提取是本地化的、可复现的，并可扩展至每天 10 万 ~ 20 万页面。
- 你仍然可以结合使用两者：用自动提取获取结构化基线数据 + 用 LLM 进行语义增强。

**快速命令（PulsarRPAPro）：**
```bash
# 注意：需要 MongoDB
curl -L -o PulsarRPAPro.jar https://github.com/platonai/PulsarRPAPro/releases/download/v3.0.0/PulsarRPAPro.jar
```

**集成状态：**
- 现已通过配套项目 [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro) 可用。
- 原生的 Browser4 API 接口正在规划中；请关注版本发布以获取更新。

**核心优势：**
- 高精度：>95% 的字段被发现；绝大多数字段准确率 >99%（在测试域名上的参考数据）。
- 对选择器变化和 HTML 噪声具有鲁棒性。
- 零外部依赖（无需 API 密钥）→ 大规模使用时具有成本优势。
- 可解释：生成的选择器和 SQL 透明且可审计。

👽 使用机器学习智能体提取数据：

![自动提取结果快照](docs/assets/images/amazon.png)

（即将推出：更丰富的仓库内示例和直接的 API 接口。）

---

## 📦 模块概览

| 模块                   | 描述                                         |
|------------------------|----------------------------------------------|
| `cli`                  | 基于 Rust 的 CLI，支持技能 (SKILLS)            |
| `browser4-core`        | 核心引擎：会话、调度、DOM、浏览器控制            |
| `browser4-agentic`     | 智能体实现、MCP 和技能注册                      |
| `browser4-rest`        | Spring Boot REST 层和命令端点                  |
| `browser4-standalone`  | 智能体和爬虫编排，包含产品打包                    |
| `examples`             | 可运行的示例和演示                              |
| `browser4-tests`       | 端到端测试、重量级集成测试和场景测试              |

---

## 🤝 支持与社区

加入我们的社区，获取支持、反馈和协作！

- **GitHub Discussions**：与开发者及用户交流互动。
- **Issue Tracker**：报告 bug 或请求新功能。
- **社交媒体**：关注我们以获取更新和新闻。

我们欢迎贡献！详情请参见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 📜 文档

完整的文档可在 `docs/` 目录和我们的 [GitHub Pages 站点](https://platonai.github.io/browser4/) 上找到。

---

## 🔧 代理配置 - 解锁网站访问

<details>

设置环境变量 `PROXY_ROTATION_URL` 为你的代理服务商提供的轮换 URL：

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

每次访问此轮换 URL 时，它应返回包含一个或多个新代理 IP 的响应。
如果你需要此类型的 URL，请联系你的代理服务提供商。

</details>

---

## 许可证

Apache 2.0 许可证。详情请参见 [LICENSE](LICENSE) 文件。
