package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared enforcement for [CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS].
 *
 * Both the file tools and the shell consult this object so that "credential location" means the
 * same thing in both layers, including the `~` expansion and the real-path check used for
 * workspace junctions. The task-input materializer in the agent layer (`TaskInputs`) is the third
 * caller: it must never copy a credential location into the workspace.
 *
 * Two questions are kept apart on purpose:
 *
 * - [isSensitive] — "does this path lie in a configured credential root?" A pure membership test,
 *   used by `TaskInputs` and by tests;
 * - [isRefused] — "must this path be refused *now*?" It adds the readable-root relaxation
 *   ([ReadablePaths]) and the OS credential stores that are never relaxed. The file tools and the
 *   shell ask the latter; keeping the pure test public means a caller that must never relax (task
 *   inputs) cannot accidentally inherit the relaxation.
 *
 * Public so the agent layer reuses the same definition instead of re-deriving it.
 */
object SensitivePaths {

    /** Expands `~`-relative entries against `user.home`; other entries are taken as-is. */
    fun roots(entries: Set<String>): List<Path> {
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
        return entries.mapNotNull { entry ->
            val e = entry.trim()
            when {
                e.isEmpty() -> null
                e == "~" -> home
                e.startsWith("~/") || e.startsWith("~\\") -> home.resolve(e.substring(2)).normalize()
                else -> Path.of(e).toAbsolutePath().normalize()
            }
        }
    }

    /**
     * Whether [candidate] lies inside one of [roots].
     *
     * Checks the lexical path and, when the path exists, its real path: a junction inside the
     * workspace can resolve into a location outside it.
     */
    fun isSensitive(candidate: Path, roots: List<Path>): Boolean {
        if (roots.isEmpty()) return false
        val lexical = candidate.toAbsolutePath().normalize()
        if (roots.any { lexical.startsWith(it) }) return true
        val real = runCatching {
            if (Files.exists(lexical)) lexical.toRealPath() else null
        }.getOrNull() ?: return false
        return roots.any { real.startsWith(it) }
    }

    /**
     * Whether [candidate] must be refused as a credential location under the current rails.
     *
     * The order matters:
     *
     * 1. an OS credential store ([ReadablePaths.ALWAYS_REFUSED]) is refused whatever the readable
     *    roots say, so adding `$HOME` as a readable root cannot expose `~/.ssh`;
     * 2. a path in no sensitive root is fine;
     * 3. a path in a sensitive root is refused **unless** it is inside a readable root — the
     *    Browser4 data root is on the default sensitive list, and the relaxation is exactly what
     *    makes the agent's own logs and data readable
     *    ([ReadablePaths]).
     *
     * @param roots home-expanded sensitive roots ([roots] applied to the policy's entries)
     * @param readableRoots the readable roots in effect for this call
     */
    fun isRefused(candidate: Path, roots: List<Path>, readableRoots: List<Path>): Boolean {
        if (ReadablePaths.isAlwaysRefused(candidate)) return true
        if (!isSensitive(candidate, roots)) return false
        return !ReadablePaths.isReadable(candidate, readableRoots)
    }
}
