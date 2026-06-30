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
 * Detects Cloudflare Turnstile widgets on a page.
 *
 * Uses JavaScript evaluation to check for:
 * - The `turnstile` JavaScript API object
 * - iframes with challenges.cloudflare.com in src
 * - .cf-turnstile div elements with data-sitekey attributes
 */
open class TurnstileDetector(val conf: ImmutableConfig) : CaptchaDetector {
    private val logger = getLogger(TurnstileDetector::class)
    private val json = ObjectMapper()

    override suspend fun detect(driver: WebDriver): CaptchaDetectionResult {
        return try {
            val jsResult = driver.evaluateValue(CaptchaSolveScripts.DETECT_TURNSTILE) as? String
                ?: return CaptchaDetectionResult.NOT_PRESENT

            val parsed: Map<String, Any?> = json.readValue(jsResult)
            val present = parsed["present"] as? Boolean ?: false

            if (!present) return CaptchaDetectionResult.NOT_PRESENT

            val siteKey = parsed["siteKey"] as? String
            val action = parsed["action"] as? String
            val cdata = parsed["cdata"] as? String

            val metadata = mutableMapOf<String, String>()
            if (cdata != null) metadata["cdata"] = cdata

            CaptchaDetectionResult.found(
                captchaType = CaptchaType.TURNSTILE,
                siteKey = siteKey,
                action = action,
                selector = ".cf-turnstile",
                confidence = 0.95f,
                metadata = metadata
            )
        } catch (e: Exception) {
            logger.trace("Turnstile detection error: {}", e.message)
            CaptchaDetectionResult.NOT_PRESENT
        }
    }
}
