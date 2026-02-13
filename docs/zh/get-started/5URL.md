URL
=

在 Browser4 中，每个任务都被定义为某种形式的 URL，这些 URL 往往和一个加载参数一起出现，来精细控制一个采集任务，譬如数据过期，数据基本要求，任务截止日期，任务重试等。绝大多数情况下，一个数据采集任务可以用 **url arguments** 的形式来唯一确定，因此它可以很轻松地被拷贝、管理、存储、传输、并行化，以及沟通交流。

## URLs

统一资源定位符(URL)，俗称网址，是对网络资源的引用，指定其在计算机网络上的位置和检索它的机制。Browser4 中的 URL 带有描述数据采集任务的额外信息，有几种基本形式：

- A [NormURL](/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/common/urls/NormURL.kt)
- A String
- A [UrlAware](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/urls/Hyperlinks.kt)
- A [DegenerateUrl](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/urls/Hyperlinks.kt)

NormURL 代表 “规范化的 URL”，这意味着该 url 是 fetch 组件的最终形式，并且通常最终被传递给真正的浏览器。

如果未指定，字符串格式的 url 实际上意味着  configured url ，url with parameters或 url with arguments，例如：

```kotlin
val url = "https://www.amazon.com/dp/B10000 -taskName amazon -expires 1d -ignoreFailure"
session.load(url)
```

上面的代码与下面的代码意义相同：

```kotlin
val url = "https://www.amazon.com/dp/B10000"
val args = "-taskName amazon -expires 1d -ignoreFailure"
session.load(url, args)
```

UrlAware 提供了更复杂的控制来描述采集任务，是所有 Hyperlink 的接口。

最后，[DegenerateUrl](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/urls/Hyperlinks.kt) 字面意思是“退化链接”，事实上不是链接，它被设计来描述非采集任务，譬如不涉及网络的本地计算任务，以便在主循环中执行。

## Hyperlinks

[超链接](https://en.wikipedia.org/wiki/Hyperlink)，或简称为链接，特指 Web 上对数据的引用，通常包含一个 URL，一个文本和一组属性，用户可以通过单击或点击来跟随它。[Browser4 中的 Hyperlink](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/urls/Hyperlinks.kt) 如同普通超链接，但带有描述任务的额外信息。

Browser4 预定义了几个超链接：

**ParsableHyperlink** 是在连续采集作业中执行 **获取-解析** 任务的一种便捷抽象：

```kotlin
val parseHandler = { _: WebPage, document: FeaturedDocument ->
    // do something wonderful with the document
}

val urls = LinkExtractors.fromResource("seeds.txt").map { ParsableHyperlink(it, parseHandler) }
PulsarContexts.create().submitAll(urls).await()
```

**CompletableHyperlink** 帮助我们进行 Java 风格的异步计算：提交一个超链接并等待任务完成。

**ListenableHyperlink** 帮助我们附加事件处理程序：

```kotlin
val session = PulsarContexts.createSession()
val link = ListenableHyperlink(portalUrl, args = "-refresh -parse", event = PrintFlowEvent())
session.load(link)
```

**CompletableListenableHyperlink** 帮助我们同时做到这两点：

```kotlin
// Load a CompletableListenableHyperlink, so we can register various event handlers,
// and we can wait for the execution to complete.
val completableListenableHyperlink = CompletableListenableHyperlink<WebPage>(url).apply {
    event.loadEvent.onLoaded.addLast { complete(it) }
}
session.submit(completableListenableHyperlink, "-expires 10s")
val page4 = completableListenableHyperlink.join()
println("CompletableListenableHyperlink loaded | " + page4.url)
```

## 详细代码

下面的代码作出了详细解释：

```kotlin
// Create a pulsar session
val session = PulsarContexts.createSession()
// The main url we are playing with
val url = "https://www.amazon.com/dp/B08PP5MSVB"

//
// 1. PlainUrl
//

// Load a page from local storage, or fetch it from the Internet if it does not exist or has expired
val plainUrl = PlainUrl(url, "-expires 10s")
val page = session.load(plainUrl)
println("PlainUrl loaded | " + page.url)

// Submit a url to the URL pool, the submitted url will be processed in a crawl loop
session.submit(plainUrl)

//
// 2. Hyperlink
//

// Load the portal page and then load all links specified by `-outLink`.
// Option `-outLink` specifies the cssSelector to select links in the portal page to load.
// Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
val hyperlink = Hyperlink(url, args = "-expires 10s -itemExpires 10s")
val pages = session.loadOutPages(hyperlink, "-outLink a[href~=/dp/] -topLinks 5")
println("Hyperlink's out pages are loaded | " + pages.size)

// Load the portal page and submit the out links specified by `-outLink` to the URL pool.
// Option `-outLink` specifies the cssSelector to select links in the portal page to submit.
// Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
val hyperlink2 = Hyperlink(url, args = "-expires 1d -itemExpires 7d")
session.submitForOutPages(hyperlink2, "-outLink a[href~=/dp/] -topLinks 5")

//
// 3. ParsableHyperlink
//

// Load a webpage and parse it into a document
val parsableHyperlink = ParsableHyperlink(url) { p, doc ->
    println("Parsed " + doc.baseUri)
}
val page2 = session.load(parsableHyperlink, "-expires 10s")
println("ParsableHyperlink loaded | " + page2.url)

//
// 4. ListenableHyperlink
//

// Load a ListenableHyperlink so we can register various event handlers
val listenableLink = ListenableHyperlink(url)
listenableLink.eventHandlers.browseEventHandlers.onDidInteract.addLast { pg, driver ->
    println("Interaction finished " + page.url)
}
val page3 = session.load(listenableLink, "-expires 10s")
println("ListenableHyperlink loaded | " + page3.url)

//
// 5. CompletableListenableHyperlink
//

// Load a CompletableListenableHyperlink, so we can register various event handlers,
// and we can wait for the execution to complete.
val completableListenableHyperlink = CompletableListenableHyperlink<WebPage>(url).apply {
    event.loadEvent.onLoaded.addLast { complete(it) }
}
session.submit(completableListenableHyperlink, "-expires 10s")
val page4 = completableListenableHyperlink.join()
println("CompletableListenableHyperlink loaded | " + page4.url)
```

------

[上一章](4data-extraction.md) [目录](1home.md) [下一章](6Java-style-async.md)
