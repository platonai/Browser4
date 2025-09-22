package ai.platon.pulsar.browser

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.browser.common.SimpleScriptConfuser
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.persist.model.ActiveDOMMetadata
import ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import kotlin.test.assertNotNull

@SpringBootTest(classes = [Application::class], webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class WebDriverTestBase : TestBase() {

    companion object {
        val browserFactory = DefaultBrowserFactory()
        lateinit var browser: Browser

        @JvmStatic
        @BeforeAll
        fun initBrowser() {
            browser = browserFactory.launchRandomTempBrowser()
            browser.newDriver()
        }

        @JvmStatic
        @AfterAll
        fun closeBrowser() {
            browser.close()
        }
    }

    @Value("\${server.port}")
    val port: Int = 0

//    @Value("\${server.servlet.context-path}")
//    val contextPath: String = "/api"

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    open val webDriverService get() = WebDriverService(browserFactory)

    protected val logger = getLogger(this)
    protected val warnUpUrl = "https://www.amazon.com/"
    protected val originUrl = "https://www.amazon.com/"
    protected val productUrl = "https://www.amazon.com/dp/B0C1H26C46"
    protected val resourceUrl2 = "https://www.amazon.com/robots.txt"

    protected val baseURL get() = "http://127.0.0.1:$port"

    protected val assetsBaseURL get() = "http://127.0.0.1:$port/assets"

    protected val assetsPBaseURL get() = "http://127.0.0.1:$port/assets-p"

    protected val generatedAssetsBaseURL get() = "http://127.0.0.1:$port/generated"

    protected val interactiveUrl get() = "$generatedAssetsBaseURL/interactive-1.html"

    protected val multiScreensInteractiveUrl get() = "$generatedAssetsBaseURL/interactive-screens.html"

    /**
     * @see [ai.platon.pulsar.test.server.MockSiteController.text]
     * */
    protected val plainTextUrl get() = "$baseURL/text"
    /**
     * @see [ai.platon.pulsar.test.server.MockSiteController.csv]
     * */
    protected val csvTextUrl get() = "$baseURL/csv"
    /**
     * @see [ai.platon.pulsar.test.server.MockSiteController.json]
     * */
    protected val jsonUrl get() = "$baseURL/json"
    /**
     * @see [ai.platon.pulsar.test.server.MockSiteController.robots]
     * */
    protected val robotsUrl get() = "$baseURL/robots.txt"
    /**
     * @see [ai.platon.pulsar.test.server.MockSiteController.amazonHome]
     * */
    protected val mockAmazonHomeUrl get() = "$baseURL/amazon/home.htm"
    /**
     * @see [ai.platon.pulsar.test.server.MockSiteController.amazonProduct]
     * */
    protected val mockAmazonProductUrl get() = "$baseURL/amazon/product.htm"

    protected val walmartUrl = "https://www.walmart.com/ip/584284401"
    protected val asin get() = productUrl.substringAfterLast("/dp/")

    protected val expressions = """
            typeof(window)
            
            typeof(window.history)
            window.history
            window.history.length
            
            typeof(document)
            document.location
            document.baseURI
            
            typeof(document.body)
            document.body.clientWidth
            
            typeof(__pulsar_)
            __pulsar_utils__.add(1, 1)
        """.trimIndent().split("\n").map { it.trim() }.filter { it.isNotBlank() }

    val settings get() = BrowserSettings(session.sessionConfig)
    val confuser get() = settings.confuser as SimpleScriptConfuser

    suspend fun evaluateExpressions(driver: WebDriver, type: String) {
        expressions.forEach { expression ->
            val detail = driver.evaluateDetail(expression)
            println(String.format("%-6s%-40s%s", type, expression, detail))
        }
    }

    protected fun runWebDriverTest(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(url, browser, block)

    protected fun runResourceWebDriverTest(url: String, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runResourceWebDriverTest(url, block)

    protected fun runResourceWebDriverTest(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runResourceWebDriverTest(url, browser, block)

    protected fun runWebDriverTest(block: suspend (driver: WebDriver) -> Unit) = webDriverService.runWebDriverTest(block)

    protected fun runWebDriverTest(browserId: BrowserId, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(browserId, block)

    protected fun runWebDriverTest(browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(browser, block)

    protected suspend fun open(url: String, driver: WebDriver, scrollCount: Int = 3) = webDriverService.open(url, driver, scrollCount)

    protected suspend fun openResource(url: String, driver: WebDriver, scrollCount: Int = 1) = webDriverService.openResource(url, driver, scrollCount)

    protected suspend fun computeActiveDOMMetadata(driver: WebDriver): ActiveDOMMetadata {
        val detail = driver.evaluateDetail("JSON.stringify(__pulsar_utils__.computeMetadata())")
        println(detail)
        assertNotNull(detail)
        assertNotNull(detail.value)
        println(detail.value)
        val data = requireNotNull(detail.value?.toString())
        return pulsarObjectMapper().readValue(data)
    }
}
