package ai.platon.pulsar.coding

import java.nio.file.Path

/**
 * Single construction point for the coding components consumed by every agent entry path.
 *
 * Before this factory existed, `AgentToolManager`, `CodingToolExecutor` and `MCPToolController`
 * each hand-built a [CodingAgentShell] and a [CodingAgentFileSystem] from the same JVM properties,
 * so a new switch had to be wired in three places and could silently drift. New options are added
 * here only.
 *
 * The factory maps the policy fields onto the component constructors; it does **not** enforce the
 * preset. Preset enforcement lives in [AgentAccessPolicy.allowsTool] and in the tool executor, so
 * that a component created for an internal purpose (for example the build runner) cannot become a
 * bypass. The component-level flags ([CodingAgentShell.allowExecution], [CodingAgentFileSystem.readOnly])
 * are projections of the preset, not its definition: they make the *component* refuse, so a direct
 * holder of a shell or file-system instance cannot re-open what the tool gate closed.
 */
object CodingComponents {

    /**
     * Builds a [CodingAgentShell] honouring the policy's shell-related switches.
     *
     * `allowExecution` is a projection of [AgentAccessPolicy.allowsExec]: under a preset that does not
     * grant execution, the model-facing shell refuses every command. The build entry points do not use
     * this instance — they use [buildShell] — so `read-build` can still run the project build.
     *
     * @param root the workspace root used as the shell base directory
     * @param policy the policy to read `allowExternalAccess` / `allowNetwork` / `allowDestructive` /
     *   `sensitivePaths` / `protectedDirs` from
     */
    fun shell(root: Path, policy: AgentAccessPolicy = AgentAccessPolicy.serverDefault): CodingAgentShell =
        CodingAgentShell(
            baseDir = root,
            allowNetwork = policy.allowNetwork,
            allowDestructive = policy.allowDestructive,
            allowExternalAccess = policy.allowExternalAccess,
            allowExecution = policy.allowsExec(),
            sensitivePaths = policy.sensitivePaths,
            protectedDirs = policy.protectedDirs,
        )

    /**
     * Builds the internal build shell — the one `coding.mvnBuild` / `verify` / `validate` /
     * `devTask` run through.
     *
     * A preset that forbids execution must still be able to run the project's own build, otherwise
     * `read-build` (whose whole point is "read + self-check") would have no way to build at all.
     * Two shells therefore exist per target: the model-facing one ([shell], used by `coding.shell*`)
     * and this one, which is only reachable through the build entry points.
     *
     * Under [AccessPreset.FULL] the model shell is returned unchanged: builds share its session
     * environment and command history exactly as before. Under a restricted preset a **separate**
     * instance is built in the [CodingAgentShell] build-only tier, so the build never inherits a
     * permissive command set.
     *
     * @param root the workspace root used as the shell base directory
     * @param policy the policy to read the shell switches from
     * @param modelShell the target's model-facing shell, reused under [AccessPreset.FULL]
     */
    fun buildShell(
        root: Path,
        policy: AgentAccessPolicy = AgentAccessPolicy.serverDefault,
        modelShell: CodingAgentShell? = null,
    ): CodingAgentShell {
        if (policy.allowsExec()) return modelShell ?: shell(root, policy)
        return CodingAgentShell(
            baseDir = root,
            buildOnly = true,
            allowNetwork = policy.allowNetwork,
            allowDestructive = policy.allowDestructive,
            allowExternalAccess = policy.allowExternalAccess,
            sensitivePaths = policy.sensitivePaths,
            protectedDirs = policy.protectedDirs,
        )
    }

    /**
     * Builds a [CodingAgentFileSystem] honouring the policy's file-related switches.
     *
     * `readOnly` is a projection of [AgentAccessPolicy.allowsFileMutation]: `read` and `read-build`
     * get a file system whose mutators refuse, so a mutating call that reaches the file system does
     * not depend on the tool gate having run first.
     *
     * @param root the workspace root
     * @param policy the policy to read `allowExternalAccess` / `allowDestructive` / `protectedFiles` from
     * @param persistSnapshots whether file snapshots survive a restart (the MCP path enables it)
     */
    fun fs(
        root: Path,
        policy: AgentAccessPolicy = AgentAccessPolicy.serverDefault,
        persistSnapshots: Boolean = false,
    ): CodingAgentFileSystem = CodingAgentFileSystem(
        workspaceRoot = root,
        allowExternalAccess = policy.allowExternalAccess,
        allowDestructive = policy.allowDestructive,
        readOnly = !policy.allowsFileMutation(),
        protectedFiles = policy.protectedFiles,
        protectedDirs = policy.protectedDirs,
        sensitivePaths = policy.sensitivePaths,
        persistSnapshots = persistSnapshots,
    )
}
