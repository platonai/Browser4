package ai.platon.pulsar.protocol.browser.emulator.context

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.proxy.ProxyPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.impl.BasicBrowserManager
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserFactory
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserManager
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.AbstractPrivacyManager
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.PrivacyManager

interface BrowserPrivacyManager: PrivacyManager {
    val browserManager: BrowserManager
    val browserFactory: BrowserFactory
    val driverPoolManager: WebDriverPoolManager
    val proxyPoolManager: ProxyPoolManager?
}

abstract class AbstractBrowserPrivacyManager(
    override val driverPoolManager: WebDriverPoolManager,
    override val proxyPoolManager: ProxyPoolManager? = null,
    conf: ImmutableConfig
): BrowserPrivacyManager, AbstractPrivacyManager(conf) {
    override val browserManager: BrowserManager get() = driverPoolManager.browserManager
    override val browserFactory: BrowserFactory get() = driverPoolManager.browserFactory
}
