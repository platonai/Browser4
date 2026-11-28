package ai.platon.pulsar.protocol.browser.resolver

import ai.platon.pulsar.common.config.ImmutableConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * 纯配置驱动的浏览器 resolver。
 *
 * [BrowserBinaryResolverRegistry] 启动时从全局 [ImmutableConfig] 读取
 * `browser.channel.<name>.*` 配置块，为每个声明的 channel 构造一个此类实例，
 * 替代为每个浏览器单独写一个 Kotlin 子类。
 *
 * 配置项命名（以 channel 名 `edge` 为例）：
 *
 * ```properties
 * browser.channels = chrome, edge, brave, ego-lite
 *
 * browser.channel.edge.aliases = edge, msedge, microsoft-edge
 * browser.channel.edge.stealth.injectable = true
 * browser.channel.edge.binary.path =               # 空 → 交给外部库默认查找
 * browser.channel.edge.binary.install-dirs = \${ProgramFiles}\\Microsoft\\Edge\\Application\\msedge.exe, \${LOCALAPPDATA}\\Microsoft\\Edge\\Application\\msedge.exe
 * browser.channel.edge.post-launch.action = none
 *
 * # 若 post-launch.action = write_json_key：
 * browser.channel.edge.post-launch.json-path = webrtc.ip_handling_policy
 * browser.channel.edge.post-launch.json-value = disable_non_proxied_udp
 * # Browser 内建快捷动作
 * browser.channel.edge.post-launch.action = webrtc_policy   # 等价于上面的 write_json_key
 * browser.channel.edge.post-launch.webrtc.policy = disable_non_proxied_udp
 * ```
 *
 * 所有字符串值都支持 `${xxx}` 占位符展开（见 [PlaceholderExpander]），按
 * config → system property → env 的优先级查找，带 `${name:-default}` 语法。
 */
class ConfigDrivenBrowserResolver(
    private val channelName: String,
    private val config: ImmutableConfig,
) : BrowserBinaryResolver {

    private val logger = LoggerFactory.getLogger(ConfigDrivenBrowserResolver::class.java)

    override val id: String = channelName

    override val channelAliases: Set<String> by lazy {
        val aliasesKey = "browser.channel.$channelName.aliases"
        val configured = PlaceholderExpander.expandList(config.get(aliasesKey), config)
            .map { it.lowercase() }
        // 如果配置里没写 aliases，回退用 channelName 自己
        if (configured.isEmpty()) setOf(channelName.lowercase()) else configured.toSet()
    }

    override val stealthInjectable: Boolean by lazy {
        config.getBoolean("browser.channel.$channelName.stealth.injectable", true)
    }

    // ---- binary resolution -------------------------------------------------

    private val configuredBinaryPath: String? by lazy {
        config.get("browser.channel.$channelName.binary.path")
    }

    private val installDirTemplates: List<String> by lazy {
        PlaceholderExpander.expandList(
            config.get("browser.channel.$channelName.binary.install-dirs"), config
        )
    }

    override fun resolveBinary(config: ImmutableConfig?): Path? {
        // 1. 显式 binary.path（最优先）
        val configured = configuredBinaryPath?.let { PlaceholderExpander.expand(it, config) }
            ?.trim()?.takeIf { it.isNotEmpty() }
        if (configured != null) {
            val path = Path.of(configured).toAbsolutePath()
            if (Files.isExecutable(path)) return path
            logger.warn("browser.channel.{}.binary.path points to non-executable file: {}", channelName, path)
            return null
        }

        // 2. install-dirs 列表兜底
        for (template in installDirTemplates) {
            val expanded = PlaceholderExpander.expand(template, config)
            val path = Path.of(expanded).toAbsolutePath()
            if (Files.isExecutable(path)) return path
        }

        // 3. 都没找到 → 交给外部库默认查找（Chrome 就是 Browsers.searchChromeBinary）
        return null
    }

    // ---- post-launch hooks -------------------------------------------------

    private val postLaunchAction: String by lazy {
        config.get("browser.channel.$channelName.post-launch.action")?.trim()?.lowercase() ?: "none"
    }

    override fun postLaunchHook(userDataDir: Path) {
        when (postLaunchAction) {
            "none" -> Unit // 什么都不做
            "webrtc_policy" -> writeWebRtcPolicy(userDataDir)
            "write_json_key" -> writeGenericJsonKey(userDataDir)
            else -> logger.warn("Unknown post-launch action '{}' for channel '{}'", postLaunchAction, channelName)
        }
    }

    private fun writeWebRtcPolicy(userDataDir: Path) {
        val policyValue = config.get("browser.channel.$channelName.post-launch.webrtc.policy")
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: "disable_non_proxied_udp"
        writeJsonAtPath(userDataDir, "webrtc.ip_handling_policy", policyValue)
    }

    private fun writeGenericJsonKey(userDataDir: Path) {
        val jsonPath = config.get("browser.channel.$channelName.post-launch.json-path")
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val jsonValue = config.get("browser.channel.$channelName.post-launch.json-value")
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        writeJsonAtPath(userDataDir, jsonPath, jsonValue)
    }

    /**
     * 通用的 Profile Preferences JSON 合并写入。
     *
     * - path 用 `.` 分层：`webrtc.ip_handling_policy`
     * - 值只有在该键不存在时才写入（已有值优先，不覆盖）
     * - 目标文件为 `userDataDir/Default/Preferences`
     */
    private fun writeJsonAtPath(userDataDir: Path, dottedPath: String, value: String) {
        val preferences = userDataDir.resolve("Default").resolve("Preferences")
        runCatching {
            val mapper = ObjectMapper()
            val root = if (Files.isRegularFile(preferences)) {
                Files.readString(preferences)
                    .takeIf { it.isNotBlank() }
                    ?.let { mapper.readTree(it) as? ObjectNode }
                    ?: mapper.createObjectNode()
            } else {
                mapper.createObjectNode()
            }

            // 逐层下钻 / 创建 ObjectNode
            val segments = dottedPath.split(".")
            var node: ObjectNode = root
            for (i in 0 until segments.lastIndex) {
                node = (node.get(segments[i]) as? ObjectNode)
                    ?: mapper.createObjectNode().also { node.replace(segments[i], it) }
            }

            val leaf = segments.last()
            if (!node.has(leaf)) {
                node.put(leaf, value)
                Files.createDirectories(preferences.parent)
                Files.writeString(preferences, mapper.writeValueAsString(root))
            }
        }.onFailure {
            logger.warn("Failed to write JSON key '{}' into {}: {}", dottedPath, preferences, it.message)
        }
    }
}
