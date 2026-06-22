package ai.platon.browser4.importer.chrome

import ai.platon.browser4.importer.ImportException
import ai.platon.browser4.importer.model.ImportedBookmark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ChromeBookmarksParserTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testParseSimpleBookmarks() {
        val json = """
        {
          "checksum": "abc123",
          "roots": {
            "bookmark_bar": {
              "children": [
                {
                  "date_added": "13250000000000000",
                  "id": "1",
                  "name": "Example",
                  "type": "url",
                  "url": "https://www.example.com"
                },
                {
                  "date_added": "13250000000000001",
                  "id": "2",
                  "name": "Google",
                  "type": "url",
                  "url": "https://www.google.com"
                }
              ],
              "date_added": "0",
              "date_modified": "0",
              "name": "Bookmarks bar",
              "type": "folder"
            },
            "other": {
              "children": [],
              "date_added": "0",
              "date_modified": "0",
              "name": "Other bookmarks",
              "type": "folder"
            },
            "synced": {
              "children": [],
              "date_added": "0",
              "date_modified": "0",
              "name": "Mobile bookmarks",
              "type": "folder"
            }
          },
          "version": 1
        }
        """.trimIndent()

        val file = tempDir.resolve("Bookmarks")
        Files.writeString(file, json)

        val bookmarks = ChromeBookmarksParser.parse(file)
        assertEquals(2, bookmarks.size, "Should parse 2 bookmarks")
        assertEquals("Example", bookmarks[0].title)
        assertEquals("https://www.example.com", bookmarks[0].url)
        assertEquals("Bookmarks bar", bookmarks[0].folder)
        assertEquals(13250000000000000L, bookmarks[0].dateAdded)
    }

    @Test
    fun testParseNestedFolders() {
        val json = """
        {
          "roots": {
            "bookmark_bar": {
              "children": [
                {
                  "type": "folder",
                  "name": "Dev",
                  "children": [
                    {
                      "type": "url",
                      "name": "GitHub",
                      "url": "https://github.com"
                    },
                    {
                      "type": "folder",
                      "name": "Docs",
                      "children": [
                        {
                          "type": "url",
                          "name": "Kotlin Docs",
                          "url": "https://kotlinlang.org/docs"
                        }
                      ]
                    }
                  ]
                }
              ],
              "name": "Bookmarks bar",
              "type": "folder"
            },
            "other": { "children": [], "name": "Other bookmarks", "type": "folder" },
            "synced": { "children": [], "name": "Mobile bookmarks", "type": "folder" }
          },
          "version": 1
        }
        """.trimIndent()

        val file = tempDir.resolve("Bookmarks")
        Files.writeString(file, json)

        val bookmarks = ChromeBookmarksParser.parse(file)
        assertEquals(2, bookmarks.size, "Should parse 2 bookmarks from nested folders")

        val github = bookmarks.find { it.url == "https://github.com" }!!
        assertEquals("Bookmarks bar/Dev", github.folder)

        val kotlinDocs = bookmarks.find { it.url == "https://kotlinlang.org/docs" }!!
        assertEquals("Bookmarks bar/Dev/Docs", kotlinDocs.folder)
    }

    @Test
    fun testParseEmptyBookmarks() {
        val json = """
        {
          "roots": {
            "bookmark_bar": {
              "children": [],
              "name": "Bookmarks bar",
              "type": "folder"
            },
            "other": {
              "children": [],
              "name": "Other bookmarks",
              "type": "folder"
            },
            "synced": {
              "children": [],
              "name": "Mobile bookmarks",
              "type": "folder"
            }
          },
          "version": 1
        }
        """.trimIndent()

        val file = tempDir.resolve("Bookmarks")
        Files.writeString(file, json)

        val bookmarks = ChromeBookmarksParser.parse(file)
        assertEquals(0, bookmarks.size, "Should return empty list for empty bookmarks")
    }

    @Test
    fun testParseMissingFile() {
        val missingFile = tempDir.resolve("NonexistentBookmarks")
        val bookmarks = ChromeBookmarksParser.parse(missingFile)
        assertEquals(0, bookmarks.size, "Should return empty list for missing file")
    }

    @Test
    fun testParseCorruptJson() {
        val file = tempDir.resolve("Bookmarks")
        Files.writeString(file, "this is not valid json {{{")

        assertFailsWith<ImportException>("Should throw ImportException for corrupt JSON") {
            ChromeBookmarksParser.parse(file)
        }
    }

    @Test
    fun testParseBookmarkWithMissingUrl() {
        val json = """
        {
          "roots": {
            "bookmark_bar": {
              "children": [
                {
                  "type": "url",
                  "name": "No URL bookmark"
                },
                {
                  "type": "url",
                  "name": "Has URL",
                  "url": "https://example.com"
                }
              ],
              "name": "Bookmarks bar",
              "type": "folder"
            },
            "other": { "children": [], "name": "Other bookmarks", "type": "folder" },
            "synced": { "children": [], "name": "Mobile bookmarks", "type": "folder" }
          },
          "version": 1
        }
        """.trimIndent()

        val file = tempDir.resolve("Bookmarks")
        Files.writeString(file, json)

        val bookmarks = ChromeBookmarksParser.parse(file)
        assertEquals(1, bookmarks.size, "Should skip bookmarks with missing URL")
        assertEquals("https://example.com", bookmarks[0].url)
    }

    @Test
    fun testParseOnlyOtherBookmarks() {
        val json = """
        {
          "roots": {
            "bookmark_bar": { "children": [], "name": "Bookmarks bar", "type": "folder" },
            "other": {
              "children": [
                {
                  "type": "url",
                  "name": "Favorite",
                  "url": "https://favorite.example.com"
                }
              ],
              "name": "Other bookmarks",
              "type": "folder"
            },
            "synced": { "children": [], "name": "Mobile bookmarks", "type": "folder" }
          },
          "version": 1
        }
        """.trimIndent()

        val file = tempDir.resolve("Bookmarks")
        Files.writeString(file, json)

        val bookmarks = ChromeBookmarksParser.parse(file)
        assertEquals(1, bookmarks.size)
        assertEquals("Other bookmarks", bookmarks[0].folder)
    }
}
