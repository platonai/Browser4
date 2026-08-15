package ai.platon.pulsar.coding

/**
 * Runtime module graph for the Browser4 multi-module repository — the
 * anti-staleness answer to the static [ModuleMap] snapshot.
 *
 * Instead of a hand-maintained module → dependents map (which drifts when the
 * module topology changes), this scans the repository's REAL pom.xml files and
 * rebuilds the graph: module path, artifactId, parent artifact, and internal
 * `<dependency>` edges (`groupId` = ai.platon.pulsar / ai.platon).
 *
 * Pure string/regex analysis of pom content — zero dependencies. Callers walk
 * the repo for pom.xml files and pass them in as (module dir → content).
 *
 * Usage (from the coding domain):
 * ```
 * val poms = <relative module dir> to <pom content> map
 * val graph = ModuleGraph.build(poms)
 * ModuleGraph.transitiveDependents(graph, "browser4-coding")  // [browser4-coding, browser4-agentic, ...]
 * ModuleGraph.drift(graph, ModuleMap.MODULES)                // real modules the static map missed
 * ```
 */
object ModuleGraph {

    /** One Maven module parsed from its pom.xml. */
    data class ModuleNode(
        /** Module directory relative to the repo root, e.g. "browser4-coding" or "browser4-plugins/browser4-seo". */
        val path: String,
        val artifactId: String,
        /** Artifact ids this module depends on (parent artifact + internal <dependency>s). */
        val dependencies: List<String>,
    )

    /** A parsed pom: the module's own artifactId, its parent artifactId, internal dep artifactIds. */
    data class PomInfo(
        val artifactId: String,
        val parentArtifactId: String?,
        val internalDependencies: List<String>,
    )

    /** The full graph: module-path index + artifactId → module-path index. */
    data class Graph(
        val nodes: Map<String, ModuleNode>,
        val artifactIndex: Map<String, String>,
    )

    private val PARENT_BLOCK = Regex("""<parent>.*?</parent>""", RegexOption.DOT_MATCHES_ALL)
    private val ARTIFACT_TAG = Regex("""<artifactId>([^<]+)</artifactId>""")
    // Blocks whose <dependency> entries are NOT compile edges: dependencyManagement
    // (version pinning only) and build/plugin <dependencies> (plugin classpath).
    private val NON_EDGE_BLOCKS = listOf(
        Regex("""<dependencyManagement>.*?</dependencyManagement>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<build>.*?</build>""", RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Parse one pom.xml into [PomInfo]. The module's own artifactId is the
     * first `<artifactId>` OUTSIDE the `<parent>` block (the parent artifact
     * is captured separately). Returns null when no own artifactId is found.
     */
    fun parsePom(content: String): PomInfo? {
        val parentMatch = PARENT_BLOCK.find(content)
        val parent = parentMatch?.let { ARTIFACT_TAG.find(it.value)?.groupValues?.get(1) }
        val own = PARENT_BLOCK.replaceFirst(content, "").let { ARTIFACT_TAG.find(it)?.groupValues?.get(1) }
            ?: return null

        // Internal dependencies: <dependency> blocks whose groupId is ai.platon(.pulsar),
        // excluding dependencyManagement / build / plugin blocks (not compile edges).
        val depsSource = NON_EDGE_BLOCKS.fold(content) { acc, regex -> regex.replace(acc, "") }
        val deps = Regex("""<dependency>.*?</dependency>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(depsSource).mapNotNull { block ->
                val group = Regex("""<groupId>([^<]+)</groupId>""").find(block.value)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                if (!group.startsWith("ai.platon")) return@mapNotNull null
                Regex("""<artifactId>([^<]+)</artifactId>""").find(block.value)?.groupValues?.get(1)
            }.distinct().toList()

        return PomInfo(own, parent, deps)
    }

    /**
     * Build the graph from a map of module-directory → pom.xml content.
     *
     * @param poms relative module dir → pom content (keys use '/', no trailing slash)
     */
    fun build(poms: Map<String, String>): Graph {
        val infos = poms.mapValues { (_, content) -> parsePom(content) }
            .filterValues { it != null }.mapValues { it.value!! }

        val nodes = linkedMapOf<String, ModuleNode>()
        val artifactIndex = mutableMapOf<String, String>()
        infos.forEach { (path, info) ->
            nodes[path] = ModuleNode(path, info.artifactId, listOfNotNull(info.parentArtifactId) + info.internalDependencies)
            artifactIndex.putIfAbsent(info.artifactId, path)
        }

        // Resolve dependency artifactIds to module paths; drop external/unresolved.
        val resolved = nodes.mapValues { (_, node) ->
            node.copy(dependencies = node.dependencies.mapNotNull { artifactIndex[it] }.distinct())
        }
        return Graph(resolved, artifactIndex)
    }

    /**
     * The standard repository scan: walk [root] for real module pom.xml files
     * (relative module dir → content). Skips `target/`, maven-archetype template
     * poms (`archetype-resources/`), and docs trees (`docs/`, `docs-dev/`) which
     * are not reactor modules. The repo-root aggregator pom is keyed as
     * `browser4` (its artifact id).
     */
    fun scanPoms(root: java.nio.file.Path): Map<String, String> {
        val poms = mutableMapOf<String, String>()
        java.nio.file.Files.walk(root).use { stream ->
            stream.filter { java.nio.file.Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }
                .filter { !it.toString().contains("\\target\\") && !it.toString().contains("/target/") }
                .filter { !it.toString().contains("archetype-resources") }
                .filter { !it.toString().contains("\\docs\\") && !it.toString().contains("/docs/") &&
                    !it.toString().contains("\\docs-dev\\") && !it.toString().contains("/docs-dev/") }
                .forEach { pom ->
                    val rel = root.relativize(pom.parent).toString().replace('\\', '/')
                    poms[if (rel.isEmpty()) "browser4" else rel] = java.nio.file.Files.readString(pom)
                }
        }
        return poms
    }

    /**
     * All modules transitively affected when [modulePath] changes: the module
     * itself plus every module that (directly or transitively) depends on it.
     */
    fun transitiveDependents(graph: Graph, modulePath: String): List<String> {
        if (modulePath !in graph.nodes) return listOf(modulePath)
        val result = linkedSetOf(modulePath)
        var frontier = listOf(modulePath)
        while (frontier.isNotEmpty()) {
            val next = graph.nodes.filterValues { node ->
                node.dependencies.any { it in frontier }
            }.keys.filter { result.add(it) }
            frontier = next
        }
        return result.toList()
    }

    /**
     * Real modules (from a live scan) that are missing from a static snapshot
     * like [ModuleMap.MODULES] — evidence the snapshot has drifted.
     */
    fun drift(graph: Graph, staticModules: List<String>): List<String> {
        return graph.nodes.keys.filter { it !in staticModules }.sorted()
    }

    /** Format the graph for the agent. */
    fun format(graph: Graph, staticModules: List<String>): String {
        val missing = drift(graph, staticModules)
        return buildString {
            appendLine("Module graph: ${graph.nodes.size} modules")
            graph.nodes.forEach { (path, node) ->
                val deps = if (node.dependencies.isEmpty()) "(none)" else node.dependencies.joinToString(", ")
                appendLine("  $path  [${node.artifactId}] → $deps")
            }
            if (missing.isNotEmpty()) {
                appendLine("⚠ Modules found in real poms but MISSING from the static ModuleMap snapshot: " +
                    missing.joinToString(", "))
            } else {
                appendLine("✓ Static ModuleMap snapshot matches the real pom graph")
            }
        }.trimEnd()
    }
}
