package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.LauncherOptions
import ai.platon.pulsar.api.model.BrowserLaunchException
import ai.platon.pulsar.chrome.ChromeLauncher
import ai.platon.pulsar.chrome.PulsarBrowser
import ai.platon.pulsar.chrome.manage.PulsarBrowserLauncher
import ai.platon.pulsar.chrome.util.ChromeLaunchException
import ai.platon.pulsar.common.config.ImmutableConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the ego (lite) browser binary for a managed launch.
 *
 * The base library's own binary search (`Browsers.searchChromeBinary`) only knows
 * Chrome, Edge and Playwright installs, and its `chrome.path` override is global to
 * the JVM — neither can express "this session launches ego lite". Resolution here is
 * therefore per launch:
 *
 * 1. `ego.path` — an explicit override read from the session/launch configuration
 *    first and the system properties second (mirrors the base library's
 *    `chrome.path` convention), so a server-wide default can live in
 *    `application.properties` or `-Dego.path=...`.
 * 2. The default ego (lite) install locations.
 *
 * ego (lite) is Chromium inside: it accepts the standard `--remote-debugging-port`
 * / `--user-data-dir` switches and answers `/json/version` with a
 * `Chrome/<major>` product string, so everything after the binary choice —
 * CDP transport, profile handling, process reaping — behaves exactly as a Chrome
 * launch.
 *
 * ego lite is also a **fingerprint browser**: it ships its own fingerprint and
 * owns its anti-detection behaviour. Browser4 therefore never injects its stealth
 * layer into an ego-lite session — no page-world `js/stealth.js` payload and no
 * `--user-agent` rewrite ([B4Constants.stealthAppliesTo] returns `false` for this
 * channel). Human-like input (trusted clicks, segmented mouse moves, typing
 * jitter) is still used: that is the automation style, not a stealth patch.
 */
object EgoLiteBrowsers {
    private val logger = LoggerFactory.getLogger(EgoLiteBrowsers::class.java)

    /** Explicit binary override, read from the configuration first, then the system properties. */
    const val EGO_PATH_PROPERTY = "ego.path"

    /**
     * The WebRTC IP handling policy managed ego launches with: WebRTC UDP is forced
     * through the proxy — or dropped when there is none — so a page never learns the
     * host's real IP from srflx candidates. This is the policy privacy extensions such
     * as "WebRTC Leak Prevent" apply; WebRTC itself stays available, only the address
     * exposure changes.
     */
    const val WEBRTC_IP_HANDLING_POLICY_VALUE = "disable_non_proxied_udp"

    /**
     * Keep WebRTC from exposing the host's IP in the profile at [userDataDir], by
     * writing the `webrtc.ip_handling_policy` profile preference.
     *
     * The `--force-webrtc-ip-handling-policy` switch is ignored by Chromium 156
     * (verified empirically against both Chrome and ego 156: srflx candidates with the
     * real public IP are gathered regardless), while the preference is honored — it is
     * the same setting the `chrome.privacy` extension API writes.
     *
     * An existing `ip_handling_policy` value in the profile always wins, and the
     * managed profile is a throwaway or Browser4-owned directory, so the merge is
     * safe. Failures are logged and never break the launch.
     */
    fun applyWebRtcIpHandlingPolicy(userDataDir: Path) {
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

    private const val EGO_INSTALL_DIR = """Citro Labs\ego lite\Application\ego.exe"""

    /**
     * True when [channel] names the ego (lite) browser. Accepts the same spellings
     * the CLI accepts: `ego-lite`, `egolite`, `ego lite`, `ego`, case-insensitive.
     */
    fun isEgoLiteChannel(channel: String?): Boolean = B4Constants.isEgoLiteChannel(channel)

    /**
     * The ego (lite) binary, or null when no candidate exists or is executable.
     */
    fun resolveBinaryOrNull(conf: ImmutableConfig? = null): Path? {
        val configured = conf?.get(EGO_PATH_PROPERTY)?.takeIf { it.isNotBlank() }
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
        // Fallbacks for environments where the variables are missing.
        candidates.add(Path.of("""C:\Program Files""", EGO_INSTALL_DIR))
        candidates.add(Path.of("""C:\Program Files (x86)""", EGO_INSTALL_DIR))
        return candidates
    }

}

/**
 * Launches the ego (lite) browser through the base library's [ChromeLauncher] with
 * an explicitly resolved binary, so a session with `browser.channel=ego-lite`
 * (`open --channel ego-lite`) starts ego instead of Chrome.
 *
 * Only the binary differs from [PulsarBrowserLauncher] — plus one profile
 * hardening: [EgoLiteBrowsers.applyWebRtcIpHandlingPolicy] keeps WebRTC from
 * exposing the host's real IP. The user data dir comes from the [BrowserId]
 * (profile mode policy applies unchanged), the launch options are the standard
 * ones from the single launch funnel, and the returned [PulsarBrowser] is the
 * same type a Chrome launch produces, so lifecycle management (pid file,
 * user-data-dir process matching, shutdown hooks) is inherited untouched.
 */
class EgoLiteBrowserLauncher : PulsarBrowserLauncher() {

    override fun launch(browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions): Browser {
        require(!browserId.isExternal) { "External browser is not allowed: $browserId" }

        val binary = EgoLiteBrowsers.resolveBinaryOrNull(launcherOptions.settings.config)
            ?: throw BrowserLaunchException(
                "The ego (lite) browser is not installed or not executable. " +
                    "Install ego lite, or set ${EgoLiteBrowsers.EGO_PATH_PROPERTY} to the ego binary."
            )

        EgoLiteBrowsers.applyWebRtcIpHandlingPolicy(browserId.userDataDir)

        val chromeLauncher = ChromeLauncher(browserId.userDataDir, launcherOptions)
        val service = try {
            chromeLauncher.launch(binary, launchOptions)
        } catch (e: ChromeLaunchException) {
            throw BrowserLaunchException("$browserId, message: ${e.message}", e)
        }
        return PulsarBrowser(browserId, service, launcherOptions.settings, chromeLauncher)
    }
}
