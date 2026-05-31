package ai.platon.pulsar.boot.autoconfigure

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.browser.manage.BasicBrowserManager
import ai.platon.pulsar.browser.privacy.PrivacyContextMonitor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.proxy.ProxyLoader
import ai.platon.pulsar.common.proxy.ProxyLoaderFactory
import ai.platon.pulsar.common.proxy.ProxyPoolManager
import ai.platon.pulsar.common.proxy.ProxyPoolManagerFactory
import ai.platon.pulsar.common.proxy.impl.LoadingProxyPool
import ai.platon.pulsar.loop.TaskLoops
import ai.platon.pulsar.loop.impl.StreamingTaskLoop
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.protocol.browser.BrowserEmulatorProtocol
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolMonitor
import ai.platon.pulsar.protocol.browser.emulator.BrowserResponseHandler
import ai.platon.pulsar.protocol.browser.emulator.BrowserResponseHandlerFactory
import ai.platon.pulsar.protocol.browser.emulator.IncognitoBrowserFetcher
import ai.platon.pulsar.protocol.browser.emulator.context.MultiPrivacyContextManager
import ai.platon.pulsar.protocol.browser.emulator.impl.InteractiveBrowserEmulator
import ai.platon.pulsar.protocol.browser.emulator.impl.PrivacyManagedBrowserFetcher
import ai.platon.pulsar.protocol.browser.impl.BrowserMonitor
import ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory
import ai.platon.pulsar.ql.context.SQLContext
import ai.platon.pulsar.skeleton.CoreMetrics
import ai.platon.pulsar.skeleton.common.AppStatusTracker
import ai.platon.pulsar.skeleton.common.message.MiscMessageWriter
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import ai.platon.pulsar.skeleton.workflow.common.GlobalCacheFactory
import ai.platon.pulsar.skeleton.workflow.component.BatchFetchComponent
import ai.platon.pulsar.skeleton.workflow.component.LoadComponent
import ai.platon.pulsar.skeleton.workflow.component.ParseComponent
import ai.platon.pulsar.skeleton.workflow.component.UpdateComponent
import ai.platon.pulsar.skeleton.workflow.parse.PageParser
import ai.platon.pulsar.skeleton.workflow.parse.ParseFilters
import ai.platon.pulsar.skeleton.workflow.parse.ParserFactory
import ai.platon.pulsar.skeleton.workflow.parse.html.PrimerHtmlParser
import ai.platon.pulsar.skeleton.workflow.protocol.ProtocolFactory
import ai.platon.pulsar.skeleton.workflow.schedule.DefaultFetchSchedule
import ai.platon.pulsar.tools.TikaParser
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment

@AutoConfiguration
@Import(PulsarContextConfiguration::class)
@Lazy
class PulsarAutoConfiguration {
    @Bean(name = ["conf"])
    @ConditionalOnMissingBean(name = ["conf"])
    fun conf(environment: Environment): MutableConfig {
        return MutableConfig(true).apply {
            this.environment = environment
        }
    }

    @Bean(name = ["webDb"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["webDb"])
    fun webDb(conf: MutableConfig): WebDb {
        return WebDb(conf)
    }

    @Bean(name = ["metricsSystem"], initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["metricsSystem"])
    fun metricsSystem(conf: MutableConfig): MetricsSystem {
        return MetricsSystem(conf)
    }

    @Bean(name = ["messageWriter"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["messageWriter"])
    fun messageWriter(): MiscMessageWriter {
        return MiscMessageWriter()
    }

    @Bean(name = ["coreMetrics"], initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["coreMetrics"])
    fun coreMetrics(messageWriter: MiscMessageWriter, conf: MutableConfig): CoreMetrics {
        return CoreMetrics(messageWriter, conf)
    }

    @Bean(name = ["appStatusTracker"])
    @ConditionalOnMissingBean(name = ["appStatusTracker"])
    fun appStatusTracker(
        metricsSystem: MetricsSystem,
        coreMetrics: CoreMetrics,
        messageWriter: MiscMessageWriter,
    ): AppStatusTracker {
        return AppStatusTracker(metricsSystem, coreMetrics, messageWriter)
    }

    @Bean(name = ["globalCacheFactory"])
    @ConditionalOnMissingBean(name = ["globalCacheFactory"])
    fun globalCacheFactory(conf: MutableConfig): GlobalCacheFactory {
        return GlobalCacheFactory(conf)
    }

    @Bean(name = ["globalCache"])
    @ConditionalOnMissingBean(name = ["globalCache"])
    fun globalCache(globalCacheFactory: GlobalCacheFactory): GlobalCache {
        return globalCacheFactory.globalCache
    }

    @Bean(name = ["taskLoop"])
    @ConditionalOnMissingBean(name = ["taskLoop"])
    fun taskLoop(conf: MutableConfig, pulsarContext: SQLContext): StreamingTaskLoop {
        return StreamingTaskLoop(pulsarContext, conf, "SpringStreamingTaskLoop")
    }

    @Bean(name = ["taskLoops"], destroyMethod = "stop")
    @ConditionalOnMissingBean(name = ["taskLoops"])
    fun taskLoops(taskLoop: StreamingTaskLoop): TaskLoops {
        return TaskLoops(mutableListOf(taskLoop))
    }

    @Bean(name = ["proxyLoaderFactory"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["proxyLoaderFactory"])
    fun proxyLoaderFactory(conf: MutableConfig): ProxyLoaderFactory {
        return ProxyLoaderFactory(conf)
    }

    @Bean(name = ["proxyLoader"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["proxyLoader"])
    fun proxyLoader(proxyLoaderFactory: ProxyLoaderFactory): ProxyLoader {
        return proxyLoaderFactory.get()
    }

    @Bean(name = ["proxyPool"], destroyMethod = "")
    @ConditionalOnMissingBean(name = ["proxyPool"])
    fun proxyPool(proxyLoader: ProxyLoader, conf: MutableConfig): LoadingProxyPool {
        return LoadingProxyPool(proxyLoader, conf)
    }

    @Bean(name = ["proxyPoolManagerFactory"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["proxyPoolManagerFactory"])
    fun proxyPoolManagerFactory(
        proxyPool: LoadingProxyPool,
        conf: MutableConfig,
    ): ProxyPoolManagerFactory {
        return ProxyPoolManagerFactory(proxyPool, conf)
    }

    @Bean(name = ["proxyPoolManager"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["proxyPoolManager"])
    fun proxyPoolManager(proxyPoolManagerFactory: ProxyPoolManagerFactory): ProxyPoolManager {
        return proxyPoolManagerFactory.get()
    }

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
        return browserResponseHandlerFactory.eventHandler
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

    @Bean(name = ["fetchSchedule"])
    @ConditionalOnMissingBean(name = ["fetchSchedule"])
    fun fetchSchedule(conf: MutableConfig, messageWriter: MiscMessageWriter): DefaultFetchSchedule {
        return DefaultFetchSchedule(conf, messageWriter)
    }

    @Bean(name = ["parseFilters"], initMethod = "initialize", destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["parseFilters"])
    fun parseFilters(conf: MutableConfig): ParseFilters {
        return ParseFilters(emptyList(), conf)
    }

    @Bean(name = ["htmlParser"])
    @ConditionalOnMissingBean(name = ["htmlParser"])
    fun htmlParser(parseFilters: ParseFilters, conf: MutableConfig): PrimerHtmlParser {
        return PrimerHtmlParser(parseFilters, conf)
    }

    @Bean(name = ["tikaParser"])
    @ConditionalOnMissingBean(name = ["tikaParser"])
    fun tikaParser(parseFilters: ParseFilters, conf: MutableConfig): TikaParser {
        return TikaParser(parseFilters, conf)
    }

    @Bean(name = ["parserFactory"])
    @ConditionalOnMissingBean(name = ["parserFactory"])
    fun parserFactory(
        htmlParser: PrimerHtmlParser,
        tikaParser: TikaParser,
        conf: MutableConfig,
    ): ParserFactory {
        return ParserFactory(listOf(htmlParser, tikaParser), conf)
    }

    @Bean(name = ["pageParser"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["pageParser"])
    fun pageParser(
        parserFactory: ParserFactory,
        conf: MutableConfig,
    ): PageParser {
        return PageParser(parserFactory = parserFactory, conf = conf)
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

    @Bean(name = ["browserEmulatorProtocol"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["browserEmulatorProtocol"])
    fun browserEmulatorProtocol(
        browserFetcher: IncognitoBrowserFetcher,
    ): BrowserEmulatorProtocol {
        return BrowserEmulatorProtocol(browserFetcher)
    }

    @Bean(name = ["protocolFactory"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["protocolFactory"])
    fun protocolFactory(
        browserEmulatorProtocol: BrowserEmulatorProtocol
    ): ProtocolFactory {
        return ProtocolFactory(listOf(browserEmulatorProtocol))
    }

    @Bean(name = ["fetchComponent"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["fetchComponent"])
    fun fetchComponent(
        webDb: WebDb,
        globalCacheFactory: GlobalCacheFactory,
        protocolFactory: ProtocolFactory,
        coreMetrics: CoreMetrics,
        conf: MutableConfig,
    ): BatchFetchComponent {
        return BatchFetchComponent(webDb, globalCacheFactory, coreMetrics, protocolFactory, conf)
    }

    @Bean(name = ["parseComponent"])
    @ConditionalOnMissingBean(name = ["parseComponent"])
    fun parseComponent(
        pageParser: PageParser,
        globalCacheFactory: GlobalCacheFactory,
        conf: MutableConfig,
    ): ParseComponent {
        return ParseComponent(pageParser, globalCacheFactory, conf)
    }

    @Bean(name = ["updateComponent"])
    @ConditionalOnMissingBean(name = ["updateComponent"])
    fun updateComponent(
        webDb: WebDb,
        fetchSchedule: DefaultFetchSchedule,
        messageWriter: MiscMessageWriter,
        conf: MutableConfig,
    ): UpdateComponent {
        return UpdateComponent(webDb, fetchSchedule, messageWriter, conf)
    }

    @Bean(name = ["loadComponent"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["loadComponent"])
    fun loadComponent(
        webDb: WebDb,
        globalCacheFactory: GlobalCacheFactory,
        fetchComponent: BatchFetchComponent,
        parseComponent: ParseComponent,
        updateComponent: UpdateComponent,
        conf: MutableConfig,
        appStatusTracker: AppStatusTracker,
    ): LoadComponent {
        return LoadComponent(
            webDb = webDb,
            globalCacheFactory = globalCacheFactory,
            fetchComponent = fetchComponent,
            parseComponent = parseComponent,
            updateComponent = updateComponent,
            immutableConfig = conf,
            statusTracker = appStatusTracker,
        )
    }
}
