package ai.platon.browser4.importer

import ai.platon.browser4.importer.chrome.ChromeBookmarksParser
import ai.platon.browser4.importer.chrome.ChromeSQLiteReader
import ai.platon.browser4.importer.firefox.FirefoxCookieParser
import ai.platon.browser4.importer.firefox.FirefoxPlacesParser
import ai.platon.browser4.importer.model.ImportReport
import ai.platon.browser4.importer.model.ImportSource
import ai.platon.browser4.importer.model.ImportSummary
import ai.platon.browser4.importer.source.BrowserDataLocator
import ai.platon.browser4.importer.writer.ChromeProfileWriter
import ai.platon.pulsar.browser.BrowserProfile
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant

/**
 * Main entry point for importing browser data from other installed browsers
 * into a Browser4-managed Chrome profile.
 *
 * ## Usage
 *
 * ```kotlin
 * val profile = BrowserProfile.createDefault()
 * val importer = BrowserDataImporter(profile)
 *
 * // Import from all available browsers
 * val summary = importer.importAll()
 * println("Imported ${summary.totalBookmarks} bookmarks from ${summary.reports.size} browsers")
 *
 * // Or import from a specific browser
 * val dataDirs = BrowserDataLocator.locate(ImportSource.CHROME)
 * if (dataDirs.isNotEmpty()) {
 *     val report = importer.importFrom(ImportSource.CHROME, dataDirs.first())
 *     println("${report.bookmarksImported} bookmarks imported")
 * }
 * ```
 *
 * ## Error handling
 *
 * Each browser source is imported independently. Errors are accumulated in
 * [ImportReport.errors] and [ImportReport.warnings]. Fatal errors (e.g. corrupt data)
 * are recorded per-source and do not block other sources.
 */
class BrowserDataImporter(
    private val profile: BrowserProfile,
    private val options: ImportOptions = ImportOptions()
) {

    companion object {
        private val logger = LoggerFactory.getLogger(BrowserDataImporter::class.java)
    }

    /**
     * Import data from all browsers detected on the system.
     *
     * Only browsers matching [ImportOptions.sourceBrowsers] are considered,
     * and only data types enabled in [ImportOptions] are imported.
     *
     * @return summary with per-source reports.
     */
    fun importAll(): ImportSummary {
        val startTime = Instant.now()
        val available = BrowserDataLocator.locateAllAvailable()

        logger.info("Found {} source browser(s) with data", available.size)

        val reports = available.entries
            .filter { (source, _) -> source in options.sourceBrowsers }
            .map { (source, dataDirs) ->
                // Use the first (primary) profile directory for each browser
                importFrom(source, dataDirs.first())
            }

        val summary = ImportSummary(reports = reports, startTime = startTime, endTime = Instant.now())

        logger.info(
            "Import complete: {} bookmark(s) from {} source(s) ({} error(s), {} warning(s))",
            summary.totalBookmarks, summary.reports.size,
            summary.totalErrors, summary.totalWarnings
        )
        return summary
    }

    /**
     * Import data from a specific [source] browser using its profile [dataDir].
     *
     * For Chrome-based browsers, [dataDir] is the "Default/" profile directory
     * (inside the "User Data" directory). For Firefox, [dataDir] is the profile
     * directory containing `places.sqlite`.
     *
     * @param source  which browser to import from
     * @param dataDir path to the browser's profile/data directory
     * @return a report describing what was imported and any errors
     */
    fun importFrom(source: ImportSource, dataDir: Path): ImportReport {
        logger.info("Importing from {} ({})", source.displayName, dataDir)

        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        var bookmarksImported = 0
        var cookiesImported = 0

        try {
            when {
                source.isChromeBased -> {
                    val (b, c) = importFromChromeBased(source, dataDir, errors, warnings)
                    bookmarksImported = b
                    cookiesImported = c
                }
                else -> {
                    val (b, c) = importFromFirefox(dataDir, errors, warnings)
                    bookmarksImported = b
                    cookiesImported = c
                }
            }
        } catch (e: Exception) {
            logger.error("Unexpected error importing from {} | {}", source.displayName, e.message)
            errors.add("Unexpected: ${e.message}")
        }

        return ImportReport(
            importTime = Instant.now(),
            source = source,
            bookmarksImported = bookmarksImported,
            cookiesImported = cookiesImported,
            errors = errors,
            warnings = warnings,
        )
    }

    // -- Chrome-based import --

    private fun importFromChromeBased(
        source: ImportSource,
        profileDir: Path,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ): Pair<Int, Int> {
        val writer = ChromeProfileWriter(profile)
        var bookmarksImported = 0
        var cookiesImported = 0

        // Bookmarks: always readable (plain JSON)
        if (options.importBookmarks) {
            val bookmarksFile = profileDir.resolve("Bookmarks")
            val bookmarks = try {
                ChromeBookmarksParser.parse(bookmarksFile)
            } catch (e: Exception) {
                errors.add("Bookmarks: ${e.message}")
                emptyList()
            }
            bookmarksImported = writer.writeBookmarks(bookmarks, source)
        }

        // Cookies: SQLite, requires browser to be closed
        if (options.importCookies) {
            val cookiesDb = profileDir.resolve("Cookies")
            val cookies = try {
                ChromeSQLiteReader.readCookies(cookiesDb)
            } catch (e: DatabaseLockedException) {
                warnings.add("Cookies: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                errors.add("Cookies: ${e.message}")
                emptyList()
            }
            // Cookie writing is a future phase — for now, count as imported
            // but logs a note to the caller
            if (cookies.isNotEmpty()) {
                logger.info("Cookie import not yet implemented ({} cookies from {} not written)",
                    cookies.size, source.displayName)
            }
            // cookiesImported = writer.writeCookies(cookies)  // future
        }

        return bookmarksImported to cookiesImported
    }

    // -- Firefox import --

    private fun importFromFirefox(
        profileDir: Path,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ): Pair<Int, Int> {
        val writer = ChromeProfileWriter(profile)
        var bookmarksImported = 0
        var cookiesImported = 0

        val placesDb = profileDir.resolve("places.sqlite")

        // Bookmarks from places.sqlite
        if (options.importBookmarks) {
            val bookmarks = try {
                FirefoxPlacesParser.readBookmarks(placesDb)
            } catch (e: DatabaseLockedException) {
                warnings.add("Bookmarks: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                errors.add("Bookmarks: ${e.message}")
                emptyList()
            }
            bookmarksImported = writer.writeBookmarks(bookmarks, ImportSource.FIREFOX)
        }

        // Cookies from cookies.sqlite
        if (options.importCookies) {
            val cookiesDb = profileDir.resolve("cookies.sqlite")
            val cookies = try {
                FirefoxCookieParser.readCookies(cookiesDb)
            } catch (e: DatabaseLockedException) {
                warnings.add("Cookies: ${e.message}")
                emptyList()
            } catch (e: Exception) {
                errors.add("Cookies: ${e.message}")
                emptyList()
            }
            if (cookies.isNotEmpty()) {
                logger.info("Cookie import not yet implemented ({} cookies from Firefox not written)",
                    cookies.size)
            }
        }

        return bookmarksImported to cookiesImported
    }
}
