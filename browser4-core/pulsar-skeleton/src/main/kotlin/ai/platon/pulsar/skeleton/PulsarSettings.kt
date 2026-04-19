package ai.platon.pulsar.skeleton

import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.browser4.driver.common.DisplayMode
import ai.platon.browser4.driver.common.DomSettlePolicy
import ai.platon.browser4.driver.common.InteractSettings
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.InteractLevel
import ai.platon.pulsar.common.config.CapabilityTypes.*
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.core.api.PulsarSettings

/**
 * The [PulsarSettings] class defines a convenient interface to control the behavior of Browser4.
 *
 * For example, to run multiple temporary browsers in headless mode, which is usually used in the spider scenario,
 * you can use the following code:
 *
 * ```kotlin
 * PulsarSettings
 *    .headless()
 *    .maxBrowsers(4)
 *    .maxOpenTabs(12)
 *    .enableUrlBlocking()
 * ```
 *
 * The above code will:
 * 1. Run the browser in headless mode
 * 2. Set the number of privacy contexts to 4
 * 3. Set the max number of open tabs in each browser context to 12
 * 4. Enable url blocking
 *
 * If you want to run your system's default browser in GUI mode, and interact with the webpage, you can use the
 * following code:
 *
 * ```kotlin
 * PulsarSettings.withSystemDefaultBrowser().withGUI().withSPA()
 * ```
 *
 * The above code will:
 * 1. Use the system's default browser
 * 2. Run the browser in GUI mode
 * 3. Set the system to work with single page application
 * */
data class PulsarSettings(
    val spa: Boolean? = null,
    val displayMode: DisplayMode? = null,
    val maxBrowsers: Int? = null,
    val maxOpenTabs: Int? = null,
    val interactSettings: InteractSettings? = null,
    val profileMode: BrowserProfileMode? = null,
) {
    fun overrideSystemProperties() {
        overrideConfigurationInternal(null)
    }

    fun overrideConfiguration(conf: MutableConfig) {
        overrideConfigurationInternal(conf)
    }

    private fun overrideConfigurationInternal(conf: MutableConfig? = null) {
        profileMode?.let { withBrowserContextMode(profileMode, conf) }
        when(displayMode) {
            DisplayMode.HEADLESS -> headless(conf)
            DisplayMode.GUI -> headed(conf)
            DisplayMode.SUPERVISED -> supervised(conf)
            else -> {}
        }
        maxBrowsers?.let { maxBrowserContexts(maxBrowsers) }
        maxOpenTabs?.let { maxOpenTabs(maxOpenTabs, conf) }
        spa?.takeIf { it }?.let { withSPA(conf) }
        interactSettings?.let { withInteractSettings(interactSettings, conf) }
    }

    companion object {

        fun parse(capabilities: Map<String, Any?>? = null): PulsarSettings {
            val spa: Boolean? = capabilities?.get("spa")?.toString()?.toBooleanStrictOrNull()
            val displayMode: DisplayMode? = capabilities?.get("displayMode")?.toString()?.let { DisplayMode.fromString(it) }
            val maxBrowsers: Int? = capabilities?.get("maxBrowsers")?.toString()?.toIntOrNull()
            val maxOpenTabs: Int? = capabilities?.get("maxOpenTabs")?.toString()?.toIntOrNull()
            val interactSettings: InteractSettings? = capabilities?.get("interactSettings")?.toString()?.let { InteractSettings.fromJsonOrNull(it) }
            val profileMode = capabilities?.get("profileMode")?.toString()?.let { BrowserProfileMode.fromString(it) }

            return PulsarSettings(
                spa = spa,
                displayMode = displayMode,
                maxBrowsers = maxBrowsers,
                maxOpenTabs = maxOpenTabs,
                profileMode = profileMode,
                interactSettings = interactSettings,
            )
        }

        @JvmStatic
        @JvmOverloads
        fun withBrowserContextMode(contextMode: BrowserProfileMode, conf: MutableConfig? = null): Companion =
            withBrowserContextMode(contextMode, BrowserType.DEFAULT, conf)

        @JvmStatic
        @JvmOverloads
        fun withBrowserContextMode(contextMode: BrowserProfileMode, browserType: BrowserType, conf: MutableConfig? = null): Companion {
            BrowserSettings.withBrowserContextMode(contextMode, browserType, conf)
            return PulsarSettings
        }

        @JvmStatic
        @JvmOverloads
        fun withBrowser(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            BrowserSettings.withBrowser(browserType, conf)
            return PulsarSettings
        }

        /**
         * Use the system's default Chrome browser, so Browser4 visits websites just like you do.
         * Any change to the browser will be kept.
         *
         * No longer supported by Chrome since v143.
         * See https://github.com/platonai/Browser4/issues/162
         * */
        @JvmStatic
        @JvmOverloads
        fun withSystemDefaultBrowser(conf: MutableConfig? = null) = withBrowserContextMode(BrowserProfileMode.SYSTEM_DEFAULT, BrowserType.DEFAULT, conf)

        /**
         * Use the system's default browser with the given type, so Browser4 visits websites just like you do.
         * Any change to the browser will be kept.
         *
         * No longer supported by Chrome since v143.
         * See https://github.com/platonai/Browser4/issues/162
         * */
        @JvmStatic
        @JvmOverloads
        fun withSystemDefaultBrowser(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            return withBrowserContextMode(BrowserProfileMode.SYSTEM_DEFAULT, browserType, conf)
        }

        /**
         * Use the default browser which has an isolated profile and user data directory.
         * Any modifications made to the browser will be preserved, including the cookies, history, etc.
         * */
        @JvmStatic
        @JvmOverloads
        fun withDefaultBrowser(conf: MutableConfig? = null) = withBrowserContextMode(BrowserProfileMode.DEFAULT, BrowserType.DEFAULT, conf)

        /**
         * Use the default browser which has an isolated profile and user data directory.
         * Any modifications made to the browser will be preserved, including the cookies, history, etc.
         *
         *
         * */
        @JvmStatic
        @JvmOverloads
        fun withDefaultBrowser(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            return withBrowserContextMode(BrowserProfileMode.DEFAULT, browserType, conf)
        }

        /**
         * Use Google Chrome with the prototype environment.
         * Any modifications made to the browser will be preserved.
         * Sequential and temporary browsers will inherit the environment from the prototype browser.
         */
        @JvmStatic
        @JvmOverloads
        fun withPrototypeBrowser(conf: MutableConfig? = null) = withBrowserContextMode(BrowserProfileMode.PROTOTYPE, BrowserType.DEFAULT, conf)

        /**
         * Use the specified browser with the prototype environment.
         * Any modifications made to the browser will be preserved.
         * Sequential and temporary browsers will inherit the environment from the prototype browser.
         * */
        @JvmStatic
        @JvmOverloads
        fun withPrototypeBrowser(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            return withBrowserContextMode(BrowserProfileMode.PROTOTYPE, browserType, conf)
        }

        /**
         * Use sequential browsers that inherits the prototype browser’s environment.
         * The sequential browsers are permanent unless the context directories are deleted manually.
         *
         * @return the PulsarSettings itself
         * */
        @JvmStatic
        @JvmOverloads
        fun withSequentialBrowsers(conf: MutableConfig? = null): Companion {
            return withSequentialBrowsers(BrowserType.DEFAULT, 10, conf)
        }

        /**
         * Use sequential browsers that inherits the prototype browser’s environment.
         * The sequential browsers are permanent unless the context directories are deleted manually.
         *
         *
         *
         * @return the PulsarSettings itself
         * */
        @JvmStatic
        @JvmOverloads
        fun withSequentialBrowsers(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            return withSequentialBrowsers(browserType, 10, conf)
        }

        /**
         * Use sequential browsers that inherits from the prototype browser’s environment. The sequential browsers are
         * permanent unless the context directories are deleted manually.
         *
         * @param maxAgents The maximum number of sequential privacy agents, the active privacy contexts is chosen from them.
         * @return the PulsarSettings itself
         * */
        @JvmStatic
        @JvmOverloads
        fun withSequentialBrowsers(browserType: BrowserType, maxAgents: Int, conf: MutableConfig? = null): Companion {
            BrowserSettings.withSequentialBrowsers(browserType, maxAgents, conf)
            return PulsarSettings
        }

        /**
         * Use a temporary browser that inherits from the prototype browser’s environment. The temporary browser
         * will not be used again after it is shut down.
         * */
        @JvmStatic
        @JvmOverloads
        fun withTemporaryBrowser(conf: MutableConfig? = null): Companion {
            return withBrowserContextMode(BrowserProfileMode.TEMPORARY, BrowserType.DEFAULT, conf)
        }

        /**
         * Use a temporary browser that inherits from the prototype browser’s environment. The temporary browser
         * will not be used again after it is shut down.
         * */
        @JvmStatic
        @JvmOverloads
        fun withTemporaryBrowser(browserType: BrowserType, conf: MutableConfig? = null) =
            withBrowserContextMode(BrowserProfileMode.TEMPORARY, browserType, conf)

        /**
         * Launch the browser in GUI mode.
         * */
        @JvmStatic
        @JvmOverloads
        fun withGUI(conf: MutableConfig? = null): Companion {
            BrowserSettings.withGUI(conf)
            return PulsarSettings
        }

        /**
         * Launch the browser in GUI mode.
         * */
        @JvmStatic
        @JvmOverloads
        fun headed(conf: MutableConfig? = null) = withGUI(conf)

        /**
         * Launch the browser in headless mode.
         * */
        @JvmStatic
        @JvmOverloads
        fun headless(conf: MutableConfig? = null): Companion {
            BrowserSettings.headless(conf)
            return PulsarSettings
        }

        /**
         * Launch the browser in supervised mode.
         * */
        @JvmStatic
        @JvmOverloads
        fun supervised(conf: MutableConfig? = null): Companion {
            BrowserSettings.supervised(conf)
            return PulsarSettings
        }

        /**
         * Set the max number of agents
         * */
        @JvmStatic
        @JvmOverloads
        fun maxBrowserContexts(n: Int, conf: MutableConfig? = null): Companion {
            BrowserSettings.maxBrowserContexts(n, conf)
            return PulsarSettings
        }

        /**
         * Set the max number to open tabs in each browser context
         * */
        @JvmStatic
        @JvmOverloads
        fun maxOpenTabs(n: Int, conf: MutableConfig? = null): Companion {
            // BrowserSettings.maxOpenTabs does not currently accept conf; we fallback to system property for conf=null
            if (conf == null) {
                BrowserSettings.maxOpenTabs(n)
            } else {
                conf[BROWSER_MAX_OPEN_TABS] = "$n"
            }
            return PulsarSettings
        }

        /**
         * Tell the system to work with single page application.
         * To collect SPA data, the execution needs to have no timeout limit.
         * */
        @JvmStatic
        @JvmOverloads
        fun withSPA(conf: MutableConfig? = null) = withSinglePageApplication(conf)

        /**
         * Tell the system to work with single page application.
         * To collect SPA data, the execution needs to have no timeout limit.
         * */
        @JvmStatic
        @JvmOverloads
        fun withSinglePageApplication(conf: MutableConfig? = null): Companion {
            BrowserSettings.withSPA(conf)
            return PulsarSettings
        }

        /**
         * Defines the level of interaction with a webpage during crawling.
         *
         * Higher levels involve more interaction (e.g., scrolling, clicking),
         * which may improve content extraction quality at the cost of speed.
         * */
        @JvmStatic
        @JvmOverloads
        fun withInteractLevel(level: InteractLevel, conf: MutableConfig? = null): Companion {
            // Interact settings modify config via settings override; no direct conf mapping here
            val settings = InteractSettings.create(level)
            BrowserSettings.withInteractSettings(settings, conf)
            return PulsarSettings
        }

        /**
         * Use the specified interact settings to interact with webpages.
         * */
        @JvmStatic
        @JvmOverloads
        fun withInteractSettings(settings: InteractSettings, conf: MutableConfig? = null): Companion {
            // Interact settings override system properties; conf routing is handled inside settings if needed
            BrowserSettings.withInteractSettings(settings, conf)
            return PulsarSettings
        }

        fun withDOMSettlePolicy(domSettlePolicy: DomSettlePolicy, conf: MutableConfig? = null): Companion {
            val settings = InteractSettings.DEFAULT.copy(domSettlePolicy = domSettlePolicy)
            BrowserSettings.withInteractSettings(settings, conf)
            return PulsarSettings
        }

        /**
         * Sets the Large Language Model (LLM) provider for the Browser4 settings.
         *
         * This function allows specifying the LLM provider to be used. The provider must be a non-null string.
         *
         * Supported LLM providers include:
         * * <a href='https://www.volcengine.com/docs/82379/1399008'>Volcengine API</a>
         * * <a href='https://api-docs.deepseek.com/'>DeepSeek API</a>
         *
         * For example, you can use the following code to set the LLM provider:
         * ```kotlin
         * // use volcengine as the LLM provider
         * PulsarSettings.withLLM("volcengine", "ep-20250218132011-2scs8", apiKey)
         * ```
         *
         * @param provider The name of the LLM provider to be used. Must not be null.
         * @return The current instance of [PulsarSettings] to allow method chaining.
         * @throws IllegalArgumentException If the provided `provider` is null.
         */
        @JvmStatic
        fun withLLM(provider: String, name: String, apiKey: String): Companion {
            withLLMProvider(provider)
            withLLMName(name)
            withLLMAPIKey(apiKey)
            return PulsarSettings
        }

        /**
         * Sets the Large Language Model (LLM) provider for the Browser4 settings.
         *
         * This function allows specifying the LLM provider to be used. The provider must be a non-null string.
         *
         * Supported LLM providers include:
         * * <a href='https://www.volcengine.com/docs/82379/1399008'>Volcengine API</a>
         * * <a href='https://api-docs.deepseek.com/'>DeepSeek API</a>
         *
         * For example, you can use the following code to set the LLM provider:
         * ```kotlin
         * PulsarSettings
         *     .withLLMProvider("volcengine") // use volcengine as the LLM provider
         *     .withLLMName("ep-20250218132011-2scs8") // the LLM name, you should change it to your own
         *     .withLLMAPIKey(apiKey) // the LLM api key, you should change it to your own
         * ```
         *
         * @param provider The name of the LLM provider to be used. Must not be null.
         * @return The current instance of [PulsarSettings] to allow method chaining.
         * @throws IllegalArgumentException If the provided `provider` is null.
         */
        @JvmStatic
        fun withLLMProvider(provider: String?): Companion {
            // Validate that the provider is not null
            requireNotNull(provider) { "$LLM_PROVIDER NOT set" }

            // Set the LLM provider as a system property
            System.setProperty(LLM_PROVIDER, provider)
            return PulsarSettings
        }

        /**
         * Sets the Large Language Model (LLM) name for the Browser4 settings.
         *
         * Supported LLM providers include:
         * * <a href='https://www.volcengine.com/docs/82379/1399008'>Volcengine API</a>
         * * <a href='https://api-docs.deepseek.com/'>DeepSeek API</a>
         *
         * For example, you can use the following code to set the LLM provider:
         * ```kotlin
         * PulsarSettings
         *     .withLLMProvider("volcengine") // use volcengine as the LLM provider
         *     .withLLMName("ep-20250218132011-2scs8") // the LLM name, you should change it to your own
         *     .withLLMAPIKey(apiKey) // the LLM api key, you should change it to your own
         * ```
         *
         * @param name The name of the Large Language Model (LLM) to be set. This parameter cannot be null.
         * @return The current instance of [PulsarSettings] to allow for method chaining.
         * @throws IllegalArgumentException If the provided name is null.
         */
        @JvmStatic
        fun withLLMName(name: String?): Companion {
            // Validate that the LLM name is not null
            requireNotNull(name) { "$LLM_NAME NOT set" }

            // Set the LLM name as a system property
            System.setProperty(LLM_NAME, name)
            return PulsarSettings
        }

        /**
         * Sets the Large Language Model (LLM) API key for the Browser4 settings.
         *
         * Supported LLM providers include:
         * * <a href='https://www.volcengine.com/docs/82379/1399008'>Volcengine API</a>
         * * <a href='https://api-docs.deepseek.com/'>DeepSeek API</a>
         *
         * For example, you can use the following code to set the LLM provider:
         * ```kotlin
         * PulsarSettings
         *     .withLLMProvider("volcengine") // use volcengine as the LLM provider
         *     .withLLMName("ep-20250218132011-2scs8") // the LLM name, you should change it to your own
         *     .withLLMAPIKey(apiKey) // the LLM api key, you should change it to your own
         * ```
         *
         * @param key The API key to be set. This parameter cannot be null, as the LLM service requires a valid API key.
         * @return The current instance of [PulsarSettings] to allow for method chaining.
         * @throws IllegalArgumentException If the provided API key is null, indicating that a valid key is required.
         */
        @JvmStatic
        fun withLLMAPIKey(key: String?): Companion {
            // Validate that the API key is not null before setting it as a system property.
            requireNotNull(key) { "$LLM_API_KEY not set" }

            // Set the provided API key as a system property for global access.
            System.setProperty(LLM_API_KEY, key)

            return PulsarSettings
        }
    }
}
