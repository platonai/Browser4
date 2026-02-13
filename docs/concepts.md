# Browser4 Concepts

English | [简体中文](/docs/zh/concepts.md)

To unlock the full potential of Browser4 and tackle even the most complex data scraping tasks, a solid understanding of its core concepts is essential. By grasping these fundamental principles, you'll be equipped to wield Browser4 as a powerful tool for extracting valuable information from the web.
Let's delve into the key concepts that form the foundation of your data scraping journey with Browser4:

## Core Concepts

### Web Scraping
Web scraping, web harvesting, or web data extraction is the process of extracting data from websites.

### Network As A Database
The Internet, especially the World Wide Web (WWW), is the largest database in the world. However, extracting data from the Web has never been easy.

Browser4 treats the network as a database. For each web page, Browser4 first checks the local storage. If it does not exist, has expired, or any other fetch condition is triggered, Pulsar retrieves it from the Web.

Browser4 has SQL support, so we can turn the Web into tables and charts using simple SQL queries, and we can also query the Web using SQL directly.

### Auto Extract
Web data extraction has evolved over the last three decades from using statistical methods to more advanced machine learning methods. Machine learning techniques are much preferred nowadays due to demand for time-to-market, developer productivity, and cost concerns. Using our cutting-edge technology, the entire end-to-end Web data extraction lifecycle is automated without any manual intervention.

We provided a preview project to show how to use our world-leading machine learning algorithm to extract almost every field in web pages automatically: PulsarRPAPro.

You can also use PulsarRPAPro as an intelligent CSS selector generator. The generated CSS selectors can be used in any traditional scraping systems to extract data from web pages.

### Browser Rendering
Although Browser4 supports various web scraping methods, browser rendering is the primary way Browser4 scrapes web pages.

Browser rendering means every web page is opened by a real browser to ensure all fields on the web page are present correctly.

### Pulsar Context
PulsarContext consists of a set of highly customizable components that provide the core set of interfaces of the system and is used to create PulsarSession.

The PulsarContext is the interface to all other contexts.

A StaticPulsarContext consists of the default components.

A ClassPathXmlPulsarContext consists of components which are customized using Spring bean configuration files.

A SQLContext consists of components to support X-SQL.

Programmers can write their own Pulsar Contexts to extend the system.












### Pulsar Session

`PulsarSession` defines an interface to load web pages from local storage or fetch from the Internet, as well as methods for parsing, extracting, saving, indexing, and exporting web pages.

Key methods:

- `.load()`: load a web page from local storage, or fetch it from the Internet.
- `.parse()`: parse a web page into a document.
- `.scrape()`: load a web page, parse it into a document, and then extract fields from the document.
- `.submit()`: submit a URL to the URL pool, which will be processed in the main loop later.

And also the batch versions:

- `.loadOutPages()`: load the portal page and out pages.
- `.scrapeOutPages()`: load the portal page and out pages, extract fields from out pages.

For a comprehensive understanding of all methods, please refer to PulsarSession.

The first step is to understand how to load a page. Load methods like `session.load()` first check the local storage and return the local version if the required page exists and meets the requirements; otherwise, it will be fetched from the Internet.

The `load options`, also known as `load parameters` can be used to specify when the system will fetch a web page from the Internet:

- Expiration
- Force refresh
- Page size
- Required fields
- Other conditions

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

Once a page is loaded from local storage, or fetched from the Internet, the next processing steps are:

1. Parse the page content into an HTML document.
2. Extract fields from the HTML document.
3. Write the fields into a destination, such as:
    - Plain file, Avro file, CSV, Excel, MongoDB, MySQL, etc.
    - Solr, Elastic, etc.

There are various ways to fetch the page content from the Internet:

- Through the HTTP protocol.
- Through a real browser.

As webpages become increasingly complex, fetching webpages through real browsers is the primary method used today.

When fetching webpages using a real browser, we may need to interact with the pages to ensure the desired fields are loaded correctly and completely. To achieve this, activate PageEvent and use WebDriver.

```kotlin
val options = session.options(args)
options.eventHandlers.browseEventHandlers.onDocumentFullyLoaded.addLast { page, driver ->
    driver.scrollDown()
}
session.load(url, options)
```

`WebDriver` provides a complete set of methods for RPA, similar to selenium, playwright, and puppeteer. All actions and behaviors are optimized to mimic real people as closely as possible.

### Web Driver

[WebDriver](/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/crawl/fetch/driver/WebDriver.kt) provides a concise interface for accessing and interacting with web pages, with all actions and behaviors optimized to mimic real humans as closely as possible, such as scrolling, clicking, typing text, dragging and dropping, etc.

The methods in this interface are mainly divided into three categories:

1. Control over the browser itself.
2. Selecting elements, extracting text content, and attributes.
3. Interacting with web pages.

The main methods include:

- `.navigateTo()`: Load a new web page.
- `.scrollDown()`: Scroll down on the web page to fully load the page. Most modern web pages support lazy loading using AJAX technology, meaning that web page content only begins to load when it scrolls into view.
- `.pageSource()`: Obtain the web page source code.

### URLs

A Uniform Resource Locator (URL), commonly referred to as a web address, is a reference to a web resource that specifies its location on a computer network and a mechanism for retrieving it.

A URL in Browser4 is a normal URL with extra information to describe a task. Every task in Browser4 is defined as some form of URL.

There are several basic forms of URLs in Browser4:

- A `NormURL`.
- A String.
- A `UrlAware`.
- A `DegenerateUrl`.

`NormURL` stands for 'normal url', which means the URL is in its final form and is usually passed to a real browser eventually.

If not specified, a URL in string format is actually a 'configured url', or 'a URL with arguments'. For example:

```kotlin
val url = "https://www.amazon.com/dp/B10000  -taskName amazon -expires 1d -ignoreFailure"
session.load(url)
```

The above code has the same meaning as the following code:

```kotlin
val url = "https://www.amazon.com/dp/B10000"
val args = "-taskName amazon -expires 1d -ignoreFailure"
session.load(url, args)
```

A UrlAware provides much more complex controls for crawl tasks. UrlAware is the interface of all Hyperlinks; for more details, see the Hyperlinks section.

Finally, a DegenerateUrl is not actually a URL; it's an interface for any task to be executed in the crawl loop.




















Finally, `DegenerateUrl` is, in fact, not a link; it is designed as an interface for non-collection tasks to be executed within the Crawl Loop.

### Hyperlinks

Hyperlinks, commonly referred to as links, are references to data on the Web, typically containing a URL, text, and a set of attributes, which users can follow by clicking or tapping.

Hyperlinks in Browser4 are similar to standard hyperlinks but include additional information describing the task.
Browser4 predefines several hyperlinks:

`ParsableHyperlink` is a convenient abstraction for executing a get-parse task within a continuous crawling job:

```kotlin
val parseHandler = { _: WebPage, document: FeaturedDocument ->
  // do something wonderful with the document
}
val urls = LinkExtractors.fromResource("seeds.txt").map { ParsableHyperlink(it, parseHandler) }
PulsarContexts.create().submitAll(urls).await()
```

`CompletableHyperlink` helps us perform Java-style asynchronous computations: submit a hyperlink and wait for the task to complete.
`ListenableHyperlink` helps us attach event handlers:

```kotlin
val session = PulsarContexts.createSession()
val link = ListenableHyperlink(portalUrl, args = "-refresh -parse", event = PrintFlowEventHandlers())
session.submit(link)
```

Example code: [kotlin](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_6_EventHandler.kt).

`CompletableListenableHyperlink` allows us to do both:

```kotlin
fun executeQuery(request: ScrapeRequest): ScrapeResponse {
  // the hyperlink is a CompletableListenableHyperlink
  val hyperlink = createScrapeHyperlink(request)
  session.submit(hyperlink)
  // wait for the task to complete or timeout
  return hyperlink.get(3, TimeUnit.MINUTES)
}
```

Example code: [kotlin](/pulsar-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/ScrapeService.kt).

### Load Options

Almost every method in a Pulsar Session accepts an argument named `load arguments` or `load options` to control how web pages are loaded, retrieved, and extracted.
There are three ways to combine URLs and their arguments:
1. URL-argument form
2. URL-options form
3. configured-URL form

```kotlin
// use URL-argument form:
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

The configured-URL form can be mixed with the other two forms and has higher priority.

The most important load options include:
- `-expires`     // Expiration time of the page
- `-itemExpires` // Expiration time of product pages in batch fetching methods
- `-outLink`     // Selector for external links to be fetched
- `-refresh`     // Force (re)fetching the page, just like clicking the refresh button in a real browser
- `-parse`       // Activate the parsing subsystem
- `-resource`    // Obtain the URL as a resource without browser rendering


Load arguments are all parsed into a `LoadOptions` object; check the code to learn about all supported options.

It's worth noting that when we execute the `load()` series of methods, the system does not parse the web page, but instead provides the `parse()` method for parsing. However, once we add the `-parse` parameter, the system activates the parsing subsystem and automatically parses the web page. We can register handlers to perform tasks such as data extraction, persistence of extraction results, and collecting more links.

There are two ways to register handlers in the parsing subsystem: using `ParseFilters` to register a global `ParseFilter`, or using `PageEventHandlers` to register a page-scoped event handler.

A good case for using `ParseFilters` to execute complex tasks is e-commerce full-site data collection, where different `ParseFilter` are registered for each type of page to handle tasks such as data extraction, persistence of extraction results, and link collection.





























### Browser Settings

BrowserSettings provides a convenient interface to define the behavior of browser automation, such as:

- Headed or headless?
- Single Page Application (SPA) or not?
- Enable proxy IPs or not?
- Block media resources or not?

For more details, refer to BrowserSettings.

### Event Handler

`Event handlers` here refer to webpage event handlers that capture and process events throughout the lifecycle of webpages.

For information on all available event handlers, refer to EventHandlerUsage.

### X-SQL

Browser4 supports the Network As A Database paradigm, and we have developed X-SQL to query the Web directly and convert webpages into tables and charts.

For a detailed introduction and function descriptions about X-SQL, refer to X-SQL.

## Implementation Concepts

Developers are not required to study the implementation concepts, but understanding these concepts can help us better grasp how the entire system operates.

### URL Pool

During continuous crawls, URLs are added to a UrlPool. A UrlPool contains various UrlCaches to meet different requirements, such as priority, delaying, deadline, and external loading requirements.

### Crawl Loop

During continuous crawls, a crawl loop is initiated to continuously visit URLs from the UrlPool, and then load/fetch them asynchronously in a PulsarSession.

### Privacy Context

One of the biggest challenges in web scraping tasks is maintaining bot stealth. For web scraping tasks, it's crucial that the website cannot discern whether a visit is from a human or a bot. If a page visit is suspected by the website, known as a privacy leak, the privacy context must be discarded, and Browser4 will visit the page in a different privacy context.

### Proxy Management

This involves obtaining IPs from proxy vendors, recording proxy status, smart and automatic IP rotation, and more.

### Web Driver Stealth

When a browser is programmed to access a webpage, the website may detect that the visit is automated. Web Driver stealth technology is used to prevent such detection.

### Backend Storage

Browser4 supports a variety of backend storage solutions to meet our customers' urgent needs, such as Local File System, MongoDB, HBase, Gora, and so on. is a URL, so the crawl loop can accept and execute any type of task.
