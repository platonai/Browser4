package ai.platon.browser4.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Manages snapshot and screenshot file output with timestamped naming.
 *
 * Mirrors the Rust [snapshot.rs] module.
 */
object SnapshotManager {

    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")

    /** Generates a timestamped filename like `snapshot-2026-06-15T10-30-00Z.yml`. */
    fun timestampedFilename(prefix: String, ext: String): String {
        val now = LocalDateTime.now().format(timestampFormat)
        return "$prefix-$now.$ext"
    }

    /**
     * Resolves the output path for a snapshot/screenshot file.
     *
     * If [filename] is provided it is used directly; otherwise a timestamped
     * name is generated.  All output goes under `.browser4-cli/snapshot/`.
     */
    fun resolveOutputPath(filename: String?, prefix: String, ext: String): Path {
        val name = filename ?: timestampedFilename(prefix, ext)
        return Path.of("").toAbsolutePath().resolve(".browser4-cli/snapshot").resolve(name)
    }

    /** Writes text content to disk, creating parent directories as needed. */
    fun saveSnapshot(path: Path, content: String) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, content)
    }

    /** Writes binary data to disk, creating parent directories as needed. */
    fun saveBinary(path: Path, data: ByteArray) {
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, data)
    }
}
