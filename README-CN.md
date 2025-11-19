# 🤖 Browser4

[![Docker Pulls](https://img.shields.io/docker/pulls/galaxyeye88/browser4?style=flat-square)](https://hub.docker.com/r/galaxyeye88/browser4)
[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.8-brightgreen?style=flat-square)](https://spring.io/projects/spring-boot)

---

> **⚠️ 授权声明：本项目采用双重授权模式。主项目遵循 Apache License 2.0，`browser4` 模块采用 GNU AGPL v3。详情见 LICENSE 及 browser4/LICENSE。**

[English](README.md) | 简体中文 | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**目录**
- [🌟 项目介绍](#-项目介绍)
- [🚀 快速开始](#-快速开始)
  - [运行 JAR 文件](#运行-jar-文件)
  - [使用 Docker 运行](#使用-docker-运行)
  - [从源码构建](#从源码构建)
  - [环境变量](#环境变量)
- [使用示例](#使用示例)
  - [浏览器智能体](#浏览器智能体)
  - [文本命令 API](#文本命令-api)
  - [LLM + X-SQL](#llm--x-sql)
  - [原生 API](#原生-api)
- [模块概览](#模块概览)
- [文档](#-文档)
- [代理配置](#-代理配置---解锁网站访问)
- [功能特性](#功能特性)
- [支持与社区](#-支持与社区)
<!-- /TOC -->

## 🌟 项目介绍

💖 **Browser4: 为 AI 而生的超快协程安全浏览器** 💖

### ✨ 核心能力:

- 🤖 **浏览器智能体** – 让智能体思考并解决问题。
- 🤖 **浏览器自动化** – 在工作流中自动化浏览器并提取数据。
- ⚡ **超快速** – 协程安全的浏览器自动化并发，爬虫级别的抓取性能。
- 🧠 **网页理解** – 深度理解动态网页内容。
- 📊 **数据提取 API** – 强大的结构化数据轻松提取工具。

---

## 🎥 演示视频

🎬 YouTube:
[![观看视频](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 哔哩哔哩:
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

## 🚀 快速开始

### 运行 JAR 文件

#### 下载
```shell
curl -L -o Browser4.jar https://github.com/platonai/browser4/releases/download/v4.1.0/Browser4.jar
```
（如需要，请将 `v4.1.0` 替换为最新版本。）

#### 运行
```shell
# 确保设置了 LLM API 密钥。也支持 VOLCENGINE_API_KEY / OPENAI_API_KEY
echo $DEEPSEEK_API_KEY
java -D"DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY}" -jar Browser4.jar
```
> Windows PowerShell: `$env:DEEPSEEK_API_KEY`（环境变量）vs `$DEEPSEEK_API_KEY`（脚本变量）。

### 使用 Docker 运行
```shell
# 确保设置了 LLM API 密钥
echo $DEEPSEEK_API_KEY
docker run -d -p 8182:8182 -e DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY} galaxyeye88/browser4:latest
```
> 添加其他支持的密钥，如 `-e OPENAI_API_KEY=...` 等。

### 从源码构建
参考 [从源码构建](docs/development/build.md)。快速命令：

**使用 Maven（主要方式）：**

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

**使用 Gradle（备选方式）：**

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

> 📖 查看 [GRADLE.md](GRADLE.md) 了解完整的 Gradle 使用文档。

构建后运行应用：
```shell
java -jar browser4/browser4-crawler/target/Browser4.jar
```
（默认端口：8182）

### 环境变量
| 变量名 | 用途 |
|------|---------|
| `DEEPSEEK_API_KEY` | AI 功能的主要 LLM 密钥 |
| `OPENAI_API_KEY` | 备选 LLM 提供商密钥 |
| `VOLCENGINE_API_KEY` | 备选 LLM 提供商密钥 |
| `PROXY_ROTATION_URL` | 返回新鲜轮换代理 IP 的端点 |
| `JAVA_OPTS` | （可选）额外的 JVM 选项（内存、GC 调优） |

> 必须至少设置一个 LLM 密钥，否则 AI 功能将被禁用。

---

## 使用示例

### 浏览器智能体
```kotlin
val problems = """
    go to amazon.com, search for pens to draw on whiteboards, compare the first 4 ones, write the result to a markdown file.
    打开百度查找厦门岛旅游景点，给出一个总结
    go to https://news.ycombinator.com/news , read top 3 articles and give me a summary
    """.lines().filter { it.isNotBlank() }

problems.forEach { agent.resolve(it) }
```

### 文本命令 API
使用自由文本驱动浏览器：
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

### 原生 API
高速并行抓取和浏览器控制示例如下（更多请参见高级部分）。

### Native API
High-speed parallel scraping & browser control examples are shown below (see advanced sections for more).

---

## 模块概览
| 模块 | 说明 |
|--------|-------------|
| `pulsar-core` | 核心引擎：会话、调度、DOM、浏览器控制 |
| `pulsar-rest` | Spring Boot REST 层和命令端点 |
| `pulsar-client` | 客户端 SDK / CLI 工具 |
| `browser4-spa` | 带单页应用的智能体 API |
| `browser4-crawler` | 用于爬虫和产品打包的 Browser4 API |
| `pulsar-tests` | 重型集成和场景测试 |
| `pulsar-tests-common` | 共享测试工具和装置 |

---

## 📜 文档

* 📖 [REST API 示例](docs/rest-api-examples.md)
* 🛠️ [LLM 配置指南](docs/config/llm/llm-config.md)
* 🛠️ [配置指南](docs/config.md)
* 📚 [从源码构建](docs/development/build.md)
* 🧠 [专家指南](docs/advanced-guides.md)

---

## 🔧 代理配置 - 解锁网站访问

<details>

将环境变量 PROXY_ROTATION_URL 设置为代理服务提供的 URL：

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

每次访问轮换 URL 时，它应返回包含一个或多个新代理 IP 的响应。
请向您的代理提供商询问此类 URL。

</details>

---

## 功能特性

### AI 与智能体
- 解决问题的自主浏览器智能体
- 并行智能体会话
- LLM 辅助的页面理解和提取

### 浏览器自动化与 RPA
- 基于工作流的浏览器操作
- 精准的协程安全控制（滚动、点击、提取）
- 灵活的事件处理器和生命周期管理

### 数据提取与查询
- 单行数据提取命令
- 用于 DOM/内容的 X-SQL 扩展查询语言
- 结构化+非结构化混合提取（LLM + 选择器）

### 性能与可扩展性
- 高效并行页面渲染
- 抗封锁设计和智能重试
- 在普通硬件上每天处理 100,000+ 页面（指示性）

### 隐身与可靠性
- 高级反机器人技术
- IP 和配置文件轮换
- 弹性调度和质量保证

### 开发者体验
- 简单的 API 集成（REST、原生、文本命令）
- 丰富的配置分层
- 清晰的结构化日志和指标

### 存储与监控
- 本地文件系统和 MongoDB 支持（可扩展）
- 全面的日志和透明度
- 详细的指标和生命周期可见性

---

## 🤝 支持与社区

- 💬 微信：galaxyeye
- 🌐 微博：[galaxyeye](https://weibo.com/galaxyeye)
- 📧 邮箱：galaxyeye@live.cn, ivincent.zhang@gmail.com
- 🐦 Twitter：galaxyeye8
- 🌍 网站：[platon.ai](https://platon.ai)

<div style="display: flex;">
  <img src="docs/images/wechat-author.png" width="300" height="365" alt="微信二维码" />
</div>

---

> 英文文档请参阅 [English README](README.md)。

