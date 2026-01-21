package ai.platon.pulsar.agentic.inference.detail

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Interface for different DOM stability detection strategies.
 *
 * Each strategy implements a specific approach to determine when a page has finished loading
 * and is ready for interaction or data extraction.
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
interface DOMStabilityStrategy {
    /**
     * Check if the page is stable according to this strategy.
     *
     * @return true if the page is considered stable, false otherwise
     */
    suspend fun check(): Boolean

    /**
     * Name of this strategy for logging and debugging.
     */
    val name: String

    /**
     * Description of what this strategy checks.
     */
    val description: String
}

/**
 * Configuration for DOM stability detection.
 *
 * @property timeout Maximum time to wait for stability (milliseconds)
 * @property checkIntervalMs Interval between stability checks (milliseconds)
 * @property mode Strategy combination mode (ALL, ANY_N, RACE)
 * @property networkIdleTime Time with no network activity to consider idle (milliseconds)
 * @property maxInflightRequests Maximum concurrent requests allowed for network idle
 * @property domStableChecks Number of consecutive stable checks required for DOM
 * @property minHeight Minimum page height for content quality check
 * @property minElements Minimum number of elements for content quality check
 */
data class StabilityConfig(
    val timeout: Long = 30_000,
    val checkIntervalMs: Long = 100,
    val mode: StabilityMode = StabilityMode.ANY_N,
    val requiredStrategies: Int = 2,
    // Network idle settings
    val networkIdleTime: Long = 500,
    val maxInflightRequests: Int = 2,
    // DOM stability settings
    val domStableChecks: Int = 3,
    val domStableChecksComplete: Int = 2,
    // Content quality settings
    val minHeight: Int = 1000,
    val minElements: Int = 10,
    val minAnchors: Int = 5,
    val minImages: Int = 2
) {
    companion object {
        /**
         * Default configuration for general web scraping.
         */
        val DEFAULT = StabilityConfig()

        /**
         * Fast configuration for AI agents - prioritizes speed.
         */
        val FAST = StabilityConfig(
            timeout = 10_000,
            mode = StabilityMode.ANY_N,
            requiredStrategies = 1,
            domStableChecks = 2
        )

        /**
         * Thorough configuration for production scraping - prioritizes reliability.
         */
        val THOROUGH = StabilityConfig(
            timeout = 60_000,
            mode = StabilityMode.ALL,
            domStableChecks = 5,
            minHeight = 2000,
            minElements = 50
        )

        /**
         * Configuration optimized for Single Page Applications.
         */
        val SPA = StabilityConfig(
            timeout = 20_000,
            mode = StabilityMode.ANY_N,
            requiredStrategies = 2,
            networkIdleTime = 1000,
            maxInflightRequests = 0
        )
    }
}

/**
 * Strategy combination mode.
 */
enum class StabilityMode {
    /**
     * All strategies must pass.
     */
    ALL,

    /**
     * At least N strategies must pass (configured via requiredStrategies).
     */
    ANY_N,

    /**
     * First strategy to pass wins (race mode).
     */
    RACE
}

/**
 * Result of a stability check.
 *
 * @property success Whether the page is considered stable
 * @property strategy Strategy that determined the result
 * @property elapsedMs Time taken to determine stability
 * @property message Optional message with details
 */
data class StabilityResult(
    val success: Boolean,
    val strategy: String,
    val elapsedMs: Long,
    val message: String? = null,
    val passedStrategies: Set<String> = emptySet()
) {
    companion object {
        fun success(strategy: String, elapsedMs: Long, message: String? = null) =
            StabilityResult(true, strategy, elapsedMs, message)

        fun failure(strategy: String, elapsedMs: Long, message: String? = null) =
            StabilityResult(false, strategy, elapsedMs, message)

        fun timeout(elapsedMs: Long) =
            StabilityResult(false, "timeout", elapsedMs, "Stability check timed out")
    }
}

/**
 * Network-idle based stability strategy.
 *
 * Waits for all network requests to finish, similar to Puppeteer's networkidle0/networkidle2.
 * This is particularly effective for AJAX-heavy sites and SPAs.
 *
 * @param session The agentic session
 * @param config Stability configuration
 */
class NetworkIdleStrategy(
    private val session: AgenticSession,
    private val config: StabilityConfig
) : DOMStabilityStrategy {
    private val logger = getLogger(this)

    override val name = "network-idle"
    override val description = "Waits for network activity to cease"

    override suspend fun check(): Boolean {
        val driver = session.getOrCreateBoundDriver()
        val startTime = System.currentTimeMillis()
        var lastActivityTime = startTime

        while (System.currentTimeMillis() - startTime < config.timeout) {
            val inflightCount = getInflightRequestCount(driver)

            if (inflightCount <= config.maxInflightRequests) {
                val idleDuration = System.currentTimeMillis() - lastActivityTime
                if (idleDuration >= config.networkIdleTime) {
                    logger.debug("Network idle detected after {}ms (inflight={})",
                        System.currentTimeMillis() - startTime, inflightCount)
                    return true
                }
            } else {
                lastActivityTime = System.currentTimeMillis()
            }

            delay(config.checkIntervalMs)
        }

        logger.debug("Network idle timeout after {}ms", config.timeout)
        return false
    }

    /**
     * Get the number of inflight network requests.
     *
     * Note: This is a simplified implementation. In production, this should use
     * CDP network monitoring or similar mechanism.
     */
    private suspend fun getInflightRequestCount(driver: WebDriver): Int {
        return try {
            // Use performance API to check pending requests
            val result = driver.evaluateValue(
                """
                (() => {
                    const entries = performance.getEntriesByType('resource');
                    // Count entries without responseEnd (still loading)
                    return entries.filter(e => e.responseEnd === 0).length;
                })()
                """.trimIndent()
            )
            (result as? Number)?.toInt() ?: 0
        } catch (e: Exception) {
            logger.warn("Failed to get inflight request count: ${e.message}")
            0
        }
    }
}

/**
 * DOM mutation-based stability strategy.
 *
 * Uses the existing PageStateTracker.waitForDOMSettle implementation.
 * This is fast and efficient for detecting when DOM mutations have stopped.
 *
 * @param pageStateTracker The page state tracker
 * @param config Stability configuration
 */
class DOMStabilityStrategy(
    private val pageStateTracker: PageStateTracker,
    private val config: StabilityConfig
) : DOMStabilityStrategy {
    private val logger = getLogger(this)

    override val name = "dom-stable"
    override val description = "Waits for DOM mutations to cease"

    override suspend fun check(): Boolean {
        return try {
            pageStateTracker.waitForDOMSettle(
                timeoutMs = config.timeout,
                checkIntervalMs = config.checkIntervalMs
            )
            true
        } catch (e: Exception) {
            logger.warn("DOM stability check failed: ${e.message}")
            false
        }
    }
}

/**
 * Content quality-based stability strategy.
 *
 * Checks if the page has minimum expected content (height, elements, etc.).
 * This is useful to ensure the page has actually loaded meaningful content.
 *
 * @param session The agentic session
 * @param config Stability configuration
 */
class ContentQualityStrategy(
    private val session: AgenticSession,
    private val config: StabilityConfig
) : DOMStabilityStrategy {
    private val logger = getLogger(this)

    override val name = "content-quality"
    override val description = "Checks for minimum content quality"

    override suspend fun check(): Boolean {
        val driver = session.getOrCreateBoundDriver()

        return try {
            val metrics = getContentMetrics(driver)

            val hasMinHeight = metrics.height >= config.minHeight
            val hasMinElements = metrics.elementCount >= config.minElements
            val hasMinAnchors = metrics.anchorCount >= config.minAnchors
            val hasMinImages = metrics.imageCount >= config.minImages

            val passed = hasMinHeight && hasMinElements && (hasMinAnchors || hasMinImages)

            if (logger.isDebugEnabled) {
                logger.debug(
                    "Content quality: height={}/{}, elements={}/{}, anchors={}/{}, images={}/{} => {}",
                    metrics.height, config.minHeight,
                    metrics.elementCount, config.minElements,
                    metrics.anchorCount, config.minAnchors,
                    metrics.imageCount, config.minImages,
                    if (passed) "PASS" else "FAIL"
                )
            }

            passed
        } catch (e: Exception) {
            logger.warn("Content quality check failed: ${e.message}")
            false
        }
    }

    private data class ContentMetrics(
        val height: Int,
        val elementCount: Int,
        val anchorCount: Int,
        val imageCount: Int
    )

    private suspend fun getContentMetrics(driver: WebDriver): ContentMetrics {
        val result = driver.evaluateValue(
            """
            (() => {
                const height = Math.max(
                    document.body?.scrollHeight || 0,
                    document.documentElement?.scrollHeight || 0
                );
                const elements = document.querySelectorAll('*').length;
                const anchors = document.querySelectorAll('a[href]').length;
                const images = document.querySelectorAll('img[src]').length;
                
                return { height, elements, anchors, images };
            })()
            """.trimIndent()
        )

        @Suppress("UNCHECKED_CAST")
        val map = result as? Map<String, Any> ?: emptyMap()

        return ContentMetrics(
            height = (map["height"] as? Number)?.toInt() ?: 0,
            elementCount = (map["elements"] as? Number)?.toInt() ?: 0,
            anchorCount = (map["anchors"] as? Number)?.toInt() ?: 0,
            imageCount = (map["images"] as? Number)?.toInt() ?: 0
        )
    }
}

/**
 * Hybrid multi-strategy stability detector.
 *
 * Combines multiple strategies to provide robust stability detection.
 * Supports different combination modes (ALL, ANY_N, RACE).
 *
 * @param session The agentic session
 * @param pageStateTracker The page state tracker
 * @param config Stability configuration
 */
class HybridStabilityDetector(
    private val session: AgenticSession,
    private val pageStateTracker: PageStateTracker,
    private val config: StabilityConfig
) {
    private val logger = getLogger(this)

    private val strategies: List<DOMStabilityStrategy> by lazy {
        listOf(
            NetworkIdleStrategy(session, config),
            DOMStabilityStrategy(pageStateTracker, config),
            ContentQualityStrategy(session, config)
        )
    }

    /**
     * Wait for page stability using configured strategy combination mode.
     *
     * @return StabilityResult indicating success/failure and details
     */
    suspend fun waitForStability(): StabilityResult {
        val startTime = System.currentTimeMillis()

        return when (config.mode) {
            StabilityMode.ALL -> waitForAll(startTime)
            StabilityMode.ANY_N -> waitForAnyN(startTime)
            StabilityMode.RACE -> waitForRace(startTime)
        }
    }

    /**
     * Wait for all strategies to pass.
     */
    private suspend fun waitForAll(startTime: Long): StabilityResult {
        val passed = mutableSetOf<String>()

        while (System.currentTimeMillis() - startTime < config.timeout) {
            for (strategy in strategies) {
                if (strategy.name !in passed && strategy.check()) {
                    passed.add(strategy.name)
                    logger.debug("Strategy {} passed", strategy.name)
                }
            }

            if (passed.size == strategies.size) {
                val elapsed = System.currentTimeMillis() - startTime
                logger.info("All strategies passed after {}ms: {}", elapsed, passed)
                return StabilityResult.success("all", elapsed, "All strategies passed", passed)
            }

            delay(config.checkIntervalMs)
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.warn("Timeout waiting for all strategies. Passed: {}", passed)
        return StabilityResult.failure("all", elapsed, "Only ${passed.size}/${strategies.size} strategies passed", passed)
    }

    /**
     * Wait for at least N strategies to pass.
     */
    private suspend fun waitForAnyN(startTime: Long): StabilityResult {
        val passed = mutableSetOf<String>()
        val required = config.requiredStrategies.coerceAtMost(strategies.size)

        while (System.currentTimeMillis() - startTime < config.timeout) {
            for (strategy in strategies) {
                if (strategy.name !in passed && strategy.check()) {
                    passed.add(strategy.name)
                    logger.debug("Strategy {} passed", strategy.name)
                }
            }

            if (passed.size >= required) {
                val elapsed = System.currentTimeMillis() - startTime
                logger.info("{} strategies passed after {}ms: {}", passed.size, elapsed, passed)
                return StabilityResult.success("any-$required", elapsed, "$required strategies passed", passed)
            }

            delay(config.checkIntervalMs)
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.warn("Timeout waiting for {} strategies. Passed: {}", required, passed)
        return StabilityResult.failure("any-$required", elapsed, "Only ${passed.size}/$required strategies passed", passed)
    }

    /**
     * Wait for the first strategy to pass (race mode).
     */
    private suspend fun waitForRace(startTime: Long): StabilityResult {
        while (System.currentTimeMillis() - startTime < config.timeout) {
            for (strategy in strategies) {
                if (strategy.check()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    logger.info("Strategy {} passed first after {}ms", strategy.name, elapsed)
                    return StabilityResult.success(strategy.name, elapsed, "${strategy.name} passed first", setOf(strategy.name))
                }
            }

            delay(config.checkIntervalMs)
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.warn("Timeout in race mode, no strategy passed")
        return StabilityResult.timeout(elapsed)
    }
}
