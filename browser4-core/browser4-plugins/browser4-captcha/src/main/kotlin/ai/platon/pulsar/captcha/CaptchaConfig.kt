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

import ai.platon.pulsar.common.config.ImmutableConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration holder for CAPTCHA solving.
 *
 * All properties are read from [ImmutableConfig] with sensible defaults.
 */
data class CaptchaConfig(
    /** Whether to automatically detect and solve CAPTCHAs during browsing */
    val autoSolveEnabled: Boolean = true,

    /** Primary solving service provider */
    val primaryProvider: CaptchaServiceProvider = CaptchaServiceProvider.CAPSOLVER,

    /** API key for CapSolver */
    val capsolverApiKey: String? = null,

    /** API key for 2Captcha */
    val twoCaptchaApiKey: String? = null,

    /** API key for Anti-Captcha */
    val antiCaptchaApiKey: String? = null,

    /** Maximum time to wait for a solution */
    val solveTimeout: Duration = 120.seconds,

    /** Interval between polling for task status */
    val pollInterval: Duration = 1.seconds,

    /** Proxy to route solver traffic through (optional) */
    val solveProxy: String? = null,

    /** Whether to enable automatic CAPTCHA detection */
    val detectionEnabled: Boolean = true,

    /** Which CAPTCHA types to auto-solve */
    val autoSolveTypes: Set<CaptchaType> = setOf(
        CaptchaType.RECAPTCHA_V2,
        CaptchaType.RECAPTCHA_V2_INVISIBLE,
        CaptchaType.HCAPTCHA,
        CaptchaType.TURNSTILE
    ),

    /** Whether to report failed solves for refund */
    val reportFailed: Boolean = true,

    /** Maximum number of retry attempts */
    val maxRetries: Int = 3
) {
    companion object {
        /**
         * Build a [CaptchaConfig] from the application configuration.
         */
        fun fromConfig(conf: ImmutableConfig): CaptchaConfig {
            return CaptchaConfig(
                autoSolveEnabled = conf.getBoolean("captcha.auto.solve.enabled", true),
                primaryProvider = parseProvider(conf.get("captcha.service.provider", "CAPSOLVER")),
                capsolverApiKey = conf.get("captcha.capsolver.api.key", null),
                twoCaptchaApiKey = conf.get("captcha.twocaptcha.api.key", null),
                antiCaptchaApiKey = conf.get("captcha.anticaptcha.api.key", null),
                solveTimeout = conf.getLong("captcha.solve.timeout.seconds", 120L).seconds,
                pollInterval = conf.getLong("captcha.poll.interval.ms", 1000L).milliseconds,
                solveProxy = conf.get("captcha.solve.proxy", null)?.ifBlank { null },
                detectionEnabled = conf.getBoolean("captcha.detection.enabled", true),
                autoSolveTypes = parseTypes(conf.get("captcha.auto.solve.types", "RECAPTCHA_V2,HCAPTCHA,TURNSTILE")),
                reportFailed = conf.getBoolean("captcha.report.failed.enabled", true),
                maxRetries = conf.getInt("captcha.solve.max.retries", 3)
            )
        }

        private fun parseProvider(name: String): CaptchaServiceProvider {
            return try {
                CaptchaServiceProvider.valueOf(name.uppercase())
            } catch (_: IllegalArgumentException) {
                CaptchaServiceProvider.NONE
            }
        }

        private fun parseTypes(expr: String): Set<CaptchaType> {
            if (expr.uppercase() == "ALL") {
                return CaptchaType.entries.toSet() - CaptchaType.UNKNOWN
            }
            return expr.split(",")
                .map { it.trim().uppercase() }
                .mapNotNull { name ->
                    try {
                        CaptchaType.valueOf(name)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                .toSet()
        }
    }
}
