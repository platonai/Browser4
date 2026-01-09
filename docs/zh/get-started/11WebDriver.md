WebDriver
=

[WebDriver](/pulsar-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/crawl/fetch/driver/WebDriver.kt) 定义了一个简洁的界面来访问网页并与之交互，所有的动作和行为都经过优化，尽可能地模仿真人，比如滚动、点击、键入文本、拖放等。

该接口中的方法主要分为三类：

1. 对浏览器本身的控制
2. 选择元素，提取文本内容和属性
3. 与网页互动

最主要的方法有：

- .navigateTo(): 加载新网页。
- .scrollDown(): 在网页上向下滚动以完全加载页面。大多数现代网页支持使用 ajax 技术的延迟加载，即网页内容只有在滚动到视图中时才开始加载。
- .pageSource(): 获得网页源代码。

```kotlin
class WebDriverDemo(private val session: PulsarSession) {
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
            text = text.substring(1, 4)

            println("type $text in $searchBoxSelector ...")
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
        driver.scrollToMiddle(0.5f)

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
        println("the page navigated to $url")
    }
}
```

完整代码：[kotlin](/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_8_WebDriver.kt)，[国内镜像](https://gitee.com/platonai_galaxyeye/browser4/blob/1.10.x/examples/browser4-examples/src/main/kotlin/ai/platon/pulsar/manual/_8_WebDriver.kt)。

------

[上一章](10RPA.md) [目录](1home.md) [下一章](12massive-crawling.md)
