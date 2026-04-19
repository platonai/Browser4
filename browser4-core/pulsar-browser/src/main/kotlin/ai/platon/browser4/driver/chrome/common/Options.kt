package ai.platon.browser4.driver.chrome.common

import ai.platon.browser4.driver.common.BrowserSettings
import java.time.Duration

/**
 * The launch config
 * */
class LauncherOptions(
    val settings: BrowserSettings = BrowserSettings(),
    var supervisorProcess: String? = null,
    val supervisorProcessArgs: MutableList<String> = mutableListOf()
) {
    var startupWaitTime = DEFAULT_STARTUP_WAIT_TIME
    var shutdownWaitTime = DEFAULT_SHUTDOWN_WAIT_TIME
    var threadWaitTime = THREAD_JOIN_WAIT_TIME

    companion object {
        /** Default startup wait time in seconds. */
        val DEFAULT_STARTUP_WAIT_TIME = Duration.ofSeconds(60)

        /** Default shutdown wait time in seconds. */
        val DEFAULT_SHUTDOWN_WAIT_TIME = Duration.ofSeconds(60)

        /** 5 seconds wait time for threads to stop. */
        val THREAD_JOIN_WAIT_TIME = Duration.ofSeconds(5)
    }
}

/** Chrome argument */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class ChromeParameter(val value: String)

/**
 * The options to open chrome devtools, list of chrome command-line switches can be found in the below link:
 * http://peter.sh/experiments/chromium-command-line-switches/
 *
 * @property proxyServer The proxy server to use for the connection.
 * */
class ChromeOptions(
    /**
     * The proxy server to use for the connection.
     *
     * You can specify a custom proxy configuration in three ways:
     * By providing a semi-colon-separated mapping of list scheme to url/port pairs.
     * For example, you can specify:
     *
     * --proxy-server="http=foopy:80;ftp=foopy2"
     *
     * to use HTTP proxy "foopy:80" for http URLs and HTTP proxy "foopy2:80" for ftp URLs.
     *
     * By providing a single uri with optional port to use for all URLs.
     * For example:
     *
     * --proxy-server="foopy:8080"
     *
     * will use the proxy at foopy:8080 for all traffic.
     *
     * By using the special "direct://" value.
     * --proxy-server="direct://" will cause all connections to not use a proxy.
     *
     * @see <a href='https://www.chromium.org/developers/design-documents/network-settings/#command-line-options-for-proxy-settings'>
     *     Command-line options for proxy settings</a>
     * */
    @ChromeParameter("proxy-server")
    var proxyServer: String? = null,
    @ChromeParameter("headless")
    var headless: Boolean = false,
    @ChromeParameter("incognito")
    var incognito: Boolean = false,
    @ChromeParameter("disable-gpu")
    var disableGpu: Boolean = true,
    @ChromeParameter("hide-scrollbars")
    var hideScrollbars: Boolean = true,
    @ChromeParameter("remote-debugging-port")
    var remoteDebuggingPort: Int = 0,
    @ChromeParameter("no-default-browser-check")
    var noDefaultBrowserCheck: Boolean = true,
    @ChromeParameter("no-first-run")
    var noFirstRun: Boolean = true,
    @ChromeParameter("no-startup-window")
    var noStartupWindow: Boolean = true,
    @ChromeParameter("mute-audio")
    var muteAudio: Boolean = true,
    @ChromeParameter("disable-background-networking")
    var disableBackgroundNetworking: Boolean = true,
    @ChromeParameter("disable-background-timer-throttling")
    var disableBackgroundTimerThrottling: Boolean = true,
    @ChromeParameter("disable-client-side-phishing-detection")
    var disableClientSidePhishingDetection: Boolean = true,
    @ChromeParameter("disable-default-apps")
    var disableDefaultApps: Boolean = false,
    @ChromeParameter("disable-extensions")
    var disableExtensions: Boolean = false,
    @ChromeParameter("disable-hang-monitor")
    var disableHangMonitor: Boolean = true,
    @ChromeParameter("disable-popup-blocking")
    var disablePopupBlocking: Boolean = true,
    @ChromeParameter("disable-prompt-on-repost")
    var disablePromptOnRepost: Boolean = true,
    @ChromeParameter("disable-sync")
    var disableSync: Boolean = true,
    @ChromeParameter("disable-translate")
    var disableTranslate: Boolean = true,
    @ChromeParameter("disable-geolocation")
    var disableGeolocation: Boolean = true,
    @ChromeParameter("disable-blink-features")
    var disableBlinkFeatures: String = "AutomationControlled",
    @ChromeParameter("metrics-recording-only")
    var metricsRecordingOnly: Boolean = true,
    @ChromeParameter("safebrowsing-disable-auto-update")
    var safebrowsingDisableAutoUpdate: Boolean = true,
    @ChromeParameter("no-sandbox")
    var noSandbox: Boolean = false,
    @ChromeParameter("ignore-certificate-errors")
    var ignoreCertificateErrors: Boolean = true,
    /**
     * The origin for DevTools Websocket connections must now be specified explicitly from Chrome 111.
     * @see [fluidsonic's pull](https://github.com/kklisura/chrome-devtools-java-client/pull/85)
     * @see [ChromeDriver 111.0.5563.19 unable to establish connection to chrome](https://groups.google.com/g/chromedriver-users/c/xL5-13_qGaA?pli=1)
     * */
    @ChromeParameter("remote-allow-origins")
    var remoteAllowOrigins: String = "*"
) {
    val additionalArguments: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Add an argument.
     * */
    fun addArgument(key: String, value: String? = null): ChromeOptions {
        additionalArguments[key] = value
        return this
    }

    /**
     * Remove an argument.
     * */
    fun removeArgument(key: String): ChromeOptions {
        additionalArguments.remove(key)
        return this
    }

    /**
     * Merge an arguments map to this
     * */
    fun merge(args: Map<String, Any?>) = args.forEach { (key, value) -> addArgument(key, value?.toString()) }

    /**
     * Convert all the arguments to a map.
     * */
    fun toMap(): Map<String, Any?> {
        val args = ChromeOptions::class.java.declaredFields
            .filter { it.annotations.any { it is ChromeParameter } }
            .onEach { it.isAccessible = true }
            .associateTo(LinkedHashMap()) { it.getAnnotation(ChromeParameter::class.java).value to it.get(this) }

        args.putAll(additionalArguments)

        return args
    }

    /**
     * Convert all the arguments to a list.
     * */
    fun toList() = toList(toMap())

    fun toList(args: Map<String, Any?>): List<String> {
        val result = ArrayList<String>()
        for ((key, value) in args) {
            if (value != null && false != value) {
                if (true == value) {
                    result.add("--$key")
                } else {
                    result.add("--$key=$value")
                }
            }
        }
        return result
    }

    override fun toString() = toList().joinToString(" ") { it }
}
