/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.captcha.detection

import ai.platon.pulsar.captcha.CaptchaDetectionResult
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chain of [CaptchaDetector] instances that tries each detector in order.
 *
 * Returns the first positive detection. If no detector finds a CAPTCHA,
 * returns [CaptchaDetectionResult.NOT_PRESENT].
 *
 * Detection is a single-shot page probe per detector by default. Widgets rendered
 * lazily by the page's own JavaScript are therefore a silent false negative on the
 * first call — pass a wait window to [detect] to poll until such a widget appears.
 *
 * Follows the same chain pattern as [ai.platon.pulsar.protocol.browser.emulator.util.ChainedPageCategorySniffer].
 */
open class ChainedCaptchaDetector(val conf: ImmutableConfig) : CaptchaDetector {
    private val logger = getLogger(ChainedCaptchaDetector::class)

    private val detectors = CopyOnWriteArrayList<CaptchaDetector>()

    /**
     * Delay between the attempts of a waited detection, from
     * `captcha.detect.poll.interval.ms` (default [DEFAULT_POLL_INTERVAL_MILLIS]).
     */
    private val pollIntervalMillis: Long =
        conf.getLong("captcha.detect.poll.interval.ms", DEFAULT_POLL_INTERVAL_MILLIS)
            .coerceAtLeast(1L)

    /**
     * Single-shot detection — the wait window is 0, so the page is probed exactly once.
     */
    override suspend fun detect(driver: WebDriver): CaptchaDetectionResult = detect(driver, 0L)

    /**
     * Detect, optionally waiting for a lazily rendered widget.
     *
     * The chain is re-run every `captcha.detect.poll.interval.ms` until a detector
     * reports a positive result or the [waitMillis] window expires — so a caller that
     * arrives before the page's JavaScript has rendered its widget gets a real answer
     * instead of a false negative, and a call that found nothing can say whether it
     * waited at all.
     *
     * The returned result carries the timing of the whole call:
     * [CaptchaDetectionResult.elapsedMillis], [CaptchaDetectionResult.waitMillis] and
     * [CaptchaDetectionResult.attempts].
     *
     * @param driver the driver connected to the page
     * @param waitMillis total time to keep polling for a widget, in milliseconds;
     *   0 probes once and never waits. Must be between 0 and [MAX_WAIT_MILLIS]
     * @return the first positive detection, or a negative result whose timing fields
     *   describe how long the chain looked for one
     * @throws IllegalArgumentException when [waitMillis] is negative or above
     *   [MAX_WAIT_MILLIS]
     */
    open suspend fun detect(driver: WebDriver, waitMillis: Long): CaptchaDetectionResult {
        require(waitMillis >= 0) { "waitMillis must not be negative, got $waitMillis" }
        require(waitMillis <= MAX_WAIT_MILLIS) {
            "waitMillis must not exceed $MAX_WAIT_MILLIS ms, got $waitMillis"
        }

        val start = System.currentTimeMillis()
        var attempts = 0
        var result = CaptchaDetectionResult.NOT_PRESENT

        while (true) {
            ++attempts
            result = detectOnce(driver)
            if (result.isPresent) {
                break
            }

            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= waitMillis) {
                break
            }
            delay(minOf(pollIntervalMillis, waitMillis - elapsed))
        }

        val elapsedMillis = System.currentTimeMillis() - start
        if (attempts > 1) {
            logger.debug(
                "CAPTCHA detection ran {} attempt(s) in {}ms (waitMillis={})",
                attempts, elapsedMillis, waitMillis
            )
        }

        return result.copy(elapsedMillis = elapsedMillis, waitMillis = waitMillis, attempts = attempts)
    }

    /**
     * One pass over the chain: the first detector that reports a CAPTCHA wins, a
     * detector that throws is skipped so a broken probe cannot mask the others.
     */
    private suspend fun detectOnce(driver: WebDriver): CaptchaDetectionResult {
        for (detector in detectors) {
            try {
                val result = detector.detect(driver)
                if (result.isPresent) {
                    return result
                }
            } catch (_: Exception) {
                // Continue to next detector
            }
        }

        return CaptchaDetectionResult.NOT_PRESENT
    }

    /**
     * Add a detector at the front (checked first).
     */
    fun addFirst(detector: CaptchaDetector): ChainedCaptchaDetector {
        detectors.add(0, detector)
        return this
    }

    /**
     * Add a detector at the end.
     */
    fun addLast(detector: CaptchaDetector): ChainedCaptchaDetector {
        detectors.add(detector)
        return this
    }

    /**
     * Remove a detector from the chain.
     */
    fun remove(detector: CaptchaDetector) {
        detectors.remove(detector)
    }

    /**
     * Number of detectors in the chain.
     */
    val size: Int get() = detectors.size

    companion object {
        /** Delay between the attempts of a waited detection when the config says nothing. */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 250L

        /** Upper bound of a single [detect] call's wait window, so a tool call cannot hang. */
        const val MAX_WAIT_MILLIS = 30_000L
    }
}
