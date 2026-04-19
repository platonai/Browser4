package ai.platon.pulsar.agentic.inference.detail

import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlin.math.min
import kotlin.math.pow

/**
 * Retry strategy with exponential backoff and jitter.
 *
 * Determines whether exceptions should be retried and calculates appropriate
 * delays between retry attempts.
 *
 * @param maxRetries Maximum number of retry attempts
 * @param baseDelayMs Base delay in milliseconds for exponential backoff
 * @param maxDelayMs Maximum delay in milliseconds (cap for exponential backoff)
 */
class RetryStrategy(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000
) {
    private val logger = getLogger(this)

    /**
     * Determine if an error should trigger a retry.
     */
    fun shouldRetry(error: Throwable): Boolean {
        return when (error) {
            is PerceptiveAgentError.TransientError,
            is PerceptiveAgentError.TimeoutError -> true
            is SocketTimeoutException,
            is ConnectException,
            is UnknownHostException -> true
            is IOException -> {
                val msg = error.message?.lowercase()
                msg?.contains("connection") == true || msg?.contains("timeout") == true
            }
            else -> false
        }
    }

    /**
     * Calculate retry delay with exponential backoff and jitter.
     *
     * @param attempt Current attempt number (0-based)
     * @return Delay in milliseconds
     */
    fun calculateDelay(attempt: Int): Long {
        // Use multiplicative jitter so delay is monotonic w.r.t attempt
        val baseExp = baseDelayMs * (2.0.pow(attempt.toDouble()))
        val jitterPercent = (0..30).random() / 100.0 // 0%..30% multiplicative jitter
        val withJitter = baseExp * (1.0 + jitterPercent)
        return min(withJitter.toLong(), maxDelayMs)
    }

    /**
     * Execute an action with retry logic.
     *
     * @param action The suspending action to execute
     * @param onRetry Optional callback invoked before each retry (receives attempt number and delay)
     * @param onError Optional callback invoked on each error (receives attempt number and error)
     * @return Result of successful execution
     * @throws Exception if all retries exhausted
     */
    suspend fun <T> execute(
        action: suspend () -> T,
        onRetry: (suspend (attempt: Int, delayMs: Long) -> Unit)? = null,
        onError: ((attempt: Int, error: Throwable) -> Unit)? = null
    ): T {
        var lastError: Throwable? = null

        for (attempt in 0..maxRetries) {
            try {
                return action()
            } catch (e: Exception) {
                lastError = e
                onError?.invoke(attempt, e)

                if (shouldRetry(e) && attempt < maxRetries) {
                    val delayMs = calculateDelay(attempt)
                    logger.info(
                        "🔄 Retry attempt {}/{} after {}ms for error: {}",
                        attempt + 1, maxRetries, delayMs, e.message
                    )
                    onRetry?.invoke(attempt, delayMs)
                    delay(delayMs)
                } else {
                    throw e
                }
            }
        }

        throw lastError ?: IllegalStateException("Retry loop completed without result or error")
    }

    /**
     * Classify an exception into a PerceptiveAgentError type for better handling.
     */
    fun classifyError(error: Throwable, context: String = ""): PerceptiveAgentError {
        return when (error) {
            is PerceptiveAgentError -> error
            is TimeoutException -> PerceptiveAgentError.TimeoutError("⏳ Timeout: $context", error)
            is SocketTimeoutException -> PerceptiveAgentError.TimeoutError("⏳ Network timeout: $context", error)
            is ConnectException -> PerceptiveAgentError.TransientError("🔄 Connection failed: $context", error)
            is UnknownHostException -> PerceptiveAgentError.TransientError("🔄 DNS resolution failed: $context", error)
            is IOException -> {
                when {
                    error.message?.contains("connection") == true ->
                        PerceptiveAgentError.TransientError("🔄 Connection issue: $context", error)
                    error.message?.contains("timeout") == true ->
                        PerceptiveAgentError.TimeoutError("⏳ Network timeout: $context", error)
                    else ->
                        PerceptiveAgentError.TransientError("🔄 IO error: $context - ${error.message}", error)
                }
            }
            is IllegalArgumentException ->
                PerceptiveAgentError.ValidationError("🚫 Validation error: $context - ${error.message}", error)
            is IllegalStateException ->
                PerceptiveAgentError.PermanentError("🛑 Invalid state: $context - ${error.message}", error)
            else ->
                PerceptiveAgentError.TransientError("💥 Unexpected error: $context - ${error.message}", error)
        }
    }
}
