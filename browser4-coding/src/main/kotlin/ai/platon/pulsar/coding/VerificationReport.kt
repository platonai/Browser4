package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * One deterministic check inside a [VerificationReport].
 *
 * The report is the *authority* on whether a development task is done: the
 * agent's own prose is not. Every check records whether it actually ran, which
 * is as important as whether it passed — a gate that silently did not run must
 * never count as green (plan P0.1, invariant I6).
 */
data class VerificationCheck(
    /** Logical check id: `compile`, `test`, `repo-consistency`, `trapCheck`. */
    val name: String,
    /** What was checked: a module path, a file path, or `repo`. */
    val target: String,
    /** True when the check actually executed (a skipped check has no verdict). */
    val ran: Boolean,
    /** True only when [ran] and the check succeeded. */
    val ok: Boolean,
    /** Whether [ran] == false makes the whole report fail (default: yes). */
    val required: Boolean = true,
    val exitCode: Int = 0,
    val durationMs: Long = 0,
    /** Short human evidence, e.g. `exit 0`, `12 diagnostics`, `no CDP pitfalls`. */
    val evidence: String = "",
    /** Why the check did not run, or the failure reason. */
    val detail: String? = null,
    val diagnostics: List<BuildDiagnostic> = emptyList(),
) {
    /** `✓ compile browser4-coding — exit 0 (8.2s)` style one-liner. */
    fun format(): String {
        val icon = when {
            !ran -> "–"
            ok -> "✓"
            else -> "✗"
        }
        val reason = detail?.takeIf { it.isNotBlank() }
            ?.replace(Regex("\\s*\\r?\\n\\s*"), "; ")
            ?: evidence.takeIf { it.isNotBlank() }
        val extra = if (reason.isNullOrBlank()) "" else " — $reason"
        return "$icon $name $target$extra (${formatDuration(durationMs)})"
    }

    companion object {
        private fun formatDuration(ms: Long): String =
            if (ms < 1000) "${ms}ms" else String.format("%.1fs", ms / 1000.0)
    }
}

/**
 * Structured verdict of a verification run — the machine-readable "done"
 * signal consumed by the agent's finish gate, the CLI (`code verify --json`)
 * and the eval harness (plan P0.1/P0.2).
 *
 * @param checks every requested check, in evaluation order (including skipped ones)
 */
data class VerificationReport(
    val checks: List<VerificationCheck>,
    val module: String? = null,
    val requested: List<String> = emptyList(),
    val durationMs: Long = 0,
) {
    val ran: List<VerificationCheck> get() = checks.filter { it.ran }
    val failed: List<VerificationCheck> get() = checks.filter { it.ran && !it.ok }
    val skipped: List<VerificationCheck> get() = checks.filter { !it.ran }

    /**
     * True only when every executed check passed **and** every required check
     * actually ran. A skipped required check fails the report: "we could not
     * check" is not "it is fine".
     */
    val passed: Boolean
        get() = failed.isEmpty() && checks.filter { it.required }.all { it.ran }

    /** One-line failure reason for logs and exit-code messages. */
    fun failureReason(): String? {
        val failedCheck = failed.firstOrNull()
        if (failedCheck != null) {
            val detail = failedCheck.detail?.takeIf { it.isNotBlank() } ?: failedCheck.evidence
            return "${failedCheck.name} ${failedCheck.target} failed" +
                (if (detail.isBlank()) "" else ": $detail")
        }
        val skippedRequired = checks.firstOrNull { it.required && !it.ran }
        if (skippedRequired != null) {
            return "${skippedRequired.name} did not run" +
                (skippedRequired.detail?.let { ": $it" } ?: "")
        }
        return null
    }

    /** All structured diagnostics gathered across checks (deduplicated by location). */
    fun diagnostics(): List<BuildDiagnostic> =
        checks.flatMap { it.diagnostics }.distinctBy { "${it.file}:${it.line}:${it.column}:${it.message}" }

    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "passed" to passed,
        "module" to module,
        "requested" to requested,
        "durationMs" to durationMs,
        "failureReason" to failureReason(),
        "checks" to checks.map { c ->
            linkedMapOf(
                "name" to c.name,
                "target" to c.target,
                "ran" to c.ran,
                "ok" to c.ok,
                "required" to c.required,
                "exitCode" to c.exitCode,
                "durationMs" to c.durationMs,
                "evidence" to c.evidence,
                "detail" to c.detail,
                "diagnostics" to c.diagnostics.map { d ->
                    linkedMapOf(
                        "file" to d.file,
                        "line" to d.line,
                        "column" to d.column,
                        "severity" to d.severity,
                        "message" to d.message,
                    )
                },
            )
        },
    )

    fun toJson(): String = JSON.writeValueAsString(toJsonMap())

    /**
     * Human summary, followed by the machine-readable verdict line so a
     * supervisor can `grep VERDICT_JSON` without parsing prose.
     */
    fun format(includeVerdictLine: Boolean = true): String {
        val status = if (passed) "PASSED" else "FAILED"
        val counts = "${ran.size}/${checks.size} ran" +
            (if (failed.isEmpty()) "" else ", ${failed.size} failed") +
            (if (skipped.isEmpty()) "" else ", ${skipped.size} skipped")
        val sb = StringBuilder()
        sb.appendLine("Verification: $status ($counts) in ${formatSeconds(durationMs)}")
        checks.forEachIndexed { index, check ->
            sb.appendLine("  ${index + 1}. ${check.format()}")
            val diags = check.diagnostics.take(5)
            diags.forEach { sb.appendLine("       ${it.format()}") }
            if (check.diagnostics.size > diags.size) {
                sb.appendLine("       ... (${check.diagnostics.size - diags.size} more diagnostics)")
            }
        }
        failureReason()?.let { sb.appendLine("Reason: $it") }
        if (includeVerdictLine) sb.append(VERDICT_PREFIX).append(toJson())
        return sb.toString().trimEnd()
    }

    companion object {
        private val JSON = ObjectMapper()

        /**
         * Sentinel that prefixes the machine-readable verdict line in tool
         * output. Stable across versions — the CLI and the eval harness find it
         * with a plain prefix match, no JSON re-parsing of the prose.
         */
        const val VERDICT_PREFIX = "VERDICT_JSON: "

        /** All checks `coding.verify` can run, in default order. */
        val KNOWN_CHECKS: List<String> = listOf("compile", "test", "repo-consistency", "trapCheck")

        private fun formatSeconds(ms: Long): String = String.format("%.1fs", ms / 1000.0)

        /**
         * Extract the embedded verdict JSON from a tool reply.
         *
         * @return the JSON text, or null when the reply carries no verdict line
         */
        fun extractJson(text: String): String? =
            text.lineSequence().lastOrNull { it.startsWith(VERDICT_PREFIX) }
                ?.removePrefix(VERDICT_PREFIX)?.trim()
                ?.takeIf { it.isNotEmpty() }

        /**
         * Parse a verdict from tool output — either a reply carrying the
         * [VERDICT_PREFIX] line, or the pure-JSON form returned by `json=true`.
         *
         * @return the parsed report, or null when [text] carries no verdict
         */
        fun parseFromText(text: String): VerificationReport? {
            val json = extractJson(text) ?: text.trim().takeIf { it.startsWith("{") } ?: return null
            return fromJson(json)
        }

        /**
         * Rebuild a [VerificationReport] from its JSON form.
         *
         * Used by the repair loop, which reads the verdict back out of a tool
         * result (the agent never holds the Kotlin object of a remote call).
         */
        fun fromJson(json: String): VerificationReport? = runCatching {
            val root = JSON.readTree(json)
            val checks = root.get("checks")?.map { node ->
                VerificationCheck(
                    name = node.get("name")?.asText().orEmpty(),
                    target = node.get("target")?.asText().orEmpty(),
                    ran = node.get("ran")?.asBoolean() ?: false,
                    ok = node.get("ok")?.asBoolean() ?: false,
                    required = node.get("required")?.asBoolean() ?: true,
                    exitCode = node.get("exitCode")?.asInt() ?: 0,
                    durationMs = node.get("durationMs")?.asLong() ?: 0,
                    evidence = node.get("evidence")?.asText().orEmpty(),
                    detail = node.get("detail")?.asText()?.takeIf { it.isNotEmpty() },
                    diagnostics = node.get("diagnostics")?.map { d ->
                        BuildDiagnostic(
                            file = d.get("file")?.asText().orEmpty(),
                            line = d.get("line")?.asInt() ?: 0,
                            column = d.get("column")?.asInt() ?: 0,
                            severity = d.get("severity")?.asText() ?: "error",
                            message = d.get("message")?.asText().orEmpty(),
                        )
                    } ?: emptyList(),
                )
            } ?: emptyList()
            VerificationReport(
                checks = checks,
                module = root.get("module")?.asText()?.takeIf { it.isNotEmpty() },
                requested = root.get("requested")?.map { it.asText() } ?: emptyList(),
                durationMs = root.get("durationMs")?.asLong() ?: 0,
            )
        }.getOrNull()
    }
}

/**
 * Runs the deterministic verification checks for a dev task.
 *
 * This is the "verification authority layer" (plan P0): one call aggregates
 * compile / test / repo-consistency / trapCheck into a [VerificationReport],
 * so the agent's completion claim can be anchored to a machine-checkable
 * verdict instead of its own narrative.
 *
 * The runner is deliberately **read-only against the workspace apart from the
 * build output directory**: it compiles, tests, validates and inspects, but it
 * never edits source files.
 */
class VerificationRunner(
    private val maven: MavenBuildSupport = MavenBuildSupport(),
) {
    companion object {
        private val logger = LoggerFactory.getLogger(VerificationRunner::class.java)
    }

    /**
     * Run the requested checks.
     *
     * @param module        Maven module (or [ModuleMap.CLI_CRATE]); required by
     *                      `compile`/`test`. When null those checks are recorded
     *                      as not-run with an actionable detail.
     * @param checks        subset of [VerificationReport.KNOWN_CHECKS]; empty = default
     * @param runTests      shortcut for adding the `test` check
     * @param testClasses   `-Dtest=` filter for the test check
     * @param trapCheckFiles files to scan for CDP pitfalls; when empty the
     *                      `trapCheck` check is recorded as skipped and is *not*
     *                      required (no driver file in the plan is legitimate)
     * @param timeoutSeconds per-check timeout
     * @param testModule    module the `test` check runs in when it differs from
     *                      [module] (e.g. the change lands in a driver module but
     *                      the named test class lives in another one). Defaults to
     *                      [module]. A `-Dtest=` filter that matches nothing there
     *                      is a FAILING check, not a green one.
     */
    suspend fun verify(
        shell: CodingAgentShell,
        fs: CodingAgentFileSystem,
        module: String? = null,
        checks: List<String> = emptyList(),
        runTests: Boolean = false,
        testClasses: List<String> = emptyList(),
        trapCheckFiles: List<String> = emptyList(),
        timeoutSeconds: Long = 300L,
        testModule: String? = null,
    ): VerificationReport {
        val started = System.currentTimeMillis()
        val requested = buildList {
            addAll(checks.map { it.trim() }.filter { it.isNotEmpty() })
            if (runTests && "test" !in this) add("test")
            if (isEmpty()) {
                add("compile")
                add("repo-consistency")
                if (trapCheckFiles.isNotEmpty()) add("trapCheck")
            }
        }.distinct()
        logger.info("coding.verify: module={} testModule={} checks={}", module, testModule ?: module, requested)

        val results = mutableListOf<VerificationCheck>()
        for (check in requested) {
            results += when (check) {
                "compile" -> compileCheck(shell, module, timeoutSeconds)
                "test" -> testCheck(shell, fs, testModule ?: module, testClasses, timeoutSeconds)
                "repo-consistency" -> repoConsistencyCheck(fs)
                "trapCheck" -> trapCheck(fs, trapCheckFiles)
                else -> VerificationCheck(
                    name = check,
                    target = module ?: "repo",
                    ran = false,
                    ok = false,
                    detail = "unknown check '$check' — supported: ${VerificationReport.KNOWN_CHECKS.joinToString(", ")}",
                )
            }
        }
        return VerificationReport(
            checks = results,
            module = module,
            requested = requested,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    private suspend fun compileCheck(
        shell: CodingAgentShell,
        module: String?,
        timeoutSeconds: Long,
    ): VerificationCheck {
        if (module.isNullOrBlank()) {
            return VerificationCheck(
                name = "compile",
                target = "(none)",
                ran = false,
                ok = false,
                detail = "no module resolved — pass module=\"<maven module>\" (or \"${ModuleMap.CLI_CRATE}\")",
            )
        }
        val started = System.currentTimeMillis()
        if (module == ModuleMap.CLI_CRATE) {
            val result = shell.executeRaw("cargo check --bin browser4-cli", timeoutSeconds)
            val ok = result.exitCode == 0 && !result.timedOut
            return VerificationCheck(
                name = "compile",
                target = module,
                ran = true,
                ok = ok,
                exitCode = result.exitCode,
                durationMs = System.currentTimeMillis() - started,
                evidence = if (ok) "cargo check exit 0" else "cargo check exit ${result.exitCode}",
                detail = if (ok) null else lastLines(result.stdout + "\n" + result.stderr, 20),
            )
        }
        val result = maven.build(shell, module, "compile", skipTests = true, timeoutSeconds = timeoutSeconds)
        return VerificationCheck(
            name = "compile",
            target = module,
            ran = true,
            ok = result.success,
            exitCode = result.exitCode,
            durationMs = System.currentTimeMillis() - started,
            evidence = if (result.success) "exit 0" else
                "${result.diagnostics.size} diagnostic(s), exit ${result.exitCode}",
            detail = if (result.success) null else
                result.diagnosticsSummary(10).ifBlank { lastLines(result.output, 20) },
            diagnostics = result.diagnostics,
        )
    }

    private suspend fun testCheck(
        shell: CodingAgentShell,
        fs: CodingAgentFileSystem,
        module: String?,
        testClasses: List<String>,
        timeoutSeconds: Long,
    ): VerificationCheck {
        if (module.isNullOrBlank()) {
            return VerificationCheck(
                name = "test",
                target = "(none)",
                ran = false,
                ok = false,
                detail = "no module resolved — pass module=\"<maven module>\" (or \"${ModuleMap.CLI_CRATE}\")",
            )
        }
        val started = System.currentTimeMillis()
        val filter = testClasses.filter { it.isNotBlank() }.joinToString(",")
        if (module == ModuleMap.CLI_CRATE) {
            val command = "cargo test --bin browser4-cli"
            val result = shell.executeRaw(command, timeoutSeconds)
            val ok = result.exitCode == 0 && !result.timedOut
            return VerificationCheck(
                name = "test",
                target = module,
                ran = true,
                ok = ok,
                exitCode = result.exitCode,
                durationMs = System.currentTimeMillis() - started,
                evidence = if (ok) "exit 0" else "exit ${result.exitCode}",
                detail = if (ok) null else lastLines(result.stdout + "\n" + result.stderr, 20),
            )
        }
        val result = maven.test(shell, module, testClasses, timeoutSeconds)
        // `-Dtest=<class>` makes Maven exit 0 when the filter matches nothing, and
        // the plan adds `-Dsurefire.failIfNoSpecifiedTests=false` so `-am`
        // upstream modules do not fail for having no match. The two together let a
        // test check "pass" after executing ZERO tests — e.g. when the class lives
        // in another module. Execution is therefore proven from what surefire
        // actually wrote, not from the exit code (plan invariant I6: a gate that
        // did not really run is never green).
        val executed = if (filter.isBlank()) null
        else countFreshSurefireReports(fs.workspaceRoot, module, started)
        val ok = result.success && (executed == null || executed > 0)
        return VerificationCheck(
            name = "test",
            target = module + if (filter.isBlank()) "" else " [-Dtest=$filter]",
            ran = true,
            ok = ok,
            exitCode = result.exitCode,
            durationMs = System.currentTimeMillis() - started,
            evidence = when {
                !result.success -> "${result.diagnostics.size} diagnostic(s), exit ${result.exitCode}"
                executed == null -> "exit 0"
                executed > 0 -> "exit 0 ($executed test class(es) ran)"
                else -> "exit 0 but no test matched -Dtest=$filter"
            },
            detail = when {
                !result.success -> result.diagnosticsSummary(10).ifBlank { lastLines(result.output, 20) }
                ok -> null
                else -> "the -Dtest=$filter filter matched no test in $module (surefire wrote no report) — " +
                    "run the test in the module that owns it"
            },
            diagnostics = result.diagnostics,
        )
    }

    private suspend fun repoConsistencyCheck(fs: CodingAgentFileSystem): VerificationCheck {
        val started = System.currentTimeMillis()
        val outcome = runCatching { RepoConsistencyReporter.check(fs) }.getOrElse { e ->
            return VerificationCheck(
                name = "repo-consistency",
                target = "repo",
                ran = false,
                ok = false,
                durationMs = System.currentTimeMillis() - started,
                detail = "repo-consistency could not run: ${e.message}",
            )
        }
        return VerificationCheck(
            name = "repo-consistency",
            target = "repo",
            ran = true,
            ok = outcome.valid,
            durationMs = System.currentTimeMillis() - started,
            evidence = if (outcome.valid) "no errors" else "${outcome.errorCount} error(s)",
            detail = if (outcome.valid) null else outcome.result.issues
                .filter { it.severity == Severity.ERROR }
                .take(10)
                .joinToString("; ") { it.message },
        )
    }

    private suspend fun trapCheck(
        fs: CodingAgentFileSystem,
        files: List<String>,
    ): VerificationCheck {
        val targets = files.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (targets.isEmpty()) {
            return VerificationCheck(
                name = "trapCheck",
                target = "(no files)",
                ran = false,
                ok = false,
                // Advisory check: "no browser-driver file in this task" is a
                // legitimate state, not a broken gate.
                required = false,
                detail = "no browser-driver file to scan",
            )
        }
        val started = System.currentTimeMillis()
        val hits = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        for (file in targets) {
            val content = fs.readFile(file)
            if (content.startsWith("Error:")) {
                unreadable += file
                continue
            }
            CdpTrapCheck.check(content).forEach { hits += "${it.id}@$file" }
        }
        val ok = unreadable.isEmpty()
        return VerificationCheck(
            name = "trapCheck",
            target = targets.joinToString(", "),
            ran = true,
            ok = ok,
            durationMs = System.currentTimeMillis() - started,
            evidence = when {
                !ok -> "unreadable: ${unreadable.joinToString(", ")}"
                hits.isEmpty() -> "no CDP pitfalls"
                else -> "${hits.size} advisory: ${hits.joinToString(", ")}"
            },
            detail = if (ok) null else "could not read ${unreadable.joinToString(", ")}",
        )
    }

    private fun lastLines(text: String, count: Int): String {
        val lines = text.lines().filter { it.isNotBlank() }
        return lines.takeLast(count).joinToString("\n").take(2_000)
    }
}

/**
 * How many test classes surefire actually executed for [module] since [sinceMillis].
 *
 * Surefire writes one `target/surefire-reports/TEST-<class>.xml` (plus a
 * `<class>.txt` summary) per executed class, so a fresh report is proof that a
 * test really ran. This is deliberately report-based rather than output-based:
 * the build runs with `-q`, which suppresses the `Tests run:` lines on success.
 *
 * @param workspaceRoot the workspace the module path is relative to (the isolated
 *   worktree root when workspace isolation is on — reports land there too).
 * @param module workspace-relative module path, e.g. `browser4-core/browser4-browser`.
 * @param sinceMillis start of the test run; a small slack absorbs clock/FS
 *   granularity. Reports older than the run are stale and never counted.
 * @return the number of distinct executed test classes; 0 when nothing ran.
 */
internal fun countFreshSurefireReports(workspaceRoot: Path, module: String, sinceMillis: Long): Int {
    val reports = runCatching { workspaceRoot.resolve(module).resolve("target").resolve("surefire-reports") }
        .getOrNull() ?: return 0
    if (!Files.isDirectory(reports)) return 0
    val slackMs = 5_000L
    val fresh = runCatching {
        Files.walk(reports).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { Files.getLastModifiedTime(it).toMillis() >= sinceMillis - slackMs }
                .map { it.fileName.toString() }
                .filter { it.endsWith(".xml") || it.endsWith(".txt") }
                .toList()
        }
    }.getOrDefault(emptyList())
    if (fresh.isEmpty()) return 0
    // One XML (or TXT, when the XML report is disabled) file per class; count
    // classes so a module never reports both of its report files as two tests.
    val preferred = fresh.filter { it.startsWith("TEST-") && it.endsWith(".xml") }
    return (preferred.ifEmpty { fresh })
        .map { it.removePrefix("TEST-").substringBeforeLast('.') }
        .distinct()
        .size
}
