package ai.platon.browser4.importer.writer

import ai.platon.browser4.importer.model.ImportSource
import ai.platon.browser4.importer.model.ImportedBookmark
import ai.platon.pulsar.browser.BrowserProfile
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ChromeProfileWriterTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Create a minimal BrowserProfile that resolves its Default/ dir to our temp dir.
     * BrowserProfile stores data at: contextDir/<browserType>/Default/
     * So we set contextDir = tempDir, and create the subdirectory structure.
     */
    private fun createTestProfile(): BrowserProfile {
        // Create the directory structure: <contextDir>/<browserType>/Default/
        val browserTypeName = BrowserType.PULSAR_CHROME.name
        val defaultDir = tempDir.resolve(browserTypeName).resolve("Default")
        Files.createDirectories(defaultDir)
        return BrowserProfile(tempDir, BrowserType.PULSAR_CHROME)
    }

    @Test
    fun testWriteBookmarksToNewFile() {
        val profile = createTestProfile()
        val writer = ChromeProfileWriter(profile)

        val bookmarks = listOf(
            ImportedBookmark("Example", "https://example.com", "Chrome", 13250000000000000L),
            ImportedBookmark("Google", "https://google.com", "Chrome", 13250000000000001L),
        )

        val count = writer.writeBookmarks(bookmarks, ImportSource.CHROME)
        assertEquals(2, count, "Should write 2 bookmarks")

        // Verify the file exists and has correct structure
        val bookmarksPath = tempDir
            .resolve(BrowserType.PULSAR_CHROME.name)
            .resolve("Default/Bookmarks")
        assertTrue(Files.isRegularFile(bookmarksPath), "Bookmarks file should exist")

        // Read and verify
        val root = pulsarObjectMapper().readTree(bookmarksPath.toFile())
        val roots = root.get("roots")
        assertNotNull(roots, "Should have roots object")

        val other = roots.get("other")
        assertNotNull(other, "Should have 'other' folder")

        val children = other.get("children")
        assertNotNull(children, "Should have children array")
        assertTrue(children.isArray, "children should be an array")
        assertEquals(1, children.size(), "Should have one imported folder")

        val importedFolder = children[0]
        assertEquals("folder", importedFolder.get("type").asText())
        assertEquals("Google Chrome bookmarks", importedFolder.get("name").asText())

        val importedChildren = importedFolder.get("children")
        assertEquals(2, importedChildren.size(), "Imported folder should contain 2 bookmarks")
        assertEquals("Example", importedChildren[0].get("name").asText())
    }

    @Test
    fun testWriteBookmarksEmptyList() {
        val profile = createTestProfile()
        val writer = ChromeProfileWriter(profile)

        val count = writer.writeBookmarks(emptyList(), ImportSource.EDGE)
        assertEquals(0, count, "Should write 0 bookmarks for empty list")

        val bookmarksPath = tempDir
            .resolve(BrowserType.PULSAR_CHROME.name)
            .resolve("Default/Bookmarks")
        assertTrue(!Files.exists(bookmarksPath), "Bookmarks file should not be created for empty list")
    }

    @Test
    fun testWriteBookmarksMergeWithExisting() {
        val profile = createTestProfile()
        val writer = ChromeProfileWriter(profile)

        // First, write Chrome bookmarks
        val chromeBookmarks = listOf(
            ImportedBookmark("Chrome Site", "https://chrome.example.com", "Chrome", 1L)
        )
        writer.writeBookmarks(chromeBookmarks, ImportSource.CHROME)

        // Then merge Firefox bookmarks
        val firefoxBookmarks = listOf(
            ImportedBookmark("Firefox Site", "https://firefox.example.com", "Firefox", 2L)
        )
        val count = writer.writeBookmarks(firefoxBookmarks, ImportSource.FIREFOX)
        assertEquals(1, count)

        // Verify both folders exist
        val bookmarksPath = tempDir
            .resolve(BrowserType.PULSAR_CHROME.name)
            .resolve("Default/Bookmarks")
        val root = pulsarObjectMapper().readTree(bookmarksPath.toFile())
        val otherChildren = root.get("roots").get("other").get("children")

        assertEquals(2, otherChildren.size(), "Should have 2 imported folders")
        val folderNames = (0 until otherChildren.size()).map { otherChildren[it].get("name").asText() }
        assertTrue(folderNames.contains("Google Chrome bookmarks"), "Should have Chrome folder")
        assertTrue(folderNames.contains("Firefox bookmarks"), "Should have Firefox folder")
    }

    @Test
    fun testWriteBookmarksWithFolderPath() {
        val profile = createTestProfile()
        val writer = ChromeProfileWriter(profile)

        val bookmarks = listOf(
            ImportedBookmark("Deep", "https://deep.example.com", "Bookmarks bar/Dev/Tools", 1L)
        )
        writer.writeBookmarks(bookmarks, ImportSource.BRAVE)

        // Folder path from source doesn't affect the writer's folder naming;
        // the writer always puts things under "<source> bookmarks".
        // The original folder info is preserved for reference but not for the target hierarchy.
        val bookmarksPath = tempDir
            .resolve(BrowserType.PULSAR_CHROME.name)
            .resolve("Default/Bookmarks")
        val root = pulsarObjectMapper().readTree(bookmarksPath.toFile())
        val importedFolder = root.get("roots").get("other").get("children")[0]
        assertEquals("Brave bookmarks", importedFolder.get("name").asText())
    }
}
