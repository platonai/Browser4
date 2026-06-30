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
import ai.platon.pulsar.core.api.WebDriver

/**
 * Interface for injecting a solved CAPTCHA token back into the page.
 *
 * Each implementation handles the specific JavaScript API of a
 * CAPTCHA provider (grecaptcha, hcaptcha, turnstile).
 */
interface CaptchaTokenInjector {
    /**
     * Inject the solved token into the page's CAPTCHA widget.
     *
     * @param driver The WebDriver connected to the page
     * @param solution The solved CAPTCHA result containing the token
     * @param detection The detection result with widget selectors and metadata
     * @return true if injection was successful, false otherwise
     */
    suspend fun inject(driver: WebDriver, solution: CaptchaSolution, detection: CaptchaDetectionResult): Boolean
}
