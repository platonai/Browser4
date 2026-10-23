package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * A single Kotlin/Java compiler error or warning with source location.
 *
 * [file] is workspace-relative when [MavenBuildSupport.parseDiagnostics] was
 * given a workspace root (e.g. `browser4-rest/src/main/kotlin/.../Foo.kt`), so
 * the agent can open it directly instead of guessing which module a bare
 * basename belongs to (plan P1.4).
 */
data class BuildDiagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: String, // "error" | "warning"
    val message: String,
) {
    /** `path:line:col` — the form editors and agents can jump to. */
    val location: String get() = if (line > 0) "$file:$line:$column" else file

    /** One-line rendering used in verification evidence. */
    fun format(): String = "[$severity] $location — $message"
}

/**
 * Result of a Maven build: exit code, raw output, parsed diagnostics.
 */
data class BuildResult(
    val module: String,
    val goals: String,
    val exitCode: Int,
    val output: String,
    val diagnostics: List<BuildDiagnostic>,
    val timedOut: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut

    /** First [limit] diagnostics as a compact multi-line string. */
    fun diagnosticsSummary(limit: Int = 30): String =
        diagnostics.take(limit).joinToString("\n") { it.format() }
}

/**
 * Maven build support tailored to the Browser4 multi-module repository.
 *
 * Wraps `mvn -pl <module> -am <goals>` behind the coding shell's command
 * allow-list (mvn is a DEV command), returns a structured [BuildResult], and
 * parses Kotlin/Java compiler errors into [BuildDiagnostic]s so the agent does
 * not have to read raw Maven logs.
 *
 * This is the "compiler passthrough" half of the Kotlin diagnostics story —
 * LSP servers for Kotlin (JDTLS) are heavy; for quick feedback, compiling the
 * affected module and parsing the errors is cheap and dependency-free.
 */
class MavenBuildSupport {

    companion object {
        private val logger = LoggerFactory.getLogger(MavenBuildSupport::class.java)

        /** Maven wrapper file names, Windows first. */
        private fun wrapperNames(): List<String> {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            return if (isWindows) listOf("mvnw.cmd", "mvnw.bat", "mvnw") else listOf("mvnw")
        }

        /**
         * Locate the Maven launcher and return an **absolute** path when the
         * repository wrapper exists.
         *
         * Prefer the repository Maven wrapper (`mvnw`/`mvnw.cmd`) over a bare
         * `mvn` on PATH: developers commonly have only the wrapper, and the
         * repository pins the Maven version it expects. Windows shells cannot
         * execute the Unix `mvnw` script, so `mvnw.cmd` wins there.
         *
         * The returned path is absolute (quoted when it contains spaces): a
         * bare `mvnw.cmd` resolved against the *module* directory does not
         * exist, and a relative name is then rejected by the command allow-list
         * on Windows — the eval's K4 defect. See plan P0.4.
         */
        fun mavenLauncher(shell: CodingAgentShell, workingDir: String? = null): String =
            // A mocked / foreign shell can hand back an unusable Path (a relaxed
            // mock's `toFile()` returns a File with a null path, and
            // `File(parent, name)` then throws). Locating the launcher must never
            // fail a build: fall back to `mvn` on PATH instead.
            runCatching { findMavenLauncher(shell, workingDir) }.getOrNull() ?: "mvn"

        private fun findMavenLauncher(shell: CodingAgentShell, workingDir: String?): String? {
            val candidates = linkedSetOf<File>()
            fun addDir(dir: File?) {
                var d = dir
                var hops = 0
                while (d != null && hops <= 3) {
                    if (!runCatching { d.isDirectory }.getOrDefault(false)) break
                    if (!candidates.add(d)) break
                    d = runCatching { d.parentFile }.getOrNull()
                    hops++
                }
            }
            if (!workingDir.isNullOrBlank()) addDir(File(workingDir))
            runCatching { shell.canonicalWorkspaceDir.toFile() }.getOrNull()?.let { addDir(it) }

            for (dir in candidates) {
                for (name in wrapperNames()) {
                    val candidate = runCatching { File(dir, name) }.getOrNull() ?: continue
                    if (runCatching { candidate.isFile }.getOrDefault(false)) {
                        return quoteIfNeeded(candidate.absolutePath)
                    }
                }
            }
            // No wrapper in the workspace: fall back to Maven on PATH. On
            // Windows `cmd.exe` resolves `mvn` through PATHEXT to `mvn.cmd`.
            return null
        }

        /** Quote a path for `sh -c` / `cmd /c` when it contains whitespace. */
        fun quoteIfNeeded(path: String): String =
            if (path.any { it.isWhitespace() }) "\"$path\"" else path

        /**
         * The shell's workspace root, or null when it cannot be resolved (a
         * mocked/foreign shell, a deleted directory). Diagnostics still parse;
         * they just stay absolute.
         */
        private fun workspaceRoot(shell: CodingAgentShell): Path? =
            runCatching { shell.canonicalWorkspaceDir }.getOrNull()

        /**
         * Parse Kotlin/Java compiler errors from Maven output.
         *
         * Handles the dominant formats:
         * - Kotlin compiler: `e: file:///D:/.../Foo.kt:12:5 Unresolved reference 'bar'`
         *   (`w:` is a warning)
         * - Java compiler: `[ERROR] /D:/.../Foo.java:[12,5] cannot find symbol`
         * - Plain: `D:\...\Foo.kt:12:5: error: ...`
         * - Legacy kotlin-maven-plugin: `[ERROR] error: file:///...:12:5 msg`
         *
         * Paths are normalized to workspace-relative when [workspaceRoot] is
         * supplied; otherwise the raw path is kept (still far more useful than
         * a bare basename).
         */
        fun parseDiagnostics(output: String, workspaceRoot: Path? = null): List<BuildDiagnostic> {
            val diags = mutableListOf<BuildDiagnostic>()
            // A mocked/foreign shell can hand back an unusable path; diagnostics
            // must still parse (unrelativized) rather than blow up the build call.
            val root = runCatching { workspaceRoot?.toAbsolutePath()?.normalize() }.getOrNull()

            for (rawLine in output.lineSequence()) {
                val line = rawLine.trimEnd()
                if (line.isBlank()) continue
                parseLine(line, root)?.let { diags += it }
            }
            return diags
        }

        /** Kotlin compiler severity prefix (`e:` / `w:`). */
        private val KOTLIN_PREFIX = Regex("""^\s*([ew]):\s+(.*)$""")

        /** `file:///path/Foo.kt:12:5 message` (kotlin form, path after optional e:/w:). */
        private val KOTLIN_FILE_URL = Regex("""^file://(\S+?):(\d+):(\d+)\s+(.+)$""")

        /** `[ERROR] /abs/path/Foo.java:[12,5] message` (javac form). */
        private val JAVAC_BRACKET = Regex(
            """^\s*\[(ERROR|WARNING)]\s+(\S*?\.(?:kt|kts|java)):\[(\d+),(\d+)]\s*(.*)$"""
        )

        /** `path/Foo.kt:12:5: error: message` (plain form). */
        private val PLAIN = Regex(
            """^\s*(?:\[(ERROR|WARNING)]\s*)?(\S*?\.(?:kt|kts|java)):(\d+):(\d+):?\s*(?:(error|warning):\s*)?(.+?)\s*$"""
        )

        /** `[ERROR] error: file:///...:12:5 message` (legacy reverse form). */
        private val REVERSE = Regex(
            """^\s*\[ERROR]\s*(?:error:\s*)?file://(\S+?):(\d+):(\d+)\s*(?:-\s*)?(.+?)\s*$"""
        )

        /** `[ERROR] e: file:///...` — Maven prefix plus the Kotlin severity prefix. */
        private val MAVEN_KOTLIN = Regex("""^\s*\[(ERROR|WARNING)]\s*([ew]):\s*(.*)$""")

        private fun parseLine(line: String, root: Path?): BuildDiagnostic? {
            // 1. Kotlin compiler: `e: file:///...` (optionally behind [ERROR]).
            val mavenKotlin = MAVEN_KOTLIN.find(line)
            val kotlinBody: String
            val kotlinSeverity: String
            if (mavenKotlin != null) {
                kotlinBody = mavenKotlin.groupValues[3].trim()
                kotlinSeverity = if (mavenKotlin.groupValues[2] == "w") "warning" else "error"
            } else {
                val prefix = KOTLIN_PREFIX.find(line) ?: return parseJavac(line, root)
                kotlinBody = prefix.groupValues[2].trim()
                kotlinSeverity = if (prefix.groupValues[1] == "w") "warning" else "error"
            }
            val fileUrl = KOTLIN_FILE_URL.find(kotlinBody)
            if (fileUrl != null) {
                return BuildDiagnostic(
                    file = normalizePath(fileUrl.groupValues[1], root),
                    line = fileUrl.groupValues[2].toIntOrNull() ?: 0,
                    column = fileUrl.groupValues[3].toIntOrNull() ?: 0,
                    severity = kotlinSeverity,
                    message = fileUrl.groupValues[4].trim(),
                )
            }
            // `e: path/Foo.kt:12:5 message` (no file:// scheme)
            val plain = PLAIN.find(kotlinBody)
            if (plain != null) {
                return BuildDiagnostic(
                    file = normalizePath(plain.groupValues[2], root),
                    line = plain.groupValues[3].toIntOrNull() ?: 0,
                    column = plain.groupValues[4].toIntOrNull() ?: 0,
                    severity = kotlinSeverity,
                    message = plain.groupValues[6].trim(),
                )
            }
            return null
        }

        private fun parseJavac(line: String, root: Path?): BuildDiagnostic? {
            JAVAC_BRACKET.find(line)?.let { m ->
                return BuildDiagnostic(
                    file = normalizePath(m.groupValues[2], root),
                    line = m.groupValues[3].toIntOrNull() ?: 0,
                    column = m.groupValues[4].toIntOrNull() ?: 0,
                    severity = if (m.groupValues[1] == "WARNING") "warning" else "error",
                    message = m.groupValues[5].trim(),
                )
            }
            PLAIN.find(line)?.let { m ->
                return BuildDiagnostic(
                    file = normalizePath(m.groupValues[2], root),
                    line = m.groupValues[3].toIntOrNull() ?: 0,
                    column = m.groupValues[4].toIntOrNull() ?: 0,
                    severity = m.groupValues[5].ifBlank {
                        if (m.groupValues[1] == "WARNING") "warning" else "error"
                    },
                    message = m.groupValues[6].trim(),
                )
            }
            REVERSE.find(line)?.let { m ->
                return BuildDiagnostic(
                    file = normalizePath(m.groupValues[1], root),
                    line = m.groupValues[2].toIntOrNull() ?: 0,
                    column = m.groupValues[3].toIntOrNull() ?: 0,
                    severity = "error",
                    message = m.groupValues[4].trim(),
                )
            }
            return null
        }

        /**
         * Normalize a compiler-reported path: strip the `file://` scheme and the
         * leading slash of a Windows drive URL, then make it workspace-relative
         * when it lives under [root].
         */
        private fun normalizePath(raw: String, root: Path?): String {
            var p = raw.trim()
            // The dedicated parsers consume `file://`; the generic PLAIN form
            // keeps it in the captured path, so strip it here as well.
            if (p.startsWith("file://")) p = p.removePrefix("file://")
            // Windows file URLs keep a leading '/': /D:/repo/Foo.kt
            if (p.length >= 3 && p[0] == '/' && p[2] == ':') p = p.substring(1)
            if (root != null) {
                val abs = runCatching { Path.of(p).toAbsolutePath().normalize() }.getOrNull()
                if (abs != null && abs.startsWith(root)) {
                    return root.relativize(abs).toString().replace('\\', '/')
                }
            }
            return p.replace('\\', '/')
        }
    }

    /**
     * Run a Maven build for a module and return structured results.
     *
     * @param shell     the coding shell (mvn must be allowed — it is a DEV command)
     * @param module    module path, e.g. "browser4-rest" or "browser4-plugins/browser4-seo"
     * @param goals     space-separated goals, default "compile"
     * @param skipTests whether to add -DskipTests (default true — the agent should
     *                  opt into tests explicitly, matching the repo's test policy)
     * @param timeoutSeconds max build time (default 300s; maven builds are slow)
     * @param workingDir repo root (or module dir); default null = shell base dir
     */
    suspend fun build(
        shell: CodingAgentShell,
        module: String,
        goals: String = "compile",
        skipTests: Boolean = true,
        timeoutSeconds: Long = 300L,
        workingDir: String? = null,
    ): BuildResult {
        val goalList = goals.trim().ifBlank { "compile" }
        val launcher = mavenLauncher(shell, workingDir)
        // Accept a bare module directory name (`browser4-browser`) for a nested
        // reactor entry (`browser4-core/browser4-browser`); Maven would reject it.
        val selector = resolveModuleSelector(shell, module, workingDir) ?: module
        val result = runMavenWithSelectorFallback(shell, selector, timeoutSeconds, workingDir) { resolved ->
            mavenBuildCommand(launcher, resolved, goalList, skipTests)
        }

        val combined = listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return BuildResult(
            module = selector,
            goals = goalList,
            exitCode = result.exitCode,
            output = combined,
            diagnostics = parseDiagnostics(combined, workspaceRoot(shell)),
            timedOut = result.timedOut,
        )
    }

    /**
     * Run the module's tests (the AGENTS.md "smallest scope" step).
     *
     * @param testClasses optional `-Dtest=` filter; when present
     *        `-Dsurefire.failIfNoSpecifiedTests=false` is added so upstream
     *        reactor modules do not fail the build for having no match.
     */
    suspend fun test(
        shell: CodingAgentShell,
        module: String,
        testClasses: List<String> = emptyList(),
        timeoutSeconds: Long = 600L,
        workingDir: String? = null,
    ): BuildResult {
        val launcher = mavenLauncher(shell, workingDir)
        val selector = resolveModuleSelector(shell, module, workingDir) ?: module
        val result = runMavenWithSelectorFallback(shell, selector, timeoutSeconds, workingDir) { resolved ->
            mavenTestCommand(launcher, resolved, testClasses)
        }
        val combined = listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return BuildResult(
            module = selector,
            goals = "test",
            exitCode = result.exitCode,
            output = combined,
            diagnostics = parseDiagnostics(combined, workspaceRoot(shell)),
            timedOut = result.timedOut,
        )
    }

    /**
     * Run a `-pl <module>` command, falling back to the ARTIFACT-ID selector
     * (`-pl :<artifactId>`) when Maven cannot match the path.
     *
     * A workspace whose module directories are NTFS junctions or symlinks (the
     * agent's isolated workspace links every module back to the real checkout)
     * is canonicalised by Maven to the link TARGET, so `-pl browser4-coding`
     * never matches the reactor entry and the build fails with
     * "Could not find the selected project in the reactor". The artifact-id
     * selector is stable across that indirection, so the same check passes
     * verbatim inside the agent workspace.
     */
    private suspend fun runMavenWithSelectorFallback(
        shell: CodingAgentShell,
        module: String,
        timeoutSeconds: Long,
        workingDir: String?,
        commandFor: (selector: String) -> String,
    ): ShellResult {
        val timeout = timeoutSeconds.coerceIn(1, CodingAgentShell.MAX_TIMEOUT_SECONDS)
        val primary = commandFor(module)
        logger.info("Maven: {}", primary)
        val first = shell.executeRaw(command = primary, timeoutSeconds = timeout, workingDir = workingDir)
        if (first.exitCode == 0 || !isMavenModuleSelectorFailure(first.stdout + "\n" + first.stderr)) {
            return first
        }
        val artifactId = resolveModuleArtifactId(shell, module, workingDir) ?: run {
            logger.warn("Maven could not resolve module path '{}' and no artifactId was found to retry with", module)
            return first
        }
        val fallback = commandFor(":$artifactId")
        logger.info("Maven module path selector failed for '{}'; retrying with artifactId selector: {}", module, fallback)
        return shell.executeRaw(command = fallback, timeoutSeconds = timeout, workingDir = workingDir)
    }

    /**
     * Format a [BuildResult] for the agent: diagnostics first (structured),
     * then a short tail of raw output.
     */
    fun format(result: BuildResult, maxOutputChars: Int = 4_000): String {
        val sb = StringBuilder()
        if (result.timedOut) sb.appendLine("⏱ Maven build timed out")
        sb.appendLine("mvn -pl ${result.module} -am ${result.goals} → exit ${result.exitCode}")

        if (result.diagnostics.isNotEmpty()) {
            sb.appendLine("Diagnostics (${result.diagnostics.size}):")
            result.diagnostics.take(30).forEach { d ->
                sb.appendLine("  ${d.format()}")
            }
            if (result.diagnostics.size > 30) {
                sb.appendLine("  ... (${result.diagnostics.size - 30} more)")
            }
        } else if (result.exitCode != 0) {
            sb.appendLine("Build failed with exit ${result.exitCode} but no parseable diagnostics — raw output tail:")
            val tail = result.output.takeLast(maxOutputChars)
            sb.appendLine(tail.ifBlank { "(no output)" })
        } else {
            sb.appendLine("✓ Build succeeded")
        }
        return sb.toString().trimEnd()
    }
}

/** Maven's diagnostic when `-pl <path>` matches nothing in the reactor. */
internal const val MAVEN_SELECTOR_FAILURE = "Could not find the selected project in the reactor"

/** Whether Maven output reports a `-pl <path>` selector that matched no reactor entry. */
internal fun isMavenModuleSelectorFailure(output: String): Boolean =
    output.contains(MAVEN_SELECTOR_FAILURE, ignoreCase = true)

/** Directory the module selectors are resolved against (working dir, else the workspace root). */
internal fun moduleBaseDir(shell: CodingAgentShell, workingDir: String?): File? {
    val base = workingDir?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: runCatching { shell.canonicalWorkspaceDir.toFile() }.getOrNull()
        ?: return null
    return base.takeIf { it.isDirectory }
}

/**
 * Resolve a model-supplied module selector to a reactor-relative path.
 *
 * The agent (and humans) usually name a module by its directory —
 * `browser4-browser` — while the reactor entry is nested
 * (`browser4-core/browser4-browser`). Maven only matches the path and answers
 *
 *     Could not find the selected project in the reactor: browser4-browser
 *
 * so `coding.verify(module="browser4-browser")` failed on the 2026-10-22
 * refactor run even though the module existed and compiled 3 seconds later once
 * the nested path was supplied by hand.
 *
 * A direct hit wins; otherwise the workspace is searched (bounded to
 * [maxDepth], build/IDE directories pruned) for a directory with that name that
 * carries a `pom.xml`.
 *
 * @return the selector to use, or null when the input already looks valid and
 *   nothing matched — callers then keep the original value and Maven's own
 *   error reaches the agent.
 */
internal fun resolveModuleSelector(
    shell: CodingAgentShell,
    module: String,
    workingDir: String? = null,
    maxDepth: Int = 4,
): String? {
    val root = moduleBaseDir(shell, workingDir) ?: return null
    return resolveModuleSelector(root, module, maxDepth)
}

/** [resolveModuleSelector] against an explicit root (pure, unit-testable). */
internal fun resolveModuleSelector(root: File, module: String, maxDepth: Int = 4): String? {
    val raw = module.trim()
    // `:artifactId` is Maven's own selector form — leave it alone.
    if (raw.isEmpty() || raw.startsWith(":")) return null
    val normalized = raw.replace('\\', '/').removePrefix("./").trim('/')
    if (normalized.isEmpty() || normalized == ".") return null
    if (File(root, normalized).let { File(it, "pom.xml").isFile }) return normalized

    val target = normalized.substringAfterLast('/')
    if (target.isEmpty()) return null
    val found = root.walkTopDown()
        .maxDepth(maxDepth)
        .onEnter { dir ->
            dir == root || (
                dir.name != "target" &&
                    dir.name != "node_modules" &&
                    !dir.name.startsWith(".")
                )
        }
        .firstOrNull { dir ->
            dir.isDirectory && dir.name == target && File(dir, "pom.xml").isFile
        } ?: return null
    val relative = root.toPath().relativize(found.toPath()).toString().replace('\\', '/')
    return relative.takeIf { it.isNotBlank() }
}

/**
 * Resolve a module's artifactId for the `-pl :<artifactId>` selector fallback.
 *
 * Accepts a module dir (`browser4-coding`) or an already-prefixed artifactId
 * (`:browser4-coding`); returns null when the module pom is unreadable.
 */
internal fun resolveModuleArtifactId(
    shell: CodingAgentShell,
    module: String,
    workingDir: String? = null,
): String? = runCatching {
    val trimmed = module.trim().removePrefix(":")
    if (trimmed.isEmpty()) return null
    val base = moduleBaseDir(shell, workingDir) ?: return null
    // A mocked/foreign workspace can hand back a File with a null path; every
    // File construction below stays inside this runCatching.
    val pom = File(File(base, trimmed), "pom.xml")
    if (!pom.isFile) return null
    ModuleGraph.parsePom(pom.readText())?.artifactId
}.getOrNull()

/**
 * The `mvn test -pl <selector>` command. The goal comes FIRST (`mvn test -pl X`,
 * not `mvn -pl X test`) — that is the shape every log, doc and test in the repo
 * assumes, including the ones that assert on the rendered command.
 */
internal fun mavenTestCommand(
    launcher: String,
    selector: String,
    testClasses: List<String>,
): String {
    val filter = testClasses.filter { it.isNotBlank() }.joinToString(",")
    val testArg = if (filter.isBlank()) "" else " -Dtest=$filter"
    val noSpecifiedTests = if (filter.isBlank()) "" else " -Dsurefire.failIfNoSpecifiedTests=false"
    return "$launcher test -pl $selector -am$testArg -DskipTests=false$noSpecifiedTests -q"
}

/** The `mvn -pl <selector> -am <goals>` build command. */
internal fun mavenBuildCommand(
    launcher: String,
    selector: String,
    goals: String,
    skipTests: Boolean,
): String {
    val skipFlag = if (skipTests) " -DskipTests" else ""
    return "$launcher -pl $selector -am $goals$skipFlag -q"
}