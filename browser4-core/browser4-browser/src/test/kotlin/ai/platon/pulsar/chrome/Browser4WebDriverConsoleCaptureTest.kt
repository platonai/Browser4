package ai.platon.pulsar.chrome

import ai.platon.cdt.kt.protocol.events.console.MessageAdded
import ai.platon.cdt.kt.protocol.support.types.EventListener
import ai.platon.cdt.kt.protocol.types.console.ConsoleMessage
import ai.platon.cdt.kt.protocol.types.console.ConsoleMessageLevel
import ai.platon.cdt.kt.protocol.types.console.ConsoleMessageSource
import ai.platon.pulsar.api.BrowserProtocol
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.model.BrowserTab
import ai.platon.pulsar.common.B4Constants
import ai.platon.pulsar.common.config.MutableConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

/**
 * Policy tests for the CDP console capture of [Browser4WebDriver] — the behaviour that decides
 * whether the page stays untouched (`Console.enable` plus `Console.messageAdded`) or the historical
 * page-side buffer ([Browser4WebDriver.consoleMessagesJs] / [Browser4WebDriver.consoleClearJs]) is
 * used instead.
 *
 * The buffer's own semantics are covered by [ConsoleMessageBufferTest]; this class pins the policy:
 * one `Console.enable` per driver, one subscription, a latch on transports that cannot enable the
 * domain, and no protocol traffic at all when `browser.console.capture=false`.
 */
@DisplayName("Browser4WebDriver console capture policy")
class Browser4WebDriverConsoleCaptureTest {

    private fun driverWith(protocol: BrowserProtocol, settings: BrowserSettings = BrowserSettings()): Browser4WebDriver {
        val browser = mock<PulsarBrowser>()
        whenever(browser.settings).thenReturn(settings)
        return Browser4WebDriver("test", BrowserTab(), protocol, browser)
    }

    /** Capture the single handler the driver registers, so messages can be fed to the buffer. */
    private fun captureHandlers(protocol: BrowserProtocol): MutableList<suspend (MessageAdded) -> Unit> {
        val handlers = mutableListOf<suspend (MessageAdded) -> Unit>()
        whenever(protocol.onConsoleMessageAdded(any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            handlers += it.arguments[0] as suspend (MessageAdded) -> Unit
            mock<EventListener>()
        }
        return handlers
    }

    private fun consoleMessage(
        level: ConsoleMessageLevel,
        text: String,
        source: ConsoleMessageSource = ConsoleMessageSource.CONSOLE_API,
    ): ConsoleMessage = ConsoleMessage(source = source, level = level, text = text)

    private fun enableSucceeds(protocol: BrowserProtocol) {
        wheneverBlocking { protocol.executeCdpCommand("Console.enable", null) }.thenReturn(emptyMap<String, Any?>())
    }

    @Test
    @DisplayName("capture enables the Console domain once and subscribes once, however often it is read")
    fun captureEnablesOnceAndSubscribesOnce(): Unit = runBlocking {
        val protocol = mock<BrowserProtocol>()
        captureHandlers(protocol)
        enableSucceeds(protocol)
        val driver = driverWith(protocol)

        driver.consoleMessages("info")
        driver.consoleMessages("debug")
        driver.consoleClear()

        verify(protocol, times(1)).executeCdpCommand("Console.enable", null)
        verify(protocol, times(1)).onConsoleMessageAdded(any())
    }

    @Test
    @DisplayName("an unusable Console domain is remembered, so the enable is never retried")
    fun failedEnableIsLatchedAndNeverSubscribes(): Unit = runBlocking {
        val protocol = mock<BrowserProtocol>()
        wheneverBlocking { protocol.executeCdpCommand("Console.enable", null) }
            .thenThrow(IllegalStateException("this transport does not implement Console.enable"))
        val driver = driverWith(protocol)

        // The fallback itself runs page-side JS through the mocked protocol; only the policy is asserted.
        runCatching { driver.consoleMessages("info") }
        runCatching { driver.consoleMessages("info") }

        verify(protocol, times(1)).executeCdpCommand("Console.enable", null)
        verify(protocol, never()).onConsoleMessageAdded(any())
    }

    @Test
    @DisplayName("browser.console.capture=false leaves the protocol alone entirely")
    fun switchOffNeverTouchesTheProtocol(): Unit = runBlocking {
        val protocol = mock<BrowserProtocol>()
        val config = MutableConfig(loadDefaults = true).apply { set(B4Constants.CONSOLE_CAPTURE_CDP, "false") }
        val driver = driverWith(protocol, BrowserSettings(config))

        runCatching { driver.consoleMessages("info") }
        runCatching { driver.consoleClear() }

        verify(protocol, never()).executeCdpCommand("Console.enable", null)
        verify(protocol, never()).onConsoleMessageAdded(any())
    }

    @Test
    @DisplayName("buffered messages are served from the buffer and filtered by level and source")
    fun bufferedMessagesAreServedFromTheBuffer(): Unit = runBlocking {
        val protocol = mock<BrowserProtocol>()
        val handlers = captureHandlers(protocol)
        enableSucceeds(protocol)
        val driver = driverWith(protocol)

        driver.consoleMessages("info") // starts the capture and registers the single listener
        assertEquals(1, handlers.size, "the driver must register exactly one listener")

        val handler = handlers.single()
        handler(MessageAdded(consoleMessage(ConsoleMessageLevel.LOG, "hello-log")))
        handler(MessageAdded(consoleMessage(ConsoleMessageLevel.ERROR, "hello-error")))
        handler(MessageAdded(consoleMessage(ConsoleMessageLevel.LOG, "from-network", ConsoleMessageSource.NETWORK)))

        val info = driver.consoleMessages("info")?.value?.toString().orEmpty()
        assertTrue(info.contains("hello-log"), "log must be listed at info: $info")
        assertTrue(info.contains("hello-error"), "error must be listed at info: $info")
        assertFalse(info.contains("from-network"), "only console-api entries are console messages: $info")

        val errorOnly = driver.consoleMessages("error")?.value?.toString().orEmpty()
        assertTrue(errorOnly.contains("hello-error"), "error must be listed at error: $errorOnly")
        assertFalse(errorOnly.contains("hello-log"), "log must not be listed at error: $errorOnly")
    }

    @Test
    @DisplayName("clearing drops the buffered messages and keeps the capture")
    fun clearDropsTheBufferAndKeepsCapturing(): Unit = runBlocking {
        val protocol = mock<BrowserProtocol>()
        val handlers = captureHandlers(protocol)
        enableSucceeds(protocol)
        val driver = driverWith(protocol)

        driver.consoleMessages("info")
        val handler = handlers.single()
        handler(MessageAdded(consoleMessage(ConsoleMessageLevel.LOG, "before-clear")))

        val cleared = driver.consoleClear()
        assertEquals(Browser4WebDriver.CONSOLE_CLEARED_MESSAGE, cleared?.value?.toString())

        handler(MessageAdded(consoleMessage(ConsoleMessageLevel.LOG, "after-clear")))
        val listed = driver.consoleMessages("info")?.value?.toString().orEmpty()
        assertFalse(listed.contains("before-clear"), "clear must drop what was buffered: $listed")
        assertTrue(listed.contains("after-clear"), "capture must continue after a clear: $listed")

        verify(protocol, times(1)).executeCdpCommand("Console.enable", null)
    }
}
