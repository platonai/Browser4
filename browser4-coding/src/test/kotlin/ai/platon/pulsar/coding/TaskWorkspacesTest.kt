package ai.platon.pulsar.coding

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Other tasks' workspaces (`allowTaskWorkspaceWrites`): what is in scope, what never is, and that
 * the boundary, the policy gate and the file system agree.
 *
 * The set is derived from the ambient data root, so every test points
 * [ReadablePaths.PROP_APP_DATA_DIR] at a temp tree. `workspace.archive`, `agent/skills`, a
 * `workspace.*` directory outside the agent layout and the task's *own* workspace must never be in
 * it — those are the four ways this widening could turn into "the whole data directory".
 */
@DisplayName("other tasks' workspaces: scope, bounds and the boundary")
class TaskWorkspacesTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dataRoot: Path
    private var savedDataDir: String? = null

    private val agentBase: Path get() = dataRoot.resolve(TaskWorkspaces.AGENT_DIR_NAME)

    private val uuidA = "11111111-1111-1111-1111-111111111111"
    private val uuidB = "22222222-2222-2222-2222-222222222222"
    private val uuidC = "33333333-3333-3333-3333-333333333333"

    @BeforeEach
    fun setUp() {
        savedDataDir = System.getProperty(ReadablePaths.PROP_APP_DATA_DIR)
        dataRoot = Files.createDirectories(tempDir.resolve("app-data"))
        Files.createDirectories(agentBase)
        System.setProperty(ReadablePaths.PROP_APP_DATA_DIR, dataRoot.toString())
    }

    @AfterEach
    fun tearDown() {
        if (savedDataDir == null) System.clearProperty(ReadablePaths.PROP_APP_DATA_DIR)
        else System.setProperty(ReadablePaths.PROP_APP_DATA_DIR, savedDataDir!!)
    }

    /** `<data>/agent/<day>/<uuid>/<name>`, with the day spelled `yyyy/MM/dd`. */
    private fun workspaceOf(day: String, uuid: String, name: String): Path =
        Files.createDirectories(agentBase.resolve(day).resolve(uuid).resolve(name))

    private fun decide(authorizer: Authorizer, effect: Effect, path: Path, grant: CapabilityId): Decision =
        authorizer.decide(
            Request(
                tool = "test.task-workspaces",
                effects = setOf(effect),
                targets = listOf(Target.PathTarget(path)),
                capabilityId = grant,
            ),
        )

    @Test
    @DisplayName("siblings and the shared workspace are in scope, the archive and skills are not")
    fun siblingsInScopeArchiveAndSkillsNot() {
        val mine = workspaceOf("2026/11/22", uuidA, "workspace.task-a")
        val sibling = workspaceOf("2026/11/22", uuidB, "workspace.task-b")
        val shared = workspaceOf("2026/11/21", uuidC, "workspace")
        val archive = workspaceOf("2026/11/22", uuidB, "workspace.archive")
        val skills = Files.createDirectories(agentBase.resolve("skills").resolve("pack"))
        // A `workspace.*` directory that is not under the agent layout is not a task workspace
        // either: the shape is `<agent>/<yyyy>/<MM>/<dd>/<uuid>/workspace[.<id>]`, not any name
        // that happens to start with "workspace.".
        val elsewhere = Files.createDirectories(tempDir.resolve("elsewhere").resolve("workspace.task-z"))

        val roots = TaskWorkspaces.writableRoots(mine)

        assertEquals(setOf(sibling, shared), roots.toSet(), "got $roots")
        for (excluded in listOf(archive, skills, elsewhere, mine)) {
            assertFalse(excluded in roots, "$excluded must not be in scope: $roots")
        }
    }

    @Test
    @DisplayName("a task workspace is recognised by layout, not by name alone")
    fun layoutIsChecked() {
        val sibling = workspaceOf("2026/11/22", uuidB, "workspace.task-b")
        val archive = workspaceOf("2026/11/22", uuidB, "workspace.archive")
        val byNameOnly = Files.createDirectories(tempDir.resolve("workspace.task-b"))
        val deepInside = Files.createDirectories(sibling.resolve("reports"))

        assertTrue(TaskWorkspaces.isTaskWorkspace(sibling))
        assertFalse(TaskWorkspaces.isTaskWorkspace(archive), "workspace.archive is not a workspace")
        assertFalse(TaskWorkspaces.isTaskWorkspace(byNameOnly), "the agent layout is required")
        assertFalse(TaskWorkspaces.isTaskWorkspace(deepInside), "only the workspace root is a root")
        assertTrue(TaskWorkspaces.isWorkspaceName("workspace"))
        assertTrue(TaskWorkspaces.isWorkspaceName("workspace.c1c660f9"))
        assertFalse(TaskWorkspaces.isWorkspaceName("workspace.archive"))
    }

    @Test
    @DisplayName("the day window and the root cap bound the scan")
    fun boundsApply() {
        val mine = workspaceOf("2026/11/22", uuidA, "workspace.task-a")
        val recent = workspaceOf("2026/11/22", uuidB, "workspace.task-b")
        val old = workspaceOf("2026/10/01", uuidC, "workspace.task-c")

        assertEquals(listOf(recent), TaskWorkspaces.writableRoots(mine, maxDays = 1))
        assertTrue(old in TaskWorkspaces.writableRoots(mine, maxDays = 400), "a wider window reaches it")

        repeat(5) { workspaceOf("2026/11/22", "uuid-$it", "workspace.task-$it") }
        assertEquals(2, TaskWorkspaces.writableRoots(mine, maxRoots = 2).size)
        assertTrue(TaskWorkspaces.writableRoots(mine, maxRoots = 0).isEmpty(), "a zero cap selects nothing")
    }

    @Test
    @DisplayName("the sandbox gets the day directory, a foreign root is passed through")
    fun sandboxRootsCoalesce() {
        val sibling = workspaceOf("2026/11/22", uuidB, "workspace.task-b")
        val other = workspaceOf("2026/11/22", uuidC, "workspace.task-c")
        val foreign = Files.createDirectories(tempDir.resolve("corpus"))

        val roots = TaskWorkspaces.sandboxRoots(listOf(sibling, other, foreign))

        assertEquals(
            listOf(agentBase.resolve("2026/11/22"), foreign),
            roots,
            "two workspaces of one day cost one grant; the foreign root is kept verbatim",
        )
        assertTrue(TaskWorkspaces.sandboxRoots(emptyList()).isEmpty())
    }

    @Test
    @DisplayName("the boundary refuses a sibling until the policy puts it in scope")
    fun boundaryFollowsTheScope() {
        val mine = workspaceOf("2026/11/22", uuidA, "workspace.task-a")
        val sibling = workspaceOf("2026/11/22", uuidB, "workspace.task-b")
        val deliverable = sibling.resolve("report.csv")
        Files.writeString(Files.createFile(deliverable), "a,b\n")

        val businessAsUsual = CodingComponents.boundaryAuthorizer(mine)
        // Only the file-system grant is asserted here: the shell grant carries the toolchain roots,
        // and one of them is the OS temp directory, so a `@TempDir` tree is lexically writable for
        // the shell whatever the task-workspace scope says (pre-existing, by design — `%TEMP%` is a
        // build location). The canonical fs grant has no such root and is the right probe.
        val refused = decide(businessAsUsual, Effect.FS_WRITE, deliverable, CodingComponents.CAP_FS_BOUNDARY)
        assertTrue(refused is Decision.Deny, "the fs grant must refuse the sibling before the widening: $refused")

        val widened = CodingComponents.boundaryAuthorizer(
            mine,
            additionalDirs = emptyList(),
            taskWorkspaces = TaskWorkspaces.writableRoots(mine),
        )
        for (grant in listOf(CodingComponents.CAP_FS_BOUNDARY, CodingComponents.CAP_SHELL_BOUNDARY)) {
            assertEquals(
                Decision.Allow,
                decide(widened, Effect.FS_WRITE, deliverable, grant),
                "$grant must allow the sibling once it is in scope",
            )
            assertEquals(
                Decision.Allow,
                decide(widened, Effect.FS_READ, deliverable, grant),
                "$grant must allow reading it too",
            )
        }

        // The widening is about task workspaces, not the agent tree: the archive and the skills
        // pack stay refused even with the sibling in scope.
        for (outOfScope in listOf(
            sibling.resolveSibling("workspace.archive").resolve("old.csv"),
            agentBase.resolve("skills").resolve("pack").resolve("run.ps1"),
        )) {
            val decision = decide(widened, Effect.FS_WRITE, outOfScope, CodingComponents.CAP_FS_BOUNDARY)
            assertTrue(decision is Decision.Deny, "$outOfScope must stay refused: $decision")
        }
    }

    @Test
    @DisplayName("the policy gate decides, and the manifest names every root")
    fun policyGateAndManifest() {
        val mine = workspaceOf("2026/11/22", uuidA, "workspace.task-a")
        val sibling = workspaceOf("2026/11/22", uuidB, "workspace.task-b")

        val enabled = CodingComponents.taskWorkspaceRoots(mine, AgentAccessPolicy())
        assertEquals(listOf(sibling), enabled)
        assertTrue(enabled[0] == sibling)

        assertTrue(
            CodingComponents.taskWorkspaceRoots(mine, AgentAccessPolicy(allowTaskWorkspaceWrites = false)).isEmpty(),
            "allowTaskWorkspaceWrites=false must resolve to no roots",
        )
        assertTrue(
            CodingComponents.taskWorkspaceRoots(mine, AgentAccessPolicy(unrestricted = true)).isEmpty(),
            "an unrestricted task needs no extra grant",
        )

        val manifest = CodingComponents.scopeManifest(mine, taskWorkspaces = enabled)
        val entry = manifest.single { it.kind == ScopeKind.TASK_WORKSPACE }
        assertEquals(sibling, entry.path)
        assertTrue(entry.effects.contains(Effect.FS_WRITE), "a sibling workspace is writable: ${entry.effects}")
        assertTrue(
            CodingComponents.CAP_FS_BOUNDARY in entry.grants && CodingComponents.CAP_SHELL_BOUNDARY in entry.grants,
            "both grants see it: ${entry.grants}",
        )
    }

    @Test
    @DisplayName("the file system writes into a sibling, and the preset still decides whether it may")
    fun fileSystemWritesThroughTheBoundary() {
        val mine = workspaceOf("2026/11/22", uuidA, "workspace.task-a")
        val sibling = workspaceOf("2026/11/22", uuidB, "workspace.task-b")
        val target = sibling.resolve("handover.json")

        val writer = CodingComponents.fs(mine, AgentAccessPolicy())
        val result = runBlocking { writer.writeFile(target.toString(), "{\"ok\":true}", createSnapshot = false) }
        assertTrue(result.startsWith("✓"), "write into a sibling must succeed: $result")
        assertEquals("{\"ok\":true}", Files.readString(target))

        val readOnlyTask = CodingComponents.fs(mine, AgentAccessPolicy(preset = AccessPreset.READ))
        val refused = runBlocking {
            readOnlyTask.writeFile(sibling.resolve("nope.json").toString(), "{}", createSnapshot = false)
        }
        assertFalse(Files.exists(sibling.resolve("nope.json")), "a read-only preset writes nothing")
        assertTrue(refused.contains("PERMISSION_DENIED") || refused.contains("PATH_DENIED"), refused)

        val closed = CodingComponents.fs(mine, AgentAccessPolicy(allowTaskWorkspaceWrites = false))
        val closedResult = runBlocking {
            closed.writeFile(sibling.resolve("closed.json").toString(), "{}", createSnapshot = false)
        }
        assertTrue(closedResult.contains("PATH_DENIED"), "the switch restores the workspace-only boundary: $closedResult")
    }
}
