# Advanced topics

Content entered directly below the header but before the first section heading is called the preamble.

## What's so difficult about scraping web data at scale?

Extracting web data at scale is extremely hard. Websites change frequently and are becoming more complex, meaning web data collected is often inaccurate or incomplete.

The right data extraction method can preempt many data-centered issues such as:

* Data that’s out-of-date
* Data that’s inaccurate
* Data with no context
* Data without sources or lineage
* Data that’s unusable due to structure

The right data extraction method for you should also address practical concerns such as:

* Your level of technical know-how
* Your budget
* The schedule on which you need your data updated
* The quantity of data you need
* Whether you need homogeneous or heterogeneous structured data
* Whether you need data from a single site, a range of sites, or from across the whole web

Since websites are becoming more and more complex, nowadays, the only way to scrape websites at scale is to render every page in a real browser. It's really hard to write a distributed browser rendering engine. Actually, Browser4 is the only open source solution for a distributed browser rendering engine.

## How to scrape a million product pages from an e-comm website a day?

The only way to scrape websites at scale with acceptable accuracy and completion is based on a distributed browser rendering engine.

Here is a complete solution to scrape amazon.com at scale: link:https://github.com/platonai/exotic-amazon[TODO]

## How to scrape pages behind a login?

It's fair simple to sign in a website before scraping in Browser4:

```kotlin
val options = session.options(args)
val loginHandler = TaobaoLoginHandler(username, password, warnUpUrl = portalUrl)
options.eventHandlers.loadEvent.onAfterBrowserLaunch.addLast(loginHandler)

session.loadOutPages(portalUrl, options)
```

The key point is: adding a LoginHandler to perform just after the browser launching. The login event handler will open the login page and automatically type the username, password and other information required to sign in the website.

The example code can be found here: [TmallCrawler](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/examples/sites/topEc/chinese/login/tmall/TmallCrawler.kt).

## How to download resources directly within a browser context?

There are many cases we want to download resources directly without a browser rendering:

* The data is returned by an AJAX request and easy to parse
* Non-browser-rendering scraping is super-fast

But the resources are often protected within a browsing session, we can not just issue http requests directly to ask
for the resources. To simulate the browsing session, we have to fetch the resources within a browsing context:

[AjaxCrawler](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/examples/sites/spa/wemix/AjaxCrawler.kt)

## How to scrape a single page application (SPA)?

There are several ways to scrape data from an SPA:

=## Resource mode

```kotlin
session.loadResource(url)
```

=## RPA mode
```kotlin

class RPAPaginateHandler(val initPageNumber: Int) : WebPageWebDriverEventHandler() {
    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        // ...
        // extract the text
        val text = driver.selectFirstTextOrNull(selector)
        // click the next page link
        driver.click(nextPageSelector)
        return null
    }
}
```

## How to make sure all fields are extracted correctly?

. Wait for the page loaded completely. There are a number of excellent strategies to determine whether a page is fully loaded
. Scroll down to the bottom of the page to ensure all ajax requests are triggered and loaded
. Simulate how a human being visits the webpage
. If there is still missing fields, consider refresh the page

## How to crawl paginated links?

. Construct the urls
. Extract the pagination urls

## How to crawl newly discovered links?

User a ListenableHyperlink to extract links after a referer page being fetched

## How to crawl the entire website?

TODO:

## How to simulate human behaviors?

Use event handler and web driver interface to interact with the browser.

## How to schedule priority tasks?

. Basic: call session.submit() with a priority parameter
. Advanced: use globalCache.urlPool for the complete control

## How to start a task at a fixed time point?

TODO:

## How to drop a scheduled task?

In a busy crawl system, there might be millions of pages are scheduled, all the tasks has to be finished before the midnight, because they have to be refreshed in the second day. In such case, all the unfinished task has to be dropped before 24:00.

Once a task is configured with the load option *-deadTime*, it will be dropped as soon as possible if now > deadTime.

## How to know the status of a task?

TODO:

## How to know what is happening in the system?

. Check the metrics
. Check the logs

## How to automatically generate the css selectors for fields to scrape?

. [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro)

## How to extract content from websites using machine learning automatically with commercial accuracy?

. [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro)

## How to scrape amazon.com to match industrial needs?
We will release a complete solution to crawl the entire amazon website: [exotic-amazon](https://github.com/platonai/exotic-amazon)

