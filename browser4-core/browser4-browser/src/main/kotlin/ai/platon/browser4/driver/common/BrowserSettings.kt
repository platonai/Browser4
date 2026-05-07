package ai.platon.browser4.driver.common

import ai.platon.browser4.driver.chrome.common.ChromeOptions
import ai.platon.pulsar.common.Runtimes
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.config.AppConstants.*
import ai.platon.pulsar.common.config.CapabilityTypes.*
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.proxy.ProxyEntry
import java.net.URI
import java.time.Duration

open class BrowserSettings constructor(
    /**
     * The configuration.
     * */
    val config: ImmutableConfig = ImmutableConfig(loadDefaults = true)
) {
    companion object {
        private val logger = getLogger(BrowserSettings::class)

        /**
         * The viewport size for browser to rendering all webpages.
         * */
        var VIEWPORT = DEFAULT_VIEWPORT!!

        /**
         * The screenshot quality.
         * Compression quality from range [0..100] (jpeg only) to capture screenshots.
         * */
        var SCREENSHOT_QUALITY = 50

        /**
         * The default script confuser, which is used to confuse the javascript that will be injected to the webpage.
         *
         * If you want to use a custom script confuser, you need to set the field before the BrowserSettings object is created.
         * If you are using spring boot, you should set the field in a ApplicationContextInitializer.
         * */
        var SCRIPT_CONFUSER: ScriptConfuser = SimpleScriptConfuser()

        /**
         * Specify the browser type to fetch webpages.
         * */
        @JvmStatic
        fun withBrowser(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            if (browserType == BrowserType.PLAYWRIGHT_CHROME) {
                System.err.println("Warning: PLAYWRIGHT support is deprecated")
            }

            if (conf == null) {
                System.setProperty(BROWSER_TYPE, browserType.name)
            } else {
                conf[BROWSER_TYPE] = browserType.name
            }
            return BrowserSettings
        }

        @JvmStatic
        fun overrideBrowserContextMode(sourceConf: ImmutableConfig, targetConf: MutableConfig? = null): Companion {
            val modeString = sourceConf[BROWSER_CONTEXT_MODE]?.uppercase()
            val browserType = sourceConf.getEnum(BROWSER_TYPE, BrowserType.DEFAULT)
            val mode = BrowserProfileMode.fromString(modeString)
            return withBrowserContextMode(mode, browserType, targetConf)
        }

        @JvmStatic
        fun withBrowserContextMode(contextMode: BrowserProfileMode, conf: MutableConfig? = null): Companion =
            withBrowserContextMode(contextMode, BrowserType.DEFAULT, conf)

        @JvmStatic
        fun withBrowserContextMode(contextMode: BrowserProfileMode, browserType: BrowserType, conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(BROWSER_CONTEXT_MODE, contextMode.name)
            } else {
                conf[BROWSER_CONTEXT_MODE] = contextMode.name
            }

            when (contextMode) {
                BrowserProfileMode.SYSTEM_DEFAULT -> {
                    withSystemDefaultBrowserInternal(browserType, conf)
                }
                BrowserProfileMode.DEFAULT -> {
                    withDefaultBrowserInternal(browserType, conf)
                }
                BrowserProfileMode.PROTOTYPE -> {
                    withPrototypeBrowserInternal(browserType, conf)
                }
                BrowserProfileMode.TEMPORARY -> {
                    withTemporaryBrowserInternal(browserType, conf)
                }
                BrowserProfileMode.SEQUENTIAL -> {
                    withSequentialBrowsersInternal(browserType, 10, conf)
                }
            }

            return this
        }

        private fun withSystemDefaultBrowserInternal(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            val clazz = "ai.platon.pulsar.skeleton.workflow.fetch.privacy.SystemDefaultPrivacyAgentGenerator"
            if (conf == null) {
                System.setProperty(PRIVACY_AGENT_GENERATOR_CLASS, clazz)
            } else {
                conf[PRIVACY_AGENT_GENERATOR_CLASS] = clazz
            }
            withBrowser(browserType, conf).maxBrowserContexts(1, conf).maxOpenTabs(50).withSPA(conf)
            return BrowserSettings
        }

        private fun withDefaultBrowserInternal(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            val clazz = "ai.platon.pulsar.skeleton.workflow.fetch.privacy.DefaultPrivacyAgentGenerator"
            if (conf == null) {
                System.setProperty(PRIVACY_AGENT_GENERATOR_CLASS, clazz)
            } else {
                conf[PRIVACY_AGENT_GENERATOR_CLASS] = clazz
            }
            withBrowser(browserType, conf).maxBrowserContexts(1, conf).maxOpenTabs(50).withSPA(conf)
            return BrowserSettings
        }

        private fun withPrototypeBrowserInternal(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            val clazz = "ai.platon.pulsar.skeleton.workflow.fetch.privacy.PrototypePrivacyAgentGenerator"
            if (conf == null) {
                System.setProperty(PRIVACY_AGENT_GENERATOR_CLASS, clazz)
            } else {
                conf[PRIVACY_AGENT_GENERATOR_CLASS] = clazz
            }
            withBrowser(browserType, conf).maxBrowserContexts(1, conf).maxOpenTabs(50).withSPA(conf)
            return BrowserSettings
        }

        fun withSequentialBrowsers(maxAgents: Int, conf: MutableConfig? = null): Companion {
            return withSequentialBrowsersInternal(BrowserType.PULSAR_CHROME, maxAgents, conf)
        }

        fun withSequentialBrowsers(browserType: BrowserType, maxAgents: Int, conf: MutableConfig? = null): Companion {
            return withSequentialBrowsersInternal(browserType, maxAgents, conf)
        }

        private fun withSequentialBrowsersInternal(browserType: BrowserType, maxAgents: Int, conf: MutableConfig? = null): Companion {
            withBrowser(browserType, conf)
            if (conf == null) {
                System.setProperty(BROWSER_CONTEXT_MODE, "SEQUENTIAL")
                System.setProperty(MAX_SEQUENTIAL_PRIVACY_AGENT_NUMBER, "$maxAgents")
            } else {
                conf[BROWSER_CONTEXT_MODE] = "SEQUENTIAL"
                conf[MAX_SEQUENTIAL_PRIVACY_AGENT_NUMBER] = "$maxAgents"
            }
            val clazz = "ai.platon.pulsar.skeleton.workflow.fetch.privacy.SequentialPrivacyAgentGenerator"
            if (conf == null) {
                System.setProperty(PRIVACY_AGENT_GENERATOR_CLASS, clazz)
            } else {
                conf[PRIVACY_AGENT_GENERATOR_CLASS] = clazz
            }
            return BrowserSettings
        }

        private fun withTemporaryBrowserInternal(browserType: BrowserType, conf: MutableConfig? = null): Companion {
            val clazz = "ai.platon.pulsar.skeleton.workflow.fetch.privacy.RandomPrivacyAgentGenerator"
            if (conf == null) {
                System.setProperty(PRIVACY_AGENT_GENERATOR_CLASS, clazz)
            } else {
                conf[PRIVACY_AGENT_GENERATOR_CLASS] = clazz
            }
            withBrowser(browserType, conf)
            return BrowserSettings
        }

        /**
         * Launch the browser in GUI mode.
         * */
        @JvmStatic
        fun withGUI(conf: MutableConfig? = null): Companion {
            if (Runtimes.hasOnlyHeadlessBrowser()) {
                logger.warn("The current environment has no GUI support, fallback to headless mode")
                headless(conf)
                return BrowserSettings
            }

            listOf(
                BROWSER_LAUNCH_SUPERVISOR_PROCESS,
                BROWSER_LAUNCH_SUPERVISOR_PROCESS_ARGS
            ).forEach {
                if (conf == null) System.clearProperty(it) else conf.unset(it)
            }

            if (conf == null) {
                System.setProperty(BROWSER_DISPLAY_MODE, DisplayMode.GUI.name)
            } else {
                conf[BROWSER_DISPLAY_MODE] = DisplayMode.GUI.name
            }

            return BrowserSettings
        }

        /**
         * Launch the browser in GUI mode.
         * */
        @JvmStatic
        fun headed(conf: MutableConfig? = null) = withGUI(conf)

        /**
         * Launch the browser in headless mode.
         * */
        @JvmStatic
        fun headless(conf: MutableConfig? = null): Companion {
            listOf(
                BROWSER_LAUNCH_SUPERVISOR_PROCESS,
                BROWSER_LAUNCH_SUPERVISOR_PROCESS_ARGS
            ).forEach { if (conf == null) System.clearProperty(it) else conf.unset(it) }

            if (conf == null) {
                System.setProperty(BROWSER_DISPLAY_MODE, DisplayMode.HEADLESS.name)
            } else {
                conf[BROWSER_DISPLAY_MODE] = DisplayMode.HEADLESS.name
            }

            return BrowserSettings
        }

        /**
         * Launch the browser in supervised mode.
         * */
        @JvmStatic
        fun supervised(conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(BROWSER_DISPLAY_MODE, DisplayMode.SUPERVISED.name)
            } else {
                conf[BROWSER_DISPLAY_MODE] = DisplayMode.SUPERVISED.name
            }
            return BrowserSettings
        }

        @Deprecated("Use maxBrowserContexts instead", ReplaceWith("BrowserSettings.maxBrowserContexts(n)" ))
        @JvmStatic
        fun maxBrowsers(n: Int, conf: MutableConfig? = null): Companion = maxBrowserContexts(n, conf)

        /**
         * Set the max number of browser contexts, each browser context has a separate profile.
         * */
        @JvmStatic
        fun maxBrowserContexts(n: Int, conf: MutableConfig? = null): Companion {
            if (n <= 0) {
                throw IllegalArgumentException("The number of browser contexts has to be greater than 0")
            }
            if (n > 50) {
                System.err.println("Browser4: The number of browser contexts is too large, it may cause out of disk space")
            }

            // PRIVACY_CONTEXT_NUMBER is deprecated, use BROWSER_CONTEXT_NUMBER instead
            if (conf == null) {
                System.setProperty(PRIVACY_CONTEXT_NUMBER, "$n")
                System.setProperty(BROWSER_CONTEXT_NUMBER, "$n")
            } else {
                conf[PRIVACY_CONTEXT_NUMBER] = "$n"
                conf[BROWSER_CONTEXT_NUMBER] = "$n"
            }

            return BrowserSettings
        }
        /**
         * Set the max number to open tabs in each browser context
         * */
        fun maxOpenTabs(n: Int): Companion {
            require(n > 0) { "The number of open tabs has to be > 0" }
            require(n <= 50) { "The number of open tabs has to be <= 50" }

            System.setProperty(BROWSER_MAX_OPEN_TABS, "$n")
            return BrowserSettings
        }
        /**
         * Tell the system to work with single page application.
         * To collect SPA data, the execution needs to have no timeout limit.
         * */
        @JvmStatic
        fun withSPA(conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(FETCH_TASK_TIMEOUT, Duration.ofDays(1000).toString())
                System.setProperty(PRIVACY_CONTEXT_IDLE_TIMEOUT, Duration.ofDays(1000).toString())
                System.setProperty(BROWSER_SPA_MODE, "true")
            } else {
                conf[FETCH_TASK_TIMEOUT] = Duration.ofDays(1000).toString()
                conf[PRIVACY_CONTEXT_IDLE_TIMEOUT] = Duration.ofDays(1000).toString()
                conf[BROWSER_SPA_MODE] = "true"
            }
            return BrowserSettings
        }
        /**
         * Use the specified interact settings to interact with webpages.
         * */
        fun withInteractSettings(settings: InteractSettings, conf: MutableConfig? = null): Companion {
            if (conf == null) {
                settings.overrideSystemProperties()
            } else {
                settings.overrideConfiguration(conf)
            }

            return BrowserSettings
        }
        /**
         * Enable url blocking. If url blocking is enabled and the blocking rules are set,
         * resources matching the rules will be blocked by the browser.
         * */
        @JvmStatic
        fun enableUrlBlocking(conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(BROWSER_RESOURCE_BLOCK_PROBABILITY, "1.0")
            } else {
                conf[BROWSER_RESOURCE_BLOCK_PROBABILITY] = "1.0"
            }
            return BrowserSettings
        }
        /**
         * Enable url blocking with the given probability.
         * The probability must be in [0, 1].
         * */
        @JvmStatic
        fun enableUrlBlocking(probability: Float, conf: MutableConfig? = null): Companion {
            require(probability in 0.0f..1.0f)
            if (conf == null) {
                System.setProperty(BROWSER_RESOURCE_BLOCK_PROBABILITY, "$probability")
            } else {
                conf[BROWSER_RESOURCE_BLOCK_PROBABILITY] = "$probability"
            }
            return BrowserSettings
        }
        /**
         * Disable url blocking. If url blocking is disabled, blocking rules are ignored.
         * */
        @JvmStatic
        fun disableUrlBlocking(conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(BROWSER_RESOURCE_BLOCK_PROBABILITY, "0.0")
            } else {
                conf[BROWSER_RESOURCE_BLOCK_PROBABILITY] = "0.0"
            }
            return BrowserSettings
        }
        /**
         * Block all images.
         * */
        @JvmStatic
        fun blockImages(conf: MutableConfig? = null): Companion {
            enableUrlBlocking(conf)
            return BrowserSettings
        }
        /**
         * Enable user agent overriding.
         *
         * Inappropriate user agent overriding will be detected by the target website and
         * the visits will be blocked.
         * */
        @JvmStatic
        fun enableUserAgentOverriding(conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(BROWSER_ENABLE_UA_OVERRIDING, "true")
            } else {
                conf[BROWSER_ENABLE_UA_OVERRIDING] = "true"
            }
            return BrowserSettings
        }
        /**
         * Disable user agent overriding.
         *
         * Inappropriate user agent overriding will be detected by the target website and
         * the visits will be blocked.
         * */
        @JvmStatic
        fun disableUserAgentOverriding(conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(BROWSER_ENABLE_UA_OVERRIDING, "false")
            } else {
                conf[BROWSER_ENABLE_UA_OVERRIDING] = "false"
            }
            return BrowserSettings
        }
        /**
         * Export all pages automatically once they are fetched.
         *
         * The export directory is under AppPaths.WEB_CACHE_DIR.
         * A typical export path is:
         *
         * * AppPaths.WEB_CACHE_DIR/default/pulsar_chrome/OK/amazon-com
         * * C:\Users\pereg\AppData\Local\Temp\pulsar-pereg\cache\web\default\pulsar_chrome\OK\amazon-com
         * */
        @JvmStatic
        fun enableOriginalPageContentAutoExporting(conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(FETCH_PAGE_AUTO_EXPORT_LIMIT, Int.MAX_VALUE.toString())
            } else {
                conf[FETCH_PAGE_AUTO_EXPORT_LIMIT] = Int.MAX_VALUE.toString()
            }
            return this
        }
        /**
         * Export at most [limit] pages once they are fetched.
         *
         * The export directory is under AppPaths.WEB_CACHE_DIR.
         * A typical export path is:
         *
         * * AppPaths.WEB_CACHE_DIR/default/pulsar_chrome/OK/amazon-com
         * * C:\Users\pereg\AppData\Local\Temp\pulsar-pereg\cache\web\default\pulsar_chrome\OK\amazon-com
         * */
        @JvmStatic
        fun enableOriginalPageContentAutoExporting(limit: Int, conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(FETCH_PAGE_AUTO_EXPORT_LIMIT, limit.toString())
            } else {
                conf[FETCH_PAGE_AUTO_EXPORT_LIMIT] = limit.toString()
            }
            return this
        }
        /**
         * Disable original page content exporting.
         * */
        @JvmStatic
        fun disableOriginalPageContentAutoExporting(conf: MutableConfig? = null): Companion {
            if (conf == null) {
                System.setProperty(FETCH_PAGE_AUTO_EXPORT_LIMIT, "0")
            } else {
                conf[FETCH_PAGE_AUTO_EXPORT_LIMIT] = "0"
            }
            return this
        }
    }
    /**
     * The javascript to execute by Web browsers.
     * */
    private val jsPropertyNames: List<String>
        get() = config[FETCH_CLIENT_JS_COMPUTED_STYLES, CLIENT_JS_PROPERTY_NAMES].split(", ")

    /**
     * The screen viewport.
     * */
    val viewportSize get() = VIEWPORT

    /**
     * The supervisor process
     * */
    val supervisorProcess get() = config.get(BROWSER_LAUNCH_SUPERVISOR_PROCESS)
    /**
     * The supervisor process arguments
     * */
    val supervisorProcessArgs get() = config.getTrimmedStringCollection(BROWSER_LAUNCH_SUPERVISOR_PROCESS_ARGS)

    /**
     * Add a --no-sandbox flag to launch the chrome if we are running inside a virtual machine,
     * for example, virtualbox, vmware or WSL
     * */
    val noSandbox get() = config.getBoolean(BROWSER_LAUNCH_NO_SANDBOX, true)

    /**
     * The browser's display mode, can be one of the following values:
     * * headless
     * * GUI
     * * supervised
     * */
    val displayMode
        get() = when {
            Runtimes.hasOnlyHeadlessBrowser() -> {
                // force headless mode if there is no display
                headless()
                DisplayMode.HEADLESS
            }
            config[BROWSER_DISPLAY_MODE] != null -> config.getEnum(BROWSER_DISPLAY_MODE, DisplayMode.HEADLESS)
            else -> DisplayMode.GUI
        }

    /**
     * If true, the browser will run in supervised mode.
     * */
    val isSupervised get() = displayMode == DisplayMode.SUPERVISED
    /**
     * If true, the browser will run in headless mode.
     * */
    val isHeadless get() = displayMode == DisplayMode.HEADLESS
    /**
     * If true, the browser will run in GUI mode as normal.
     * */
    val isGUI get() = displayMode == DisplayMode.GUI
    /**
     * Check if it's SPA mode, SPA stands for Single Page Application.
     *
     * If PulsarPRA works in SPA mode:
     * 1. execution of loads and fetches has no timeout limit, so we can interact with the page as long as we want.
     * */
    val isSPA get() = config.getBoolean(BROWSER_SPA_MODE, false)
    /**
     * The probability to block resource requests.
     * The probability must be in [0, 1].
     * */
    val resourceBlockProbability get() = config.getFloat(BROWSER_RESOURCE_BLOCK_PROBABILITY, 0.0f)
    /**
     * Check if user agent overriding is enabled. User agent overriding is disabled by default,
     * because inappropriate user agent overriding can be detected by the website,
     * furthermore, there is no obvious benefits to rotate the user agent.
     * */
    val isUserAgentOverridingEnabled get() = config.getBoolean(BROWSER_ENABLE_UA_OVERRIDING, false)

    val fetchTaskTimeout get() = config.getDuration(FETCH_TASK_TIMEOUT, FETCH_TASK_TIMEOUT_DEFAULT)

    val pollingDriverTimeout get() = config.getDuration(POLLING_DRIVER_TIMEOUT, POLLING_DRIVER_TIMEOUT_DEFAULT)

    /**
     * The interaction settings. Interaction settings define how the system
     * interacts with webpages to mimic the behavior of real people.
     * */
    val interactSettings get() = InteractSettings.fromJson(config[BROWSER_INTERACT_SETTINGS], InteractSettings.DEFAULT)

    /**
     * Page load strategy.
     *
     * Browser4 checks document ready using javascript so just set the strategy to be none.
     *
     * @see <a href='https://blog.knoldus.com/page-loading-strategy-in-the-selenium-webdriver/'>
     *     Page Loading Strategy</a>
     * */
    var pageLoadStrategy = "none"
    /**
     * The user agent to use.
     * */
    val userAgent = UserAgent()
    /**
     * The script confuser.
     * */
    val confuser = BrowserSettings.SCRIPT_CONFUSER
    /**
     * The script loader (legacy, single-world).
     * @deprecated Use dualWorldScriptLoader for dual-world architecture
     * */
    @Deprecated("Use dualWorldScriptLoader for dual-world architecture")
    val scriptLoader = ScriptLoader(confuser, jsPropertyNames)

    /**
     * The dual-world script loader.
     * Separates Page World scripts (stealth) from Isolated World scripts (runtime).
     * */
    val dualWorldScriptLoader = DualWorldScriptLoader(confuser, jsPropertyNames)

    open fun formatViewPort(delimiter: String = ","): String {
        return "${VIEWPORT.width}$delimiter${VIEWPORT.height}"
    }

    open fun createChromeOptions(generalOptions: Map<String, Any>): ChromeOptions {
        val chromeOptions = ChromeOptions()
        chromeOptions.merge(generalOptions)

        // rewrite proxy argument
        chromeOptions.removeArgument("proxy")
        when (val proxy = generalOptions["proxy"]) {
            is String -> chromeOptions.proxyServer = proxy
            is URI -> chromeOptions.proxyServer = proxy.host + ":" + proxy.port
            is ProxyEntry -> chromeOptions.proxyServer = proxy.hostPort
        }

        chromeOptions.headless = isHeadless
        chromeOptions.noSandbox = noSandbox

        chromeOptions
            .addArgument("window-position", "0,0")
            .addArgument("window-size", formatViewPort())
            .addArgument("pageLoadStrategy", pageLoadStrategy)
            .addArgument("throwExceptionOnScriptError", "true")
            .addArgument("disable-blink-features", "AutomationControlled")
//            .addArgument("start-maximized")

        return chromeOptions
    }
}
