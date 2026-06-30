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
package ai.platon.pulsar.captcha

/**
 * Result of CAPTCHA detection on a page.
 *
 * @param isPresent Whether any CAPTCHA was detected
 * @param captchaType The type of CAPTCHA detected
 * @param siteKey The data-sitekey value extracted from the widget
 * @param action Optional action name (reCAPTCHA v3)
 * @param selector CSS selector locating the CAPTCHA element
 * @param confidence Detection confidence (0.0 to 1.0)
 * @param metadata Additional detection metadata (e.g., widget count, s parameter, cdata)
 */
data class CaptchaDetectionResult(
    val isPresent: Boolean,
    val captchaType: CaptchaType = CaptchaType.UNKNOWN,
    val siteKey: String? = null,
    val action: String? = null,
    val selector: String? = null,
    val confidence: Float = 0f,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        val NOT_PRESENT = CaptchaDetectionResult(false)

        fun found(
            captchaType: CaptchaType,
            siteKey: String?,
            action: String? = null,
            selector: String? = null,
            confidence: Float = 1.0f,
            metadata: Map<String, String> = emptyMap()
        ): CaptchaDetectionResult = CaptchaDetectionResult(
            isPresent = true,
            captchaType = captchaType,
            siteKey = siteKey,
            action = action,
            selector = selector,
            confidence = confidence,
            metadata = metadata
        )
    }
}
