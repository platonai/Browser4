package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.SensitivePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * One file a task instruction referenced outside the workspace and that was copied in.
 *
 * @property referenced the path exactly as the task spelled it (kept for the model-facing note)
 * @property workspacePath workspace-relative path the model must read instead
 * @property bytes size of the copied file
 */
data class MaterializedInput(
    val referenced: String,
    val workspacePath: String,
    val bytes: Long,
)

/**
 * Outcome of [TaskInputs.materialize].
 *
 * @property inputs the copied inputs (empty when nothing was materialized)
 * @property instruction the instruction to hand the model: the original text, plus a
 *   mapping note when [inputs] is not empty
 */
data class MaterializedInputs(
    val inputs: List<MaterializedInput>,
    val instruction: String,
) {
    val isEmpty: Boolean get() = inputs.isEmpty()
}

/**
 * Copies files a task instruction references — but that live outside the agent
 * workspace — into `<workspace>/.b4inputs/`, and renders the mapping the model needs
 * to read them.
 *
 * Why this exists: a browsing task runs in an isolated per-task workspace, so
 * `coding.read` on a repository-relative path (or any absolute path) is refused with
 * `PATH_DENIED`. A real task — "…the fields to collect are listed in
 * browser4-tests/…/asin_field_description.xlsx" — therefore burned its first ten
 * minutes hunting a file it was never allowed to read (2026-10-28 supervision run).
 * Copying the inputs in and telling the model the workspace path keeps the workspace
 * boundary intact while making the task doable.
 *
 * Deliberate limits: regular files only; at most [MAX_INPUTS] files, at most
 * [MAX_FILE_BYTES] each and [MAX_TOTAL_BYTES] in total; credential locations
 * ([ai.platon.pulsar.coding.CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS]) are never
 * copied; a path already readable from the workspace (including the coding mirror) is
 * left alone; a reference that does not resolve is ignored — the model still learns
 * about it when the read fails.
 */
object TaskInputs {
    /** Workspace-relative directory that receives the copied inputs. */
    const val INPUT_DIR = ".b4inputs"

    /** Hard cap on copied inputs per run. */
    const val MAX_INPUTS = 20

    /** A single larger file is not copied (the note would lie about what is available). */
    const val MAX_FILE_BYTES = 10L * 1024 * 1024

    /** Cumulative cap for one run. */
    const val MAX_TOTAL_BYTES = 25L * 1024 * 1024

    private val WINDOWS_ABSOLUTE = Regex("""^[A-Za-z]:[\\/].+""")

    private val URL_SCHEME = Regex("""^(?:https?|file|ftp|data):""", RegexOption.IGNORE_CASE)

    /**
     * Materialize every readable input [instruction] references.
     *
     * @param instruction the task text as the user wrote it
     * @param workspace the agent workspace root; `.b4inputs/` is created under it
     * @param searchRoots directories a relative reference is resolved against, in order
     *   (the repository root, then the backend working directory)
     * @param sensitiveRoots locations that must never be copied; defaults to the
     *   credential locations the coding tools and the shell refuse
     */
    fun materialize(
        instruction: String,
        workspace: Path,
        searchRoots: List<Path>,
        sensitiveRoots: List<Path> = SensitivePaths.roots(CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS),
    ): MaterializedInputs {
        val ws = workspace.toAbsolutePath().normalize()
        val copied = mutableListOf<MaterializedInput>()
        val usedNames = mutableSetOf<String>()
        var totalBytes = 0L

        for (reference in extractCandidates(instruction)) {
            if (copied.size >= MAX_INPUTS || totalBytes >= MAX_TOTAL_BYTES) break
            val source = resolveSource(reference, ws, searchRoots) ?: continue
            if (SensitivePaths.isSensitive(source, sensitiveRoots)) continue
            val size = runCatching { Files.size(source) }.getOrDefault(-1L)
            if (size <= 0 || size > MAX_FILE_BYTES || totalBytes + size > MAX_TOTAL_BYTES) continue
            val name = uniqueName(source.fileName?.toString().orEmpty().ifEmpty { "input" }, usedNames)
            val target = ws.resolve(INPUT_DIR).resolve(name)
            val ok = runCatching {
                Files.createDirectories(target.parent)
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
            if (!ok) continue
            usedNames += name
            totalBytes += size
            copied += MaterializedInput(reference, "$INPUT_DIR/$name", size)
        }

        val finalInstruction = if (copied.isEmpty()) instruction else instruction + renderNote(copied)
        return MaterializedInputs(copied, finalInstruction)
    }

    /**
     * Path-like tokens of [instruction], in order and deduplicated.
     *
     * Conservative on purpose: a token must look like a path — absolute (Windows or
     * POSIX), or contain a separator **and** end with an extension — so prose, URLs,
     * `@file` CLI arguments and glob patterns are not mistaken for inputs.
     */
    internal fun extractCandidates(instruction: String): List<String> {
        val tokens = instruction.split(Regex("""[\s,;'"`()\[\]{}<>|]+"""))
        val found = LinkedHashSet<String>()
        for (raw in tokens) {
            val token = raw.trim().trimEnd('.', ':', ')', ']', '}')
            if (token.length < 3 || token.length > 300) continue
            if (token.startsWith("@") || token.startsWith("-")) continue
            if (URL_SCHEME.containsMatchIn(token)) continue
            if (token.contains('*') || token.contains('?')) continue
            if (!token.contains('/') && !token.contains('\\')) continue
            val absolute = WINDOWS_ABSOLUTE.matches(token) || (token.startsWith("/") && !token.startsWith("//"))
            val lastSegment = token.substringAfterLast('/').substringAfterLast('\\')
            val hasExtension = lastSegment.contains('.') && !lastSegment.endsWith(".")
            if (absolute || hasExtension) found += token
        }
        return found.toList()
    }

    /**
     * Resolve [reference] against the workspace and then [searchRoots].
     *
     * Returns null when the reference is already readable from the workspace (nothing to
     * copy — this is what keeps the coding mirror from being duplicated) or when it does
     * not resolve to a regular file.
     */
    private fun resolveSource(reference: String, workspace: Path, searchRoots: List<Path>): Path? {
        val path = runCatching { Path.of(reference) }.getOrNull() ?: return null
        if (!path.isAbsolute) {
            // A workspace-relative form that already exists needs no copy (the coding
            // mirror, or a file the task itself wrote). Resolving an absolute reference
            // against the workspace would return the reference itself, so this check is
            // relative-only on purpose.
            val insideWorkspace = runCatching { workspace.resolve(reference).normalize() }.getOrNull()
            if (insideWorkspace != null && Files.isRegularFile(insideWorkspace)) return null
        }
        if (path.isAbsolute) {
            return runCatching {
                if (Files.isRegularFile(path)) path.toAbsolutePath().normalize() else null
            }.getOrNull()
        }
        for (root in searchRoots) {
            val candidate = runCatching {
                root.toAbsolutePath().normalize().resolve(reference).normalize()
            }.getOrNull() ?: continue
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    /** `name`, or `name-2`, `name-3`, … when two references share a file name. */
    private fun uniqueName(name: String, used: Set<String>): String {
        if (name !in used) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while ("$base-$index$ext" in used) index++
        return "$base-$index$ext"
    }

    private fun renderNote(inputs: List<MaterializedInput>): String = buildString {
        appendLine()
        appendLine()
        appendLine("## Task inputs copied into your workspace")
        appendLine()
        appendLine("These files are referenced by the task but live outside your workspace, so they were")
        appendLine("copied into `$INPUT_DIR/` before the run. Read them from these workspace-relative paths:")
        appendLine()
        inputs.forEach { appendLine("- `${it.referenced}` → `${it.workspacePath}` (${formatBytes(it.bytes)})") }
        appendLine()
        appendLine("Do not retry the original paths. Binary inputs (.xlsx, .pdf, …) still need a script to read.")
        appendLine("These copies are INPUTS, not deliverables: do not list them in filesChanged or deletedFiles.")
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
