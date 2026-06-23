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

import ai.platon.pulsar.captcha.*
import ai.platon.pulsar.captcha.detection.ChainedCaptchaDetector
import ai.platon.pulsar.captcha.inject.CaptchaTokenInjector
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.core.api.WebPage
import ai.platon.pulsar.skeleton.event.WebPageWebDriverEventHandler

/**
 * A browse event handler that automatically detects and solves CAPTCHAs
 * during page browsing.
 *
 * Hooks into [onDocumentSteady] — the recommended event for RPA actions —
 * to detect CAPTCHA widgets, submit them to the solving service, and inject
 * the returned token back into the page.
 *
 * A window variable `__pulsar_captcha_solved__` is set to prevent double-solving
 * if multiple browse events fire for the same page.
 */
open class CaptchaBrowseEventHandler(
    private val captchaDetector: ChainedCaptchaDetector,
    private val captchaSolver: ChainedCaptchaSolver,
    private val tokenInjectors: Map<CaptchaType, CaptchaTokenInjector>,
    private val config: CaptchaConfig
) : WebPageWebDriverEventHandler() {
    private val logger = getLogger(CaptchaBrowseEventHandler::class)

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        if (!config.autoSolveEnabled) {
            return null
        }

        // Check if already solved for this page load
        if (isAlreadyProcessed(driver)) {
            return null
        }

        val startTime = System.currentTimeMillis()

        return try {
            val detection = captchaDetector.detect(driver)
            if (!detection.isPresent) {
                return null
            }

            if (detection.captchaType !in config.autoSolveTypes) {
                logger.debug("CAPTCHA type {} not in auto-solve types, skipping", detection.captchaType)
                return null
            }

            logger.info(
                "CAPTCHA detected: type={}, siteKey={}, confidence={}",
                detection.captchaType, detection.siteKey, detection.confidence
            )

            // Build solve request
            val request = CaptchaSolveRequest(
                type = detection.captchaType,
                siteKey = detection.siteKey ?: "",
                pageUrl = driver.currentUrl(),
                metadata = detection.metadata,
                proxy = config.solveProxy
            )

            // Solve with retries
            var solution = CaptchaSolution.failed(CaptchaServiceProvider.NONE, "Not attempted")
            for (attempt in 1..config.maxRetries) {
                solution = captchaSolver.solve(request)
                if (solution.isSolved) break
                logger.debug("Solve attempt {}/{} failed: {}", attempt, config.maxRetries, solution.error)
            }

            if (!solution.isSolved) {
                logger.warn("Failed to solve CAPTCHA after {} attempts: {}", config.maxRetries, solution.error)
                return solution
            }

            // Inject the token
            val injector = tokenInjectors[detection.captchaType]
            if (injector == null) {
                logger.warn("No token injector for CAPTCHA type: {}", detection.captchaType)
                return solution
            }

            val injected = injector.inject(driver, solution, detection)
            if (!injected) {
                logger.warn("Failed to inject token for {}", detection.captchaType)
                return solution
            }

            markAsProcessed(driver)
            val elapsedMs = System.currentTimeMillis() - startTime
            logger.info("CAPTCHA solved and injected in {}ms", elapsedMs)

            solution
        } catch (e: Exception) {
            logger.warn("CAPTCHA auto-solve error: {}", e.message)
            null
        }
    }

    /**
     * Check if a CAPTCHA was already processed for the current page load.
     * Uses a window variable flag to prevent double-solving.
     */
    private suspend fun isAlreadyProcessed(driver: WebDriver): Boolean {
        return try {
            val flag = driver.evaluateValue("window.__pulsar_captcha_solved__") as? Boolean
            flag == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Set a flag on the page indicating CAPTCHA was processed.
     */
    private suspend fun markAsProcessed(driver: WebDriver) {
        try {
            driver.evaluateValue("window.__pulsar_captcha_solved__ = true;")
        } catch (_: Exception) {
            // Best effort
        }
    }
}
