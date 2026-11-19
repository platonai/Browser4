package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * Decides whether an isolated coding run may be merged into the main work tree.
 *
 * The rule is deliberately narrow: **a run that changed code must carry passing
 * `coding.verify` verdicts that cover the modules it touched, and those verdicts must
 * describe the tree about to be merged.** Everything else (documentation-only changes,
 * the gate switched off by the operator) passes without a build.
 *
 * Three things are checked, in this order:
 *
 * 1. **Coverage** — every changed module is covered by some passing verdict
 *    ([ModuleResolver.covers]: the verdict's module is that module or a transitive
 *    dependent of it, which is what `mvn -pl <verifier> -am` compiles). A single
 *    verdict used to be required, which made a change spanning the Rust CLI and a
 *    Kotlin module impossible to merge: no one module covers both ecosystems.
 * 2. **Passing** — at least one recorded verdict passed; a failing one is reported with
 *    its own reason.
 * 3. **Freshness** — the verdict must still describe the change set. `coding.verify`
 *    records [VerificationReport.contentDigest] (and [VerificationReport.verifiedAt]);
 *    this gate recomputes the digest against the worktree it is about to apply. Without
 *    that check a run could verify, keep editing, and merge on the old verdict — the one
 *    thing the gate exists to prevent.
 *
 * Why a gate instead of trusting the completion report: the worktree already
 * guarantees the main tree is untouched, but merging is the moment the change
 * becomes live for the next build — and a compile error there costs a full
 * rebuild cycle (and can leave the checkout unbuildable, see
 * `docs-dev/copilot/20261113-self-dev-worktree-isolation-plan.md` §D3/D6).
 *
 * The verdicts are the ones the run itself recorded
 * (`BasicBrowserAgent.innerVerdicts`), not values the model restated in its report:
 * a cited verdict is checked separately by the finish gate.
 */
object IsolationGate {

    /** Property that switches the verification requirement off (default `true`). */
    const val REQUIRE_VERIFY_PROPERTY = "browser4.agent.isolation.requireVerify"

    /**
     * Tolerance when comparing a file's timestamp with the verdict's: coarse (2 s)
     * filesystem timestamps must not turn a same-second write into a false refusal.
     */
    private const val FRESHNESS_TOLERANCE_SECONDS = 2L

    /**
     * Outcome of one gate evaluation.
     *
     * @property allow whether the patch may be applied to the main work tree
     * @property reason machine-readable justification, also written to the task
     *   `problems` / failure reason when the merge is refused
     * @property changedFiles repo-relative paths the run changed (normalised)
     * @property requiredModules modules owning those files
     * @property verifier the module(s) whose verdict allowed the merge, when any
     */
    data class Decision(
        val allow: Boolean,
        val reason: String,
        val changedFiles: List<String> = emptyList(),
        val requiredModules: Set<String> = emptySet(),
        val verifier: String? = null,
    )

    /**
     * Evaluate the gate for a single recorded verdict.
     *
     * @param verdictJson `VERDICT_JSON` recorded by this run, or null when no
     *   `coding.verify` ran
     * @param requireVerify overrides the property; tests use it to pin both
     *   branches without touching process state
     * @param worktree the isolated tree the change set belongs to (enables the
     *   freshness check; omitted, freshness is not checked)
     */
    fun evaluate(
        changedFiles: List<String>,
        verdictJson: String?,
        requireVerify: Boolean = isRequiredByConfig(),
        worktree: Path? = null,
    ): Decision = evaluate(changedFiles, listOfNotNull(verdictJson), worktree, requireVerify)

    /**
     * Evaluate the gate for every verdict the run recorded.
     *
     * @param verdicts `VERDICT_JSON`s recorded by this run (any order, duplicates fine)
     * @param worktree the isolated tree the change set belongs to; when null the
     *   freshness check is skipped and the coverage rule falls back to the static
     *   [ModuleMap]. When present, its own pom graph also supplies the module
     *   dependents, so a foreign Maven workspace is judged by its own topology
     *   instead of Browser4's snapshot
     */
    fun evaluate(
        changedFiles: List<String>,
        verdicts: List<String>,
        worktree: Path? = null,
        requireVerify: Boolean = isRequiredByConfig(),
    ): Decision {
        val changed = changedFiles
            .map { it.replace('\\', '/').trim().removePrefix("./") }
            .filter { it.isNotEmpty() }
            .distinct()
        if (changed.isEmpty()) {
            return Decision(true, "no changes to merge", changed)
        }
        val graph = worktree?.let { root -> runCatching { ModuleGraph.cachedGraph(root) }.getOrNull() }
        val modules = ModuleResolver.modulesOf(changed, graph)
        if (!requireVerify || !ModuleResolver.requiresVerification(changed)) {
            return Decision(true, "no verification required (documentation-only or gate disabled)", changed, modules)
        }

        val reported = verdicts.mapNotNull { parseVerdict(it) }
        if (reported.isEmpty()) {
            return Decision(
                false,
                "no coding.verify verdict for this run — run coding.verify(module=<module>) " +
                    "and make it pass before finishing (changed: ${changed.take(5).joinToString(", ")})",
                changed,
                modules,
            )
        }
        val passing = reported.filter { it.passed }
        if (passing.isEmpty()) {
            val failed = reported.first()
            return Decision(
                false,
                "coding.verify did not pass (module=${failed.module ?: "?"}): " +
                    (failed.failureReason() ?: "unknown reason"),
                changed,
                modules,
                failed.module,
            )
        }

        // Coverage: one verdict may cover several modules (`-am`), and several
        // verdicts may cover one change set (a Rust crate + a Kotlin module).
        //
        // The dependents are read from the WORKTREE's own module graph (built above from its
        // poms). The static ModuleMap only knows Browser4's modules, so it answered "no
        // dependent" for every foreign Maven project and refused a legitimate merge — the
        // two-module toy repo in evaluation §11, where `app` depends on `lib` and
        // `coding.verify(module=app)` was rejected for `lib` even though `-pl app -am`
        // compiles it. Without a worktree (or with a tree whose poms we cannot read) the
        // historical static behaviour is kept.
        val covering = mutableMapOf<String, VerificationReport>()
        val uncovered = mutableListOf<String>()
        for (module in modules) {
            val verdict = passing.firstOrNull { ModuleResolver.covers(it.module, setOf(module), graph) }
            if (verdict == null) uncovered += module else covering[module] = verdict
        }
        if (uncovered.isNotEmpty()) {
            val verified = reported.joinToString(", ") {
                "${it.module ?: "?"}(${if (it.passed) "passed" else "failed"})"
            }
            return Decision(
                false,
                "coding.verify did not cover ${uncovered.joinToString(", ")} — recorded: [$verified]. " +
                    coverageHint(modules, graph),
                changed,
                modules,
                passing.firstOrNull()?.module,
            )
        }

        val used = covering.values.distinct()
        freshnessProblem(changed, used, worktree)?.let { problem ->
            return Decision(false, problem, changed, modules, used.firstOrNull()?.module)
        }
        return Decision(
            true,
            "verification passed (module=${used.joinToString(", ") { it.module ?: "?" }})",
            changed,
            modules,
            used.joinToString(", ") { it.module ?: "?" },
        )
    }

    /**
     * What to verify instead, naming the concrete `module=` whose build covers the change set.
     *
     * "Verify every changed module" says nothing about which module's `-am` build contains the
     * others, so a change spanning two modules can only be rescued by reasoning about the
     * dependency direction — the 2026-11-18 run guessed it backwards (`browser4-coding` for a
     * change that also touched `browser4-agentic`), the merge was refused, and 19 minutes of
     * work stayed in the worktree. [ModuleResolver.suggestVerifier] answers that question from
     * the same graph the coverage check uses.
     */
    private fun coverageHint(modules: Set<String>, graph: ModuleGraph.Graph?): String = buildString {
        append("Verify every changed module, or one whose `-am` build compiles all of them")
        ModuleResolver.suggestVerifier(modules, graph)?.let { verifier ->
            append(": coding.verify(module=\"").append(verifier)
            append("\") → mvn -pl ").append(verifier).append(" -am covers this change set")
        }
        if (ModuleMap.CLI_CRATE in modules) {
            append("; the Rust crate needs module=\"").append(ModuleMap.CLI_CRATE).append("\"")
        }
        append('.')
    }

    /**
     * The first reason the verdicts no longer describe [changed], or null when they do.
     *
     * The content fingerprint is authoritative; a verdict recorded before the field
     * existed falls back to the timestamp, and one with neither is accepted (the
     * recording side always writes both now).
     */
    private fun freshnessProblem(
        changed: List<String>,
        verdicts: Collection<VerificationReport>,
        worktree: Path?,
    ): String? {
        if (worktree == null) return null
        for (verdict in verdicts) {
            val label = verdict.module ?: "(none)"
            val digest = verdict.contentDigest
            if (!digest.isNullOrBlank()) {
                val current = WorkspaceDigest.of(worktree, changed)
                if (current != null) {
                    if (current != digest) {
                        return "coding.verify (module=$label) ran on a different change set than the one about " +
                            "to be merged — the tree changed after the verdict. Re-run coding.verify for the " +
                            "final change set."
                    }
                    continue
                }
                // Too large or unreadable to fingerprint now: the timestamp still applies.
            }
            val verifiedAt = verdict.verifiedAt ?: continue
            val limit = verifiedAt.plusSeconds(FRESHNESS_TOLERANCE_SECONDS)
            val late = changed.firstOrNull { rel ->
                val path = worktree.resolve(rel).normalize()
                runCatching {
                    Files.isRegularFile(path) && Files.getLastModifiedTime(path).toInstant().isAfter(limit)
                }.getOrDefault(false)
            }
            if (late != null) {
                return "coding.verify (module=$label) ran before '$late' was last written — re-run it after the " +
                    "final edit; the merge gate only accepts a verdict that describes the tree it applies."
            }
        }
        return null
    }

    /** The recorded verdict of a run, parsed from either form the engine stores. */
    fun parseVerdict(verdictJson: String?): VerificationReport? {
        val raw = verdictJson?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The recorder stores pure JSON; a tool result embeds it after the marker.
        return VerificationReport.fromJson(raw)
            ?: VerificationReport.parseFromText(raw)
    }

    /** Whether the operator left the verification requirement on (default: yes). */
    fun isRequiredByConfig(): Boolean =
        System.getProperty(REQUIRE_VERIFY_PROPERTY, "true").trim().equals("false", ignoreCase = true).not()
}
