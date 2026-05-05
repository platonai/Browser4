package ai.platon.pulsar.protocol.browser.driver.cdt.detail

import ai.platon.browser4.driver.chrome.util.CDPReturnError
import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.browser4.driver.chrome.util.ChromeIOException
import ai.platon.browser4.driver.chrome.util.ChromeRPCException
import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.stringify
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import ai.platon.pulsar.skeleton.workflow.fetch.driver.BrowserUnavailableException
import ai.platon.pulsar.skeleton.workflow.fetch.driver.IllegalWebDriverStateException
import kotlinx.coroutines.delay
import java.text.MessageFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class RobustRPC(
    private val driver: PulsarWebDriver
) {
    companion object {
        private val logger = getLogger(this)

        // handle to many exceptions
        private val exceptionCounts = ConcurrentHashMap<Long, AtomicInteger>()
        private val exceptionMessages = ConcurrentHashMap<Long, String>()

        var MAX_RPC_FAILURES = 5
    }

    private val logger = getLogger(this)

    private val _isActive = AtomicBoolean(false)

    val isActive get() = driver.isActive && _isActive.get()

    val rpcFailures = AtomicInteger()
    var maxRPCFailures = MAX_RPC_FAILURES

    @Throws(ChromeRPCException::class)
    suspend fun <T> invoke(action: String, block: suspend () -> T): T? {
        if (!driver.checkState(action)) {
            return null
        }

        try {
            return block().also { decreaseRPCFailures() }
        } catch (e: ChromeRPCException) {
            increaseRPCFailures()
            throw e
        }
    }

    @Throws(Exception::class)
    suspend fun <T> invokeWithRetry(
        action: String,
        maxRetry: Int = 2,
        url: String? = null,
        block: suspend () -> T
    ): T? {
        if (!driver.checkState(action)) {
            return null
        }

        if (!driver.devTools.isOpen) {
            if (_isActive.compareAndSet(true, false)) {
                logger.info("Devtools has been closed")
            }
            return null
        }

        var result = kotlin.runCatching { invokeDeferred0(action, url, block) }
            .onFailure {
                logger.info("Oop, a bit slip-up executing action: " +
                            "[$action], retrying 1/$maxRetry time ... | {}", it.brief())
            }

        var i = 1
        while (result.isFailure && i++ < maxRetry && driver.checkState()) {
            val exception = result.exceptionOrNull()
            // Check if this is a permanent error that shouldn't be retried
            if (exception != null && !isRetryableException(exception)) {
                logger.warn("Encountered non-retryable exception: [$action], aborting retries | {}", exception.message)
                break
            }
            delay(200.milliseconds)
            result = kotlin.runCatching { invokeDeferred0(action, url, block) }
                .onFailure { logger.warn("Exception to execute action: [$action], retrying $i/$maxRetry times", it) }
        }

        if (driver.checkState(action)) {
            return result.getOrElse { throw it }
        }

        return null
    }

    /**
     * Check if an exception is retryable. Returns false for permanent errors that
     * will not be resolved by retrying, such as:
     * - Invalid URL errors
     * - Invalid parameter errors
     * - etc.
     */
    private fun isRetryableException(e: Throwable): Boolean {
        // Non-retryable CDP errors
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

    suspend fun <T> invokeSilently(action: String, message: String? = null, block: suspend () -> T): T? {
        return try {
            invoke(action, block)
        } catch (e: ChromeRPCException) {
            handleChromeException(e, action, message)
            null
        }
    }

    suspend fun <T> invokeDeferredSilently(
        action: String, url: String? = null, message: String? = null, maxRetry: Int = 2, block: suspend () -> T
    ): T? {
        return try {
            invokeWithRetry(action, maxRetry, url, block)
        } catch (e: ChromeRPCException) {
            handleChromeException(e, action, message)
            null
        }
    }

    @Throws(IllegalWebDriverStateException::class, ChromeDriverException::class)
    fun handleChromeException(e: ChromeDriverException, action: String? = null, message: String? = null) {
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
    fun handleChromeIOException(e: ChromeIOException, action: String? = null, message: String? = null) {
        if (!AppContext.isActive) {
            logger.info("Ignored chrome IO exception because of system shutting down")
            return
        }

        val message2 = MessageFormat.format(
            "Browser unavailable: {0} ({1}/{2}) | {3}",
            action, rpcFailures, maxRPCFailures, e.message
        )

        if (!e.isOpen) {
            throw BrowserUnavailableException("Browser connection closed | $message2", e)
        } else if (!driver.isConnectable) {
            throw BrowserUnavailableException("Browser connection lost | $message2", e)
        } else {
            throw IllegalWebDriverStateException("Unknown chrome IO error | $message2", e)
        }
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
        if (!driver.checkState(action)) {
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
