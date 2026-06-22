package ai.platon.browser4.importer.firefox

import ai.platon.browser4.importer.DatabaseLockedException
import ai.platon.browser4.importer.ImportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class FirefoxPlacesParserTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Create an in-memory copy of places.sqlite with the Firefox schema for testing.
     * We use a temp file (not :memory:) because FirefoxPlacesParser expects a file path.
     */
    private fun createPlacesDb(block: (java.sql.Connection) -> Unit): Path {
        val dbPath = tempDir.resolve("places.sqlite")
        val jdbcUrl = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS moz_bookmarks (
                        id INTEGER PRIMARY KEY,
                        type INTEGER,
                        fk INTEGER,
                        parent INTEGER,
                        title TEXT,
                        dateAdded INTEGER
                    )
                """.trimIndent())
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS moz_places (
                        id INTEGER PRIMARY KEY,
                        url TEXT,
                        title TEXT,
                        rev_host TEXT,
                        visit_count INTEGER DEFAULT 0,
                        last_visit_date INTEGER,
                        hidden INTEGER DEFAULT 0
                    )
                """.trimIndent())
            }
            block(conn)
        }
        return dbPath
    }

    @Test
    fun testReadBookmarks_simple() {
        val dbPath = createPlacesDb { conn ->
            conn.createStatement().use { stmt ->
                // Root folder (type=2, parent=0)
                stmt.executeUpdate("INSERT INTO moz_bookmarks(id, type, fk, parent, title) VALUES (1, 2, NULL, 0, '')")
                // Menu folder (type=2, parent=1)
                stmt.executeUpdate("INSERT INTO moz_bookmarks(id, type, fk, parent, title) VALUES (2, 2, NULL, 1, 'Bookmarks Menu')")
                // Place
                stmt.executeUpdate("INSERT INTO moz_places(id, url, title, visit_count) VALUES (1, 'https://example.com', 'Example', 5)")
                // Bookmark (type=1, fk=1 -> moz_places, parent=2)
                stmt.executeUpdate("INSERT INTO moz_bookmarks(id, type, fk, parent, title, dateAdded) VALUES (3, 1, 1, 2, 'My Example', 1700000000000000)")
                // Another bookmark
                stmt.executeUpdate("INSERT INTO moz_places(id, url, title, visit_count) VALUES (2, 'https://mozilla.org', 'Mozilla', 10)")
                stmt.executeUpdate("INSERT INTO moz_bookmarks(id, type, fk, parent, title, dateAdded) VALUES (4, 1, 2, 2, 'Mozilla', 1700000000000001)")
            }
        }

        val bookmarks = FirefoxPlacesParser.readBookmarks(dbPath)
        assertEquals(2, bookmarks.size, "Should read 2 bookmarks")

        val example = bookmarks.find { it.url == "https://example.com" }!!
        assertEquals("My Example", example.title)
        assertEquals("Bookmarks Menu", example.folder)
        assertEquals(1700000000000000L, example.dateAdded)
    }

    @Test
    fun testReadBookmarks_emptyDatabase() {
        val dbPath = createPlacesDb { /* empty */ }

        val bookmarks = FirefoxPlacesParser.readBookmarks(dbPath)
        assertEquals(0, bookmarks.size, "Should return empty list for empty database")
    }

    @Test
    fun testReadBookmarks_missingFile() {
        val missingFile = tempDir.resolve("nonexistent.sqlite")
        val bookmarks = FirefoxPlacesParser.readBookmarks(missingFile)
        assertEquals(0, bookmarks.size, "Should return empty list for missing file")
    }

    @Test
    fun testReadHistory() {
        val dbPath = createPlacesDb { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("INSERT INTO moz_places(id, url, title, visit_count, last_visit_date) VALUES (1, 'https://visited.com', 'Visited Page', 3, 1700000000000000)")
                stmt.executeUpdate("INSERT INTO moz_places(id, url, title, visit_count, last_visit_date, hidden) VALUES (2, 'https://hidden.com', 'Hidden', 1, 1700000000000001, 1)")
            }
        }

        val history = FirefoxPlacesParser.readHistory(dbPath)
        assertEquals(1, history.size, "Should only return non-hidden entries with visit_count > 0")
        assertEquals("https://visited.com", history[0].url)
        assertEquals("Visited Page", history[0].title)
        assertEquals(3, history[0].visitCount)
    }
}
