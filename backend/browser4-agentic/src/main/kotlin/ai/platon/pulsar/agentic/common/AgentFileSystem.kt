package ai.platon.pulsar.agentic.common

import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

private const val INVALID_FILENAME_ERROR_MESSAGE =
    "Error: Invalid fileName format. Must be alphanumeric with supported extension."
private const val DEFAULT_FILE_SYSTEM_PATH = "fs"

/** Custom exception for file system operations that should be shown to LLM */
class FileSystemError(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Base class for all file types in the agent file system.
 *
 * This class provides thread-safe content management using atomic operations.
 * All file content modifications are synchronized to prevent lost updates in
 * concurrent scenarios.
 *
 * @property name The base name of the file (without extension)
 * @property extension The file extension (e.g., "txt", "md")
 */
sealed class BaseFile(
    open val name: String,
    initialContent: String = ""
) {
    /** File extension (e.g. "txt", "md") */
    abstract val extension: String

    /** Thread-safe content storage using atomic reference */
    private val contentRef = AtomicReference(initialContent)

    /** Thread-safe access to file content */
    var content: String
        get() = contentRef.get()
        set(value) = contentRef.set(value)

    val fullName: String get() = "$name.$extension"

    fun writeFileContent(newContent: String) {
        updateContent(newContent)
    }

    fun appendFileContent(append: String) {
        contentRef.updateAndGet { current -> current + append }
    }

    protected fun updateContent(newContent: String) {
        contentRef.set(newContent)
    }

    // Align method names with java.nio.file.Files for a more idiomatic Kotlin/Java feel
    @Throws(IOException::class)
    open fun writeString(baseDir: Path): Path {
        val filePath = baseDir.resolve(fullName)
        try {
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, content, StandardCharsets.UTF_8)

            return filePath
        } catch (e: Exception) {
            throw FileSystemError("Error: Could not write to file '$fullName'. ${e.message}", e)
        }
    }

    open suspend fun writeStringAsync(dataDir: Path) = withContext(Dispatchers.IO) { writeString(dataDir) }

    suspend fun writeString(newContent: String, dataDir: Path): Path {
        writeFileContent(newContent)
        return writeStringAsync(dataDir)
    }

    suspend fun appendString(append: String, dataDir: Path): Path {
        appendFileContent(append)
        return writeStringAsync(dataDir)
    }

    open fun content(): String = content

    val size: Int get() = content.length

    /**
     * Calculates the number of lines in the file content.
     * Returns 0 for empty content.
     */
    val lineCount: Int
        get() {
            val c = content
            if (c.isEmpty()) return 0
            // Count newlines and add 1 if content doesn't end with newline
            return c.count { it == '\n' } + if (!c.endsWith('\n')) 1 else 0
        }
}

class MarkdownFile(override val name: String, initialContent: String = "") : BaseFile(name, initialContent) {
    override val extension: String get() = "md"
}

class TxtFile(override val name: String, initialContent: String = "") : BaseFile(name, initialContent) {
    override val extension: String get() = "txt"
}

class JsonFile(override val name: String, initialContent: String = "") : BaseFile(name, initialContent) {
    override val extension: String get() = "json"
}

class CsvFile(override val name: String, initialContent: String = "") : BaseFile(name, initialContent) {
    override val extension: String get() = "csv"
}

class JsonlFile(override val name: String, initialContent: String = "") : BaseFile(name, initialContent) {
    override val extension: String get() = "jsonl"
}

/**
 * Serializable entry representing a file's state.
 *
 * @property type The type of the file (class name)
 * @property name The base name of the file
 * @property content The file's content
 */
data class FileStateEntry(val type: String, val name: String, val content: String)

/**
 * Serializable state of the entire file system.
 *
 * @property files Map of full file names to their state entries
 * @property baseDir The base directory path as a string
 * @property extractedContentCount Counter for extracted content files
 */
data class FileSystemState(
    val files: Map<String, FileStateEntry> = emptyMap(), // full fileName -> file data
    val baseDir: String,
    val extractedContentCount: Int = 0
)

/**
 * Enhanced file system with in-memory storage and multiple file type support.
 *
 * This class provides a thread-safe, coroutine-friendly file system for AI agents.
 * Files are stored both in memory (for fast access) and on disk (for persistence).
 *
 * **Thread Safety:** All operations are thread-safe. The in-memory file map uses
 * ConcurrentHashMap, and file content uses atomic references to prevent lost updates.
 *
 * **Lifecycle:** By default, the data directory is cleaned on initialization. Files
 * are automatically persisted to disk on write operations.
 *
 * **Supported Extensions:** md, txt, json, jsonl, csv
 *
 * **Filename Format:** Alphanumeric characters, underscores, hyphens, and dots in
 * the base name. No path separators allowed (flat file structure only).
 *
 * @property baseDir The base directory for the file system (default: "target")
 * @property createDefaultFiles Whether to create default files on init
 */
class AgentFileSystem(
    private val baseDir: Path = Path.of("target"),
    createDefaultFiles: Boolean = true
) {
    companion object {
        /** Maximum characters to display in file preview (per half) */
        const val DISPLAY_CHARS = 400
    }

    private val logger = getLogger(this)

    val dataDir: Path = baseDir.resolve(DEFAULT_FILE_SYSTEM_PATH)

    private val fileFactories: Map<String, (String, String) -> BaseFile> = mapOf(
        "md" to { name, c -> MarkdownFile(name, c) },
        "txt" to { name, c -> TxtFile(name, c) },
        "json" to { name, c -> JsonFile(name, c) },
        "jsonl" to { name, c -> JsonlFile(name, c) },
        "csv" to { name, c -> CsvFile(name, c) },
    )

    private val files: MutableMap<String, BaseFile> = ConcurrentHashMap()
    private val extractedContentCount: AtomicInteger = AtomicInteger(0)
    private val allowedExtensionsPattern: Pattern = run {
        val exts = fileFactories.keys.joinToString("|")
        // Allow dots in basename (e.g. "a.b.txt"), but disallow path separators and empty segments.
        // Also disallow leading/trailing dot.
        Pattern.compile("^[A-Za-z0-9_\\-]+(?:\\.[A-Za-z0-9_\\-]+)*\\.($exts)$")
    }

    init {
        // setup directories
        if (!baseDir.exists()) baseDir.createDirectories()
        if (dataDir.exists()) {
            // clean
            cleanDirectory(dataDir)
        }
        if (!dataDir.exists()) {
            dataDir.createDirectories()
        }
    }

    /**
     * Gets a list of allowed file extensions.
     *
     * @return List of supported file extensions
     */
    fun getAllowedExtensions(): List<String> = fileFactories.keys.toList()

    private fun createFile(extension: String, name: String, content: String = ""): BaseFile {
        val factory = fileFactories[extension.lowercase()]
            ?: throw IllegalArgumentException("Error: Invalid file extension '$extension' for file '$name.$extension'.")
        return factory(name, content)
    }

    private fun isValidFilename(fileName: String): Boolean = allowedExtensionsPattern.matcher(fileName).matches()

    private fun parseFilename(fileName: String): Pair<String, String> {
        val idx = fileName.lastIndexOf('.')
        require(idx > 0 && idx < fileName.length - 1) { "Invalid fileName: $fileName" }
        val name = fileName.take(idx)
        val ext = fileName.substring(idx + 1).lowercase()
        return name to ext
    }

    /**
     * Gets a file by its full name (including extension).
     *
     * @param fullFileName The full file name (e.g., "data.json")
     * @return The file object, or null if not found or invalid filename
     */
    fun getFile(fullFileName: String): BaseFile? {
        if (!isValidFilename(fullFileName)) return null
        return files[fullFileName]
    }

    /**
     * Lists all file names in the agent file system.
     *
     * @return Sorted list of file names
     */
    fun listFiles(): List<String> = files.values.map { it.fullName }.sorted()

    /**
     * Lists all file paths on the operating system.
     *
     * @return Sorted list of file paths
     */
    fun listOSFiles(): List<Path> = files.values.map { dataDir.resolve(it.fullName) }.sortedBy { it.toString() }

    /**
     * Displays the content of a file.
     *
     * @param fullFileName The full file name (e.g., "data.json")
     * @return The file content, or null if not found or invalid filename
     */
    fun displayFile(fullFileName: String): String? {
        if (!isValidFilename(fullFileName)) return null
        val file = getFile(fullFileName) ?: return null
        return file.content()
    }

    /**
     * Reads the content of a file from the agent file system or from an external path.
     *
     * @param fullFileName The full file name or external path
     * @param externalFile If true, reads from the file system at the given path; if false, reads from agent file system
     * @return A message containing the file content or an error message
     */
    suspend fun readString(fullFileName: String, externalFile: Boolean = false): String {
        if (externalFile) {
            return try {
                val ext = runCatching { parseFilename(fullFileName).second }.getOrElse {
                    return "Error: Invalid fileName format $fullFileName. Must be alphanumeric with a supported extension."
                }
                when (ext) {
                    "md", "txt", "json", "jsonl", "csv" -> {
                        val p = Path.of(fullFileName)
                        val content = withContext(Dispatchers.IO) {
                            Files.newBufferedReader(p, StandardCharsets.UTF_8).use { it.readText() }
                        }
                        "Read from file $fullFileName.\n<content>\n$content\n</content>"
                    }

                    else -> "Error: Cannot read file $fullFileName as $ext extension is not supported."
                }
            } catch (e: IOException) {
                logger.warn("Could not read external file '{}': {}", fullFileName, e.message)
                "Error: Could not read file '$fullFileName'. ${e.message ?: ""}".trim()
            } catch (e: SecurityException) {
                logger.warn("Permission denied reading external file '{}': {}", fullFileName, e.message)
                "Error: Permission denied to read file '$fullFileName'."
            }
        }

        if (!isValidFilename(fullFileName)) return INVALID_FILENAME_ERROR_MESSAGE
        val file = getFile(fullFileName) ?: return "File '$fullFileName' not found."

        return try {
            val content = file.content()
            "Read from file $fullFileName.\n<content>\n$content\n</content>"
        } catch (e: FileSystemError) {
            logger.warn("Could not read file '{}': {}", fullFileName, e.message)
            e.message ?: "Error: Could not read file '$fullFileName'."
        } catch (e: Exception) {
            logger.warn("Could not read file '{}': {}", fullFileName, e.message)
            "Error: Could not read file '$fullFileName'. ${e.message ?: ""}".trim()
        }
    }

    /**
     * Writes content to a file in the agent file system.
     *
     * Creates a new file if it doesn't exist, or overwrites existing content.
     *
     * @param fullFileName The full file name (e.g., "data.json")
     * @param content The content to write
     * @return A success message or error message
     */
    suspend fun writeString(fullFileName: String, content: String): String {
        if (!isValidFilename(fullFileName)) return INVALID_FILENAME_ERROR_MESSAGE
        return try {
            val (name, ext) = parseFilename(fullFileName)
            val file = files[fullFileName] ?: createFile(ext, name).also { files[fullFileName] = it }
            val path = file.writeString(content, dataDir)

            // logger.info("Write to file | {}", path.toUri())

            "Data written to file $fullFileName successfully."
        } catch (e: FileSystemError) {
            logger.warn("Could not write to file '{}': {}", fullFileName, e.message)
            e.message ?: "Error: Could not write to file '$fullFileName'."
        } catch (e: Exception) {
            logger.warn("Could not write to file '{}': {}", fullFileName, e.message)
            "Error: Could not write to file '$fullFileName'. ${e.message ?: ""}".trim()
        }
    }

    /**
     * Appends content to an existing file.
     *
     * @param fullFileName The full file name (e.g., "data.json")
     * @param content The content to append
     * @return A success message or error message
     */
    suspend fun append(fullFileName: String, content: String): String {
        if (!isValidFilename(fullFileName)) return INVALID_FILENAME_ERROR_MESSAGE
        val file = getFile(fullFileName) ?: return "File '$fullFileName' not found."
        return try {
            file.appendString(content, dataDir)
            "Data appended to file $fullFileName successfully."
        } catch (e: FileSystemError) {
            logger.warn("Could not append to file '{}': {}", fullFileName, e.message)
            e.message ?: "Error: Could not append to file '$fullFileName'."
        } catch (e: Exception) {
            logger.warn("Could not append to file '{}': {}", fullFileName, e.message)
            "Error: Could not append to file '$fullFileName'. ${e.message ?: ""}".trim()
        }
    }

    /**
     * Replaces all occurrences of a string in a file with a new string.
     *
     * @param fullFileName The full file name (e.g., "data.json")
     * @param oldStr The string to replace (must not be empty)
     * @param newStr The replacement string
     * @return A success message or error message
     */
    suspend fun replaceContent(fullFileName: String, oldStr: String, newStr: String): String {
        if (!isValidFilename(fullFileName)) return INVALID_FILENAME_ERROR_MESSAGE
        if (oldStr.isEmpty()) return "Error: Cannot replace empty string. Please provide a non-empty string to replace."
        val file = getFile(fullFileName) ?: return "File '$fullFileName' not found."
        return try {
            val replaced = file.content().replace(oldStr, newStr)
            file.writeString(replaced, dataDir)
            "Successfully replaced all occurrences of \"$oldStr\" with \"$newStr\" in file $fullFileName"
        } catch (e: FileSystemError) {
            logger.warn("Could not replace string in file '{}': {}", fullFileName, e.message)
            e.message ?: "Error: Could not replace string in file '$fullFileName'."
        } catch (e: Exception) {
            logger.warn("Could not replace string in file '{}': {}", fullFileName, e.message)
            "Error: Could not replace string in file '$fullFileName'. ${e.message ?: ""}".trim()
        }
    }

    /**
     * Checks if a file exists in the agent file system.
     *
     * @param fullFileName The full file name including extension (e.g., "data.json")
     * @return A message indicating whether the file exists
     */
    fun fileExists(fullFileName: String): String {
        if (!isValidFilename(fullFileName)) return INVALID_FILENAME_ERROR_MESSAGE
        val exists = files.containsKey(fullFileName)
        return if (exists) {
            "File '$fullFileName' exists."
        } else {
            "File '$fullFileName' does not exist."
        }
    }

    /**
     * Returns information about a file including size and line count.
     *
     * @param fullFileName The full file name including extension (e.g., "data.json")
     * @return A message with file information or an error message
     */
    fun getFileInfo(fullFileName: String): String {
        if (!isValidFilename(fullFileName)) return INVALID_FILENAME_ERROR_MESSAGE
        val file = getFile(fullFileName) ?: return "File '$fullFileName' not found."
        return try {
            val content = file.content()
            // More efficient byte size calculation
            val sizeBytes = content.encodeToByteArray().size
            val lineCount = file.lineCount
            val charCount = content.length
            """File info for '$fullFileName':
- Size: $sizeBytes bytes
- Characters: $charCount
- Lines: $lineCount
- Extension: ${file.extension}"""
        } catch (e: Exception) {
            logger.warn("Could not get info for file '{}': {}", fullFileName, e.message)
            "Error: Could not get info for file '$fullFileName'. ${e.message ?: ""}".trim()
        }
    }

    /**
     * Deletes a file from the agent file system.
     *
     * @param fullFileName The full file name including extension (e.g., "data.json")
     * @return A message indicating success or failure
     */
    suspend fun deleteFile(fullFileName: String): String {
        if (!isValidFilename(fullFileName)) return INVALID_FILENAME_ERROR_MESSAGE
        val file = files.remove(fullFileName) ?: return "File '$fullFileName' not found."
        return try {
            val filePath = dataDir.resolve(file.fullName)
            withContext(Dispatchers.IO) {
                if (filePath.exists()) {
                    Files.delete(filePath)
                }
            }
            "File '$fullFileName' deleted successfully."
        } catch (e: IOException) {
            logger.warn("File '{}' removed from memory but could not delete from disk: {}", fullFileName, e.message)
            // File removed from memory but OS file deletion failed
            "File '$fullFileName' removed from memory, but could not delete from disk: ${e.message}"
        } catch (e: Exception) {
            logger.warn("Could not delete file '{}': {}", fullFileName, e.message)
            "Error: Could not delete file '$fullFileName'. ${e.message ?: ""}".trim()
        }
    }

    /**
     * Copies a file within the agent file system.
     *
     * @param sourceFileName The source file name (e.g., "source.txt")
     * @param destFileName The destination file name (e.g., "dest.txt")
     * @return A message indicating success or failure
     */
    suspend fun copyFile(sourceFileName: String, destFileName: String): String {
        if (!isValidFilename(sourceFileName)) return "Error: Invalid source fileName format. Must be alphanumeric with supported extension."
        if (!isValidFilename(destFileName)) return "Error: Invalid destination fileName format. Must be alphanumeric with supported extension."
        if (sourceFileName == destFileName) return "Error: Source and destination file names must be different."

        val sourceFile = getFile(sourceFileName) ?: return "Source file '$sourceFileName' not found."

        return try {
            val (destName, destExt) = parseFilename(destFileName)
            val newFile = createFile(destExt, destName, sourceFile.content())
            files[destFileName] = newFile
            newFile.writeString(dataDir)
            "File '$sourceFileName' copied to '$destFileName' successfully."
        } catch (e: FileSystemError) {
            logger.warn("Could not copy file '{}' to '{}': {}", sourceFileName, destFileName, e.message)
            e.message ?: "Error: Could not copy file."
        } catch (e: Exception) {
            logger.warn("Could not copy file '{}' to '{}': {}", sourceFileName, destFileName, e.message)
            "Error: Could not copy file '$sourceFileName' to '$destFileName'. ${e.message ?: ""}".trim()
        }
    }

    /**
     * Moves/renames a file within the agent file system.
     *
     * The operation is performed atomically to ensure data consistency even in
     * concurrent scenarios. The old disk file is deleted only after the new file
     * has been successfully written.
     *
     * @param sourceFileName The source file name (e.g., "old.txt")
     * @param destFileName The destination file name (e.g., "new.txt")
     * @return A message indicating success or failure
     */
    suspend fun moveFile(sourceFileName: String, destFileName: String): String {
        if (!isValidFilename(sourceFileName)) return "Error: Invalid source fileName format. Must be alphanumeric with supported extension."
        if (!isValidFilename(destFileName)) return "Error: Invalid destination fileName format. Must be alphanumeric with supported extension."
        if (sourceFileName == destFileName) return "Error: Source and destination file names must be different."

        // Use compute to make the operation atomic
        val sourceFile = files[sourceFileName] ?: return "Source file '$sourceFileName' not found."

        return try {
            // Create new file with same content (but don't write to disk yet)
            val (destName, destExt) = parseFilename(destFileName)
            val newFile = createFile(destExt, destName, sourceFile.content())

            // Write new file to disk first
            newFile.writeString(dataDir)

            // Atomically update the map: remove source, add destination
            files.compute(sourceFileName) { _, _ -> null }
            files[destFileName] = newFile

            // Delete old file from disk after successful map update
            val oldPath = dataDir.resolve(sourceFile.fullName)
            withContext(Dispatchers.IO) {
                if (oldPath.exists()) {
                    try {
                        Files.delete(oldPath)
                    } catch (e: IOException) {
                        logger.warn(
                            "Moved file '{}' to '{}' in memory but could not delete old disk file: {}",
                            sourceFileName, destFileName, e.message
                        )
                    }
                }
            }

            "File '$sourceFileName' moved to '$destFileName' successfully."
        } catch (e: FileSystemError) {
            logger.warn("Could not move file '{}' to '{}': {}", sourceFileName, destFileName, e.message)
            // Restore source file on failure
            files[sourceFileName] = sourceFile
            e.message ?: "Error: Could not move file."
        } catch (e: Exception) {
            logger.warn("Could not move file '{}' to '{}': {}", sourceFileName, destFileName, e.message)
            // Restore source file on failure
            files[sourceFileName] = sourceFile
            "Error: Could not move file '$sourceFileName' to '$destFileName'. ${e.message ?: ""}".trim()
        }
    }

    /**
     * Lists all files in the agent file system with their basic info.
     *
     * @return A formatted string listing all files with size and line count
     */
    fun listFilesInfo(): String {
        if (files.isEmpty()) {
            return "No files in the file system."
        }

        return buildString {
            appendLine("Files in agent file system (${files.size} files):")
            for ((fileName, file) in files) {
                val content = file.content()
                // More efficient byte size calculation
                val sizeBytes = content.encodeToByteArray().size
                val lineCount = file.lineCount
                appendLine("- $fileName ($sizeBytes bytes, $lineCount lines)")
            }
        }.trimEnd()
    }

    /**
     * Saves extracted content to a new markdown file with an auto-generated name.
     *
     * The file name follows the pattern: extracted_content_N.md where N is a counter.
     *
     * @param content The content to save
     * @return The generated file name
     */
    suspend fun saveExtractedContent(content: String): String {
        val count = extractedContentCount.getAndIncrement()
        val initial = "extracted_content_$count"
        val fileName = "$initial.md"
        val file = MarkdownFile(initial)
        file.writeString(content, dataDir)
        files[fileName] = file
        return fileName
    }

    /**
     * Describes all files in the agent file system with a preview of their content.
     *
     * For small files, shows the full content. For large files, shows the first and
     * last portions with a line count for the middle.
     *
     * @return A formatted description of all files
     */
    fun describe(): String {
        return buildString {
            for (file in files.values) {
                val content = file.content()
                if (content.isEmpty()) {
                    append("<file>\n${file.fullName} - [empty file]\n</file>\n")
                    continue
                }
                val lines = content.split("\n")
                val lineCount = lines.size
                val whole = "<file>\n${file.fullName} - $lineCount lines\n<content>\n$content\n</content>\n</file>\n"
                if (content.length < (1.5 * DISPLAY_CHARS).toInt()) {
                    append(whole)
                    continue
                }
                val half = DISPLAY_CHARS / 2
                var chars = 0
                var startLineCount = 0
                val startPreview = StringBuilder()
                for (line in lines) {
                    if (chars + line.length + 1 > half) break
                    startPreview.append(line).append('\n')
                    chars += line.length + 1
                    startLineCount += 1
                }
                chars = 0
                var endLineCount = 0
                val endPreview = StringBuilder()
                for (line in lines.asReversed()) {
                    if (chars + line.length + 1 > half) break
                    endPreview.insert(0, line + '\n')
                    chars += line.length + 1
                    endLineCount += 1
                }
                val middle = lineCount - startLineCount - endLineCount
                if (middle <= 0) {
                    append(whole)
                    continue
                }
                val start = startPreview.toString().trim('\n').trimEnd()
                val end = endPreview.toString().trim('\n').trimEnd()
                if (start.isEmpty() && end.isEmpty()) {
                    append("<file>\n${file.fullName} - $lineCount lines\n<content>\n$middle lines...\n</content>\n</file>\n")
                } else {
                    append("<file>\n${file.fullName} - $lineCount lines\n<content>\n$start\n")
                    append("... $middle more lines ...\n")
                    append("$end\n")
                    append("</content>\n</file>\n")
                }
            }
        }.trimEnd('\n')
    }

    /**
     * Gets the current state of the file system for serialization.
     *
     * @return A FileSystemState object containing all files and metadata
     */
    fun getState(): FileSystemState {
        val map = files.mapValues { (_, f) -> FileStateEntry(f::class.simpleName ?: "", f.name, f.content) }
        return FileSystemState(
            files = map,
            baseDir = baseDir.toString(),
            extractedContentCount = extractedContentCount.get()
        )
    }

    /**
     * Cleans a directory by removing all files and subdirectories within it.
     * The directory itself is preserved.
     *
     * @param dir The directory to clean
     */
    private fun cleanDirectory(dir: Path) {
        if (!dir.exists()) return
        if (!dir.isDirectory()) return
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { p ->
                    if (p != dir) {
                        try {
                            Files.delete(p)
                        } catch (e: IOException) {
                            logger.warn("Could not delete '{}' during directory cleanup: {}", p, e.message)
                        }
                    }
                }
        } catch (e: IOException) {
            logger.warn("Error walking directory '{}' during cleanup: {}", dir, e.message)
        }
    }
}
