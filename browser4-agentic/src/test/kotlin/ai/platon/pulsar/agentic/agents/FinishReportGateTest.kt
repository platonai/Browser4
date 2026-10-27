package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the deterministic finish-report rules (plan P0.5): each rejection
 * path corresponds to a real false-completion incident, and the warn mode
 * (used by `-Dbrowser4.agent.finishGateCheck=warn`) is the same rule set with
 * the violations logged instead of thrown.
 */
class FinishReportGateTest {

    private fun evidence(
        executedToolCalls: Int = 3,
        executedToolNames: Set<String> = setOf("coding.mvnBuild", "coding.validate"),
        gates: List<Map<String, Any?>> = emptyList(),
        filesChanged: List<String> = emptyList(),
        gitChangedFiles: Set<String>? = null,
        requiredGates: List<String> = emptyList(),
        claimedDeletedFiles: List<String> = emptyList(),
        recordedMutations: List<String> = emptyList(),
        claimedVerdictJson: String? = null,
        actualVerdictJson: String? = null,
        gitDeletedFiles: Set<String>? = null,
    ) = FinishEvidence(
        executedToolCalls = executedToolCalls,
        executedToolNames = executedToolNames,
        gates = gates,
        filesChanged = filesChanged,
        gitChangedFiles = gitChangedFiles,
        requiredGates = requiredGates,
        claimedDeletedFiles = claimedDeletedFiles,
        recordedMutations = recordedMutations,
        claimedVerdictJson = claimedVerdictJson,
        actualVerdictJson = actualVerdictJson,
        gitDeletedFiles = gitDeletedFiles,
    )

    private fun gate(name: String, ran: Boolean, ok: Boolean = ran, detail: String? = null): Map<String, Any?> =
        buildMap {
            put("name", name)
            put("ran", ran)
            put("ok", ok)
            if (detail != null) put("detail", detail)
        }

    @Test
    @DisplayName("a complete report with anchored evidence passes")
    fun anchoredReportPasses() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                gates = listOf(gate("coding.mvnBuild", ran = true), gate("coding.validate", ran = true)),
                filesChanged = listOf("browser4-coding/src/main/kotlin/Foo.kt"),
                gitChangedFiles = setOf("browser4-coding/src/main/kotlin/Foo.kt"),
            )
        )
        assertTrue(verdict.passed, "violations: ${verdict.violations}")
        assertTrue(verdict.violations.isEmpty())
    }

    @Test
    @DisplayName("rule 1: zero executed tool calls is rejected")
    fun zeroToolCallsRejected() {
        val verdict = FinishReportGate.evaluate(evidence(executedToolCalls = 0, executedToolNames = emptySet()))
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("zero executed tool calls") }, "${verdict.violations}")
    }

    @Test
    @DisplayName("rule 2: a gate claiming ran=true with no matching tool call is rejected")
    fun inventedGateRejected() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                executedToolNames = setOf("coding.mvnBuild"),
                gates = listOf(gate("coding.mvnBuild", ran = true), gate("coding.runTests", ran = true)),
            )
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("coding.runTests") }, "${verdict.violations}")
    }

    @Test
    @DisplayName("rule 3: a gate that did not run without a detail is rejected")
    fun silentSkippedGateRejected() {
        val verdict = FinishReportGate.evaluate(
            evidence(gates = listOf(gate("coding.runTests", ran = false, ok = false)))
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("carries no detail") }, "${verdict.violations}")
    }

    @Test
    @DisplayName("rule 3: a skipped gate WITH a detail is accepted")
    fun documentedSkippedGateAccepted() {
        val verdict = FinishReportGate.evaluate(
            evidence(gates = listOf(gate("coding.runTests", ran = false, ok = false, detail = "no tests in module")))
        )
        assertTrue(verdict.passed, "violations: ${verdict.violations}")
    }

    @Test
    @DisplayName("rule 4: a reported file git does not show as changed is rejected")
    fun phantomFileChangeRejected() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                filesChanged = listOf("browser4-coding/src/main/kotlin/Foo.kt"),
                gitChangedFiles = setOf("browser4-coding/src/main/kotlin/Other.kt"),
            )
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("git does not show as changed") }, "${verdict.violations}")
    }

    @Test
    @DisplayName("rule 4: files git shows but the report omits are not a failure")
    fun unreportedChangeIsNotAFailure() {
        // One direction only: a dirty work tree may carry pre-existing changes,
        // so "changed but unreported" cannot be attributed to this task.
        val verdict = FinishReportGate.evaluate(
            evidence(
                filesChanged = listOf("browser4-coding/src/main/kotlin/Foo.kt"),
                gitChangedFiles = setOf(
                    "browser4-coding/src/main/kotlin/Foo.kt",
                    "browser4-coding/src/main/kotlin/Bar.kt",
                ),
            )
        )
        assertTrue(verdict.passed, "violations: ${verdict.violations}")
        assertTrue(verdict.warnings.isEmpty(), "warnings: ${verdict.warnings}")
    }

    @Test
    @DisplayName("rule 4: without git the file check is skipped, not failed")
    fun missingGitSkipsFileCheck() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                filesChanged = listOf("browser4-coding/src/main/kotlin/Foo.kt"),
                gitChangedFiles = null,
            )
        )
        assertTrue(verdict.passed, "violations: ${verdict.violations}")
    }

    @Test
    @DisplayName("rule 5: a required gate that is missing is rejected")
    fun missingRequiredGateRejected() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                requiredGates = listOf("verify"),
                gates = listOf(gate("coding.mvnBuild", ran = true)),
            )
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("required gate 'verify'") }, "${verdict.violations}")
    }

    @Test
    @DisplayName("rule 5: a required gate that ran but failed is rejected")
    fun failingRequiredGateRejected() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                requiredGates = listOf("verify"),
                executedToolNames = setOf("coding.verify"),
                gates = listOf(gate("coding.verify", ran = true, ok = false)),
            )
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("ok=false") }, "${verdict.violations}")
    }

    @Test
    @DisplayName("rule 5: a required gate that did not run is rejected")
    fun unrunRequiredGateRejected() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                requiredGates = listOf("verify"),
                gates = listOf(gate("coding.verify", ran = false, ok = false, detail = "not requested")),
            )
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("did not run") }, "${verdict.violations}")
    }

    @Test
    @DisplayName("gate names match by suffix, so 'verify' matches 'coding.verify'")
    fun gateNameSuffixMatch() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                requiredGates = listOf("verify"),
                executedToolNames = setOf("coding.verify"),
                gates = listOf(gate("coding.verify", ran = true, ok = true)),
            )
        )
        assertTrue(verdict.passed, "violations: ${verdict.violations}")
    }

    @Test
    @DisplayName("Windows paths normalize before the git comparison")
    fun windowsPathsNormalize() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                filesChanged = listOf("browser4-coding\\src\\main\\kotlin\\Foo.kt"),
                gitChangedFiles = setOf("browser4-coding/src/main/kotlin/Foo.kt"),
            )
        )
        assertTrue(verdict.passed, "violations: ${verdict.violations}")
    }

    // --- narrative vs trace: deletions, unlisted writes, cited verdicts -------

    @Test
    @DisplayName("a claimed deletion the run never performed is rejected")
    fun claimedDeletionNotPerformed() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                claimedDeletedFiles = listOf("VERSION"),
                recordedMutations = listOf("write browser4-coding/src/main/kotlin/Foo.kt"),
                filesChanged = listOf("browser4-coding/src/main/kotlin/Foo.kt"),
                gitChangedFiles = setOf("browser4-coding/src/main/kotlin/Foo.kt"),
            )
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("deletion") }, "violations: ${verdict.violations}")
    }

    @Test
    @DisplayName("a deletion the run recorded (or git shows) is accepted")
    fun recordedDeletionAccepted() {
        val recorded = FinishReportGate.evaluate(
            evidence(
                claimedDeletedFiles = listOf("temp/probe.txt"),
                recordedMutations = listOf("delete temp/probe.txt"),
            )
        )
        assertTrue(recorded.passed, "violations: ${recorded.violations}")

        val git = FinishReportGate.evaluate(
            evidence(
                claimedDeletedFiles = listOf("old/File.kt"),
                gitDeletedFiles = setOf("old/File.kt"),
            )
        )
        assertTrue(git.passed, "violations: ${git.violations}")
    }

    @Test
    @DisplayName("files the run modified but the report does not list are rejected")
    fun unlistedMutationRejected() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                recordedMutations = listOf(
                    "write browser4-coding/src/main/kotlin/Foo.kt",
                    "edit browser4-coding/src/main/kotlin/Bar.kt",
                ),
                filesChanged = listOf("browser4-coding/src/main/kotlin/Foo.kt"),
                gitChangedFiles = setOf(
                    "browser4-coding/src/main/kotlin/Foo.kt",
                    "browser4-coding/src/main/kotlin/Bar.kt",
                ),
            )
        )
        assertFalse(verdict.passed)
        assertTrue(verdict.violations.any { it.contains("Bar.kt") }, "violations: ${verdict.violations}")
    }

    @Test
    @DisplayName("tool state and out-of-repo writes are not material")
    fun nonMaterialMutationsIgnored() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                recordedMutations = listOf(
                    "write .browser4/coding-snapshots/x.json",
                    "write /tmp/scratch.txt",
                ),
                gitChangedFiles = setOf("browser4-coding/src/main/kotlin/Foo.kt"),
            )
        )
        assertTrue(verdict.passed, "violations: ${verdict.violations}")
    }

    @Test
    @DisplayName("rule 7 exposes the unlisted files so the engine can repair the report")
    fun unlistedFilesAreExposed() {
        // The 2026-10-27 incident: a browsing run wrote 37 helper scripts and the
        // model reported 20, so the strict gate failed a task whose issue had
        // already been filed and verified. The omission must be actionable, not
        // just a rejection.
        val verdict = FinishReportGate.evaluate(
            evidence(
                recordedMutations = listOf("write ev.js", "write issue_body.md"),
                filesChanged = listOf("issue_body.md"),
            )
        )
        assertFalse(verdict.passed)
        assertEquals(listOf("ev.js"), verdict.unlistedFiles)
        assertTrue(verdict.onlyUnlistedFiles, "an omission is repairable: ${verdict.violations}")
    }

    @Test
    @DisplayName("a file written and then deleted is not a deliverable")
    fun writeThenDeleteIsNotMaterial() {
        val deleted = FinishReportGate.evaluate(
            evidence(recordedMutations = listOf("write ev.js", "delete ev.js"))
        )
        assertTrue(deleted.passed, "violations: ${deleted.violations}")
        assertTrue(deleted.unlistedFiles.isEmpty())

        // A sibling that is still there must still be reported.
        val sibling = FinishReportGate.evaluate(
            evidence(recordedMutations = listOf("write ev.js", "delete ev.js", "write scan.js"))
        )
        assertFalse(sibling.passed)
        assertEquals(listOf("scan.js"), sibling.unlistedFiles)
    }

    @Test
    @DisplayName("an omission alongside a lie is not auto-amendable")
    fun omissionWithAnotherViolationIsNotAmendable() {
        val verdict = FinishReportGate.evaluate(
            evidence(
                executedToolCalls = 0,
                executedToolNames = emptySet(),
                recordedMutations = listOf("write ev.js"),
            )
        )
        assertFalse(verdict.passed)
        assertFalse(
            verdict.onlyUnlistedFiles,
            "only the rule-7 omission is amendable, not a fabricated completion: ${verdict.violations}"
        )
    }

    @Test
    @DisplayName("the rule-7 violation stays bounded while the full list is exposed")
    fun rule7ViolationIsBounded() {
        val files = (1..12).map { "scratch$it.js" }
        val verdict = FinishReportGate.evaluate(
            evidence(recordedMutations = files.map { "write $it" })
        )
        assertFalse(verdict.passed)
        assertEquals(12, verdict.unlistedFiles.size)
        val violation = verdict.violations.single()
        assertTrue(violation.contains("12 file(s)"), violation)
        assertTrue(violation.contains("scratch1.js, scratch2.js, scratch3.js, scratch4.js, scratch5.js"), violation)
        assertFalse(violation.contains("scratch6.js"), "the log line lists five paths: $violation")
    }

    @Test
    @DisplayName("a cited verdict must match the verdict the run recorded")
    fun citedVerdictMismatchRejected() {
        val actual = """{"passed":true,"module":"browser4-coding","requested":["compile"],""" +
            """"checks":[{"name":"compile","ran":true,"ok":true,"exitCode":0}]}"""
        val claimed = """{"passed":true,"module":"browser4-coding","requested":["compile"],""" +
            """"checks":[{"name":"compile","ran":true,"ok":false,"exitCode":1}]}"""
        val verdict = FinishReportGate.evaluate(
            evidence(claimedVerdictJson = claimed, actualVerdictJson = actual)
        )
        assertFalse(verdict.passed, "a disagreeing verdict must be rejected")
        assertTrue(
            verdict.violations.any { it.contains("verdict") },
            "violations: ${verdict.violations}",
        )
    }

    @Test
    @DisplayName("an identical cited verdict passes, a citation without verify fails")
    fun citedVerdictBackedByRun() {
        val actual = """{"passed":true,"module":"browser4-coding","requested":["compile"],""" +
            """"checks":[{"name":"compile","ran":true,"ok":true,"exitCode":0}]}"""
        val same = FinishReportGate.evaluate(
            evidence(claimedVerdictJson = actual, actualVerdictJson = actual)
        )
        assertTrue(same.passed, "violations: ${same.violations}")

        val unbacked = FinishReportGate.evaluate(evidence(claimedVerdictJson = actual))
        assertFalse(unbacked.passed)
        assertTrue(
            unbacked.violations.any { it.contains("no coding.verify ran") },
            "violations: ${unbacked.violations}",
        )

        val fabricated = FinishReportGate.evaluate(evidence(claimedVerdictJson = "{not json"))
        assertFalse(fabricated.passed)
        assertTrue(
            fabricated.violations.any { it.contains("cannot be parsed") },
            "violations: ${fabricated.violations}",
        )
    }

    // --- Which work tree backs the filesChanged cross-check -------------------

    @Test
    @DisplayName("the agent workspace is checked, never the server's process checkout")
    fun finishWorkspaceIsTheAgentWorkspace(@TempDir tmp: Path) {
        val repo = Files.createDirectories(tmp.resolve("repo"))
        Files.createDirectories(repo.resolve(".git"))
        // The isolated workspace is a copy WITHOUT .git, and the server's process
        // directory is a different (clean) checkout. Resolving the latter made every
        // file the agent wrote look fabricated to the strict gate.
        val workspace = Files.createDirectories(tmp.resolve("agent").resolve("workspace"))

        assertNull(
            resolveFinishGitWorkspace("", workspace, repo),
            "a git-less workspace must skip the git rules instead of reading the process repo"
        )
    }

    @Test
    @DisplayName("a configured finish workspace wins over the agent workspace")
    fun configuredFinishWorkspaceWins(@TempDir tmp: Path) {
        val repo = Files.createDirectories(tmp.resolve("repo"))
        Files.createDirectories(repo.resolve(".git"))
        val workspace = Files.createDirectories(tmp.resolve("agent").resolve("workspace"))

        assertEquals(
            repo,
            resolveFinishGitWorkspace(repo.toString(), workspace, tmp),
            "the explicit -Dbrowser4.agent.finish.workspace must win"
        )
    }

    @Test
    @DisplayName("without a workspace the process directory is still used")
    fun processDirectoryIsTheLastResort(@TempDir tmp: Path) {
        val repo = Files.createDirectories(tmp.resolve("repo"))
        Files.createDirectories(repo.resolve(".git"))

        assertEquals(repo, resolveFinishGitWorkspace("", null, repo))
    }
}