Event Handling
=

[Prev](8continuous-crawling.md) [Home](1home.md) [Next](10RPA.md)

**📖 For comprehensive AI-friendly EventHandlers documentation, see [EventHandlers AI Guide](../event-handlers-ai.md)**

The event handling mechanism offers a comprehensive approach to capture and process events across the entire lifecycle of a web page. The following concise program logs the sequence of all page events along with their respective names.
Page event handlers are encapsulated within the `PageEventHandlers` class, categorized into three distinct groups to manage events at various phases:

1. `CrawlEventHandlers` - Deals with events occurring within the crawling iteration.
2. `LoadEventHandlers` - Manages events related to the loading and parsing of the page.
3. `BrowseEventHandlers` - Controls events that happen during the interactive browsing phase, including actions that prompt the loading or display of associated elements.

```kotlin

/**
 * Print the call sequence and the event name of all page event handlers
 * */
class PrintFlowEventHandlers: DefaultPageEventHandlers() {
    private val sequencer = AtomicInteger()
    private val seq get() = sequencer.incrementAndGet()
    
    init {
        loadEventHandlers.apply {
            onNormalize.addLast { url ->
                println("$seq. load - onNormalize")
                url
            }
            onWillLoad.addLast { url ->
                println("$seq. load - onWillLoad")
                null
            }
            onWillFetch.addLast { page ->
                println("$seq. load - onWillFetch")
            }
            onFetched.addLast { page ->
                println("$seq. load - onFetched")
            }
            onWillParse.addLast { page ->
                println("$seq. load - onWillParse")
            }
            onWillParseHTMLDocument.addLast { page ->
                println("$seq. load - onWillParseHTMLDocument")
            }
            onHTMLDocumentParsed.addLast { page: WebPage, document: FeaturedDocument ->
                println("$seq. load - onHTMLDocumentParsed")
            }
            onParsed.addLast { page ->
                println("$seq. load - onParsed")
            }
            onLoaded.addLast { page ->
                println("$seq. load - onLoaded")
            }
        }
        
        browseEventHandlers.apply {
            onWillLaunchBrowser.addLast { page ->
                println("$seq. browse - onWillLaunchBrowser")
            }
            onBrowserLaunched.addLast { page, driver ->
                println("$seq. browse - onBrowserLaunched")
            }
            onWillNavigate.addLast { page, driver ->
                println("$seq. browse - onWillNavigate")
            }
            onNavigated.addLast { page, driver ->
                println("$seq. browse - onNavigated")
            }
            onWillInteract.addLast { page, driver ->
                println("$seq. browse - onWillInteract")
            }
            onWillCheckDocumentState.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onWillCheckDocumentState")
            }
            onDocumentFullyLoaded.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onDocumentFullyLoaded")
            }
            onWillScroll.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onWillScroll")
            }
            onDidScroll.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onDidScroll")
            }
            onDocumentSteady.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onDocumentSteady")
            }
            onWillComputeFeature.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onWillComputeFeature")
            }
            onFeatureComputed.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onFeatureComputed")
            }
            onDidInteract.addLast { page, driver ->
                println("$seq. browse - onDidInteract")
            }
            onWillStopTab.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onWillStopTab")
            }
            onTabStopped.addLast { page: WebPage, driver: WebDriver ->
                println("$seq. browse - onTabStopped")
            }
        }
        
        crawlEventHandlers.apply {
            onWillLoad.addLast { url: UrlAware ->
                println("$seq. crawl - onWillLoad")
                url
            }
            onLoaded.addLast { url, page ->
                println("$seq. crawl - onLoaded")
            }
        }
    }
}
```

The entry point for the call is as follows:

```kotlin
/**
 * Demonstrates how to use event handlers.
 * */
fun main() {
    BrowserSettings.withSystemDefaultBrowser()
    
    val url = "https://www.amazon.com/dp/B08PP5MSVB"
    val session = PulsarContexts.createSession()
    val link = ListenableHyperlink(url, "", args = "-refresh -parse", event = PrintFlowEventHandlers())
    
    // submit the link to the fetch pool.
    session.submit(link)
    
    // wait until all done.
    PulsarContexts.await()
}

```

The example program outputs the following:

```
1. crawl - onWillLoad
2. load - onNormalize
3. load - onWillLoad
4. load - onWillFetch
5. browse - onWillLaunchBrowser
6. browse - onBrowserLaunched
7. browse - onWillNavigate
8. browse - onNavigated
9. browse - onWillNavigate
10. browse - onWillCheckDocumentState
11. browse - onDocumentFullyLoaded
12. browse - onWillScroll
13. browse - onDidScroll
14. browse - onDocumentSteady
15. browse - onWillComputeFeature
16. browse - onFeatureComputed
17. browse - onDidInteract
18. browse - onWillStopTab
19. browse - onTabStopped
20. load - onFetched
21. load - onWillParse
22. load - onWillParseHTMLDocument
23. load - onHTMLDocumentParsed
24. load - onParsed
25. load - onLoaded
26. crawl - onLoaded
```

------

[Prev](8continuous-crawling.md) [Home](1home.md) [Next](10RPA.md)
