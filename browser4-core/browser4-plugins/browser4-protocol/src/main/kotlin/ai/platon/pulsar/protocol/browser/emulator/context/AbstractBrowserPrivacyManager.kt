package ai.platon.pulsar.protocol.browser.emulator.context

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.proxy.ProxyPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.skeleton.browser.BrowserManager
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.AbstractPrivacyManager
import ai.platon.pulsar.skeleton.workflow.fetch.privacy.PrivacyManager

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
