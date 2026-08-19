package ai.platon.pulsar.coding

import java.nio.file.Path

/**
 * Central resolution of the coding workspace for Browser4's self-coding
 * capability (the `coding_*` tools and the CLI `code` command family).
 *
 * The workspace root defaults to the backend JVM working directory
 * (`user.dir`), which is the runtime bundle directory when the server is
 * launched by the CLI daemon — usually NOT where the user's code lives.
 * Point it at a real repository with:
 *
 * ```
 * -Dbrowser4.agent.workspace=<path>                              (JVM system property)
 * BROWSER4_SERVER_OPTS=-Dbrowser4.agent.workspace=<path> ...     (CLI daemon env var)
 * ```
 *
 * Related switches:
 * - `browser4.agent.allowExternalAccess=true` — permit absolute paths
 *   outside the workspace root (off by default).
 * - `browser4.agent.allowDestructive=false` — deny rm/del/mv/cp/kill
 *   style operations (on by default).
 */
object CodingWorkspace {

    /**
     * The workspace root for all coding file operations.
     *
     * Computed on every access (not cached) so tests and runtime
     * reconfiguration can switch the property.
     */
    val workspaceRoot: Path
        get() {
            val configured = System.getProperty("browser4.agent.workspace")
            return if (!configured.isNullOrBlank()) {
                Path.of(configured).toAbsolutePath().normalize()
            } else {
                Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            }
        }

    /** Whether absolute paths outside the workspace root are permitted. */
    val allowExternalAccess: Boolean
        get() = System.getProperty("browser4.agent.allowExternalAccess", "false").toBoolean()

    /** Whether destructive file operations (delete/move/copy) are permitted. */
    val allowDestructive: Boolean
        get() = System.getProperty("browser4.agent.allowDestructive", "true") != "false"
}
