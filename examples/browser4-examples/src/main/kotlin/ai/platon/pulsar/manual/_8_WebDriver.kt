package ai.platon.pulsar.manual

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import org.slf4j.LoggerFactory

internal class WebDriverDemo(private val session: AgenticSession = AgenticContexts.createSession()) {
    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val searchBoxSelector = ".form input[type=text]"
    private val searchBoxSubmit = ".form input[type=submit]"

    val fieldSelectors = mutableMapOf(
        "title" to "#productTitle",
        "reviews" to "#acrCustomerReviewText",
        "prodDetails" to "#prodDetails"
    )

    fun options(args: String): LoadOptions {
        val options = session.options(args)

        val be = options.eventHandlers.browseEventHandlers

        be.onDocumentFullyLoaded.addLast { page, driver ->
            fieldSelectors.values.forEach { interact1(it, driver) }
        }

        be.onDocumentFullyLoaded.addLast { page, driver ->
            interact2(driver)
        }

        be.onDidInteract.addLast { page, driver ->
            logger.info("Did the interaction")
        }

        return options
    }

    private suspend fun interact1(selector: String, driver: WebDriver) {
        if (driver.exists(selector)) {
            println("click $selector ...")
            driver.click(selector)

            println("select first text by $selector ...")
            var text = driver.selectFirstTextOrNull(selector) ?: "no-text"
            text = text.replace("\\s+", " ").trim().take(5)

            println("type `$text` in $searchBoxSelector ...")
            driver.type(searchBoxSelector, text)
        }
    }

    private suspend fun interact2(driver: WebDriver) {
        val selector = "#productTitle"

        println("bring the page to front ...")
        driver.bringToFront()

        println("scroll to the bottom of the page ...")
        driver.scrollToBottom()
        println("bounding box of body: " + driver.boundingBox("body"))

        println("scroll to the middle of the page ...")
        driver.scrollToMiddle(0.5)

        println("click $selector ...")
        driver.click(selector)

        println("query text of $selector ...")
        var text = driver.selectFirstTextOrNull(selector) ?: "no-text"
        text = text.substring(1, 4)
        println("type `$text` in $searchBoxSelector")
        driver.type(searchBoxSelector, text)

        println("capture screenshot over $selector ...")
        driver.captureScreenshot(selector)

        println("evaluate 1 + 1 ...")
        val result = driver.evaluate("1 + 1")
        require(result is Int)
        println("evaluate 1 + 1 returns $result")

        println("wheel down for 5 times ...")
        driver.mouseWheelDown(5, delayMillis = 2000)

        println("scroll to top ...")
        driver.mouseWheelDown(5, delayMillis = 2000)
        driver.scrollToTop()

        println("search ...")
        text = "Vincent Willem van Gogh"
        println("type `$text` in $searchBoxSelector")
        driver.type(searchBoxSelector, text)
        driver.click(searchBoxSubmit)
        val url = driver.currentUrl()
        println("page navigated to $url")

        println("Using plain language, tell the browser to click $selector ...")
        // Only works when LLM is configured
        session.bindDriver(driver)
        session.plainActs("scroll to middle")
    }
}

/**
 * Demonstrates how to use a web driver to interact with the page.
 * */
fun main() {
    val url = PRODUCT_DETAIL_URL
    val args = "-refresh -parse"
    // Set the system to work with single page applications (SPA)
    val session = AgenticContexts.createSession()
    val crawler = WebDriverDemo(session)
    session.load(url, crawler.options(args))
}
