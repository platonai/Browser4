package ai.platon.pulsar.protocol.browser.resolver

import ai.platon.pulsar.common.config.ImmutableConfig
import java.nio.file.Path

/**
 * 浏览器二进制解析器。
 *
 * 每个 Chromium 内核浏览器（Chrome/Edge/Brave/Ego/…）通过实现此接口声明：
 * - 自己的 channel 名称别名集合
 * - 是否允许 Browser4 注入 stealth 层
 * - 如何找到可执行的浏览器二进制（配置优先，install dirs 兜底）
 * - 启动后需要对 profile 做哪些硬化（如 WebRTC policy）
 *
 * 实现通过 [BrowserBinaryResolverRegistry.register] 或直接在 registry 的默认
 * 注册列表中登记，[BrowserBinaryResolverRegistry.select] 按 session 的 channel
 * 字符串路由到正确的 resolver。
 */
interface BrowserBinaryResolver {
    /** 稳定字符串标识，如 "chrome", "ego-lite" */
    val id: String

    /**
     * 接受的 channel 别名集合，全部小写。
     * 空集合意味着「default 通道」，channel 为 null 或空白时选中。
     */
    val channelAliases: Set<String>

    /**
     * Browser4 的 stealth 层（page-world stealth.js + launch-time UA 重写）
     * 是否应对此浏览器启用。指纹浏览器（如 ego lite）应返回 false。
     */
    val stealthInjectable: Boolean

    /**
     * 解析可执行的浏览器二进制路径。
     * 返回 null 时表示"交给外部库默认查找"（对 Chrome 就是 [Browsers.searchChromeBinary]）。
     */
    fun resolveBinary(config: ImmutableConfig?): Path?

    /**
     * profile 硬化 hook —— 在 browser 进程启动前调用。
     * 典型用途是写入 Profile Preferences 以强制 WebRTC policy。
     */
    fun postLaunchHook(userDataDir: Path) {}
}
