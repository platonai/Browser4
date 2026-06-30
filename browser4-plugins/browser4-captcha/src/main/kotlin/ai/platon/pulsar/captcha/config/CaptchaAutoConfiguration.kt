package ai.platon.pulsar.captcha.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.captcha.CaptchaConfig
import ai.platon.pulsar.captcha.CaptchaServiceProvider
import ai.platon.pulsar.captcha.CaptchaType
import ai.platon.pulsar.captcha.ChainedCaptchaSolver
import ai.platon.pulsar.captcha.detection.ChainedCaptchaDetector
import ai.platon.pulsar.captcha.detection.HCaptchaDetector
import ai.platon.pulsar.captcha.detection.ImageCaptchaDetector
import ai.platon.pulsar.captcha.detection.RecaptchaDetector
import ai.platon.pulsar.captcha.detection.TurnstileDetector
import ai.platon.pulsar.captcha.inject.CaptchaTokenInjector
import ai.platon.pulsar.captcha.inject.HCaptchaTokenInjector
import ai.platon.pulsar.captcha.inject.RecaptchaTokenInjector
import ai.platon.pulsar.captcha.inject.TurnstileTokenInjector
import ai.platon.pulsar.captcha.integration.CaptchaBrowseEventHandler
import ai.platon.pulsar.captcha.integration.CaptchaPageCategorySniffer
import ai.platon.pulsar.captcha.provider.AntiCaptchaProvider
import ai.platon.pulsar.captcha.provider.CapSolverProvider
import ai.platon.pulsar.captcha.provider.TwoCaptchaProvider
import ai.platon.pulsar.captcha.tools.CaptchaToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.protocol.browser.emulator.util.PageSnifferMount
import ai.platon.pulsar.skeleton.event.BrowseEventHandlers
import ai.platon.pulsar.skeleton.plugin.BrowseEventMount
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
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
 * - Browse event handler for auto-solve
 * - LLM agent tool executor (captcha domain)
 * - Page category sniffer
 *
 * Implements [BrowseEventMount], [ToolMount], and [PageSnifferMount] so that
 * [ai.platon.browser4.boot.plugin.PluginManager] can automatically wire the
 * handlers and tools into the appropriate integration points.
 */
@AutoConfiguration
@ConditionalOnClass(name = ["ai.platon.pulsar.captcha.CaptchaConfig"])
@ConditionalOnProperty(name = ["captcha.auto.solve.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class CaptchaAutoConfiguration(
    private val applicationContext: ApplicationContext
) : BrowseEventMount, ToolMount, PageSnifferMount {

    private val logger = getLogger(CaptchaAutoConfiguration::class)

    // ---- Mount Points ----

    override fun configureBrowseHandlers(handlers: BrowseEventHandlers) {
        try {
            val handler = applicationContext.getBean("captchaBrowseEventHandler") as CaptchaBrowseEventHandler
            handlers.onDocumentSteady.addLast(handler)
            logger.info("CAPTCHA browse event handler registered on onDocumentSteady via BrowseEventMount")
        } catch (e: Exception) {
            logger.warn("Failed to register captcha browse event handler: {}", e.message)
        }
    }

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("captchaToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            logger.warn("Failed to get captchaToolExecutor for mount: {}", e.message)
            emptyList()
        }
    }

    override fun getPageSniffers(): List<ai.platon.pulsar.protocol.browser.emulator.util.PageCategorySniffer> {
        return try {
            listOf(applicationContext.getBean("captchaPageCategorySniffer") as CaptchaPageCategorySniffer)
        } catch (e: Exception) {
            logger.warn("Failed to get captchaPageCategorySniffer for mount: {}", e.message)
            emptyList()
        }
    }

    // ---- Beans ----

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
        return CaptchaBrowseEventHandler(
            captchaDetector = captchaDetector,
            captchaSolver = captchaSolver,
            tokenInjectors = captchaTokenInjectors,
            config = captchaConfig
        )
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
        return CaptchaToolExecutor(
            captchaSolver = captchaSolver,
            captchaDetector = captchaDetector,
            tokenInjectors = captchaTokenInjectors,
            config = captchaConfig
        )
    }

    // ---- Page Category Sniffer ----

    @Bean(name = ["captchaPageCategorySniffer"])
    @ConditionalOnMissingBean(name = ["captchaPageCategorySniffer"])
    open fun captchaPageCategorySniffer(conf: MutableConfig): CaptchaPageCategorySniffer {
        return CaptchaPageCategorySniffer(conf)
    }
}
