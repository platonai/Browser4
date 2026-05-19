# 🤖 Browser4

---

[English](README.md) | 简体中文

<!-- TOC -->
**目录**
- [🤖 Browser4](#-browser4)
	- [🌟 简介](#-简介)
		- [✨ 核心能力](#-核心能力)
	- [🎥 演示视频](#-演示视频)
	- [💡 使用示例](#-使用示例)
		- [工作流自动化](#工作流自动化)
		- [LLM + X-SQL](#llm--x-sql)
		- [高速并行处理](#高速并行处理)
		- [自动抽取](#自动抽取)
	- [✨ 功能特性](#-功能特性)
	- [🤝 支持与社区](#-支持与社区)
	- [📜 文档](#-文档)
	- [🔧 代理配置：解除网站访问限制](#-代理配置解除网站访问限制)
<!-- /TOC -->

## 🌟 简介

💖 **Browser4：一个为 AI 打造的、闪电般快速且协程安全的浏览器引擎** 💖

### ✨ 核心能力

* 🤖 **浏览器自动化** —— 面向工作流、页面导航与数据抽取的高性能自动化能力。
* ⚡ **极致性能** —— 完全协程安全；单机每天可支持 10 万 ~ 20 万次复杂页面访问。
* 🧬 **数据抽取** —— 融合 LLM、机器学习与选择器，在复杂混乱页面中提取干净数据。

## 🎥 演示视频

🎬 YouTube：
[![Watch the video](https://img.youtube.com/vi/rJzXNXH3Gwk/0.jpg)](https://youtu.be/rJzXNXH3Gwk)

📺 Bilibili：
[https://www.bilibili.com/video/BV1fXUzBFE4L](https://www.bilibili.com/video/BV1fXUzBFE4L)

---

## 💡 使用示例

### Maven

```xml
<dependencyManagement>
	<dependencies>
		<dependency>
			<groupId>ai.platon.pulsar</groupId>
			<artifactId>pulsar-bom</artifactId>
			<version>${browser4-base.version}</version>
			<type>pom</type>
			<scope>import</scope>
		</dependency>
	</dependencies>
</dependencyManagement>

<dependencies>
	<dependency>
		<groupId>ai.platon.pulsar</groupId>
		<artifactId>pulsar-skeleton</artifactId>
	</dependency>
	<dependency>
		<groupId>ai.platon.pulsar</groupId>
		<artifactId>pulsar-protocol</artifactId>
	</dependency>
	<dependency>
		<groupId>ai.platon.pulsar</groupId>
		<artifactId>pulsar-ql</artifactId>
	</dependency>
</dependencies>
```

### 工作流自动化

以细粒度控制方式实现底层浏览器自动化与数据抽取。

**特性：**
- 同时支持在线 DOM 访问与离线快照解析
- 直接、完整控制 Chrome DevTools Protocol（CDP），并且协程安全
- 精确的元素交互能力（点击、滚动、输入）
- 使用 CSS 选择器/XPath 进行高速数据抽取

```kotlin
val session = AgenticContexts.getOrCreateSession()
val agent = session.companionAgent
val driver = session.getOrCreateBoundDriver()

// Load the initial page referenced by your input URL
var page = session.open(url)

// Drive the browser with natural-language instructions
agent.act("scroll to the comment section")
// Read the first matching comment node directly from the live DOM
val content = driver.selectFirstTextOrNull("#comments")

// Snapshot the page to an in-memory document for offline parsing
var document = session.parse(page)
// Map CSS selectors to structured fields in one call
var fields = session.extract(document, mapOf("title" to "#title"))

// Let the companion agent execute a multi-step navigation/search flow
val history = agent.run(
	"Go to amazon.com, search for 'smart phone', open the product page with the highest ratings"
)

// Capture the updated browser state back into a PageSnapshot
page = session.capture(driver)
document = session.parse(page)
// Extract additional attributes from the captured snapshot
fields = session.extract(document, mapOf("ratings" to "#ratings"))
```

### LLM + X-SQL

非常适合高复杂度的数据抽取流水线：每个实体包含数十个对象、每个对象包含数百个字段的场景。

**优势：**
- 相比传统方法，可多抽取 10 倍实体、100 倍字段
- 将 LLM 的智能能力与精确的 CSS 选择器/XPath 相结合
- 采用类 SQL 语法，便于熟悉数据查询的开发者上手

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

* [使用 X-SQL 从 Amazon 商品页抓取 100+ 字段](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)
* [用于抓取各种 Amazon 网页的 X-SQL 集合](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)

### 高速并行处理

通过并行浏览器控制与智能资源优化，实现极高吞吐量。

**性能：**
- 单机每天可处理 1 万 ~ 2 万个复杂页面访问
- 支持并发会话管理
- 通过资源拦截提升页面加载速度

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
[![Watch the video](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 Bilibili：
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

### 自动抽取

基于自监督/无监督机器学习，实现自动化、大规模、高精度字段发现与抽取——无需调用 LLM API、无需消耗 token，具备确定性且速度快。

**它能做什么：**
- 自动学习商品页/详情页中所有可抽取字段（通常为几十到上百个），并保持很高精度。
- 当 browser4 在 GitHub 获得 1 万星标后开源。

**为什么不直接只用 LLM？**
- LLM 抽取会带来额外延迟、成本与 token 限制。
- 基于机器学习的自动抽取可本地运行、结果可复现，并可扩展到每天 10 万+ ~ 20 万+ 页面。
- 两者也可以结合：用自动抽取构建结构化基线，再用 LLM 做语义增强。

**快速命令（PulsarRPAPro）：**
```bash
# NOTE: MongoDB required
curl -L -o PulsarRPAPro.jar https://github.com/platonai/PulsarRPAPro/releases/download/v3.0.0/PulsarRPAPro.jar
```

**集成状态：**
- 当前可通过配套项目 [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro) 使用。
- 原生 Browser4 API 正在规划中；请关注后续版本发布。

**核心优势：**
- 高精度：发现字段超过 95%；其中多数在已测试站点上准确率超过 99%（为参考值）。
- 能适应选择器变化与 HTML 噪声。
- 零外部依赖（无需 API Key）→ 更适合大规模低成本运行。
- 可解释：生成的选择器与 SQL 透明、可审计。

👽 使用机器学习智能体抽取数据：

![Auto Extraction Result Snapshot](docs/assets/images/amazon.png)

（即将提供：更丰富的仓库内示例以及直接 API 接口。）

---

## ✨ 功能特性

状态说明：[Available] 仓库中已可用，[Experimental] 正在积极迭代，[Planned] 尚未进入仓库，[Indicative] 为性能目标参考值。

### 浏览器自动化与 RPA
- [Available] 基于工作流的浏览器操作
- [Available] 精确、协程安全的控制能力（滚动、点击、抽取）
- [Available] 灵活的事件处理器与生命周期管理

### 数据抽取与查询
- [Available] 单行数据抽取命令
- [Available] 面向 DOM/内容的 X-SQL 扩展查询语言
- [Experimental] 结构化 + 非结构化混合抽取（LLM + ML + 选择器）

### 性能与可扩展性
- [Available] 高效率并行页面渲染
- [Available] 抗封锁设计与智能重试
- [Indicative] 在普通硬件上每天处理 100,000+ 个复杂页面

### 隐匿性与可靠性
- [Experimental] 高级反机器人技术
- [Available] 通过 `PROXY_ROTATION_URL` 实现代理轮换
- [Available] 稳健的调度与质量保障

### 开发者体验
- [Available] 简单的 API 集成方式（REST、原生接口、文本命令）
- [Available] 丰富的分层配置能力
- [Available] 清晰的结构化日志与指标

### 存储与监控
- [Available] 支持本地文件系统与 MongoDB（可扩展）
- [Available] 全面的日志与透明性

---

## 🤝 支持与社区

欢迎加入我们的社区，获取支持、反馈问题并开展协作！

- **GitHub Discussions**：与开发者和用户交流。
- **Issue Tracker**：报告缺陷或提出功能请求。
- **Social Media**：关注我们，获取最新动态与新闻。

欢迎贡献代码！详情请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 📜 文档

完整文档位于 `docs/` 目录，也可访问我们的 [GitHub Pages 站点](https://platonai.github.io/browser4/)。

---

## 🔧 代理配置：解除网站访问限制

<details>

将环境变量 `PROXY_ROTATION_URL` 设置为你的代理服务商提供的轮换地址：

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

每次访问该轮换地址时，它应返回一个或多个新的代理 IP。
如果你需要此类地址，请联系你的代理服务提供商。

</details>

---

## License

Apache 2.0 License。详见 [LICENSE](LICENSE)。

