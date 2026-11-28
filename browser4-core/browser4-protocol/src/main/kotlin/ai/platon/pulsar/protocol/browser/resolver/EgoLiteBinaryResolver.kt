package ai.platon.pulsar.protocol.browser.resolver

import ai.platon.pulsar.common.config.ImmutableConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ego (lite) 浏览器 resolver。
 *
 * ego lite 是 Chromium 内核的指纹浏览器：它自带 fingerprint，自己管理 anti-detection
 * 行为，所以 Browser4 的 stealth.js / UA 重写都不应注入（[stealthInjectable] = false）。
 *
 * 二进制查找优先级：
 * 1. 配置项 `ego.path`（session/launch config 优先，system property 兜底）
 * 2. Windows install dirs（ProgramFiles / ProgramFiles(x86) / LOCALAPPDATA / 硬编码兜底）
 *
 * profile 硬化：[postLaunchHook] 写入 `webrtc.ip_handling_policy = disable_non_proxied_udp`
 * 到 Profile Preferences，强制 WebRTC UDP 走代理。
 */
class EgoLiteBinaryResolver : BrowserBinaryResolver {
    private val logger = LoggerFactory.getLogger(EgoLiteBinaryResolver::class.java)

    companion object {
        /** 显式 binary override 配置键，镜像 chrome.path 约定 */
        const val EGO_PATH_PROPERTY = "ego.path"

        /** WebRTC IP 策略值：强制 UDP 走代理，无代理则丢弃 */
        const val WEBRTC_IP_HANDLING_POLICY_VALUE = "disable_non_proxied_udp"

        private const val EGO_INSTALL_DIR = """Citro Labs\ego lite\Application\ego.exe"""
    }

    override val id: String = "ego-lite"
    override val channelAliases: Set<String> = setOf("ego-lite", "egolite", "ego lite", "ego")
    override val stealthInjectable: Boolean = false

    override fun resolveBinary(config: ImmutableConfig?): Path? {
        val configured = config?.get(EGO_PATH_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(EGO_PATH_PROPERTY)?.takeIf { it.isNotBlank() }
        if (configured != null) {
            val path = Path.of(configured).toAbsolutePath()
            if (Files.isExecutable(path)) {
                return path
            }
            logger.warn("{} points to a non-executable file: {}", EGO_PATH_PROPERTY, path)
            return null
        }
        return installDirCandidates().firstOrNull { Files.isExecutable(it) }?.toAbsolutePath()
    }

    private fun installDirCandidates(): List<Path> {
        val candidates = mutableListOf<Path>()
        System.getenv("ProgramFiles")?.let { candidates.add(Path.of(it, EGO_INSTALL_DIR)) }
        System.getenv("ProgramFiles(x86)")?.let { candidates.add(Path.of(it, EGO_INSTALL_DIR)) }
        System.getenv("LOCALAPPDATA")?.let { candidates.add(Path.of(it, EGO_INSTALL_DIR)) }
        candidates.add(Path.of("""C:\Program Files""", EGO_INSTALL_DIR))
        candidates.add(Path.of("""C:\Program Files (x86)""", EGO_INSTALL_DIR))
        return candidates
    }

    override fun postLaunchHook(userDataDir: Path) {
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
            val webrtc = root.get("webrtc") as? ObjectNode
                ?: mapper.createObjectNode().also { root.replace("webrtc", it) }
            if (!webrtc.has("ip_handling_policy")) {
                webrtc.put("ip_handling_policy", WEBRTC_IP_HANDLING_POLICY_VALUE)
                Files.createDirectories(preferences.parent)
                Files.writeString(preferences, mapper.writeValueAsString(root))
            }
        }.onFailure {
            logger.warn("Failed to apply WebRTC IP handling policy to {}: {}", preferences, it.message)
        }
    }
}
