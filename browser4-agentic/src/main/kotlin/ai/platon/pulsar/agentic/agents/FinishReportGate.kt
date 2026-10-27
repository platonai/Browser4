package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.coding.VerificationReport
import java.nio.file.Files
import java.nio.file.Path

/**
 * Evidence a completion report must be anchored to (plan P0.5, design §3.2).
 *
 * The agent's finish report is prose plus a few structured claims; this is the
 * deterministic evidence the engine can compare those claims against. Kept as a
 * plain data class (no agent, no I/O) so the rules are unit-testable.
 */
internal data class FinishEvidence(
    /** Tool calls that actually executed (outer states + inner tool-calling loop). */
    val executedToolCalls: Int,
    /** Executed tool names, `domain.method` form. */
    val executedToolNames: Set<String>,
    /** The report's quality gates: `{name, ran, ok, exitCode, detail}`. */
    val gates: List<Map<String, Any?>>,
    /** Files the report lists as changed. */
    val filesChanged: List<String>,
    /** Repository-relative paths git reports as changed; null when git is unavailable. */
    val gitChangedFiles: Set<String>?,
    /** Gates that must be present and passing for a success claim. */
    val requiredGates: List<String> = emptyList(),
    /** Files the report claims it deleted. */
    val claimedDeletedFiles: List<String> = emptyList(),
    /**
     * What the run's file system actually did, `"<op> <path>"` per mutation
     * (e.g. `"write browser4-coding/src/main/kotlin/Foo.kt"`). This is the
     * trace side of the "narrative vs trace" check: a report cannot claim no
     * work while the log shows writes, nor omit files it modified.
     */
    val recordedMutations: List<String> = emptyList(),
    /** `VERDICT_JSON` the report cites inline, if any. */
    val claimedVerdictJson: String? = null,
    /** `VERDICT_JSON` of the last `coding.verify` this run executed, if any. */
    val actualVerdictJson: String? = null,
    /** Repository-relative paths git reports as deleted; null when git is unavailable. */
    val gitDeletedFiles: Set<String>? = null,
)

/**
 * The deterministic finish-report rules.
 *
 * Each rule exists because a real false-completion incident looked exactly like
 * the pattern it rejects:
 *
 * 1. **zero executed tool calls** — a 35 s / 1 step / 0 tool-call "all done".
 * 2. **a gate that claims `ran:true` with no matching tool call** — an invented
 *    gate.
 * 3. **a gate that did not run and carries no `detail`** — a silent gate that
 *    reads as a pass.
 * 4. **`filesChanged` entries git does not show** — an invented change.
 * 5. **a configured required gate missing or `ok:false`** — an unsupported
 *    success claim.
 *
 * The reverse direction (files git shows that the report does not list) is a
 * warning, not a violation: a dirty working tree may legitimately carry changes
 * from before this task.
 *
 * Rule 7's own direction (files the *run* wrote that the report omits) is real
 * evidence but not a lie, so it is reported as [Verdict.unlistedFiles] instead of
 * being baked into the failure: [FinishReportRepair] turns it into one bounded
 * repair round, then amends the report from the trace. A long run writes dozens
 * of helper files (one `.js` per `b4 run eval --file`) and the model rebuilds
 * `filesChanged` from a compressed memory of the run — observed: a 13-minute run
 * wrote 37 files, reported 20, and the strict gate failed a task whose issue had
 * already been filed and verified.
 */
internal object FinishReportGate {

    /** Missing files named in the rule-7 violation, so the log line stays bounded. */
    private const val MAX_UNLISTED_IN_VIOLATION = 5

    /** Separately-reportable outcome: hard violations and soft warnings. */
    internal data class Verdict(
        val violations: List<String>,
        /**
         * Soft observations that never fail the completion. Currently empty:
         * the `filesChanged` check is one-directional (see rule 4), because a
         * shared work tree has no run-start baseline to compare against.
         */
        val warnings: List<String>,
        /**
         * Material files the run wrote that `filesChanged` does not list:
         * rule 7's actionable detail (empty when the rule passed).
         *
         * A caller that can still talk to the model sends this list back as a
         * repair message; one that cannot amends the report with it instead of
         * failing a run that did the work (see [FinishReportRepair]).
         */
        val unlistedFiles: List<String> = emptyList(),
    ) {
        val passed: Boolean get() = violations.isEmpty()

        /**
         * True when the **only** violation is rule 7's omission.
         *
         * Rule 7 appends exactly one violation when it fires, so a single
         * violation plus a non-empty [unlistedFiles] means no other rule
         * objected. Such a report can be amended from the run's own trace: the
         * files really were written, only the bookkeeping list is incomplete —
         * unlike a phantom change or an invented gate, which are lies about what
         * happened and must still fail the task.
         */
        val onlyUnlistedFiles: Boolean
            get() = unlistedFiles.isNotEmpty() && violations.size == 1
    }

    fun evaluate(evidence: FinishEvidence): Verdict {
        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 1. No executed tool call: the report cannot be anchored to anything.
        if (evidence.executedToolCalls == 0) {
            violations += "task completed with zero executed tool calls (false-completion guard)"
        }

        // 2. `ran:true` must name a tool call that really executed.
        evidence.gates.filter { it["ran"] == true }.forEach { gate ->
            val name = gate["name"]?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                violations += "a gate claims ran=true but has no name"
                return@forEach
            }
            val matched = evidence.executedToolNames.any { it.endsWith(name) || it.contains(name) }
            if (!matched) {
                violations += "gate '$name' claims ran but no matching tool call executed " +
                    "(executed=${evidence.executedToolNames.joinToString(",")})"
            }
        }

        // 3. `ran:false` must say why, otherwise it silently reads as a pass.
        evidence.gates.filter { it["ran"] != true }.forEach { gate ->
            val name = gate["name"]?.toString()?.trim().orEmpty().ifBlank { "(unnamed gate)" }
            val detail = gate["detail"]?.toString()?.trim().orEmpty()
            if (detail.isEmpty()) {
                violations += "gate '$name' did not run and carries no detail"
            }
        }

        // 4. Reported changes must exist in git's view of the work tree.
        //
        // One direction only: reported ⊆ git-changed. The reverse ("git shows a
        // change the report omits") is NOT checked, because the working tree may
        // legitimately carry unrelated changes from before the task and the agent
        // has no run-start baseline to subtract them.
        val reported = evidence.filesChanged.map { it.trim() }.filter { it.isNotEmpty() }
        val changed = evidence.gitChangedFiles
        if (reported.isNotEmpty() && changed != null) {
            val normalized = reported.map { normalizePath(it) }
            val phantom = normalized.filterNot { path ->
                changed.any { c -> c == path || c.endsWith("/$path") || path.endsWith("/$c") }
            }
            if (phantom.isNotEmpty()) {
                violations += "filesChanged lists ${phantom.size} file(s) git does not show as changed: " +
                    phantom.take(5).joinToString(", ")
            }
        }

        // 5. Configured required gates must be present, ran, and ok.
        evidence.requiredGates.forEach { required ->
            val gate = evidence.gates.firstOrNull { g ->
                val name = g["name"]?.toString().orEmpty()
                name.endsWith(required) || name.contains(required)
            }
            when {
                gate == null -> violations += "required gate '$required' missing from the completion report"
                gate["ran"] != true -> violations += "required gate '$required' did not run"
                gate["ok"] == false -> violations += "required gate '$required' reported ok=false"
            }
        }

        // 6. Claimed deletions must be backed by the run or by git.
        //
        // A deleted file never shows up in `git status`'s changed list (unless it
        // was tracked), so a "deleted X" claim was previously uncheckable — and an
        // untracked file's deletion was invisible altogether.
        val claimedDeletions = evidence.claimedDeletedFiles.map { normalizePath(it) }.filter { it.isNotEmpty() }
        if (claimedDeletions.isNotEmpty()) {
            val recordedDeletions = evidence.recordedMutations
                .filter { it.startsWith("delete ") }
                .map { normalizePath(it.removePrefix("delete ")) }
            val gitDeletions = evidence.gitDeletedFiles.orEmpty().map { normalizePath(it) }
            val unbacked = claimedDeletions.filterNot { path ->
                (recordedDeletions + gitDeletions).any { d -> d == path || d.endsWith("/$path") || path.endsWith("/$d") }
            }
            if (unbacked.isNotEmpty()) {
                violations += "the report claims ${unbacked.size} deletion(s) neither the run nor git shows: " +
                    unbacked.take(5).joinToString(", ")
            }
        }

        // 7. Files the run actually modified must be listed in the report.
        //
        // This is the structured half of "narrative vs trace": a run that writes
        // files but reports none is a false completion regardless of the prose.
        // Only *material* mutations count — a write outside the repository (a
        // scratch/temp file) and the `.browser4/` tool state are excluded.
        //
        // An omission is reported separately from a lie: `unlistedFiles` lets the
        // caller repair the report (or amend it from the trace) instead of
        // failing a finished run over bookkeeping.
        var unlistedFiles = emptyList<String>()
        val materialMutations = materialMutationPaths(evidence)
        if (materialMutations.isNotEmpty()) {
            val listed = (reported + claimedDeletions).map { normalizePath(it) }
            unlistedFiles = materialMutations.filterNot { path ->
                listed.any { l -> l == path || l.endsWith("/$path") || path.endsWith("/$l") }
            }
            if (unlistedFiles.isNotEmpty()) {
                violations += "the run modified ${unlistedFiles.size} file(s) the report does not list: " +
                    unlistedFiles.take(MAX_UNLISTED_IN_VIOLATION).joinToString(", ")
            }
        }

        // 8. A reported verification verdict must match the one the run recorded.
        //
        // The report may cite `VERDICT_JSON: {...}`; if it does, that JSON has to
        // equal the verdict of the last `coding.verify` call this run executed.
        // A fabricated verdict (or a citation with no verify at all) is caught
        // here instead of being taken on trust.
        evidence.claimedVerdictJson?.let { claimedRaw ->
            val claimed = runCatching { VerificationReport.parseFromText("VERDICT_JSON: $claimedRaw") }.getOrNull()
            val actual = evidence.actualVerdictJson
            when {
                claimed == null -> violations += "the report cites a VERDICT_JSON that cannot be parsed"
                actual == null ->
                    violations += "the report cites a verification verdict but no coding.verify ran in this run"
                else -> {
                    val actualReport = runCatching {
                        VerificationReport.parseFromText("VERDICT_JSON: $actual")
                    }.getOrNull()
                    if (actualReport == null) {
                        violations += "the recorded verification verdict cannot be parsed"
                    } else if (claimed.passed != actualReport.passed) {
                        violations += "the reported verdict says passed=${claimed.passed} but the run recorded " +
                            "passed=${actualReport.passed}"
                    } else {
                        val claimedChecks = claimed.checks.associate { it.name to it.ok }
                        val mismatched = actualReport.checks.filter { c ->
                            claimedChecks.containsKey(c.name) && claimedChecks[c.name] != c.ok
                        }
                        if (mismatched.isNotEmpty()) {
                            violations += "the reported verdict disagrees with the recorded one for: " +
                                mismatched.joinToString(", ") { it.name }
                        }
                    }
                }
            }
        }

        return Verdict(violations, warnings, unlistedFiles)
    }

    /**
     * Paths the run touched that still exist, in the order of their **last** touch.
     *
     * The last operation wins: a file written and then deleted is not a
     * deliverable any more (the workspace no longer has it), so a report that
     * omits it is truthful. Deletions of files this run never wrote are not
     * material here either — rule 6 owns the "claimed deletion" direction.
     */
    private fun materialMutationPaths(evidence: FinishEvidence): List<String> {
        val lastOp = LinkedHashMap<String, String>()
        evidence.recordedMutations.forEach { entry ->
            val op = entry.substringBefore(' ', "").trim()
            if (op.isEmpty()) return@forEach
            val path = normalizePath(entry.substringAfter(' ', ""))
            if (path.isEmpty()) return@forEach
            // Re-insert so iteration order tracks the latest touch of each path.
            lastOp.remove(path)
            lastOp[path] = op
        }
        return lastOp.entries
            .filter { it.value != "delete" }
            .map { it.key }
            .filter { isMaterialPath(it, evidence) }
    }

    /**
     * Whether a mutated path is material to the checkout: a path git reports as
     * changed, or (when git is unavailable) any path outside the tool's own
     * `.browser4/` state directory.
     */
    private fun isMaterialPath(path: String, evidence: FinishEvidence): Boolean {
        if (path.startsWith(".browser4/")) return false
        val changed = evidence.gitChangedFiles ?: return true
        return changed.any { c -> c == path || c.endsWith("/$path") || path.endsWith("/$c") }
    }

    private fun normalizePath(raw: String): String =
        raw.trim().trim('"').replace('\\', '/').removePrefix("./").removePrefix("/")
}

/**
 * Pick the work tree whose git state backs a completion report's `filesChanged`.
 *
 * `-Dbrowser4.agent.finish.workspace=<dir>` wins; otherwise the **agent's own
 * workspace** — the tree its coding tools actually wrote into — is used.
 *
 * Resolving the server's process working directory instead compared the report
 * against a *different* tree (the checkout the server was launched from): that
 * checkout is normally clean, so every file the agent really wrote in its
 * isolated workspace looked fabricated and the strict gate rejected a perfectly
 * good completion (observed: `filesChanged lists N file(s) git does not show as
 * changed` for a run whose workspace compiled).
 *
 * The isolated workspace is a copy **without** `.git`, so this returns null
 * there and the git rules are skipped; the run's mutation trace
 * (`codingFs.mutationsSnapshot`) still backs rule 7.
 *
 * @param configured the `-Dbrowser4.agent.finish.workspace` value (may be blank)
 * @param agentWorkspace the agent's workspace directory, or null when unknown
 * @param processDir the JVM working directory, used only when no workspace exists
 * @return the enclosing git work tree of the chosen directory, or null
 */
internal fun resolveFinishGitWorkspace(
    configured: String,
    agentWorkspace: Path?,
    processDir: Path,
): Path? {
    val configuredDir = configured.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    if (configuredDir != null) return findGitRoot(configuredDir)
    if (agentWorkspace != null) return findGitRoot(agentWorkspace)
    return findGitRoot(processDir)
}

/**
 * The nearest ancestor of [start] holding a `.git` entry (directory or file for
 * a worktree/submodule checkout), or null within 12 hops.
 */
internal fun findGitRoot(start: Path): Path? {
    var dir: Path? = runCatching { start.toAbsolutePath().normalize() }.getOrNull()
    var hops = 0
    while (dir != null && hops < 12) {
        if (Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git"))) {
            return dir
        }
        dir = dir.parent
        hops++
    }
    return null
}
