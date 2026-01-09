Load Options
=

[Prev](2basic-usage.md) [Home](1home.md) [Next](4data-extraction.md)

Browser4 uses simple load options to precisely describe a collection task, such as data lifecycle, page quality requirements, task deadlines, task retries, etc. In most cases, a data collection task can be uniquely determined by URL arguments in the form of text, making it easy to copy, manage, store, transmit, parallelize, and communicate.

Starting with PulsarSession:

```kotlin
// Create a pulsar session
val session = PulsarContexts.createSession()
// The main URL we are working with
val url = "https://www.amazon.com/dp/B08PP5MSVB"
```

The most common requirement is to specify the page expiration time. If the required page is already stored locally and not expired, the local version will be returned.

The `-expires` option is used to specify the page expiration time:

```kotlin
// Load a page, or fetch it if the expiry time exceeds.
//
// Option `-expires` specifies the expiry time and has a short form `-i`.
//
// The expiry time supports both the ISO-8601 standard and the Hadoop time duration format:
// 1. ISO-8601 standard: PnDTnHnMn.nS
// 2. Hadoop time duration format: Valid units are: ns, us, ms, s, m, h, d.
var page = session.load(url, "-expires 10s")
page = session.load(url, "-i 10s")
```

A link may actually become invalid, for example, when a product on an e-commerce website is removed. However, the link may become effective again later, such as when the product is restocked. By default, Browser4 marks a link as "Gone" after detecting it as invalid 3 times and will no longer collect that link.

The `-ignoreFailure` option is used to ignore the "Gone" status:

```kotlin
// Add option `-ignoreFailure` to force re-fetch, ignoring all failures even if `fetchRetries` exceeds the maximum.
page = session.load(url, "-ignoreFailure -expires 0s")
```

The `-refresh` option is used to force a re-fetch, similar to clicking the "refresh" button on a real browser:

```kotlin
// Add option `-refresh` to force re-fetch, ignoring all failures and setting `fetchRetries` to 0,
// `-refresh` = `-ignoreFailure -expires 0s` and `page.fetchRetires = 0`.
page = session.load(url, "-refresh")
```

Modern web pages often contain a large amount of **lazy-loaded** content. After a page is opened, only the first screen of visible data is actually loaded, and the rest of the data is only loaded when the page scrolls into view.

For specific websites, we usually have an estimate of the basic situation of the web page, such as page size, the number of links on the page, the number of images on the page, etc. At this time, we can use these values to determine whether a page is fully loaded. If the web page is incomplete, it needs to be re-collected.

The `.requireSize` option specifies the minimum page size, and if the actual collected size is smaller than this value, it needs to be re-collected.

The `.requireImages` option specifies the minimum number of images on the page, and if the actual number of collected images is smaller than this value, it needs to be re-collected.

The `.requireAnchors` option specifies the minimum number of anchors on the page, and if the actual number of collected anchors is smaller than this value, it needs to be re-collected.

```kotlin
// Option `-requireSize` to specifies the minimal page size, the page should be re-fetch if the
// last page size is smaller than that.
page = session.load(url, "-requireSize 300000")

// Option `-requireImages` specifies the minimal image count, the page should be re-fetch if the image count of the
// last fetched page is smaller than that.
page = session.load(url, "-requireImages 10")

// Option `-requireAnchors` specifies the minimal anchor count, the page should be re-fetch if
// the anchor count of the last fetched page is smaller than that.
page = session.load(url, "-requireAnchors 100")
```

Web page collection tasks usually have timeliness. For example, a task is to monitor a product once a day, and if the task is not completed by midnight, it should be discarded, and a new task responsible for the next day's collection will enter the queue.

The `-deadline` option is used to specify the deadline for the task. If the task is not completed after the deadline, the task should be discarded as soon as possible.

```kotlin
// If the deadline is exceeded, the task should be abandoned as soon as possible.
page = session.load(url, "-deadline 2022-04-15T18:36:54.941Z")
```

In **large-scale collection** projects, we usually do not write sequential code like load() -> parse() -> select(). Instead, we activate the **parsing subsystem** and register event handlers in the parsing subsystem to perform document-related tasks, such as extracting fields, saving fields to the database, collecting more links, etc.

The `-parse` option is used to activate the parsing subsystem.

```kotlin
// Add option `-parse` to activate the parsing subsystem.
page = session.load(url, "-parse")
```

Web page content is crucial for data analysis. However, for various considerations such as storage costs and storage performance, we sometimes choose not to save web page content.

The `-storeContent` option is used to choose whether to save web page content.

```kotlin
// Option `-storeContent` tells the system to save the page content to storage.
page = session.load(url, "-storeContent")
```

Browser4 provides a comprehensive retry mechanism to ensure that the collected pages meet the analysis requirements.

Due to the increasing complexity of modern web pages, the collected pages may be incomplete:

1. Lazy-loaded content is not loaded.
2. The page is temporarily unavailable.
3. Local network temporary failure.
4. Proxy network temporary failure.
5. The page redirects to other pages, such as login/CAPTCHA, etc.
6. Other exceptions.

The `-nMaxRetry` option specifies the maximum number of retries in the crawl loop, and if it's still failed after this number, the page will be marked as "Gone". Unless specifically specified, the system will not collect lost pages.

```kotlin
// Option `-nMaxRetry` specifies the maximal number of retries in the crawl loop, and if it's still failed
// after this number, the page will be marked as `Gone`. A retry will be triggered when a RETRY(1601) status code
// is returned.
page = session.load(url, "-nMaxRetry 3")
```

In some scenarios, we need to retry immediately:

```kotlin
// Option `-nJitRetry` specifies the maximal number of retries for the load phase, which will be triggered
// when a RETRY(1601) status is returned.
page = session.load(url, "-nJitRetry 2")
```

The following demonstrates a comprehensive example. In this example, we collect the portal page, extract links from the portal page, and then collect the linked-out pages:

```kotlin
// Load or fetch the portal page, and then load or fetch the out links selected by `-outLink`.
//
// 1. `-expires` specifies the expiry time of item pages and has a short form `-ii`.
// 2. `-outLink` specifies the cssSelector for links in the portal page to load.
// 3. `-topLinks` specifies the maximal number of links selected by `-outLink`.
//
// Fetch conditions:
// 1. `-itemExpires` specifies the expiry time of item pages and has a short form `-ii`.
// 2. `-itemRequireSize` specifies the minimal page size.
// 3. `-itemRequireImages` specifies the minimal number of images in the page.
// 4. `-itemRequireAnchors` specifies the minimal number of anchors in the page.
var pages = session.loadOutPages(url, "-expires 10s" +
        " -itemExpires 7d" +
        " -outLink a[href~=item]" +
        " -topLinks 10" +
        " -itemExpires 1d" +
        " -itemRequireSize 600000" +
        " -itemRequireImages 5" +
        " -itemRequireAnchors 50"
)
```

This course provides [complete code](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_1_LoadOptions.kt), [Chinese mirror](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_1_LoadOptions.kt).

------

[Prev](2basic-usage.md) [Home](1home.md) [Next](4data-extraction.md)
