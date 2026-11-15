package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class IsolationGateTest {

    private fun verdict(module: String, passed: Boolean): String = VerificationReport(
        checks = listOf(
            VerificationCheck(
                name = "compile",
                target = module,
                ran = true,
                ok = passed,
                exitCode = if (passed) 0 else 1,
                detail = if (passed) null else "compilation failed",
            )
        ),
        module = module,
        requested = listOf("compile"),
        durationMs = 10,
    ).toJson()

    /** A verdict whose content fingerprint was taken over [changed] in [worktree]. */
    private fun digestVerdict(worktree: Path, module: String, changed: List<String>): String = VerificationReport(
        checks = listOf(VerificationCheck(name = "compile", target = module, ran = true, ok = true)),
        module = module,
        requested = listOf("compile"),
        durationMs = 10,
        verifiedAt = Instant.now(),
        contentDigest = WorkspaceDigest.of(worktree, changed),
    ).toJson()

    @Test
    @DisplayName("an empty change set merges without a verdict")
    fun emptyChangeSetAllowed() {
        val decision = IsolationGate.evaluate(emptyList(), null)
        assertTrue(decision.allow)
        assertTrue(decision.reason.contains("no changes"), decision.reason)
    }

    @Test
    @DisplayName("documentation-only changes merge without a verdict")
    fun documentationOnlyAllowed() {
        val decision = IsolationGate.evaluate(listOf("docs/config.md", "README.md"), null)
        assertTrue(decision.allow, decision.reason)
    }

    @Test
    @DisplayName("a code change without a verify verdict is refused")
    fun codeChangeWithoutVerdictRefused() {
        val decision = IsolationGate.evaluate(listOf("browser4-coding/src/main/kotlin/X.kt"), null)
        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("no coding.verify verdict"), decision.reason)
        assertTrue(decision.requiredModules.contains("browser4-coding"))
    }

    @Test
    @DisplayName("a failed verdict is refused and keeps its reason")
    fun failedVerdictRefused() {
        val decision = IsolationGate.evaluate(
            listOf("browser4-coding/src/main/kotlin/X.kt"),
            verdict("browser4-coding", passed = false),
        )
        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("did not pass"), decision.reason)
    }

    @Test
    @DisplayName("a passing verdict for the changed module allows the merge")
    fun passingVerdictAllowed() {
        val decision = IsolationGate.evaluate(
            listOf("browser4-coding/src/main/kotlin/X.kt"),
            verdict("browser4-coding", passed = true),
        )
        assertTrue(decision.allow, decision.reason)
        assertTrue(decision.reason.contains("verification passed"), decision.reason)
    }

    @Test
    @DisplayName("a downstream verifier covers an upstream change (-am semantics)")
    fun downstreamVerifierCoversUpstreamModule() {
        val decision = IsolationGate.evaluate(
            listOf("browser4-coding/src/main/kotlin/X.kt"),
            verdict("browser4-agentic", passed = true),
        )
        assertTrue(decision.allow, decision.reason)
    }

    @Test
    @DisplayName("an upstream verifier does not cover a downstream change")
    fun upstreamVerifierDoesNotCoverDownstreamModule() {
        val decision = IsolationGate.evaluate(
            listOf("browser4-agentic/src/main/kotlin/X.kt"),
            verdict("browser4-coding", passed = true),
        )
        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("did not cover"), decision.reason)
    }

    @Test
    @DisplayName("turning the requirement off allows an unverified code change")
    fun requirementCanBeDisabled() {
        val decision = IsolationGate.evaluate(
            listOf("browser4-coding/src/main/kotlin/X.kt"),
            null,
            requireVerify = false,
        )
        assertTrue(decision.allow, decision.reason)
    }

    @Test
    @DisplayName("the verdict parser accepts both the JSON and the VERDICT_JSON line form")
    fun verdictParserAcceptsBothForms() {
        val json = verdict("browser4-coding", passed = true)
        assertTrue(IsolationGate.parseVerdict(json)?.passed == true)
        assertTrue(IsolationGate.parseVerdict("ok\nVERDICT_JSON: $json\n")?.passed == true)
        assertTrue(IsolationGate.parseVerdict(null) == null)
    }

    // -- coverage across ecosystems -------------------------------------------

    @Test
    @DisplayName("one verdict cannot cover a change spanning the Rust crate and a Kotlin module")
    fun mixedChangeNeedsBothVerdicts() {
        val changed = listOf("cli/browser4-cli/src/main.rs", "browser4-coding/src/main/kotlin/X.kt")

        val onlyRust = IsolationGate.evaluate(changed, listOf(verdict(ModuleMap.CLI_CRATE, passed = true)))
        assertFalse(onlyRust.allow, "a Rust verdict must not cover a Kotlin module")
        assertTrue(onlyRust.reason.contains("browser4-coding"), onlyRust.reason)

        val both = IsolationGate.evaluate(
            changed,
            listOf(verdict(ModuleMap.CLI_CRATE, passed = true), verdict("browser4-coding", passed = true)),
        )
        assertTrue(both.allow, both.reason)
    }

    @Test
    @DisplayName("an earlier failing attempt is superseded by a later passing verdict")
    fun passingVerdictSupersedesFailure() {
        val decision = IsolationGate.evaluate(
            listOf("browser4-coding/src/main/kotlin/X.kt"),
            listOf(verdict("browser4-coding", passed = false), verdict("browser4-coding", passed = true)),
        )
        assertTrue(decision.allow, decision.reason)
    }

    // -- freshness -------------------------------------------------------------

    @Test
    @DisplayName("a verdict that still describes the tree is accepted")
    fun matchingDigestAllowed(@TempDir tmp: Path) {
        val changed = listOf("browser4-coding/src/main/kotlin/X.kt")
        val file = tmp.resolve(changed.first())
        Files.createDirectories(file.parent)
        Files.writeString(file, "class X\n")

        val decision = IsolationGate.evaluate(changed, listOf(digestVerdict(tmp, "browser4-coding", changed)), worktree = tmp)

        assertTrue(decision.allow, decision.reason)
    }

    @Test
    @DisplayName("editing after verify refuses the merge: the verdict no longer describes the tree")
    fun editingAfterVerifyRefused(@TempDir tmp: Path) {
        val changed = listOf("browser4-coding/src/main/kotlin/X.kt")
        val file = tmp.resolve(changed.first())
        Files.createDirectories(file.parent)
        Files.writeString(file, "class X\n")
        val recorded = digestVerdict(tmp, "browser4-coding", changed)

        // The agent keeps editing after the verdict.
        Files.writeString(file, "class X { fun y() = 1 }\n")

        val decision = IsolationGate.evaluate(changed, listOf(recorded), worktree = tmp)

        assertFalse(decision.allow, "a stale verdict must never merge")
        assertTrue(decision.reason.contains("different change set"), decision.reason)
    }

    @Test
    @DisplayName("a new file after verify also refuses the merge")
    fun newFileAfterVerifyRefused(@TempDir tmp: Path) {
        val changed = listOf("browser4-coding/src/main/kotlin/X.kt")
        val file = tmp.resolve(changed.first())
        Files.createDirectories(file.parent)
        Files.writeString(file, "class X\n")
        val recorded = digestVerdict(tmp, "browser4-coding", changed)

        Files.writeString(tmp.resolve("browser4-coding/src/main/kotlin/Y.kt"), "class Y\n")

        val decision = IsolationGate.evaluate(
            changed + "browser4-coding/src/main/kotlin/Y.kt", listOf(recorded), worktree = tmp,
        )

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("different change set"), decision.reason)
    }

    @Test
    @DisplayName("a timestamp-only verdict is refused when a changed file is newer")
    fun staleTimestampRefused(@TempDir tmp: Path) {
        val changed = listOf("browser4-coding/src/main/kotlin/X.kt")
        val file = tmp.resolve(changed.first())
        Files.createDirectories(file.parent)
        Files.writeString(file, "class X\n")
        val recorded = VerificationReport(
            checks = listOf(VerificationCheck(name = "compile", target = "browser4-coding", ran = true, ok = true)),
            module = "browser4-coding",
            requested = listOf("compile"),
            durationMs = 5,
            verifiedAt = Instant.now().minusSeconds(600),
        ).toJson()
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()))

        val decision = IsolationGate.evaluate(changed, listOf(recorded), worktree = tmp)

        assertFalse(decision.allow)
        assertTrue(decision.reason.contains("was last written"), decision.reason)
    }

    @Test
    @DisplayName("without a worktree the gate keeps the coverage rule only")
    fun noWorktreeSkipsFreshness() {
        val decision = IsolationGate.evaluate(
            listOf("browser4-coding/src/main/kotlin/X.kt"),
            verdict("browser4-coding", passed = true),
        )
        assertTrue(decision.allow, decision.reason)
    }
}
