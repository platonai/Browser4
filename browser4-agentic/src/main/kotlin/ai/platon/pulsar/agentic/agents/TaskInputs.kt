package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.SensitivePaths
import org.slf4j.LoggerFactory
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
 *
 * The import runs under **every** access preset, `read` included: `.b4inputs/` is the
 * platform's read-only-for-the-model sandbox, not the user's files. A read-only task that
 * cannot receive its inputs would simply fail, and the copy mutates nothing the preset
 * protects.
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
     * A URL scheme anywhere in a token.
     *
     * [URL_SCHEME] is anchored, which is enough for a whole token but not for one a Chinese
     * sentence glued to the preceding word (`… 见https://host/a/b.csv`): without this the
     * reference would look like a relative path.
     */
    private val URL_IN_TEXT = Regex("""(?:https?|file|ftp|data)://""", RegexOption.IGNORE_CASE)

    /**
     * Characters that end a path, as a Java regex character-class body: whitespace plus the
     * punctuation a Windows path may never contain (`\ / : * ? " < > |`) and the separators this
     * file has always split on (`,`, `;`, quotes and brackets).
     *
     * `\s` is ASCII-only in Java's default mode, so `\p{Z}` adds the Unicode separators,
     * U+3000 (ideographic space) included.
     */
    private const val PATH_STOP_CHARS = """\s\p{Z}"'`()\[\]{}<>|,;"""

    /**
     * The punctuation a Chinese sentence uses mid-line: `…需要提取以下字段：D:\Backup\…`.
     *
     * It separates a reference from the prose around it, but it is also legal *inside* a file
     * name, so it is a separator for the sentence-level split and merely a boundary (not a stop)
     * for the embedded scans.
     */
    private const val CJK_PUNCTUATION = "，。、：；！？（）【】《》「」『』“”‘’"

    /** Punctuation trimmed from the end of a candidate (`D:\x.xlsx）` → `D:\x.xlsx`). */
    private const val TRAILING_PUNCTUATION = ".,:)]}。，、：；！？）】》」』”’"

    /** Sentence-level separators: the ASCII stop characters plus [CJK_PUNCTUATION]. */
    private const val SENTENCE_STOP_CHARS = PATH_STOP_CHARS + CJK_PUNCTUATION

    /** Sentence-level split: what an instruction glues to a reference, wide. */
    private val TOKEN_SEPARATORS = Regex("[$SENTENCE_STOP_CHARS]+")

    /**
     * Path-level split: the same but *without* [CJK_PUNCTUATION], so a reference whose name
     * contains a full-width comma or colon stays whole (`docs/a，b.md`). It runs after the wide
     * split and can only add candidates, never remove one.
     */
    private val SEGMENT_SEPARATORS = Regex("[$PATH_STOP_CHARS]+")

    /**
     * A quoted reference — the only way a path containing spaces survives the split:
     * `'C:\My Docs\fields.xlsx'`, `"…"`, `“…”`, `‘…’`.
     *
     * Shell quoting is stripped before the instruction reaches the backend, so the quotes have to
     * be part of the task text: `agent run "… in 'D:\My Docs\a.xlsx'"`.
     */
    private val QUOTED_SPAN = Regex("""["“]([^"”]+)["”]|['‘]([^'’]+)['’]""")

    /**
     * A local `file:` URL, capturing the path *after* the authority marker.
     *
     * The scheme is `file:` + `//` + an absolute path, so `file:///home/me/x` captures
     * `/home/me/x` and `file:///D:/x` captures `/D:/x`. `file://host/share` has no leading slash
     * and is therefore not matched — a remote host is not a local file.
     */
    private val FILE_URL = Regex("""^file://(/.*)$""", RegexOption.IGNORE_CASE)

    /**
     * An absolute Windows path embedded in a longer token.
     *
     * Fallback for a reference that is not its own whitespace-delimited token: it only *finds*
     * the reference, [accept] still applies every rule, and [resolveSource] still requires an
     * existing regular file. The lookbehind keeps a scheme (`https://`) from being read as `s:/…`.
     */
    private val EMBEDDED_WINDOWS_PATH = Regex("""(?<![A-Za-z])[A-Za-z]:[\\/][^$PATH_STOP_CHARS]+""")

    /**
     * An absolute POSIX path embedded in a longer token.
     *
     * The lookbehind demands a real boundary before the leading `/`, so `https://host/x` and
     * `a/b.txt` do not turn into `/host/x` and `/b.txt`.
     */
    private val EMBEDDED_POSIX_PATH =
        Regex("""(?:^|(?<=[$SENTENCE_STOP_CHARS]))(/[^$PATH_STOP_CHARS]+)""")

    private val logger = LoggerFactory.getLogger(TaskInputs::class.java)

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
        // Resolved (real) paths already handled, so one file named twice — absolutely and
        // relatively, say — is copied once instead of landing as `fields.xlsx` and
        // `fields-2.xlsx`, which reads to the model like two different inputs.
        val usedSources = mutableSetOf<Path>()
        val candidates = extractCandidates(instruction)
        var totalBytes = 0L

        for (reference in candidates) {
            if (copied.size >= MAX_INPUTS || totalBytes >= MAX_TOTAL_BYTES) {
                logger.info(
                    "Task input skipped: {} (limit reached: {} of {} files, {} of {} bytes)",
                    reference, copied.size, MAX_INPUTS, totalBytes, MAX_TOTAL_BYTES,
                )
                break
            }
            val source = resolveSource(reference, ws, searchRoots)
            if (source == null) {
                // Debug: most candidates are prose that merely looks path-like.
                logger.debug("Task input not resolved: {}", reference)
                continue
            }
            // Compare on the real path: a junction, a `..` or a second spelling of the same
            // location must not become a second copy.
            val canonical = runCatching { source.toRealPath() }.getOrDefault(source.toAbsolutePath().normalize())
            if (!usedSources.add(canonical)) {
                logger.debug("Task input already handled, copying once: {}", source)
                continue
            }
            if (SensitivePaths.isSensitive(source, sensitiveRoots)) {
                logger.info("Task input refused: {} is a credential location", source)
                continue
            }
            val size = runCatching { Files.size(source) }.getOrDefault(-1L)
            if (size <= 0 || size > MAX_FILE_BYTES || totalBytes + size > MAX_TOTAL_BYTES) {
                logger.info("Task input skipped: {} ({} bytes; per-file limit {} B, total limit {} B)", source, size, MAX_FILE_BYTES, MAX_TOTAL_BYTES)
                continue
            }
            val name = uniqueName(source.fileName?.toString().orEmpty().ifEmpty { "input" }, usedNames)
            val target = ws.resolve(INPUT_DIR).resolve(name)
            val ok = runCatching {
                Files.createDirectories(target.parent)
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
            if (!ok) {
                logger.warn("Task input could not be copied: {} → {}", source, target)
                continue
            }
            usedNames += name
            totalBytes += size
            copied += MaterializedInput(reference, "$INPUT_DIR/$name", size)
        }

        if (copied.isEmpty() && candidates.isNotEmpty()) {
            // One line, so "the model never saw the file" is diagnosable without debug logging:
            // the import ran, looked at N references, and none of them was a readable file.
            logger.info("No task input materialized: {} candidate reference(s) in the instruction", candidates.size)
        }

        val finalInstruction = if (copied.isEmpty()) instruction else instruction + renderNote(copied)
        return MaterializedInputs(copied, finalInstruction)
    }

    /**
     * Path-like tokens of [instruction], in order and deduplicated.
     *
     * Four passes, because a reference is not always its own whitespace-delimited token:
     *
     * 1. quoted spans — the only form that survives a path containing spaces;
     * 2. the sentence split, which separates prose from a reference written without a space
     *    (`…需要提取以下字段：D:\Backup\…`, `读取 docs/config.md，然后总结`);
     * 3. the path split, which keeps a name containing full-width punctuation whole
     *    (`docs/a，b.md`);
     * 4. embedded absolute paths glued into a run of text, for a separator nobody listed.
     *
     * Every pass feeds the same [accept] filter, so prose, URLs, `@file` CLI arguments and glob
     * patterns stay out whichever pass found them, and a pass can only add candidates.
     */
    internal fun extractCandidates(instruction: String): List<String> {
        val found = LinkedHashSet<String>()
        QUOTED_SPAN.findAll(instruction).forEach {
            accept(it.groupValues[1].ifEmpty { it.groupValues[2] }, found)
        }
        instruction.split(TOKEN_SEPARATORS).forEach { accept(it, found) }
        instruction.split(SEGMENT_SEPARATORS).forEach { accept(it, found) }
        EMBEDDED_WINDOWS_PATH.findAll(instruction).forEach { accept(it.value, found) }
        EMBEDDED_POSIX_PATH.findAll(instruction).forEach { accept(it.groupValues[1], found) }
        return found.toList()
    }

    /**
     * Adds [raw] to [found] when it looks like a readable input reference.
     *
     * Conservative on purpose: a token must look like a path — absolute (Windows or
     * POSIX), or contain a separator **and** end with an extension — so prose, URLs,
     * `@file` CLI arguments and glob patterns are not mistaken for inputs.
     */
    private fun accept(raw: String, found: MutableSet<String>) {
        val token = stripFileUrl(raw).trim().trimEnd { it in TRAILING_PUNCTUATION }
        if (token.length < 3 || token.length > 300) return
        if (token.startsWith("@") || token.startsWith("-")) return
        if (URL_SCHEME.containsMatchIn(token) || URL_IN_TEXT.containsMatchIn(token)) return
        if (token.contains('*') || token.contains('?')) return
        if (!token.contains('/') && !token.contains('\\')) return
        val absolute = WINDOWS_ABSOLUTE.matches(token) || (token.startsWith("/") && !token.startsWith("//"))
        val lastSegment = token.substringAfterLast('/').substringAfterLast('\\')
        val hasExtension = lastSegment.contains('.') && !lastSegment.endsWith(".")
        if (absolute || hasExtension) found += token
    }

    /**
     * `file:///D:/x.xlsx` / `file:///home/me/x.xlsx` → the local path it names.
     *
     * Only the form with a path after the authority marker is local; `file://host/share` names a
     * remote host, stays a URL, and is therefore refused by the guard above. Percent-encoding is
     * not decoded (`%20` stays `%20`).
     */
    private fun stripFileUrl(raw: String): String {
        val local = FILE_URL.find(raw.trim())?.groupValues?.get(1) ?: return raw
        // A Windows file URL keeps the slash before the drive: /D:/x → D:/x.
        return if (local.length >= 3 && local[2] == ':') local.substring(1) else local
    }

    /**
     * Resolve [reference] against the workspace and then [searchRoots].
     *
     * Returns null when the reference is already readable from the workspace (nothing to
     * copy — this is what keeps the coding mirror from being duplicated) or when it does
     * not resolve to a regular file.
     */
    private fun resolveSource(reference: String, workspace: Path, searchRoots: List<Path>): Path? {
        val expanded = expandHome(reference)
        val path = runCatching { Path.of(expanded) }.getOrNull() ?: return null
        if (!path.isAbsolute) {
            // A workspace-relative form that already exists needs no copy (the coding
            // mirror, or a file the task itself wrote). Resolving an absolute reference
            // against the workspace would return the reference itself, so this check is
            // relative-only on purpose.
            val insideWorkspace = runCatching { workspace.resolve(expanded).normalize() }.getOrNull()
            if (insideWorkspace != null && Files.isRegularFile(insideWorkspace)) return null
        }
        if (path.isAbsolute) {
            return runCatching {
                if (Files.isRegularFile(path)) path.toAbsolutePath().normalize() else null
            }.getOrNull()
        }
        for (root in searchRoots) {
            val candidate = runCatching {
                root.toAbsolutePath().normalize().resolve(expanded).normalize()
            }.getOrNull() ?: continue
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    /**
     * `~/x` and `~\x` mean the home directory, as a shell would read them.
     *
     * `~user/x` is left untouched on purpose: resolving another account's home would need a
     * password-database lookup, and an input the task cannot name exactly should be copied
     * explicitly instead of guessed.
     */
    internal fun expandHome(reference: String): String {
        if (!reference.startsWith("~")) return reference
        if (reference.length > 1 && reference[1] != '/' && reference[1] != '\\') return reference
        val home = runCatching { System.getProperty("user.home") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return reference
        return home + reference.substring(1)
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
