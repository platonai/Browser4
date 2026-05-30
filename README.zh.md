# 🤖 Browser4

[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)

---

[English](README.md) | 简体中文 | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**目录**
- [🤖 Browser4](#-browser4)
    - [🌟 项目简介](#-项目简介)
        - [✨ 核心能力](#-核心能力)
    - [🎥 演示视频](#-演示视频)
    - [🚀 快速开始](#-快速开始)
    - [💡 使用示例](#-使用示例)
        - [浏览器智能体 (Browser Agents)](#浏览器智能体-browser-agents)
        - [工作流自动化](#工作流自动化)
        - [LLM + X-SQL](#llm--x-sql)
        - [高速并行处理](#高速并行处理)
        - [自动提取](#自动提取)
    - [📦 模块概览](#-模块概览)
    - [📜 文档](#-文档)
    - [🔧 代理配置 - 解锁网站访问](#-代理配置---解锁网站访问)
    - [✨ 特性](#-特性)
    - [🤝 支持与社区](#-支持与社区)
<!-- /TOC -->

## 🌟 项目简介

💖 **Browser4：为你的 AI 打造的闪电般快速、协程安全的浏览器引擎** 💖

### ✨ 核心能力

* 👽 **浏览器智能体** — 完全自主的浏览器智能体，能够推理、规划并端到端执行任务。
* 🤖 **浏览器自动化** — 高性能的自动化工作流、页面导航和数据提取。
* ⚙️ **机器学习智能体** — 在不消耗 token 的情况下学习复杂页面的字段结构。
* ⚡  **极致性能** — 完全协程安全；支持每台机器每天 10 万 ~ 20 万次复杂页面访问。
* 🧬 **数据提取** — 结合 LLM、ML 和选择器，在混乱的页面中提取干净的数据。

## 💡 使用示例

### 快速入门

只需让任何大语言模型（LLM）智能体调用 browser4-cli来处理浏览器交互，它就能胜任像这样的复杂任务。

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
# 打开一个新的浏览器窗口
browser4-cli open

# 导航到页面
browser4-cli goto https://playwright.dev

# 检查页面 — 注意可交互节点上的 eN 标签
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

从源码构建 CLI：

[README.md](cli/browser4-cli/README.md)

Browser4 CLI 专为 AI 智能体通过技能 (SKILLS) + CLI 使用而设计。

[SKILL.md](cli/skill/SKILL.md)

---

### 🚀 Native API 快速开始

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

4. **运行示例**
   ```shell
   ./mvnw -pl examples/browser4-examples exec:java -D"exec.mainClass=ai.platon.pulsar.examples.agent.Browser4AgentKt"
   ```
   如果在 Windows 上遇到编码问题：
   ```shell
   ./bin/run-agent-examples.ps1
   ```

   在 `browser4-examples` 模块中探索并运行示例，亲身体验 Browser4 的强大功能。
   Java 兼容示例已被移除；请使用 Kotlin API、SDK 或 CLI 工具。

关于 Docker 部署，请查看我们的 [Docker Hub 仓库](https://hub.docker.com/r/galaxyeye88/browser4)。

**Windows 用户**：你也可以将 Browser4 构建为独立的 Windows 安装程序。详情请参阅 [Windows 安装程序指南](browser4-app/browser4-agents/README.md)。

---

### 浏览器智能体 (Browser Agents)

理解自然语言指令并执行复杂浏览器工作流的自主智能体。

```kotlin
val agent = AgenticContexts.getOrCreateAgent()

val task = """
    1. 访问 amazon.com
    2. 搜索用于白板绘图的笔
    3. 对比前 4 个商品
    4. 将结果写入 markdown 文件
    """

agent.run(task)
```

### 工作流自动化

提供精细控制的底层浏览器自动化与数据提取。

**特性：**
- 支持实时 DOM 访问和离线快照解析
- 直接且完整的 Chrome DevTools Protocol (CDP) 控制，协程安全
- 精确的元素交互（点击、滚动、输入）
- 使用 CSS 选择器/XPath 进行快速数据提取

```kotlin
val session = AgenticContexts.getOrCreateSession()
val agent = session.companionAgent
val driver = session.getOrCreateBoundDriver()

// 加载输入 URL 引用的初始页面
var page = session.open(url)

// 使用自然语言指令驱动浏览器
agent.act("滚动到评论区")
// 从实时 DOM 中读取第一个匹配的评论节点
val content = driver.selectFirstTextOrNull("#comments")

// 将页面快照保存为内存文档以进行离线解析
var document = session.parse(page)
// 一次性将 CSS 选择器映射到结构化字段
var fields = session.extract(document, mapOf("title" to "#title"))

// 让伴随智能体执行多步导航/搜索流程
val history = agent.run(
    "前往 amazon.com，搜索 '智能手机'，打开评分最高的商品页面"
)

// 将更新后的浏览器状态捕获回 PageSnapshot
page = session.capture(driver)
document = session.parse(page)
// 从捕获的快照中提取其他属性
fields = session.extract(document, mapOf("ratings" to "#ratings"))
```

### LLM + X-SQL

非常适合高复杂度的数据提取流水线，涉及数十个实体和每个实体数百个字段。

**优势：**
- 与传统方法相比，可多提取 10 倍的实体和 100 倍的字段
- 将 LLM 智能与精确的 CSS 选择器/XPath 相结合
- 类似 SQL 的语法，实现熟悉的数据查询方式

```kotlin
val context = AgenticContexts.create()
val sql = """
select
  llm_extract(dom, 'product name, price, ratings') as llm_extracted_data,
  dom_first_text(dom, '#productTitle') as title,
  dom_first_text(dom, '#bylineInfo') as brand,
  dom_first_text(dom, '#price tr td:matches(^Price) ~ td, #corePrice_desktop tr td:matches(^Price) ~ td') as price,
  dom_first_text(dom, '#acrCustomerReviewText') as ratings,
  str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
from load_and_select('https://www.amazon.com/dp/B08PP5MSVB -i 1s -njr 3', 'body');
"""
val rs = context.executeQuery(sql)
println(ResultSetFormatter(rs, withHeader = true))
```

示例代码：

* [使用 X-SQL 从亚马逊商品页面抓取 100+ 个字段](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)
* [使用 X-SQL 抓取所有类型的亚马逊网页](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)

### 高速并行处理

通过并行浏览器控制和智能资源优化实现极致吞吐量。

**性能：**
- 每台机器每天 1 万 ~ 2 万次复杂页面访问
- 并发会话管理
- 资源拦截以加快页面加载速度

```kotlin
val args = "-refresh -dropContent -interactLevel fastest"
val blockingUrls = listOf("*.png", "*.jpg")
val links = LinkExtractors.fromResource("urls.txt")
    .map { ListenableHyperlink(it, "", args = args) }
    .onEach {
        it.eventHandlers.browseEventHandlers.onWillNavigate.addLast { page, driver ->
            driver.addBlockedURLs(blockingUrls)
        }
    }

session.submitAll(links)
```

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
curl -L -o PulsarRPAPro.jar https://github.com/platonai/PulsarRPAPro/releases/download/v4.6.0/PulsarRPAPro.jar
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

| 模块                 | 描述                                         |
|----------------------|----------------------------------------------|
| `cli`                | 基于 Rust 的 CLI，支持技能 (SKILLS)            |
| `browser4-core`      | 核心引擎：会话、调度、DOM、浏览器控制            |
| `browser4-agentic`   | 智能体实现、MCP 和技能注册                      |
| `browser4-rest`      | Spring Boot REST 层和命令端点                  |
| `browser4-agents`    | 智能体和爬虫编排，包含产品打包                    |
| `examples`           | 可运行的示例和演示                              |
| `browser4-tests`     | 端到端测试、重量级集成测试和场景测试              |

---

## ✨ 特性

状态说明：[Available] 已在仓库中可用，[Experimental] 正在积极迭代中，[Planned] 尚未在仓库中，[Indicative] 性能目标。

### AI 与智能体
- [Available] 具备问题解决能力的自主浏览器智能体
- [Available] 并行智能体会话
- [Experimental] LLM 辅助的页面理解和提取

### 浏览器自动化与 RPA
- [Available] 基于工作流的浏览器操作
- [Available] 精确的协程安全控制（滚动、点击、提取）
- [Available] 灵活的事件处理器和生命周期管理

### 数据提取与查询
- [Available] 一行命令完成数据提取
- [Available] 用于 DOM/内容查询的 X-SQL 扩展查询语言
- [Experimental] 结构化与非结构化混合提取（LLM & ML & 选择器）

### 性能与可扩展性
- [Available] 高效并行页面渲染
- [Available] 抗拦截设计和智能重试
- [Indicative] 在普通硬件上每天处理 10 万+ 复杂页面

### 隐匿性与可靠性
- [Experimental] 高级反反爬技术
- [Available] 通过 `PROXY_ROTATION_URL` 进行代理轮换
- [Available] 弹性调度和质量保证

### 开发者体验
- [Available] 简洁的 API 集成（REST、原生、文本命令）
- [Available] 丰富的配置分层
- [Available] 清晰的结构化日志和指标

### 存储与监控
- [Available] 本地文件系统和 MongoDB 支持（可扩展）
- [Available] 全面的日志和透明度

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

