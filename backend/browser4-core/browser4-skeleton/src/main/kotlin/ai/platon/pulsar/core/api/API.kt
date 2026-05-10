package ai.platon.pulsar.core.api

import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.event.CrawlEventHandlers
import ai.platon.pulsar.skeleton.event.LoadEventHandlers
import ai.platon.pulsar.skeleton.event.PageEventHandlers

typealias ImmutableConfig = ai.platon.pulsar.common.config.ImmutableConfig
typealias MutableConfig = ai.platon.pulsar.common.config.MutableConfig
typealias VolatileConfig = ai.platon.pulsar.common.config.VolatileConfig

typealias InteractSettings = ai.platon.browser4.driver.common.InteractSettings
typealias PulsarSettings = ai.platon.pulsar.skeleton.PulsarSettings
typealias LoadOptions = ai.platon.pulsar.skeleton.common.options.LoadOptions

typealias PageEventHandlers = PageEventHandlers
typealias CrawlEventHandlers = CrawlEventHandlers
typealias LoadEventHandlers = LoadEventHandlers
typealias BrowserEventHandlers = BrowseEventHandlers

typealias BrowserManager = ai.platon.pulsar.skeleton.workflow.fetch.driver.BrowserManager
typealias Browser = ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
typealias WebDriver = ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
typealias PageSnapshot = ai.platon.pulsar.persist.PageSnapshot
typealias WebPage = ai.platon.pulsar.persist.WebPage
typealias ProtocolStatus = ai.platon.pulsar.persist.ProtocolStatus
typealias FeaturedDocument = ai.platon.pulsar.dom.FeaturedDocument

typealias PulsarSession = ai.platon.pulsar.skeleton.session.PulsarSession
typealias PulsarContext = ai.platon.pulsar.skeleton.context.PulsarContext
typealias PulsarContexts = ai.platon.pulsar.skeleton.context.PulsarContexts
