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
import ai.platon.pulsar.core.api.WebDriver

/**
 * Interface for detecting CAPTCHA widgets on a web page.
 *
 * Each implementation handles a specific CAPTCHA type by inspecting
 * the page's DOM and JavaScript objects.
 */
interface CaptchaDetector {
    /**
     * Detect whether a specific type of CAPTCHA is present on the current page.
     *
     * @param driver The WebDriver connected to the page
     * @return Detection result with type, siteKey, confidence, etc.
     */
    suspend fun detect(driver: WebDriver): CaptchaDetectionResult
}
