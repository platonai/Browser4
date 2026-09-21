package ai.platon.pulsar.chrome

import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_LAUNCH_CHROME_ARGS
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Removes the `HeadlessChrome` token from the User-Agent of a headless Chrome launch.
 *
 * ## Why the launch flag and not CDP
 *
 * Chrome in `--headless` mode advertises `HeadlessChrome/<version>` in its User-Agent while
 * `navigator.userAgentData.brands` still reports `Google Chrome`, i.e. the two contradict each
 * other — a deterministic, one-line bot signal. The obvious fix, a CDP
 * `Emulation.setUserAgentOverride`, was measured to reach only the main frame, its iframes and
 * *dedicated* workers; `SharedWorker` and `ServiceWorker` globals live in their own renderer
 * processes and keep the native `HeadlessChrome` value, which is exactly the cross-context
 * inconsistency detectors look for (`inconsistentServiceWorkerNavigatorPropery`).
 *
 * Passing `--user-agent=<ua>` at launch is applied in the browser process and was measured to be
 * visible in all four scopes (main / dedicated / shared / service worker) with the
 * `Sec-CH-UA`, `Sec-CH-UA-Platform` and `Accept-Language` request headers left untouched.
 *
 * The value must therefore be known *before* Chrome starts, while the native User-Agent is only
 * observable *after* it starts. This object closes that gap by deriving the reduced User-Agent
 * (`Chrome/<major>.0.0.0`, the only form Chrome reports since UA reduction) from the installed
 * Chrome version, which is read from the version-named install directory (Windows, macOS) or from
 * `chrome --version` (Linux). [Browser4WebDriver] additionally re-checks the native User-Agent of
 * every new driver and falls back to a CDP override if the flag did not take effect.
 *
 * @see Browser4WebDriver.ensureNonHeadlessUserAgent
 */
object HeadlessUserAgent {
    /** Configuration key that disables the fix (`browser.launch.headless.user.agent.fix`). */
    const val ENABLED_KEY = "browser.launch.headless.user.agent.fix"

    /** The token Chrome puts into the User-Agent of every headless session. */
    const val HEADLESS_TOKEN = "HeadlessChrome"

    private val logger = getLogger(HeadlessUserAgent::class)

    /** `153.0.8010.52` — the version-named directory Chrome installs its binaries into. */
    private val VERSION_DIR = Regex("""\d+\.\d+\.\d+\.\d+""")

    /** Full Chrome version inside `chrome --version` output. */
    private val VERSION_IN_OUTPUT = Regex("""\d+\.\d+\.\d+\.\d+""")

    @Volatile
    private var cachedArgument: String? = null

    @Volatile
    private var resolved = false

    /**
     * Add `--user-agent=<reduced ua>` to [conf] unless it is disabled, already configured by the
     * user, or the local Chrome version cannot be determined.
     *
     * @param conf the session configuration whose `browser.launch.chrome.args` is extended
     * @return true when the configuration was changed
     */
    fun fixUserAgent(conf: MutableConfig): Boolean {
        if (!conf.getBoolean(ENABLED_KEY, true)) {
            return false
        }

        val existing = conf[BROWSER_LAUNCH_CHROME_ARGS]?.trim().orEmpty()
        if (existing.contains("--user-agent")) {
            // An explicitly configured User-Agent always wins.
            return false
        }

        val argument = chromeArgument() ?: return false
        conf[BROWSER_LAUNCH_CHROME_ARGS] = if (existing.isEmpty()) argument else "$existing $argument"
        return true
    }

    /**
     * The `--user-agent="..."` argument for the locally installed Chrome, or null when the Chrome
     * binary or its version cannot be determined.
     */
    fun chromeArgument(): String? {
        if (!resolved) {
            synchronized(this) {
                if (!resolved) {
                    cachedArgument = computeArgument()
                    resolved = true
                }
            }
        }
        return cachedArgument
    }

    /** The version of the locally installed Chrome, or null when it cannot be determined. */
    fun localChromeVersion(): String? = runCatching { localChromeBinary()?.let { resolveChromeVersion(it) } }
        .getOrNull()

    /**
     * Replace the `HeadlessChrome` token with `Chrome`, keeping every other token intact.
     *
     * @param userAgent the native User-Agent reported by Chrome
     * @return the de-headlessed User-Agent, or null when [userAgent] carries no headless token
     */
    fun toNonHeadlessUserAgent(userAgent: String?): String? {
        if (userAgent.isNullOrBlank() || !userAgent.contains(HEADLESS_TOKEN)) {
            return null
        }
        return userAgent.replace(HEADLESS_TOKEN, "Chrome")
    }

    /**
     * Build the reduced User-Agent Chrome reports for [version] on the given platform.
     *
     * Since UA reduction the User-Agent only carries the major version (`Chrome/153.0.0.0`) and a
     * frozen platform token, so the value is stable per platform/architecture.
     *
     * @param version the full Chrome version, e.g. `153.0.8010.52`
     * @param osName the value of `os.name`
     * @param osArch the value of `os.arch`
     */
    fun buildReducedUserAgent(version: String, osName: String, osArch: String): String {
        val major = version.substringBefore('.').ifBlank { version }
        val arch = osArch.lowercase()
        val is64 = arch.contains("64") || arch.contains("aarch64")
        val isArm = arch.contains("aarch64") || arch.contains("arm")
        val platform = when {
            osName.contains("win", ignoreCase = true) ->
                if (is64 && !isArm) "Windows NT 10.0; Win64; x64" else "Windows NT 10.0"

            osName.contains("mac", ignoreCase = true) -> "Macintosh; Intel Mac OS X 10_15_7"

            isArm && is64 -> "X11; Linux aarch64"
            is64 -> "X11; Linux x86_64"
            else -> "X11; Linux i686"
        }

        return "Mozilla/5.0 ($platform) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
    }

    /**
     * Resolve the Chrome version from the installed binary.
     *
     * Tries the version-named install directory first (Chrome installs into
     * `.../Google/Chrome/Application/153.0.8010.52/` on Windows and
     * `.../Versions/153.0.8010.52/` on macOS); on Linux, where the layout is flat, falls back to
     * `<binary> --version`. Returns null when neither yields a version, so callers degrade to
     * "leave the User-Agent alone" instead of guessing.
     */
    fun resolveChromeVersion(binary: Path): String? = versionFromInstallDirectory(binary)
        ?: versionFromVersionFlag(binary)

    private fun computeArgument(): String? {
        val binary = localChromeBinary() ?: return null
        val version = resolveChromeVersion(binary) ?: run {
            logger.warn(
                "Cannot determine the Chrome version from {} — the headless User-Agent keeps the " +
                    "'{}' token. Set '{}' to a full User-Agent to override it explicitly.",
                binary, HEADLESS_TOKEN, "$ENABLED_KEY (or browser.launch.chrome.args)"
            )
            return null
        }

        val userAgent = buildReducedUserAgent(
            version = version,
            osName = System.getProperty("os.name").orEmpty(),
            osArch = System.getProperty("os.arch").orEmpty(),
        )
        logger.info("Headless sessions report '{}' instead of the '{}' token", userAgent, HEADLESS_TOKEN)
        // The value contains whitespace, so it must be quoted for ChromeOptions.parseArguments.
        return "--user-agent=\"$userAgent\""
    }

    private fun localChromeBinary(): Path? = runCatching { ChromeLauncher.searchChromeBinary() }
        .onFailure { logger.debug("Chrome binary not found, headless User-Agent fix skipped: {}", it.message) }
        .getOrNull()
        ?.takeIf { Files.isExecutable(it) }

    private fun versionFromInstallDirectory(binary: Path): String? {
        val candidates = mutableListOf<String>()

        var dir: Path? = binary
        repeat(3) {
            dir?.fileName?.toString()?.let { if (VERSION_DIR.matches(it)) candidates += it }
            dir = dir?.parent
        }

        binary.parent?.takeIf { Files.isDirectory(it) }?.let { parent ->
            runCatching {
                Files.list(parent).use { stream ->
                    stream.map { it.fileName.toString() }
                        .filter { VERSION_DIR.matches(it) }
                        .forEach { candidates += it }
                }
            }
        }

        return candidates.maxByOrNull { versionSortKey(it) }
    }

    private fun versionFromVersionFlag(binary: Path): String? = runCatching {
        val process = ProcessBuilder(binary.toString(), "--version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        VERSION_IN_OUTPUT.find(output)?.value
    }.getOrNull()

    private fun versionSortKey(version: String): String =
        version.split('.').joinToString(".") { it.padStart(6, '0') }
}
