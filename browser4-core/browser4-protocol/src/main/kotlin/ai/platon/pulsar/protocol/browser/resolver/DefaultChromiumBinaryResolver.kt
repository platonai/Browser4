package ai.platon.pulsar.protocol.browser.resolver

import ai.platon.pulsar.common.config.ImmutableConfig
import java.nio.file.Path

/**
 * 默认的 Chromium 浏览器 resolver，覆盖 Chrome。
 *
 * 二进制查找交给外部库 [Browsers.searchChromeBinary]；Browser4 stealth 层应正常注入。
 * channel 为 null 时也选中此 resolver（默认 Chrome）。
 */
class DefaultChromiumBinaryResolver : BrowserBinaryResolver {
    override val id: String = "chrome"
    override val channelAliases: Set<String> = setOf("chrome", "google-chrome", "google chrome")
    override val stealthInjectable: Boolean = true

    override fun resolveBinary(config: ImmutableConfig?): Path? = null
}
