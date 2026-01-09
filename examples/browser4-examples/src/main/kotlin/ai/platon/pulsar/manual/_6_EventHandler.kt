package ai.platon.pulsar.manual

import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * Demonstrates how to use event handlers.
 * */
fun main() {
    PulsarSettings.withDefaultBrowser()

    val url = PRODUCT_DETAIL_URL
    val session = PulsarContexts.createSession()
    val link = ListenableHyperlink(url, "", args = "-refresh -parse", eventHandlers = PrintFlowEventHandlers())

    // submit the link to the fetch pool.
    session.submit(link)

    // wait until all done.
    PulsarContexts.await()
}
