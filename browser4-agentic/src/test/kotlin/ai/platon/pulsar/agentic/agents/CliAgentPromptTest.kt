package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.Capability
import ai.platon.pulsar.coding.CapabilityId
import ai.platon.pulsar.coding.CapabilitySource
import ai.platon.pulsar.coding.Effect
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The CLI tool-loop system prompt must fit the task it opens. A refactor of a
 * 4,000-line Kotlin driver used to be introduced as "You are a browser automation
 * agent. Drive the browser EXCLUSIVELY through browser4-cli", and the run spent its
 * first rounds on `b4.run --help` / `system.listTools` instead of the file.
 */
@DisplayName("CliAgentPrompt")
class CliAgentPromptTest {

    private val workspace: Path = Path.of("/tmp/b4-workspace").toAbsolutePath().normalize()

    @Test
    @DisplayName("a coding run gets a coding framing, not a browser one")
    fun codingRoleIsCoding() {
        val prompt = CliAgentPrompt.role(
            codingMode = true, repairMaxRounds = 3, workspaceDir = workspace, windows = true,
        )

        assertTrue(prompt.contains("coding agent"), prompt)
        assertFalse(prompt.contains("browser automation agent"), prompt)
        assertFalse(prompt.contains("b4.run"), prompt)
        assertTrue(prompt.contains(workspace.toString()), "the working tree must be named: $prompt")
        assertTrue(prompt.contains("coding.verify"), prompt)
        assertTrue(prompt.contains("3 repair"), "repair rounds must be stated: $prompt")
    }

    @Test
    @DisplayName("a coding run is told the operational facts live runs lacked")
    fun codingRoleStatesOperationalFacts() {
        val windowsPrompt = CliAgentPrompt.role(
            codingMode = true, repairMaxRounds = 3, workspaceDir = workspace, windows = true,
        )
        // The mirror has no .git — `git status`/`git diff` fail there.
        assertTrue(windowsPrompt.contains("NO `.git`"), windowsPrompt)
        assertTrue(windowsPrompt.contains("coding.changeSummary"), windowsPrompt)
        // coding.shell is cmd.exe on Windows: POSIX tools fail.
        assertTrue(windowsPrompt.contains("cmd.exe"), windowsPrompt)
        assertTrue(windowsPrompt.contains("workingDir"), windowsPrompt)
        // Scratch artifacts must not pollute module sources / the workspace root.
        assertTrue(windowsPrompt.contains(".b4scratch/"), windowsPrompt)

        val posixPrompt = CliAgentPrompt.role(
            codingMode = true, repairMaxRounds = 3, workspaceDir = workspace, windows = false,
        )
        assertTrue(posixPrompt.contains("sh -c"), posixPrompt)
        assertFalse(posixPrompt.contains("cmd.exe"), posixPrompt)
    }

    @Test
    @DisplayName("a coding run is told to checkpoint its plan across round cut-offs")
    fun codingRoleAsksForPlanCheckpoints() {
        // A refactor run burned 165 tool calls in three overflowed rounds without
        // landing an edit: each round restart keeps only a short digest of the tools,
        // never the model's plan. The prompt therefore asks for an explicit note.
        val prompt = CliAgentPrompt.role(
            codingMode = true, repairMaxRounds = 3, workspaceDir = workspace, windows = true,
        )

        assertTrue(prompt.contains("per-round iteration cap"), prompt)
        assertTrue(prompt.contains("memory.note(key=\"plan\""), prompt)
        assertTrue(prompt.contains("first concrete edit"), prompt)
    }

    @Test
    @DisplayName("a browsing run keeps the browser framing and the CLI reference hook")
    fun browsingRoleIsBrowsing() {
        val prompt = CliAgentPrompt.role(
            codingMode = false, repairMaxRounds = 3, workspaceDir = workspace, windows = true,
        )

        assertTrue(prompt.contains("browser automation agent"), prompt)
        assertTrue(prompt.contains("b4.run"), prompt)
        assertFalse(prompt.contains(".b4scratch/"), prompt)
    }

    @Test
    @DisplayName("hygiene block follows the mode")
    fun hygieneFollowsMode() {
        val coding = CliAgentPrompt.hygiene(codingMode = true, workspaceDir = workspace)
        assertTrue(coding.contains(".b4scratch/"), coding)
        assertTrue(coding.contains("memory_read()"), coding)
        assertFalse(coding.contains("snapshot -v 0"), coding)

        val browsing = CliAgentPrompt.hygiene(codingMode = false, workspaceDir = workspace)
        assertTrue(browsing.contains("snapshot -v 0"), browsing)
        assertTrue(browsing.contains(workspace.toString()), browsing)
        // A browsing run writes one helper .js per `b4 run eval --file`; those
        // belong under .b4scratch/ — the finish gate counts everything else as a
        // deliverable (observed: 37 files written, 20 reported, task failed).
        assertTrue(browsing.contains(".b4scratch/"), browsing)
        assertTrue(browsing.contains("eval --file .b4scratch/"), browsing)
        // Multi-item collection must checkpoint to disk: a live 20-product run
        // re-crawled the same 20 pages four times because the round digest kept
        // only the newest six results and the collected rows lived in the conversation.
        assertTrue(browsing.contains(".b4scratch/progress.txt"), browsing)
        // Localized marketplaces (observed: Amazon serving zh-CN/HKD to a US task)
        // need a locale fix on the page, not post-processing of the wrong values.
        assertTrue(browsing.contains("Accept-Language"), browsing)
        // How a particular site accepts a locale (a query parameter it happens to honour)
        // is that site's business, not the engine's. The prompt may *name* the hint, but only
        // to forbid relying on it — driving the site's own picker is the fix (measured:
        // `?language=en_US`/headers/cookies all left amazon.com on its Chinese interstitial).
        // The prompt is a hard-wrapped literal, so assertions have to stay inside one line.
        assertTrue(browsing.contains("HINTS, not the fix"), browsing)
        assertTrue(browsing.contains("assume a site-specific query parameter"), browsing)
        // No spreadsheet writer is built in (decision 2026-10-28: no Excel dependency):
        // deliverables are CSV/JSONL, and an .xlsx INPUT is extracted by a script. A
        // spreadsheet REQUEST is a documented downgrade that must be reported, not a
        // silent substitution.
        assertTrue(browsing.contains("write CSV"), browsing)
        assertTrue(browsing.contains("out of scope"), browsing)
        assertTrue(browsing.contains("delivered as CSV"), browsing)
        assertTrue(browsing.contains("name it in `problems`"), browsing)
        assertTrue(browsing.contains("coding.read refuses binary"), browsing)
        // A repeated infrastructure failure must end the run with a report, not start an
        // environment investigation: a live run spent ~50 steps on status/close/doctor
        // against a driver that could not start, then stopped its own backend.
        assertTrue(browsing.contains("SAME infrastructure error"), browsing)
        assertTrue(browsing.contains("doctor log tail"), browsing)
        assertTrue(browsing.contains("report it in `problems`"), browsing)
        assertFalse(coding.contains("progress.txt"), coding)
    }

    @Test
    @DisplayName("both modes point at coding.changeSummary as the authoritative file list")
    fun changeSummaryIsTheAuthoritativeList() {
        for (codingMode in listOf(true, false)) {
            val hygiene = CliAgentPrompt.hygiene(codingMode, workspace)
            assertTrue(hygiene.contains("coding.changeSummary"), hygiene)
            assertTrue(hygiene.contains("filesChanged"), hygiene)
        }
    }

    @Test
    @DisplayName("the coding prompt prices output tokens and forbids per-round narration")
    fun outputTokenBudgetIsStated() {
        val coding = CliAgentPrompt.hygiene(true, workspace)
        // Measured: model time is ~4 ms per output token; a coding run that narrates every
        // round pays for it in wall clock (plan P1-4). The rule must stay in the prompt.
        assertTrue(coding.contains("1,000 output tokens"), coding)
        assertTrue(coding.contains("A tool round needs\n          NO prose") || coding.contains("NO prose"), coding)
        // A "batch independent calls into one round" rule was A/B-tested and REVERTED: rounds did
        // not drop (22 vs 17-21) and wall clock rose ~15% (plan §0.4, appendix B.8).
        assertFalse(coding.contains("Batch independent calls"), coding)
        // N1 note: a prompt line recommending coding.readFiles was A/B-tested and DROPPED —
        // the tool's own spec description is what makes the model adopt it (both arms used it;
        // the arm with the extra prompt line was not faster). The tool is advertised through
        // `CodingReadFilesToolTest`, not through the prompt.
        assertFalse(coding.contains("coding.readFiles"), coding)
        assertFalse(CliAgentPrompt.hygiene(false, workspace).contains("NO prose"),
            "browsing runs must not inherit the coding output-budget rule")
    }

    @Test
    @DisplayName("the prompt states the finish-report contract for both modes")
    fun finishContractPresent() {
        for (codingMode in listOf(true, false)) {
            val hygiene = CliAgentPrompt.hygiene(codingMode, workspace)
            assertTrue(hygiene.contains("system.taskComplete"), hygiene)
            assertTrue(hygiene.contains("VERDICT_JSON"), hygiene)
            assertTrue(hygiene.contains("filesChanged"), hygiene)
        }
    }

    @Test
    @DisplayName("a restricted preset adds the permissions block; full adds only the workspace boundary")
    fun permissionsBlockFollowsThePolicy() {
        // FULL with external access OFF still has a boundary: the coding file tools refuse every
        // path outside the workspace. Announcing it is what stops the retry loop — a live browsing
        // run re-phrased the same denied repo path four times because the preset check suppressed
        // the block while the boundary was still active. The full preset contract block stays absent.
        val full = CliAgentPrompt.hygiene(true, workspace, AgentAccessPolicy())
        assertFalse(full.contains("### Permissions"), full)
        assertTrue(full.contains("### Workspace boundary"), full)
        assertTrue(full.contains("allowExternalAccess=false"), full)
        assertTrue(full.contains("do NOT retry"), full)

        // External access on = nothing to warn about: the prompt stays as it was.
        val fullExternal = CliAgentPrompt.hygiene(
            true, workspace, AgentAccessPolicy(allowExternalAccess = true),
        )
        assertFalse(fullExternal.contains("### Workspace boundary"), fullExternal)
        assertFalse(fullExternal.contains("### Permissions"), fullExternal)

        val read = CliAgentPrompt.hygiene(true, workspace, AgentAccessPolicy(preset = AccessPreset.READ))
        assertTrue(read.contains("### Permissions"), read)
        assertTrue(read.contains("access preset 'read'"), read)
        // The point of the block: the model must not retry a structural refusal.
        assertTrue(read.contains("do NOT retry"), read)
        assertTrue(read.contains("coding.permissions"), read)
        assertTrue(read.contains("Paths outside the workspace are refused"), read)
        assertFalse(read.contains("run the project build"), read)
        assertFalse(read.contains("run shell commands"), read)
    }

    @Test
    @DisplayName("an unrestricted prompt names the lifted command list and the vetoes that still refuse")
    fun unrestrictedPromptNamesTheLiftedTierAndTheSurvivingVetoes() {
        // `--unrestricted` lifts the command allow-list, so the prompt must not promise a blank
        // cheque either: the OS credential stores still refuse, and killing the backend or
        // rebuilding the runtime still refuses (CodingAgentShell.handOffVetoViolation).
        val prompt = CliAgentPrompt.hygiene(true, workspace, AgentAccessPolicy().withUnrestricted())

        assertTrue(prompt.contains("unrestricted"), prompt)
        assertTrue(prompt.contains("command allow-list"), prompt)
        assertTrue(prompt.contains("b4w build"), "the surviving vetoes must be named: $prompt")
        assertTrue(prompt.contains("taskkill"), prompt)
        assertTrue(prompt.contains("~/.ssh"), "the credential rail must be named: $prompt")
        assertFalse(prompt.contains("UNSUPPORTED_SYNTAX"), "a removed gate must not be advertised: $prompt")
    }

    @Test
    @DisplayName("the permissions block reports what the policy actually grants, including an operator grant")
    fun permissionsBlockReflectsOperatorGrant() {
        // `read` + an operator-approved `fs.write` grant unlocks file mutation and — because the
        // build requires the narrower `read-build` preset — the project build. The prompt must say
        // so: it is the ground truth the model plans against.
        val grants = listOf(
            Capability(
                id = CapabilityId("op-write"),
                effects = setOf(Effect.FS_WRITE),
                source = CapabilitySource.OPERATOR,
            )
        )
        val policy = AgentAccessPolicy(preset = AccessPreset.READ, operatorGrants = { grants })
        val prompt = CliAgentPrompt.hygiene(true, workspace, policy)

        assertTrue(prompt.contains("write workspace files"), prompt)
        assertTrue(prompt.contains("run the project build"), prompt)
        assertFalse(prompt.contains("run shell commands"), prompt)
        assertTrue(prompt.contains("Operator approval"), prompt)
    }

    @Test
    @DisplayName("denyTools alone is enough to warn a full-preset run")
    fun permissionsBlockAppearsForDenyTools() {
        val policy = AgentAccessPolicy(preset = AccessPreset.FULL, denyTools = setOf("coding.shell"))
        val prompt = CliAgentPrompt.hygiene(true, workspace, policy)
        assertTrue(prompt.contains("### Permissions"), prompt)
    }

    @Test
    @DisplayName("an isolated coding run is told it has git, and that unverified work is not merged")
    fun isolatedCodingRoleDescribesWorktreeMerge() {
        val prompt = CliAgentPrompt.role(
            codingMode = true, repairMaxRounds = 3, workspaceDir = workspace, windows = true, isolated = true,
        )

        assertTrue(prompt.contains("isolated git worktree"), prompt)
        assertTrue(prompt.contains("HAS `.git`"), prompt)
        assertFalse(prompt.contains("NO `.git`"), prompt)
        assertTrue(prompt.contains("NOTHING is merged"), prompt)
        assertTrue(prompt.contains("b4w build"), prompt)
        assertTrue(prompt.contains(workspace.toString()), prompt)
    }

    @Test
    @DisplayName("a folder granted with --add-dir is named in the prompt so the model uses it")
    fun permissionsBlockNamesAddedFolders() {
        val added = Path.of("/corpus").toAbsolutePath().normalize()
        val granted = CliAgentPrompt.hygiene(
            true, workspace, AgentAccessPolicy().withAdditionalDirs(listOf(added)),
        )
        assertTrue(granted.contains("Extra folders granted"), granted)
        assertTrue(granted.contains(added.toString()), "the absolute path must be usable as given: $granted")
        assertTrue(granted.contains("writable"), granted)

        // Under a read preset the folder is still named, but no write is promised there.
        val read = CliAgentPrompt.hygiene(
            true, workspace, AgentAccessPolicy(preset = AccessPreset.READ).withAdditionalDirs(listOf(added)),
        )
        assertTrue(read.contains("Extra folders granted"), read)
        assertTrue(read.contains("writes there are refused"), read)

        // No grant, no line.
        val plain = CliAgentPrompt.hygiene(true, workspace, AgentAccessPolicy())
        assertFalse(plain.contains("Extra folders granted"), plain)
    }
}
