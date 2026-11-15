package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Opportunistic ripgrep accelerator for [CodingAgentFileSystem.grep].
 *
 * ## Why narrowing instead of parsing matches
 *
 * A JVM line-by-line scan costs ~0.15 ms per warm file and 3-24 ms per cold one (antivirus
 * first touch), so a large tree takes seconds to minutes. ripgrep scans the same tree with
 * SIMD + memory mapping + a thread pool, but its output format, its regex dialect (Rust
 * `regex`) and its line handling differ from ours — parsing its match stream would mean
 * re-implementing the exact result shape that [CodingAgentFileSystem.grep] already produces.
 *
 * So ripgrep is used only to answer "**which files can possibly match**"
 * (`--files-with-matches --null`). The files it lists are then matched by the normal Kotlin
 * path, which keeps line numbers, the match window, non-UTF-8 decoding, sorting and the
 * `partial:`/`note:` footers identical with and without ripgrep. The JVM still reads only the
 * matching files, which are few.
 *
 * ## Safety rules

 * - **Only a conservative pattern dialect is delegated.** Rust `regex` rejects lookarounds,
 *   backreferences and possessive quantifiers — those make ripgrep exit 2 and we fall back.
 *   Constructs both engines accept with *different* meaning are the dangerous ones (a file
 *   ripgrep misses never reaches our matcher), so they are refused up front: `\p{…}`, `\uXXXX`,
 *   `\A`/`\z`/`\Z`, `\Q…\E`, POSIX classes and any alphabetic escape other than
 *   `\d \D \w \W \s \S \b \B \t \n \r \f`.
 * - **`(?m)` is prepended** because the Kotlin matcher applies the regex per line: without it
 *   `^`/`$` would only match at the file boundaries and ripgrep would miss later lines.
 * - **Every failure falls back** to the plain walk: not installed, disabled, exited non-0/1,
 *   timed out (partial list is kept but flagged), unparseable output.
 *
 * ## Test seam
 *
 * [commandRunner] replaces the real process for unit tests, the same way
 * `WorkspaceMirror.processRunner` does.
 */
object RipgrepSupport {

    private val logger = LoggerFactory.getLogger(RipgrepSupport::class.java)

    /** Points the accelerator at a specific binary (useful when `rg` is not on `PATH`). */
    const val ENV_RG_PATH = "BROWSER4_RG_PATH"

    /** `off`/`0`/`false`/`no` disables the accelerator entirely. */
    const val ENV_DISABLE = "BROWSER4_CODING_RIPGREP"

    /** How much of a call's budget ripgrep may spend; the matcher keeps the rest. */
    private const val NARROW_SHARE = 0.6

    /** Hard cap on ripgrep's own run, whatever the caller's budget is. */
    private const val MAX_NARROW_MILLIS = 60_000L

    /** Threads ripgrep may use; the server JVM also drives a browser. */
    private const val RG_THREADS = "4"

    /** Files whose content cannot be read by [CodingAgentFileSystem.grep] anyway. */
    private const val MAX_RG_FILESIZE = "5M"

    /** Result of one candidate-narrowing pass. */
    class Narrowing(
        /** Files ripgrep says contain the pattern (absolute, normalised). */
        val files: List<Path>,
        /** True when ripgrep was killed at the deadline, so [files] may be incomplete. */
        val timedOut: Boolean,
    )

    /** stdout/exit-code of one command, with the timeout flag. */
    class RunResult(val exitCode: Int, val stdout: ByteArray, val timedOut: Boolean)

    /**
     * Runs one command and returns its output, or null when the process could not be started.
     *
     * The real implementation is a [ProcessBuilder]; tests replace it to exercise the
     * narrowing, fallback and timeout paths without an installed `rg`.
     */
    fun interface CommandRunner {
        fun run(command: List<String>, workingDir: Path, timeoutMillis: Long): RunResult?
    }

    /** Escape hatch for operations and tests; initialized from [ENV_DISABLE]. */
    @Volatile
    var enabled: Boolean = System.getenv(ENV_DISABLE)?.lowercase() !in setOf("0", "false", "off", "no")

    /** Test seam; null means "use a real process". */
    @Volatile
    var commandRunner: CommandRunner? = null

    @Volatile
    private var cachedExecutable: String? = null

    @Volatile
    private var executableResolved = false

    private val defaultRunner = CommandRunner { command, workingDir, timeoutMillis ->
        try {
            val process = ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val buffer = ByteArrayOutputStream(1 shl 16)
            val reader = Thread({ process.inputStream.use { it.copyTo(buffer) } }, "b4-rg-reader")
            reader.isDaemon = true
            reader.start()
            val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(2_000)
            RunResult(
                exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
                stdout = buffer.toByteArray(),
                timedOut = !finished,
            )
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * True when a pattern is inside the dialect that both Java and Rust `regex` read the same
     * way. Deliberately conservative: a false "no" only costs the accelerator, a false "yes"
     * can hide a file from the matcher.
     */
    fun isSupportedPattern(pattern: String): Boolean {
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN_CHARS) return false
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '\\' -> {
                    if (i + 1 >= pattern.length) return false
                    if (pattern[i + 1] !in SAFE_ESCAPED_CHARS) return false
                    i += 2
                }

                '(' -> {
                    // (?i)/(?m)/lookarounds/atomic groups: dialect-specific.
                    if (i + 1 < pattern.length && pattern[i + 1] == '?') return false
                    i++
                }

                '[' -> {
                    // POSIX classes ([[:alpha:]]) and Unicode property classes ([[:...]]).
                    if (i + 1 < pattern.length && pattern[i + 1] == ':') return false
                    if (i + 1 < pattern.length && pattern[i + 1] == '^' && i + 2 < pattern.length && pattern[i + 2] == ':') return false
                    i++
                }

                else -> i++
            }
        }
        return true
    }

    /** True when the accelerator can run at all (binary present, not disabled). */
    fun isAvailable(): Boolean = enabled && (commandRunner != null || executableOrNull() != null)

    /**
     * Files under [root] whose bytes contain [pattern], or null when ripgrep cannot be used
     * and the caller must walk the tree itself.
     *
     * @param excludedDirNames directory names [CodingAgentFileSystem.grep] would not descend
     *   into; each one becomes a `--glob` exclusion so ripgrep does not enter that subtree.
     * @param budgetMillis wall-clock budget of the whole grep call; ripgrep gets part of it.
     */
    fun narrow(
        root: Path,
        pattern: String,
        ignoreCase: Boolean,
        excludedDirNames: Set<String>,
        budgetMillis: Long,
    ): Narrowing? {
        if (!isAvailable()) return null
        if (!isSupportedPattern(pattern)) {
            logger.debug("ripgrep narrowing skipped: pattern outside the safe dialect: {}", pattern)
            return null
        }
        val executable = commandRunner?.let { "rg" } ?: executableOrNull() ?: return null
        val timeout = narrowTimeout(budgetMillis)
        val command = buildCommand(executable, pattern, ignoreCase, excludedDirNames)
        val result = (commandRunner ?: defaultRunner).run(command, root, timeout) ?: run {
            logger.debug("ripgrep narrowing skipped: process could not be started")
            return null
        }
        // 0 = matches, 1 = no matches; anything else (2 = regex/IO error) must not narrow.
        if (!result.timedOut && result.exitCode !in 0..1) {
            logger.debug("ripgrep narrowing skipped: exit code {}", result.exitCode)
            return null
        }
        return Narrowing(parsePaths(root, result.stdout), result.timedOut)
    }

    /** The exact argv handed to ripgrep; exposed for tests that pin the contract. */
    fun buildCommand(
        executable: String,
        pattern: String,
        ignoreCase: Boolean,
        excludedDirNames: Set<String>,
    ): List<String> {
        val command = ArrayList<String>(16 + excludedDirNames.size)
        command += executable
        command += "--files-with-matches"   // only "which files", the JVM produces the result text
        command += "--null"                 // NUL-separated paths: a path may contain ':'
        command += "--text"                 // GBK/UTF-16 files are searched by the matcher, not skipped here
        command += "--no-ignore"            // the tool's own exclusion list applies, not .gitignore
        command += "--hidden"
        command += "--no-messages"
        command += "--max-filesize"
        command += MAX_RG_FILESIZE
        command += "--threads"
        command += RG_THREADS
        if (ignoreCase) command += "--ignore-case"
        for (name in excludedDirNames.sorted()) {
            command += "--glob"
            command += "!**/$name/**"
        }
        // `bin/` is skipped by [CodingAgentFileSystem] only when it is a build output; express
        // exactly that here so a repository-level `bin/` of scripts stays searchable.
        for (child in BUILD_OUTPUT_BIN_GLOBS) {
            command += "--glob"
            command += "!**/bin/$child/**"
        }
        command += "--regexp"
        command += MULTILINE_PREFIX + pattern
        command += "."
        return command
    }

    private fun narrowTimeout(budgetMillis: Long): Long {
        if (budgetMillis <= 0) return MAX_NARROW_MILLIS
        val share = (budgetMillis * NARROW_SHARE).toLong()
        return share.coerceIn(1L, MAX_NARROW_MILLIS)
    }

    private fun parsePaths(root: Path, stdout: ByteArray): List<Path> {
        if (stdout.isEmpty()) return emptyList()
        val text = String(stdout, StandardCharsets.UTF_8)
        val files = ArrayList<Path>(minOf(256, text.length / 8 + 1))
        for (raw in text.split('\u0000')) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val file = runCatching { root.resolve(name).normalize() }.getOrNull() ?: continue
            if (Files.isRegularFile(file)) files.add(file)
        }
        return files
    }

    private fun executableOrNull(): String? {
        if (executableResolved) return cachedExecutable
        synchronized(this) {
            if (executableResolved) return cachedExecutable
            val override = System.getenv(ENV_RG_PATH)?.trim()?.takeIf { it.isNotEmpty() }
            val candidate = override ?: PATH_CANDIDATES.firstOrNull { probe(it) }
            cachedExecutable = candidate
            executableResolved = true
            if (candidate == null) {
                logger.debug("ripgrep not found on PATH; coding.grep uses the built-in scanner")
            }
            return candidate
        }
    }

    /** `rg --version` is the cheapest reliable probe: a file on PATH may not be runnable. */
    private fun probe(executable: String): Boolean {
        val result = defaultRunner.run(listOf(executable, "--version"), Path.of(".").toAbsolutePath(), 5_000)
            ?: return false
        if (result.timedOut || result.exitCode != 0) return false
        return String(result.stdout, StandardCharsets.UTF_8).startsWith("ripgrep")
    }

    /** Resets the cached probe; tests only. */
    fun resetProbeForTests() {
        synchronized(this) {
            cachedExecutable = null
            executableResolved = false
        }
    }

    private const val MAX_PATTERN_CHARS = 500

    /** `(?m)` so `^`/`$` match line boundaries, exactly like the per-line Kotlin matcher. */
    private const val MULTILINE_PREFIX = "(?m)"

    private val SAFE_ESCAPED_CHARS: Set<Char> =
        setOf(
            'd', 'D', 'w', 'W', 's', 'S', 'b', 'B', 't', 'n', 'r', 'f',
            '\\', '.', '+', '*', '?', '(', ')', '[', ']', '{', '}', '|', '^', '$',
            '/', '-', '_', '=', '!', '<', '>', '@', '#', '&', ':', ';', ',', '"', '\'', ' ', '~', '`',
        )

    private val BUILD_OUTPUT_BIN_GLOBS = listOf("Debug", "Release", "x64", "x86", "classes")

    private val PATH_CANDIDATES: List<String> =
        if (System.getProperty("os.name").lowercase().contains("win")) listOf("rg", "rg.exe")
        else listOf("rg")
}
