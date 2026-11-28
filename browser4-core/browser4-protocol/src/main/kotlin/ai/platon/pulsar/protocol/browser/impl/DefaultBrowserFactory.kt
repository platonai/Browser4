package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.behavior.BrowserBehavior
import ai.platon.browser4.behavior.stealth.StealthLaunchContext
import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.LauncherOptions
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.BrowserLauncher
import ai.platon.pulsar.api.model.BrowserLaunchException
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.manage.AbstractBrowserFactory
import ai.platon.pulsar.chrome.ChromeLauncher
import ai.platon.pulsar.chrome.PulsarBrowser
import ai.platon.pulsar.chrome.manage.PulsarBrowserLauncher
import ai.platon.pulsar.chrome.util.ChromeLaunchException
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.protocol.browser.resolver.BrowserBinaryResolverRegistry

/**
 * 统一的浏览器启动工厂。
 *
 * 所有浏览器启动（Chrome / ego lite / 未来的 Edge / Brave / …）都经由此单入口。
 * 工厂自己不硬编码任何浏览器分支 —— 它只做三件事：
 *
 * 1. 从 session 配置取 [B4Constants.BROWSER_CHANNEL]，通过
 *    [BrowserBinaryResolverRegistry.select] 路由到对应的
 *    [BrowserBinaryResolver][ai.platon.pulsar.protocol.browser.resolver.BrowserBinaryResolver]。
 * 2. 条件调用 stealth（resolver 声明自己是否接受 Browser4 的 stealth 层）。
 * 3. 交给统一的 [ChromeLauncher] 启动 + 条件调用 resolver 的 post-launch profile 硬化 hook。
 *
 * 新增浏览器的接入点只有一个：在 resolver registry 注册一个新的
 * [BrowserBinaryResolver][ai.platon.pulsar.protocol.browser.resolver.BrowserBinaryResolver]
 * 实现，工厂和底层 Launcher 不需要改动。
 */
class DefaultBrowserFactory(
    conf: ImmutableConfig = ImmutableConfig(loadDefaults = true),
    settings: BrowserSettings = BrowserSettings(conf)
) : AbstractBrowserFactory(conf, settings) {
    private val launcher: BrowserLauncher = PulsarBrowserLauncher()

    constructor(conf: ImmutableConfig) : this(conf, BrowserSettings(conf))

    @Synchronized
    override fun launch(
        browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): Browser {
        require(!browserId.isExternal) { "External browser is not allowed: $browserId" }

        val channel = launcherOptions.settings.config.get(B4Constants.BROWSER_CHANNEL)

        val resolver = BrowserBinaryResolverRegistry.select(channel)

        // BrowserStealthProvider 拥有 launch policy —— 其 default 实现会根据
        // [B4Constants.stealthAppliesTo] 跳过 fingerprint-browser channel 的 UA 重写。
        val stealth = BrowserBehavior.stealth(launcherOptions.settings.config)
        stealth.applyLaunchOptions(
            StealthLaunchContext(launchOptions, launcherOptions.settings, channel)
        )

        resolver.postLaunchHook(browserId.userDataDir)

        val binary = resolver.resolveBinary(launcherOptions.settings.config)

        return if (binary != null) {
            // resolver 指定了二进制（如 ego lite），走统一 ChromeLauncher 手动启动路径
            val chromeLauncher = ChromeLauncher(browserId.userDataDir, launcherOptions)
            val service = try {
                chromeLauncher.launch(binary, launchOptions)
            } catch (e: ChromeLaunchException) {
                throw BrowserLaunchException("$browserId, message: ${e.message}", e)
            }
            PulsarBrowser(browserId, service, launcherOptions.settings, chromeLauncher)
        } else {
            // resolver 返回 null（如 Chrome），让 PulsarBrowserLauncher 从外部库默认查找
            launcher.launch(browserId, launcherOptions, launchOptions)
        }
    }

    @Synchronized
    override fun connect(browserType: BrowserType, port: Int, settings: BrowserSettings): Browser =
        launcher.connect(port, settings)
}
