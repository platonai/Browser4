加载参数
=

Browser4 使用简单的加载参数来精确描述一个采集任务，譬如数据生命周期，页面质量要求，任务截止日期，任务重试等。绝大多数情况下，一个数据采集任务可以用 url arguments 形式的文本来唯一确定，因此它可以很轻松地被复制、管理、存储、传输、并行化，以及沟通交流。

首先还是从 PulsarSession 开始：

```kotlin
// Create a pulsar session
val session = PulsarContexts.createSession()
// The main url we are playing with
val url = "https://www.amazon.com/dp/B08PP5MSVB"
```

最常见的需求是指定页面过期时间，如果需要的页面已在本地存储中并且未过期，则返回本地版本。

选项 -expires 用来指定页面过期时间：

```kotlin
// Load a page, or fetch it if the expiry time exceeds.
//
// Option `-expires` specifies the expiry time and has a short form `-i`.
//
// The expiry time support both ISO-8601 standard and hadoop time duration format:
// 1. ISO-8601 standard : PnDTnHnMn.nS
// 2. Hadoop time duration format : Valid units are : ns, us, ms, s, m, h, d.
var page = session.load(url, "-expires 10s")
page = session.load(url, "-i 10s")
```

一个链接会实际失效，譬如说电商网站的一个商品被下架；但是该链接在后来或许又会重新生效，譬如该商品重新上架。默认情况下，Browser4 在 3 次探测到链接无效后，会将该链接标记为“丢失”（ Gone），并不再采集该链接。

选项 -ignoreFailure 用来忽略“丢失”状态：

```kotlin
// Add option `-ignoreFailure` to force re-fetch ignoring all failures even if `fetchRetries` exceeds the maximal.
page = session.load(url, "-ignoreFailure -expires 0s")
```

选项 -refresh 用来强制重新采集，就像在真实浏览器上点击“刷新”按钮：

```kotlin
// Add option `-refresh` to force re-fetch ignoring all failures and set `fetchRetries` to be 0,
// `-refresh` = `-ignoreFailure -expires 0s` and `page.fetchRetires = 0`.
page = session.load(url, "-refresh")
```

现代网页通常包含了大量**延迟加载**的内容，一个页面被打开后，仅仅只有第一屏可见部分的数据被实际加载，而其他部分的数据，只有当页面滚动到视野中时，才会被加载。

对于特定网站，我们通常对网页的基本情况有估算，譬如，页面尺寸大小，页面上的链接数量，页面上的图片数量等，这时候，我们可以通过这些值来判断一个页面是否加载完整，如果网页不完整，则需要重新采集。

选项 -requireSize 指定页面最小尺寸，如果实际采集的尺寸小于该值，则需要重新采集。

选项 -requireImages 指定页面最小图片数，如果实际采集的图片数小于该值，则需要重新采集。

选项 -requireAnchors 指定页面最小链接数，如果实际采集的链接数小于该值，则需要重新采集。

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

网页采集任务通常有时效性，譬如任务是每天监控产品一次，而如果过了凌晨该任务还没有完成，它就应当被丢弃，而负责第二天采集的新任务会进入队列。

选项 -deadline 用来指定任务的截止时间，如果过了截止事件任务还没有完成，则该任务会在第一时间丢弃。

```kotlin
// If the deadline is exceeded, the task should be abandoned as soon as possible.
page = session.load(url, "-deadline 2022-04-15T18:36:54.941Z")
```

在**大规模采集**项目中，我们通常不会编写 load() -> parse() -> select() 这样的顺序性代码，而是会激活**解析子系统**，并在解析子系统中注册
事件处理器，通过事件处理器来执行文档相关任务，譬如提取字段，将字段保存到数据库，收集更多链接等等。

选项 -parse 用来激活解析子系统。

```kotlin
// Add option `-parse` to activate the parsing subsystem.
page = session.load(url, "-parse")
```

网页内容对数据分析至关重要。然而，为了存储成本、存储性能等多方面的考虑，我们有时候会选择不保存网页内容。

选项 -storeContent 来选择保存网页内容。

```kotlin
// Option `-storeContent` tells the system to save the page content to the storage.
page = session.load(url, "-storeContent")
```

Browser4 提供了完善的重试机制，来保证所采集的页面符合分析要求。

由于现代网页越来越复杂，被采集的页面可能不完整：

1. 延迟加载的内容未被加载
2. 页面临时失效
3. 本地网络临时故障
4. 代理网络临时失效
5. 页面跳转至其他页面，如登录/人机验证等
6. 其他异常

选项 -nMaxRetry 指定了自动重试的最大次数，如果重试次数超过最大次数，则该页面被标记为“丢失”（ Gone ）。除非特别指定，系统不会再采集丢失的页面。

```kotlin
// Option `-nMaxRetry` specifies the maximal number of retries in the crawl loop, and if it's still failed
// after this number, the page will be marked as `Gone`. A retry will be triggered when a RETRY(1601) status code
// is returned.
page = session.load(url, "-nMaxRetry 3")
```

在一些场景中，我们需要即时重试：

```kotlin
// Option `-nJitRetry` specifies the maximal number of retries for the load phase, which will be triggered
// when a RETRY(1601) status is returned.
page = session.load(url, "-nJitRetry 2")
```

下面演示了一个综合的例子。在这个例子中，我们采集入口页，从入口页中提取链接，然后采集链出页：

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

本课程提供了[完整代码](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_1_LoadOptions.kt)，[国内镜像](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_1_LoadOptions.kt)。

------

[上一章](2basic-usage.md) [目录](1home.md) [下一章](4data-extraction.md)
