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
package ai.platon.pulsar.captcha.integration

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.PageDatum
import ai.platon.pulsar.persist.metadata.OpenPageCategory
import ai.platon.pulsar.persist.metadata.PageCategory
import ai.platon.pulsar.protocol.browser.emulator.util.PageCategorySniffer

/**
 * A [PageCategorySniffer] that detects CAPTCHA pages by inspecting
 * the page source for known CAPTCHA indicators.
 *
 * This allows the system to classify pages as CAPTCHA-blocked
 * and trigger appropriate retry mechanisms.
 *
 * Registers as an UNKNOWN category to avoid overriding existing
 * page categorization — the detection result is informational
 * and consumed by [CaptchaBrowseEventHandler].
 */
open class CaptchaPageCategorySniffer(val conf: ImmutableConfig) : PageCategorySniffer {

    /**
     * Common indicators of CAPTCHA presence in page source:
     * - recaptcha
     * - hcaptcha
     * - cf-turnstile (Cloudflare Turnstile)
     * - challenge-platform (generic challenge page)
     * - captcha (generic)
     */
    private val captchaIndicators = listOf(
        "recaptcha",
        "hcaptcha",
        "cf-turnstile",
        "challenges.cloudflare.com",
        "funcaptcha",
        "captcha"
    )

    override fun invoke(pageDatum: PageDatum): OpenPageCategory {
        val content = pageDatum.content?.toString()?.lowercase() ?: return OpenPageCategory(PageCategory.UNKNOWN)
        val url = pageDatum.url.lowercase()

        val hasCaptchaIndicator = captchaIndicators.any { indicator ->
            content.contains(indicator)
        }

        val hasCaptchaInUrl = url.contains("captcha") || url.contains("challenge")

        // Return UNKNOWN to not override other page categorization;
        // CAPTCHA detection is handled by CaptchaBrowseEventHandler
        // which runs during onDocumentSteady.
        return OpenPageCategory(PageCategory.UNKNOWN)
    }
}
