package ai.platon.browser4.boot.plugin

import ai.platon.browser4.api.manage.BasicBrowserManager
import ai.platon.browser4.api.model.BrowserSettings
import ai.platon.pulsar.browser.privacy.PrivacyContextMonitor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.proxy.ProxyPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolMonitor
import ai.platon.pulsar.protocol.browser.emulator.BrowserResponseHandler
import ai.platon.pulsar.protocol.browser.emulator.BrowserResponseHandlerFactory
import ai.platon.pulsar.protocol.browser.emulator.context.MultiPrivacyContextManager
import ai.platon.pulsar.protocol.browser.emulator.impl.InteractiveBrowserEmulator
import ai.platon.pulsar.protocol.browser.emulator.impl.PrivacyManagedBrowserFetcher
import ai.platon.pulsar.protocol.browser.impl.BrowserMonitor
import ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory
import ai.platon.pulsar.skeleton.CoreMetrics
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Browser runtime plugin wiring for browser4-browser.
 *
 * Enabled by default and can be disabled with `browser.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["browser.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
class BrowserPluginAutoConfiguration {
    @Bean(name = ["browserSettings"])
    @ConditionalOnMissingBean(name = ["browserSettings"])
    fun browserSettings(conf: MutableConfig): BrowserSettings {
        return BrowserSettings(conf)
    }

    @Bean(name = ["browserFactory"])
    @ConditionalOnMissingBean(name = ["browserFactory"])
    fun browserFactory(conf: MutableConfig): DefaultBrowserFactory {
        return DefaultBrowserFactory(conf)
    }

    @Bean(name = ["browserManager"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["browserManager"])
    fun browserManager(browserFactory: DefaultBrowserFactory, conf: MutableConfig): BasicBrowserManager {
        return BasicBrowserManager(browserFactory, conf)
    }

    @Bean(name = ["driverPoolManager"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["driverPoolManager"])
    fun driverPoolManager(browserManager: BasicBrowserManager, conf: MutableConfig): WebDriverPoolManager {
        return WebDriverPoolManager(browserManager, conf, false)
    }

    @Bean(name = ["privacyManager"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["privacyManager"])
    fun privacyManager(
        proxyPoolManager: ProxyPoolManager,
        driverPoolManager: WebDriverPoolManager,
        coreMetrics: CoreMetrics,
        conf: MutableConfig,
    ): MultiPrivacyContextManager {
        return MultiPrivacyContextManager(driverPoolManager, proxyPoolManager, coreMetrics, conf)
    }

    @Bean(name = ["browserResponseHandlerFactory"])
    @ConditionalOnMissingBean(name = ["browserResponseHandlerFactory"])
    fun browserResponseHandlerFactory(conf: MutableConfig): BrowserResponseHandlerFactory {
        return BrowserResponseHandlerFactory(conf)
    }

    @Bean(name = ["browserResponseHandler"])
    @ConditionalOnMissingBean(name = ["browserResponseHandler"])
    fun browserResponseHandler(
        browserResponseHandlerFactory: BrowserResponseHandlerFactory,
    ): BrowserResponseHandler {
        return browserResponseHandlerFactory.browserResponseHandler
    }

    @Bean(name = ["browserEmulator"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["browserEmulator"])
    fun browserEmulator(
        driverPoolManager: WebDriverPoolManager,
        browserResponseHandler: BrowserResponseHandler,
        conf: MutableConfig,
    ): InteractiveBrowserEmulator {
        return InteractiveBrowserEmulator(driverPoolManager, browserResponseHandler, conf)
    }

    @Bean(name = ["browserFetcher"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["browserFetcher"])
    fun browserFetcher(
        browserManager: BasicBrowserManager,
        privacyManager: MultiPrivacyContextManager,
        browserEmulator: InteractiveBrowserEmulator,
        conf: MutableConfig,
    ): PrivacyManagedBrowserFetcher {
        return PrivacyManagedBrowserFetcher(browserManager, privacyManager, browserEmulator, conf, false)
    }

    @Bean(name = ["privacyContextMonitor"], initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["privacyContextMonitor"])
    fun privacyContextMonitor(privacyManager: MultiPrivacyContextManager): PrivacyContextMonitor {
        return PrivacyContextMonitor(privacyManager, 30, 30)
    }

    @Bean(name = ["driverPoolMonitor"], initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["driverPoolMonitor"])
    fun driverPoolMonitor(
        driverPoolManager: WebDriverPoolManager,
        conf: MutableConfig,
    ): WebDriverPoolMonitor {
        return WebDriverPoolMonitor(driverPoolManager, conf, 30, 30)
    }

    @Bean(name = ["browserMonitor"], initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["browserMonitor"])
    fun browserMonitor(browserManager: BasicBrowserManager): BrowserMonitor {
        return BrowserMonitor(browserManager, 30, 30)
    }
}
