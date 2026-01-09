Massive Crawling
=

[Prev](11WebDriver.md) | [Home](1home.md) | [Next](13X-SQL.md)

> "Bad programmers worry about code. Good programmers worry about data structures and their relationships."
> -- Linus Torvalds

Massively extracting Web data is very challenging. **Websites frequently change and are becoming increasingly complex, which means that collected Web data is often inaccurate or incomplete**. [Browser4](https://github.com/platonai/Browser4) has developed a series of cutting-edge technologies to address these issues.

Browser4 has designed a set of data structures to handle the problem of URL pools. This allows Browser4 not only to handle millions of URLs simultaneously but also to accurately define the behavior of these URLs.

When running continuous crawling, URLs are added to [URLPool](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/collect/UrlPool.kt). URLPool contains various carefully designed [URLCache](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/collect/UrlCache.kt) to meet different needs, such as priority tasks, delayed tasks, deadlines, external loading, and so on.

By combining URLCache and LoadOptions, we can meet the most complex collection requirements.

In massive crawling scenarios, hardware resources need to be utilized to the extreme. This requires adjusting default settings, parallelizing more privacy entities, and having each browser open more tabs. These two settings determine how many web pages the system can crawl in parallel and are usually limited by the hardware configuration of the machine, especially the amount of memory. We recommend at least 32 GB of memory for massive crawling scenarios.

```kotlin
BrowserSettings.maxBrowsers(5).maxOpenTabs(15).headless()
```

This article demonstrates how to meet different crawling requirements through different URLCache.

First, create a Browser4 session and prepare a batch of test URLs:

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

The simplest scenario, submit URLs to the default URLCache:

```kotlin
session.submit(url1)
session.submit(url2, "-refresh")
session.submit(url3, "-i 30s")
```

Batch processing version:

```kotlin
session.submitAll(urls)
// feel free to submit millions of urls here
session.submitAll(urls, "-i 7d")
```

Or directly use the add method of URLPool:

```kotlin
urlPool.add(url4)
```

The above methods all support link priority:

```kotlin
url5.priority = Priority13.HIGHER4.value
session.submit(url5)
urlPool.add(url5)
```

Directly accessing URLCache can better control the crawling behavior:

```kotlin
urlPool.highestCache.reentrantQueue.add(url1)
urlPool.higher2Cache.nonReentrantQueue.add(url2)
urlPool.lower2Cache.nReentrantQueue.add(url3)
```

Among them, nonReentrantQueue accepts the same URL only once, nReentrantQueue accepts the same URL up to n times, and reentrantQueue accepts the same URL multiple times. These Queues are designed to achieve local deduplication.

If there is an especially urgent task, it can be added to the real-time cache:

```kotlin
// highest priority
urlPool.realTimeCache.reentrantQueue.add(url4)
```

The crawling loop will process the real-time cache with the highest priority.

If a task needs to be delayed, it can be added to the delay cache:

```kotlin
// will start 2 hours later
urlPool.delayCache.add(DelayUrl(url5, Duration.ofHours(2)))
```

The most important purpose of designing the delay cache is to handle failed tasks. Failed tasks are added to the delay cache to wait for a retry later, thus avoiding the internal and external environment at the time of failure.

By default, URLCache is based on memory, but we can also reconfigure it to use cache implementations that support file systems or databases.

This course provides [complete code](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_9_MassiveCrawler.kt), [Chinese mirror](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_9_MassiveCrawler.kt).

------

[Prev](11WebDriver.md) | [Home](1home.md) | [Next](13X-SQL.md)
