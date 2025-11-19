# 🤖 Browser4

[![Docker Pulls](https://img.shields.io/docker/pulls/galaxyeye88/browser4?style=flat-square)](https://hub.docker.com/r/galaxyeye88/browser4)
[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.8-brightgreen?style=flat-square)](https://spring.io/projects/spring-boot)

---

> **⚠️ License Notice: This project uses dual licensing. The main project is licensed under Apache License 2.0. The `browser4` module is licensed under GNU AGPL v3. See LICENSE and browser4/LICENSE for details.**

English | [简体中文](README-CN.md) | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**Table of Contents**
- [🌟 Introduction](#-introduction)
- [🚀 Quick Start](#-quick-start)
  - [Run from JAR](#run-from-jar)
  - [Run with Docker](#run-with-docker)
  - [Build from Source](#build-from-source)
  - [Environment Variables](#environment-variables)
- [Usage Examples](#usage-examples)
  - [Browser Use (Agents)](#browser-use-agents)
  - [Text Commands API](#text-commands-api)
  - [LLM + X-SQL](#llm--x-sql)
  - [Native API](#native-api)
- [Modules Overview](#modules-overview)
- [Proxies](#-proxies---unblock-websites)
- [Features](#features)
- [Documentation](#-documents)
- [Support & Community](#-support--community)
<!-- /TOC -->

## 🌟 Introduction

💖 **Browser4: a lightning-fast, coroutine-safe browser for your AI** 💖

### ✨ Key Capabilities:

- 🤖 **Browser Use** – Let the agent think and resolve problems.
- 🤖 **Browser Automation** – Automate the browser in workflow and extract data.
- ⚡ **Ultra-Fast** – Coroutine-safe browser automation concurrency, spider-level crawling performance.
- 🧠 **Web Understanding** – Deep comprehension of dynamic web content.
- 📊 **Data Extraction APIs** – Powerful tools to extract structured data effortlessly.

---

## 🎥 Demo Videos

🎬 YouTube:
[![Watch the video](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 Bilibili:
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

## 🚀 Quick Start

### Run from JAR

#### Download
```shell
curl -L -o Browser4.jar https://github.com/platonai/browser4/releases/download/v4.1.0/Browser4.jar
```
(Replace `v4.1.0` with the latest release if needed.)

#### Run
```shell
# ensure an LLM api key is set. VOLCENGINE_API_KEY / OPENAI_API_KEY also supported
echo $DEEPSEEK_API_KEY
java -D"DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}" -jar Browser4.jar
```
> Windows PowerShell: `$env:DEEPSEEK_API_KEY` (env) vs `$DEEPSEEK_API_KEY` (script variable).

### Run with Docker
```shell
# ensure LLM api key is set
echo $DEEPSEEK_API_KEY
docker run -d -p 8182:8182 -e DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY} galaxyeye88/browser4:latest
```
> Add other supported keys as `-e OPENAI_API_KEY=...` etc.

### Build from Source
Refer to [Build from Source](docs/development/build.md). Quick commands:

**Using Maven (Primary):**

Windows (CMD):
```shell
mvnw.cmd -q -DskipTests
mvnw.cmd -pl browser4 -am test -D"surefire.failIfNoSpecifiedTests=false"
```
Linux/macOS:
```shell
./mvnw -q -DskipTests
./mvnw -pl browser4 -am test -Dsurefire.failIfNoSpecifiedTests=false
```

**Using Gradle (Alternative):**

Linux/macOS:
```shell
./gradlew build -x test
./gradlew :browser4:test
```
Windows:
```shell
gradlew.bat build -x test
gradlew.bat :browser4:test
```

> 📖 See [GRADLE.md](GRADLE.md) for complete Gradle usage documentation.

Run the app after build:
```shell
java -jar browser4/browser4-crawler/target/Browser4.jar
```
(Default port: 8182)

### Environment Variables
| Name | Purpose |
|------|---------|
| `DEEPSEEK_API_KEY` | Primary LLM key for AI features |
| `OPENAI_API_KEY` | Alternative LLM provider key |
| `VOLCENGINE_API_KEY` | Alternative LLM provider key |
| `PROXY_ROTATION_URL` | Endpoint returning fresh rotating proxy IP(s) |
| `JAVA_OPTS` | (Optional) Extra JVM opts (memory, GC tuning) |

> At least one LLM key must be set or AI features will be disabled.

---

## Usage Examples

### Browser Use (Agents)
```kotlin
val problems = """
    go to amazon.com, search for pens to draw on whiteboards, compare the first 4 ones, write the result to a markdown file.
    打开百度查找厦门岛旅游景点，给出一个总结
    go to https://news.ycombinator.com/news , read top 3 articles and give me a summary
    """.lines().filter { it.isNotBlank() }

problems.forEach { agent.resolve(it) }
```

### Text Commands API
Use free-form text to drive the browser:
```text
Go to https://www.amazon.com/dp/B08PP5MSVB

After browser launch: clear browser cookies.
After page load: scroll to the middle.

Summarize the product.
Extract: product name, price, ratings.
Find all links containing /dp/.
```

### LLM + X-SQL
```shell
curl -X POST "http://localhost:8182/api/x/e" -H "Content-Type: text/plain" -d "
select
  llm_extract(dom, 'product name, price, ratings') as llm_extracted_data,
  dom_base_uri(dom) as url,
  dom_first_text(dom, '#productTitle') as title,
  dom_first_slim_html(dom, 'img:expr(width > 400)') as img
from load_and_select('https://www.amazon.com/dp/B08PP5MSVB', 'body');
"
```

### Native API
High-speed parallel scraping & browser control examples are shown below (see advanced sections for more).

---

## Modules Overview
| Module | Description                                             |
|--------|----------------------------------------------------------------|
| `pulsar-core` | Core engine: sessions, scheduling, DOM, browser control |
| `pulsar-rest` | Spring Boot REST layer & command endpoints              |
| `pulsar-client` | Client SDK / CLI utilities                             |
| `browser4-spa` | Browser4 API for agents with Single Page Application    |
| `browser4-crawler` | Browser4 API for crawler & product packaging        |
| `pulsar-tests` | Heavy integration & scenario tests                      |
| `pulsar-tests-common` | Shared test utilities & fixtures                 |

---

## 📜 Documents

* 📖 [REST API Examples](docs/rest-api-examples.md)
* 🛠️ [LLM Configuration Guide](docs/config/llm/llm-config.md)
* 🛠️ [Configuration Guide](docs/config.md)
* 📚 [Build from Source](docs/development/build.md)
* 🧠 [Expert Guide](docs/advanced-guides.md)

---

## 🔧 Proxies - Unblock Websites

<details>

Set the environment variable PROXY_ROTATION_URL to the URL provided by your proxy service:

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

Each time the rotation URL is accessed, it should return a response containing one or more fresh proxy IPs.
Ask your proxy provider for such a URL.

</details>

---

## Features

### AI & Agents
- Problem-solving autonomous browser agents
- Parallel agent sessions
- LLM-assisted page understanding & extraction

### Browser Automation & RPA
- Workflow-based browser actions
- Precise coroutine-safe control (scroll, click, extract)
- Flexible event handlers & lifecycle management

### Data Extraction & Query
- One-line data extraction commands
- X-SQL extended query language for DOM/content
- Structured + unstructured hybrid extraction (LLM + selectors)

### Performance & Scalability
- High-efficiency parallel page rendering
- Block-resistant design & smart retries
- 100,000+ pages/day on modest hardware (indicative)

### Stealth & Reliability
- Advanced anti-bot techniques
- IP & profile rotation
- Resilient scheduling & quality assurance

### Developer Experience
- Simple API integration (REST, native, text commands)
- Rich configuration layering
- Clear structured logging & metrics

### Storage & Monitoring
- Local FS & MongoDB support (extensible)
- Comprehensive logs & transparency
- Detailed metrics & lifecycle visibility

---

## 🤝 Support & Community

- 💬 WeChat: galaxyeye
- 🌐 Weibo: [galaxyeye](https://weibo.com/galaxyeye)
- 📧 Email: galaxyeye@live.cn, ivincent.zhang@gmail.com
- 🐦 Twitter: galaxyeye8
- 🌍 Website: [platon.ai](https://platon.ai)

<div style="display: flex;">
  <img src="docs/images/wechat-author.png" width="300" height="365" alt="WeChat QR Code" />
</div>

---

> For Chinese documentation see [简体中文 README](README-CN.md).
