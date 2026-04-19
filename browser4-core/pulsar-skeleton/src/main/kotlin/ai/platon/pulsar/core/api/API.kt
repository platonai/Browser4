package ai.platon.pulsar.core.api

typealias ImmutableConfig = ai.platon.pulsar.common.config.ImmutableConfig
typealias MutableConfig = ai.platon.pulsar.common.config.MutableConfig
typealias VolatileConfig = ai.platon.pulsar.common.config.VolatileConfig

typealias PulsarSettings = ai.platon.pulsar.skeleton.PulsarSettings
typealias LoadOptions = ai.platon.pulsar.skeleton.common.options.LoadOptions

typealias PageEventHandlers = ai.platon.pulsar.skeleton.crawl.PageEventHandlers
typealias CrawlEventHandlers = ai.platon.pulsar.skeleton.crawl.CrawlEventHandlers
typealias LoadEventHandlers = ai.platon.pulsar.skeleton.crawl.LoadEventHandlers
typealias BrowserEventHandlers = ai.platon.pulsar.skeleton.crawl.BrowseEventHandlers

typealias BrowserFactory = ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserFactory
typealias Browser = ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
typealias WebDriver = ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
typealias PageSnapshot = ai.platon.pulsar.persist.PageSnapshot
typealias WebPage = ai.platon.pulsar.persist.WebPage
typealias ProtocolStatus = ai.platon.pulsar.persist.ProtocolStatus
typealias FeaturedDocument = ai.platon.pulsar.dom.FeaturedDocument

typealias PulsarSession = ai.platon.pulsar.skeleton.session.PulsarSession
typealias PulsarContext = ai.platon.pulsar.skeleton.context.PulsarContext
typealias PulsarContexts = ai.platon.pulsar.skeleton.context.PulsarContexts
