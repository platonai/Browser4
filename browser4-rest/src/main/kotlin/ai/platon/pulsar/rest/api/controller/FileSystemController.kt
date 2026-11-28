package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.coding.ReadablePaths
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** One directory row the console's folder picker renders. */
data class DirectoryEntry(
    /** Leaf name shown in the list (e.g. `browser4-project`), or the drive label at root level. */
    val name: String,
    /** Absolute path handed back to the submit call as `--workspace` when picked. */
    val path: String,
)

/**
 * One level of a directory listing.
 *
 * `path` is `null` at the roots level (nothing is "selected" yet); `parent` is `null` when
 * the current directory has no browsable parent (a drive root, or `/` on POSIX). `error` is
 * set only on a `400`, so the picker can say *why* a path was refused instead of looking
 * like an empty folder.
 */
data class DirectoryListing(
    val path: String?,
    val parent: String?,
    val entries: List<DirectoryEntry>,
    val error: String? = null,
)

/**
 * Directory browsing for the web console's workspace picker (`agent.html`).
 *
 * ## Why the backend has to do this
 *
 * A browser cannot name an absolute local path: `<input type="file" webkitdirectory>` only
 * exposes relative names, and `showDirectoryPicker()` only exposes the directory's own name.
 * A task workspace must be an **absolute, existing** directory (`TaskWorkspaceOverride`), so
 * the picker has to ask the process that actually runs the task — this one.
 *
 * ## What it exposes, and what it does not
 *
 * Only **directory names**, never file contents, and only as much as needed to walk to a
 * folder. That grants no capability the caller does not already have: the same client can
 * submit a task that reads and writes any directory it names. The OS credential stores
 * ([ReadablePaths.ALWAYS_REFUSED]) are refused anyway, so the picker cannot advertise
 * `~/.ssh` as a selectable workspace.
 */
@CrossOrigin
@RestController
@RequestMapping("api/system/fs", produces = [MediaType.APPLICATION_JSON_VALUE])
class FileSystemController {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * List the sub-directories of [path], or the browse roots when [path] is blank.
     *
     * @param path an absolute directory on this machine; blank/absent asks for the roots.
     * @return `200` with a [DirectoryListing], or `400` when [path] is not a usable directory
     *   (relative, missing, a file, or a credential store) — the picker shows the message
     *   inline instead of silently landing somewhere else.
     */
    @GetMapping("list")
    fun list(@RequestParam(required = false) path: String?): ResponseEntity<DirectoryListing> {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return ResponseEntity.ok(roots())

        val directory = try {
            usableDirectory(raw)
        } catch (e: IllegalArgumentException) {
            logger.debug("Rejecting a folder-picker path '{}': {}", raw, e.message)
            return ResponseEntity.badRequest()
                .body(DirectoryListing(null, null, emptyList(), e.message ?: "unusable path"))
        }

        val entries = try {
            Files.newDirectoryStream(directory).use { stream ->
                stream.asSequence()
                    .filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(it) }
                    .filterNot { ReadablePaths.isAlwaysRefused(it.toAbsolutePath().normalize()) }
                    .map { DirectoryEntry(leafName(it), it.toAbsolutePath().normalize().toString()) }
                    .sortedBy { it.name.lowercase() }
                    .take(MAX_ENTRIES)
                    .toList()
            }
        } catch (e: Exception) {
            logger.debug("Cannot list directory '{}': {}", directory, e.message)
            return ResponseEntity.badRequest()
                .body(DirectoryListing(directory.toString(), null, emptyList(), e.message ?: "cannot list"))
        }

        return ResponseEntity.ok(
            DirectoryListing(
                path = directory.toString(),
                parent = directory.parent?.toString(),
                entries = entries,
            )
        )
    }

    /**
     * The starting points of a browse session: the filesystem roots, the user's home
     * directory, and the backend's own working directory.
     */
    private fun roots(): DirectoryListing {
        val entries = LinkedHashMap<String, DirectoryEntry>()
        val home = runCatching { Path.of(System.getProperty("user.home")) }.getOrNull()
        if (home != null && Files.isDirectory(home)) {
            entries[home.toString()] = DirectoryEntry("🏠 主目录", home.toString())
        }
        val cwd = runCatching { Path.of("").toAbsolutePath().normalize() }.getOrNull()
        if (cwd != null && Files.isDirectory(cwd)) {
            entries[cwd.toString()] = DirectoryEntry("📂 后端工作目录", cwd.toString())
        }
        runCatching { File.listRoots() }.getOrNull()?.forEach { root ->
            val absolute = root.absolutePath
            entries.putIfAbsent(absolute, DirectoryEntry(absolute, absolute))
        }
        return DirectoryListing(path = null, parent = null, entries = entries.values.toList())
    }

    /** A non-blank, absolute, existing directory that is not an OS credential store. */
    private fun usableDirectory(raw: String): Path {
        val candidate = runCatching { Path.of(raw) }.getOrElse {
            throw IllegalArgumentException("'$raw' is not a valid path on this machine: ${it.message}")
        }
        require(candidate.isAbsolute) { "the path must be absolute" }
        val normalized = candidate.toAbsolutePath().normalize()
        require(!ReadablePaths.isAlwaysRefused(normalized)) { "credential stores are not browsable" }
        require(Files.isDirectory(normalized)) { "'$normalized' is not a directory" }
        return normalized
    }

    /** The last path segment (the drive prefix when there is none, e.g. `C:\`). */
    private fun leafName(path: Path): String {
        val name = path.fileName?.toString()
        return if (name.isNullOrBlank()) path.toString().trimEnd('\\', '/').ifEmpty { path.toString() } else name
    }

    private companion object {
        /** A safety valve for enormous directories; the picker is for walking, not auditing. */
        const val MAX_ENTRIES = 500
    }
}
