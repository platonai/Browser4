package ai.platon.pulsar.crawl.common.collect

import ai.platon.pulsar.boot.autoconfigure.PulsarContextConfiguration
import ai.platon.pulsar.core.api.BrowserManager
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.emulator.context.MultiPrivacyContextManager
import ai.platon.pulsar.protocol.browser.emulator.impl.PrivacyManagedBrowserFetcher
import ai.platon.pulsar.skeleton.context.support.DefaultAnnotationConfigPulsarContext
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class TestAnnotationConfigAgenticContext {

    val context = DefaultAnnotationConfigPulsarContext(PulsarContextConfiguration::class.java)

    @Test
    suspend fun whenCloseSession_thenBrowserClosed() {
        val session = context.getOrCreateSession()

        val page = session.load("https://example.com", "-refresh")
        assertNotNull(page)

        val globalCache = context.globalCache
        assertFalse(globalCache.urlPool.hasMore())

        val browserManager = context.getBeanOrNull(BrowserManager::class)
        assertNotNull(browserManager)
        assertNotNull(context.getBeanOrNull(BrowserManager::class))

        assertNotNull(context.getBeanOrNull(PrivacyManagedBrowserFetcher::class))
        assertNotNull(context.getBeanOrNull(MultiPrivacyContextManager::class))

        assertNotNull(context.getBeanOrNull(WebDriverPoolManager::class))
        val driverPoolManager = context.getBeanOrNull(WebDriverPoolManager::class)
        assertNotNull(driverPoolManager)
        requireNotNull(driverPoolManager)
        assertNotNull(driverPoolManager.browserManager)
        assertNotNull(driverPoolManager.browserManager.browsers)

        assertNotNull(context.getBeanOrNull(BrowserManager::class))

        session.close()

        delay(1000.milliseconds)

        assertNotNull(context.getBeanOrNull(BrowserManager::class))

        val managedBrowsers = context.browserManager.browsers
        assertEquals(1, managedBrowsers.size)

        context.browserManager.close()
        assertEquals(0, managedBrowsers.size)
    }
}
