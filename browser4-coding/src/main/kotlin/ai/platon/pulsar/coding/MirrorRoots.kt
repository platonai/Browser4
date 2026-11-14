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

    /**
     * The checkout [workspaceRoot] mirrors, or `null` when the workspace is not a mirror.
     *
     * The mirrored module roots are all paths *inside* one checkout, so the checkout is the common
     * ancestor of the modules the workspace links. It is then walked up while the parent is itself a
     * Maven project (`browser4-tests/pulsar-tests-common` is a module of `browser4-tests`, which is
     * a module of the reactor), and only accepted when the result really looks like a checkout root
     * ([CHECKOUT_MARKERS]) — a link to an unrelated directory must not turn its parent into a grant.
     *
     * This is the "code this task is developing" root: the mirror makes the scratch workspace *look*
     * like the repository, but only the real checkout has `.git`, the non-module folders (`bin/`,
     * `cli/`, `docs/`) and the untracked fixtures a self-development task has to edit. Its write
     * scope is a boundary grant ([CodingComponents.scopeManifest]'s `SOURCE` entry); the protected
     * files/dirs and the credential rails stay in force.
     *
     * @param workspaceRoot the agent workspace (`<app data>/agent/<date>/<uuid>/workspace.<task>`)
     */
    fun sourceRoot(workspaceRoot: Path): Path? {
        val mirrors = derive(workspaceRoot)
        if (mirrors.isEmpty()) return null
        var candidate = commonAncestor(mirrors)?.toAbsolutePath()?.normalize() ?: return null
        var hops = 0
        while (hops < MAX_SOURCE_ROOT_HOPS) {
            val parent = candidate.parent ?: break
            if (!Files.isRegularFile(parent.resolve("pom.xml"))) break
            candidate = parent
            hops++
        }
        return candidate.takeIf { isCheckoutRoot(it) }
    }

    /** Depth limit for walking a nested module up to its reactor root. */
    private const val MAX_SOURCE_ROOT_HOPS = 4

    /** Files/dirs that make a directory a checkout root rather than a module or an accident. */
    private val CHECKOUT_MARKERS = listOf("pom.xml", "Cargo.toml", "package.json")

    private fun isCheckoutRoot(dir: Path): Boolean =
        Files.isDirectory(dir.resolve(".git")) || CHECKOUT_MARKERS.any { Files.isRegularFile(dir.resolve(it)) }

    /** The deepest directory that contains every path in [paths], or `null` for mixed roots. */
    private fun commonAncestor(paths: Collection<Path>): Path? {
        val normalized = paths.map { it.toAbsolutePath().normalize() }
        val first = normalized.firstOrNull() ?: return null
        var common: Path = first
        for (path in normalized.drop(1)) {
            var candidate: Path? = common
            while (candidate != null && !path.startsWith(candidate)) {
                candidate = candidate.parent
            }
            common = candidate ?: return null
        }
        return common
    }

    private fun samePath(a: Path, b: Path): Boolean {
        val win = System.getProperty("os.name").lowercase().contains("win")
        return if (win) a.toString().equals(b.toString(), ignoreCase = true) else a == b
    }
}
