package ai.platon.pulsar.protocol.browser.resolver

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.common.config.ImmutableConfig
import org.slf4j.LoggerFactory

/**
 * BrowserBinaryResolver 注册表。
 *
 * 解析策略（两级）：
 *
 * 1. **配置优先** —— 启动时从全局 [ImmutableConfig] 读取
 *    `browser.channels = chrome, ego-lite, edge, ...`，为每个声明的 channel
 *    构造 [ConfigDrivenBrowserResolver]。channel 的所有属性
 *    （aliases / stealth / binary / post-launch）都在
 *    `browser.channel.<name>.*` 配置块里声明。
 *
 * 2. **代码兜底** —— 对于**没有在配置里声明**的 channel，回退到内建的
 *    [DefaultChromiumBinaryResolver] 和 [EgoLiteBinaryResolver]。
 *
 * 这样新增浏览器时只需要：在 application.properties 加 6 行配置，
 * 不需要写 Kotlin 代码、不需要重新编译、不需要改 registry。
 *
 * 调用方：[ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory]
 * 以及需要判断浏览器通道属性的场景（如 stealth 是否应注入）。
 */
object BrowserBinaryResolverRegistry {

    private val logger = LoggerFactory.getLogger(BrowserBinaryResolverRegistry::class.java)

    /** 内建代码注册 resolver —— 作为未配置 channel 的兜底 */
    private val builtInResolvers: List<BrowserBinaryResolver> = listOf(
        DefaultChromiumBinaryResolver(),
        EgoLiteBinaryResolver(),
    )

    private val resolversField = mutableListOf<BrowserBinaryResolver>()
    private val resolversLock = Any()

    /**
     * 最终生效的 resolver 列表：先配置驱动、后代码兜底，按 [BrowserBinaryResolver.id] 去重
     * （配置驱动的同 id resolver 覆盖代码注册的）。
     *
     * 首次访问时用全局 [ImmutableConfig] 加载；也可通过 [reloadFromConfig] 显式刷新
     * （测试和需要动态变更配置的场景）。
     */
    private val resolvers: List<BrowserBinaryResolver>
        get() = synchronized(resolversLock) {
            if (resolversField.isEmpty()) {
                val globalConf = ImmutableConfig(loadDefaults = true)
                resolversField += discoverFromConfig(globalConf)
                resolversField += merge(emptyList(), builtInResolvers).filter { !resolversField.any { r -> r.id == it.id } }
            }
            resolversField.toList()
        }

    /** 显式用指定 config 刷新 resolver 列表（测试辅助 + 运维热加载）。 */
    fun reloadFromConfig(config: ImmutableConfig) = synchronized(resolversLock) {
        val configured = discoverFromConfig(config)
        resolversField.clear()
        resolversField += configured
        resolversField += builtInResolvers.filter { it.id !in configured.map { c -> c.id } }
    }

    /** 测试辅助：重置为未加载状态，让下次访问走全局默认 config。 */
    internal fun resetForTest() = synchronized(resolversLock) { resolversField.clear() }

    internal fun discoverFromConfig(config: ImmutableConfig): List<BrowserBinaryResolver> {
        val declared = PlaceholderExpander.expandList(config.get("browser.channels"), config)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        if (declared.isEmpty()) {
            logger.debug("browser.channels not configured — falling back to built-in resolvers only")
            return emptyList()
        }

        val result = mutableListOf<BrowserBinaryResolver>()
        for (channel in declared) {
            // 用 aliases 键是否存在来判断配置块是否实际声明了（至少要有 aliases）
            val aliasesKey = "browser.channel.$channel.aliases"
            if (config.get(aliasesKey) != null) {
                result += ConfigDrivenBrowserResolver(channel, config)
            } else {
                logger.debug("browser.channel.$channel has no aliases configured — skipping (will rely on built-in fallback)")
            }
        }
        return result
    }

    private fun merge(configured: List<BrowserBinaryResolver>, builtIn: List<BrowserBinaryResolver>): List<BrowserBinaryResolver> {
        val seenIds = configured.map { it.id }.toMutableSet()
        return configured + builtIn.filter { it.id !in seenIds }
    }

    /**
     * 按 channel 字符串选择 resolver。
     *
     * - null 或空白 → Chrome resolver
     * - 小写化后匹配 channelAliases → 对应 resolver
     * - 未匹配 → 抛出 IllegalArgumentException，错误消息包含 channel 值和所有已知通道
     */
    fun select(channel: String?): BrowserBinaryResolver {
        val normalized = channel?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        if (normalized == null) return chromeResolver

        for (resolver in resolvers) {
            if (normalized in resolver.channelAliases) return resolver
        }

        val supported = resolvers.flatMap { it.channelAliases }.distinct().sorted()
        throw IllegalArgumentException(
            "Unknown ${B4Constants.BROWSER_CHANNEL}: '$channel'. " +
                "Supported channels: ${supported.joinToString(", ") { "'$it'" }}"
        )
    }

    /** 默认 Chrome resolver，便于直接引用。 */
    val chromeResolver: BrowserBinaryResolver by lazy {
        resolvers.first { it.id == "chrome" }
    }

    /** Ego Lite resolver，便于直接引用。 */
    val egoLiteResolver: BrowserBinaryResolver by lazy {
        resolvers.first { it.id == "ego-lite" }
    }

    /**
     * True 当 channel 属于指纹浏览器（Browser4 不应注入自己的 stealth 层）。
     *
     * 委托给 resolver 的 [BrowserBinaryResolver.stealthInjectable] 取反。
     */
    fun isFingerprintBrowserChannel(channel: String?): Boolean {
        val resolver = runCatching { select(channel) }.getOrNull() ?: return false
        return !resolver.stealthInjectable
    }

    /**
     * True 当 Browser4 的 stealth 层应注入到此 channel 的浏览器。
     *
     * 委托给 resolver 的 [BrowserBinaryResolver.stealthInjectable]。
     */
    fun stealthAppliesTo(channel: String?): Boolean {
        val resolver = runCatching { select(channel) }.getOrNull() ?: return true
        return resolver.stealthInjectable
    }

    /** 注册的所有 resolver（只读视图，供诊断/测试）。 */
    fun all(): List<BrowserBinaryResolver> = resolvers.toList()
}
