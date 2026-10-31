package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Derives the real paths of a workspace's mirrored module roots.
 *
 * [WorkspaceMirror] links `<workspace>/<module>` to `<sourceRepo>/<module>` via a Windows junction or
 * a Unix symbolic link. When the file tools judge a path by its **real** location (see
 * [canonicalPath]), a write to `<workspace>/browser4-coding/src/...` resolves to the real checkout
 * outside the workspace. Without knowing those real targets, every module write would be refused as
 * out of scope — the whole point of `--fresh-workspace` is to keep the checkout writable through the
 * mirror.
 *
 * This object walks the workspace for directory entries that are links (junctions or symlinks) and
 * resolves where they really point. A target that still lives inside the workspace is not a mirror
 * and is ignored. Links are never descended into, so the walk stays within the small workspace
 * directory itself and never traverses the source tree.
 */
internal object MirrorRoots {

    /**
     * Real paths of the mirrored roots under [workspaceRoot].
     *
     * @return the canonical real paths that the workspace's directory links point at, excluding any
     *   that resolve back inside [workspaceRoot] itself.
     */
    fun derive(workspaceRoot: Path): Set<Path> {
        val realRoot = runCatching { workspaceRoot.toRealPath() }
            .getOrDefault(workspaceRoot.toAbsolutePath().normalize())
        if (!Files.isDirectory(realRoot)) return emptySet()

        val roots = linkedSetOf<Path>()

        fun visit(dir: Path) {
            val entries = runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
            for (entry in entries) {
                // Detect a link (Unix symlink or Windows junction) by comparing the absolute path
                // with its real path: a real directory resolves to itself, a link resolves to its
                // target. This is the same trick CodingAgentFileSystem.resolveLinkTarget uses and it
                // is the only cross-platform check that catches NTFS junctions (which NIO's
                // isSymbolicLink / readSymbolicLink do not recognize).
                val absolute = runCatching { entry.toAbsolutePath().normalize() }.getOrDefault(entry)
                val real = runCatching { entry.toRealPath() }.getOrNull()
                val isLink = real != null && !samePath(real, absolute)
                if (isLink) {
                    if (Files.isDirectory(entry) && real != null && !real.startsWith(realRoot)) {
                        roots.add(real.normalize())
                    }
                    continue // never descend into a linked tree
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    visit(entry)
                }
            }
        }

        visit(realRoot)
        return roots
    }

    private fun samePath(a: Path, b: Path): Boolean {
        val win = System.getProperty("os.name").lowercase().contains("win")
        return if (win) a.toString().equals(b.toString(), ignoreCase = true) else a == b
    }
}
