基本用法
=

上一章 [目录](1home.md) [下一章](3load-options.md)

用一句话介绍 Browser4，它仅仅**正确实现了两个方法：加载网页，提取数据**。为了实现这个目标，Browser4 增删近百万行代码，沉淀数十万行代码，开发了一系列尖端技术。

**加载网页：**

1. 加载一个网页或者资源
2. 使用浏览器加载并渲染网页，或者使用原始协议加载单一资源
3. 用一台机器加载十万个网页
4. 用十台机器加载一百万个网页
5. 用 N 台机器加载 10 x N 万个网页

**提取数据：**

1. 从网页内容文本中提取数据，从文本资源中提取数据
2. 从文档对象模型（DOM）中提取数据
3. 同网页进行交互后提取数据
4. 将网页截屏后提取数据
5. 从数以百万计的网页中**自动提取**数据

**综合起来：**

1. 使用 SQL 加载网页并提取数据
2. 通过云服务加载网页并提取数据
3. 在极端复杂的应用场景中，正确地加载网页并正确地提取数据
4. 围绕加载和提取的其他辅助性方法

Browser4 实现**网络即数据库**范式，像对待内部数据库一样对待外部网络，如果需要的数据不在本地存储中，或者现存版本不满足分析需要，则系统会从互联网上采集该数据的最新版本。

本课程介绍了加载数据和提取数据的基本 API，这些 API 出现在 PulsarSession 中。PulsarSession 提供了丰富的 API，以覆盖“**加载-解析-提取**”的所有需求。

**这样丰富的 API 使得我们的绝大多数编程场景下，都能够使用一行代码解决“加载-解析-提取”问题：**

1. 普通加载
2. 异步加载
3. 批量加载
4. 批量加载链出页面
5. 各种参数组合

下面是这些 API 的预览，后面我们会详细解释。

1. load()
2. loadDeferred()
3. loadAsync()
4. submit()
5. loadAll()
6. submitAll()
7. loadOutPages()
8. submitForOutPages()
9. loadResource()
10. loadResourceDeferred()
11. loadDocument()
12. scrape()
13. scrapeOutPages()

我们来看看常见的用法是怎样的。首先创建一个 Pulsar 会话，所有重要的工作都在 Pulsar 会话中处理：

```kotlin
val session = PulsarContexts.createSession()
val url = "https://www.amazon.com/dp/B08PP5MSVB"
```

最基本的思想和方法是 load()，它先尝试从本地存储加载网页，如果需要的页面不存在，或已过期，或者不满足其他要求，则从 Internet 获取该页面：

```kotlin
val page = session.load(url)
```

可以用一个简单参数指定网页过期时间，如果需要的页面已在本地存储中且未过期，则返回本地版本：

```kotlin
// Returns the local version
val page2 = session.load(url, "-expires 100d")
```

在**连续采集**任务中，我们会以**异步并行**的方式来处理大批量采集任务，会将大批 URL 提交到 URL 池中，在采集循环中持续处理这些 URL：

```kotlin
// 向 URL 池提交 URL，提交的 URL 将在一个采集循环中处理
session.submit(url, "-expires 10s")
// 向 URL 池提交一批 URL，提交的 URL 将在采集循环中处理
session.submitAll(urls, "-expires 30d")
```

一旦一个网页成功加载或者采集，我们通常会需要将该网页解析成一个 DOM，并开始后续的数据提取工作：

```kotlin
// 将页面内容解析成文档
val document = session.parse(page)
// 使用该文档
val title = document.selectFirstText(".title")
// ...
```

或者“**加载-解析**”两个步骤可以在一个方法中完成：

```kotlin
// 加载并解析
val document = session.loadDocument(url, "-expires 10s")
// 使用该文档
val title = document.selectFirstText(".title")
// ...
```

在后续章节中，我们会详细介绍如何使用**标准** CSS 选择器和**扩展**的 CSS 选择器来提取数据。

在很多场景中，我们会从一个列表页出发，采集链出页面。譬如说，我们需要打开一个商品列表页，然后采集其中的商品详情页。PulsarSession 提供了一组方法来简化这个任务：

```kotlin
// 加载入口页面，然后加载由 `-outLink` 指定的所有链接。
// 选项“-outLink”指定cssSelector来选择入口页面中要加载的链接。
// 选项“-topLinks”指定“-outLink”选择的链接的最大数量。
val pages = session.loadOutPages(url, "-expires 10s -itemExpires 10s -outLink a[href~=/dp/] -topLinks 10")

// 加载门户页面并将`-outLink '指定的外部链接提交到URL池。
// 选项“-outLink”指定cssSelector来选择入口页面中要提交的链接。
// 选项“-topLinks”指定“-outLink”选择的链接的最大数量。
session.submitForOutPages(url, "-expires 1d -itemExpires 7d -outLink a[href~=/dp/] -topLinks 10")
```

网页采集最终是要从网页中提取字段，PulsarSession 也提供了丰富的方法来简化“**采集-解析-提取**”复合任务，有单网页版本的，也有批处理版本的：

```kotlin
// 加载页面、解析页面并提取字段
val fields = session.scrape(url, "-expires 10s", "#centerCol", listOf("#title", "#acrCustomerReviewText"))

// 加载页面、解析页面并提取具名字段
val fields2 = session.scrape(url, "-i 10s", "#centerCol",
    mapOf("title" to "#title", "reviews" to "#acrCustomerReviewText"))

// 加载链出页面、解析链出页面并从链出页面中提取具名字段
val fields3 = session.scrapeOutPages(url, "-i 10s -ii 10s -outLink a[href~=/dp/] -topLink 10", "#centerCol",
    mapOf("title" to "#title", "reviews" to "#acrCustomerReviewText"))
```

在**大规模采集**项目中，我们可以激活**解析子系统**，并在解析子系统中注册
事件处理器，通过事件处理器来执行文档相关任务，譬如提取字段，将字段保存到数据库，收集更多链接等等。

```kotlin
// 添加 `-parse` 选项来激活解析子系统，（激活后可以注册 DOM 文档相关的事件处理器，并进一步处理 DOM 文档）
val page = session.load(url, "-parse")
```

PulsarSession 也提供了一批实用方法，来方便实际的编程工作，譬如异步调用，协程支持，参数组合等。

```kotlin
// Kotlin suspend calls
val page = runBlocking { session.loadDeferred(url, "-expires 10s") }

// Java-style async calls
session.loadAsync(url, "-expires 10s").thenApply(session::parse).thenAccept(session::export)
```

最后小结一下，我们提供了一系列方法家族来**加载**页面：

| 函数名                      | 描述                                                   |
|:-------------------------|:------------------------------------------------------|
| load()                   | **加载**一个页面                                        |
| loadDeferred()           | 异步**加载**一个页面，它可以在 Kotlin 协程中执行            |
| loadAsync()              | 异步**加载**一个页面，它以 Java 异步编程风格执行            |
| submit()                 | 提交一个链接，它会在采集循环中被**加载**                    |
| loadAll()                | **加载**批量页面                                        |
| submitAll()              | 提交批量页面，它们会在采集循环中被**加载**                   |
| loadOutPages()           | **加载**一批链出页面                                     |
| submitForOutPages()      | 提交一批链出链接，它们会在采集循环中被**加载**               |
| loadResource()           | **加载**一个资源，使用简单的 HTTP 协议，而不是通过浏览器渲染  |
| loadResourceDeferred()   | **加载**一个资源，它可以在 Kotlin 协程中执行               |
| loadDocument()           | **加载**一个页面，并且解析成 DOM 文档                      |
| scrape()                 | **加载**一个页面，解析成 DOM 文档，并且从中提取数据          |
| scrapeOutPages()         | **加载**一批链出页面，解析成 DOM 文档，并且从中提取数据       |

这些函数提供了丰富的重载函数，以满足绝大多数复杂编程要求。譬如，当我们准备启动一个新的采集项目时，我们第一步要做的是评估采集难度，这可以用一行代码来启动：

```kotlin
fun main() = PulsarContexts.createSession().scrapeOutPages(
  "https://www.amazon.com/", "-outLink a[href~=/dp/]", listOf("#title", "#acrCustomerReviewText"))
```

本课程完整的代码可以在这里找到：[kotlin](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_0_BasicUsage.kt)，[国内镜像](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_0_BasicUsage.kt)。了解更加详细的使用方法，可以直接阅读源代码：[PulsarSession](/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/session/PulsarSession.kt)，[国内镜像](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/session/PulsarSession.kt)。

下一章我们将详细介绍[加载参数](3load-options.md)，通过配置加载参数，可以精确定义我们的采集任务。

------

上一章 [目录](1home.md) [下一章](3load-options.md)
