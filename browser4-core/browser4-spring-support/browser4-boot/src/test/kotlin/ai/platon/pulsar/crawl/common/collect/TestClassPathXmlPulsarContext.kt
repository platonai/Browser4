package ai.platon.pulsar.crawl.common.collect

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.emulator.context.MultiPrivacyContextManager
import ai.platon.pulsar.protocol.browser.emulator.impl.PrivacyManagedBrowserFetcher
import ai.platon.pulsar.skeleton.browser.BrowserManager
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import kotlin.test.*

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

        assertNotNull(context.getBeanOrNull(BrowserManager::class))
        val browserManager = context.getBeanOrNull(BrowserManager::class)
        assertNotNull(browserManager)

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

        Thread.sleep(1000)

        assertNotNull(context.getBeanOrNull(BrowserManager::class))

        val managedBrowsers = context.browserManager.browsers
        assertEquals(1, managedBrowsers.size)

        context.browserManager.close()
        assertEquals(0, managedBrowsers.size)
    }
}
