package ai.platon.pulsar

import ai.platon.browser4.driver.chrome.dom.CDPSnapshotService
import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.PageTarget
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.browser4.driver.chrome.dom.util.DomDebug
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.browser4.driver.common.SimpleScriptConfuser
import ai.platon.pulsar.browser.FastWebDriverService
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserFactory
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import ai.platon.pulsar.util.server.EnableMockServerApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.atomic.AtomicBoolean

@SpringBootTest(
    classes = [EnableMockServerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT
)
open class WebDriverTestBase : MockSiteAccess() {

    companion object {
        val isInitialized = AtomicBoolean(false)
        lateinit var browser: Browser

        @JvmStatic
        @AfterAll
        fun closeBrowser() {
            if (isInitialized.compareAndSet(true, false)) {
                browser.close()
            }
        }
    }

    @BeforeEach
    fun initBrowser() {
        synchronized(isInitialized) {
            if (isInitialized.compareAndSet(false, true)) {
                browser = browserFactory.launchRandomTempBrowser()
                browser.newDriver()
            }
        }
    }

    val browserFactory
        get() = context.getBeanOrNull(BrowserFactory::class) ?: DefaultBrowserFactory(session.configuration)

    open val webDriverService get() = FastWebDriverService(browserFactory)

    val settings get() = BrowserSettings(session.sessionConfig)
    val confuser get() = settings.confuser as SimpleScriptConfuser

    /**
     * Run webdriver test with the default browser.
     * */
    protected fun runEnhancedWebDriverTest(
        url: String, block: suspend (driver: WebDriver) -> Unit
    ) = webDriverService.runEnhancedWebDriverTest(url, browser, block)

    /**
     * Run webdriver test with the default browser.
     * */
    protected fun runEnhancedWebDriverTest(block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runEnhancedWebDriverTest(browser, block)

    /**
     * Run webdriver test with a specified browser.
     * */
    protected fun runEnhancedWebDriverTest(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runEnhancedWebDriverTest(url, browser, block)

    /**
     * Run webdriver test with a specified browser.
     * */
    protected fun runEnhancedWebDriverTest(browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runEnhancedWebDriverTest(browser, block)

    /**
     * Run webdriver test with a newly created browser with the given browser profile.
     * */
    protected fun runWebDriverTest(browserId: BrowserId, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(browserId, block)

    protected fun runWebDriverTest(url: String, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(url, block)

    protected fun runWebDriverTest(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(url, browser, block)

    protected suspend fun openEnhanced(url: String, driver: WebDriver, scrollCount: Int = 3) =
        webDriverService.openEnhanced(url, driver, scrollCount)

    protected suspend fun open(url: String, driver: WebDriver, scrollCount: Int = 1) =
        webDriverService.open(url, driver, scrollCount)

    // Helper to DFS find the first node by id in the enhanced tree
    protected fun findNodeById(root: MergedDOMTreeNode?, id: String): MergedDOMTreeNode? {
        root ?: return null
        if (root.attributes["id"] == id) return root
        root.children.forEach { findNodeById(it, id)?.let { return it } }
        root.shadowRoots.forEach { findNodeById(it, id)?.let { return it } }
        root.contentDocument?.let { findNodeById(it, id)?.let { return it } }
        return null
    }

    protected suspend fun collectEnhancedRoot(service: CDPSnapshotService, options: SnapshotOptions): MergedDOMTreeNode {
        repeat(3) { attempt ->
            val t = service.buildTargetTrees(target = PageTarget(), options = options)
            // Best-effort summary for diagnostics
            printlnPro(DomDebug.summarize(t))
            val r = service.buildMergedDOMTreeNode(t)
            if (r.children.isNotEmpty() || attempt == 2) return r
            Thread.sleep(300)
        }
        return service.buildMergedDOMTreeNode(service.buildTargetTrees(PageTarget(), options))
    }
}
