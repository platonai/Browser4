package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
        assertTrue(decision.reason.contains("covered"), decision.reason)
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
}
