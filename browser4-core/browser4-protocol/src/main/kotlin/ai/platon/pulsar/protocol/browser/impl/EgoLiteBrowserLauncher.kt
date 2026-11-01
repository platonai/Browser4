package ai.platon.pulsar.protocol.browser.impl

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.BrowserId
import ai.platon.pulsar.api.ChromeOptions
import ai.platon.pulsar.api.LauncherOptions
import ai.platon.pulsar.api.model.BrowserLaunchException
import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.api.model.ReducedUserAgent
import ai.platon.pulsar.chrome.ChromeLauncher
import ai.platon.pulsar.chrome.PulsarBrowser
import ai.platon.pulsar.chrome.manage.PulsarBrowserLauncher
import ai.platon.pulsar.chrome.util.ChromeLaunchException
import ai.platon.pulsar.common.config.ImmutableConfig
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
 */
object EgoLiteBrowsers {
    private val logger = LoggerFactory.getLogger(EgoLiteBrowsers::class.java)

    /** Explicit binary override, read from the configuration first, then the system properties. */
    const val EGO_PATH_PROPERTY = "ego.path"

    private const val EGO_INSTALL_DIR = """Citro Labs\ego lite\Application\ego.exe"""

    /**
     * True when [channel] names the ego (lite) browser. Accepts the same spellings
     * the CLI accepts: `ego-lite`, `egolite`, `ego lite`, `ego`, case-insensitive.
     */
    fun isEgoLiteChannel(channel: String?): Boolean {
        val normalized = channel?.trim()?.lowercase() ?: return false
        return normalized in setOf(B4Constants.BROWSER_CHANNEL_EGO_LITE, "egolite", "ego lite", "ego")
    }

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

    /**
     * Probed Chromium major version per binary (keyed by path + last-modified), so the
     * probe launch runs at most once per ego installation per backend JVM.
     */
    private val chromiumMajorCache = ConcurrentHashMap<String, Int>()

    /**
     * Apply the headless stealth user agent for an ego (lite) launch.
     *
     * Unlike Chrome, ego stamps its binaries **and** its install-layout version folder
     * with the application version (`0.6.2.5`), never the Chromium version, so the
     * base library's `ReducedUserAgent.chromeMajorVersion(binary)` resolves to major 0
     * and would put the absurd `Chrome/0.0.0.0` on the wire — a worse bot signal than
     * the native `HeadlessChrome` token. The real Chromium major is only visible in a
     * running instance, so this probes it once over CDP (`/json/version` of a throwaway
     * headless launch) and caches the result.
     *
     * Mirrors [Browser4UserAgent]'s contract: a headed launch is left untouched (it
     * reports the reduced form natively), a caller- or config-decided user agent always
     * wins, and `browser.launch.user.agent.stealth=false` opts out.
     *
     * @return `true` when [ChromeOptions.userAgent] was set by this call
     */
    fun applyStealthUserAgent(launchOptions: ChromeOptions, settings: BrowserSettings, binary: Path): Boolean {
        if (!launchOptions.headless || !launchOptions.userAgent.isNullOrBlank()) {
            return false
        }

        val configured = settings.launchConfig.userAgent.trim()
        if (configured.isNotEmpty()) {
            launchOptions.userAgent = ReducedUserAgent.reduce(configured)
            return true
        }
        if (!settings.launchConfig.userAgentStealth) {
            return false
        }

        val major = chromiumMajorVersionOrNull(binary)
        if (major == null) {
            logger.warn(
                "Could not probe ego lite's Chromium major version (binary: {}); the browser keeps its " +
                    "native user agent, which advertises 'HeadlessChrome' in headless mode. " +
                    "Configure browser.launch.user.agent to override it.",
                binary
            )
            return false
        }

        launchOptions.userAgent = ReducedUserAgent.build(major)
        return true
    }

    /**
     * The Chromium major version of the ego binary at [binary] (e.g. 156), or null when
     * the probe fails. Successful probes are cached for the lifetime of this JVM.
     */
    fun chromiumMajorVersionOrNull(binary: Path): Int? {
        val key = runCatching { "$binary#" + Files.getLastModifiedTime(binary).toMillis() }
            .getOrDefault(binary.toString())
        chromiumMajorCache[key]?.let { return it }

        val major = probeChromiumMajorVersion(binary)?.takeIf { it > 0 } ?: return null
        chromiumMajorCache[key] = major
        return major
    }

    /**
     * Extract the browser major version from a `/json/version` payload, e.g.
     * `"Browser": "Chrome/156.0.6829.0"` or `"Browser": "HeadlessChrome/156.0.6829.0"`.
     */
    internal fun parseBrowserMajorFromVersionJson(json: String): Int? {
        val product = Regex(""""Browser"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return null
        return Regex("""/(\d+)\.""").find(product)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Launch ego headless with a throwaway profile, read `/json/version` from its
     * DevTools endpoint, and tear the probe down again. Takes roughly a second.
     */
    private fun probeChromiumMajorVersion(binary: Path): Int? {
        val profileDir = runCatching { Files.createTempDirectory("ego-lite-version-probe") }.getOrElse { return null }
        val process = try {
            ProcessBuilder(
                binary.toString(),
                "--headless", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--remote-debugging-port=0", "--user-data-dir=$profileDir", "about:blank"
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            logger.warn("Failed to start ego lite for the Chromium version probe: {}", e.message)
            runCatching { profileDir.toFile().deleteRecursively() }
            return null
        }

        try {
            // Chromium writes DevToolsActivePort (first line: the port) for a non-default
            // user data dir; poll for it instead of assuming a fixed startup delay.
            val portFile = profileDir.resolve("DevToolsActivePort")
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            var port: Int? = null
            while (System.nanoTime() < deadline && port == null) {
                if (!process.isAlive) break
                port = runCatching {
                    Files.readAllLines(portFile).firstOrNull()?.trim()?.toIntOrNull()
                }.getOrNull()?.takeIf { it > 0 }
                if (port == null) Thread.sleep(100)
            }
            port ?: return null

            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            val response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/version"))
                    .GET().timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            return parseBrowserMajorFromVersionJson(response.body())
        } catch (e: Exception) {
            logger.warn("Failed to probe ego lite's Chromium version: {}", e.message)
            return null
        } finally {
            runCatching {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            runCatching { profileDir.toFile().deleteRecursively() }
        }
    }
}

/**
 * Launches the ego (lite) browser through the base library's [ChromeLauncher] with
 * an explicitly resolved binary, so a session with `browser.channel=ego-lite`
 * (`open --channel ego-lite`) starts ego instead of Chrome.
 *
 * Only the binary differs from [PulsarBrowserLauncher]: the user data dir comes
 * from the [BrowserId] (profile mode policy applies unchanged), the launch options
 * are the standard ones from the single launch funnel, and the returned
 * [PulsarBrowser] is the same type a Chrome launch produces, so lifecycle
 * management (pid file, user-data-dir process matching, shutdown hooks) is
 * inherited untouched.
 */
class EgoLiteBrowserLauncher : PulsarBrowserLauncher() {

    override fun launch(browserId: BrowserId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions): Browser {
        require(!browserId.isExternal) { "External browser is not allowed: $browserId" }

        val binary = EgoLiteBrowsers.resolveBinaryOrNull(launcherOptions.settings.config)
            ?: throw BrowserLaunchException(
                "The ego (lite) browser is not installed or not executable. " +
                    "Install ego lite, or set ${EgoLiteBrowsers.EGO_PATH_PROPERTY} to the ego binary."
            )

        val chromeLauncher = ChromeLauncher(browserId.userDataDir, launcherOptions)
        val service = try {
            chromeLauncher.launch(binary, launchOptions)
        } catch (e: ChromeLaunchException) {
            throw BrowserLaunchException("$browserId, message: ${e.message}", e)
        }
        return PulsarBrowser(browserId, service, launcherOptions.settings, chromeLauncher)
    }
}
