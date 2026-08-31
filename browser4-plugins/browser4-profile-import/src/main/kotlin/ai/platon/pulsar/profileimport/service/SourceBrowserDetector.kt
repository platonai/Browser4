package ai.platon.pulsar.profileimport.service

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * A discovered source browser profile (Chrome / Edge / Safari).
 *
 * @param browser The browser family: `chrome`, `edge` or `safari`.
 * @param directory The profile directory name inside the user data dir (e.g. `Default`, `Profile 1`).
 *                  Empty for Safari, which has no profile directories.
 * @param name The user-visible profile name (e.g. `Person 1`), or [directory] when unknown.
 * @param userDataDir The browser user data dir containing the profile.
 * @param profileDir The profile directory itself.
 */
data class SourceProfile(
    val browser: String,
    val directory: String,
    val name: String,
    val userDataDir: Path,
    val profileDir: Path,
) {
    val ident: String
        get() = if (directory.isEmpty()) browser else "$browser:$directory"

    override fun toString(): String = ident
}

/**
 * Detects source browser installations and their profiles on the current
 * platform. Mirrors the well-known platform layout for Chrome/Edge and the
 * macOS-only Safari data files.
 */
open class SourceBrowserDetector {

    private val logger = LoggerFactory.getLogger(SourceBrowserDetector::class.java)

    private val osName: String = System.getProperty("os.name", "").lowercase()

    val isWindows: Boolean get() = osName.contains("win")
    val isMac: Boolean get() = osName.contains("mac")
    val isLinux: Boolean get() = !isWindows && !isMac

    /** The home directory of the current user. */
    val homeDir: Path = Path.of(System.getProperty("user.home"))

    /**
     * Candidate Chrome/Edge user data directories for the current platform,
     * in preference order. Empty entries are skipped.
     */
    open fun userDataDirs(browser: String): List<Path> {
        val base = if (isWindows) {
            val local = System.getenv("LOCALAPPDATA") ?: return emptyList()
            Path.of(local)
        } else if (isMac) {
            homeDir.resolve("Library/Application Support")
        } else {
            homeDir.resolve(".config")
        }
        return when (browser) {
            "chrome" -> if (isWindows) {
                listOf(
                    base.resolve("Google/Chrome/User Data"),
                    base.resolve("Google/Chrome SxS/User Data"),
                    base.resolve("Chromium/User Data"),
                    base.resolve("BraveSoftware/Brave-Browser/User Data"),
                )
            } else {
                listOf(
                    base.resolve("Google/Chrome"),
                    base.resolve("Google/Chrome Canary"),
                    base.resolve("Chromium"),
                    base.resolve("BraveSoftware/Brave-Browser"),
                )
            }
            "edge" -> if (isWindows) {
                listOf(base.resolve("Microsoft/Edge/User Data"))
            } else {
                listOf(
                    base.resolve("Microsoft Edge"),
                    base.resolve("microsoft-edge"),
                )
            }
            else -> emptyList()
        }.filter { Files.isDirectory(it) }
    }

    /** The first existing Chrome/Edge user data dir, or null. */
    open fun findUserDataDir(browser: String): Path? =
        userDataDirs(browser).firstOrNull { Files.isRegularFile(it.resolve("Local State")) }

    /**
     * Discover all Chrome/Edge profiles by reading the `Local State` file's
     * `profile.info_cache`. Returns an empty list when the file is missing or
     * malformed.
     */
    open fun listProfiles(browser: String): List<SourceProfile> {
        val userDataDir = findUserDataDir(browser) ?: return emptyList()
        return ChromeProfileReader.listProfiles(userDataDir, browser)
    }

    /**
     * Safari data file candidates on macOS. Each value is the first existing
     * candidate path, or null when Safari data is not present.
     */
    open fun safariPaths(): Map<String, Path?> {
        if (!isMac) return emptyMap()
        val safari = homeDir.resolve("Library/Safari")
        val container = homeDir.resolve("Library/Containers/com.apple.Safari/Data/Library")
        return linkedMapOf(
            "bookmarks" to firstExisting(
                safari.resolve("Bookmarks.plist"),
            ),
            "history" to firstExisting(
                safari.resolve("History.db"),
            ),
            "cookies" to firstExisting(
                container.resolve("Cookies/Cookies.binarycookies"),
                homeDir.resolve("Library/Cookies/Cookies.binarycookies"),
            ),
            "keychain" to firstExisting(
                homeDir.resolve("Library/Keychains/login.keychain-db"),
            ),
        )
    }

    private fun firstExisting(vararg paths: Path): Path? =
        paths.firstOrNull { Files.isRegularFile(it) }
}
