package ai.platon.browser4.importer.firefox

import ai.platon.browser4.importer.DatabaseLockedException
import ai.platon.browser4.importer.ImportException
import ai.platon.browser4.importer.model.ImportSource
import ai.platon.browser4.importer.model.ImportedBookmark
import ai.platon.browser4.importer.model.ImportedHistoryEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Reads bookmarks and history from Firefox's `places.sqlite` database.
 *
 * Firefox stores both bookmarks and history in a single SQLite database.
 *
 * ## Schema (simplified)
 *
 * `moz_bookmarks` — bookmark tree
 * - `id`, `type` (1=bookmark, 2=folder, 3=separator)
 * - `fk` → `moz_places.id` for URL-type bookmarks
 * - `parent` → `moz_bookmarks.id` for the tree structure
 * - `title`, `dateAdded`, `lastModified`
 *
 * `moz_places` — visited URLs
 * - `id`, `url`, `title`, `rev_host`, `visit_count`, `last_visit_date`, `hidden`
 *
 * Timestamps are in microseconds since 1970-01-01 (Unix epoch µs).
 */
class FirefoxPlacesParser {

    companion object {
        private val logger = LoggerFactory.getLogger(FirefoxPlacesParser::class.java)

        /**
         * Read all bookmarks from a Firefox `places.sqlite` database.
         *
         * Uses a recursive CTE to reconstruct the folder hierarchy.
         */
        fun readBookmarks(placesDb: Path): List<ImportedBookmark> {
            if (!Files.isRegularFile(placesDb)) {
                logger.debug("Firefox places.sqlite not found: {}", placesDb)
                return emptyList()
            }
            return try {
                val jdbcUrl = "jdbc:sqlite:${placesDb.toAbsolutePath()}"
                DriverManager.getConnection(jdbcUrl).use { conn ->
                    conn.createStatement().use { stmt ->
                        // Recursive CTE: walk moz_bookmarks to build folder paths,
                        // then join with moz_places to get URLs for leaf bookmarks.
                        // Start from direct children of root folders (parent IN roots)
                        // so that root folder names ("Firefox") don't appear in paths.
                        val sql = """
                            WITH RECURSIVE folder_paths(id, path) AS (
                                SELECT id, COALESCE(NULLIF(title, ''), '(untitled)')
                                FROM moz_bookmarks
                                WHERE type = 2
                                  AND parent IN (SELECT id FROM moz_bookmarks WHERE type = 2 AND parent = 0)
                                UNION ALL
                                SELECT b.id, fp.path || '/' || COALESCE(NULLIF(b.title, ''), '(untitled)')
                                FROM moz_bookmarks b
                                JOIN folder_paths fp ON b.parent = fp.id
                                WHERE b.type = 2
                            )
                            SELECT COALESCE(NULLIF(b.title, ''), p.url) AS title,
                                   p.url,
                                   COALESCE(fp.path, 'Firefox') AS path,
                                   b.dateAdded
                            FROM moz_bookmarks b
                            JOIN moz_places p ON b.fk = p.id
                            LEFT JOIN folder_paths fp ON b.parent = fp.id
                            WHERE b.type = 1
                              AND p.url IS NOT NULL
                              AND p.url != ''
                            ORDER BY b.dateAdded DESC
                        """.trimIndent()

                        val rs = stmt.executeQuery(sql)
                        val results = mutableListOf<ImportedBookmark>()
                        while (rs.next()) {
                            results.add(
                                ImportedBookmark(
                                    title = rs.getString("title") ?: "",
                                    url = rs.getString("url") ?: continue,
                                    folder = rs.getString("path") ?: "Firefox",
                                    dateAdded = rs.getLong("dateAdded")
                                )
                            )
                        }
                        logger.info("Read {} bookmarks from {}", results.size, placesDb)
                        results
                    }
                }
            } catch (e: SQLException) {
                if (isLockedError(e)) throw DatabaseLockedException(ImportSource.FIREFOX, "places.sqlite")
                logger.error("Failed to read Firefox places from {} | {}", placesDb, e.message)
                throw ImportException("Failed to read Firefox places: ${e.message}", e)
            }
        }

        /**
         * Read browsing history from Firefox `places.sqlite`.
         */
        fun readHistory(placesDb: Path): List<ImportedHistoryEntry> {
            if (!Files.isRegularFile(placesDb)) {
                logger.debug("Firefox places.sqlite not found: {}", placesDb)
                return emptyList()
            }
            return try {
                val jdbcUrl = "jdbc:sqlite:${placesDb.toAbsolutePath()}"
                DriverManager.getConnection(jdbcUrl).use { conn ->
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery(
                            """SELECT url, title, visit_count, last_visit_date
                               FROM moz_places
                               WHERE visit_count > 0 AND hidden = 0
                               ORDER BY last_visit_date DESC"""
                        )
                        val results = mutableListOf<ImportedHistoryEntry>()
                        while (rs.next()) {
                            results.add(
                                ImportedHistoryEntry(
                                    url = rs.getString("url") ?: continue,
                                    title = rs.getString("title") ?: "",
                                    visitCount = rs.getInt("visit_count"),
                                    lastVisitTime = rs.getLong("last_visit_date")
                                )
                            )
                        }
                        logger.info("Read {} history entries from {}", results.size, placesDb)
                        results
                    }
                }
            } catch (e: SQLException) {
                if (isLockedError(e)) throw DatabaseLockedException(ImportSource.FIREFOX, "places.sqlite")
                logger.error("Failed to read Firefox history from {} | {}", placesDb, e.message)
                throw ImportException("Failed to read Firefox history: ${e.message}", e)
            }
        }

        private fun isLockedError(e: SQLException): Boolean {
            val message = e.message ?: return false
            return message.contains("database is locked", ignoreCase = true) ||
                    message.contains("SQLITE_BUSY", ignoreCase = true)
        }
    }
}
