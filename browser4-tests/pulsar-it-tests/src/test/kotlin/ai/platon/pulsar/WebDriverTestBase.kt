package ai.platon.pulsar

import ai.platon.browser4.boot.autoconfigure.Browser4AutoConfiguration
import ai.platon.browser4.chrome.dom.CDPSnapshotService
import ai.platon.browser4.chrome.dom.util.DomDebug
import ai.platon.browser4.api.BrowserId
import ai.platon.browser4.api.model.BrowserSettings
import ai.platon.browser4.api.scripting.SimpleScriptConfuser
import ai.platon.browser4.api.manage.BrowserFactory
import ai.platon.browser4.api.model.MergedDOMTreeNode
import ai.platon.browser4.api.model.PageTarget
import ai.platon.browser4.api.model.SnapshotOptions
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.core.api.Browser
import ai.platon.pulsar.core.api.WebDriver
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.annotation.Import
import java.util.concurrent.atomic.AtomicBoolean

@Import(Browser4AutoConfiguration::class)
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

    /**
     * The browser factory used for browser provisioning.
     * Tries to resolve from Spring context first, falls back to the one from BrowserManager.
     */
    open val browserFactory: BrowserFactory
        get() = context.getBeanOrNull(BrowserFactory::class)
            ?: context.browserManager.browserFactory

    open val webDriverService get() = FastWebDriverService(browserFactory)

    val settings get() = BrowserSettings(session.sessionConfig)
    val confuser get() = settings.confuser as SimpleScriptConfuser

    /**
     * Run webdriver test with the default browser.
     * */
    protected fun runWebDriverTestAndCompute(
        url: String, block: suspend (driver: WebDriver) -> Unit
    ) = webDriverService.runWebDriverTestAndCompute(url, browser, block)

    /**
     * Run webdriver test with the default browser.
     * */
    protected fun runWebDriverTestAndCompute(block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTestAndCompute(browser, block)

    /**
     * Run webdriver test with a specified browser.
     * */
    protected fun runWebDriverTestAndCompute(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTestAndCompute(url, browser, block)

    /**
     * Run webdriver test with a specified browser.
     * */
    protected fun runWebDriverTestAndCompute(browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTestAndCompute(browser, block)

    /**
     * Run webdriver test with a newly created browser with the given browser profile.
     * */
    protected fun runWebDriverTest(browserId: BrowserId, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(browserId, block)

    protected fun runWebDriverTest(url: String, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(url, block)

    protected fun runWebDriverTest(url: String, browser: Browser, block: suspend (driver: WebDriver) -> Unit) =
        webDriverService.runWebDriverTest(url, browser, block)

    protected suspend fun openAndCompute(url: String, driver: WebDriver, scrollCount: Int = 3) =
        webDriverService.openAndCompute(url, driver, scrollCount)

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

    protected suspend fun collectEnhancedRoot(
        service: CDPSnapshotService,
        options: SnapshotOptions
    ): MergedDOMTreeNode {
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
