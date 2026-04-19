package ai.platon.pulsar.basic

import ai.platon.pulsar.common.PulsarParams.VAR_IS_SCRAPE
import ai.platon.pulsar.common.urls.DegenerateUrl
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.common.persist.ext.loadEventHandlers
import ai.platon.pulsar.skeleton.crawl.PageEventHandlers
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.crawl.event.AbstractCrawlEventHandlers
import ai.platon.pulsar.skeleton.crawl.event.AbstractLoadEventHandlers
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultPageEventHandlers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

open class MockListenableHyperlink(url: String): ListenableHyperlink(url, "") {
    val sequencer = AtomicInteger()
    val triggeredEvents = mutableListOf<String>()
    val expectedEvents = listOf(
        "1. CrawlEvent.onWillLoad",
        "2. LoadEvent.onWillLoad",
        "3. LoadEvent.onWillParseHTMLDocument 2",
        "4. LoadEvent.onWillParseHTMLDocument",
        "5. LoadEvent.onHTMLDocumentParsed",
        "6. LoadEvent.onParsed",
        "7. LoadEvent.onLoaded",
        "8. LoadEvent.onLoaded - 2",
        "9. CrawlEvent.onLoaded"
    )

    class MockCrawlEventHandlers(val hyperlink: MockListenableHyperlink): AbstractCrawlEventHandlers() {
        val seq get() = hyperlink.sequencer.incrementAndGet()

        init {
            onWillLoad.addFirst {
                hyperlink.triggeredEvents.add("$seq. CrawlEvent.onWillLoad")
                it
            }
            onLoaded.addFirst { url: UrlAware, page: WebPage? ->
                hyperlink.triggeredEvents.add("$seq. CrawlEvent.onLoaded")

                if (page == null) {
                    return@addFirst null
                }

                printlnPro("............onLoaded " + page.id)
                require(page is AbstractWebPage)
                page.variables.variables.forEach { (t, u) -> printlnPro("$t $u") }

                if (page.protocolStatus.isSuccess) {
                    assertTrue(page.isLoaded || page.isContentUpdated)
                    assertNull(page.persistContent)
                    if (page.isContentUpdated) {
                        assertNotNull(page.tmpContent) { "if the page is fetched, the content must be cached" }
                    }
                }
                assertTrue(page.hasVar(VAR_IS_SCRAPE))
            }
        }
    }

    class MockLoadEventHandlers(val hyperlink: MockListenableHyperlink) : AbstractLoadEventHandlers() {
        val seq get() = hyperlink.sequencer.incrementAndGet()
        private val thisHandler = this

        init {
            onWillLoad.addFirst {
                hyperlink.triggeredEvents.add("$seq. LoadEvent.onWillLoad")
                printlnPro("............onWillLoad")
                it
            }
            onWillParseHTMLDocument.addFirst { page ->
                hyperlink.triggeredEvents.add("$seq. LoadEvent.onWillParseHTMLDocument")
                printlnPro("............onWillParseHTMLDocument " + page.id)
                printlnPro("$this " + page.loadEventHandlers)
                require(page is AbstractWebPage)
                page.variables[VAR_IS_SCRAPE] = true
                null
            }
            onWillParseHTMLDocument.addFirst { page ->
                hyperlink.triggeredEvents.add("$seq. LoadEvent.onWillParseHTMLDocument 2")
//                assertSame(thisHandler, page.loadEvent)
                printlnPro("............onWillParseHTMLDocument " + page.id)
            }
            onHTMLDocumentParsed.addFirst { page, document ->
                hyperlink.triggeredEvents.add("$seq. LoadEvent.onHTMLDocumentParsed")
                printlnPro("............onHTMLDocumentParsed " + page.id)
//                assertSame(thisHandler, page.loadEvent)
                require(page is AbstractWebPage)
                assertTrue(page.hasVar(VAR_IS_SCRAPE))
            }
            onParsed.addFirst { page ->
                hyperlink.triggeredEvents.add("$seq. LoadEvent.onParsed")
                printlnPro("............onParsed " + page.id)
                printlnPro("$thisHandler " + page.loadEventHandlers)
//                assertSame(thisHandler, page.loadEvent)
            }
            onLoaded.addFirst { page ->
                hyperlink.triggeredEvents.add("$seq. LoadEvent.onLoaded")
//                assertSame(thisHandler, page.loadEvent)
            }
            onLoaded.addLast { page ->
                hyperlink.triggeredEvents.add("$seq. LoadEvent.onLoaded - 2")
                hyperlink.page = page
                hyperlink.isDone.countDown()
            }
        }
    }

    override var args: String? = "-cacheContent true -storeContent false -parse -refresh"
    override var eventHandlers: PageEventHandlers = DefaultPageEventHandlers(
        loadEventHandlers = MockLoadEventHandlers(this),
        crawlEventHandlers = MockCrawlEventHandlers(this)
    )

    var page: WebPage? = null

    private val isDone = CountDownLatch(1)
    fun isDone() = isDone.count == 0L
    fun await() = isDone.await()
}

open class MockDegeneratedListenableHyperlink : ListenableHyperlink("", ""), DegenerateUrl {
    val sequencer = AtomicInteger()
    val triggeredEvents = mutableListOf<String>()
    val expectedEvents = listOf(
        "1. CrawlEvent.onWillLoad",
        "2. CrawlEvent.onLoaded"
    )

    class MockCrawlEventHandlers(val hyperlink: MockDegeneratedListenableHyperlink): AbstractCrawlEventHandlers() {
        val seq get() = hyperlink.sequencer.incrementAndGet()

        init {
            onWillLoad.addFirst {
                hyperlink.triggeredEvents.add("$seq. CrawlEvent.onWillLoad")
                it
            }
//            onLoad.addFirst {
//                hyperlink.triggeredEvents.add("$seq. CrawlEvent.onLoad")
//                logPrintln("Hello! I'm here!")
//                it
//            }
            onLoaded.addFirst { url: UrlAware, page: WebPage? ->
                assertNull(page)
                hyperlink.triggeredEvents.add("$seq. CrawlEvent.onLoaded")
                hyperlink.isDone.countDown()
            }
        }
    }

    override var eventHandlers: PageEventHandlers = DefaultPageEventHandlers(
        crawlEventHandlers = MockCrawlEventHandlers(this)
    )

    private val isDone = CountDownLatch(1)
    fun await() = isDone.await()
}

