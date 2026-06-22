package ai.platon.browser4.importer.chrome

import ai.platon.browser4.importer.DatabaseLockedException
import ai.platon.browser4.importer.ImportException
import ai.platon.browser4.importer.model.ImportSource
import ai.platon.browser4.importer.model.ImportedCookie
import ai.platon.browser4.importer.model.ImportedHistoryEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Reads data from Chrome/Chromium SQLite databases (History, Cookies, Login Data).
 *
 * All Chrome user data SQLite databases are in the profile directory
 * (e.g. `Default/History`, `Default/Cookies`, `Default/Login Data`).
 *
 * **Important**: Chrome holds exclusive locks on these databases while running.
 * Reads will fail with [DatabaseLockedException] if Chrome is open for that profile.
 */
class ChromeSQLiteReader {

    companion object {
        private val logger = LoggerFactory.getLogger(ChromeSQLiteReader::class.java)

        /**
         * Read browsing history from a Chrome History SQLite database.
         *
         * Table: `urls` (id, url, title, visit_count, typed_count, last_visit_time, hidden)
         * Timestamps are microseconds since 1601-01-01 (Chrome time).
         */
        fun readHistory(historyDb: Path): List<ImportedHistoryEntry> {
            if (!Files.isRegularFile(historyDb)) {
                logger.debug("History database not found: {}", historyDb)
                return emptyList()
            }
            return try {
                val jdbcUrl = "jdbc:sqlite:${historyDb.toAbsolutePath()}"
                DriverManager.getConnection(jdbcUrl).use { conn ->
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery(
                            "SELECT url, title, visit_count, last_visit_time FROM urls ORDER BY last_visit_time DESC"
                        )
                        val results = mutableListOf<ImportedHistoryEntry>()
                        while (rs.next()) {
                            results.add(
                                ImportedHistoryEntry(
                                    url = rs.getString("url") ?: continue,
                                    title = rs.getString("title") ?: "",
                                    visitCount = rs.getInt("visit_count"),
                                    lastVisitTime = rs.getLong("last_visit_time")
                                )
                            )
                        }
                        logger.info("Read {} history entries from {}", results.size, historyDb)
                        results
                    }
                }
            } catch (e: SQLException) {
                if (isLockedError(e)) throw DatabaseLockedException(ImportSource.CHROME, historyDb.fileName.toString())
                logger.error("Failed to read Chrome history from {} | {}", historyDb, e.message)
                throw ImportException("Failed to read Chrome history: ${e.message}", e)
            }
        }

        /**
         * Read cookies from a Chrome Cookies SQLite database.
         *
         * Table: `cookies` (host_key, name, value, path, expires_utc, is_secure, is_httponly,
         *                    creation_utc, ...)
         * Timestamps for expiry are in microseconds since 1601-01-01.
         */
        fun readCookies(cookiesDb: Path): List<ImportedCookie> {
            if (!Files.isRegularFile(cookiesDb)) {
                logger.debug("Cookies database not found: {}", cookiesDb)
                return emptyList()
            }
            return try {
                val jdbcUrl = "jdbc:sqlite:${cookiesDb.toAbsolutePath()}"
                DriverManager.getConnection(jdbcUrl).use { conn ->
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery(
                            """SELECT host_key, name, value, path, expires_utc,
                                      is_secure, is_httponly, creation_utc
                               FROM cookies"""
                        )
                        val results = mutableListOf<ImportedCookie>()
                        while (rs.next()) {
                            results.add(
                                ImportedCookie(
                                    domain = rs.getString("host_key") ?: continue,
                                    name = rs.getString("name") ?: continue,
                                    value = rs.getString("value") ?: "",
                                    path = rs.getString("path") ?: "/",
                                    expiry = rs.getLong("expires_utc"),
                                    secure = rs.getInt("is_secure") != 0,
                                    httpOnly = rs.getInt("is_httponly") != 0,
                                    creationTime = rs.getLong("creation_utc")
                                )
                            )
                        }
                        logger.info("Read {} cookies from {}", results.size, cookiesDb)
                        results
                    }
                }
            } catch (e: SQLException) {
                if (isLockedError(e)) throw DatabaseLockedException(ImportSource.CHROME, cookiesDb.fileName.toString())
                logger.error("Failed to read Chrome cookies from {} | {}", cookiesDb, e.message)
                throw ImportException("Failed to read Chrome cookies: ${e.message}", e)
            }
        }

        /** Check if the SQLException indicates a locked database. */
        private fun isLockedError(e: SQLException): Boolean {
            val message = e.message ?: return false
            return message.contains("database is locked", ignoreCase = true) ||
                    message.contains("SQLITE_BUSY", ignoreCase = true)
        }
    }
}
