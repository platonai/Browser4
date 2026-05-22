package ai.platon.pulsar.common

import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkExtractorsTests {

    @TempDir
    lateinit var tempDir: Path
    lateinit var tempFile: Path

    @BeforeEach
    fun setUp() {
        tempFile = Files.createTempFile(tempDir, "testFile", ".txt")
    }

    @AfterEach
    fun tearDown() {
        if (Files.exists(tempDir)) {
            // delete the temporary directory and its contents
            FileUtils.deleteDirectory(tempDir.toFile())
        }
    }

    @Test
    @DisplayName("fromText with URLs should extract URLs")
    fun fromTextWithUrlsShouldExtractUrls() {
        val text = """
            Visit http://localhost:18080/ec/dp/B0E000001
After page load: click #title, then scroll to the middle.
Summarize the product.
Extract: product name, price, ratings.
Find all links containing /dp/.
        """.trimIndent()

        val urls = LinkExtractors.fromText(text)
        assertEquals(setOf("http://localhost:18080/ec/dp/B0E000001"), urls)
    }

    @Test
    @DisplayName("fromFile with URLs should extract URLs")
    fun fromFileWithUrlsShouldExtractUrls() {
        Files.write(tempFile, "http://example.com\nhttps://another-eexxaammppllee.com".toByteArray())
        val urls = LinkExtractors.fromFile(tempFile)
        assertEquals(setOf("http://example.com", "https://another-eexxaammppllee.com"), urls)
    }

    @Test
    @DisplayName("fromFile non-existent file should return empty set")
    fun fromFileNonExistentFileShouldReturnEmptySet() {
        val nonExistentFile = Paths.get("non_existent_file.txt")
        val urls = LinkExtractors.fromFile(nonExistentFile)
        assertTrue(urls.isEmpty())
    }

    @Test
    @DisplayName("fromFile without URLs should return empty set")
    fun fromFileWithoutUrlsShouldReturnEmptySet() {
        Files.write(tempFile, "No URLs here".toByteArray())
        val urls = LinkExtractors.fromFile(tempFile)
        assertTrue(urls.isEmpty())
    }

    @Test
    @DisplayName("fromFile with URLs and filter should extract filtered URLs")
    fun fromFileWithUrlsAndFilterShouldExtractFilteredUrls() {
        Files.write(tempFile, "http://example.com\nhttps://another-eexxaammppllee.com".toByteArray())
        val filter: (String) -> Boolean = { it.contains("example.com") }
        val urls = LinkExtractors.fromFile(tempFile, filter)
        assertEquals(setOf("http://example.com"), urls)
    }

    @Test
    @DisplayName("fromDirectory with files containing URLs should extract URLs")
    fun fromDirectoryWithFilesContainingUrlsShouldExtractUrls() {
        val file1 = Files.createTempFile(tempDir, "file1", ".txt")
        val file2 = Files.createTempFile(tempDir, "file2", ".txt")
        Files.write(file1, "http://example.com\n".toByteArray())
        Files.write(file2, "https://another-eexxaammppllee.com\n".toByteArray())

        val urls = LinkExtractors.fromDirectory(tempDir)
        assertEquals(setOf("http://example.com", "https://another-eexxaammppllee.com"), urls)
    }

    @Test
    @DisplayName("fromDirectory non-existent directory should return empty set")
    fun fromDirectoryNonExistentDirectoryShouldReturnEmptySet() {
        val nonExistentDir = Paths.get("non_existent_dir")
        val urls = LinkExtractors.fromDirectory(nonExistentDir)
        assertTrue(urls.isEmpty())
    }

    @Test
    @DisplayName("fromDirectory without files should return empty set")
    fun fromDirectoryWithoutFilesShouldReturnEmptySet() {
        val urls = LinkExtractors.fromDirectory(tempDir)
        assertTrue(urls.isEmpty())
    }

    @Test
    @DisplayName("fromDirectory with files without URLs should return empty set")
    fun fromDirectoryWithFilesWithoutUrlsShouldReturnEmptySet() {
        val file1 = Files.createTempFile(tempDir, "file1", ".txt")
        val file2 = Files.createTempFile(tempDir, "file2", ".txt")
        Files.write(file1, "No URLs here\n".toByteArray())
        Files.write(file2, "No URLs here\n".toByteArray())

        val urls = LinkExtractors.fromDirectory(tempDir)
        assertTrue(urls.isEmpty())
    }

    @Test
    @DisplayName("fromDirectory with files containing URLs and filter should extract filtered URLs")
    fun fromDirectoryWithFilesContainingUrlsAndFilterShouldExtractFilteredUrls() {
        val file1 = Files.createTempFile(tempDir, "file1", ".txt")
        val file2 = Files.createTempFile(tempDir, "file2", ".txt")
        Files.write(file1, "http://a.com\n".toByteArray())
        Files.write(file2, "https://b.com\n".toByteArray())

        val filter: (String) -> Boolean = { it.contains("a.com") }
        val urls = LinkExtractors.fromDirectory(tempDir, filter)
        assertEquals(setOf("http://a.com"), urls)
    }
}
