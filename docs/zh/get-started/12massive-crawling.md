大规模采集
=

> 烂程序员关心的是代码。好程序员关心的是数据结构和它们之间的关系。
> -- Linus Torvalds

大规模提取 Web 数据非常困难。**网站经常变化并且变得越来越复杂，这意味着收集的网络数据通常不准确或不完整**，[Browser4](https://github.com/platonai/Browser4) 开发了一系列尖端技术来解决这些问题。

Browser4 设计了一组数据结构来处理 URL 池的问题。这使得 Browser4 不仅能同时处理数以百万计的 URL，还能够准确定义这些 URL 的行为。

运行连续采集时，URL 会添加到 [URLPool](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/collect/UrlPool.kt) 中。URLPool 包含各种精心设计的 [URLCache](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/collect/UrlCache.kt) 来满足不同的需求，例如，优先任务、延时任务、截止日期、外部加载等等。

通过组合 URLCache 和 LoadOptions，我们可以满足最复杂的采集需求。

在大规模采集场景下，硬件资源需要被极致利用。为此需要调整默认设置，并行启动更多的隐私实体，每个浏览器需要打开更多标签页，这两个设置决定了系统能够并行采集多少网页，它通常受限于机器的硬件配置，尤其是内存大小，我们建议在大规模采集场景下，内存至少要 32 G。

```kotlin
BrowserSettings.maxBrowsers(5).maxOpenTabs(15).headless()
```

本文演示如何通过不同的 URLCache 来满足不同的采集要求。

首先创建 Pulsar 会话并准备一批测试用的 URL：

```kotlin
val session = PulsarContexts.createSession()
val urlPool = session.context.crawlPool

val parseHandler = { _: WebPage, document: FeaturedDocument ->
    // do something wonderful with the document
    println(document.title + "\t|\t" + document.baseUri)
}
val urls = LinkExtractors.fromResource("seeds100.txt").map { ParsableHyperlink(it, parseHandler) }
val (url1, url2, url3, url4, url5) = urls.shuffled()
```

最简单的情形，将 URL 提交到默认的 URLCache：

```kotlin
session.submit(url1)
session.submit(url2, "-refresh")
session.submit(url3, "-i 30s")
```

批处理版本：

```kotlin
session.submitAll(urls)
// feel free to submit millions of urls here
session.submitAll(urls, "-i 7d")
```

或者直接使用 URLPool 的 add 方法：

```kotlin
urlPool.add(url4)
```

上述方法均支持链接优先级：

```kotlin
url5.priority = Priority13.HIGHER4.value
session.submit(url5)
urlPool.add(url5)
```

直接访问 URLCache 可以更好地控制采集行为：

```kotlin
urlPool.highestCache.reentrantQueue.add(url1)
urlPool.higher2Cache.nonReentrantQueue.add(url2)
urlPool.lower2Cache.nReentrantQueue.add(url3)
```

其中，nonReentrantQueue 只接受同一个 URL 一次，nReentrantQueue 最多接受同一个 URL n次，reentrantQueue 多次接受同一个 URL。这些 Queue 被设计来达到本地去重的目的。

如果有特别紧急的任务，可以加入到实时缓存中：

```kotlin
// highest priority
urlPool.realTimeCache.reentrantQueue.add(url4)
```

采集循环将以最高优先级处理实时缓存。

如果一个任务需要延时处理，可以加入到延时缓存中：

```kotlin
// will start 2 hours later
urlPool.delayCache.add(DelayUrl(url5, Duration.ofHours(2)))
```

设计延时缓存最重要的目的是处理失败任务。失败任务会加入延时缓存中等待稍后重试，从而避开失败时刻的内外部环境。

默认配置中，URLCache 都是基于内存的，我们也可以重新配置，使用支持文件系统或者数据库的缓存实现。

本课程提供了[完整代码](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_9_MassiveCrawler.kt)，[国内镜像](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_9_MassiveCrawler.kt)。

------

[上一章](11WebDriver.md) [目录](1home.md) [下一章](13X-SQL.md)
