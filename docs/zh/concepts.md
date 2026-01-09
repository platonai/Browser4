# Browser4 概念

要释放 Browser4 的全部潜能并应对最复杂的数据抓取任务，对其核心概念的扎实理解至关重要。通过掌握这些基本原理，您将能够使用 Browser4 作为从网络中提取有价值信息的强大工具。

## 核心概念

### 网页抓取

网页抓取（Web scraping）、网页收割（Web harvesting）或网页数据提取（Web data extraction）是指从网页中提取数据的方法和技术。

### 网络即数据库

互联网，尤其是万维网，是世界上最大的数据库。但从网络上提取数据从来都不是一件容易的事。Browser4 像对待内部数据库一样对待外部网络，如果需要的数据不在本地存储中，或者现存版本不满足分析需要，则系统会从互联网上采集该数据的最新版本。

我们也可以使用简单的 SQL 将 Web 直接转换为表格和图表，更进一步，我们可以使用 SQL 直接查询 Web。

### 自动提取

Web 数据抽取在过去三十年中已经从使用统计方法发展到更高级的机器学习方法。由于对上线时间、开发者生产率和成本的考虑，机器学习技术现在更受欢迎。

我们提供了一个预览项目，展示如何使用我们领先的机器学习算法来自动提取网页中的几乎每个字段。

您也可以将该预览项目用作一个智能 CSS 路径生成器，生成的 CSS 选择器可以在传统爬虫系统中使用，从网页中提取数据。

### 浏览器渲染

尽管 Browser4 支持各种网页抓取方法，但浏览器渲染是 Browser4 抓取网页的首要方式。

浏览器渲染意味着每个网页都由真正的浏览器打开，以确保网页上的所有字段都正确显示。

### Pulsar Session

`PulsarSession` 定义了一个简洁的接口，用于从本地存储或互联网获取网页，以及解析、提取、保存、索引和导出网页的方法。

主要方法:

- `.load()`: 从本地存储加载网页，或从互联网获取网页
- `.parse()`: 将网页解析成文档
- `.scrape()`: 加载网页，将其解析为文档，然后从文档中提取字段
- `.submit()`: 向 URL 池提交一个 URL，该 URL 将会在主循环中被处理

还有批量版本：

- `.loadOutPages()`: 加载入口页面和链出页面
- `.scrapeOutPages()`: 加载入口页面和链出页面，从链出页面中提取字段

首先要学习的是如何加载一个页面。加载方法如 `session.load()` 首先检查本地存储，如果所需页面存在并且符合要求，则返回本地版本，否则将从互联网获取它。

可以通过 `加载参数` 或 `加载选项` 来指定何时从互联网获取一个网页：

- 失效期限
- 强制更新
- 网页尺寸
- 必需字段
- 其他条件

```kotlin
val session = PulsarContexts.createSession()
val url = "..."
val page = session.load(url, "-expires 1d")
val document = session.parse(page)
val document2 = session.loadDocument(url, "-expires 1d")
val pages = session.loadOutPages(url, "-outLink a[href~=item] -expires 1d -itemExpires 7d -itemRequireSize 300000")
val fields = session.scrape(url, "-expires 1d", "li[data-sku]", listOf(".p-name em", ".p-price"))
// ...
```

一旦网页从本地存储器加载，或从互联网获取，我们将进入下一个处理过程:

- 将 Web 内容解析成 HTML 文档
- 从 HTML 文档中提取字段
- 将提取的字段写入目的地，例如普通文件，Avro 文件，CSV，Excel，MongoDB，MySQL 等。
- Solr、Elasticsearch 等。

有许多方法可以从互联网上获取页面内容:

- 通过 HTTP 协议
- 通过真实的浏览器

由于网页变得越来越复杂，通过真实浏览器获取网页是当今的主要方式。

当我们使用真实浏览器获取网页时，我们可能需要与页面进行交互，以确保正确完整地加载所需的字段。激活 `PageEventHandlers` 并使用 `WebDriver` 来实现此目的。

```kotlin
val options = session.options(args)
options.eventHandlers.browseEventHandlers.onDocumentFullyLoaded.addLast { page, driver ->
    driver.scrollDown()
}
session.load(url, options)
```

`WebDriver` 为 RPA 提供了一套完整的方法集，就像 Selenium, Playwright 和 Puppeteer，但所有的动作和行为都经过优化，以尽可能模仿真人。

### Pulsar Context

`PulsarContext` 由一组高度可定制的组件组成，提供了系统最核心的一组接口，并用来生成 `PulsarSession`。

`PulsarContext` 是所有 Pulsar Context 的接口类。

- `StaticPulsarContext` 由默认组件组成。
- `ClassPathXmlPulsarContext` 由使用 Spring bean 配置文件定制的组件组成。
- `SQLContext` 包含一组组件，用来实现 X-SQL。
- 程序员也可以编写自己的 Pulsar Context 来扩展系统。

## Web Driver

`WebDriver` 定义了一个简洁的界面来访问网页并与之交互，所有的动作和行为都经过优化，尽可能地模仿真人，比如滚动、点击、键入文本、拖放等。

该接口中的方法分为三类:

- 对浏览器本身的控制
- 选择元素，提取文本内容和属性
- 与网页互动

主要方法:

- `.navigateTo()`: 加载新网页。
- `.scrollDown()`: 在网页上向下滚动以完全加载页面。大多数现代网页支持使用 ajax 技术的延迟加载，即网页内容只有在滚动到视图中时才开始加载。
- `.pageSource()`: 获得网页源代码。

## URLs

统一资源定位符（URL），俗称网址，是对网络资源的引用，指定其在计算机网络上的位置和检索它的机制。

Browser4 中的 URL 是一个普通的 URL，带有描述任务的额外信息。Browser4 中的每个任务都被定义为某种形式的 URL。

Browser4 中有几种基本的 URL 形式:

- `NormURL`
- 字符串格式的 URL
- `UrlAware`
- `DegenerateUrl`

`NormURL` 代表“规范化的 URL”，这意味着该 URL 是 fetch 组件的最终形式，并且通常最终被传递给真正的浏览器。

如果未指定，字符串格式的 URL 实际上意味着“configured url”或“url with parameters”，例如:

```kotlin
val url = "https://www.amazon.com/dp/B10000  -taskName amazon -expires 1d -ignoreFailure"
session.load(url)
```

上面的代码与下面的代码意义相同:

```kotlin
val url = "https://www.amazon.com/dp/B10000"
val args = "-taskName amazon -expires 1d -ignoreFailure"
session.load(url, args)
```

`UrlAware` 提供了更复杂的控制来完成采集任务。`UrlAware` 是所有 Hyperlink 的接口，更多详情请参见 Hyperlinks 章节。

最后，`DegenerateUrl` 事实上不是链接，它被设计为非采集任务的接口，以便在 Crawl Loop 中执行。

## 超链接

超链接，或简称为链接，特指 Web 上对数据的引用，通常包含一个 URL，一个文本和一组属性，用户可以通过单击或点击来跟随它。

Browser4 中的 Hyperlink 如同普通超链接，但带有描述任务的额外信息。

Browser4 预定义了几个超链接:

- `ParsableHyperlink` 是在连续爬虫作业中执行获取-解析任务的一种便捷抽象:

```kotlin
val parseHandler = { _: WebPage, document: FeaturedDocument ->
  // do something wonderful with the document
}

val urls = LinkExtractors.fromResource("seeds.txt").map { ParsableHyperlink(it, parseHandler) }
PulsarContexts.create().submitAll(urls).await()
```

- `CompletableHyperlink` 帮助我们进行 java 风格的异步计算: 提交一个超链接并等待任务完成。
- `ListenableHyperlink` 帮助我们附加事件处理程序：

```kotlin
val session = PulsarContexts.createSession()
val link = ListenableHyperlink(portalUrl, args = "-refresh -parse", event = PrintFlowEventHandlers())
session.submit(link)
```

示例代码: [kotlin](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_6_EventHandler.kt).

- `CompletableListenableHyperlink` 帮助我们做到这两点:

```kotlin
fun executeQuery(request: ScrapeRequest): ScrapeResponse {
  // the hyperlink is a CompletableListenableHyperlink
  val hyperlink = createScrapeHyperlink(request)
  session.submit(hyperlink)
  // wait for the task to complete or timeout
  return hyperlink.get(3, TimeUnit.MINUTES)
}
```

示例代码: [kotlin](/pulsar-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/ScrapeService.kt).

## Load Options

Pulsar Session 中的几乎每个方法都接受一个名为 `load arguments` 或 `load options` 的参数，以控制如何加载、获取和提取网页。

有三种形式来组合 URL 及其参数：

1. URL-arguments 形式
2. URL-options 形式
3. configured-URL 形式

```kotlin
// use URL-arguments form:
val page = session.load(url, "-expires 1d")
val page2 = session.load(url, "-refresh")
val document = session.loadDocument(url, "-expires 1d -ignoreFailure")
val pages = session.loadOutPages(url, "-outLink a[href~=item] -itemExpires 7d")
session.submit(Hyperlink(url, args = "-expires 1d"))

// Or use configured-URL form:
val page = session.load("$url -expires 1d")
val page2 = session.load("$url -refresh")
val document = session.loadDocument("$url -expires 1d -ignoreFailure")
val pages = session.loadOutPages("$url -expires 1d -ignoreFailure", "-outLink a[href~=item] -itemExpires 7d")
session.submit(Hyperlink("$url -expires 1d"))

// Or use URL-options form:
var options = session.options("-expires 1d -ignoreFailure")
val document = session.loadDocument(url, options)
options = session.options("-outLink a[href~=item] -itemExpires 7d")
val pages = session.loadOutPages("$url -expires 1d -ignoreFailure", options)

// ...
```

其中，configured-URL 形式可以与其他两种形式混合使用，并且具有更高的优先级。

最重要的加载选项有：

- `-expires`     // 页面的过期时间
- `-itemExpires` // 批量抓取方法中商品页面的过期时间
- `-outLink`     // 要抓取的外部链接的选择器
- `-refresh`     // 强制（重新）获取页面，就像在真实浏览器中点击刷新按钮一样
- `-parse`       // 激活解析子系统，激活后可以注册 DOM 文档相关的事件处理器，并进一步处理 DOM 文档
- `-resource`    // 作为资源获取 URL，不进行浏览器渲染

加载参数都被解析为一个 `LoadOptions` 对象，查看代码了解所有支持的选项。

值得留意的是，当我们执行 `load()` 系列方法时，系统不会解析网页，而是提供了 `parse()` 方法来解析网页。但是，一旦我们加入了 `-parse` 参数，
系统就会激活解析子系统并自动解析网页。我们可以注册处理程序，执行数据提取、提取结果持久化、收集更多链接等任务。

有两种方法可以在解析子系统中注册处理程序：用 `ParseFilters` 注册一个全局范围的 `ParseFilter`，或者用 `PageEventHandlers` 注册一个页面范围的事件处理程序。

使用 `ParseFilter` 来执行复杂任务的一个很好的案例是电商全站数据采集，针对每种类型的页面注册了不同的 `ParseFilter`，来处理数据提取、提取结果持久化、链接收集等任务。

## 浏览器设置

`BrowserSettings` 定义了一个方便的接口来指定浏览器自动化的行为，例如：

- 有头还是无头？
- 是否运行单网页应用程序（SPA）？
- 是否启用代理 IP？
- 是否屏蔽媒体资源？

## 事件处理器

这里的事件处理程序是网页事件处理程序，它在网页的整个生命周期中捕获和处理事件。

查看 [EventHandlerUsage](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_6_EventHandler.kt) 了解所有支持的事件，以及它们被执行的顺序。

## X-SQL

Browser4 支持网络即数据库范式，我们开发了 X-SQL 来直接查询网页，并将网页转换成表格和图表。

点击 [X-SQL](/docs/zh/x-sql.md) 查看关于 X-SQL 的详细介绍和函数说明。

## 实现概念

开发人员不需要研究实现概念，但是了解这些概念有助于我们更好地理解整个系统是如何工作的。

### 链接池

运行连续爬网时，URL 会添加到 `UrlPool` 中。`UrlPool` 包含各种 `UrlCache` 来满足不同的需求，例如，优先级、延迟、截止日期、外部加载等等。

### Crawl Loop

当运行连续爬网时，系统会启动一个主循环来不断从 `UrlPool` 获取 URL，然后在 `PulsarSession` 中异步加载或获取它们。

请记住，Browser4 中的每个任务都是一个 URL，因此主循环可以接受和执行任何类型的任务。

### 隐私上下文

网页采集任务中最大的困难之一是机器人隐身。对于采集任务，网站不应该知道访问是来自人类还是机器人。一旦网页访问被网站怀疑，我们称之为隐私泄露，隐私上下文必须被丢弃，Browser4 将在另一个隐私上下文中重新访问该页面。

### IP 代理管理

从代理供应商处获取 IP，记录代理状态，智能和自动地轮换 IP，等等。

### Web Driver 隐身

当浏览器被编程访问网页时，网站可能会检测到该访问是自动进行的，Web Driver 隐身技术用于防止检测。

### 后端存储

Browser4 支持各种后端存储解决方案，以满足我们客户的迫切需要：本地文件系统、MongoDB、HBase、Gora 等。
