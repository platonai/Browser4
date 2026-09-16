package ai.platon.pulsar.protocol.browser.emulator

import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.api.model.WebDriverException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Duration

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

    @Test
    @DisplayName("TabOriginMismatchException is a WebDriverException, so the fetch retires the driver and retries")
    fun tabOriginMismatchExceptionIsWebDriverException() {
        val e = TabOriginMismatchException("Tab origin mismatch: refusing to capture 'b' for fetch 'a'")
        assertTrue(
            e is WebDriverException,
            "the emulator retires the driver and requests a crawl retry only for a WebDriverException"
        )
        assertEquals("Tab origin mismatch: refusing to capture 'b' for fetch 'a'", e.message)
    }

    @Test
    @DisplayName("TabOriginMismatchException stores the driver it refused to capture with")
    fun tabOriginMismatchExceptionStoresDriver() {
        val driver = mock<WebDriver>()
        val e = TabOriginMismatchException("refused", driver)
        assertSame(driver, e.driver, "the guard refusal must carry the taken-over driver so it can be retired")
    }

    @Test
    @DisplayName("a snapshot origin refusal retries promptly, unlike a remote-failure backoff")
    fun snapshotOriginRefusalRetriesPromptly() {
        val delay = crawlRetryDelayFor(TabOriginMismatchException("refused"))
        assertEquals(TAB_ORIGIN_MISMATCH_RETRY_DELAY, delay)

        // The default policy (AbstractTaskRunner.retryDelayPolicy) backs off 30-45s per
        // retry; the prompt delay has to stay below that floor.
        assertTrue(delay!! < Duration.ofSeconds(30), "the refusal retry must not use the backoff: $delay")

        // The initial attempt plus maxRetriesOf (3) retries of a refused fetch have to fit
        // inside the two minutes a caller waits for a task (the swarm API polls a task for
        // two minutes), otherwise the fetch exhausts its retry budget after the caller has
        // already given up and the caller only ever sees a retrying task.
        assertTrue(
            delay.multipliedBy(4) < Duration.ofMinutes(2),
            "3 retries of a refused fetch must fit inside a caller's two-minute wait: $delay"
        )
    }

    @Test
    @DisplayName("other driver failures keep the default retry policy")
    fun otherDriverFailuresKeepTheDefaultRetryPolicy() {
        assertNull(crawlRetryDelayFor(WebDriverException("driver disconnected")))
        assertNull(crawlRetryDelayFor(WebDriverPoolExhaustedException("browser-123", "no drivers")))
        assertNull(crawlRetryDelayFor(RuntimeException("anything else")))
    }
}
