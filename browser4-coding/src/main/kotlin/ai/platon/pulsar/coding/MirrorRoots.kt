package ai.platon.pulsar.coding

import java.nio.file.Files
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
 * This object looks for directory entries that are links (junctions or symlinks) and resolves where
 * they really point. A target that still lives inside the workspace is not a mirror and is ignored;
 * a target that is not a module of a checkout is ignored too, because the result is a **writable**
 * root (see [isCheckoutLinkedModule]).
 * Links are never descended into, so the walk never traverses the source tree.
 *
 * ## Bounded on purpose
 *
 * The result feeds the boundary [Authorizer] built on the first tool call, so this walk runs on
 * every cold [CodingAgentFileSystem] / [CodingAgentShell] instance. An unbounded recursive walk was
 * fine for a mirror workspace (a handful of links) but catastrophic for a workspace that **is** a
 * large checkout: measured on a 198k-file / 3.4 GB tree, `derive` took 100 s and `sourceRoot` 186 s,
 * i.e. the first `coding.grep` of the session spent minutes before scanning anything.
 *
 * The walk is therefore bounded by [MAX_DERIVE_DEPTH] and [MAX_DERIVE_ENTRIES]: a mirror link can
 * only live a couple of directories below the workspace root, and files are skipped entirely (they
 * cannot be mirrored roots), so the cost follows the directory count and not the file count.
 */
internal object MirrorRoots {

    /**
     * How deep below the workspace root a mirrored module can sit.
     *
     * `WorkspaceMirror` links `<workspace>/<module>` and nested reactor modules such as
     * `<workspace>/browser4-plugins/browser4-testprobe`; one extra level is kept as margin.
     */
    private const val MAX_DERIVE_DEPTH = 3

    /**
     * Entry budget of one derivation.
     *
     * A mirror workspace holds tens of entries, so this never truncates a real mirror; it only
     * keeps a huge non-mirror workspace from paying a full walk on its first tool call. Measured
     * on a 198k-file tree, 2,000 entries cost ~1 s (cold metadata); 5,000 cost 2.6 s.
     */
    private const val MAX_DERIVE_ENTRIES = 2_000

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
        var budget = MAX_DERIVE_ENTRIES

        fun visit(dir: Path, depth: Int) {
            if (depth > MAX_DERIVE_DEPTH || budget <= 0) return
            val entries = runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
            for (entry in entries) {
                if (budget-- <= 0) return
                // Only directories can be mirrored module roots. Skipping files keeps the walk
                // proportional to the directory count instead of the file count.
                if (runCatching { Files.isDirectory(entry) }.getOrDefault(false).not()) continue
                // Detect a link (Unix symlink or Windows junction) by comparing the absolute path
                // with its real path: a real directory resolves to itself, a link resolves to its
                // target. This is the same trick CodingAgentFileSystem.resolveLinkTarget uses and it
                // is the only cross-platform check that catches NTFS junctions (which NIO's
                // isSymbolicLink / readSymbolicLink do not recognize).
                val absolute = runCatching { entry.toAbsolutePath().normalize() }.getOrDefault(entry)
                val real = runCatching { entry.toRealPath() }.getOrNull()
                val isLink = real != null && !samePath(real, absolute)
                if (isLink) {
                    // A link target is a GRANT (the boundary authorizer treats it as writable and
                    // the low-integrity backend lowers its mandatory label), so it must be a module
                    // of a checkout — never an arbitrary folder a link happens to point at.
                    if (real != null && !real.startsWith(realRoot) && isCheckoutLinkedModule(real)) {
                        roots.add(real.normalize())
                    }
                    continue // never descend into a linked tree
                }
                visit(entry, depth + 1)
            }
        }

        visit(realRoot, 0)
        return roots
    }

    /**
     * Whether a link target is a module of a checkout, i.e. a real mirror rather than an arbitrary
     * folder a workspace link points at.
     *
     * The boundary authorizer treats every returned mirror root as **writable**, and
     * [ai.platon.pulsar.coding.sandbox.WindowsLowIntegritySandbox] lowers its mandatory label so the
     * sandboxed child can write there. Accepting any link target therefore turned a workspace
     * junction into a grant to whatever it named: `permission-e2e` A17 measured exactly that on
     * 2026-11-20 — the canary directory (outside every readable root) came back with a Low label and
     * the low-integrity child wrote into it, so a model that could create a junction (an inline
     * `powershell`/`python` body is not tier-checked) escaped the boundary.
     *
     * A mirror target is a module *of a checkout*: walking up a bounded number of levels finds the
     * repository markers the mirror itself was derived from. Anything else is ignored — the link
     * still reads through the workspace path, but a write through it is refused by the policy (real
     * path outside every writable root) and, because the target keeps its own label, by the kernel.
     */
    private fun isCheckoutLinkedModule(real: Path): Boolean {
        if (!runCatching { Files.isDirectory(real) }.getOrDefault(false)) return false
        var candidate: Path? = runCatching { real.toAbsolutePath().normalize() }.getOrNull() ?: return false
        var hops = 0
        while (candidate != null && hops <= MAX_SOURCE_ROOT_HOPS) {
            if (isCheckoutRoot(candidate)) return true
            candidate = candidate.parent
            hops++
        }
        return false
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
    private val CHECKOUT_MARKERS = listOf(
        "pom.xml", "Cargo.toml", "package.json", "pyproject.toml", "setup.py", "go.mod",
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "Gemfile",
    )

    private fun isCheckoutRoot(dir: Path): Boolean =
        Files.isDirectory(dir.resolve(".git")) ||
            Files.isRegularFile(dir.resolve(".git")) ||
            CHECKOUT_MARKERS.any { Files.isRegularFile(dir.resolve(it)) }

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
