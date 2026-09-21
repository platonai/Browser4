package ai.platon.pulsar.profileimport.service

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Orchestrates browser personal-data imports:
 *
 * - Chrome / Edge: whole-profile copy (bookmarks, history, cookies,
 *   extensions, IndexedDB) with optional per-data pruning; passwords are
 *   refused by default (encryption-bound, see `allowPasswords`).
 * - Safari: converts `Bookmarks.plist` to a Chrome `Bookmarks` JSON file and
 *   `Cookies.binarycookies` to a cookies JSON array. History, passwords and
 *   extensions are not convertible in v1.
 *
 * Imports land in `~/.browser4/imports/<snapshot>/`, ready to be mounted with
 * `open --profile <dir>` (Chromium copies) or consumed file-by-file (Safari).
 */
open class ProfileImportService(
    private val detector: SourceBrowserDetector,
    private val copier: ProfileCopier,
    private val safariReader: SafariDataReader,
    private val importRoot: Path,
    private val allowPasswords: Boolean,
    private val browser4Root: Path = Path.of(
        System.getProperty("browser4.data.dir", System.getProperty("user.home")),
        ".browser4"
    ),
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ProfileImportService::class.java)
        private val objectMapper = pulsarObjectMapper()
        private val TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Data types that can be pruned independently on a Chromium copy. */
        val SUPPORTED_DATA = setOf("bookmarks", "history", "passwords", "cookies", "extensions")
    }

    /**
     * Discovers source browsers and their profiles on this machine.
     */
    fun listSources(): Map<String, Any?> {
        val chrome = detector.listProfiles("chrome").map { profileSummary(it) }
        val edge = detector.listProfiles("edge").map { profileSummary(it) }
        val safari = detector.safariPaths().mapValues { (_, p) -> p?.toString() }

        return linkedMapOf(
            "chrome" to chrome,
            "edge" to edge,
            "safari" to safari,
        )
    }

    /**
     * Imports personal data from [source] into a new snapshot directory.
     *
     * @param source `chrome`, `edge` or `safari`
     * @param profile Chrome/Edge profile name or directory name (defaults to
     *                the first profile); ignored for Safari
     * @param data Comma-separated subset of [SUPPORTED_DATA]; null = all
     * @param into Landing: `temp` (snapshot dir, default), `prototype`
     *              (seed ~/.browser4/browser/chrome/prototype/google-chrome),
     *              or `default` (replace the default context dir).
     * @return A summary map with the import directory, counts and warnings.
     */
    fun import(
        source: String,
        profile: String?,
        data: String?,
        into: String?,
    ): Map<String, Any?> {
        require(source in setOf("chrome", "edge", "safari")) {
            "Unsupported source '$source'. Expected chrome, edge or safari."
        }
        val requested = parseData(data)
        val landing = into?.trim()?.lowercase()?.ifEmpty { null } ?: "temp"
        require(landing in setOf("temp", "prototype", "default")) {
            "Unsupported into '$landing'. Expected temp, prototype or default."
        }

        val result = when (source) {
            "safari" -> importSafari(requested)
            else -> importChromium(source, profile, requested)
        }
        if (landing == "temp") return result

        val snapshotRoot = Path.of(result["importDir"].toString())
        val snapshotProfile = Path.of(result["profileDir"].toString())
        // Chromium snapshots: profile/<dir> plus Local State at the snapshot
        // profile level; Safari snapshots: profile/ directly.
        val relProfile = snapshotRoot.relativize(snapshotProfile)
        val target = landingTarget(landing)
        if (Files.exists(target) && Files.list(target).use { it.findAny().isPresent }) {
            throw IllegalStateException(
                "$landing landing target $target is not empty; refusing to overwrite. " +
                    "Delete it first, or use --into temp."
            )
        }
        Files.createDirectories(target)
        copySnapshot(snapshotRoot, relProfile, target)
        return result + mapOf("landedAt" to target.toString())
    }

    /** The Browser4-managed landing directory for prototype/default modes. */
    private fun landingTarget(landing: String): Path {
        val chrome = browser4Root.resolve("browser/chrome")
        return when (landing) {
            "prototype" -> chrome.resolve("prototype/google-chrome")
            "default" -> chrome.resolve("default/PULSAR_CHROME")
            else -> throw IllegalArgumentException("Unsupported landing: $landing")
        }
    }

    /**
     * Copies `snapshotRoot/relProfile` contents (the profile files) into
     * [target], plus `snapshotRoot/profile/Local State` when present
     * (Chromium snapshots keep it one level above the profile dir).
     */
    private fun copySnapshot(snapshotRoot: Path, relProfile: Path, target: Path) {
        val srcProfile = snapshotRoot.resolve(relProfile)
        if (Files.isDirectory(srcProfile)) {
            Files.walk(srcProfile).use { stream ->
                stream.filter { it != srcProfile }.forEach { src ->
                    val rel = srcProfile.relativize(src)
                    val dst = target.resolve(rel)
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst)
                    } else {
                        Files.createDirectories(dst.parent)
                        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
        // Chromium: Local State sits next to the profile dir (snapshot/profile/).
        val localState = snapshotRoot.resolve(relProfile).parent?.resolve("Local State")
        if (localState != null && Files.isRegularFile(localState)) {
            Files.createDirectories(target)
            Files.copy(localState, target.resolve("Local State"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ------------------------------------------------------------------
    // Chromium (Chrome / Edge)
    // ------------------------------------------------------------------

    private fun importChromium(
        browser: String,
        profileInput: String?,
        requested: Set<String>,
    ): Map<String, Any?> {
        val userDataDir = detector.findUserDataDir(browser)
            ?: throw IllegalStateException("No $browser installation found on this machine")

        val profile = if (profileInput.isNullOrBlank()) {
            ChromeProfileReader.listProfiles(userDataDir, browser).firstOrNull()
                ?: throw IllegalStateException("No $browser profiles found in $userDataDir")
        } else {
            ChromeProfileReader.resolveProfile(userDataDir, browser, profileInput)
        }

        val warnings = mutableListOf<String>()
        // Passwords are encrypted with OS-bound keys and cannot be moved
        // reliably. Default full imports drop them with a warning; an EXPLICIT
        // passwords request is an error unless explicitly allowed.
        val effective = if ("passwords" in requested && !allowPasswords) {
            if (requested == SUPPORTED_DATA) {
                warnings += "Passwords were not imported (disabled by default). " +
                    "Set profileimport.allow.passwords=true to copy Login Data as-is " +
                    "(same machine + same user only)."
                requested - "passwords"
            } else {
                throw IllegalArgumentException(
                    "Importing passwords is disabled by default: the password database is " +
                        "encrypted with OS-bound keys (DPAPI / Keychain / app-bound) and cannot be " +
                        "reliably moved. Set profileimport.allow.passwords=true to copy Login Data " +
                        "as-is (same machine + same user only), or use attach + state-save for cookies."
                )
            }
        } else {
            requested
        }

        val snapshot = snapshotDir(browser, profile.directory)
        val profileDest = snapshot.resolve("profile")
        val copied = copier.copyProfile(userDataDir, profile, profileDest)

        if (effective != SUPPORTED_DATA) {
            val pruned = prune(profileDest.resolve(profile.directory), effective)
            warnings += "Pruned ${pruned.size} data file(s) not in the requested set: $effective"
        }

        writeMeta(snapshot, browser, profile, effective, copied)

        return linkedMapOf(
            "importDir" to snapshot.toString(),
            "profileDir" to profileDest.resolve(profile.directory).toString(),
            "browser" to browser,
            "sourceProfile" to profile.ident,
            "filesCopied" to copied,
            "data" to effective.sorted(),
            "warnings" to warnings,
            "nextStep" to "browser4-cli open --profile ${profileDest.resolve(profile.directory)}",
        )
    }

    /** Deletes data files not present in [requested]. Returns deleted file count. */
    private fun prune(profileDir: Path, requested: Set<String>): List<Path> {
        val deleted = mutableListOf<Path>()
        fun del(p: Path) {
            if (Files.exists(p)) {
                Files.deleteIfExists(p)
                deleted.add(p)
            }
        }
        if ("bookmarks" !in requested) del(profileDir.resolve("Bookmarks"))
        if ("history" !in requested) del(profileDir.resolve("History"))
        if ("passwords" !in requested) {
            del(profileDir.resolve("Login Data"))
            del(profileDir.resolve("Login Data For Account"))
        }
        if ("cookies" !in requested) {
            val network = profileDir.resolve("Network")
            if (Files.isDirectory(network)) {
                network.toFile().deleteRecursively()
                deleted.add(network)
            }
        }
        if ("extensions" !in requested) {
            val extensions = profileDir.resolve("Extensions")
            if (Files.isDirectory(extensions)) {
                extensions.toFile().deleteRecursively()
                deleted.add(extensions)
            }
        }
        return deleted
    }

    // ------------------------------------------------------------------
    // Safari (macOS)
    // ------------------------------------------------------------------

    private fun importSafari(requested: Set<String>): Map<String, Any?> {
        val paths = detector.safariPaths()
        val warnings = mutableListOf<String>()

        val unsupported = requested - setOf("bookmarks", "cookies")
        if (unsupported.isNotEmpty()) {
            warnings += "Safari cannot import: ${unsupported.joinToString()} " +
                "(history needs SQLite mapping, passwords live in the Keychain, extensions are signed apps)."
        }

        val snapshot = snapshotDir("safari", "Default")
        val profileDest = snapshot.resolve("profile")
        Files.createDirectories(profileDest)

        var bookmarkCount = 0
        var cookieCount = 0
        if ("bookmarks" in requested) {
            val plist = paths["bookmarks"]
            if (plist == null) {
                warnings += "Safari bookmarks file not found (expected ~/Library/Safari/Bookmarks.plist)"
            } else {
                val bookmarks = safariReader.parseBookmarks(plist)
                bookmarkCount = countBookmarks(bookmarks)
                safariReader.writeChromeBookmarks(bookmarks, profileDest.resolve("Bookmarks"))
            }
        }
        if ("cookies" in requested) {
            val cookiesFile = paths["cookies"]
            if (cookiesFile == null) {
                warnings += "Safari cookies file not found (expected Cookies.binarycookies under the Safari container)"
            } else {
                val cookies = safariReader.parseCookies(cookiesFile)
                cookieCount = cookies.size
                safariReader.writeCookiesJson(cookies, snapshot.resolve("cookies.json"))
            }
        }

        writeMeta(snapshot, "safari", null, requested, bookmarkCount + cookieCount)

        return linkedMapOf(
            "importDir" to snapshot.toString(),
            "bookmarksImported" to bookmarkCount,
            "cookiesImported" to cookieCount,
            "data" to requested.sorted(),
            "warnings" to warnings,
            "nextStep" to "Mount the copied Bookmarks file into a target profile while the browser is closed, " +
                "or load cookies.json via state-load / cookies set --curl.",
        )
    }

    private fun countBookmarks(bookmarks: List<SafariDataReader.SafariBookmark>): Int =
        bookmarks.sumOf { b -> 1 + if (b.url != null) 0 else countBookmarks(b.children) }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun parseData(data: String?): Set<String> {
        if (data.isNullOrBlank()) return SUPPORTED_DATA
        val parsed = data.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val unknown = parsed - SUPPORTED_DATA
        require(unknown.isEmpty()) {
            "Unsupported data type(s): ${unknown.joinToString()}. Supported: ${SUPPORTED_DATA.joinToString()}"
        }
        return parsed
    }

    private fun snapshotDir(browser: String, directory: String): Path {
        val safeDir = directory.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = importRoot.resolve("$browser-$safeDir-${LocalDateTime.now().format(TS)}")
        Files.createDirectories(dir)
        return dir
    }

    private fun profileSummary(p: SourceProfile): Map<String, Any?> = linkedMapOf(
        "directory" to p.directory,
        "name" to p.name,
        "userDataDir" to p.userDataDir.toString(),
        "profileDir" to p.profileDir.toString(),
    )

    private fun writeMeta(
        snapshot: Path,
        browser: String,
        profile: SourceProfile?,
        data: Set<String>,
        copied: Int,
    ) {
        val meta = linkedMapOf<String, Any?>(
            "createdAt" to LocalDateTime.now().toString(),
            "browser" to browser,
            "sourceProfile" to profile?.ident,
            "data" to data.sorted(),
            "filesCopied" to copied,
        )
        objectMapper.writerWithDefaultPrettyPrinter()
            .writeValue(snapshot.resolve("meta.json").toFile(), meta)
    }
}
