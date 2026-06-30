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
package ai.platon.pulsar.captcha.inject

import ai.platon.pulsar.captcha.CaptchaDetectionResult
import ai.platon.pulsar.captcha.CaptchaSolution
import ai.platon.pulsar.captcha.CaptchaSolveScripts
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Injects a solved reCAPTCHA token into the page via the grecaptcha API.
 *
 * Sets the token in:
 * - #g-recaptcha-response textarea
 * - .g-recaptcha-response elements
 * - Calls the data-callback function if found
 */
open class RecaptchaTokenInjector : CaptchaTokenInjector {
    private val logger = getLogger(RecaptchaTokenInjector::class)
    private val json = ObjectMapper()

    override suspend fun inject(
        driver: WebDriver,
        solution: CaptchaSolution,
        detection: CaptchaDetectionResult
    ): Boolean {
        val token = solution.token ?: run {
            logger.warn("No token in solution to inject")
            return false
        }

        return try {
            val js = CaptchaSolveScripts.INJECT_RECAPTCHA_TOKEN(token)
            val result = driver.evaluateValue(js) as? String ?: return false
            val parsed: Map<String, Any?> = json.readValue(result)
            parsed["success"] as? Boolean ?: false
        } catch (e: Exception) {
            logger.warn("Failed to inject reCAPTCHA token: {}", e.message)
            false
        }
    }
}
