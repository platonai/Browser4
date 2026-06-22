package ai.platon.browser4.importer.firefox

import ai.platon.browser4.importer.DatabaseLockedException
import ai.platon.browser4.importer.ImportException
import ai.platon.browser4.importer.model.ImportSource
import ai.platon.browser4.importer.model.ImportedCookie
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Reads cookies from Firefox's `cookies.sqlite` database.
 *
 * Schema: `moz_cookies` (id, originAttributes, name, value, host, path, expiry,
 *                          lastAccessed, creationTime, isSecure, isHttpOnly, ...)
 *
 * Timestamps are in seconds since 1970-01-01 (Unix epoch seconds).
 */
class FirefoxCookieParser {

    companion object {
        private val logger = LoggerFactory.getLogger(FirefoxCookieParser::class.java)

        /**
         * Read all cookies from a Firefox cookies.sqlite database.
         */
        fun readCookies(cookiesDb: Path): List<ImportedCookie> {
            if (!Files.isRegularFile(cookiesDb)) {
                logger.debug("Firefox cookies.sqlite not found: {}", cookiesDb)
                return emptyList()
            }
            return try {
                val jdbcUrl = "jdbc:sqlite:${cookiesDb.toAbsolutePath()}"
                DriverManager.getConnection(jdbcUrl).use { conn ->
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery(
                            """SELECT name, value, host, path, expiry, creationTime,
                                      isSecure, isHttpOnly
                               FROM moz_cookies"""
                        )
                        val results = mutableListOf<ImportedCookie>()
                        while (rs.next()) {
                            results.add(
                                ImportedCookie(
                                    name = rs.getString("name") ?: continue,
                                    value = rs.getString("value") ?: "",
                                    domain = rs.getString("host") ?: continue,
                                    path = rs.getString("path") ?: "/",
                                    expiry = rs.getLong("expiry"),
                                    secure = rs.getInt("isSecure") != 0,
                                    httpOnly = rs.getInt("isHttpOnly") != 0,
                                    creationTime = rs.getLong("creationTime")
                                )
                            )
                        }
                        logger.info("Read {} cookies from {}", results.size, cookiesDb)
                        results
                    }
                }
            } catch (e: SQLException) {
                if (isLockedError(e)) throw DatabaseLockedException(ImportSource.FIREFOX, "cookies.sqlite")
                logger.error("Failed to read Firefox cookies from {} | {}", cookiesDb, e.message)
                throw ImportException("Failed to read Firefox cookies: ${e.message}", e)
            }
        }

        private fun isLockedError(e: SQLException): Boolean {
            val message = e.message ?: return false
            return message.contains("database is locked", ignoreCase = true) ||
                    message.contains("SQLITE_BUSY", ignoreCase = true)
        }
    }
}
