# 🤖 Browser4

---

English | [简体中文](README.zh.md)

<!-- TOC -->
**Table of Contents**
- [🤖 Browser4](#-browser4)
    - [🌟 Introduction](#-introduction)
        - [✨ Key Capabilities](#-key-capabilities)
    - [🎥 Demo Videos](#-demo-videos)
    - [💡 Usage Examples](#-usage-examples)
        - [Workflow Automation](#workflow-automation)
        - [LLM + X-SQL](#llm--x-sql)
        - [High-Speed Parallel Processing](#high-speed-parallel-processing)
        - [Auto Extraction](#auto-extraction)
    - [📦 Modules Overview](#-modules-overview)
    - [📜 Documentation](#-documentation)
    - [🔧 Proxies - Unblock Websites](#-proxies---unblock-websites)
    - [✨ Features](#-features)
    - [🤝 Support & Community](#-support--community)
<!-- /TOC -->

## 🌟 Introduction

💖 **Browser4: a lightning-fast, coroutine-safe browser engine for your AI** 💖

### ✨ Key Capabilities

* 🤖 **Browser Automation** — High-performance automation for workflows, navigation, and data extraction.
* ⚡  **Extreme Performance** — Fully coroutine-safe; supports 100k ~ 200k complex page visits per machine per day.
* 🧬 **Data Extraction** — Hybrid of LLM, ML, and selectors for clean data across chaotic pages.

## 🎥 Demo Videos

🎬 YouTube:
[![Watch the video](https://img.youtube.com/vi/rJzXNXH3Gwk/0.jpg)](https://youtu.be/rJzXNXH3Gwk)

📺 Bilibili:
[https://www.bilibili.com/video/BV1fXUzBFE4L](https://www.bilibili.com/video/BV1fXUzBFE4L)

---

## 💡 Usage Examples

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

### Workflow Automation

Low-level browser automation & data extraction with fine-grained control.

**Features:**
- Both live DOM access and offline snapshot parsing
- Direct and full Chrome DevTools Protocol (CDP) control, coroutine safe
- Precise element interactions (click, scroll, input)
- Fast data extraction using CSS selectors/XPath

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

Ideal for high-complexity data-extraction pipelines with multiple-dozen entities and several hundred fields per entity.

**Benefits:**
- Extract 10x more entities and 100x more fields compared to traditional methods
- Combine LLM intelligence with precise CSS selectors/XPath
- SQL-like syntax for familiar data queries

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

Example code:

* [X-SQL to scrape 100+ fields from an Amazon's product page](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)
* [X-SQLs to crawl all types of Amazon webpages](https://github.com/platonai/exotic-amazon/tree/main/src/main/resources/sites/amazon/crawl/parse/sql/crawl)

### High-Speed Parallel Processing

Achieve extreme throughput with parallel browser control and smart resource optimization.

**Performance:**
- 10k ~ 20k complex page visits per machine per day
- Concurrent session management
- Resource blocking for faster page loads

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

### Auto Extraction

Automatic, large-scale, high-precision field discovery and extraction powered by self-/unsupervised machine learning — no LLM API calls, no tokens, deterministic and fast.

**What it does:**
- Learns every extractable field on item/detail pages (often dozens to hundreds) with high precision.
- Open source when browser4 has 10K stars on GitHub.

**Why not just LLMs?**
- LLM extraction adds latency, cost, and token limits.
- ML-based auto extraction is local, reproducible, and scalable to 100k+ ~ 200k pages/day.
- You can still combine both: use Auto Extraction for structured baseline + LLM for semantic enrichment.

**Quick Commands (PulsarRPAPro):**
```bash
# NOTE: MongoDB required
curl -L -o PulsarRPAPro.jar https://github.com/platonai/PulsarRPAPro/releases/download/v3.0.0/PulsarRPAPro.jar
```

**Integration Status:**
- Available today via the companion project [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro).
- Native Browser4 API exposure is planned; follow releases for updates.

**Key Advantages:**
- High precision: >95% fields discovered; majority with >99% accuracy (indicative on tested domains).
- Resilient to selector churn & HTML noise.
- Zero external dependency (no API key) → cost-efficient at scale.
- Explainable: generated selectors & SQL are transparent and auditable.

👽 Extract data with machine learning agents:

![Auto Extraction Result Snapshot](docs/assets/images/amazon.png)

(Coming soon: richer in-repo examples and direct API hooks.)

---

---

## ✨ Features

Status: [Available] in repo, [Experimental] in active iteration, [Planned] not in repo, [Indicative] performance target.

### Browser Automation & RPA
- [Available] Workflow-based browser actions
- [Available] Precise coroutine-safe control (scroll, click, extract)
- [Available] Flexible event handlers & lifecycle management

### Data Extraction & Query
- [Available] One-line data extraction commands
- [Available] X-SQL extended query language for DOM/content
- [Experimental] Structured + unstructured hybrid extraction (LLM & ML & selectors)

### Performance & Scalability
- [Available] High-efficiency parallel page rendering
- [Available] Block-resistant design & smart retries
- [Indicative] 100,000+ complex pages/day on modest hardware

### Stealth & Reliability
- [Experimental] Advanced anti-bot techniques
- [Available] Proxy rotation via `PROXY_ROTATION_URL`
- [Available] Resilient scheduling & quality assurance

### Developer Experience
- [Available] Simple API integration (REST, native, text commands)
- [Available] Rich configuration layering
- [Available] Clear structured logging & metrics

### Storage & Monitoring
- [Available] Local FS & MongoDB support (extensible)
- [Available] Comprehensive logs & transparency

---

## 🤝 Support & Community

Join our community for support, feedback, and collaboration!

- **GitHub Discussions**: Engage with developers and users.
- **Issue Tracker**: Report bugs or request features.
- **Social Media**: Follow us for updates and news.

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

---

## 📜 Documentation

Comprehensive documentation is available in the `docs/` directory and on our [GitHub Pages site](https://platonai.github.io/browser4/).

---

## 🔧 Proxy Configuration - Unblock Website Access

<details>

Set the environment variable `PROXY_ROTATION_URL` to the rotation URL provided by your proxy service provider:

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

Each time you access this rotation URL, it should return a response containing one or more fresh proxy IPs.
If you need this type of URL, please contact your proxy service provider.

</details>

---

## License

Apache 2.0 License. See [LICENSE](LICENSE) for details.


