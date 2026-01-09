# 🤖 Browser4

[![Docker Pulls](https://img.shields.io/docker/pulls/galaxyeye88/browser4?style=flat-square)](https://hub.docker.com/r/galaxyeye88/browser4)
[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.8-brightgreen?style=flat-square)](https://spring.io/projects/spring-boot)

---

[English](README.md) | 简体中文 | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

> 本文件已与英文版 README 同步（同步日期：2026-01-02），如有差异请以英文版为准。

<!-- TOC -->
**目录**
- 🤖 Browser4
    - 🌟 项目介绍
        - ✨ 核心能力
        - ⚡ 快速示例：Agentic 工作流
    - 🎥 演示视频
    - 🚀 快速开始
    - 💡 使用示例
        - 浏览器智能体
        - 工作流自动化
        - LLM + X-SQL
        - 高速并行处理
        - 自动抽取
    - 📦 模块概览
    - 📜 文档
    - 🔧 代理配置 - 解锁网站访问
    - ✨ 功能特性
    - 🤝 支持与社区
<!-- /TOC -->

## 🌟 项目介绍

💖 **Browser4：为 AI 打造的闪电般快速、协程安全 (coroutine-safe) 的浏览器引擎** 💖

### ✨ 核心能力

* 👽 **浏览器智能体 (Browser Agents)** — 能在浏览器中进行推理、规划并执行操作的自主智能体。
* 🤖 **浏览器自动化** — 面向工作流、导航和数据提取的高性能自动化。
* ⚙️ **机器学习智能体** — 在复杂页面上学习字段结构，无需消耗 Token。
* ⚡  **极致性能** — 完全协程安全；支持单机每天访问 100k ~ 200k 页面。
* 🧬 **数据抽取** — 结合 LLM、ML 与选择器，在复杂页面中获取干净数据。

## ⚡ 快速示例：Agentic 工作流

```kotlin
// 让你的智能体执行使命，而不是写脚本
val agent = AgenticContexts.getOrCreateAgent()

// 智能体规划、导航、执行，Browser4 充当“手”和“眼”
val result = agent.run("""
    1. Go to amazon.com
    2. Search for '4k monitors'
    3. Analyze the top 5 results for price/performance ratio
    4. Return the best option as JSON
""")
```

---

## 🎥 演示视频

🎬 YouTube:
[![Watch the video](https://img.youtube.com/vi/rJzXNXH3Gwk/0.jpg)](https://youtu.be/rJzXNXH3Gwk)

📺 Bilibili:
[https://www.bilibili.com/video/BV1fXUzBFE4L](https://www.bilibili.com/video/BV1fXUzBFE4L)

---

## 🚀 快速开始

**前置要求**：Java 17+ 与 Maven 3.6+

1. **克隆仓库**
   ```shell
   git clone https://github.com/platonai/browser4.git
   cd browser4
   ```

2. **配置你的 LLM API 密钥**

   编辑 [application.properties](application.properties) 并添加你的 API Key。

3. **构建项目（Linux/macOS）**
   ```shell
   ./mvnw -DskipTests
   ```
   **Windows (cmd)**：
   ```shell
   mvnw.cmd -DskipTests
   ```

4. **运行示例（Linux/macOS）**
   ```shell
   ./mvnw -pl browser4-examples exec:java -D"exec.mainClass=ai.platon.pulsar.examples.agent.Browser4AgentKt"
   ```
   **Windows (cmd)**：
   ```shell
   mvnw.cmd -pl examples/browser4-examples exec:java -D"exec.mainClass=ai.platon.pulsar.examples.agent.Browser4AgentKt"
   ```
   如有乱码问题（Windows）：
   ```shell
   ./bin/run-examples.ps1
   ```

   在 `browser4-examples` 模块中探索并运行示例，直观了解 Browser4 的能力。

Docker 部署请参见我们的 [Docker Hub 仓库](https://hub.docker.com/r/galaxyeye88/browser4)。

---

## 💡 使用示例

### 浏览器智能体

能理解自然语言指令并执行复杂浏览器工作流的自主智能体。

```kotlin
val agent = AgenticContexts.getOrCreateAgent()

val task = """
    1. go to amazon.com
    2. search for pens to draw on whiteboards
    3. compare the first 4 ones
    4. write the result to a markdown file
    """

agent.run(task)
```

### 工作流自动化

低层级浏览器自动化与数据提取，提供细粒度控制。

**特性：**
- 直接且完整的 Chrome DevTools Protocol (CDP) 控制，协程安全
- 精确的元素交互（点击、滚动、输入）
- 基于 CSS 选择器 / XPath 的快速数据提取

```kotlin
val session = AgenticContexts.getOrCreateSession()
val agent = session.companionAgent
val driver = session.getOrCreateBoundDriver()

// 打开并解析页面
var page = session.open(url)
var document = session.parse(page)
var fields = session.extract(document, mapOf("title" to "#title"))

// 与页面交互
var result = agent.act("scroll to the comment section")
var content = driver.selectFirstTextOrNull("#comments")

// 复杂的智能体任务
var history = agent.run("Search for 'smart phone', read the first four products, and give me a comparison.")

// 捕获并基于当前状态提取
page = session.capture(driver)
document = session.parse(page)
fields = session.extract(document, mapOf("ratings" to "#ratings"))
```

### LLM + X-SQL

适用于高复杂度的数据抽取流水线，包含数十个实体与每个实体数百个字段。

**优势：**
- 相比传统方法，可多提取 10 倍实体与 100 倍字段
- 结合 LLM 智能与精确 CSS 选择器 / XPath
- 类 SQL 语法，上手友好

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
* [使用 X-SQL 从亚马逊商品页抓取 100+ 字段](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)
* [抓取多类型亚马逊页面的 X-SQL 集合](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)

### 高速并行处理

通过并行浏览器控制与智能资源优化，获得极致吞吐。

**性能：**
- 单机每天访问 100,000+ 页面
- 并发会话管理
- 阻断无关资源，加速页面加载

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

🎬 YouTube:
[![Watch the video](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 Bilibili:
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

### 自动抽取

基于自 / 无监督机器学习的自动化、大规模、高精度字段发现与抽取——无需 LLM API 调用、无 Token 成本、确定且快速。

**它能做什么：**
- 以高精度学习商品 / 详情页上所有可抽取字段（通常从几十到上百）。

**为何不只用 LLM？**
- LLM 抽取会带来延迟、成本与 Token 限制。
- 本地 ML 抽取可复现，可扩展至每日 10~20 万页面。
- 也可组合使用：ML 提供结构化基线，LLM 负责语义增强。

**快捷命令（PulsarRPAPro）：**
```bash
# Linux/macOS：下载并演示采集（附诊断输出）
curl -L -o PulsarRPAPro.jar https://github.com/platonai/PulsarRPAPro/releases/download/v4.3.0/PulsarRPAPro.jar
# Windows (PowerShell)：
Invoke-WebRequest -Uri https://github.com/platonai/PulsarRPAPro/releases/download/v4.3.0/PulsarRPAPro.jar -OutFile PulsarRPAPro.jar
```
> 旧版 exotic-standalone*.jar 调用方式已弃用，示例已更新为最新发布包下载。

**集成状态：**
- 现已通过配套项目 [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro) 提供。
- 计划提供 Browser4 原生 API；关注后续版本发布。

**关键优势：**
- 高精度：>95% 字段被发现；多数字段精度 >99%（指示性测试数据）。
- 抗选择器震荡与 HTML 噪声。
- 零外部依赖（无需 API Key），规模化成本更优。
- 可解释：生成的选择器与 SQL 透明可审计。

👽 利用机器学习智能体抽取数据：

![Auto Extraction Result Snapshot](docs/assets/images/amazon.png)

（即将推出：更丰富的仓库内示例与直接 API 挂钩。）

---

## 📦 模块概览

| 模块 | 说明 |
|--------|-------------|
| `pulsar-core` | 核心引擎：会话、调度、DOM、浏览器控制 |
| `pulsar-rest` | Spring Boot REST 层与命令端点 |
| `pulsar-client` | 客户端 SDK / CLI 工具 |
| `browser4-spa` | 面向浏览器智能体的单页应用 |
| `browser4-agents` | 智能体与爬虫编排及产品打包 |
| `pulsar-tests` | 重型集成与场景测试 |
| `pulsar-tests-common` | 共享测试工具与夹具 |

---

## 📜 SDK

Python / Node.js SDK 正在筹备中。

## 📜 文档

* 🛠️ [配置指南](docs/config.md)
* 📚 [源码构建](docs/build.md)
* 🧠 [进阶指南](docs/advanced-guides.md)
* 🤖 [AI 编程产品指导](docs/zh/ai-products-guidance-cn.md) - 支持 Cursor、Windsurf、Cline、Aider、GitHub Copilot

---

## 🔧 代理配置 - 解锁网站访问

<details>

将环境变量 PROXY_ROTATION_URL 设置为代理服务提供的轮换 URL：

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

每次访问该轮换 URL，应返回包含一个或多个新鲜代理 IP 的响应。
如需该类 URL，请联系你的代理服务商。

</details>

---

## ✨ 功能特性

### AI 与智能体
- 面向问题求解的自主浏览器智能体
- 并行智能体会话
- LLM 辅助的页面理解与抽取

### 浏览器自动化与 RPA
- 基于工作流的浏览器动作
- 协程安全的精确控制（滚动、点击、抽取）
- 灵活的事件处理与生命周期管理

### 数据抽取与查询
- 一行命令完成数据抽取
- 面向 DOM/内容的 X-SQL 扩展查询语言
- 结构化 + 非结构化的混合抽取（LLM + 选择器）

### 性能与可扩展性
- 高效并行页面渲染
- 抗封锁设计与智能重试
- 在普通硬件上达到 100,000+ 页/天（指示性）

### 隐匿与可靠性
- 先进的反机器人技术
- IP 与配置文件轮换
- 弹性调度与质量保证

### 开发者体验
- 简单的 API 集成（REST、原生、文本命令）
- 丰富的配置分层
- 清晰的结构化日志 (Structured Logging) 与指标

### 存储与监控
- 本地文件系统与 MongoDB 支持（可扩展）
- 全面日志与透明度
- 细致的指标与生命周期可观测性

---

## 🤝 支持与社区

- 💬 微信：galaxyeye
- 🌐 微博：[galaxyeye](https://weibo.com/galaxyeye)
- 📧 邮箱：galaxyeye@live.cn, ivincent.zhang@gmail.com
- 🐦 Twitter：galaxyeye8
- 🌍 官网：[browser4.io](https://browser4.io)

<div style="display: flex;">
  <img src="docs/images/wechat-author.png" width="300" height="365" alt="微信二维码" />
</div>

---

> 英文文档请参阅 [English README](README.md)。

---

**术语速览（维护一致性）：**
- Browser Agents：浏览器智能体（首次出现保留英文）
- AgenticSession / AgenticContexts：保持英文
- WebDriver：保持英文
- Chrome DevTools Protocol (CDP)：Chrome DevTools 协议 (CDP)
- X-SQL：保持英文
- LLM：大语言模型 (LLM)
- Structured Logging：结构化日志
- Auto Extraction：自动抽取

