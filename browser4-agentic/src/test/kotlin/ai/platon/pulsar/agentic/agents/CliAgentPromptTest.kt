package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AgentAccessPolicy
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
    @DisplayName("a restricted preset adds the permissions block, full does not")
    fun permissionsBlockFollowsThePolicy() {
        // FULL is the default and must keep the prompt byte-for-byte what it was: the block is
        // added only when it changes what the model may do.
        val full = CliAgentPrompt.hygiene(true, workspace, AgentAccessPolicy())
        assertFalse(full.contains("### Permissions"), full)

        val read = CliAgentPrompt.hygiene(true, workspace, AgentAccessPolicy(preset = AccessPreset.READ))
        assertTrue(read.contains("### Permissions"), read)
        assertTrue(read.contains("access preset 'read'"), read)
        // The point of the block: the model must not retry a structural refusal.
        assertTrue(read.contains("do NOT retry"), read)
        assertTrue(read.contains("coding.permissions"), read)
        assertFalse(read.contains("run the project build"), read)
        assertFalse(read.contains("run shell commands"), read)
    }

    @Test
    @DisplayName("the permissions block reports what the policy actually grants, including auto-approval")
    fun permissionsBlockReflectsAutoApproval() {
        // `read` + a `write` ceiling grants file mutation and — because the build requires the
        // narrower `read-build` preset — the project build. The prompt must say so: it is the
        // ground truth the model plans against.
        val policy = AgentAccessPolicy(preset = AccessPreset.READ, autoApproveUpTo = AccessPreset.WRITE)
        val prompt = CliAgentPrompt.hygiene(true, workspace, policy)

        assertTrue(prompt.contains("write workspace files"), prompt)
        assertTrue(prompt.contains("run the project build"), prompt)
        assertFalse(prompt.contains("run shell commands"), prompt)
        assertTrue(prompt.contains("Automatic approval is on up to 'write'"), prompt)
    }

    @Test
    @DisplayName("denyTools alone is enough to warn a full-preset run")
    fun permissionsBlockAppearsForDenyTools() {
        val policy = AgentAccessPolicy(preset = AccessPreset.FULL, denyTools = setOf("coding.shell"))
        val prompt = CliAgentPrompt.hygiene(true, workspace, policy)
        assertTrue(prompt.contains("### Permissions"), prompt)
    }
}
