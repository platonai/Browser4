package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Result category for a mirror entry that could not be made available.
 *
 * [kind] is a stable, machine-readable tag so callers can filter by failure
 * cause ("skipped because an ancestor module already covers this" vs "Windows
 * privilege") without parsing human-facing text.
 */
enum class MirrorFailureKind {
    /** The entry's parent directory did not exist and was not created by a
     *  prior module — the depth-order fix should have made this impossible,
     *  so seeing it means something else is wrong. */
    PARENT_MISSING,
    /** A junction/symlink already existed at this path (not the agent's own). */
    ALREADY_EXISTS,
    /** The OS denied the operation (typically Windows privilege for mklink). */
    PRIVILEGE,
    /** The source directory/file is not present on disk. */
    SOURCE_MISSING,
    /** The child process hung beyond the budget; we force-killed it. */
    TIMEOUT,
    /** Anything we couldn't classify — detail carries the message. */
    UNKNOWN,
}

/** One failed/unavailable mirror entry. */
data class MirrorFailure(
    val path: String,
    val kind: MirrorFailureKind,
    val detail: String,
)

/**
 * Result of one provisioning pass.
 *
 * v0.3 revision: [errors] kept for backward compatibility (legacy callers still
 * read it) but new callers should prefer [unavailable] — it pairs a stable
 * [MirrorFailureKind] with the human-readable detail, so the caller can filter
 * on cause instead of grepping text.
 */
data class MirrorReport(
    val sourceRepo: Path?,
    val linked: List<String> = emptyList(),
    val copied: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    /** Human-readable error strings, kept for v0.2 compatibility. */
    val errors: List<String> = emptyList(),
    /** Classified failures — new in v0.3, supersedes [errors] for consumers. */
    val unavailable: List<MirrorFailure> = emptyList(),
) {
    val changed: Boolean get() = linked.isNotEmpty() || copied.isNotEmpty()

    /** Whether the mirror has every declared module available — nothing is
     *  missing from a build standpoint. [unavailable] entries are non-fatal
     *  warnings (e.g., a Windows privilege-denied nested module) rather than
     *  hard failures unless the caller chooses to treat them as such. */
    val isHealthy: Boolean get() = unavailable.isEmpty() && errors.isEmpty()

    fun summary(): String = when {
        sourceRepo == null -> "no source checkout found — workspace left untouched"
        !changed && unavailable.isEmpty() -> "workspace already mirrors ${sourceRepo.fileName} (${skipped.size} entries)"
        else -> buildString {
            append("mirrored ${sourceRepo.fileName}: ")
            append(linked.size).append(" linked, ").append(copied.size).append(" copied")
            if (unavailable.isNotEmpty()) {
                append(", ").append(unavailable.size).append(" unavailable (")
                append(unavailable.joinToString("; ") { "${it.path}: ${it.kind.name.lowercase().replace('_', '-')}" })
                append(')')
            }
        }
    }
}

/**
 * Execute external processes (mklink, git, etc.) for [WorkspaceMirror].
 *
 * Extracted as an interface to make mklink-timeout testing possible: `mklink`
 * is a cmd.exe built-in that cannot be faked via PATH shims (PATH lookups go
 * through cmd.exe first). A test can inject a [ProcessRunner] that returns a
 * fake [Process] whose `waitFor` blocks forever and whose input stream emits
 * nothing — we then assert that the timeout path fires, the process is
 * destroyed, and [MirrorFailureKind.TIMEOUT] is recorded.
 *
 * Default is [RealProcessRunner] which wraps [ProcessBuilder].
 */
fun interface ProcessRunner {
    fun start(command: List<String>, directory: Path): Process

    companion object {
        val DEFAULT: ProcessRunner = RealProcessRunner()
    }
}

/** Default runner that shells out via [ProcessBuilder]. */
class RealProcessRunner : ProcessRunner {
    override fun start(command: List<String>, directory: Path): Process =
        ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
}

/**
 * Provisioning of an agent workspace that mirrors a Browser4 checkout.
 *
 * The CLI-engine agent workspace (`<app-data>/agent/<date>/<uuid>/workspace`) is
 * a scratch directory. When the task is a self-development task, coding tools
 * write repository-relative paths there (`browser4-coding/src/...`), so the
 * directory has to look like the repository: every reactor module visible, plus
 * the build entry files (`pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn`, `VERSION`,
 * `ROOT.md`).
 *
 * Before this existed, an agent that wanted to run Maven had to repair the
 * workspace itself — observed in a real run: it ran `mklink /J` for every
 * module, copied the root files, and even deleted/re-created the
 * `browser4-coding` junction. Doing it here is deterministic, happens before the
 * first tool call, and keeps the agent out of workspace topology.
 *
 * Behaviour:
 * - **Derived from the reactor, not hardcoded**: the module list comes from
 *   `<sourceRepo>/pom.xml` (`<module>` entries including `<profiles>`), so a
 *   new module is mirrored automatically.
 * - **v0.3 depth-sorted**: shallow modules are linked before their nested
 *   children so the parent directory always exists; this turns "child fails,
 *   parent later succeeds, child skipped" into a deterministic zero-error pass.
 * - **Idempotent and non-destructive**: an existing entry is never replaced,
 *   links are only created for missing paths, and the pass is a no-op once the
 *   workspace is complete.
 * - **Safe when there is nothing to mirror**: a real checkout (already a repo
 *   root) and a scratch directory with no detectable source repo are left
 *   untouched.
 */
object WorkspaceMirror {

    private val logger = LoggerFactory.getLogger(WorkspaceMirror::class.java)

    /** Root files Maven and the repo markers need in the workspace. */
    private val ROOT_FILES = listOf("pom.xml", "mvnw", "mvnw.cmd", "VERSION", "ROOT.md")

    /** Root directories linked as a whole (Maven wrapper config). */
    private val ROOT_DIRS = listOf(".mvn")

    private val IS_WINDOWS = System.getProperty("os.name").lowercase().contains("win")

    /** Default runner (override in tests). */
    @Volatile
    var processRunner: ProcessRunner = ProcessRunner.DEFAULT

    /** Timeout for one junction creation; Windows `mklink /J` never stalls in
     *  practice but a stale `cmd.exe` subprocess or antivirus hook can. */
    private val LINK_TIMEOUT = 30.seconds

    /**
     * The checkout the backend runs from (its working directory or a parent), or
     * null for a packaged runtime that is not inside a source tree.
     */
    fun defaultSourceRepo(): Path? = runCatching {
        CodingWorkspace.findRepoRootFrom(Path.of(System.getProperty("user.dir")))
    }.getOrNull()

    /**
     * Make [workspace] a complete mirror of [sourceRepo].
     *
     * @param workspace the agent workspace (may already contain a partial mirror)
     * @param sourceRepo the checkout to mirror; defaults to [defaultSourceRepo]
     */
    fun ensure(workspace: Path, sourceRepo: Path? = defaultSourceRepo()): MirrorReport {
        val source = sourceRepo?.toAbsolutePath()?.normalize() ?: return MirrorReport(null)
        val target = workspace.toAbsolutePath().normalize()
        if (!Files.isDirectory(source) || !Files.isDirectory(target)) {
            val missing = if (!Files.isDirectory(source)) source else target
            val err = MirrorFailure("(root)", MirrorFailureKind.SOURCE_MISSING, "not a directory: $missing")
            return MirrorReport(source, unavailable = listOf(err), errors = listOf("not a directory: $missing"))
        }
        // Never mirror a checkout onto itself, or into one of its own subdirectories.
        if (isSamePath(source, target) || target.startsWith(source)) {
            return MirrorReport(source)
        }
        if (!Files.isRegularFile(source.resolve("pom.xml"))) {
            return MirrorReport(null)
        }

        val linked = mutableListOf<String>()
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val unavailable = mutableListOf<MirrorFailure>()

        // v0.3: depth-sort modules so parents are linked before their children.
        val sortedModules = reactorModules(source).sortedBy { it.count { c -> c == '/' } }

        for (module in sortedModules) {
            val link = target.resolve(module)
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                skipped += module
                continue
            }
            // v0.3: ensure the parent directory exists (covers the "profile module
            // declares a nested child before the profile's parent module runs" case
            // as a safety net on top of depth sort).
            runCatching { Files.createDirectories(link.parent) }

            val failure = linkDirectory(source.resolve(module), link)
            if (failure != null) {
                unavailable += MirrorFailure(module, failure.kind, failure.detail)
                errors += "mklink failed for $module: ${failure.detail}"
            } else {
                linked += module
            }
        }
        for (dir in ROOT_DIRS) {
            val sourceDir = source.resolve(dir)
            if (!Files.isDirectory(sourceDir)) continue
            val link = target.resolve(dir)
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                skipped += dir
                continue
            }
            runCatching { Files.createDirectories(link.parent) }
            val failure = linkDirectory(sourceDir, link)
            if (failure != null) {
                unavailable += MirrorFailure(dir, failure.kind, failure.detail)
                errors += "mklink failed for $dir: ${failure.detail}"
            } else {
                linked += dir
            }
        }
        for (file in ROOT_FILES) {
            val sourceFile = source.resolve(file)
            if (!Files.isRegularFile(sourceFile)) continue
            val destination = target.resolve(file)
            if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                skipped += file
                continue
            }
            runCatching {
                Files.copy(sourceFile, destination, StandardCopyOption.COPY_ATTRIBUTES)
                copied += file
            }.onFailure { t ->
                val msg = t.message ?: "copy failed"
                unavailable += MirrorFailure(file, MirrorFailureKind.UNKNOWN, msg)
                errors += "$file: $msg"
            }
        }

        if (linked.isNotEmpty() || errors.isNotEmpty()) {
            logger.info(
                "workspace mirror {}: linked=[{}] copied=[{}] skipped={} unavailable={}",
                target,
                linked.joinToString(","),
                copied.joinToString(","),
                skipped.size,
                unavailable.joinToString("; ") { "${it.path}(${it.kind})" },
            )
        }
        return MirrorReport(source, linked, copied, skipped, errors, unavailable)
    }

    /** Top-level `<module>` entries of [sourceRepo]/pom.xml, in **depth-ascending**
     *  stable order (shallow modules first). This is the v0.3 fix: a profile's
     *  child module (e.g. `browser4-tests/pulsar-tests-common`) is no longer
     *  processed before the profile parent (`browser4-tests`) — the parent
     *  junction always exists by the time the child entry runs, so `mklink`
     *  never fails with "系统找不到指定的路径". */
    fun reactorModules(sourceRepo: Path): List<String> {
        val pom = sourceRepo.resolve("pom.xml")
        if (!Files.isRegularFile(pom)) return emptyList()
        val text = Files.readString(pom)
        val raw: List<String> = MODULE_TAG.findAll(text)
            .map { it.groupValues[1].trim().trimEnd('/') }
            .filter { it.isNotEmpty() && !it.startsWith("..") }
            .distinct()
            .toList()
        return raw.sortedBy { it.count { c -> c == '/' } }
    }

    /** Whether [workspace] already exposes every reactor module of [sourceRepo]. */
    fun isComplete(workspace: Path, sourceRepo: Path): Boolean {
        val modules = reactorModules(sourceRepo)
        if (modules.isEmpty()) return false
        return modules.all { Files.exists(workspace.resolve(it), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
    }

    /** Outcome of one [linkDirectory] call. Either `null` on success or a
     *  classified failure record. v0.3 replaces the old `String?` return. */
    private data class LinkOutcome(
        val kind: MirrorFailureKind,
        val detail: String,
    )

    /**
     * Link [sourceDir] as [link]. Returns [LinkOutcome] on failure, null on
     * success. A directory junction is used on Windows (no privilege needed);
     * a symbolic link elsewhere.
     *
     * v0.3 changes (from v0.2):
     * - **Real timeout**: we wait for the child process first, then read output.
     *   v0.2's `readText()` blocked until EOF so `waitFor(30s)` always observed
     *   an already-exited process — the timeout never fired.
     * - **Classified failure**: returns [LinkOutcome] with [MirrorFailureKind]
     *   instead of a human string; callers surface both.
     * - **Shared decoder**: uses [CodingAgentShell.Companion.decodeProcessOutput]
     *   so Windows OEM console output (cp936/GBK on Chinese systems) is decoded
     *   correctly instead of coming back as "ϵͳ�Ҳ��ָ����·����".
     */
    private fun linkDirectory(sourceDir: Path, link: Path): LinkOutcome? {
        if (!Files.isDirectory(sourceDir)) return LinkOutcome(
            MirrorFailureKind.SOURCE_MISSING,
            "source directory missing",
        )
        return runCatching {
            if (IS_WINDOWS) {
                val process = processRunner.start(
                    listOf("cmd", "/c", "mklink", "/J", link.toString(), sourceDir.toString()),
                    link.parent,
                )
                val exited = process.waitFor(LINK_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    return@runCatching LinkOutcome(
                        MirrorFailureKind.TIMEOUT,
                        "mklink hung beyond ${LINK_TIMEOUT.inWholeSeconds}s",
                    )
                }
                val output = process.inputStream.buffered().use { readAllBytesSafely(it) }
                    ?.let { CodingAgentShell.Companion.decodeProcessOutput(it) }
                    ?: ""
                val exitValue = process.exitValue()
                if (exitValue != 0) {
                    val kind = classifyMklinkFailure(output, exitValue)
                    return@runCatching LinkOutcome(kind, output.trim().ifBlank { "exit=$exitValue" })
                }
            } else {
                Files.createSymbolicLink(link, sourceDir)
            }
            null as LinkOutcome?
        }.getOrElse { LinkOutcome(MirrorFailureKind.UNKNOWN, it.message ?: "link failed") }
    }

    /** Drain the child process output stream to bytes, safely terminating on
     *  timeout-driven process death (v0.2's `readText()` would block forever
     *  after `destroyForcibly()` because some JDK versions leave the pipe open
     *  until the OS reaps the handle). Returns null on read failure. */
    private fun readAllBytesSafely(input: InputStream): ByteArray? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var n: Int
        while (input.read(buf).also { n = it } > 0) {
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }.getOrNull()

    /** Classify a failed `mklink /J` invocation from its human-readable output.
     *  The exact message depends on the Windows language and locale, so we match
     *  substrings rather than equality. Unknown text lands in [UNKNOWN]. */
    private fun classifyMklinkFailure(output: String, exitCode: Int): MirrorFailureKind {
        val lower = output.lowercase()
        return when {
            exitCode != 0 && lower.contains("privilege") -> MirrorFailureKind.PRIVILEGE
            exitCode != 0 && (lower.contains("already exists") || lower.contains("already")) ->
                MirrorFailureKind.ALREADY_EXISTS
            exitCode != 0 && (lower.contains("cannot find") || lower.contains("not found") ||
                lower.contains("指定的路径") || lower.contains("找不到")) -> MirrorFailureKind.PARENT_MISSING
            exitCode != 0 -> MirrorFailureKind.UNKNOWN
            else -> MirrorFailureKind.UNKNOWN
        }
    }

    private fun isSamePath(a: Path, b: Path): Boolean =
        if (IS_WINDOWS) a.toString().equals(b.toString(), ignoreCase = true) else a == b

    private val MODULE_TAG = Regex("<module>\\s*([^<]+?)\\s*</module>")
}
