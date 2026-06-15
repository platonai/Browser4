package ai.platon.browser4.chrome.detail

import ai.platon.browser4.chrome.PulsarWebDriver
import ai.platon.browser4.chrome.util.CDPReturnError
import ai.platon.browser4.chrome.util.ChromeDriverException
import ai.platon.browser4.chrome.util.ChromeIOException
import ai.platon.browser4.chrome.util.ChromeRPCException
import ai.platon.pulsar.browser.AbstractWebDriver
import ai.platon.pulsar.browser.WebDriver
import ai.platon.pulsar.browser.common.BrowserUnavailableException
import ai.platon.pulsar.browser.common.IllegalWebDriverStateException
import ai.platon.pulsar.browser.common.WebDriverException
import ai.platon.pulsar.browser.common.WebDriverUnavailableException
import ai.platon.pulsar.browser.impl.NodeRef
import ai.platon.pulsar.common.*
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class RobustRPC(
    private val driver: WebDriver
) {
    companion object {
        private val logger = getLogger(this)

        // handle to many exceptions
        private val exceptionCounts = ConcurrentHashMap<Long, AtomicInteger>()
        private val exceptionMessages = ConcurrentHashMap<Long, String>()

        var MAX_RPC_FAILURES = 5
    }

    val rpcFailures = AtomicInteger()
    var maxRPCFailures = MAX_RPC_FAILURES

    /**
     * Invoke an executable block without return value.
     * */
    @Throws(ChromeDriverException::class)
    suspend fun invoke0(action: String, block: suspend () -> Unit) {
        invokeWithRetry(action, block = block)
    }

    /**
     * Invoke an executable block.
     * */
    @Throws(ChromeDriverException::class)
    suspend fun <T> invoke(action: String, block: suspend () -> T): T? {
        return invokeWithRetry(action, block = block)
    }

    /**
     * Invoke an executable block on the page with retry on transient CDP failures (max 2 retries).
     *
     * **Important:** The [block] should be idempotent or safe to re-execute, as it may be retried
     * on transient CDP failures. Avoid placing stateful lookups (e.g., fetching navigation history
     * to compute a target index) inside the block — capture the target state before calling this
     * method and use the captured value inside the block instead.
     */
    @Throws(ChromeDriverException::class)
    suspend fun <T> invokeOnPage(
        action: String,
        url: String? = null,
        message: String? = null,
        block: suspend () -> T
    ): T? {
        try {
            return invokeWithRetry(action, url = url, block = block)
        } catch (e: ChromeDriverException) {
            interceptChromeException(e, action, message)
            throw e
        }
    }

    @Throws(ChromeDriverException::class)
    suspend fun <T> invokeOnElement(
        selector: String,
        action: String,
        focus: Boolean = false,
        scrollIntoView: Boolean = false,
        message: String? = null,
        block: suspend (NodeRef) -> T
    ): T? {
        require(driver is PulsarWebDriver)

        try {
            return invokeWithRetry(action) {
                val node = if (focus) {
                    driver.page.focusOnSelector(selector)
                } else if (scrollIntoView) {
                    driver.page.scrollIntoViewIfNeeded(selector)
                } else {
                    driver.page.dom.queryLocator(selector)
                }

                if (node != null) {
                    block(node)
                } else {
                    null
                }
            }
        } catch (e: ChromeDriverException) {
            interceptChromeException(e, action, "selector: [$selector], focus: $focus, scrollIntoView: $scrollIntoView")
            throw e
        }
    }

    @Throws(ChromeDriverException::class)
    suspend fun <T> predicateOnPage(
        action: String,
        url: String? = null,
        message: String? = null,
        block: suspend () -> T
    ): Boolean = invokeOnPage(action, url, message, block) != null

    @Throws(ChromeDriverException::class)
    suspend fun predicateOnElement(
        selector: String,
        action: String,
        focus: Boolean = false,
        scrollIntoView: Boolean = false,
        message: String? = null,
        predicate: suspend (NodeRef) -> Boolean
    ): Boolean = invokeOnElement(selector, action, focus, scrollIntoView, message, predicate) == true

    @Throws(WebDriverException::class)
    private suspend fun <T> invokeWithRetry(
        action: String,
        maxRetry: Int = 2,
        url: String? = null,
        block: suspend () -> T
    ): T? {
        require(driver is AbstractWebDriver)

        if (driver.quickCheckHealthy(action).isNotOK) {
            return null
        }

        var result = runCatching { invokeDeferred0(action, url, block) }
            .onFailure {
                logger.info(
                    "Oop, a bit slip-up executing action: [$action], retrying 1/$maxRetry time ... | {}", it.brief()
                )
            }

        // If quick check fails, the driver is likely dead, no need to retry
        var i = 1
        while (result.isFailure && i++ < maxRetry && driver.quickCheckHealthy().isOK) {
            val healthy = checkHealthy(driver)
            if (healthy.isNotOK) {
                throw WebDriverUnavailableException(
                    "Driver is unhealthy, cannot execute action: [$action]" +
                            " | state: ${driver.readableState} | healthy: $healthy"
                ) as Throwable
            }

            val exception = result.exceptionOrNull()
            // Check if this is a permanent error that shouldn't be retried
            if (exception != null && !isRetryableException(exception)) {
                logger.warn("Encountered non-retryable exception: [$action], aborting retries | {}", exception.message)
                break
            }
            delay(200.milliseconds)
            result = runCatching { invokeDeferred0(action, url, block) }
                .onFailure { logger.warn("Exception to execute action: [$action], retrying $i/$maxRetry times", it) }
        }

        return if (driver.quickCheckHealthy(action).isOK) {
            result.getOrElse { throw it }
        } else {
            // If quick check fails, the driver is likely dead, return null to avoid further exceptions
            null
        }
    }

    @Throws(BrowserUnavailableException::class, WebDriverUnavailableException::class)
    private suspend fun checkHealthy(driver: WebDriver): CheckState {
        require(driver is AbstractWebDriver)

        val healthy = driver.healthy()
        if (!healthy.isOK) {
            logger.warn("Driver {} is unhealthy | state: {} | healthy: {}", driver.id, driver.readableState, healthy)
            throw WebDriverUnavailableException("Driver ${driver.id} is unhealthy | state: ${driver.readableState} | healthy: $healthy")
        }

        return healthy
    }

    /**
     * Check if an exception is retryable. Returns false for permanent errors that
     * will not be resolved by retrying, such as:
     * - Invalid URL errors
     * - Invalid parameter errors
     * - etc.
     */
    private fun isRetryableException(e: Throwable): Boolean {
        // Non-retryable BrowserProtocol errors
        if (e is CDPReturnError) {
            val errorMessage = e.errorMessage?.lowercase() ?: ""
            val message = e.message?.lowercase() ?: ""

            // List of error messages that indicate permanent failures
            val permanentErrorPatterns = listOf(
                "cannot navigate to invalid url",
                "invalid url",
                "unsupported url scheme",
                "cannot find context",
                "invalid parameter",
                "unsupported operation",
                "target closed"
            )

            return permanentErrorPatterns.none { pattern ->
                errorMessage.contains(pattern) || message.contains(pattern)
            }
        }

        // ChromeRPCException with specific error codes might also be non-retryable
        if (e is ChromeRPCException) {
            // Add specific error codes here if needed
            // For now, assume other RPC errors are retryable
        }

        // Default: assume unknown exceptions are retryable
        return true
    }

    @Throws(ChromeDriverException::class)
    suspend fun <T> invokeSilently(action: String, message: String? = null, block: suspend () -> T): T? {
        return try {
            invoke(action, block)
        } catch (e: ChromeRPCException) {
            interceptChromeException(e, action, message)
            null
        }
    }

    @Throws(ChromeDriverException::class)
    suspend fun <T> invokeDeferredSilently(
        action: String, url: String? = null, message: String? = null, maxRetry: Int = 2, block: suspend () -> T
    ): T? {
        return try {
            invokeWithRetry(action, maxRetry, url, block)
        } catch (e: ChromeRPCException) {
            interceptChromeException(e, action, message)
            null
        }
    }

    @Throws(IllegalWebDriverStateException::class, ChromeDriverException::class)
    suspend fun interceptChromeException(e: ChromeDriverException, action: String? = null, message: String? = null) {
        when (e) {
            is ChromeIOException -> {
                handleChromeIOException(e, action, message)
            }

            is ChromeRPCException -> {
                handleChromeRPCException(e, action, message)
            }

            else -> throw e
        }
    }

    @Throws(BrowserUnavailableException::class, IllegalWebDriverStateException::class)
    suspend fun handleChromeIOException(e: ChromeIOException, action: String? = null, message: String? = null) {
        if (!AppContext.isActive) {
            logger.info("Ignored chrome IO exception because of system shutting down")
            return
        }

        checkHealthy(driver)
    }

    @Throws(IllegalWebDriverStateException::class)
    fun handleChromeRPCException(e: ChromeRPCException, action: String? = null, message: String? = null) {
        if (rpcFailures.get() > maxRPCFailures) {
            logger.warn("Too many RPC failures: {} ({}/{}) | {}", action, rpcFailures, maxRPCFailures, e.message)
            throw IllegalWebDriverStateException("Too many RPC failures", driver = driver)
        }

        val count = exceptionCounts.computeIfAbsent(e.code) { AtomicInteger() }.get()
        traceException(e)

        if (count < 10L) {
            logException(count, e, action, message)
        } else if (count < 100L && count % 10 == 0) {
            logException(count, e, action, message)
        } else if (count < 1000L && count % 50 == 0) {
            logException(count, e, action, message)
        }
    }

    @Throws(ChromeRPCException::class)
    private suspend fun <T> invokeDeferred0(action: String, url: String? = null, block: suspend () -> T): T? {
        require(driver is AbstractWebDriver)

        if (driver.quickCheckHealthy(action).isNotOK) {
            return null
        }

        try {
            return block().also { decreaseRPCFailures() }
        } catch (e: ChromeRPCException) {
            increaseRPCFailures()
            fixCDTAgentIfNecessary(e)
            e.url = url
            throw e
        }
    }

    @Throws(ChromeIOException::class)
    private suspend fun fixCDTAgentIfNecessary(e: Exception) {
        require(driver is PulsarWebDriver)
        if (e.toString().contains("agent was not enabled")) {
            logger.warn(e.stringify())
            try {
                driver.enableAPIAgents()
            } catch (ex: Exception) {
                throw ChromeIOException("Failed to enable CDT agents", e)
            }
            decreaseRPCFailures()
        }
    }

    private fun decreaseRPCFailures() {
        rpcFailures.getAndUpdate { it.dec().coerceAtLeast(0) }
    }

    private fun increaseRPCFailures() {
        rpcFailures.incrementAndGet()
    }

    /**
     * Normalize message, remove all digits
     * */
    private fun normalizeMessage(message: String?): String {
        if (message == null) {
            return ""
        }

        return message.filterNot { it.isDigit() }
    }

    private fun traceException(e: ChromeRPCException) {
        val code = e.code
        exceptionCounts.computeIfAbsent(code) { AtomicInteger() }.incrementAndGet()
        exceptionMessages[code] = normalizeMessage(e.message)
    }

    private fun logException(count: Int, e: ChromeRPCException, action: String? = null, message: String? = null) {
        if (message == null) {
            logger.info(
                "{}.\t[{}] ({}/{}) | code={}, exception_message={} | url={}",
                count, action, rpcFailures, maxRPCFailures, e.code, e.message, e.url
            )
        } else {
            logger.info(
                "{}.\t[{}] ({}/{}) | message={} | code={}, exception_message={} | url={}",
                count, action, rpcFailures, maxRPCFailures, message, e.code, e.message, e.url
            )
        }

        if (e.cause != null) {
            if (driver.browser.isActive) {
                logger.warn(e.cause?.stringify("Caused by: "))
            } else {
                // The browser is closing, nothing to do
            }
        }
    }
}
