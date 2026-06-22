package ai.platon.browser4.importer

import ai.platon.browser4.importer.model.ImportSource
import ai.platon.browser4.importer.model.ImportedBookmark
import ai.platon.browser4.importer.writer.ChromeProfileWriter
import ai.platon.pulsar.browser.BrowserProfile
import ai.platon.pulsar.common.browser.BrowserType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BrowserDataImporterTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createTestProfile(): BrowserProfile {
        val browserTypeName = BrowserType.PULSAR_CHROME.name
        val defaultDir = tempDir.resolve(browserTypeName).resolve("Default")
        Files.createDirectories(defaultDir)
        return BrowserProfile(tempDir, BrowserType.PULSAR_CHROME)
    }

    private fun createFakeChromeProfile(dataDir: Path) {
        Files.createDirectories(dataDir)

        // Create a Bookmarks file with one bookmark
        val json = """
        {
          "roots": {
            "bookmark_bar": {
              "children": [
                {
                  "date_added": "13250000000000000",
                  "id": "1",
                  "name": "Test Page",
                  "type": "url",
                  "url": "https://test.example.com"
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
        Files.writeString(dataDir.resolve("Bookmarks"), json)
    }

    @Test
    fun testImportWithNoAvailableBrowsers() {
        val profile = createTestProfile()
        val importer = BrowserDataImporter(profile, ImportOptions(sourceBrowsers = emptySet()))

        val summary = importer.importAll()
        assertEquals(0, summary.reports.size, "Should have no reports when no sources are enabled")
    }

    @Test
    fun testImportFromFakeChromeProfile() {
        val profile = createTestProfile()
        val importer = BrowserDataImporter(profile)

        // Create a fake Chrome Default/ profile with a Bookmarks file
        val fakeProfileDir = tempDir.resolve("FakeChrome/Default")
        createFakeChromeProfile(fakeProfileDir)

        val report = importer.importFrom(ImportSource.CHROME, fakeProfileDir)
        assertEquals(ImportSource.CHROME, report.source)
        assertEquals(1, report.bookmarksImported, "Should import 1 bookmark")
        assertEquals(0, report.errors.size, "Should have no errors")
    }

    @Test
    fun testImportFromNonexistentProfile() {
        val profile = createTestProfile()
        val importer = BrowserDataImporter(profile)

        val nonexistentDir = tempDir.resolve("DoesNotExist")
        val report = importer.importFrom(ImportSource.EDGE, nonexistentDir)

        assertEquals(ImportSource.EDGE, report.source)
        assertEquals(0, report.bookmarksImported, "Should import 0 bookmarks for nonexistent profile")
    }

    @Test
    fun testImportFromFirefoxProfile() {
        val profile = createTestProfile()
        val importer = BrowserDataImporter(profile)

        // Firefox without a places.sqlite should gracefully return 0
        val fakeFirefoxDir = tempDir.resolve("FirefoxProfile")
        Files.createDirectories(fakeFirefoxDir)

        val report = importer.importFrom(ImportSource.FIREFOX, fakeFirefoxDir)
        assertEquals(ImportSource.FIREFOX, report.source)
        assertEquals(0, report.bookmarksImported, "Should import 0 when places.sqlite is missing")
    }
}
