package ai.platon.pulsar.crawl.common.collect

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.emulator.context.MultiPrivacyContextManager
import ai.platon.pulsar.protocol.browser.emulator.impl.PrivacyManagedBrowserFetcher
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserFactory
import ai.platon.pulsar.skeleton.crawl.fetch.driver.BrowserManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestClassPathXmlPulsarContext {

    val context = AgenticContexts.create()

    @Test
    fun whenCloseSession_thenBrowserClosed() {
        assertTrue(context is AbstractPulsarContext)
        val session = context.getOrCreateSession()

        val page = session.load("https://example.com", "-refresh")
        assertNotNull(page)

        val globalCache = context.globalCache
        assertFalse(globalCache.urlPool.hasMore())

        assertNotNull(context.getBeanOrNull(BrowserFactory::class))
        val browserFactory = context.getBeanOrNull(BrowserFactory::class)
        assertNotNull(browserFactory)
        assertNotNull(browserFactory.conf)

        assertNotNull(context.browserFetcher)

        assertNotNull(context.getBeanOrNull(PrivacyManagedBrowserFetcher::class))
        assertNotNull(context.getBeanOrNull(MultiPrivacyContextManager::class))

        assertNotNull(context.getBeanOrNull(WebDriverPoolManager::class))
        val driverPoolManager = context.getBeanOrNull(WebDriverPoolManager::class)
        assertNotNull(driverPoolManager)
        assertNotNull(driverPoolManager.browserManager)
        assertNotNull(driverPoolManager.browserManager.browsers)

        assertNotNull(context.getBeanOrNull(BrowserManager::class))

        session.close()

        Thread.sleep(2000)

        assertNotNull(context.getBeanOrNull(BrowserFactory::class))
        assertNotNull(context.getBeanOrNull(BrowserManager::class))

        val managedBrowsers = context.browserManager.browsers
        assertEquals(0, managedBrowsers.size)
    }
}
