package ai.platon.pulsar.skeleton.workflow.fetch.driver

import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.browser.AbstractWebDriver
import ai.platon.pulsar.common.AppFiles
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.emoji.PopularEmoji
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.persist.model.ActiveDOMMessage
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.nio.file.Path
import java.text.MessageFormat
import java.util.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@Tag("Slow")
@Tag("E2ETest")
open class PulsarWebDriverE2ETest : WebDriverTestBase() {

    private val fieldSelectors = mapOf(
        "01productTitle" to "#productTitle",
        "02acrPopover" to "#acrPopover",
        "03acrCustomerReviewText" to "#acrCustomerReviewText",
        "04productOverview" to "#productOverview_feature_div",
        "05featureBullets" to "#featurebullets_feature_div",
        "06prodDetails" to "#prodDetails",
        "07customerReviews" to "#reviewsMedley",
        "08review1" to "#cm-cr-dp-review-list div[data-hook=review]:nth-child(1)",
        "09review2" to "#cm-cr-dp-review-list div[data-hook=review]:nth-child(2)",
        "10review3" to "#cm-cr-dp-review-list div[data-hook=review]:nth-child(3)",
        "11review4" to "#cm-cr-dp-review-list div[data-hook=review]:nth-child(4)",
        "12review5" to "#cm-cr-dp-review-list div[data-hook=review]:nth-child(5)",
    )

    private val screenshotDir = AppPaths.TEST_DIR.resolve("screenshot")

    @BeforeTest
    fun setup() {
        logger.warn("Tests may fall because of page layout changing")

        session.globalCache.resetCaches()
    }

    @AfterTest
    fun tearDown() {
        session.globalCache.resetCaches()
    }

    @Test
    @Disabled("Feature not implemented yet: mainRequestCookies may not be captured if RequestWillBeSentExtraInfo is not received yet")
    @DisplayName("When navigate to a HTML page then the navigate state are correct")
    fun whenNavigateAHtmlPageThenTheNavigateStateAreCorrect() = runEnhancedWebDriverTest(browser) { driver ->
        openEnhanced(e2eProductUrl, driver, 1)

        val navbarMain = driver.selectFirstTextOrNull("#navbar-main")
        val title = driver.selectFirstTextOrNull("#productTitle")
        Assumptions.assumeTrue { navbarMain != null || title != null }

        val navigateEntry = driver.navigateEntry
        assertTrue("Expect mainFrameReceived") { navigateEntry.mainFrameReceived }
        assertTrue { navigateEntry.networkRequestCount.get() > 0 }
        assertTrue { navigateEntry.networkResponseCount.get() > 0 }

        require(driver is AbstractWebDriver)
        assertEquals(200, driver.mainResponseStatus)
        assertTrue { driver.mainResponseStatus == 200 }
        assertTrue { driver.mainResponseHeaders.isNotEmpty() }
        assertEquals(200, navigateEntry.mainResponseStatus)
        assertTrue { navigateEntry.mainResponseStatus == 200 }
        assertTrue { navigateEntry.mainResponseHeaders.isNotEmpty() }
    }

    @Test
    @DisplayName("When navigate to a HTML page then mainRequestCookies are captured")
    fun whenNavigateAHtmlPageThenMainRequestCookiesAreCaptured() = runEnhancedWebDriverTest(browser) { driver ->
        // Navigate to the page with cookies
        openEnhanced(e2eProductUrl, driver, 1)

        val navbarMain = driver.selectFirstTextOrNull("#navbar-main")
        val title = driver.selectFirstTextOrNull("#productTitle")
        Assumptions.assumeTrue { navbarMain != null || title != null }

        val navigateEntry = driver.navigateEntry
        assertTrue("Expect mainFrameReceived") { navigateEntry.mainFrameReceived }

        // Verify that mainRequestCookies are captured
        // Note: mainRequestCookies may be empty if no cookies are sent with the request
        // or if RequestWillBeSentExtraInfo is not received yet
        require(driver is AbstractWebDriver)
        val mainRequestCookies = driver.mainRequestCookies

        // Log the cookies for debugging
        printlnPro("mainRequestCookies: $mainRequestCookies")

        // The cookies should be a list (may be empty depending on the site)
        assertNotNull(mainRequestCookies)
    }

    @Test
    @DisplayName("when open a HTML page then script is injected")
    fun whenOpenAHtmlPageThenScriptIsInjected() = runEnhancedWebDriverTest(e2eOriginUrl, browser) { driver ->
        var detail = driver.evaluateDetail("typeof(window)")
        printlnPro(detail)
        // assertNotNull(detail?.value)

        detail = driver.evaluateDetail("typeof(document)")
        printlnPro(detail)
        // assertNotNull(detail?.value)

        val r = driver.evaluate("__pulsar_utils__.add(1, 1)")
        assertEquals(2, r)

        detail = driver.evaluateDetail("JSON.stringify(__pulsar_CONFIGS)")
        val value = detail?.value?.toString()
        assertNotNull(value)
        printlnPro(value)
        assertTrue { value.contains("viewPortWidth") }

        detail = driver.evaluateDetail("JSON.stringify(__pulsar_utils__.getConfig())")
        val value2 = detail?.value?.toString()
        assertNotNull(value2)
        printlnPro(value2)
        assertTrue { value2.contains("viewPortWidth") }
    }

    @Test
    @DisplayName("open a HTML page and compute metadata")
    fun openAHtmlPageAndComputeMetadata() = runEnhancedWebDriverTest(e2eOriginUrl, browser) { driver ->
        driver.evaluate("__pulsar_utils__.scrollToMiddle()")
        var detail = driver.evaluateDetail("__pulsar_utils__.compute()")
        printlnPro(detail)

        detail = driver.evaluateDetail("__pulsar_utils__.getActiveDomMessage()")
        printlnPro(detail)
        val data = detail?.value?.toString()
        assertNotNull(data)

        val message = pulsarObjectMapper().readValue<ActiveDOMMessage>(data)
        val urls = message.urls
        assertNotNull(urls)
        assertEquals(e2eOriginUrl, urls.URL)

        val metadata = message.metadata
        assertNotNull(metadata)
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(metadata))
        assertEquals(1920, metadata.viewPortWidth)
        assertEquals(1080, metadata.viewPortHeight)
        // Assumptions.assumeTrue(metadata.scrollTop > metadata.viewPortHeight)
        assertTrue { metadata.scrollTop >= 0 }
        assertTrue { metadata.scrollLeft.toInt() == 0 }
        assertTrue { metadata.clientWidth > 0 } // 1683 on my laptop
        assertTrue { metadata.clientHeight > 0 } // 986 on my laptop
    }

    @Test
    fun test_selectAttributeAll() = runEnhancedWebDriverTest(browser) { driver ->
        driver.navigate(e2eProductUrl)
        val navbarMain = driver.selectFirstTextOrNull("#navbar-main")
        val title = driver.selectFirstTextOrNull("#productTitle")
        Assumptions.assumeTrue { navbarMain != null || title != null }

        val selector = "body a[href]"
        driver.waitForSelector(selector)

        printlnPro("Selecting attributes: ")

        var links = driver.selectAttributeAll(selector, "href")
        assertTrue { links.isNotEmpty() }

        printlnPro("Top 10 href: ")
        links.take(10).forEach { printlnPro(it) }

        links = driver.selectAttributeAll(selector, "abs:href")
        printlnPro("NOTE: abs:href not supported by WebDriver.selectAttributeXXX()")
        printlnPro("Abs:href: ")
        links.forEach { printlnPro(it) }
        // assertTrue { links.isEmpty() }
    }

    @Test
    @Ignore("Disabled temporarily")
    fun testClickTextMatches() = runEnhancedWebDriverTest(browser) { driver ->
        openEnhanced(e2eProductUrl, driver, 1)
        val navbarMain = driver.selectFirstTextOrNull("#navbar-main")
        val title = driver.selectFirstTextOrNull("#productTitle")
        Assumptions.assumeTrue { navbarMain != null || title != null }

//        driver.waitForSelector("a[href*=stores]")
        driver.waitForSelector("a[href*=iphone]")

        // should match the anchor text "Brand: iphone"
//        driver.clickTextMatches("a[href*=stores]", "Store")
        driver.clickTextMatches("a[href*=iphone]", "iphone")
        driver.waitForNavigation()
        driver.waitForSelector("body")

        // expected url like: https://www.amazon.com/stores/Apple/page/77D9E1F7-0337-4282-9DB6-B6B8FB2DC98D?ref_=ast_bln
        val currentUrl = driver.currentUrl()
        printlnPro("The page should be redirected")
        printlnPro("Current url: $currentUrl")

        val pageSource = driver.pageSource()
        Assumptions.assumeTrue { (pageSource?.length ?: 0) > 1000 }
        Assumptions.assumeTrue { pageSource?.contains("iphone", ignoreCase = true) == true }

        assertNotEquals(e2eProductUrl, currentUrl)
        // assertContains(currentUrl, "iphone", ignoreCase = true)
    }

    @Test
    fun testMouseMove() = runEnhancedWebDriverTest(mockAmazonProductUrl, browser) { driver ->
        repeat(10) { i ->
            val x = 100.0 + 2 * i
            val y = 100.0 + 3 * i

            driver.mouseMove(x, y)

            delay(500.milliseconds)
        }
    }

    @Test
    fun testMouseWheel() = runEnhancedWebDriverTest(mockAmazonProductUrl, browser) { driver ->
        driver.mouseWheel(5.0)
        val box = driver.boundingBox("body")
        printlnPro(box)
        assertNotNull(box)

        delay(3000.milliseconds)

        driver.mouseWheelUp(5)

        val box2 = driver.boundingBox("body")
        printlnPro(box2)
        assertNotNull(box2)
        // assertTrue { box2.height > box.height }
    }

    @Test
    fun testKeyPress() = runEnhancedWebDriverTest(browser) { driver ->
        driver.navigate(e2eProductUrl)
        delay(1000.milliseconds)

        val navbarMain = driver.selectFirstTextOrNull("#navbar-main")
        val title = driver.selectFirstTextOrNull("#productTitle")
        Assumptions.assumeTrue { navbarMain != null || title != null }

        driver.waitForSelector("#productTitle")

        assertTrue { driver.exists("#productTitle") }

        var text = driver.selectFirstTextOrNull("#productTitle")
        printlnPro("Product title: $text")

        // val selector = "#nav-search input[placeholder*=Search]"
        val selector = "form input[type=text]"
        text = driver.selectFirstAttributeOrNull(selector, "placeholder")
        printlnPro("Search bar - placeholder - 1 - driver.selectFirstAttributeOrNull() : <$text>")

        text = driver.selectFirstPropertyValueOrNull(selector, "placeholder")
        printlnPro("Search bar - placeholder - 2 - driver.selectFirstPropertyValueOrNull() : <$text>")
        assertTrue("Placeholder should not be empty") { !text.isNullOrBlank() }

        "iphone".forEach { ch ->
            driver.press("$ch", selector)
        }
        driver.press("Digit6", selector)
        driver.press("0", selector)

        delay(1000.milliseconds)

        text = driver.selectFirstPropertyValueOrNull(selector, "value")
        printlnPro("Search bar value (should not be empty) - 1: <$text>")
        assertEquals("iphone60", text)

        MessageFormat.format("{0} key pressed {0}", PopularEmoji.SPARKLES).also { printlnPro(it) }

        var evaluate = driver.evaluateDetail("document.querySelector('$selector').value")
        printlnPro("Search bar evaluate result - driver.evaluateDetail() : $evaluate")
        printlnPro("Search bar value - driver.evaluateDetail() : <${evaluate?.value}>")
        // assertEquals("Mate60", evaluate?.value)

        text = driver.selectAttributeAll(selector, "value").joinToString()
        printlnPro("Search bar value - 3 - selectAttributeAll() : <$text>")
//            assertEquals("Mate60", text)

        val html = driver.outerHTML(selector)
        printlnPro("Search bar html: >>>\n$html\n<<<")
        assertNotNull(html)
        // assertTrue { html.contains("Mate60") }

        evaluate = driver.evaluateDetail("document.querySelector('$selector').value")
        printlnPro("Search bar evaluate result - driver.evaluateDetail() : >>>\n$evaluate\n<<<")
        printlnPro("Search bar value - driver.evaluateDetail() : <${evaluate?.value}>")
        // assertEquals("Mate60", evaluate?.value)

        // TODO: FIXME: enter seems not working
        driver.press("Enter", selector)
        driver.waitForNavigation()
        assertTrue { driver.currentUrl() != e2eProductUrl }
    }

    @Test
    @Tag("ManualOnly")
    fun testTypeText() = runEnhancedWebDriverTest(browser) { driver ->
        driver.navigate(e2eProductUrl)
        driver.waitForSelector("#productTitle")

        assertTrue { driver.exists("#productTitle") }

        var text = driver.selectFirstTextOrNull("#productTitle")
        printlnPro("Product title: $text")

        // val selector = "#nav-search input[placeholder*=Search]"
        val selector = "form input[type=text]"
        text = driver.selectFirstAttributeOrNull(selector, "placeholder")
        printlnPro("Search bar - placeholder - 1 - driver.selectFirstAttributeOrNull() : <$text>")
        assertTrue("Placeholder should not be empty") { !text.isNullOrBlank() }
        text = driver.selectAttributeAll(selector, "placeholder").joinToString()
        printlnPro("Search bar - placeholder - 2 - driver.selectAttributeAll() : <$text>")
        assertTrue("Placeholder should not be empty") { text.isNotBlank() }

        text = driver.selectAttributeAll(selector, "value").joinToString()
        printlnPro("Search bar value (should be empty) - 1: <$text>")
        assertEquals("", text)

        driver.type("Mate60", selector)

        MessageFormat.format("{0} text typed {0}", PopularEmoji.SPARKLES).also { printlnPro(it) }

        var evaluate = driver.evaluateDetail("document.querySelector('$selector').value")
        printlnPro("Search bar evaluate result - driver.evaluateDetail() : $evaluate")
        printlnPro("Search bar value - driver.evaluateDetail() : <${evaluate?.value}>")
        assertEquals("Mate60", evaluate?.value)

        text = driver.selectAttributeAll(selector, "value").joinToString()
        printlnPro("Search bar value - 3: $text")
//            assertEquals("Mate60", text)

        val html = driver.outerHTML(selector)
        printlnPro("Search bar html: $html")
        assertNotNull(html)
// assertTrue { html.contains("Mate60") }

        evaluate = driver.evaluateDetail("document.querySelector('$selector').value")
        printlnPro("Search bar evaluate result - driver.evaluateDetail() : $evaluate")
        printlnPro("Search bar value - driver.evaluateDetail() : <${evaluate?.value}>")
        assertEquals("Mate60", evaluate?.value)

        val lastUrl = driver.currentUrl()

        // TODO: FIXME: enter seems not working
        driver.press("Enter", selector)
        driver.waitForNavigation(oldUrl = lastUrl)
        // assertTrue { driver.currentUrl() != lastUrl }
        val currentUrl = driver.currentUrl()
        Assumptions.assumingThat(currentUrl != lastUrl) {
            printlnPro("Current url: $currentUrl, last url: $lastUrl")
        }
    }

    @Test
    fun testCaptureScreenshot() = runEnhancedWebDriverTest(e2eProductUrl, browser) { driver ->
        val navbarMain = driver.selectFirstTextOrNull("#navbar-main")
        val title = driver.selectFirstTextOrNull("#productTitle")
        Assumptions.assumeTrue { navbarMain != null || title != null }

        driver.waitForSelector("#productTitle")
        assertTrue { driver.exists("body") }
        val pageSource = driver.pageSource()
        assertNotNull(pageSource)
        assertTrue { pageSource.contains(asin) }

        val paths = mutableListOf<Path>()
        fieldSelectors.entries.take(3).forEach { (name, selector) ->
            val screenshot = driver.runCatching { screenshot(selector) }
                .onFailure { logger.info("Failed to screenshot | $name - $selector") }
                .getOrNull()

            if (screenshot != null) {
                val path = exportScreenshot("$name.jpg", screenshot)
                paths.add(path)
                delay(1000.milliseconds)
            }
        }

        if (paths.isNotEmpty()) {
            printlnPro(String.format("%d screenshots are saved | %s", paths.size, paths[0].parent))
        }

        // assertTrue { paths.isNotEmpty() }
    }

    @Test
    @DisplayName("When call queryClientRects then return client rects")
    fun whenCallQueryClientRectsThenReturnClientRects() = runEnhancedWebDriverTest(e2eProductUrl, browser) { driver ->
        val navbarMain = driver.selectFirstTextOrNull("#navbar-main")
        val title = driver.selectFirstTextOrNull("#productTitle")
        Assumptions.assumeTrue { navbarMain != null || title != null }

        driver.mouseWheelDown(5)
        val box = driver.boundingBox("body")
        // RectD(x=0.0, y=-600.0, width=1912.0, height=10538.828125)
        printlnPro(box)
        assertNotNull(box)

        delay(3000.milliseconds)

        driver.mouseWheelUp(5)

        val box2 = driver.boundingBox("body")
        // RectD(x=0.0, y=-150.0, width=1912.0, height=10538.828125)
        printlnPro(box2)
        assertNotNull(box2)

        var jsFun = "__pulsar_utils__.queryClientRects"
        var bodyInfo = driver.evaluate("$jsFun('body')")?.toString() ?: "unexpected"
        // [{0 0 1912 10538.8}, ]
        printlnPro("queryClientRects: $bodyInfo")

        jsFun = "__pulsar_utils__.queryClientRect"
        bodyInfo = driver.evaluate("$jsFun('body')")?.toString() ?: "unexpected"
        // [{0 0 1912 10538.8}, ]
        printlnPro("queryClientRect: $bodyInfo")

        bodyInfo = driver.evaluate("document.body.scrollWidth")?.toString() ?: "unexpected"
        // [{0 0 1912 10538.8}, ]
        printlnPro("body.scrollWidth: $bodyInfo")

        bodyInfo = driver.evaluate("document.body.clientWidth")?.toString() ?: "unexpected"
        // [{0 0 1912 10538.8}, ]
        printlnPro("body.clientWidth: $bodyInfo")
    }

    @Throws(IOException::class)
    private fun exportScreenshot(filename: String, screenshot: String): Path {
        val path = screenshotDir.resolve(filename)
        val bytes = Base64.getDecoder().decode(screenshot)
        return AppFiles.saveTo(bytes, path, true)
    }
}
