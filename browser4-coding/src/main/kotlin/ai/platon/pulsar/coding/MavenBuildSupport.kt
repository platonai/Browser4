package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory

/**
 * A single Kotlin/Java compiler error or warning with source location.
 */
data class BuildDiagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: String, // "error" | "warning"
    val message: String,
)

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
}

/**
 * Maven build support tailored to the Browser4 multi-module repository.
 *
 * Wraps `mvn -pl <module> -am <goals> -DskipTests` behind the coding shell's
 * command whitelist (mvn is a DEV command), returns a structured [BuildResult],
 * and parses Kotlin/Java compiler errors into [BuildDiagnostic]s so the agent
 * does not have to read raw Maven logs.
 *
 * This is the "compiler passthrough" half of the Kotlin diagnostics story —
 * LSP servers for Kotlin (JDTLS) are heavy; for quick feedback, compiling the
 * affected module and parsing the errors is cheap and dependency-free.
 */
class MavenBuildSupport {

    companion object {
        private val logger = LoggerFactory.getLogger(MavenBuildSupport::class.java)

        /**
         * Parse Kotlin/Java compiler errors from Maven output.
         *
         * Handles the two dominant formats:
         * - `file:///D:/.../Foo.kt:12:5 error: Unresolved reference 'bar'`
         * - `D:\...\Foo.kt:12:5: error: ...` (Windows, some toolchains)
         * - Maven reactor ERROR lines with `path\Foo.kt:12` style
         */
        fun parseDiagnostics(output: String): List<BuildDiagnostic> {
            val diags = mutableListOf<BuildDiagnostic>()

            // Primary format (kotlin-maven-plugin):
            //   [ERROR] file:///D:/.../Foo.kt:12:5 Unresolved reference 'bar'
            //   [ERROR] file:///.../Foo.java:34:2 cannot find symbol
            //   e: file:///D:/.../Foo.kt:12:5 Unresolved reference   (bare form)
            // severity comes from the [ERROR]/[WARNING] prefix when present
            // (default "error"); the message is everything after "<column> ".
            val primary = Regex(
                """(?m)^\s*(?:\[(ERROR|WARNING)\]\s*)?(?:[a-z]:\s*)?file://[^\s]*?([^\\/\s:]+?\.(?:kt|kts|java)):(\d+):(\d+)[:\s]\s*(.+?)\s*$"""
            )
            // Plain path form:  D:\...\Foo.kt:12:5: error: msg
            val plain = Regex(
                """(?m)^\s*(?:\[(ERROR|WARNING)\]\s*)?([^\\/\s:]+?\.(?:kt|kts|java)):(\d+):(\d+):\s*(error|warning):\s*(.+?)\s*$"""
            )
            // Maven kotlin-maven-plugin reverse style:
            //   [ERROR] error: file:///...:12:5 Unresolved reference
            val reverse = Regex(
                """(?m)^\s*\[ERROR\]\s*error:\s*file://[^\s]*?([^\\/\s:]+?\.(?:kt|kts|java)):(\d+):(\d+)\s*(?:-\s*)?(.+?)\s*$"""
            )

            primary.findAll(output).forEach { m ->
                diags += BuildDiagnostic(
                    file = m.groupValues[2],
                    line = m.groupValues[3].toIntOrNull() ?: 0,
                    column = m.groupValues[4].toIntOrNull() ?: 0,
                    severity = m.groupValues[1].ifBlank { "error" }.lowercase(),
                    message = m.groupValues[5].trim(),
                )
            }
            if (diags.isEmpty()) {
                plain.findAll(output).forEach { m ->
                    diags += BuildDiagnostic(
                        file = m.groupValues[2],
                        line = m.groupValues[3].toIntOrNull() ?: 0,
                        column = m.groupValues[4].toIntOrNull() ?: 0,
                        severity = m.groupValues[5],
                        message = m.groupValues[6].trim(),
                    )
                }
            }
            if (diags.isEmpty()) {
                reverse.findAll(output).forEach { m ->
                    diags += BuildDiagnostic(
                        file = m.groupValues[1],
                        line = m.groupValues[2].toIntOrNull() ?: 0,
                        column = m.groupValues[3].toIntOrNull() ?: 0,
                        severity = "error",
                        message = m.groupValues[4].trim(),
                    )
                }
            }
            return diags
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
        val skipFlag = if (skipTests) " -DskipTests" else ""
        val command = "mvn -pl $module -am $goalList$skipFlag -q"
        logger.info("Maven build: {}", command)
        val result = shell.executeRaw(
            command = command,
            timeoutSeconds = timeoutSeconds.coerceIn(1, CodingAgentShell.MAX_TIMEOUT_SECONDS),
            workingDir = workingDir,
        )

        val combined = listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return BuildResult(
            module = module,
            goals = goalList,
            exitCode = result.exitCode,
            output = combined,
            diagnostics = parseDiagnostics(combined),
            timedOut = result.timedOut,
        )
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
                sb.appendLine("  [${d.severity}] ${d.file}:${d.line}:${d.column} — ${d.message}")
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
