package ai.platon.pulsar.manual

import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.PlainUrl
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import ai.platon.pulsar.skeleton.crawl.common.url.CompletableListenableHyperlink
import ai.platon.pulsar.skeleton.crawl.common.url.ListenableHyperlink
import ai.platon.pulsar.skeleton.crawl.common.url.ParsableHyperlink

/**
 * Demonstrates various URLs in Browser4.
 * */
fun main() {
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    PulsarSettings.withDefaultBrowser()

    // Create a pulsar session
    val session = PulsarContexts.createSession()
    // The main url we are playing with
    val url = PRODUCT_DETAIL_URL

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
    val hyperlink = Hyperlink(url, "", args = "-expires 10s -itemExpires 10s")
    val pages = session.loadOutPages(hyperlink, "-outLink a[href~=/dp/] -topLinks 5")
    println("Hyperlink's out pages are loaded | " + pages.size)

    // Load the portal page and submit the out links specified by `-outLink` to the URL pool.
    // Option `-outLink` specifies the cssSelector to select links in the portal page to submit.
    // Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
    val hyperlink2 = Hyperlink(url, "", args = "-expires 1d -itemExpires 7d")
    session.submitForOutPages(hyperlink2, "-outLink a[href~=/dp/] -topLinks 5")

    //
    // 3. ParsableHyperlink
    //

    // Load a webpage and parse it into a document
    val parsableHyperlink = ParsableHyperlink(url) { p, doc ->
        println("Parsed " + doc.baseURI)
    }
    val page2 = session.load(parsableHyperlink, "-expires 10s")
    println("ParsableHyperlink loaded | " + page2.url)

    //
    // 4. ListenableHyperlink
    //

    // Load a ListenableHyperlink so we can register various event handlers
    val listenableLink = ListenableHyperlink(url, "")
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
        eventHandlers.loadEventHandlers.onLoaded.addLast { complete(it) }
    }
    session.submit(completableListenableHyperlink, "-expires 10s")
    val page4 = completableListenableHyperlink.join()
    println("CompletableListenableHyperlink loaded | " + page4.url)

    // Wait until all tasks are done.
    PulsarContexts.await()
}
