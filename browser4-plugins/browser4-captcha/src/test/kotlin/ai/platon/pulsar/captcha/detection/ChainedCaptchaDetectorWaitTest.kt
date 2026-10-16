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
import ai.platon.pulsar.captcha.CaptchaType
import ai.platon.pulsar.captcha.FakePageDriver
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.core.api.WebDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Issue 123037#9: detection had no wait/timeout semantics, so a call that arrived
 * before the page rendered its widget was a silent false negative, and the result
 * said nothing about whether the chain had waited at all.
 */
@DisplayName("captcha detection wait window")
class ChainedCaptchaDetectorWaitTest {

    private val conf = ImmutableConfig()

    @Test
    @DisplayName("the no-argument detect is a single-shot probe")
    fun noArgumentDetectIsSingleShot() {
        val detector = CountingDetector { CaptchaDetectionResult.NOT_PRESENT }
        val chain = chainOf(detector)

        val result = runBlocking { chain.detect(driver()) }

        assertFalse(result.isPresent)
        assertEquals(1, result.attempts, "the default must not wait")
        assertEquals(0, result.waitMillis)
        assertEquals(1, detector.calls)
    }

    @Test
    @DisplayName("a wait window polls until a detector finds the widget")
    fun waitWindowPollsUntilFound() {
        val detector = CountingDetector { call ->
            if (call < 3) {
                CaptchaDetectionResult.NOT_PRESENT
            } else {
                CaptchaDetectionResult.found(CaptchaType.HCAPTCHA, siteKey = "late-widget", widgetCount = 1)
            }
        }
        val chain = chainOf(detector)

        val result = runBlocking { chain.detect(driver(), 5_000L) }

        assertTrue(result.isPresent)
        assertEquals(CaptchaType.HCAPTCHA, result.captchaType)
        assertEquals("late-widget", result.siteKey)
        assertEquals(3, result.attempts)
        assertEquals(5_000L, result.waitMillis)
        assertEquals(3, detector.calls)
    }

    @Test
    @DisplayName("an expired wait window reports the timing of the whole window")
    fun expiredWaitWindowReportsTiming() {
        val detector = CountingDetector { CaptchaDetectionResult.NOT_PRESENT }
        val chain = chainOf(detector)

        val result = runBlocking { chain.detect(driver(), 150L) }

        assertFalse(result.isPresent)
        assertTrue(result.attempts >= 2, "the chain must have retried: ${result.attempts}")
        assertEquals(150L, result.waitMillis)
        assertTrue(
            result.elapsedMillis >= 150L,
            "a waited detection reports at least the window: ${result.elapsedMillis}"
        )
        assertEquals(result.attempts, detector.calls)
    }

    @Test
    @DisplayName("a single-shot detection reports the latency it took")
    fun singleShotDetectionReportsLatency() {
        val detector = CountingDetector {
            CaptchaDetectionResult.found(CaptchaType.TURNSTILE, siteKey = "immediate")
        }

        val result = runBlocking { chainOf(detector).detect(driver(), 0L) }

        assertTrue(result.isPresent)
        assertEquals(1, result.attempts)
        assertEquals(0L, result.waitMillis)
        assertTrue(result.elapsedMillis >= 0L)
    }

    @Test
    @DisplayName("a wait window outside 0..30000 is rejected")
    fun waitWindowOutsideTheSupportedRangeIsRejected() {
        val chain = chainOf(CountingDetector { CaptchaDetectionResult.NOT_PRESENT })

        val negative = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { chain.detect(driver(), -1L) }
        }
        assertTrue(negative.message!!.contains("waitMillis"), negative.message)

        val oversized = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { chain.detect(driver(), ChainedCaptchaDetector.MAX_WAIT_MILLIS + 1) }
        }
        assertTrue(oversized.message!!.contains("30000"), oversized.message)
    }

    @Test
    @DisplayName("a throwing detector never masks the next one in the chain")
    fun throwingDetectorIsSkipped() {
        val broken = CountingDetector { throw IllegalStateException("page probe exploded") }
        val working = CountingDetector {
            CaptchaDetectionResult.found(CaptchaType.TURNSTILE, siteKey = "second-detector")
        }

        val result = runBlocking { chainOf(broken, working).detect(driver()) }

        assertTrue(result.isPresent)
        assertEquals("second-detector", result.siteKey)
        assertEquals(1, broken.calls)
    }

    private fun chainOf(vararg detectors: CaptchaDetector): ChainedCaptchaDetector {
        val chain = ChainedCaptchaDetector(conf)
        detectors.forEach { chain.addLast(it) }
        return chain
    }

    private fun driver(): WebDriver = FakePageDriver { null }.driver

    /** A detector that records how often the chain called it. */
    private class CountingDetector(
        private val outcome: (Int) -> CaptchaDetectionResult
    ) : CaptchaDetector {
        var calls: Int = 0
            private set

        override suspend fun detect(driver: WebDriver): CaptchaDetectionResult {
            ++calls
            return outcome(calls)
        }
    }
}
