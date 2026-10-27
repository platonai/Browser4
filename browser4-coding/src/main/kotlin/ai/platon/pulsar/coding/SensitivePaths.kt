package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared enforcement for [CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS].
 *
 * Both the file tools and the shell consult this object so that "credential location" means the
 * same thing in both layers, including the `~` expansion and the real-path check used for
 * workspace junctions.
 */
internal object SensitivePaths {

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
}
