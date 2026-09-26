package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.api.model.NavigateEntry
import ai.platon.pulsar.common.B4Constants
import ai.platon.pulsar.common.config.MutableConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

/**
 * Policy tests for the CDP focus emulation of [Browser4WebDriver] — the fix for the tab that a CDP
 * driver creates reporting itself as an unfocused background tab, which a bot detector reads as a
 * headless browser (see [Browser4WebDriver.ensureFocusEmulation] for the measurement).
 *
 * Only the policy is pinned here: one command per driver whichever hook fires first, a latch on
 * transports that reject the command, and no protocol traffic at all when
 * `browser.focus.emulation=false`. What the emulation does to a real page — `visibilityState`,
 * `document.hidden`, `document.hasFocus()` — is pinned by `NavigatorStealthE2ETest`.
 */
@Tag("Unit")
@DisplayName("Browser4WebDriver focus emulation policy")
class Browser4WebDriverFocusEmulationTest {

    private val focusCommand = Browser4WebDriver.FOCUS_EMULATION_CDP

    private val focusParams = mapOf("enabled" to true)

    private fun driverWith(protocol: BrowserProtocol, settings: BrowserSettings = BrowserSettings()): Browser4WebDriver {
        val browser = mock<PulsarBrowser>()
        whenever(browser.settings).thenReturn(settings)
        return Browser4WebDriver("test", BrowserTab(), protocol, browser)
    }

    private fun emulationSucceeds(protocol: BrowserProtocol) {
        wheneverBlocking { protocol.executeCdpCommand(focusCommand, focusParams) }
            .thenReturn(emptyMap<String, Any?>())
    }

    @Test
    @DisplayName("the emulation is sent once, however often the driver navigates and evaluates")
    fun emulationIsSentOncePerDriver(): Unit = runBlocking {
        val protocol = mock<BrowserProtocol>()
        emulationSucceeds(protocol)
        val driver = driverWith(protocol)

        // Both hooks are exercised: a reused tab is re-visited by navigating, a driver bound to an
        // already-loaded tab reaches the page by evaluating.
        runCatching { driver.navigate(NavigateEntry("about:blank")) }
        runCatching { driver.evaluate("1 + 1") }
        runCatching { driver.evaluate("2 + 2") }
        runCatching { driver.navigate(NavigateEntry("about:blank")) }

        verify(protocol, times(1)).executeCdpCommand(focusCommand, focusParams)
    }

    @Test
    @DisplayName("a transport that rejects the emulation is remembered, so it is never retried")
    fun rejectedEmulationIsLatchedAndNeverRetried(): Unit = runBlocking {
        val protocol = mock<BrowserProtocol>()
        wheneverBlocking { protocol.executeCdpCommand(focusCommand, focusParams) }
            .thenThrow(IllegalStateException("this transport does not implement Emulation"))
        val driver = driverWith(protocol)

        runCatching { driver.evaluate("1 + 1") }
        runCatching { driver.evaluate("2 + 2") }
        runCatching { driver.navigate(NavigateEntry("about:blank")) }

        verify(protocol, times(1)).executeCdpCommand(focusCommand, focusParams)
    }

    @Test
    @DisplayName("browser.focus.emulation=false leaves the protocol alone entirely")
    fun switchOffNeverTouchesTheProtocol(): Unit = runBlocking {
        val protocol = mock<BrowserProtocol>()
        val config = MutableConfig(loadDefaults = true).apply { set(B4Constants.FOCUS_EMULATION, "false") }
        val driver = driverWith(protocol, BrowserSettings(config))

        runCatching { driver.evaluate("1 + 1") }
        runCatching { driver.navigate(NavigateEntry("about:blank")) }

        verify(protocol, never()).executeCdpCommand(focusCommand, focusParams)
    }
}