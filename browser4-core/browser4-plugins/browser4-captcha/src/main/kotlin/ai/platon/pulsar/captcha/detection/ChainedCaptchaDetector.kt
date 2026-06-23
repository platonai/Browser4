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
import ai.platon.pulsar.core.api.WebDriver
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chain of [CaptchaDetector] instances that tries each detector in order.
 *
 * Returns the first positive detection. If no detector finds a CAPTCHA,
 * returns [CaptchaDetectionResult.NOT_PRESENT].
 *
 * Follows the same chain pattern as [ai.platon.pulsar.protocol.browser.emulator.util.ChainedPageCategorySniffer].
 */
open class ChainedCaptchaDetector(val conf: ImmutableConfig) : CaptchaDetector {
    private val detectors = CopyOnWriteArrayList<CaptchaDetector>()

    override suspend fun detect(driver: WebDriver): CaptchaDetectionResult {
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
}
