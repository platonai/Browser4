package ai.platon.pulsar.agentic.cli

import java.nio.file.Path

/**
 * Where a child of the agent keeps the CLI's session state when it runs inside the task's OS
 * boundary.
 *
 * The CLI defaults to `~/.browser4`, and the agent's boundary makes that directory **readable but
 * not writable** rather than unreadable: the Windows Low-integrity backend (`windows-low-il`, the
 * default coding backend) hands the child a Low token, a Low subject may read Medium objects, and
 * the directory's mandatory integrity label refuses its writes. `attach`/`open` therefore relocate
 * the state file to the CLI's CWD-relative fallback while the *next* invocation reads the (still
 * empty) default directory and answers `Session required` — a success report followed by a failure
 * (2026-11-20 live run).
 *
 * Pointing every sandboxed channel at `<workspace>/.b4scratch/cli-state` fixes that at the source:
 * the workspace is granted to the boundary, `b4.run` and `coding.shell` agree on one state, and the
 * state dies with the task workspace instead of accumulating in the operator's profile (which also
 * holds the backend's LLM keys under `config/conf-enabled/` — that directory must stay out of the
 * boundary's reach, so relocating the state is the permission-safe fix, not widening the grant).
 */
object CliStateEnv {
    /**
     * Workspace-relative state directory.
     *
     * `.b4scratch/` is already the task's scratch root, is git-ignored and is excluded from the
     * change set, so a relocated session never shows up as a deliverable.
     */
    const val DIR_IN_WORKSPACE = ".b4scratch/cli-state"

    /** Backend id meaning "no OS boundary" — see `ExecutionSandboxes.configuredId`. */
    private const val NO_BOUNDARY = "off"

    /** `<workspace>/.b4scratch/cli-state` for a task workspace that exists. */
    fun dirInWorkspace(workspace: Path): String = workspace.resolve(DIR_IN_WORKSPACE).toString()

    /**
     * The `BROWSER4_CLI_STATE_DIR` a child of the agent must carry, or `null` to keep the CLI's flat
     * default (`~/.browser4`).
     *
     * @param operatorOverride the backend's own `BROWSER4_CLI_STATE_DIR`; it wins, because an
     *   operator who relocated the state already granted that directory to the task.
     * @param boundaryId the backend the child runs under, or `null` when none could be resolved.
     * @param workspace the task workspace the boundary grants, or `null` before one is bound.
     */
    fun stateDir(operatorOverride: String?, boundaryId: String?, workspace: Path?): String? {
        operatorOverride?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Unsandboxed runs keep the shared layout: the shell then sees the same session as the
        // operator's own terminal, which is what `agent run` users expect.
        if (boundaryId.isNullOrBlank() || boundaryId == NO_BOUNDARY) return null
        return workspace?.let(::dirInWorkspace)
    }
}
