package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*

/**
 * Full filesystem access layer for AI coding agents.
 *
 * Unlike [AgentFileSystem] which is sandboxed to a specific directory and limited
 * to a few file extensions, this class provides the agent with read/write access
 * across the entire filesystem (subject to OS permissions), supports all file types,
 * and includes directory operations, glob-based file search, and content diffing.
 *
 * ## Security
 *
 * Operations outside the [workspaceRoot] log a warning but are permitted when
 * [allowExternalAccess] is true. Destructive operations (delete, move outside
 * workspace) require explicit opt-in.
 *
 * ## Usage
 *
 * ```kotlin
 * val cfs = CodingAgentFileSystem(workspaceRoot = Path.of("/home/user/project"))
 * cfs.writeFile("src/main.kt", code)
 * val content = cfs.readFile("src/main.kt")
 * val results = cfs.glob("src/**/*.kt")
 * ```
 */
class CodingAgentFileSystem(
    val workspaceRoot: Path,
    private val allowExternalAccess: Boolean = false,
    private val allowDestructive: Boolean = true,
    /**
     * Directory names skipped by recursive searches ([glob]/[grep]/[detectLanguages]).
     * Default excludes the usual build/dependency/vcs noise that makes full-tree
     * walks slow in large repos.
     */
    private val searchExcludedDirs: Set<String> = DEFAULT_SEARCH_EXCLUDED_DIRS,
    /**
     * Repo-critical file names (basenames) that destructive operations must not
     * touch: delete/replace/append are blocked for these. Defaults to the
     * Browser4 governance set (VERSION, AGENTS.md, BOM, root pom).
     */
    private val protectedFiles: Set<String> = DEFAULT_PROTECTED_FILES,
    /**
     * Persist write snapshots under `<workspace>/.browser4/coding-snapshots/`
     * so `revert`/`diff` survive a backend restart (plan P2.3). The directory is
     * git-ignored. Off by default so tests and throw-away workspaces stay
     * pristine; the production wiring (`AgentToolManager`, `MCPToolController`)
     * enables it.
     */
    private val persistSnapshots: Boolean = false,
) {
    companion object {
        const val MAX_READ_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB
        const val MAX_GLOB_RESULTS = 10_000

        /** Upper bound of the per-session mutation log (oldest entries drop out). */
        const val MAX_MUTATION_LOG = 200

        /** Path comparison is case-insensitive on Windows only. */
        private val IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")

        /**
         * Upper bound for [listDir] recursion. The requested [listDir.maxDepth] is honored
         * up to this bound; anything beyond is capped and reported in the output instead of
         * being silently truncated (agents must not believe a deep listing was complete).
         */
        const val MAX_LIST_DIR_DEPTH = 32

        /**
         * Default cap on the number of characters returned to the LLM context by
         * [readFile] / [readFileLines] / [diff]. ~120K chars ≈ 35K tokens —
         * enough for most source files, while preventing a single 5 MB read
         * (~1.5M tokens) from blowing out the agent's context window.
         *
         * Content exceeding this is folded head+tail with an omission marker
         * directing the agent to [readFileLines] for targeted ranges.
         */
        const val DEFAULT_MAX_OUTPUT_CHARS = 120_000
        private val logger = LoggerFactory.getLogger(CodingAgentFileSystem::class.java)

        /** Repo-governance files that destructive ops must never modify. */
        val DEFAULT_PROTECTED_FILES: Set<String> = setOf(
            "VERSION",
            "AGENTS.md",
            "CLAUDE.md",
            "pom.xml",          // root aggregator pom — module registration lives here
            "browser4-dependencies/pom.xml", // BOM
            ".github/workflows/ci.yml",
        )

        /** Directories skipped by recursive searches by default. */
        val DEFAULT_SEARCH_EXCLUDED_DIRS: Set<String> = setOf(
            ".git", ".svn", ".hg",
            "node_modules", "bower_components",
            "target", "build", "dist", "out", "bin",
            ".gradle", ".idea", ".vscode", ".claude", ".mvn",
            "__pycache__", ".venv", "venv", ".tox",
            ".next", ".nuxt", ".cache", ".parcel-cache",
        )

        /** Version-control metadata directories — protected from recursive deletion. */
        val VCS_DIRS: Set<String> = setOf(".git", ".svn", ".hg")

        /** Binary file extensions that should not be read as text */
        val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svgz",
            "mp3", "mp4", "avi", "mov", "mkv", "webm", "wav", "flac",
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "class", "jar", "war", "ear", "exe", "dll", "so", "dylib",
            "bin", "dat", "db", "sqlite", "sqlite3",
            "ttf", "otf", "woff", "woff2", "eot",
            "wasm", "o", "a", "lib", "obj",
        )

        /** Recognized source code / text extensions */
        val SOURCE_EXTENSIONS = setOf(
            "kt", "kts", "java", "scala", "groovy",
            "rs", "c", "cpp", "cc", "cxx", "h", "hpp", "hh",
            "py", "pyi", "pyx",
            "js", "jsx", "ts", "tsx", "mjs", "cjs",
            "go", "rb", "php", "swift", "cs", "fs",
            "sh", "bash", "zsh", "fish", "ps1", "psm1", "psd1", "bat", "cmd",
            "html", "htm", "css", "scss", "sass", "less",
            "xml", "xsl", "xslt", "svg",
            "json", "jsonc", "json5", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "md", "markdown", "rst", "txt", "text", "log",
            "sql", "graphql", "gql",
            "proto", "thrift", "avsc", "avdl",
            "gradle", "properties", "lock",
            "dockerfile", "makefile", "cmake",
            "tf", "tfvars", "hcl",
            "yml", "yaml",
        )
    }

    private val canonicalRoot: Path by lazy { workspaceRoot.toRealPath() }

    /**
     * File change tracker for reverting.
     *
     * [checksum] is a real content hash (`sha256:<hex>`), not a timestamp: it
     * backs the optimistic-concurrency guard on writes — a file another process
     * touched must not be overwritten blindly, and a revert target is identified
     * by content (plan P2.3).
     */
    private data class FileSnapshot(
        val path: Path,
        val existed: Boolean,
        val content: String?,
        val checksum: String,
        val trackedAtMillis: Long = System.currentTimeMillis(),
    )

    private val snapshots = ConcurrentHashMap<Path, FileSnapshot>()
    private val changeCounter = AtomicInteger(0)

    /**
     * Ordered log of every mutation this file system performed (write/append/
     * delete/move/copy/revert), bounded.
     *
     * Why: the completion gate can only compare *structured* claims. Without a
     * record of what actually happened, a report saying "I only confirmed the
     * files, they already existed" is indistinguishable from the truth even when
     * the run wrote those files three tool calls earlier. The log turns that
     * narrative into a checkable contradiction.
     */
    private val mutationLog = java.util.concurrent.ConcurrentLinkedDeque<FileMutation>()
    private val mutationCount = AtomicInteger(0)

    /** One file mutation, [op] ∈ {write, append, delete, move, copy, revert}. */
    data class FileMutation(val op: String, val path: String, val ok: Boolean)

    /** Snapshot of the mutation log, oldest first. */
    fun mutationsSnapshot(): List<FileMutation> = mutationLog.toList()

    /** Reset the log (a new task started). */
    fun clearMutations() {
        mutationLog.clear()
        mutationCount.set(0)
    }

    private fun recordMutation(op: String, path: Path, ok: Boolean) {
        val relative = runCatching { workspaceRoot.relativize(path.toAbsolutePath().normalize()).toString() }
            .getOrElse { path.toString() }
        mutationLog.addLast(FileMutation(op, relative.replace('\\', '/'), ok))
        if (mutationCount.incrementAndGet() > MAX_MUTATION_LOG) {
            mutationLog.pollFirst()
        }
    }

    /** JSON codec + location for persisted snapshots (plan P2.3). */
    private val snapshotMapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val snapshotDir: Path by lazy { workspaceRoot.resolve(".browser4").resolve("coding-snapshots") }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Read a text file. Path is resolved relative to [workspaceRoot], or absolute
     * when [allowExternalAccess] is true.
     */
    suspend fun readFile(path: String, encoding: Charset = StandardCharsets.UTF_8): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("File not found: $path")
        if (resolved.isDirectory()) return errorResult("Path is a directory: $path")
        if (Files.size(resolved) > MAX_READ_SIZE_BYTES) {
            return errorResult("File too large (${Files.size(resolved)} bytes, max $MAX_READ_SIZE_BYTES): $path")
        }

        val ext = resolved.extension.lowercase()
        if (ext in BINARY_EXTENSIONS) {
            return "[Binary file: $path (${Files.size(resolved)} bytes, type: $ext)]"
        }

        return try {
            withContext(Dispatchers.IO) {
                Files.readString(resolved, encoding)
            }.let { truncateForContext(it, path = path) }
        } catch (e: IOException) {
            errorResult("Failed to read '$path': ${e.message}")
        }
    }

    /**
     * Read a file with surrounding context lines (like head/tail).
     *
     * The returned text is folded to [DEFAULT_MAX_OUTPUT_CHARS] to protect the
     * agent's context budget; pass a larger [maxChars] when a full range is needed.
     */
    suspend fun readFileLines(
        path: String,
        startLine: Int = 1,
        endLine: Int = -1,
        maxChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    ): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("File not found: $path")

        return try {
            withContext(Dispatchers.IO) {
                val lines = Files.readAllLines(resolved)
                val s = (startLine - 1).coerceIn(0, lines.size - 1)
                val e = if (endLine < 0) lines.size else endLine.coerceIn(s, lines.size)
                lines.subList(s, e).joinToString("\n")
            }.let { truncateForContext(it, maxChars, path) }
        } catch (e: IOException) {
            errorResult("Failed to read '$path': ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Write content to a file. Creates parent directories if needed.
     * Takes a snapshot before writing for potential revert.
     *
     * The write is atomic (temp file in the same directory + move), so a crash
     * mid-write cannot leave a half-written source file behind — a partial
     * Kotlin file then poisons every later compile (plan P0.8).
     *
     * @param expectedChecksum optimistic concurrency (plan P2.3/P2.5): when
     *   given, the write only proceeds if the file's CURRENT content hashes to
     *   exactly this value (see [checksumOf]). A concurrent edit then fails
     *   loudly instead of being silently overwritten.
     */
    suspend fun writeFile(
        path: String,
        content: String,
        encoding: Charset = StandardCharsets.UTF_8,
        createSnapshot: Boolean = true,
        expectedChecksum: String? = null,
    ): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (resolved.isDirectory()) return errorResult("Cannot write to a directory: $path")
        protectionViolation(resolved)?.let { return errorResult(it) }
        expectedChecksum?.let { expected ->
            concurrencyViolation(resolved, expected)?.let { return errorResult(it) }
        }

        return try {
            // Take snapshot before writing
            if (createSnapshot) {
                snapshotFile(resolved)
            }

            withContext(Dispatchers.IO) {
                writeAtomic(resolved, content, encoding)
            }
            changeCounter.incrementAndGet()
            recordMutation("write", resolved, true)
            "✓ Wrote ${content.length} chars to $path (${Files.size(resolved)} bytes)"
        } catch (e: IOException) {
            errorResult("Failed to write '$path': ${e.message}")
        }
    }

    /**
     * Append content to a file.
     *
     * Implemented as read + atomic rewrite rather than an `APPEND` open so the
     * append cannot interleave with a concurrent writer and cannot leave a
     * torn tail on crash.
     */
    suspend fun appendFile(path: String, content: String): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (resolved.isDirectory()) return errorResult("Cannot append to a directory: $path")
        protectionViolation(resolved)?.let { return errorResult(it) }

        return try {
            snapshotFile(resolved)
            withContext(Dispatchers.IO) {
                val existing = if (resolved.exists()) Files.readString(resolved) else ""
                writeAtomic(resolved, existing + content)
            }
            changeCounter.incrementAndGet()
            recordMutation("append", resolved, true)
            "✓ Appended ${content.length} chars to $path"
        } catch (e: IOException) {
            errorResult("Failed to append to '$path': ${e.message}")
        }
    }

    /**
     * Replace all occurrences of a string in a file.
     */
    suspend fun replaceInFile(
        path: String,
        oldStr: String,
        newStr: String,
        count: Int = -1, // -1 = replace all
    ): String {
        if (oldStr.isEmpty()) return errorResult("Cannot replace empty string")
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("File not found: $path")
        protectionViolation(resolved)?.let { return errorResult(it) }

        return try {
            val original = withContext(Dispatchers.IO) { Files.readString(resolved) }
            if (!original.contains(oldStr)) {
                return "⚠️ '$oldStr' not found in $path — no changes made"
            }

            snapshotFile(resolved)

            val replaced = if (count < 0) {
                original.replace(oldStr, newStr)
            } else {
                var remaining = original
                var c = 0
                var idx = remaining.indexOf(oldStr)
                while (idx >= 0 && c < count) {
                    remaining = remaining.substring(0, idx) + newStr + remaining.substring(idx + oldStr.length)
                    c++
                    idx = remaining.indexOf(oldStr, idx + newStr.length)
                }
                remaining
            }

            withContext(Dispatchers.IO) {
                writeAtomic(resolved, replaced)
            }
            changeCounter.incrementAndGet()
            recordMutation("edit", resolved, true)

            val occurrences = if (count < 0) "all" else "up to $count"
            "✓ Replaced $occurrences occurrence(s) of '$oldStr' → '$newStr' in $path"
        } catch (e: IOException) {
            errorResult("Failed to replace in '$path': ${e.message}")
        }
    }

    /**
     * Replace all matches of a regular expression in a file.
     *
     * @param regex   Java regex pattern; the whole match is replaced by [replacement].
     *                Use `$1`/`${name}` groups with [replacement] as in [String.replace].
     * @param count   max number of matches to replace; -1 = replace all
     */
    suspend fun replaceRegexInFile(
        path: String,
        regex: String,
        replacement: String,
        count: Int = -1,
    ): String {
        if (regex.isEmpty()) return errorResult("Cannot replace empty regex")
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("File not found: $path")
        protectionViolation(resolved)?.let { return errorResult(it) }

        return try {
            val pattern = Regex(regex)
            val original = withContext(Dispatchers.IO) { Files.readString(resolved) }
            val matches = pattern.findAll(original).toList()
            if (matches.isEmpty()) {
                return "⚠️ Regex '$regex' not found in $path — no changes made"
            }

            snapshotFile(resolved)

            val replaced = if (count < 0) {
                original.replace(pattern, replacement)
            } else {
                val sb = StringBuilder()
                var last = 0
                var c = 0
                for (m in matches) {
                    if (c >= count) break
                    sb.append(original, last, m.range.first)
                    sb.append(expandReplacement(m, replacement))
                    last = m.range.last + 1
                    c++
                }
                sb.append(original, last, original.length)
                sb.toString()
            }

            withContext(Dispatchers.IO) {
                writeAtomic(resolved, replaced)
            }
            changeCounter.incrementAndGet()
            recordMutation("replace", resolved, true)

            val occurrences = if (count < 0) "${matches.size}" else "up to $count"
            "✓ Replaced $occurrences regex match(es) of '$regex' → '$replacement' in $path"
        } catch (e: IllegalArgumentException) {
            errorResult("Invalid regex '$regex': ${e.message}")
        } catch (e: IOException) {
            errorResult("Failed to replace in '$path': ${e.message}")
        }
    }

    /**
     * Replace a line range [startLine]..[endLine] (1-based, inclusive) with [content].
     * Uses the snapshot BEFORE the edit as its diff baseline is captured on first write.
     */
    suspend fun editLinesInFile(
        path: String,
        startLine: Int,
        endLine: Int,
        content: String,
    ): String {
        if (startLine < 1 || endLine < startLine) {
            return errorResult("Invalid line range: start=$startLine end=$endLine (1-based, start <= end)")
        }
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("File not found: $path")
        protectionViolation(resolved)?.let { return errorResult(it) }

        return try {
            val original = withContext(Dispatchers.IO) { Files.readString(resolved) }
            val lines = lineList(original)
            if (startLine > lines.size) {
                return errorResult("startLine $startLine beyond file end (${lines.size} lines): $path")
            }

            snapshotFile(resolved)

            val newLines = lines.toMutableList()
            val end = endLine.coerceAtMost(lines.size)
            newLines.subList(startLine - 1, end).clear()
            newLines.addAll(startLine - 1, content.lines())

            withContext(Dispatchers.IO) {
                writeAtomic(resolved, joinLines(newLines, original))
            }
            changeCounter.incrementAndGet()
            recordMutation("replaceLines", resolved, true)

            "✓ Replaced lines $startLine..$endLine in $path with ${content.lines().size} line(s)"
        } catch (e: IOException) {
            errorResult("Failed to edit '$path': ${e.message}")
        }
    }

    /**
     * Insert [content] after the line containing [anchor] (substring match on first hit).
     */
    suspend fun insertAfterInFile(path: String, anchor: String, content: String): String {
        if (anchor.isEmpty()) return errorResult("Anchor must not be empty")
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("File not found: $path")
        protectionViolation(resolved)?.let { return errorResult(it) }

        return try {
            val original = withContext(Dispatchers.IO) { Files.readString(resolved) }
            val lines = lineList(original)
            val idx = lines.indexOfFirst { it.contains(anchor) }
            if (idx < 0) {
                return "⚠️ Anchor '$anchor' not found in $path — no changes made"
            }

            snapshotFile(resolved)

            val newLines = lines.toMutableList()
            newLines.addAll(idx + 1, content.lines())

            withContext(Dispatchers.IO) {
                writeAtomic(resolved, joinLines(newLines, original))
            }
            changeCounter.incrementAndGet()
            recordMutation("insert", resolved, true)

            "✓ Inserted after line ${idx + 1} (anchor '$anchor') in $path"
        } catch (e: IOException) {
            errorResult("Failed to insert in '$path': ${e.message}")
        }
    }

    /**
     * Restore a file to its snapshot (the state before the first tracked write).
     *
     * The snapshot is looked up in memory first and then on disk
     * (`.browser4/coding-snapshots/`), so a revert still works after a backend
     * restart (plan P2.3). Fails if no snapshot exists — use [changeSummary] to
     * see tracked files.
     */
    suspend fun revert(path: String): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        val snapshot = snapshots[resolved] ?: loadPersistedSnapshot(resolved)
            ?: return errorResult("No snapshot available for revert: $path")
        snapshots.putIfAbsent(resolved, snapshot)

        return try {
            withContext(Dispatchers.IO) {
                if (snapshot.existed && snapshot.content != null) {
                    writeAtomic(resolved, snapshot.content)
                } else if (snapshot.existed) {
                    // Snapshot existed but content was null (binary or unreadable) — leave untouched
                    return@withContext
                } else {
                    Files.deleteIfExists(resolved)
                }
            }
            changeCounter.incrementAndGet()
            recordMutation("revert", resolved, true)
            "✓ Reverted $path to snapshot"
        } catch (e: IOException) {
            errorResult("Failed to revert '$path': ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // File / Directory operations
    // ------------------------------------------------------------------

    /**
     * Check if a path exists.
     */
    fun exists(path: String): Boolean {
        return resolvePath(path)?.exists() == true
    }

    /**
     * Get file or directory information.
     */
    fun fileInfo(path: String): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("Not found: $path")

        return try {
            val attrs = Files.readAttributes(resolved, BasicFileAttributes::class.java)
            buildString {
                appendLine("Path: $path → ${resolved.toAbsolutePath()}")
                appendLine("Type: ${if (attrs.isDirectory) "directory" else "file"}")
                appendLine("Size: ${attrs.size()} bytes")
                appendLine("Created: ${attrs.creationTime()}")
                appendLine("Modified: ${attrs.lastModifiedTime()}")
                if (!attrs.isDirectory) {
                    try {
                        val lines = Files.readAllLines(resolved).size
                        appendLine("Lines: $lines")
                    } catch (_: Exception) {}
                }
            }.trimEnd()
        } catch (e: IOException) {
            errorResult("Failed to stat '$path': ${e.message}")
        }
    }

    /**
     * Delete a file or empty directory.
     *
     * Hard protections (always active, independent of [allowDestructive]):
     * - the workspace root itself can never be deleted
     * - version-control directories (.git/.svn/.hg) can never be deleted recursively
     */
    suspend fun delete(path: String, recursive: Boolean = false): String {
        if (!allowDestructive) return errorResult("Destructive operations disabled")
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("Not found: $path")

        // Governance protection: never delete repo-critical files.
        protectionViolation(resolved)?.let { return errorResult(it) }

        // Hard protection: never delete the workspace root itself.
        if (resolved == canonicalRoot) {
            return errorResult("Refusing to delete the workspace root: $path")
        }
        // Hard protection: never delete VCS metadata directories.
        if (resolved.fileName.toString() in VCS_DIRS) {
            return errorResult("Refusing to delete version-control directory: $path")
        }
        // Hard protection: never delete a link or a junction. Deleting the LINK
        // is at best confusing (the workspace loses a module) and at worst
        // destructive on platforms/APIs that follow the reparse point into the
        // target tree. Observed in practice: an agent deleted the
        // `browser4-coding` junction inside its workspace while trying to "fix"
        // a Maven reactor problem.
        resolveLinkTarget(resolved)?.let { target ->
            return errorResult(
                "Refusing to delete a link or junction: $path (it points at $target). " +
                    "Delete the target explicitly if that is really intended."
            )
        }
        // A recursive delete must not walk through a link either: one junction
        // inside a directory turns "delete this folder" into "wipe a checkout".
        if (recursive && resolved.isDirectory()) {
            val linkedChild = firstLinkedChild(resolved)
            if (linkedChild != null) {
                return errorResult(
                    "Refusing recursive delete of '$path' — it contains a link or junction: " +
                        resolved.relativize(linkedChild)
                )
            }
        }
        // Also refuse deleting a VCS dir when the target is one of its parents and
        // recursive would swallow it — e.g. deleting a directory that *is* a .git
        // parent is allowed, but the walk below never enters VCS dirs.
        if (recursive && resolved.isDirectory() && hasVcsChild(resolved)) {
            return errorResult("Refusing recursive delete of '$path' — it contains a version-control directory")
        }

        if (resolved.isDirectory() && !recursive) {
            return errorResult("Use recursive=true to delete directory: $path")
        }

        return try {
            snapshotFile(resolved)
            withContext(Dispatchers.IO) {
                if (resolved.isDirectory() && recursive) {
                    resolved.toFile().deleteRecursively()
                } else {
                    Files.delete(resolved)
                }
            }
            changeCounter.incrementAndGet()
            recordMutation("delete", resolved, true)
            "✓ Deleted: $path"
        } catch (e: IOException) {
            errorResult("Failed to delete '$path': ${e.message}")
        }
    }

    /** Version-control metadata directory names that recursive deletes must not touch. */
    private fun hasVcsChild(dir: Path): Boolean =
        VCS_DIRS.any { vcs -> Files.isDirectory(dir.resolve(vcs)) }

    /**
     * The link/junction target behind [path], or null when [path] is a plain
     * file/directory.
     *
     * A junction (Windows reparse point) is not a symbolic link to NIO, so
     * `isSymbolicLink` misses it; comparing the real path with the absolute
     * path catches both kinds without platform-specific APIs.
     */
    private fun resolveLinkTarget(path: Path): Path? = runCatching {
        val absolute = path.toAbsolutePath().normalize()
        val real = path.toRealPath()
        if (samePath(real, absolute)) null else real
    }.getOrNull()

    private fun samePath(a: Path, b: Path): Boolean =
        if (IS_WINDOWS) a.toString().equals(b.toString(), ignoreCase = true) else a == b

    /** The first direct child of [dir] that is a link/junction, or null. */
    private fun firstLinkedChild(dir: Path): Path? = runCatching {
        Files.list(dir).use { stream ->
            stream.filter { resolveLinkTarget(it) != null }.findFirst().orElse(null)
        }
    }.getOrNull()

    /**
     * Create a directory (and parents if needed).
     */
    suspend fun mkdir(path: String): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")

        return try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(resolved)
            }
            "✓ Created directory: $path"
        } catch (e: IOException) {
            errorResult("Failed to create directory '$path': ${e.message}")
        }
    }

    /**
     * Copy a file or directory.
     */
    suspend fun copy(source: String, dest: String): String {
        if (!allowDestructive) return errorResult("Destructive operations disabled")
        val src = resolvePath(source) ?: return errorResult("Source not resolved: $source")
        val dst = resolvePath(dest) ?: return errorResult("Dest not resolved: $dest")
        if (!src.exists()) return errorResult("Source not found: $source")
        // A copy into a governance file is a replace in disguise.
        protectionViolation(dst)?.let { return errorResult(it) }

        return try {
            withContext(Dispatchers.IO) {
                if (src.isDirectory()) {
                    src.toFile().copyRecursively(dst.toFile(), overwrite = true)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            changeCounter.incrementAndGet()
            recordMutation("copy", dst, true)
            "✓ Copied $source → $dest"
        } catch (e: IOException) {
            errorResult("Failed to copy '$source' → '$dest': ${e.message}")
        }
    }

    /**
     * Move/rename a file or directory.
     */
    suspend fun move(source: String, dest: String): String {
        if (!allowDestructive) return errorResult("Destructive operations disabled")
        val src = resolvePath(source) ?: return errorResult("Source not resolved: $source")
        val dst = resolvePath(dest) ?: return errorResult("Dest not resolved: $dest")
        if (!src.exists()) return errorResult("Source not found: $source")
        // A move deletes the source and replaces the destination — both ends
        // must respect governance protection.
        protectionViolation(src)?.let { return errorResult(it) }
        protectionViolation(dst)?.let { return errorResult(it) }

        return try {
            snapshotFile(src)
            withContext(Dispatchers.IO) {
                Files.createDirectories(dst.parent)
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
            changeCounter.incrementAndGet()
            recordMutation("move", dst, true)
            "✓ Moved $source → $dest"
        } catch (e: IOException) {
            errorResult("Failed to move '$source' → '$dest': ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Listing and search
    // ------------------------------------------------------------------

    /**
     * List files in a directory (relative to workspace root).
     */
    suspend fun listDir(path: String = ".", maxDepth: Int = 1): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("Not found: $path")
        if (!resolved.isDirectory()) return errorResult("Not a directory: $path")

        return try {
            // Honor the requested depth up to a generous bound; if the caller asked for more,
            // tell them in the output rather than silently walking only 5 levels.
            val effectiveDepth = maxDepth.coerceAtLeast(1).coerceAtMost(MAX_LIST_DIR_DEPTH)
            val depthCapped = maxDepth > MAX_LIST_DIR_DEPTH
            val entries: List<Path> = withContext(Dispatchers.IO) {
                Files.walk(resolved, effectiveDepth)
                    .filter { it != resolved }
                    .sorted()
                    .toList()
            }

            if (entries.isEmpty()) return "Directory is empty: $path"

            buildString {
                val capNote = if (depthCapped) " (depth capped at $MAX_LIST_DIR_DEPTH)" else ""
                appendLine("Contents of $path (${entries.size} entries)$capNote:")
                for (entry in entries) {
                    val relPath = resolved.relativize(entry)
                    val prefix = if (entry.isDirectory()) "📁" else "📄"
                    val size = try {
                        if (!entry.isDirectory()) " (${Files.size(entry)} bytes)" else ""
                    } catch (_: Exception) { "" }
                    appendLine("  $prefix $relPath$size")
                }
            }.trimEnd()
        } catch (e: IOException) {
            errorResult("Failed to list '$path': ${e.message}")
        }
    }

    /**
     * Workspace-relative paths matching a glob [pattern] (e.g. a recursive glob for Foo.kt),
     * sorted, at most [maxResults].
     *
     * Used to resolve a bare filename mentioned in a dev task to the module that
     * owns it — the planner cannot guess a directory from `PulsarWebDriver.kt`
     * (eval K1), and walking the tree is the caller's job (I/O stays here).
     */
    suspend fun findFiles(pattern: String, maxResults: Int = 50): List<String> {
        val (baseDir, globPart) = splitGlobPattern(pattern)
        if (!baseDir.exists()) return emptyList()
        val matches = try {
            withContext(Dispatchers.IO) { walkGlob(baseDir, globPart, maxResults) }
        } catch (_: IOException) {
            return emptyList()
        }
        return matches.sortedBy { it.toString() }
            .map { canonicalRoot.relativize(it).toString().replace('\\', '/') }
            .take(maxResults)
    }

    /** Split a glob pattern into the non-wildcard base directory and the glob part. */
    private fun splitGlobPattern(pattern: String): Pair<Path, String> {
        if (!pattern.contains("**") && !pattern.contains("*")) return canonicalRoot to pattern
        // Find the FIRST wildcard segment: everything before it is the base directory.
        // Split on '/' and '\\' manually — Path.of() rejects wildcard characters on Windows.
        val allParts = pattern.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val firstWildcard = allParts.indexOfFirst { it.contains("*") || it.contains("?") }
        val baseDir = if (firstWildcard > 0) {
            canonicalRoot.resolve(allParts.take(firstWildcard).joinToString("/"))
        } else {
            canonicalRoot
        }
        val globPart = if (firstWildcard >= 0) {
            allParts.drop(firstWildcard).joinToString("/")
        } else {
            allParts.joinToString("/")
        }
        return baseDir to globPart
    }

    private fun walkGlob(baseDir: Path, globPart: String, maxResults: Int): List<Path> {
        val pathMatcher = FileSystems.getDefault().getPathMatcher("glob:$globPart")
        // JDK glob semantics: "**/*" requires at least one directory level, so it silently
        // misses files directly under the base dir. Add a second matcher with the leading
        // "**/" stripped ("**" matches zero or more directories) to catch root-level files.
        val rootLevelMatcher = if (globPart.startsWith("**/")) {
            FileSystems.getDefault().getPathMatcher("glob:${globPart.removePrefix("**/")}")
        } else {
            null
        }
        val results = mutableListOf<Path>()

        Files.walkFileTree(baseDir, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != baseDir && dir.fileName.toString() in searchExcludedDirs) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = baseDir.relativize(file)
                if (pathMatcher.matches(rel) ||
                    (rootLevelMatcher != null && rootLevelMatcher.matches(rel)) ||
                    pathMatcher.matches(file.fileName)
                ) {
                    results.add(file)
                    if (results.size >= maxResults) return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                return FileVisitResult.SKIP_SUBTREE
            }
        })
        return results
    }

    /**
     * Search for files matching a glob pattern.
     * Supports ** for recursive matching.
     */
    suspend fun glob(pattern: String, maxResults: Int = MAX_GLOB_RESULTS): String {
        val (baseDir, globPart) = splitGlobPattern(pattern)
        if (!baseDir.exists()) return "Base directory not found: $baseDir"

        return try {
            val results = withContext(Dispatchers.IO) { walkGlob(baseDir, globPart, maxResults) }

            if (results.isEmpty()) {
                "No files matching '$pattern'"
            } else {
                buildString {
                    val truncated = results.size >= maxResults
                    appendLine("Found ${results.size}${if (truncated) "+" else ""} file(s) matching '$pattern':")
                    for (file in results.sortedBy { it.toString() }.take(200)) {
                        val rel = canonicalRoot.relativize(file)
                        val size = try { Files.size(file) } catch (_: Exception) { 0L }
                        appendLine("  $rel (${formatSize(size)})")
                    }
                    if (truncated) appendLine("  ... (results truncated at $maxResults)")
                }.trimEnd()
            }
        } catch (e: IOException) {
            errorResult("Glob failed for '$pattern': ${e.message}")
        }
    }

    /**
     * Search file contents for a regex pattern (like grep -r).
     */
    suspend fun grep(
        pattern: String,
        path: String = ".",
        filePattern: String = "*",
        ignoreCase: Boolean = false,
        maxResults: Int = 500,
    ): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        if (!resolved.exists()) return errorResult("Not found: $path")

        val regex = try {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (e: Exception) {
            return errorResult("Invalid regex pattern: $pattern — ${e.message}")
        }

        return try {
            val results = mutableListOf<String>()
            val pathMatcher = FileSystems.getDefault().getPathMatcher("glob:$filePattern")

            withContext(Dispatchers.IO) {
                Files.walkFileTree(resolved, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != resolved && dir.fileName.toString() in searchExcludedDirs) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (results.size >= maxResults) return FileVisitResult.TERMINATE
                        val ext = file.extension.lowercase()
                        if (ext in BINARY_EXTENSIONS) return FileVisitResult.CONTINUE
                        if (Files.size(file) > MAX_READ_SIZE_BYTES) return FileVisitResult.CONTINUE
                        if (!pathMatcher.matches(file.fileName)) return FileVisitResult.CONTINUE

                        try {
                            val lines = Files.readAllLines(file)
                            for ((i, line) in lines.withIndex()) {
                                if (regex.containsMatchIn(line)) {
                                    val rel = resolved.relativize(file)
                                    results.add("$rel:${i + 1}: ${line.trim().take(200)}")
                                    if (results.size >= maxResults) break
                                }
                            }
                        } catch (_: Exception) {}
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                })
            }

            if (results.isEmpty()) {
                "No matches for '$pattern' in $path"
            } else {
                buildString {
                    appendLine("Found ${results.size} match(es) for '$pattern':")
                    results.take(200).forEach { appendLine(it) }
                    if (results.size > 200) appendLine("  ... (${results.size - 200} more)")
                }.trimEnd()
            }
        } catch (e: IOException) {
            errorResult("Grep failed: ${e.message}")
        }
    }

    /**
     * Collect all text files under [path] (recursively) as relative-path →
     * content, skipping [searchExcludedDirs], binary extensions, and files over
     * [MAX_READ_SIZE_BYTES]. Returns an empty map when nothing readable is found
     * or the path is not a directory.
     *
     * Used by the multi-file live-template extraction (`coding.scaffoldFromExample`
     * with a directory path) and other whole-subtree operations.
     */
    suspend fun collectTextFiles(path: String = "."): Map<String, String> {
        val resolved = resolvePath(path) ?: return emptyMap()
        if (!resolved.isDirectory()) return emptyMap()

        val results = mutableMapOf<String, String>()
        try {
            withContext(Dispatchers.IO) {
                Files.walkFileTree(resolved, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir != resolved && dir.fileName.toString() in searchExcludedDirs) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val ext = file.extension.lowercase()
                        if (ext in BINARY_EXTENSIONS) return FileVisitResult.CONTINUE
                        if (ext.isNotEmpty() && ext !in SOURCE_EXTENSIONS) return FileVisitResult.CONTINUE
                        try {
                            if (Files.size(file) > MAX_READ_SIZE_BYTES) return FileVisitResult.CONTINUE
                            val content = Files.readString(file)
                            val rel = resolved.relativize(file).toString().replace('\\', '/')
                            results[rel] = content
                        } catch (_: Exception) {}
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                })
            }
        } catch (_: Exception) {}
        return results
    }

    /**
     * Get a unified diff between the snapshot and current content of a file.
     *
     * @param algorithm "myers" (default, fastest, edit-distance optimal) or
     *                  "patience" (anchors on unique lines — reads better for
     *                  code moves and repeated boilerplate).
     */
    suspend fun diff(path: String, algorithm: String = "myers"): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        val snapshot = snapshots[resolved] ?: return "No snapshot available for diff: $path"

        val current = try {
            if (resolved.exists()) withContext(Dispatchers.IO) { Files.readString(resolved) } else "(deleted)"
        } catch (_: Exception) { "(unreadable)" }

        val oldContent = snapshot.content ?: "(did not exist)"

        if (oldContent == current) return "No changes in $path"

        val oldLines = oldContent.lines()
        val newLines = current.lines()
        val edits = DiffEngine.diff(oldLines, newLines, algorithm)
        val unified = DiffEngine.toUnified(path, path, edits) ?: return "No changes in $path"
        return truncateForContext("diff $path ($algorithm)\n$unified", path = path)
    }

    /**
     * Get change summary since tracking started.
     *
     * Change tracking lives on this filesystem instance. Long-lived shared instances
     * (e.g. the session-less standalone coding shell) accumulate entries from every
     * caller, so a [maxAge] window (default 24h) filters out stale noise from other
     * callers/earlier sessions; hidden entries are counted and reported so agents know
     * the listing is windowed. `diff`/`revert` still see snapshots of any age.
     */
    fun changeSummary(maxAge: Duration = Duration.ofHours(24)): String {
        if (snapshots.isEmpty()) return "No changes tracked."
        val cutoff = System.currentTimeMillis() - maxAge.toMillis()
        val recent = snapshots.values.filter { it.trackedAtMillis >= cutoff }
        val hidden = snapshots.size - recent.size
        if (recent.isEmpty()) {
            return "No changes tracked in the last ${maxAge.toHours()}h ($hidden older snapshot(s) hidden)."
        }
        return buildString {
            appendLine("Changes tracked: ${changeCounter.get()} operations on ${recent.size} files" +
                if (hidden > 0) " (${hidden} older snapshot(s) hidden — age > ${maxAge.toHours()}h)" else "")
            for (snap in recent.sortedBy { it.path.toString() }) {
                val path = snap.path
                val current = try {
                    if (path.exists()) Files.readString(path) else "(deleted)"
                } catch (_: Exception) { "(unreadable)" }
                val status = when {
                    !snap.existed && path.exists() -> "created"
                    snap.existed && !path.exists() -> "deleted"
                    snap.content != current -> "modified"
                    else -> "unchanged"
                }
                val rel = canonicalRoot.relativize(path)
                appendLine("  $rel: $status")
            }
        }.trimEnd()
    }

    /**
     * Get the workspace root path.
     */
    fun getWorkspaceRoot(): String = canonicalRoot.toString()

    /**
     * Resolve a path against the workspace sandbox and return the absolute path string.
     *
     * Returns null when the path is blocked (external access without [allowExternalAccess],
     * or traversal outside [workspaceRoot]) — same policy as [readFile]/[writeFile].
     * Use this for tools that hand the path to third-party validators that do raw IO
     * (e.g. `ArtifactValidator.validatePlugin`), so they cannot escape the sandbox.
     */
    fun resolvePathString(path: String): String? = resolvePath(path)?.toString()

    /**
     * Detect the programming languages used in the workspace.
     */
    fun detectLanguages(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        try {
            Files.walkFileTree(canonicalRoot, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val ext = file.extension.lowercase()
                    val lang = when (ext) {
                        "kt", "kts" -> "Kotlin"
                        "java" -> "Java"
                        "rs" -> "Rust"
                        "py" -> "Python"
                        "js", "jsx", "mjs", "cjs" -> "JavaScript"
                        "ts", "tsx" -> "TypeScript"
                        "go" -> "Go"
                        "c", "h" -> "C"
                        "cpp", "cc", "cxx", "hpp", "hh" -> "C++"
                        "cs" -> "C#"
                        "rb" -> "Ruby"
                        "php" -> "PHP"
                        "swift" -> "Swift"
                        "sh", "bash" -> "Shell"
                        "sql" -> "SQL"
                        "html", "htm" -> "HTML"
                        "css", "scss", "less" -> "CSS"
                        "xml" -> "XML"
                        "json" -> "JSON"
                        "yaml", "yml" -> "YAML"
                        "md", "markdown" -> "Markdown"
                        "tf", "hcl" -> "Terraform"
                        "dockerfile" -> "Dockerfile"
                        else -> null
                    }
                    if (lang != null) {
                        counts[lang] = (counts[lang] ?: 0) + 1
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    return FileVisitResult.SKIP_SUBTREE
                }
            })
        } catch (_: Exception) {}

        return counts.toSortedMap()
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun resolvePath(path: String): Path? {
        val p = Path.of(path)
        val resolved = if (p.isAbsolute) {
            if (!allowExternalAccess && !p.startsWith(canonicalRoot)) {
                logger.warn("External path access denied: $path")
                return null
            }
            p.normalize()
        } else {
            canonicalRoot.resolve(path).normalize()
        }

        // Security: prevent path traversal outside workspace when external access disabled
        if (!allowExternalAccess && !resolved.startsWith(canonicalRoot)) {
            logger.warn("Path traversal blocked: $path resolves to $resolved")
            return null
        }

        return resolved
    }

    private fun snapshotFile(path: Path) {
        if (snapshots.containsKey(path)) return // already snapshotted
        val existed = path.exists()
        val content = if (existed && !path.isDirectory()) {
            try {
                val ext = path.extension.lowercase()
                if (ext !in BINARY_EXTENSIONS) Files.readString(path) else null
            } catch (_: Exception) { null }
        } else null
        val snapshot = FileSnapshot(path, existed, content, checksumString(content))
        snapshots[path] = snapshot
        persistSnapshot(snapshot)
    }

    /**
     * `sha256:<hex>` of a file's current content, or null when the file is
     * absent/binary. Feed it back as `write(..., expectedChecksum=…)` for
     * optimistic concurrency (plan P2.3/P2.5).
     */
    fun checksumOf(path: String): String? {
        val resolved = resolvePath(path) ?: return null
        if (!resolved.exists() || resolved.isDirectory()) return null
        if (resolved.extension.lowercase() in BINARY_EXTENSIONS) return null
        return runCatching { checksumString(Files.readString(resolved)) }.getOrNull()
    }

    private fun checksumString(content: String?): String {
        if (content == null) return "absent"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** Optimistic-concurrency guard: null when the current content matches [expected]. */
    private fun concurrencyViolation(resolved: Path, expected: String): String? {
        val actual = if (resolved.exists() && !resolved.isDirectory() && !isBinary(resolved)) {
            runCatching { checksumString(Files.readString(resolved)) }.getOrNull()
        } else if (!resolved.exists()) {
            "absent"
        } else {
            null
        }
        if (actual == null) return null // binary/unreadable: cannot verify, do not block
        if (actual == expected) return null
        val rel = runCatching { canonicalRoot.relativize(resolved).toString().replace('\\', '/') }
            .getOrDefault(resolved.toString())
        return "Refusing to write '$rel': content changed since it was read " +
            "(expected $expected, found $actual). Re-read the file and retry."
    }

    private fun isBinary(path: Path): Boolean = path.extension.lowercase() in BINARY_EXTENSIONS

    /** Persist one snapshot so a revert survives a restart (plan P2.3). */
    private fun persistSnapshot(snapshot: FileSnapshot) {
        if (!persistSnapshots) return
        runCatching {
            Files.createDirectories(snapshotDir)
            val node = snapshotMapper.createObjectNode()
            val rel = runCatching { canonicalRoot.relativize(snapshot.path).toString().replace('\\', '/') }
                .getOrDefault(snapshot.path.toString())
            node.put("path", rel)
            node.put("existed", snapshot.existed)
            node.put("checksum", snapshot.checksum)
            node.put("trackedAtMillis", snapshot.trackedAtMillis)
            snapshot.content?.let { node.put("content", it) }
            writeAtomic(snapshotDir.resolve(snapshotFileName(snapshot.path)), node.toString())
        }
    }

    private fun loadPersistedSnapshot(path: Path): FileSnapshot? {
        if (!persistSnapshots) return null
        return runCatching {
            val file = snapshotDir.resolve(snapshotFileName(path))
            if (!Files.isRegularFile(file)) return null
            val node = snapshotMapper.readTree(Files.readString(file))
            val contentNode = node.get("content")
            FileSnapshot(
                path = path,
                existed = node.get("existed")?.asBoolean() ?: false,
                content = contentNode?.takeIf { !it.isNull }?.asText(),
                checksum = node.get("checksum")?.asText() ?: "absent",
                trackedAtMillis = node.get("trackedAtMillis")?.asLong() ?: 0L,
            )
        }.getOrNull()
    }

    /** Stable per-path snapshot file name (SHA-256 of the workspace-relative path). */
    private fun snapshotFileName(path: Path): String {
        val rel = runCatching { canonicalRoot.relativize(path).toString().replace('\\', '/') }
            .getOrDefault(path.toString())
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rel.toByteArray(StandardCharsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) } + ".json"
    }

    /**
     * Atomically replace [target] with [content].
     *
     * Writes a sibling temp file and moves it into place: `ATOMIC_MOVE` on the
     * same filesystem is a rename, so readers see either the old or the new
     * file — never a truncated one. Falls back to a plain replace move where the
     * filesystem cannot do atomic moves (some container/FUSE mounts).
     *
     * The temp file is always cleaned up, including when the move throws — a
     * crashed write must not leave `.b4-*.tmp` litter in the repository.
     */
    private fun writeAtomic(
        target: Path,
        content: String,
        encoding: Charset = StandardCharsets.UTF_8,
    ) {
        val dir = target.toAbsolutePath().parent
            ?: throw IOException("Cannot resolve parent directory of $target")
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, ".b4-write-", ".tmp")
        try {
            Files.writeString(tmp, content, encoding, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(
                    tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /**
     * Split [text] into lines, dropping the empty element produced by a
     * trailing newline, so callers can re-join with [joinLines] without
     * inventing or losing a final line.
     */
    private fun lineList(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = text.lines().toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines
    }

    /**
     * Re-join [lines] using the ORIGINAL file's newline style and trailing
     * newline presence. Line-range edits must not rewrite a CRLF file as LF
     * (that produced whole-file diffs and broke CRLF-preserving tooling).
     */
    private fun joinLines(lines: List<String>, original: String): String {
        val eol = when {
            original.contains("\r\n") -> "\r\n"
            original.contains('\n') -> "\n"
            original.contains('\r') -> "\r"
            else -> "\n"
        }
        val trailingNewline = original.isEmpty() ||
            original.endsWith("\n") || original.endsWith("\r")
        if (lines.isEmpty()) return ""
        val body = lines.joinToString(eol)
        return if (trailingNewline) body + eol else body
    }

    private fun errorResult(message: String): String = "Error: $message"

    /**
     * Fold [content] to at most [maxChars] characters for LLM context.
     *
     * Keeps the first 60% and last 40% of the allowed budget, joined by an
     * omission marker that reports the total size and hints the agent to use
     * [readFileLines] for the skipped middle. No-op when content already fits.
     */
    private fun truncateForContext(
        content: String,
        maxChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
        path: String? = null,
    ): String {
        if (content.length <= maxChars) return content
        val headLen = (maxChars * 6 / 10)
        val tailLen = maxChars - headLen
        val head = content.substring(0, headLen)
        val tail = content.substring(content.length - tailLen)
        val omitted = content.length - maxChars
        val loc = if (path != null) " of '$path'" else ""
        val marker = "\n\n[… omitted $omitted chars (total ${content.length} chars$loc). " +
            "Use readFileLines(startLine, endLine) to inspect the middle section …]\n\n"
        return head + marker + tail
    }


    /**
     * Reject destructive ops on repo-governance files (VERSION, AGENTS.md, poms, ...)
     * or session-dynamically protected files ([protect]).
     * Returns an error string when the resolved path is protected, else null.
     */
    private fun protectionViolation(resolved: Path): String? {
        val rel = canonicalRoot.relativize(resolved).toString().replace('\\', '/')
        val protected = protectedFiles.any { p ->
            // Exact relative path, or basename match for single-file governance
            // entries (VERSION, AGENTS.md, CLAUDE.md). pom.xml is matched only by
            // exact root path so module poms stay editable.
            if (p == "pom.xml" || p.contains('/')) p == rel
            else rel.substringAfterLast('/') == p
        } || dynamicProtected.contains(rel)
        return if (protected) {
            "File is protected: $rel — delete/replace blocked. " +
                "Use explicit intent, human review, or coding.protect(path=..., on=false) for dynamic protections."
        } else null
    }

    // ------------------------------------------------------------------
    // Dynamic protection (session-level)
    // ------------------------------------------------------------------

    /**
     * Dynamically protected files added at runtime via [protect] (exact relative
     * paths, session-scoped). Repo-governance defaults ([protectedFiles]) cannot
     * be removed this way.
     */
    private val dynamicProtected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Add (or remove) a session-level protection on a specific file.
     *
     * @param path file path (resolved against the workspace root)
     * @param on true = protect (destructive ops blocked), false = unprotect
     * @return confirmation message
     */
    fun protect(path: String, on: Boolean = true): String {
        val resolved = resolvePath(path) ?: return errorResult("Path not resolved: $path")
        val rel = canonicalRoot.relativize(resolved).toString().replace('\\', '/')
        return if (on) {
            if (dynamicProtected.add(rel)) "✓ Protected $rel from destructive operations (session)"
            else "Already protected: $rel"
        } else {
            if (dynamicProtected.remove(rel)) "✓ Removed dynamic protection on $rel"
            else "Not dynamically protected: $rel"
        }
    }

    /** List the session-level dynamic protections. */
    fun protectedList(): String {
        if (dynamicProtected.isEmpty()) return "No dynamic protections."
        return buildString {
            appendLine("Dynamically protected (${dynamicProtected.size}):")
            dynamicProtected.sorted().forEach { appendLine("  $it") }
        }.trimEnd()
    }

    /**
     * Expand `$1`, `$2`, `${name}` capture-group references in a regex replacement
     * for a given [MatchResult] (Kotlin equivalent of java Matcher.appendReplacement).
     */
    private fun expandReplacement(match: MatchResult, replacement: String): String {
        val groupRef = Regex("""\$(?:\{([a-zA-Z_][a-zA-Z0-9_]*)\}|([1-9][0-9]*))""")
        return groupRef.replace(replacement) { m ->
            val name = m.groupValues[1]
            val number = m.groupValues[2]
            if (name.isNotEmpty()) {
                match.groups[name]?.value ?: ""
            } else {
                val idx = number.toInt()
                match.groupValues.getOrNull(idx) ?: ""
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

