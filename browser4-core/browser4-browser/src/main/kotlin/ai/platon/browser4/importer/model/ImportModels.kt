package ai.platon.browser4.importer.model

import java.time.Instant

/**
 * Represents a single bookmark imported from another browser.
 */
data class ImportedBookmark(
    val title: String,
    val url: String,
    /** Target folder path within Chrome bookmarks (e.g. "Bookmarks bar/Folder"). */
    val folder: String = "Imported",
    /**
     * Chrome timestamp: microseconds since 1601-01-01 UTC (Windows epoch).
     * Zero means no original date is available and the current time should be used.
     */
    val dateAdded: Long = 0
)

/**
 * Represents a browsing history entry imported from another browser.
 */
data class ImportedHistoryEntry(
    val url: String,
    val title: String,
    val visitCount: Int = 1,
    val lastVisitTime: Long = 0
)

/**
 * Represents a cookie imported from another browser.
 */
data class ImportedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    /** Expiry time as a Unix timestamp (seconds since epoch), or Long.MAX_VALUE for session cookies. */
    val expiry: Long = Long.MAX_VALUE,
    /** Creation time as microseconds since 1601-01-01 (Chrome time). */
    val creationTime: Long = 0
)

/**
 * Identifies which browser data is being imported from.
 */
enum class ImportSource(val displayName: String) {
    CHROME("Google Chrome"),
    EDGE("Microsoft Edge"),
    BRAVE("Brave"),
    OPERA("Opera"),
    VIVALDI("Vivaldi"),
    CHROMIUM("Chromium"),
    FIREFOX("Firefox");

    /** Whether this source uses Chromium-style data layout (Bookmarks JSON, SQLite in Default/). */
    val isChromeBased: Boolean get() = this != FIREFOX
}

/**
 * Outcome of importing from a single browser source.
 */
data class ImportReport(
    val importTime: Instant = Instant.now(),
    val source: ImportSource,
    val bookmarksImported: Int = 0,
    val cookiesImported: Int = 0,
    val historyImported: Int = 0,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val totalItems: Int get() = bookmarksImported + cookiesImported + historyImported
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
}

/**
 * Aggregate result of importing from multiple browser sources.
 */
data class ImportSummary(
    val reports: List<ImportReport>,
    val startTime: Instant = Instant.now(),
    val endTime: Instant = Instant.now()
) {
    val totalBookmarks: Int get() = reports.sumOf { it.bookmarksImported }
    val totalCookies: Int get() = reports.sumOf { it.cookiesImported }
    val totalHistory: Int get() = reports.sumOf { it.historyImported }
    val totalItems: Int get() = reports.sumOf { it.totalItems }
    val totalErrors: Int get() = reports.sumOf { it.errors.size }
    val totalWarnings: Int get() = reports.sumOf { it.warnings.size }
    val successCount: Int get() = reports.count { !it.hasErrors }
}
