package ai.platon.browser4.importer.source

import ai.platon.browser4.importer.model.ImportSource
import org.apache.commons.lang3.SystemUtils
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Discovers browser data directories on the local system.
 *
 * Supports Chrome, Edge, Brave, Opera, Vivaldi, Chromium (Chromium-based, single Default/ profile)
 * and Firefox (multi-profile via profiles.ini).
 *
 * Path conventions follow the standard install locations for each platform.
 */
class BrowserDataLocator {

    companion object {
        private val logger = LoggerFactory.getLogger(BrowserDataLocator::class.java)

        // -- Public API --

        /**
         * Locate the primary user data directory for the given [source] browser.
         * For Chrome-based browsers this is the "User Data" directory (containing "Default/").
         * For Firefox this returns the profile directories found via profiles.ini.
         *
         * Returns an empty list if the browser is not installed or no profiles exist.
         */
        fun locate(source: ImportSource): List<Path> {
            return when (source) {
                ImportSource.CHROME -> locateChromeDataDirs()
                ImportSource.EDGE -> locateEdgeDataDirs()
                ImportSource.BRAVE -> locateBraveDataDirs()
                ImportSource.OPERA -> locateOperaDataDirs()
                ImportSource.VIVALDI -> locateVivaldiDataDirs()
                ImportSource.CHROMIUM -> locateChromiumDataDirs()
                ImportSource.FIREFOX -> locateFirefoxProfileDirs()
            }
        }

        /**
         * Discover all available browser sources on this system.
         *
         * @return map of source → list of profile/data directories found (only sources with at least one).
         */
        fun locateAllAvailable(): Map<ImportSource, List<Path>> {
            return ImportSource.entries.associateWith { source ->
                runCatching { locate(source) }.getOrDefault(emptyList())
            }.filter { it.value.isNotEmpty() }
        }

        // -- Chrome-based browsers --

        private fun locateChromeDataDirs(): List<Path> {
            val base = when {
                SystemUtils.IS_OS_WINDOWS -> {
                    val localAppData = System.getenv("LOCALAPPDATA") ?: return emptyList()
                    Path.of(localAppData, "Google", "Chrome", "User Data")
                }
                SystemUtils.IS_OS_MAC -> userHome("Library/Application Support/Google/Chrome")
                SystemUtils.IS_OS_LINUX -> userHome(".config/google-chrome")
                else -> return emptyList()
            }
            return locateDefaultProfiles(base)
        }

        private fun locateEdgeDataDirs(): List<Path> {
            val base = when {
                SystemUtils.IS_OS_WINDOWS -> {
                    val localAppData = System.getenv("LOCALAPPDATA") ?: return emptyList()
                    Path.of(localAppData, "Microsoft", "Edge", "User Data")
                }
                SystemUtils.IS_OS_MAC -> userHome("Library/Application Support/Microsoft Edge")
                SystemUtils.IS_OS_LINUX -> userHome(".config/microsoft-edge")
                else -> return emptyList()
            }
            return locateDefaultProfiles(base)
        }

        private fun locateBraveDataDirs(): List<Path> {
            val base = when {
                SystemUtils.IS_OS_WINDOWS -> {
                    val localAppData = System.getenv("LOCALAPPDATA") ?: return emptyList()
                    Path.of(localAppData, "BraveSoftware", "Brave-Browser", "User Data")
                }
                SystemUtils.IS_OS_MAC -> userHome("Library/Application Support/BraveSoftware/Brave-Browser")
                SystemUtils.IS_OS_LINUX -> userHome(".config/BraveSoftware/Brave-Browser")
                else -> return emptyList()
            }
            return locateDefaultProfiles(base)
        }

        private fun locateOperaDataDirs(): List<Path> {
            val base = when {
                SystemUtils.IS_OS_WINDOWS -> {
                    val appData = System.getenv("APPDATA") ?: return emptyList()
                    Path.of(appData, "Opera Software", "Opera Stable")
                }
                SystemUtils.IS_OS_MAC -> userHome("Library/Application Support/com.operasoftware.Opera")
                SystemUtils.IS_OS_LINUX -> userHome(".config/opera")
                else -> return emptyList()
            }
            // Opera uses a top-level data dir, not a "User Data" parent
            if (!Files.isDirectory(base)) return emptyList()
            return locateDefaultProfiles(base)
        }

        private fun locateVivaldiDataDirs(): List<Path> {
            val base = when {
                SystemUtils.IS_OS_WINDOWS -> {
                    val localAppData = System.getenv("LOCALAPPDATA") ?: return emptyList()
                    Path.of(localAppData, "Vivaldi", "User Data")
                }
                SystemUtils.IS_OS_MAC -> userHome("Library/Application Support/Vivaldi")
                SystemUtils.IS_OS_LINUX -> userHome(".config/vivaldi")
                else -> return emptyList()
            }
            return locateDefaultProfiles(base)
        }

        private fun locateChromiumDataDirs(): List<Path> {
            val base = when {
                SystemUtils.IS_OS_WINDOWS -> {
                    val localAppData = System.getenv("LOCALAPPDATA") ?: return emptyList()
                    Path.of(localAppData, "Chromium", "User Data")
                }
                SystemUtils.IS_OS_MAC -> userHome("Library/Application Support/Chromium")
                SystemUtils.IS_OS_LINUX -> userHome(".config/chromium")
                else -> return emptyList()
            }
            return locateDefaultProfiles(base)
        }

        // -- Firefox --

        private fun locateFirefoxProfileDirs(): List<Path> {
            val home = Path.of(System.getProperty("user.home"))
            val profilesIni = when {
                SystemUtils.IS_OS_WINDOWS -> {
                    val appData = System.getenv("APPDATA") ?: return emptyList()
                    Path.of(appData, "Mozilla", "Firefox", "profiles.ini")
                }
                SystemUtils.IS_OS_MAC -> home.resolve("Library/Application Support/Firefox/profiles.ini")
                SystemUtils.IS_OS_LINUX -> home.resolve(".mozilla/firefox/profiles.ini")
                else -> return emptyList()
            }
            if (!Files.isRegularFile(profilesIni)) {
                logger.debug("Firefox profiles.ini not found at {}", profilesIni)
                return emptyList()
            }
            return parseFirefoxProfilesIni(profilesIni)
        }

        // -- Internal helpers --

        /**
         * Parse a Firefox profiles.ini file and return the resolved profile directories.
         *
         * Format (INI-like):
         * ```
         * [Profile0]
         * Name=default
         * IsRelative=1
         * Path=Profiles/abc123.default
         * Default=yes
         * ```
         */
        internal fun parseFirefoxProfilesIni(iniPath: Path): List<Path> {
            val baseDir = iniPath.parent
            val content = try {
                Files.readString(iniPath)
            } catch (e: Exception) {
                logger.warn("Failed to read Firefox profiles.ini | {}", e.message)
                return emptyList()
            }

            val profiles = mutableListOf<Path>()
            var currentPath: String? = null
            var isRelative = true
            var isDefault = false

            fun commitProfile() {
                val path = currentPath ?: return
                val resolved = if (isRelative) baseDir.resolve(path) else Path.of(path)
                if (Files.isDirectory(resolved)) {
                    profiles.add(resolved)
                }
                currentPath = null
                isRelative = true
                isDefault = false
            }

            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[")) {
                    commitProfile()
                } else if (trimmed.startsWith("Path=", ignoreCase = true)) {
                    currentPath = trimmed.substringAfter('=')
                } else if (trimmed.startsWith("IsRelative=", ignoreCase = true)) {
                    isRelative = trimmed.substringAfter('=').trim() != "0"
                } else if (trimmed.startsWith("Default=", ignoreCase = true)) {
                    isDefault = trimmed.substringAfter('=').trim() == "1"
                }
            }
            commitProfile()

            if (profiles.isEmpty()) {
                logger.debug("No valid Firefox profiles found in {}", iniPath)
            }
            return profiles
        }

        /**
         * Locate the profile directories (e.g. "Default", "Profile 1") under a Chrome-style
         * User Data directory.
         *
         * Returns each directory that contains bookmarks/history data — currently just
         * the primary "Default" directory for Chromium-based browsers.
         */
        private fun locateDefaultProfiles(userDataDir: Path): List<Path> {
            if (!Files.isDirectory(userDataDir)) {
                logger.debug("Browser user data dir not found: {}", userDataDir)
                return emptyList()
            }

            // Try "Default" first (primary profile for all Chromium browsers)
            val defaultProfile = userDataDir.resolve("Default")
            if (Files.isDirectory(defaultProfile)) {
                return listOf(defaultProfile)
            }

            // Scan for numbered profiles ("Profile 1", "Profile 2", etc.)
            val profiles = try {
                Files.list(userDataDir).use { stream ->
                    stream.filter { Files.isDirectory(it) }
                        .filter { it.fileName.toString().startsWith("Profile ") }
                        .sorted()
                        .toList()
                }
            } catch (e: Exception) {
                logger.debug("Failed to list profile dirs in {} | {}", userDataDir, e.message)
                emptyList()
            }

            return profiles.ifEmpty {
                logger.debug("No Default/ or Profile N/ directories found in {}", userDataDir)
                emptyList()
            }
        }

        private fun userHome(relative: String): Path {
            return Path.of(System.getProperty("user.home")).resolve(relative)
        }
    }
}
