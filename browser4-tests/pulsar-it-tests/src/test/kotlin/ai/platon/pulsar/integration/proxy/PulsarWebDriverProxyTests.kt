package ai.platon.pulsar.integration.proxy

import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.proxy.impl.ProxyHubLoader
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.workflow.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.BrowserId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.net.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName

class PulsarWebDriverProxyTests : WebDriverTestBase() {
    private val proxyLoader by lazy { ProxyHubLoader(conf) }
    private val proxyHubUrl = "http://localhost:8192/api/proxies"

    private val proxies = mutableListOf<ProxyEntry>()

    // val ipTestUrl = "https://ip.tool.chinaz.com/"
    // val ipTestUrl = "https://whatismyipaddress.com/"
    val ipTestUrl = "https://www.baidu.com/"
    val browserId = BrowserId.RANDOM_TEMP

    @BeforeEach
    fun setupBrowserContext() {
        PulsarSettings.withSPA()
    }

    @BeforeEach
    fun checkProxyHub() {
        Assumptions.assumeTrue(NetUtil.testHttpNetwork(proxyHubUrl))
        System.setProperty(ProxyHubLoader.PROXY_HUB_URL, proxyHubUrl)

        proxyLoader.loadProxies().toCollection(proxies)
        printlnPro(proxies)

        Assumptions.assumeTrue(proxies.isNotEmpty())
        browserId.setProxy(proxies.random())
    }

    @AfterTest
    fun tearDown() {
        runCatching { FileUtils.deleteDirectory(browserId.userDataDir.toFile()) }.onFailure { printlnPro(it.brief()) }
    }

    @Test
        @DisplayName("When navigate to a HTML page with proxy then success")
    fun whenNavigateAHtmlPageWithProxyThenSuccess() = runWebDriverTest(browserId) { driver ->
        Assumptions.assumeTrue(proxies.isNotEmpty())

        openEnhanced(ipTestUrl, driver, 1)

        val navigateEntry = driver.navigateEntry
        assertTrue { navigateEntry.mainFrameReceived }
        assertTrue { navigateEntry.networkRequestCount.get() > 0 }
        assertTrue { navigateEntry.networkResponseCount.get() > 0 }

        require(driver is AbstractWebDriver)
        assertEquals(200, driver.mainResponseStatus)
        assertTrue { driver.mainResponseStatus == 200 }
        assertTrue { driver.mainResponseHeaders.isNotEmpty() }
        assertEquals(200, navigateEntry.mainResponseStatus)
        assertTrue { navigateEntry.mainResponseStatus == 200 }
        assertTrue { navigateEntry.mainResponseHeaders.isNotEmpty() }

//        assertTrue { browserId.fingerprint.proxyURI?.host == proxyEntry.host }
//        assertTrue { driver.selectFirstTextOrNull(".ipLocalContent")?.contains(proxyEntry.host) == true }

        delay(3000)
    }

    @Test
    fun testProxyAuthorization() {
        val proxyEntry = ProxyEntry("127.0.0.1", 10808, "abc", "abc", Proxy.Type.SOCKS)
        if (!NetUtil.testTcpNetwork(proxyEntry.host, proxyEntry.port)) {
            logger.info(
                "To run this test case, you should rise a local proxy server with proxy: {}",
                proxyEntry.toURI()
            )
            return
        }

        val browserId = BrowserId.RANDOM_TEMP
        browserId.setProxy(proxyEntry)

        val browser = browserFactory.launch(browserId)
        val driver = browser.newDriver()

        runBlocking {
            driver.navigate("https://www.baidu.com/")
            driver.waitForNavigation()
            driver.waitForSelector("body")
            delay(1000)
            val source = driver.pageSource()
            assertTrue { source != null && source.length > 1000 }
        }
    }
}

