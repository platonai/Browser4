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
import ai.platon.pulsar.captcha.CaptchaSolveScripts
import ai.platon.pulsar.captcha.CaptchaType
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Detects traditional image-based CAPTCHAs on a page.
 *
 * Uses JavaScript evaluation to find images and input fields
 * matching common CAPTCHA patterns (keywords in src/alt/id/class).
 */
open class ImageCaptchaDetector(val conf: ImmutableConfig) : CaptchaDetector {
    private val logger = getLogger(ImageCaptchaDetector::class)
    private val json = ObjectMapper()

    override suspend fun detect(driver: WebDriver): CaptchaDetectionResult {
        return try {
            val jsResult = driver.evaluateValue(CaptchaSolveScripts.DETECT_IMAGE_CAPTCHA) as? String
                ?: return CaptchaDetectionResult.NOT_PRESENT

            val parsed: Map<String, Any?> = json.readValue(jsResult)
            val present = parsed["present"] as? Boolean ?: false

            if (!present) return CaptchaDetectionResult.NOT_PRESENT

            val imageSelector = parsed["imageSelector"] as? String
            val inputSelector = parsed["inputSelector"] as? String

            CaptchaDetectionResult.found(
                captchaType = CaptchaType.IMAGE,
                siteKey = null,
                selector = imageSelector,
                confidence = 0.7f,
                metadata = mapOf(
                    "imageSelector" to (imageSelector ?: ""),
                    "inputSelector" to (inputSelector ?: "")
                )
            )
        } catch (e: Exception) {
            logger.trace("Image captcha detection error: {}", e.message)
            CaptchaDetectionResult.NOT_PRESENT
        }
    }
}
