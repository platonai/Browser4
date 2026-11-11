package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * Folders granted to one task besides its workspace — `agent run --add-dir` on the CLI,
 * `command.run(addDirs = [...])` on the backend.
 *
 * The grant is **scope, not power**: an added folder joins the boundary grants (read + write, and
 * therefore usable as a shell working directory) for the task, while the access preset still
 * decides whether anything may be written there (`--access read` keeps it read-only) and the
 * credential guard still refuses the OS stores. See [AgentAccessPolicy.additionalDirs].
 *
 * ## Why validate here
 *
 * The CLI resolves its values against the shell directory it was typed in and only forwards the
 * resulting absolute paths (the backend runs in the runtime bundle, so a relative path would name a
 * different directory). A raw API caller can still send anything, so this is the single place that
 * decides what a valid grant is — and it fails loudly instead of handing the runner a folder that
 * does not exist:
 *
 * - a leading `~` is expanded against `user.home` (the property value is not shell-expanded);
 * - the path must be absolute — a relative one has no meaning on the backend's working directory;
 * - the path must be an existing directory: a grant is never created implicitly, because a typo
 *   would then silently become an empty folder the run writes into;
 * - the OS credential stores ([ReadablePaths.ALWAYS_REFUSED]) are refused up front (the runtime
 *   guard refuses them again).
 */
object AdditionalDirs {

    /**
     * Upper bound on the folders one task may be granted.
     *
     * A task legitimately needs a handful (a sibling checkout, a corpus, a download target). The
     * bound keeps a pathological caller from turning the boundary into "the whole machine" while
     * still being far above any real use.
     */
    const val MAX_DIRS = 32

    /**
     * Resolves one `--add-dir` value.
     *
     * @throws IllegalArgumentException with an actionable message when [raw] is blank, not
     *   absolute, not an existing directory, a credential store, or not a valid path at all.
     */
    fun resolve(raw: String): Path {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "addDirs entries must be non-blank absolute paths" }
        val expanded = expandHome(trimmed)
        val path = runCatching { Path.of(expanded) }.getOrElse {
            throw IllegalArgumentException("addDirs entry '$raw' is not a valid path on this machine: ${it.message}")
        }
        require(path.isAbsolute) {
            "addDirs entry '$raw' must be an absolute path. The CLI resolves --add-dir against the " +
                "directory it was typed in; a caller sending the raw argument has to do the same " +
                "(~ is expanded on the backend, a relative path is not)"
        }
        val normalized = path.normalize()
        // Credentials are checked before existence so a credential location reports the real
        // reason ("stays refused") instead of the misleading "does not exist".
        require(!ReadablePaths.isAlwaysRefused(normalized)) {
            "addDirs entry '$raw' names an OS credential store, which stays refused whatever the " +
                "task asks for (${ReadablePaths.ALWAYS_REFUSED.joinToString(", ")})"
        }
        require(Files.isDirectory(normalized)) {
            "addDirs entry '$raw' is not an existing directory on the backend machine " +
                "(${normalized.toAbsolutePath()}). Create the folder first — an added folder is " +
                "never created implicitly, because a typo would then become an empty directory the " +
                "run writes into."
        }
        return normalized.toAbsolutePath()
    }

    /**
     * Resolves every granted folder, in the order given.
     *
     * @throws IllegalArgumentException when one entry is invalid (the message names it) or when more
     *   than [MAX_DIRS] folders are requested.
     */
    fun resolveAll(raw: Collection<String>, max: Int = MAX_DIRS): List<Path> {
        if (raw.isEmpty()) return emptyList()
        require(raw.size <= max) {
            "addDirs takes at most $max folders per task (got ${raw.size}); grant the ones the task " +
                "actually needs rather than a list of roots"
        }
        val resolved = raw.map { resolve(it) }
        // Two spellings of the same folder (a junction, a trailing separator) are one grant.
        return resolved.distinct()
    }

    /**
     * Expands a leading `~` against `user.home`, leaving every other spelling alone.
     *
     * An unexpanded `~` would otherwise be rejected as relative — the property value a raw caller
     * sends is not shell-expanded.
     */
    internal fun expandHome(raw: String): String {
        val rest = when {
            raw == "~" -> return home()?.toString() ?: raw
            raw.startsWith("~/") || raw.startsWith("~\\") -> raw.substring(2)
            else -> return raw
        }
        val home = home() ?: return raw
        return home.resolve(rest).toString()
    }

    private fun home(): Path? = runCatching {
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    }.getOrNull()

    /** A one-line description for logs and the permission report; `none` when nothing is granted. */
    fun describe(dirs: Collection<Path>): String =
        dirs.map { it.toString() }.sorted().joinToString(", ").ifEmpty { "none" }
}
