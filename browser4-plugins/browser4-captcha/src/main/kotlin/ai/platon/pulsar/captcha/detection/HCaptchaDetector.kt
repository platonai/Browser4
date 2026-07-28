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
 * Detects hCaptcha widgets on a page.
 *
 * Uses JavaScript evaluation to check for:
 * - The `hcaptcha` JavaScript API object
 * - iframes with hcaptcha.com in src
 * - .h-captcha div elements with data-sitekey attributes
 */
open class HCaptchaDetector(val conf: ImmutableConfig) : CaptchaDetector {
    private val logger = getLogger(HCaptchaDetector::class)
    private val json = ObjectMapper()

    override suspend fun detect(driver: WebDriver): CaptchaDetectionResult {
        return try {
            val jsResult = driver.evaluateValue(CaptchaSolveScripts.DETECT_HCAPTCHA) as? String
                ?: return CaptchaDetectionResult.NOT_PRESENT

            val parsed: Map<String, Any?> = json.readValue(jsResult)
            val present = parsed["present"] as? Boolean ?: false

            if (!present) return CaptchaDetectionResult.NOT_PRESENT

            val siteKey = parsed["siteKey"] as? String

            CaptchaDetectionResult.found(
                captchaType = CaptchaType.HCAPTCHA,
                siteKey = siteKey,
                selector = ".h-captcha",
                confidence = 0.95f
            )
        } catch (e: Exception) {
            logger.trace("hCaptcha detection error: {}", e.message)
            CaptchaDetectionResult.NOT_PRESENT
        }
    }
}
