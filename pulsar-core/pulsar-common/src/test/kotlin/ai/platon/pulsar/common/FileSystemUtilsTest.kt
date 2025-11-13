package ai.platon.pulsar.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

class FileSystemUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testDir: Path

    @BeforeEach
    fun setUp() {
        testDir = tempDir.resolve("test")
        Files.createDirectories(testDir)
    }

    @AfterEach
    fun tearDown() {
        // Cleanup is handled by @TempDir
    }

    @Test
    fun `deleteDirectoryRecursively deletes directory and contents`() {
        val subDir = testDir.resolve("subdir")
        Files.createDirectories(subDir)
        Files.writeString(subDir.resolve("file.txt"), "content")

        assertTrue(subDir.exists())
        assertTrue(FileSystemUtils.deleteDirectoryRecursively(subDir))
        assertFalse(subDir.exists())
    }

    @Test
    fun `deleteDirectoryRecursively returns true for non-existent directory`() {
        val nonExistent = testDir.resolve("does-not-exist")
        assertTrue(FileSystemUtils.deleteDirectoryRecursively(nonExistent))
    }

    @Test
    fun `deleteDirectoryRecursively throws for non-directory path`() {
        val file = testDir.resolve("file.txt")
        Files.writeString(file, "content")

        assertThrows(IllegalArgumentException::class.java) {
            FileSystemUtils.deleteDirectoryRecursively(file)
        }
    }

    @Test
    fun `copyDirectory copies all files and subdirectories`() {
        val source = testDir.resolve("source")
        val target = testDir.resolve("target")
        
        Files.createDirectories(source)
        Files.writeString(source.resolve("file1.txt"), "content1")
        
        val subDir = source.resolve("subdir")
        Files.createDirectories(subDir)
        Files.writeString(subDir.resolve("file2.txt"), "content2")

        FileSystemUtils.copyDirectory(source, target)

        assertTrue(target.exists())
        assertTrue(target.resolve("file1.txt").exists())
        assertTrue(target.resolve("subdir").exists())
        assertTrue(target.resolve("subdir/file2.txt").exists())
        assertEquals("content1", Files.readString(target.resolve("file1.txt")))
        assertEquals("content2", Files.readString(target.resolve("subdir/file2.txt")))
    }

    @Test
    fun `copyDirectory with overwrite replaces existing files`() {
        val source = testDir.resolve("source")
        val target = testDir.resolve("target")
        
        Files.createDirectories(source)
        Files.createDirectories(target)
        
        Files.writeString(source.resolve("file.txt"), "new content")
        Files.writeString(target.resolve("file.txt"), "old content")

        FileSystemUtils.copyDirectory(source, target, overwrite = true)

        assertEquals("new content", Files.readString(target.resolve("file.txt")))
    }

    @Test
    fun `isPathSafe returns true for path within base directory`() {
        val basePath = testDir
        val safePath = testDir.resolve("subdir/file.txt")

        assertTrue(FileSystemUtils.isPathSafe(basePath, safePath))
    }

    @Test
    fun `isPathSafe returns false for path outside base directory`() {
        val basePath = testDir.resolve("restricted")
        val unsafePath = testDir.resolve("outside/file.txt")

        assertFalse(FileSystemUtils.isPathSafe(basePath, unsafePath))
    }

    @Test
    fun `isPathSafe prevents path traversal attack`() {
        val basePath = testDir.resolve("safe")
        Files.createDirectories(basePath)
        val attackPath = basePath.resolve("../../../etc/passwd")

        assertFalse(FileSystemUtils.isPathSafe(basePath, attackPath))
    }

    @Test
    fun `resolveSafely resolves safe relative paths`() {
        val basePath = testDir
        val resolved = FileSystemUtils.resolveSafely(basePath, "subdir/file.txt")

        assertTrue(resolved.startsWith(basePath))
        assertEquals("file.txt", resolved.fileName.toString())
    }

    @Test
    fun `resolveSafely throws for paths that escape base directory`() {
        val basePath = testDir.resolve("restricted")
        Files.createDirectories(basePath)

        assertThrows(IllegalArgumentException::class.java) {
            FileSystemUtils.resolveSafely(basePath, "../../outside.txt")
        }
    }

    @Test
    fun `getDirectorySize calculates total size correctly`() {
        val dir = testDir.resolve("sizetest")
        Files.createDirectories(dir)
        
        Files.writeString(dir.resolve("file1.txt"), "12345") // 5 bytes
        Files.writeString(dir.resolve("file2.txt"), "1234567890") // 10 bytes

        val size = FileSystemUtils.getDirectorySize(dir)
        assertEquals(15L, size)
    }

    @Test
    fun `getDirectorySize returns zero for non-existent directory`() {
        val nonExistent = testDir.resolve("does-not-exist")
        assertEquals(0L, FileSystemUtils.getDirectorySize(nonExistent))
    }

    @Test
    fun `countFiles counts all files in directory`() {
        val dir = testDir.resolve("counttest")
        Files.createDirectories(dir)
        
        Files.writeString(dir.resolve("file1.txt"), "content")
        Files.writeString(dir.resolve("file2.txt"), "content")
        
        val subDir = dir.resolve("subdir")
        Files.createDirectories(subDir)
        Files.writeString(subDir.resolve("file3.txt"), "content")

        val count = FileSystemUtils.countFiles(dir)
        assertEquals(3L, count)
    }

    @Test
    fun `countFiles with predicate filters files`() {
        val dir = testDir.resolve("filtertest")
        Files.createDirectories(dir)
        
        Files.writeString(dir.resolve("file1.txt"), "content")
        Files.writeString(dir.resolve("file2.kt"), "content")
        Files.writeString(dir.resolve("file3.txt"), "content")

        val count = FileSystemUtils.countFiles(dir, predicate = { FileSystemUtils.getExtension(it) == "txt" })
        assertEquals(2L, count)
    }

    @Test
    fun `findFiles finds files matching glob pattern`() {
        val dir = testDir.resolve("findtest")
        Files.createDirectories(dir)
        
        Files.writeString(dir.resolve("file1.kt"), "content")
        Files.writeString(dir.resolve("file2.kt"), "content")
        Files.writeString(dir.resolve("file3.java"), "content")

        val ktFiles = FileSystemUtils.findFiles(dir, "*.kt")
        assertEquals(2, ktFiles.size)
        assertTrue(ktFiles.all { FileSystemUtils.getExtension(it) == "kt" })
    }

    @Test
    fun `ensureDirectory creates directory if not exists`() {
        val newDir = testDir.resolve("newdir")
        assertFalse(newDir.exists())

        FileSystemUtils.ensureDirectory(newDir)
        assertTrue(newDir.exists())
        assertTrue(newDir.isDirectory())
    }

    @Test
    fun `ensureDirectory does not throw if directory already exists`() {
        val existingDir = testDir.resolve("existing")
        Files.createDirectories(existingDir)

        assertDoesNotThrow {
            FileSystemUtils.ensureDirectory(existingDir)
        }
    }

    @Test
    fun `ensureDirectory throws if path is a file`() {
        val file = testDir.resolve("file.txt")
        Files.writeString(file, "content")

        assertThrows(Exception::class.java) {
            FileSystemUtils.ensureDirectory(file)
        }
    }

    @Test
    fun `createTempDirectory creates directory with prefix`() {
        val tempDir = FileSystemUtils.createTempDirectory("test-prefix-")
        
        assertTrue(tempDir.exists())
        assertTrue(tempDir.isDirectory())
        assertTrue(tempDir.fileName.toString().startsWith("test-prefix-"))
        
        // Cleanup
        Files.delete(tempDir)
    }

    @Test
    fun `cleanupOldFiles removes files older than max age`() {
        val dir = testDir.resolve("cleanup")
        Files.createDirectories(dir)
        
        val oldFile = dir.resolve("old.txt")
        val newFile = dir.resolve("new.txt")
        
        Files.writeString(oldFile, "content")
        Files.writeString(newFile, "content")
        
        // Set old file's last modified time to 2 hours ago
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000)
        oldFile.toFile().setLastModified(twoHoursAgo)

        val deleted = FileSystemUtils.cleanupOldFiles(dir, 60 * 60 * 1000) // 1 hour
        
        assertEquals(1, deleted.size)
        assertEquals("old.txt", deleted[0].fileName.toString())
    }

    @Test
    fun `cleanupOldFiles dry run does not delete files`() {
        val dir = testDir.resolve("dryrun")
        Files.createDirectories(dir)
        
        val oldFile = dir.resolve("old.txt")
        Files.writeString(oldFile, "content")
        
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000)
        oldFile.toFile().setLastModified(twoHoursAgo)

        FileSystemUtils.cleanupOldFiles(dir, 60 * 60 * 1000, dryRun = true)
        
        assertTrue(oldFile.exists())
    }

    @Test
    fun `writeAtomic writes content atomically`() {
        val file = testDir.resolve("atomic.txt")
        val content = "test content"

        FileSystemUtils.writeAtomic(file, content)

        assertTrue(file.exists())
        assertEquals(content, Files.readString(file))
    }

    @Test
    fun `writeAtomic with bytes writes content atomically`() {
        val file = testDir.resolve("atomic-bytes.txt")
        val content = "test content".toByteArray()

        FileSystemUtils.writeAtomic(file, content)

        assertTrue(file.exists())
        assertArrayEquals(content, Files.readAllBytes(file))
    }

    @Test
    fun `isEmpty returns true for empty file`() {
        val file = testDir.resolve("empty.txt")
        Files.writeString(file, "")

        assertTrue(FileSystemUtils.isEmpty(file))
    }

    @Test
    fun `isEmpty returns false for non-empty file`() {
        val file = testDir.resolve("nonempty.txt")
        Files.writeString(file, "content")

        assertFalse(FileSystemUtils.isEmpty(file))
    }

    @Test
    fun `isEmpty returns true for non-existent file`() {
        val file = testDir.resolve("does-not-exist.txt")
        assertTrue(FileSystemUtils.isEmpty(file))
    }

    @Test
    fun `getExtension returns correct extension`() {
        val file = testDir.resolve("document.pdf")
        assertEquals("pdf", FileSystemUtils.getExtension(file))
    }

    @Test
    fun `getExtension returns empty string for no extension`() {
        val file = testDir.resolve("noextension")
        assertEquals("", FileSystemUtils.getExtension(file))
    }

    @Test
    fun `getExtension handles multiple dots`() {
        val file = testDir.resolve("archive.tar.gz")
        assertEquals("gz", FileSystemUtils.getExtension(file))
    }

    @Test
    fun `changeExtension changes file extension`() {
        val file = testDir.resolve("document.txt")
        val newPath = FileSystemUtils.changeExtension(file, "pdf")
        
        assertEquals("document.pdf", newPath.fileName.toString())
    }

    @Test
    fun `changeExtension removes extension when new extension is empty`() {
        val file = testDir.resolve("document.txt")
        val newPath = FileSystemUtils.changeExtension(file, "")
        
        assertEquals("document", newPath.fileName.toString())
    }

    @Test
    fun `changeExtension adds extension to file without extension`() {
        val file = testDir.resolve("document")
        val newPath = FileSystemUtils.changeExtension(file, "txt")
        
        assertEquals("document.txt", newPath.fileName.toString())
    }
}
