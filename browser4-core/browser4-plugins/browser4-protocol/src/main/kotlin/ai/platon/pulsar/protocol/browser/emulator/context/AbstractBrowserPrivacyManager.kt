package ai.platon.pulsar.protocol.browser.emulator.context

import ai.platon.browser4.api.BrowserManager
import ai.platon.browser4.api.privacy.AbstractPrivacyManager
import ai.platon.browser4.api.privacy.PrivacyManager
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.proxy.ProxyPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager

interface BrowserPrivacyManager : PrivacyManager {
    val browserManager: BrowserManager
    val driverPoolManager: WebDriverPoolManager
    val proxyPoolManager: ProxyPoolManager?
}

abstract class AbstractBrowserPrivacyManager(
    override val driverPoolManager: WebDriverPoolManager,
    override val proxyPoolManager: ProxyPoolManager? = null,
    conf: ImmutableConfig
) : BrowserPrivacyManager, AbstractPrivacyManager(conf) {
    override val browserManager: BrowserManager get() = driverPoolManager.browserManager
}
