package ai.platon.pulsar.protocol.browser.emulator

import ai.platon.pulsar.api.model.WebDriverException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Unit")
@Tag("Fast")
@DisplayName("Exception classes")
class ExceptionsTest {

    @Test
    @DisplayName("NavigateTaskCancellationException default constructor")
    fun navigateTaskCancellationExceptionDefaultConstructor() {
        val e = NavigateTaskCancellationException()
        assertNull(e.message)
        assertNull(e.cause)
    }

    @Test
    @DisplayName("NavigateTaskCancellationException with message")
    fun navigateTaskCancellationExceptionWithMessage() {
        val e = NavigateTaskCancellationException("navigation aborted")
        assertEquals("navigation aborted", e.message)
        assertNull(e.cause)
    }

    @Test
    @DisplayName("NavigateTaskCancellationException with message and cause")
    fun navigateTaskCancellationExceptionWithMessageAndCause() {
        val cause = RuntimeException("root cause")
        val e = NavigateTaskCancellationException("navigation aborted", cause)
        assertEquals("navigation aborted", e.message)
        assertSame(cause, e.cause)
    }

    @Test
    @DisplayName("NavigateTaskCancellationException with cause only")
    fun navigateTaskCancellationExceptionWithCauseOnly() {
        val cause = RuntimeException("root cause")
        val e = NavigateTaskCancellationException(cause)
        assertSame(cause, e.cause)
    }

    @Test
    @DisplayName("NavigateTaskCancellationException is an IllegalStateException")
    fun navigateTaskCancellationExceptionIsIllegalStateException() {
        val e = NavigateTaskCancellationException("test")
        assertTrue(e is IllegalStateException, "NavigateTaskCancellationException should extend IllegalStateException")
    }

    @Test
    @DisplayName("WebDriverPoolException stores browserId and message")
    fun webDriverPoolExceptionStoresBrowserIdAndMessage() {
        val e = WebDriverPoolException("browser-123", "pool is retired")
        assertEquals("browser-123", e.browserId)
        assertEquals("pool is retired", e.message)
    }

    @Test
    @DisplayName("WebDriverPoolException is a WebDriverException")
    fun webDriverPoolExceptionIsWebDriverException() {
        val e = WebDriverPoolException("browser-123", "test")
        assertTrue(e is WebDriverException, "WebDriverPoolException should extend WebDriverException")
    }

    @Test
    @DisplayName("WebDriverPoolExhaustedException extends WebDriverPoolException")
    fun webDriverPoolExhaustedExtendsWebDriverPoolException() {
        val e = WebDriverPoolExhaustedException("browser-456", "no drivers available")
        assertTrue(e is WebDriverPoolException, "WebDriverPoolExhaustedException should extend WebDriverPoolException")
        assertEquals("browser-456", e.browserId)
        assertEquals("no drivers available", e.message)
    }

    @Test
    @DisplayName("WebDriverPoolExhaustedException is a WebDriverException")
    fun webDriverPoolExhaustedIsWebDriverException() {
        val e = WebDriverPoolExhaustedException("browser-789", "exhausted")
        assertTrue(e is WebDriverException, "WebDriverPoolExhaustedException should extend WebDriverException")
    }
}
