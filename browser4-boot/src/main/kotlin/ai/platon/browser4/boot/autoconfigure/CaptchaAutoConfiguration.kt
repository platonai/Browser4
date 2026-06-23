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
package ai.platon.browser4.boot.autoconfigure

import ai.platon.pulsar.captcha.*
import ai.platon.pulsar.captcha.detection.*
import ai.platon.pulsar.captcha.inject.*
import ai.platon.pulsar.captcha.integration.CaptchaBrowseEventHandler
import ai.platon.pulsar.captcha.integration.CaptchaPageCategorySniffer
import ai.platon.pulsar.captcha.provider.AntiCaptchaProvider
import ai.platon.pulsar.captcha.provider.CapSolverProvider
import ai.platon.pulsar.captcha.provider.TwoCaptchaProvider
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import ai.platon.pulsar.captcha.tools.CaptchaToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.event.PulsarEventBus
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Spring Boot auto-configuration for CAPTCHA solving.
 *
 * Conditionally enabled when `captcha.auto.solve.enabled=true` (default).
 * Creates the full CAPTCHA solving pipeline:
 * - Detection chain (reCAPTCHA, hCaptcha, Turnstile, image)
 * - Solver chain (CapSolver, 2Captcha, Anti-Captcha)
 * - Token injectors
 * - Browse event handler for auto-solve (registered on PulsarEventBus)
 * - LLM agent tool executor (captcha domain)
 * - Page category sniffer
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["captcha.auto.solve.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class CaptchaAutoConfiguration {
    private val logger = getLogger(CaptchaAutoConfiguration::class)

    @Bean(name = ["captchaConfig"])
    @ConditionalOnMissingBean(name = ["captchaConfig"])
    open fun captchaConfig(conf: MutableConfig): CaptchaConfig {
        return CaptchaConfig.fromConfig(conf)
    }

    // ---- Detection ----

    @Bean(name = ["captchaDetector"])
    @ConditionalOnMissingBean(name = ["captchaDetector"])
    open fun captchaDetector(conf: MutableConfig): ChainedCaptchaDetector {
        val chain = ChainedCaptchaDetector(conf)
        chain.addLast(RecaptchaDetector(conf))
        chain.addLast(HCaptchaDetector(conf))
        chain.addLast(TurnstileDetector(conf))
        chain.addLast(ImageCaptchaDetector(conf))
        logger.info("CAPTCHA detection chain initialized with {} detectors", chain.size)
        return chain
    }

    // ---- Token Injectors ----

    @Bean(name = ["captchaTokenInjectors"])
    @ConditionalOnMissingBean(name = ["captchaTokenInjectors"])
    open fun captchaTokenInjectors(): Map<CaptchaType, CaptchaTokenInjector> {
        return mapOf(
            CaptchaType.RECAPTCHA_V2 to RecaptchaTokenInjector(),
            CaptchaType.RECAPTCHA_V2_INVISIBLE to RecaptchaTokenInjector(),
            CaptchaType.RECAPTCHA_V3 to RecaptchaTokenInjector(),
            CaptchaType.RECAPTCHA_ENTERPRISE to RecaptchaTokenInjector(),
            CaptchaType.HCAPTCHA to HCaptchaTokenInjector(),
            CaptchaType.HCAPTCHA_ENTERPRISE to HCaptchaTokenInjector(),
            CaptchaType.TURNSTILE to TurnstileTokenInjector()
        )
    }

    // ---- Solver ----

    @Bean(name = ["captchaSolver"])
    @ConditionalOnMissingBean(name = ["captchaSolver"])
    open fun captchaSolver(conf: MutableConfig, captchaConfig: CaptchaConfig): ChainedCaptchaSolver {
        val chain = ChainedCaptchaSolver(conf)

        // Add primary solver first
        val primary = captchaConfig.primaryProvider
        when (primary) {
            CaptchaServiceProvider.CAPSOLVER -> {
                captchaConfig.capsolverApiKey?.let { key ->
                    chain.addLast(CapSolverProvider(key, captchaConfig.solveTimeout, captchaConfig.pollInterval))
                    logger.info("CapSolver registered as primary CAPTCHA solver")
                }
            }
            CaptchaServiceProvider.TWO_CAPTCHA -> {
                captchaConfig.twoCaptchaApiKey?.let { key ->
                    chain.addLast(TwoCaptchaProvider(key, captchaConfig.solveTimeout, captchaConfig.pollInterval))
                    logger.info("2Captcha registered as primary CAPTCHA solver")
                }
            }
            CaptchaServiceProvider.ANTI_CAPTCHA -> {
                captchaConfig.antiCaptchaApiKey?.let { key ->
                    chain.addLast(AntiCaptchaProvider(key, captchaConfig.solveTimeout, captchaConfig.pollInterval))
                    logger.info("Anti-Captcha registered as primary CAPTCHA solver")
                }
            }
            CaptchaServiceProvider.NONE -> { /* No solver configured */ }
        }

        // Add fallback solvers (skip primary to avoid duplicates)
        captchaConfig.capsolverApiKey?.let { key ->
            if (primary != CaptchaServiceProvider.CAPSOLVER) {
                chain.addLast(CapSolverProvider(key, captchaConfig.solveTimeout, captchaConfig.pollInterval))
                logger.info("CapSolver registered as fallback CAPTCHA solver")
            }
        }
        captchaConfig.twoCaptchaApiKey?.let { key ->
            if (primary != CaptchaServiceProvider.TWO_CAPTCHA) {
                chain.addLast(TwoCaptchaProvider(key, captchaConfig.solveTimeout, captchaConfig.pollInterval))
                logger.info("2Captcha registered as fallback CAPTCHA solver")
            }
        }
        captchaConfig.antiCaptchaApiKey?.let { key ->
            if (primary != CaptchaServiceProvider.ANTI_CAPTCHA) {
                chain.addLast(AntiCaptchaProvider(key, captchaConfig.solveTimeout, captchaConfig.pollInterval))
                logger.info("Anti-Captcha registered as fallback CAPTCHA solver")
            }
        }

        logger.info("CAPTCHA solver chain initialized with {} solver(s)", chain.size)
        return chain
    }

    // ---- Browse Event Handler ----

    @Bean(name = ["captchaBrowseEventHandler"])
    @ConditionalOnMissingBean(name = ["captchaBrowseEventHandler"])
    open fun captchaBrowseEventHandler(
        captchaDetector: ChainedCaptchaDetector,
        captchaSolver: ChainedCaptchaSolver,
        captchaTokenInjectors: Map<CaptchaType, CaptchaTokenInjector>,
        captchaConfig: CaptchaConfig
    ): CaptchaBrowseEventHandler {
        val handler = CaptchaBrowseEventHandler(
            captchaDetector = captchaDetector,
            captchaSolver = captchaSolver,
            tokenInjectors = captchaTokenInjectors,
            config = captchaConfig
        )

        // Register on the global event bus so it fires during every browse session
        try {
            PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onDocumentSteady?.addLast(handler)
            logger.info("CAPTCHA browse event handler registered on onDocumentSteady")
        } catch (e: Exception) {
            logger.warn("Failed to register CAPTCHA browse event handler: {}", e.message)
        }

        return handler
    }

    // ---- LLM Agent Tool Executor ----

    @Bean(name = ["captchaToolExecutor"])
    @ConditionalOnMissingBean(name = ["captchaToolExecutor"])
    open fun captchaToolExecutor(
        captchaDetector: ChainedCaptchaDetector,
        captchaSolver: ChainedCaptchaSolver,
        captchaTokenInjectors: Map<CaptchaType, CaptchaTokenInjector>,
        captchaConfig: CaptchaConfig
    ): CaptchaToolExecutor {
        val executor = CaptchaToolExecutor(
            captchaSolver = captchaSolver,
            captchaDetector = captchaDetector,
            tokenInjectors = captchaTokenInjectors,
            config = captchaConfig
        )

        // Register in CustomToolRegistry so AgentToolManager can dispatch captcha.* tool calls
        try {
            if (!CustomToolRegistry.instance.contains(executor.domain)) {
                CustomToolRegistry.instance.register(executor)
                logger.info("CAPTCHA tool executor registered in CustomToolRegistry for domain '{}'", executor.domain)
            }
        } catch (e: Exception) {
            logger.warn("Failed to register CAPTCHA tool executor in CustomToolRegistry: {}", e.message)
        }

        return executor
    }

    // ---- Page Category Sniffer ----

    @Bean(name = ["captchaPageCategorySniffer"])
    @ConditionalOnMissingBean(name = ["captchaPageCategorySniffer"])
    open fun captchaPageCategorySniffer(conf: MutableConfig): CaptchaPageCategorySniffer {
        return CaptchaPageCategorySniffer(conf)
    }
}
