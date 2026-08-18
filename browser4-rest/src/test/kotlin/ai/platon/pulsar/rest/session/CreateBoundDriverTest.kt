package ai.platon.pulsar.rest.session

import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.context.GenericAgenticContext
import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.chrome.Browser4WebDriver
import ai.platon.pulsar.chrome.PulsarBrowser
import ai.platon.pulsar.chrome.PulsarWebDriver
import ai.platon.pulsar.common.config.VolatileConfig
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/**
 * Regression guard for the attach flow: when a browser is already bound to the
 * session (e.g. `attach --cdp` bound an external browser), [createBoundDriver]
 * must create the driver on THAT browser instead of launching a brand-new
 * Browser4 Chrome — launching a new browser silently discards the attached
 * browser's profile (cookies, manual logins).
 */
class CreateBoundDriverTest {

    @Test
    fun createBoundDriverReusesAlreadyBoundBrowser() {
        val context = Mockito.mock(GenericAgenticContext::class.java)
        val session = GenericAgenticSession(context, VolatileConfig(false))

        // Simulate `attach --cdp`: an external browser is bound, no driver yet.
        val boundBrowser = Mockito.mock(PulsarBrowser::class.java)
        session.bindBrowser(boundBrowser)

        val tabDriver = Mockito.mock(PulsarWebDriver::class.java)
        Mockito.`when`(boundBrowser.newDriver()).thenReturn(tabDriver)
        Mockito.`when`(boundBrowser.settings).thenReturn(ai.platon.pulsar.api.model.BrowserSettings())
        Mockito.`when`(tabDriver.guid).thenReturn("test-guid")
        Mockito.`when`(tabDriver.chromeTab).thenReturn(BrowserTab().apply { id = "t1" })
        Mockito.`when`(tabDriver.browserProtocol).thenReturn(Mockito.mock(BrowserProtocol::class.java))
        Mockito.`when`(tabDriver.browser).thenReturn(boundBrowser)

        val driver = session.createBoundDriver()

        assertTrue(driver is Browser4WebDriver, "bound driver should be swapped to Browser4WebDriver")
        assertSame(driver, session.boundDriver, "session must be bound to the new driver")
        // The driver must come from the already-bound browser. If createBoundDriver
        // fell through to browserManager.launch, the unstubbed mocked context would
        // NPE loudly — and this verify would fail.
        Mockito.verify(boundBrowser).newDriver()
    }
}
