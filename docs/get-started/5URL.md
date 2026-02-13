URL
=
[Prev](4data-extraction.md) [Home](1home.md) [Next](6Java-style-async.md)

In Browser4, each task is defined as some form of URL, which often appears with a loading argument to finely control a collection task, such as data expiration, basic data requirements, task deadlines, task retries, etc. In most cases, a data collection task can be uniquely determined by the form of *URL-arguments*, so it can be easily copied, managed, stored, transferred, parallelized, and used for communication.

## URLs

A Uniform Resource Locator (URL), commonly known as a web address, is a reference to a network resource that specifies its location on a computer network and the mechanism for retrieving it. URLs in Browser4 carry additional information for describing data collection tasks and come in several basic forms:

- A [NormURL](/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/common/urls/NormURL.kt)
- A String
- A [UrlAware](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/urls/Hyperlinks.kt)
- A [DegenerateUrl](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/urls/Urls.kt)

NormURL represents a "normalized URL," meaning that this URL is the final form for the fetch component and is usually ultimately passed to the actual browser.

If not specified, the string format of the URL actually means a configured URL, a URL with parameters, or a URL with arguments, for example:

```kotlin
val url = "https://www.amazon.com/dp/B10000  -taskName amazon -expires 1d -ignoreFailure"
session.load(url)
```

The above code is equivalent to the following code:

```kotlin
val url = "https://www.amazon.com/dp/B10000"
val args = "-taskName amazon -expires 1d -ignoreFailure"
session.load(url, args)
```

UrlAware provides more complex control to describe collection tasks and is the interface for all Hyperlinks.

Finally, [DegenerateUrl](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/urls/Urls.kt) literally means a "degenerate url," which is not actually a url. It is designed to describe non-scraping tasks, such as local computing tasks that do not involve the network, to be executed in the main loop.

## Hyperlinks

A [hyperlink](https://en.wikipedia.org/wiki/Hyperlink), or simply a link, specifically refers to a reference to data on the Web, usually containing a URL, text, and a set of attributes that users can follow by clicking or tapping. [Hyperlinks in Browser4](/pulsar-common/src/main/kotlin/ai/platon/pulsar/common/urls/Hyperlinks.kt) are like ordinary hyperlinks but with additional information to describe tasks.

Pulsar predefines several hyperlinks:

**ParsableHyperlink** is a convenient abstraction for performing **fetch-parse** tasks in a continuous collection job:

```kotlin
val parseHandler = { _: WebPage, document: FeaturedDocument ->
    // do something wonderful with the document
}

val urls = LinkExtractors.fromResource("seeds.txt").map { ParsableHyperlink(it, parseHandler) }
PulsarContexts.create().submitAll(urls).await()
```

**CompletableHyperlink** helps us with Java-style asynchronous computing: submit a hyperlink and wait for the task to complete.

**ListenableHyperlink** helps us attach event handlers:

```kotlin
val session = PulsarContexts.createSession()
val link = ListenableHyperlink(portalUrl, args = "-refresh -parse", event = PrintFlowEvent())
session.load(link)
```

**CompletableListenableHyperlink** helps us do both at the same time:

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

## Detailed Code

The following code provides a detailed explanation:

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

[Prev](4data-extraction.md) [Home](1home.md) [Next](6Java-style-async.md)
