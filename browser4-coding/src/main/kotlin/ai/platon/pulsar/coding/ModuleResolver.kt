package ai.platon.pulsar.coding

/**
 * Maps repository paths to Maven modules and decides whether a verification
 * verdict covers the modules a task changed.
 *
 * This was extracted from `CodingToolExecutor.inferModule` so the isolation gate
 * and the build tool share one resolver. Two copies of the "which module owns
 * this path" heuristic drift apart, and a gate that answers a different question
 * than the build command is worse than no gate at all.
 *
 * The resolver is deliberately tolerant about the input shape: relative paths
 * (`browser4-coding/src/...`), absolute paths and Windows separators all resolve
 * to the same module, because the caller may hand it either the changed-file list
 * from `git status` or an absolute path from a tool result.
 */
object ModuleResolver {

    /** Documentation suffixes that never need a build before they are merged. */
    private val DOC_SUFFIXES = listOf(".md", ".txt", ".adoc", ".rst")

    /** Documentation directories (repo-relative). */
    private val DOC_PREFIXES = listOf("docs/", "docs-dev/", "coworker/tasks/")

    /**
     * Whether [relativePath] is documentation — a change that needs no build.
     *
     * A `.md` file under a module (`browser4-coding/README.md`) counts as
     * documentation too: it cannot break compilation.
     */
    fun isDocumentation(relativePath: String): Boolean {
        val norm = normalize(relativePath)
        if (norm.isEmpty()) return false
        if (DOC_PREFIXES.any { norm.startsWith(it) }) return true
        val name = norm.substringAfterLast('/')
        return DOC_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }
    }

    /**
     * The module that owns [path], or null when the path is not inside one.
     *
     * [graph] is the workspace's own module graph ([ModuleGraph.cachedGraph], when the
     * caller can see the tree). It wins over the static [ModuleMap] snapshot because it
     * also knows a **foreign** workspace's modules: the snapshot only ever contains
     * Browser4's, so a nested module such as `svc/core` was resolved as `core` — a name
     * `mvn -pl` cannot select. Known modules from [ModuleMap.MODULES] remain the fallback
     * (and the historical best-effort heuristic stays for paths outside every module —
     * `coding.mvnBuild` has always accepted those, and changing that would be a
     * behaviour regression).
     */
    fun moduleOf(path: String, graph: ModuleGraph.Graph? = null): String? {
        val norm = normalize(path)
        if (norm.isEmpty()) return null
        val srcIdx = norm.indexOf("/src/")
        val candidate = if (srcIdx > 0) norm.substring(0, srcIdx) else norm.substringBeforeLast('/', "")
        if (candidate.isEmpty()) return null

        // Exact module first, then the longest suffix match (nested modules such as
        // browser4-core/browser4-common, and absolute paths ending in the module dir).
        graph?.nodes?.keys
            ?.filter { candidate == it || candidate.endsWith("/$it") }
            ?.maxByOrNull { it.length }
            ?.let { return it }
        ModuleMap.MODULES
            .filter { candidate == it || candidate.endsWith("/$it") }
            .maxByOrNull { it.length }
            ?.let { return it }
        if (candidate == ModuleMap.CLI_CRATE_DIR || candidate.endsWith("/${ModuleMap.CLI_CRATE_DIR}")) {
            return ModuleMap.CLI_CRATE
        }

        val segments = candidate.split('/').filter { it.isNotEmpty() }
        return when {
            segments.size >= 2 && segments[segments.size - 2].startsWith("browser4-") ->
                segments.takeLast(2).joinToString("/")
            else -> segments.lastOrNull()
        }
    }

    /** The modules owning [changedFiles], ignoring paths outside every module. */
    fun modulesOf(changedFiles: Collection<String>, graph: ModuleGraph.Graph? = null): Set<String> =
        changedFiles.mapNotNull { moduleOf(it, graph) }.toSet()

    /**
     * Whether verifying [verifier] also compiles every module in [changed].
     *
     * `mvn -pl <verifier> -am` builds the verifier **and its dependencies**, so a
     * verifier covers a changed module when the verifier is that module or one of
     * its transitive dependents.
     *
     * The dependents come from [graph] whenever it knows the verifier: the static
     * [ModuleMap] is a snapshot of *this* repository, so for any other Maven project it
     * answers "no dependent" and refuses a legitimate merge (the two-module toy repo in
     * evaluation §11: `app` depends on `lib`, `coding.verify(module=app)` was refused for
     * `lib`). An absent or foreign graph keeps the static behaviour.
     */
    fun covers(verifier: String?, changed: Set<String>, graph: ModuleGraph.Graph? = null): Boolean {
        if (changed.isEmpty()) return true
        val v = verifier?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (graph != null && v in graph.nodes) {
            return changed.all { m -> m == v || v in ModuleGraph.transitiveDependents(graph, m) }
        }
        return changed.all { m -> m == v || v in runCatching { ModuleMap.transitiveDependents(m) }.getOrDefault(listOf(m)) }
    }

    /** Whether at least one changed file can break a build. */
    fun requiresVerification(changedFiles: Collection<String>): Boolean =
        changedFiles.any { !isDocumentation(it) }

    private fun normalize(path: String): String =
        path.replace('\\', '/').trim().removePrefix("./").trimEnd('/')
}
