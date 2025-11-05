package ai.platon.pulsar.common

import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.*

/**
 * Enhanced filesystem utility methods for safe file operations.
 */
object FileSystemUtils {

    /**
     * Safely delete a directory and all its contents.
     *
     * @param path The directory path to delete
     * @param maxDepth Maximum depth to traverse (safety limit)
     * @return true if deletion was successful
     */
    @Throws(IOException::class)
    fun deleteDirectoryRecursively(path: Path, maxDepth: Int = 100): Boolean {
        if (!path.exists()) {
            return true
        }

        if (!path.isDirectory()) {
            throw IllegalArgumentException("Path is not a directory: $path")
        }

        var deleted = true
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            private var depth = 0

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (++depth > maxDepth) {
                    throw IOException("Directory depth exceeds maximum: $maxDepth")
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                try {
                    Files.delete(file)
                } catch (e: IOException) {
                    deleted = false
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                depth--
                try {
                    Files.delete(dir)
                } catch (e: IOException) {
                    deleted = false
                }
                return FileVisitResult.CONTINUE
            }
        })

        return deleted
    }

    /**
     * Copy a directory and all its contents to a new location.
     *
     * @param source The source directory
     * @param target The target directory
     * @param overwrite Whether to overwrite existing files
     * @return The target path
     */
    @Throws(IOException::class)
    fun copyDirectory(source: Path, target: Path, overwrite: Boolean = false): Path {
        require(source.exists()) { "Source directory does not exist: $source" }
        require(source.isDirectory()) { "Source is not a directory: $source" }

        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val targetDir = target.resolve(source.relativize(dir))
                if (!targetDir.exists()) {
                    Files.createDirectories(targetDir)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val targetFile = target.resolve(source.relativize(file))
                if (overwrite) {
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.copy(file, targetFile)
                }
                return FileVisitResult.CONTINUE
            }
        })

        return target
    }

    /**
     * Check if a path is safe to access (not trying to escape a base directory).
     *
     * @param basePath The base directory that should contain the path
     * @param targetPath The path to validate
     * @return true if the path is within the base directory
     */
    fun isPathSafe(basePath: Path, targetPath: Path): Boolean {
        val normalizedBase = basePath.toAbsolutePath().normalize()
        val normalizedTarget = targetPath.toAbsolutePath().normalize()
        
        return normalizedTarget.startsWith(normalizedBase)
    }

    /**
     * Validate that a path is safe and resolve it relative to a base directory.
     *
     * @param basePath The base directory
     * @param relativePath The relative path to resolve
     * @return The resolved path
     * @throws IllegalArgumentException if the path would escape the base directory
     */
    @Throws(IllegalArgumentException::class)
    fun resolveSafely(basePath: Path, relativePath: String): Path {
        val resolved = basePath.resolve(relativePath).normalize()
        require(isPathSafe(basePath, resolved)) {
            "Path would escape base directory: $relativePath"
        }
        return resolved
    }

    /**
     * Get the size of a directory and all its contents.
     *
     * @param path The directory path
     * @return Total size in bytes
     */
    @Throws(IOException::class)
    fun getDirectorySize(path: Path): Long {
        if (!path.exists() || !path.isDirectory()) {
            return 0L
        }

        var size = 0L
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                size += attrs.size()
                return FileVisitResult.CONTINUE
            }
        })

        return size
    }

    /**
     * Count files in a directory matching a predicate.
     *
     * @param path The directory path
     * @param predicate The filter predicate
     * @param maxDepth Maximum depth to traverse
     * @return Number of matching files
     */
    @Throws(IOException::class)
    fun countFiles(
        path: Path,
        predicate: (Path) -> Boolean = { true },
        maxDepth: Int = Int.MAX_VALUE
    ): Long {
        if (!path.exists() || !path.isDirectory()) {
            return 0L
        }

        return Files.walk(path, maxDepth)
            .filter { it.isRegularFile() }
            .filter(predicate)
            .count()
    }

    /**
     * Find files in a directory matching a pattern.
     *
     * @param path The directory path
     * @param pattern Glob pattern to match
     * @param maxDepth Maximum depth to traverse
     * @return List of matching files
     */
    @Throws(IOException::class)
    fun findFiles(path: Path, pattern: String, maxDepth: Int = Int.MAX_VALUE): List<Path> {
        if (!path.exists() || !path.isDirectory()) {
            return emptyList()
        }

        val matcher = path.fileSystem.getPathMatcher("glob:$pattern")
        val results = mutableListOf<Path>()

        Files.walk(path, maxDepth).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { matcher.matches(it.fileName) }
                .forEach { results.add(it) }
        }

        return results
    }

    /**
     * Ensure a directory exists, creating it if necessary.
     *
     * @param path The directory path
     * @return The directory path
     */
    @Throws(IOException::class)
    fun ensureDirectory(path: Path): Path {
        if (!path.exists()) {
            Files.createDirectories(path)
        } else if (!path.isDirectory()) {
            throw IOException("Path exists but is not a directory: $path")
        }
        return path
    }

    /**
     * Create a temporary directory with a unique name in the system temp directory.
     *
     * @param prefix Prefix for the directory name
     * @return The created temporary directory path
     */
    @Throws(IOException::class)
    fun createTempDirectory(prefix: String = "pulsar-"): Path {
        return Files.createTempDirectory(prefix)
    }

    /**
     * Clean up old files in a directory based on age.
     *
     * @param path The directory path
     * @param maxAgeMillis Maximum age in milliseconds
     * @param dryRun If true, only report what would be deleted without actually deleting
     * @return List of deleted (or would-be-deleted) files
     */
    @Throws(IOException::class)
    fun cleanupOldFiles(path: Path, maxAgeMillis: Long, dryRun: Boolean = false): List<Path> {
        if (!path.exists() || !path.isDirectory()) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val deletedFiles = mutableListOf<Path>()

        Files.walk(path).use { stream ->
            stream.filter { it.isRegularFile() }
                .forEach { file ->
                    try {
                        val lastModified = Files.getLastModifiedTime(file).toMillis()
                        if (now - lastModified > maxAgeMillis) {
                            deletedFiles.add(file)
                            if (!dryRun) {
                                Files.delete(file)
                            }
                        }
                    } catch (e: IOException) {
                        // Log and continue
                    }
                }
        }

        return deletedFiles
    }

    /**
     * Atomically write content to a file using a temporary file and rename.
     *
     * @param path The target file path
     * @param content The content to write
     * @return The file path
     */
    @Throws(IOException::class)
    fun writeAtomic(path: Path, content: ByteArray): Path {
        val tempFile = Files.createTempFile(path.parent, ".tmp-", path.fileName.toString())
        try {
            Files.write(tempFile, content)
            Files.move(tempFile, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // Clean up temp file on failure
            try {
                Files.deleteIfExists(tempFile)
            } catch (cleanup: IOException) {
                // Ignore cleanup errors
            }
            throw e
        }
        return path
    }

    /**
     * Atomically write text content to a file using a temporary file and rename.
     *
     * @param path The target file path
     * @param content The text content to write
     * @return The file path
     */
    @Throws(IOException::class)
    fun writeAtomic(path: Path, content: String): Path {
        return writeAtomic(path, content.toByteArray())
    }

    /**
     * Check if a file is empty.
     *
     * @param path The file path
     * @return true if the file is empty or doesn't exist
     */
    fun isEmpty(path: Path): Boolean {
        if (!path.exists()) {
            return true
        }
        
        return try {
            Files.size(path) == 0L
        } catch (e: IOException) {
            true
        }
    }

    /**
     * Get file extension.
     *
     * @param path The file path
     * @return The file extension without the dot, or empty string if none
     */
    fun getExtension(path: Path): String {
        val fileName = path.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        
        return if (dotIndex > 0 && dotIndex < fileName.length - 1) {
            fileName.substring(dotIndex + 1)
        } else {
            ""
        }
    }

    /**
     * Change file extension.
     *
     * @param path The file path
     * @param newExtension The new extension (without dot)
     * @return New path with changed extension
     */
    fun changeExtension(path: Path, newExtension: String): Path {
        val fileName = path.fileName.toString()
        val dotIndex = fileName.lastIndexOf('.')
        
        val baseName = if (dotIndex > 0) {
            fileName.substring(0, dotIndex)
        } else {
            fileName
        }
        
        val newFileName = if (newExtension.isEmpty()) {
            baseName
        } else {
            "$baseName.$newExtension"
        }
        
        return path.resolveSibling(newFileName)
    }
}
