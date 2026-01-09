package ai.platon.pulsar.manual

import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.context.PulsarContexts
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking

const val PRODUCT_DETAIL_URL = "https://www.amazon.com/dp/B08PP5MSVB"

/**
 * Demonstrates the very basic usage of Browser4.
 * */
fun main() {
    // Use the default browser which has an isolated profile.
    // You can also try other browsers, such as system default, prototype, sequential, temporary, etc.
    PulsarSettings.withDefaultBrowser()

    // Create a pulsar session
    val session = PulsarContexts.createSession()
    // The main url we are playing with
    val url = PRODUCT_DETAIL_URL

    // Open a page with the browser
    val page = session.open(url)

    // Load a page from local storage, or fetch it from the Internet if it does not exist or has expired
    val page2 = session.load(url, "-expires 1d")

    // Submit a url to the URL pool, the submitted url will be processed in a crawl loop
    session.submit(url, "-expires 1d")

    // Parse the page content into a document
    val document = session.parse(page)
    // do something with the document
    // ...

    // Load and parse
    val document2 = session.loadDocument(url, "-expires 1d")
    // do something with the document
    // ...

    // Chat with the page
    val response = runBlocking { session.chat("Tell me something about the page", document) }
    println(response)

    // Load the portal page and then load all links specified by `-outLink`.
    // Option `-outLink` specifies the cssSelector to select links in the portal page to load.
    // Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
    val pages = session.loadOutPages(url, "-expires 1d -itemExpires 1d -outLink a[href~=/dp/] -topLinks 10")

    // Load the portal page and submit the out links specified by `-outLink` to the URL pool.
    // Option `-outLink` specifies the cssSelector to select links in the portal page to submit.
    // Option `-topLinks` specifies the maximal number of links selected by `-outLink`.
    session.submitForOutPages(url, "-expires 1d -itemExpires 7d -outLink a[href~=/dp/] -topLinks 10")

    // Load, parse and scrape fields
    val fields = session.scrape(
        url, "-expires 1d", "#centerCol",
        listOf("#title", "#acrCustomerReviewText")
    )

    // Load, parse and scrape named fields
    val fields2 = session.scrape(
        url, "-i 1d", "#centerCol",
        mapOf("title" to "#title", "reviews" to "#acrCustomerReviewText")
    )

    // Load, parse and scrape named fields
    val fields3 = session.scrapeOutPages(
        url, "-i 1d -ii 1d -outLink a[href~=/dp/] -topLink 10", "#centerCol",
        mapOf("title" to "#title", "reviews" to "#acrCustomerReviewText")
    )

    // Add `-parse` option to activate the parsing subsystem
    val page10 = session.load(url, "-parse -expires 1d")

    // Kotlin suspend calls
    val page11 = runBlocking { session.loadDeferred(url, "-expires 1d") }

    // Java-style async calls
    session.loadAsync(url, "-expires 1d").thenApply(session::parse).thenAccept(session::export)

    println("== document")
    println(document.title)
    println(document.selectFirstOrNull("#title")?.text())

    println("== document2")
    println(document2.title)
    println(document2.selectFirstOrNull("#title")?.text())

    println("== pages")
    println(pages.map { it.url })

    val gson = Gson()
    println("== fields")
    println(gson.toJson(fields))

    println("== fields2")
    println(gson.toJson(fields2))

    println("== fields3")
    println(gson.toJson(fields3))

    // Wait until all tasks are done.
    PulsarContexts.await()
}
