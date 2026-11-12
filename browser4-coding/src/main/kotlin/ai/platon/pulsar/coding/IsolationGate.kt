package ai.platon.pulsar.coding

/**
 * Decides whether an isolated coding run may be merged into the main work tree.
 *
 * The rule is deliberately narrow: **a run that changed code must carry a passing
 * `coding.verify` verdict that covers the modules it touched.** Everything else
 * (documentation-only changes, the gate switched off by the operator) passes
 * without a build.
 *
 * Why a gate instead of trusting the completion report: the worktree already
 * guarantees the main tree is untouched, but merging is the moment the change
 * becomes live for the next build — and a compile error there costs a full
 * rebuild cycle (and can leave the checkout unbuildable, see
 * `docs-dev/copilot/20261113-self-dev-worktree-isolation-plan.md` §D3/D6).
 *
 * The verdict is the one the run itself recorded
 * (`BasicBrowserAgent.lastInnerVerdictJson`), not a value the model restated in
 * its report: a cited verdict is checked separately by the finish gate.
 */
object IsolationGate {

    /** Property that switches the verification requirement off (default `true`). */
    const val REQUIRE_VERIFY_PROPERTY = "browser4.agent.isolation.requireVerify"

    /**
     * Outcome of one gate evaluation.
     *
     * @property allow whether the patch may be applied to the main work tree
     * @property reason machine-readable justification, also written to the task
     *   `problems` / failure reason when the merge is refused
     * @property changedFiles repo-relative paths the run changed (normalised)
     * @property requiredModules modules owning those files
     * @property verifier the module named by the recorded verdict, when any
     */
    data class Decision(
        val allow: Boolean,
        val reason: String,
        val changedFiles: List<String> = emptyList(),
        val requiredModules: Set<String> = emptySet(),
        val verifier: String? = null,
    )

    /**
     * Evaluate the gate.
     *
     * @param changedFiles repo-relative paths from the isolated tree
     * @param verdictJson `VERDICT_JSON` recorded by this run, or null when no
     *   `coding.verify` ran
     * @param requireVerify overrides the property; tests use it to pin both
     *   branches without touching process state
     */
    fun evaluate(
        changedFiles: List<String>,
        verdictJson: String?,
        requireVerify: Boolean = isRequiredByConfig(),
    ): Decision {
        val changed = changedFiles
            .map { it.replace('\\', '/').trim().removePrefix("./") }
            .filter { it.isNotEmpty() }
            .distinct()
        if (changed.isEmpty()) {
            return Decision(true, "no changes to merge", changed)
        }
        val modules = ModuleResolver.modulesOf(changed)
        if (!requireVerify || !ModuleResolver.requiresVerification(changed)) {
            return Decision(true, "no verification required (documentation-only or gate disabled)", changed, modules)
        }

        val report = parseVerdict(verdictJson)
            ?: return Decision(
                false,
                "no coding.verify verdict for this run — run coding.verify(module=<module>) " +
                    "and make it pass before finishing (changed: ${changed.take(5).joinToString(", ")})",
                changed,
                modules,
            )
        val verifier = report.module?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Decision(false, "the verification verdict names no module", changed, modules, null)
        if (!report.passed) {
            return Decision(
                false,
                "coding.verify did not pass (module=$verifier): ${report.failureReason() ?: "unknown reason"}",
                changed,
                modules,
                verifier,
            )
        }
        if (!ModuleResolver.covers(verifier, modules)) {
            return Decision(
                false,
                "coding.verify covered '$verifier' but the run changed ${modules.joinToString(", ")} — " +
                    "verify a module that depends on the changed ones (mvn -pl <module> -am)",
                changed,
                modules,
                verifier,
            )
        }
        return Decision(true, "verification passed (module=$verifier)", changed, modules, verifier)
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
