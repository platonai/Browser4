package ai.platon.pulsar.agentic.inference.detail

import ai.platon.browser4.common.B4ResourceLoader
import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.AgentConfig
import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayDeque
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tracks page state changes to detect loops and ensure DOM stability.
 *
 * This helper class encapsulates page state tracking logic, including:
 * - DOM stability detection
 * - Page state fingerprinting
 * - Loop detection
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class PageStateTracker(
    private val session: AgenticSession,
    private val config: AgentConfig
) {
    private val logger = getLogger(this)

    private val activeDriver get() = session.getOrCreateBoundDriver()
    private var lastURL: String? = null
    private var lastPageStateHash: Int? = null
    private var sameStateCount = 0

    // Keep a short history to detect small-period oscillations like A<->B toggles
    private val recentHashes: ArrayDeque<Int> = ArrayDeque()
    private val maxRecentHashes = 10

    private val domStateHandle = "_ds_" + UUID.randomUUID().toString().filter { it.isLetterOrDigit() }.take(8)
    private val domSettleJsLoaded = AtomicBoolean(false)
    private var domSettleJs: String? = null

    /**
     * Calculate page state fingerprint for loop detection.
     * Combines URL, DOM structure, and scroll position into a single hash.
     *
     * @param browserUseState The current browser state
     * @return Hash code representing the page state
     */
    fun calculatePageStateHash(browserUseState: BrowserUseState): Int {
        // Combine URL, scroll state, DOM structure for fingerprint
        val urlHash = browserUseState.browserState.url.hashCode()
        val domHash = browserUseState.domState.ariaSnapshot.hashCode()
        // Quantize scroll ratio to reduce noise from tiny jitters (bucket to percentage 0..100)
        val scrollBucket = (browserUseState.browserState.scrollState.scrollYRatio * 100).toInt()
        val scrollHash = scrollBucket

        return (urlHash * 31 + domHash) * 31 + scrollHash
    }

    /**
     * Check if page state has changed since last check.
     * Tracks consecutive same-state occurrences for loop detection.
     *
     * @param browserUseState The current browser state
     * @return Number of consecutive times the same state has been observed
     */
    fun checkStateChange(browserUseState: BrowserUseState): Int {
        val currentURL = browserUseState.browserState.url
        // Navigated to a new page
        if (currentURL != lastURL) {
            sameStateCount = 0
            lastPageStateHash = 0
            lastURL = currentURL
            return 0
        }

        val currentStateHash = calculatePageStateHash(browserUseState)

        if (currentStateHash == lastPageStateHash) {
            sameStateCount++
        } else {
            sameStateCount = 0
        }

        lastPageStateHash = currentStateHash
        // Maintain a ring buffer of recent hashes for loop detection
        recentHashes.addLast(currentStateHash)
        if (recentHashes.size > maxRecentHashes) {
            recentHashes.removeFirst()
        }
        return sameStateCount
    }

    /**
     * Detect simple oscillation loops like A<->B or A->B->C->A.
     * @param periods Periods to check for, e.g., 2,3
     * @param minRepeatsPerPeriod Require at least this many repeats of the period to confirm
     * @return The detected period if a loop is found, or null otherwise
     */
    fun detectLoop(periods: Set<Int> = setOf(2, 3), minRepeatsPerPeriod: Int = 2): Int? {
        if (recentHashes.size < 2) return null
        val list = recentHashes.toList()
        for (p in periods) {
            // Need at least (minRepeatsPerPeriod + 1) * p samples to confirm p-periodicity
            val needed = (minRepeatsPerPeriod + 1) * p
            if (list.size < needed) continue
            var periodic = true
            // Compare last needed window
            val start = list.size - needed
            for (i in start + p until list.size) {
                if (list[i] != list[i - p]) {
                    periodic = false
                    break
                }
            }
            if (periodic) return p
        }
        return null
    }

    /**
     * Reset state tracking counters.
     */
    fun reset() {
        lastPageStateHash = null
        sameStateCount = 0
        recentHashes.clear()
    }

    /**
     * Wait for DOM to stabilize by checking for mutations, using config defaults.
     * This overload uses AgentConfig.domSettleTimeoutMs and domSettleCheckIntervalMs.
     */
    suspend fun waitForDOMSettle() = waitForDOMSettle(
        timeoutMs = config.domSettleTimeoutMs,
        checkIntervalMs = config.domSettleCheckIntervalMs
    )

    // Install the lightweight DOM stability probe once per page to avoid re-parsing JS in the loop
    private suspend fun ensureDomStabilityProbeInstalled() {
        val driver = activeDriver

        if (domSettleJsLoaded.compareAndSet(false, true)) {
            val rawJs = B4ResourceLoader.readString("js/dom_settle.js")
            domSettleJs = rawJs.replace("__DOM_STATE_VAR__", domStateHandle)
        }

        val js = requireNotNull(domSettleJs) { "dom_settle Js is null" }
        driver.evaluateValue(js)
    }

    /**
     * Wait for DOM to stabilize by checking for mutations.
     *
     * Faster implementation details:
     * - Install a single MutationObserver once (no reallocation per check)
     * - Avoid layout-triggering reads (scrollHeight/offsets); rely on a stamp + readyState
     * - Return a compact numeric signature to minimize JS<->JVM marshalling
     * - Require fewer stable checks when document.readyState is 'complete'
     *
     * @param timeoutMs Maximum time to wait for DOM to settle
     * @param checkIntervalMs Interval between stability checks
     */
    suspend fun waitForDOMSettle(timeoutMs: Long, checkIntervalMs: Long) {
        val driver = activeDriver

        driver.waitForSelector("body", timeoutMs)
        // Install once to avoid re-parsing the script below on each poll
        ensureDomStabilityProbeInstalled()

        val startTime = System.currentTimeMillis()
        var lastSignature: Long? = null
        var lastReadyStateCode: Int = -1
        var stableCount = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                // Call the cached function with minimal JS to reduce parser/bridge overhead
                val signatureNum = (driver.evaluateValue(
                    """
                    (() => { try { const s = window['$domStateHandle']; return s && typeof s.getSignature === 'function' ? s.getSignature() : -1; } catch(e) { return -1; } })()
                    """.trimIndent()
                ) as? Number)?.toLong()

                // For debug purpose only
                if (logger.isDebugEnabled) {
                    if (driver.currentUrl().contains("?q=")) {
                        logger.debug("signatureNum: $signatureNum")
                    }
                }

                if (signatureNum != null && signatureNum >= 0) {
                    val rsCode = (signatureNum % 4L).toInt()
                    val isSame = signatureNum == lastSignature
                    if (isSame) {
                        stableCount++
                        val requiredStableChecks = if (rsCode == 2) 2 else 3
                        if (stableCount >= requiredStableChecks) {
                            logger.debug("DOM settled after ${System.currentTimeMillis() - startTime}ms (rsCode=$rsCode, checks=$stableCount)")
                            return
                        }
                    } else {
                        stableCount = 0
                    }
                    lastReadyStateCode = rsCode
                    lastSignature = signatureNum
                } else {
                    // Treat as unstable and continue polling
                    stableCount = 0
                }

                delay(checkIntervalMs.milliseconds)
            } catch (e: Exception) {
                logger.warn("Error checking DOM stability: ${e.message}")
                delay(checkIntervalMs.milliseconds)
            }
        }

        logger.info("DOM settle timeout after ${timeoutMs}ms (lastReadyStateCode=$lastReadyStateCode)")
    }
}
