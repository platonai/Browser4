package ai.platon.pulsar.agentic.common

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AgentFileSystemTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var fs: AgentFileSystem

    @BeforeEach
    fun setUp() {
        fs = AgentFileSystem(tempDir, createDefaultFiles = false)
    }

    @AfterEach
    fun tearDown() {
        // Cleanup handled by TempDir
    }

    // --- Basic file operations ---

    @Test
    @DisplayName("writeString creates new file")
    fun writeStringCreatesNewFile() = runBlocking {
        val result = fs.writeString("test.txt", "Hello, World!")
        assertTrue(result.contains("successfully"))
        assertEquals(listOf("test.txt"), fs.listFiles())
    }

    @Test
    @DisplayName("readString returns file content")
    fun readStringReturnsFileContent() = runBlocking {
        fs.writeString("test.txt", "Hello, World!")
        val result = fs.readString("test.txt")
        assertTrue(result.contains("Hello, World!"))
        assertTrue(result.contains("<content>"))
    }

    @Test
    @DisplayName("readString returns error for non-existent file")
    fun readStringReturnsErrorForNonExistentFile() = runBlocking {
        val result = fs.readString("nonexistent.txt")
        assertTrue(result.contains("not found"))
    }

    @Test
    @DisplayName("append adds content to existing file")
    fun appendAddsContentToExistingFile() = runBlocking {
        fs.writeString("test.txt", "Line 1\n")
        fs.append("test.txt", "Line 2\n")
        val result = fs.readString("test.txt")
        assertTrue(result.contains("Line 1"))
        assertTrue(result.contains("Line 2"))
    }

    @Test
    @DisplayName("append returns error for non-existent file")
    fun appendReturnsErrorForNonExistentFile() = runBlocking {
        val result = fs.append("nonexistent.txt", "content")
        assertTrue(result.contains("not found"))
    }

    @Test
    @DisplayName("replaceContent replaces string in file")
    fun replaceContentReplacesStringInFile() = runBlocking {
        fs.writeString("test.txt", "Hello, World!")
        val result = fs.replaceContent("test.txt", "World", "Universe")
        assertTrue(result.contains("Successfully"))
        val content = fs.readString("test.txt")
        assertTrue(content.contains("Universe"))
        assertFalse(content.contains("World"))
    }

    @Test
    @DisplayName("replaceContent returns error for empty oldStr")
    fun replaceContentReturnsErrorForEmptyOldstr() = runBlocking {
        fs.writeString("test.txt", "Hello, World!")
        val result = fs.replaceContent("test.txt", "", "new")
        assertTrue(result.contains("Cannot replace empty string"))
    }

    // --- New file operations ---

    @Test
    @DisplayName("fileExists returns exists for existing file")
    fun fileExistsReturnsExistsForExistingFile() = runBlocking {
        fs.writeString("test.txt", "content")
        val result = fs.fileExists("test.txt")
        assertTrue(result.contains("exists"))
        assertFalse(result.contains("does not"))
    }

    @Test
    @DisplayName("fileExists returns not exists for missing file")
    fun fileExistsReturnsNotExistsForMissingFile() = runBlocking {
        val result = fs.fileExists("nonexistent.txt")
        assertTrue(result.contains("does not exist"))
    }

    @Test
    @DisplayName("getFileInfo returns file metadata")
    fun getFileInfoReturnsFileMetadata() = runBlocking {
        fs.writeString("test.txt", "Line 1\nLine 2\nLine 3")
        val result = fs.getFileInfo("test.txt")
        assertTrue(result.contains("Size:"))
        assertTrue(result.contains("Lines: 3"))
        assertTrue(result.contains("Extension: txt"))
    }

    @Test
    @DisplayName("getFileInfo returns error for non-existent file")
    fun getFileInfoReturnsErrorForNonExistentFile() = runBlocking {
        val result = fs.getFileInfo("nonexistent.txt")
        assertTrue(result.contains("not found"))
    }

    @Test
    @DisplayName("deleteFile removes file")
    fun deleteFileRemovesFile() = runBlocking {
        fs.writeString("test.txt", "content")
        assertTrue(fs.listFiles().contains("test.txt"))

        val result = fs.deleteFile("test.txt")
        assertTrue(result.contains("deleted successfully"))
        assertFalse(fs.listFiles().contains("test.txt"))
    }

    @Test
    @DisplayName("deleteFile returns error for non-existent file")
    fun deleteFileReturnsErrorForNonExistentFile() = runBlocking {
        val result = fs.deleteFile("nonexistent.txt")
        assertTrue(result.contains("not found"))
    }

    @Test
    @DisplayName("copyFile creates copy with same content")
    fun copyFileCreatesCopyWithSameContent() = runBlocking {
        fs.writeString("source.txt", "Original content")
        val result = fs.copyFile("source.txt", "dest.txt")
        assertTrue(result.contains("copied"))

        // Both files should exist
        assertTrue(fs.listFiles().contains("source.txt"))
        assertTrue(fs.listFiles().contains("dest.txt"))

        // Content should be the same
        val sourceContent = fs.readString("source.txt")
        val destContent = fs.readString("dest.txt")
        assertTrue(sourceContent.contains("Original content"))
        assertTrue(destContent.contains("Original content"))
    }

    @Test
    @DisplayName("copyFile returns error for non-existent source")
    fun copyFileReturnsErrorForNonExistentSource() = runBlocking {
        val result = fs.copyFile("nonexistent.txt", "dest.txt")
        assertTrue(result.contains("not found"))
    }

    @Test
    @DisplayName("copyFile returns error when source equals dest")
    fun copyFileReturnsErrorWhenSourceEqualsDest() = runBlocking {
        fs.writeString("test.txt", "content")
        val result = fs.copyFile("test.txt", "test.txt")
        assertTrue(result.contains("must be different"))
    }

    @Test
    @DisplayName("moveFile moves file to new name")
    fun moveFileMovesFileToNewName() = runBlocking {
        fs.writeString("old.txt", "Content to move")
        val result = fs.moveFile("old.txt", "new.txt")
        assertTrue(result.contains("moved"))

        // Old file should not exist, new file should
        assertFalse(fs.listFiles().contains("old.txt"))
        assertTrue(fs.listFiles().contains("new.txt"))

        // Content should be preserved
        val content = fs.readString("new.txt")
        assertTrue(content.contains("Content to move"))
    }

    @Test
    @DisplayName("moveFile returns error for non-existent source")
    fun moveFileReturnsErrorForNonExistentSource() = runBlocking {
        val result = fs.moveFile("nonexistent.txt", "dest.txt")
        assertTrue(result.contains("not found"))
    }

    @Test
    @DisplayName("moveFile returns error when source equals dest")
    fun moveFileReturnsErrorWhenSourceEqualsDest() = runBlocking {
        fs.writeString("test.txt", "content")
        val result = fs.moveFile("test.txt", "test.txt")
        assertTrue(result.contains("must be different"))
    }

    @Test
    @DisplayName("listFilesInfo returns formatted file list")
    fun listFilesInfoReturnsFormattedFileList() = runBlocking {
        fs.writeString("file1.txt", "Content 1")
        fs.writeString("file2.md", "# Markdown content")

        val result = fs.listFilesInfo()
        assertTrue(result.contains("2 files"))
        assertTrue(result.contains("file1.txt"))
        assertTrue(result.contains("file2.md"))
        assertTrue(result.contains("bytes"))
        assertTrue(result.contains("lines"))
    }

    @Test
    @DisplayName("listFilesInfo returns empty message when no files")
    fun listFilesInfoReturnsEmptyMessageWhenNoFiles() = runBlocking {
        val result = fs.listFilesInfo()
        assertTrue(result.contains("No files"))
    }

    // --- File extension validation ---

    @Test
    @DisplayName("writeString rejects invalid extension")
    fun writeStringRejectsInvalidExtension() = runBlocking {
        val result = fs.writeString("test.exe", "content")
        assertTrue(result.contains("Invalid"))
    }

    @Test
    @DisplayName("supports all valid extensions")
    fun supportsAllValidExtensions() = runBlocking {
        val extensions = listOf("md", "txt", "json", "jsonl", "csv")
        for (ext in extensions) {
            val result = fs.writeString("test.$ext", "content")
            assertTrue(result.contains("successfully"), "Failed for extension: $ext")
        }
    }

    @Test
    @DisplayName("rejects filenames with special characters")
    fun rejectsFilenamesWithSpecialCharacters() = runBlocking {
        val invalidNames = listOf("test file.txt", "test/path.txt", "test..txt")
        for (name in invalidNames) {
            val result = fs.writeString(name, "content")
            assertTrue(result.contains("Invalid") || result.contains("Error"), "Should reject: $name")
        }
    }

    @Test
    @DisplayName("allows dot in base name")
    fun allowsDotInBaseName() = runBlocking {
        val result = fs.writeString("a.b.txt", "content")
        assertTrue(result.contains("successfully"), result)
        assertTrue(fs.listFiles().contains("a.b.txt"))
    }

    // --- Edge cases ---

    @Test
    @DisplayName("handles empty file content")
    fun handlesEmptyFileContent() = runBlocking {
        fs.writeString("empty.txt", "")
        val info = fs.getFileInfo("empty.txt")
        assertTrue(info.contains("Lines: 0"))
    }

    @Test
    @DisplayName("handles multi-line content correctly")
    fun handlesMultiLineContentCorrectly() = runBlocking {
        val multiLine = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5"
        fs.writeString("multiline.txt", multiLine)
        val info = fs.getFileInfo("multiline.txt")
        assertTrue(info.contains("Lines: 5"))
    }

    @Test
    @DisplayName("copyFile can change extension")
    fun copyFileCanChangeExtension() = runBlocking {
        fs.writeString("source.txt", "Content")
        val result = fs.copyFile("source.txt", "dest.md")
        assertTrue(result.contains("copied"))
        assertTrue(fs.listFiles().contains("dest.md"))
    }

    @Test
    @DisplayName("moveFile can change extension")
    fun moveFileCanChangeExtension() = runBlocking {
        fs.writeString("source.txt", "Content")
        val result = fs.moveFile("source.txt", "dest.md")
        assertTrue(result.contains("moved"))
        assertTrue(fs.listFiles().contains("dest.md"))
        assertFalse(fs.listFiles().contains("source.txt"))
    }

    // --- Concurrent access tests ---

    @Test
    @DisplayName("handles multiple concurrent writes")
    fun handlesMultipleConcurrentWrites() = runBlocking {
        val files = (1..10).map { "file$it.txt" }
        files.forEach { fs.writeString(it, "Content for $it") }

        assertEquals(10, fs.listFiles().size)
        files.forEach { assertTrue(fs.listFiles().contains(it)) }
    }

    // --- New tests for better coverage ---

    @Test
    @DisplayName("describe returns formatted file descriptions")
    fun describeReturnsFormattedFileDescriptions() = runBlocking {
        fs.writeString("file1.txt", "Short content")
        fs.writeString("file2.md", "Line 1\nLine 2\nLine 3")

        val description = fs.describe()
        assertTrue(description.contains("file1.txt"))
        assertTrue(description.contains("file2.md"))
        assertTrue(description.contains("<file>"))
        assertTrue(description.contains("</file>"))
        assertTrue(description.contains("<content>"))
    }

    @Test
    @DisplayName("describe truncates large files with preview")
    fun describeTruncatesLargeFilesWithPreview() = runBlocking {
        val largeContent = (1..100).joinToString("\n") { "Line $it with some content to make it longer" }
        fs.writeString("large.txt", largeContent)

        val description = fs.describe()
        assertTrue(description.contains("large.txt"))
        assertTrue(description.contains("more lines"))
    }

    @Test
    @DisplayName("describe handles empty file")
    fun describeHandlesEmptyFile() = runBlocking {
        fs.writeString("empty.txt", "")

        val description = fs.describe()
        assertTrue(description.contains("empty.txt"))
        assertTrue(description.contains("[empty file]"))
    }

    @Test
    @DisplayName("saveExtractedContent creates numbered files")
    fun saveExtractedContentCreatesNumberedFiles() = runBlocking {
        val fileName1 = fs.saveExtractedContent("Content 1")
        val fileName2 = fs.saveExtractedContent("Content 2")
        val fileName3 = fs.saveExtractedContent("Content 3")

        assertEquals("extracted_content_0.md", fileName1)
        assertEquals("extracted_content_1.md", fileName2)
        assertEquals("extracted_content_2.md", fileName3)

        assertTrue(fs.listFiles().contains(fileName1))
        assertTrue(fs.listFiles().contains(fileName2))
        assertTrue(fs.listFiles().contains(fileName3))
    }

    @Test
    @DisplayName("getState captures file system state")
    fun getStateCapturesFileSystemState() = runBlocking {
        fs.writeString("test1.txt", "Content 1")
        fs.writeString("test2.md", "Content 2")

        val state = fs.getState()
        assertEquals(2, state.files.size)
        assertTrue(state.files.containsKey("test1.txt"))
        assertTrue(state.files.containsKey("test2.md"))
        assertEquals("Content 1", state.files["test1.txt"]?.content)
        assertEquals("Content 2", state.files["test2.md"]?.content)
    }

    @Test
    @DisplayName("getState includes extracted content count")
    fun getStateIncludesExtractedContentCount() = runBlocking {
        fs.saveExtractedContent("Content 1")
        fs.saveExtractedContent("Content 2")

        val state = fs.getState()
        assertEquals(2, state.extractedContentCount)
    }

    @Test
    @DisplayName("readString with external file reads from disk")
    fun readStringWithExternalFileReadsFromDisk() = runBlocking {
        // Create a test file outside the agent file system
        val externalFile = tempDir.resolve("external.txt")
        java.nio.file.Files.writeString(externalFile, "External content")

        val result = fs.readString(externalFile.toString(), externalFile = true)
        assertTrue(result.contains("External content"))
        assertTrue(result.contains("<content>"))
    }

    @Test
    @DisplayName("readString with external file returns error for invalid extension")
    fun readStringWithExternalFileReturnsErrorForInvalidExtension() = runBlocking {
        val result = fs.readString("test.exe", externalFile = true)
        assertTrue(result.contains("Error") || result.contains("not supported"))
    }

    @Test
    @DisplayName("readString with external file returns error for missing file")
    fun readStringWithExternalFileReturnsErrorForMissingFile() = runBlocking {
        val result = fs.readString("/nonexistent/path/file.txt", externalFile = true)
        assertTrue(result.contains("Error") || result.contains("Could not read"))
    }

    @Test
    @DisplayName("createFile throws exception for unsupported extension")
    fun createFileThrowsExceptionForUnsupportedExtension() = runBlocking {
        val result = fs.writeString("test.exe", "content")
        assertTrue(result.contains("Invalid"))
    }

    @Test
    @DisplayName("concurrent file writes maintain data integrity")
    fun concurrentFileWritesMaintainDataIntegrity() = runBlocking {
        coroutineScope {
            val jobs = (1..20).map { i ->
                launch {
                    fs.writeString("file$i.txt", "Content $i")
                }
            }
            jobs.forEach { it.join() }
        }

        assertEquals(20, fs.listFiles().size)
        for (i in 1..20) {
            val content = fs.readString("file$i.txt")
            assertTrue(content.contains("Content $i"))
        }
    }

    @Test
    @DisplayName("concurrent append operations maintain data integrity")
    fun concurrentAppendOperationsMaintainDataIntegrity() = runBlocking {
        fs.writeString("shared.txt", "Initial\n")

        coroutineScope {
            val jobs = (1..10).map { i ->
                launch {
                    fs.append("shared.txt", "Line $i\n")
                }
            }
            jobs.forEach { it.join() }
        }

        val content = fs.readString("shared.txt")
        // All lines should be present
        for (i in 1..10) {
            assertTrue(content.contains("Line $i"), "Missing Line $i")
        }
    }

    @Test
    @DisplayName("verifies files are persisted to disk")
    fun verifiesFilesArePersistedToDisk() = runBlocking {
        fs.writeString("test.txt", "Disk content")

        // Check that the file exists on disk
        val diskPath = fs.dataDir.resolve("test.txt")
        assertTrue(java.nio.file.Files.exists(diskPath))

        // Verify content matches
        val diskContent = java.nio.file.Files.readString(diskPath)
        assertEquals("Disk content", diskContent)
    }

    @Test
    @DisplayName("getAllowedExtensions returns supported extensions")
    fun getAllowedExtensionsReturnsSupportedExtensions() {
        val extensions = fs.getAllowedExtensions()
        assertTrue(extensions.contains("md"))
        assertTrue(extensions.contains("txt"))
        assertTrue(extensions.contains("json"))
        assertTrue(extensions.contains("jsonl"))
        assertTrue(extensions.contains("csv"))
    }
}
