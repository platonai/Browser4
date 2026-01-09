Basic Usage
=

Prev [Home](1home.md) [Next](3load-options.md)

In a nutshell, Browser4 is all about **correctly implementing two methods: loading web pages and extracting data**. To achieve this goal, Browser4 has written and refined nearly a million lines of code, developing a range of cutting-edge technologies.

**Loading Web Pages:**

1. Load a web page or resource.
2. Load and render a web page using a browser, or load a single resource using the raw protocol.
3. Load hundreds of thousands of web pages on a single machine.
4. Load millions of web pages on ten machines.
5. Load 10 x N million web pages on N machines.

**Extracting Data:**

1. Extract data from web page content text, from text resources.
2. Extract data from the Document Object Model (DOM).
3. Extract data after interacting with web pages.
4. Extract data after taking screenshots of web pages.
5. **Automatically extract** data from millions of web pages.

**Combined:**

1. Use SQL to load web pages and extract data.
2. Load and extract data through cloud services.
3. Correctly load and extract data in extremely complex application scenarios.
4. Other auxiliary methods around loading and extracting.

Browser4 implements the **web as a database** paradigm, treating the external web like an internal database. If the required data is not in local storage, or the existing version does not meet analysis needs, the system will collect the latest version of the data from the internet.

This course introduces the basic APIs for loading and extracting data, which appear in PulsarSession. PulsarSession provides a rich set of APIs to cover all needs of the "load-parse-extract" process.

**These rich APIs allow us to solve the "load-parse-extract" problem with just one line of code in most of our programming scenarios:**

1. Regular loading.
2. Asynchronous loading.
3. Batch loading.
4. Batch loading of linked pages.
5. Various parameter combinations.

Here's a preview of these APIs, which we will explain in detail later.

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

Let's see how common usage looks like. First, create a Pulsar session, where all important work is processed:

```kotlin
val session = PulsarContexts.createSession()
val url = "https://www.amazon.com/dp/B08PP5MSVB"
```

The fundamental idea and method is load(), which first tries to load the web page from local storage. If the required page does not exist, has expired, or does not meet other requirements, it fetches the page from the Internet:

```kotlin
val page = session.load(url)
```

A simple parameter can be used to specify the web page expiration time. If the required page is already stored locally and not expired, the local version will be returned:

```kotlin
// Returns the local version
val page2 = session.load(url, "-expires 100d")
```

In **continuous crawling** tasks, we will handle large-scale crawling tasks in an **asynchronous and parallel** manner, submitting a large batch of URLs to the URL pool for continuous processing in the crawling loop:

```kotlin
// Submit URLs to the URL pool, which will be processed in a crawling loop
session.submit(url, "-expires 10s")
// Submit a batch of URLs, which will be processed in a crawling loop
session.submitAll(urls, "-expires 30d")
```

Once a web page is successfully loaded or crawled, we usually need to parse the web page into a DOM and start the subsequent data extraction work:

```kotlin
// Parse the page content into a document
val document = session.parse(page)
// Use the document
val title = document.selectFirstText(".title")
// ...
```

Alternatively, the "**load-parse**" two steps can be completed in one method:

```kotlin
// Load and parse
val document = session.loadDocument(url, "-expires 10s")
// Use the document
val title = document.selectFirstText(".title")
// ...
```

In later chapters, we will introduce in detail how to use **standard** CSS selectors and **extended** CSS selectors to extract data.

In many scenarios, we start from a list page and crawl linked pages. For example, we need to open a product list page and then crawl the detail pages of the products. PulsarSession provides a set of methods to simplify this task:

```kotlin
// Load the portal page, then load all links specified by `-outLink`.
// Option `-outLink` specifies the cssSelector to select links in the portal page to load.
// Option `-topLinks` specifies the maximum number of links selected by `-outLink`.
val pages = session.loadOutPages(url, "-expires 10s -itemExpires 10s -outLink a[href~=/dp/] -topLinks 10")

// Load the portal page and submit the out links specified by `-outLink` to the URL pool.
// Option `-outLink` specifies the cssSelector to select links in the portal page to submit.
// Option `-topLinks` specifies the maximum number of links selected by `-outLink`.
session.submitForOutPages(url, "-expires 1d -itemExpires 7d -outLink a[href~=/dp/] -topLinks 10")
```

Web page crawling ultimately involves extracting fields from web pages. PulsarSession also provides a wealth of methods to simplify the "**crawl-parse-extract**" composite task, with both single-page and batch processing versions:

```kotlin
// Load page, parse page, and extract fields
val fields = session.scrape(url, "-expires 10s", "#centerCol", listOf("#title", "#acrCustomerReviewText"))

// Load page, parse page, and extract named fields
val fields2 = session.scrape(url, "-i 10s", "#centerCol",
    mapOf("title" to "#title", "reviews" to "#acrCustomerReviewText"))

// Load linked pages, parse linked pages, and extract named fields from linked pages
val fields3 = session.scrapeOutPages(url, "-i 10s -ii 10s -outLink a[href~=/dp/] -topLink 10", "#centerCol",
    mapOf("title" to "#title", "reviews" to "#acrCustomerReviewText"))
```

In **large-scale crawling** projects, we usually do not write sequential code like load() -> parse() -> select(). Instead, we activate the **parsing subsystem** and register event handlers in the parsing subsystem to perform document-related tasks, such as extracting fields, saving fields to the database, collecting more links, etc.:

```kotlin
// Add `-parse` option to activate the parsing subsystem
val page = session.load(url, "-parse")
```

PulsarSession also provides a set of practical methods to facilitate actual programming work, such as asynchronous calls, coroutine support, parameter combinations, etc.

```kotlin
// Kotlin suspend calls
val page = runBlocking { session.loadDeferred(url, "-expires 10s") }

// Java-style async calls
session.loadAsync(url, "-expires 10s").thenApply(session::parse).thenAccept(session::export)
```

Finally, let's summarize the family of methods we provide to **load** pages:

| Function Name | Description |
|:-------------|:----------|
| load() | **Load** a page |
| loadDeferred() | Asynchronously **load** a page, which can be executed in Kotlin coroutines |
| loadAsync() | Asynchronously **load** a page, executed in Java async programming style |
| submit() | Submit a link, which will be **loaded** in the crawling loop |
| loadAll() | **Load** batch pages |
| submitAll() | Submit batch pages, which will be **loaded** in the crawling loop |
| loadOutPages() | **Load** a batch of linked pages |
| submitForOutPages() | Submit a batch of linked links, which will be **loaded** in the crawling loop |
| loadResource() | **Load** a resource, using a simple HTTP protocol, not rendered through a browser |
| loadResourceDeferred() | **Load** a resource, which can be executed in Kotlin coroutines |
| loadDocument() | **Load** a page and parse it into a DOM document |
| scrape() | **Load** a page, parse it into a DOM document, and extract data from it |
| scrapeOutPages() | **Load** a batch of linked pages, parse them into DOM documents, and extract data from them |

These functions provide a wealth of overloaded functions to meet the majority of complex programming requirements. For example, when we are ready to start a new crawling project, the first step is to assess the difficulty of the crawl, which can be initiated with one line of code:

```kotlin
fun main() = PulsarContexts.createSession().scrapeOutPages(
  "https://www.amazon.com/",  "-outLink a[href~=/dp/]", listOf("#title", "#acrCustomerReviewText"))
```

The complete code for this course can be found here: [kotlin](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_0_BasicUsage.kt), [Chinese mirror](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_0_BasicUsage.kt). To understand more detailed usage methods, you can directly read the source code: [PulsarSession](/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/session/PulsarSession.kt), [Chinese mirror](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/session/PulsarSession.kt).

In the next chapter, we will introduce [Load Options](3load-options.md) in detail. By configuring load options, you can precisely define our crawling tasks.

------

Prev [Home](1home.md) [Next](3load-options.md)
