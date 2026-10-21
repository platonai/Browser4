package ai.platon.pulsar.coding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * Runs [RepoConsistencyCheck] against a live workspace.
 *
 * Split out of the agentic `CodingToolExecutor` so that both the `coding.validate`
 * tool and the deterministic `coding.verify` gate (plan P0.1) share one
 * implementation — a gate that reports differently from the tool it aggregates
 * is not a gate.
 */
object RepoConsistencyReporter {

    /**
     * Outcome of a repo-consistency run.
     *
     * @param result structured validation result (errors/warnings/issues)
     * @param note extra human note, e.g. "ModuleMap.kt unreadable — drift checks skipped"
     */
    data class Outcome(
        val result: ValidationResult,
        val note: String = "",
    ) {
        val valid: Boolean get() = result.valid

        val errorCount: Int get() = result.issues.count { it.severity == Severity.ERROR }

        fun format(): String = result.format() + note
    }

    suspend fun check(fs: CodingAgentFileSystem): Outcome {
        val versionContent = fs.readFile("VERSION").takeUnless { it.startsWith("Error:") }
        val rootPom = fs.readFile("pom.xml").takeUnless { it.startsWith("Error:") }
        val bomPom = fs.readFile("browser4-dependencies/pom.xml").takeUnless { it.startsWith("Error:") }

        val root = fs.workspaceRoot
        val (onDiskModuleDirs, pluginManifestContents) = withContext(Dispatchers.IO) {
            val moduleDirs = Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { !it.fileName.toString().startsWith(".") }
                    .filter { Files.isRegularFile(it.resolve("pom.xml")) }
                    .map { root.relativize(it).toString().replace('\\', '/') }
                    .sorted()
                    .toList()
            }
            // Every in-repo plugin manifest outside build output (target/) and
            // hidden dirs (.git, .worktrees, .claude, ...) — other branches'
            // checkouts must not be scanned. Pruned walk (plan P1.6): an
            // unfiltered Files.walk here dominated `code verify` (17s on a
            // Windows checkout with a populated target/).
            val manifests = runCatching { collectPluginManifests(root) }.getOrDefault(emptyList())
            moduleDirs to manifests
        }

        // Live module topology from the real poms — cross-checked against the
        // static ModuleMap snapshot (MODULES + DEPENDENTS) so module drift fails
        // validation instead of silently corrupting devTask planning. Cached by
        // the poms' signature: every pom edit invalidates it (eval K8).
        val liveGraph = runCatching {
            ModuleGraph.cachedGraph(root)
        }.getOrNull()
        val liveModules = liveGraph?.nodes?.keys?.sorted() ?: emptyList()
        val liveDependentsOf: (String) -> Set<String> = { m ->
            liveGraph?.nodes?.filterValues { it.dependencies.contains(m) }?.keys?.toSet() ?: emptySet()
        }

        // Prefer the ON-DISK ModuleMap.kt as the static snapshot. The loaded
        // ModuleMap class comes from the running backend's build and can be
        // stale (e.g. right after scaffoldToDir synced a new module) — comparing
        // against it reports false drift. Parse the source file so the check
        // reflects what the next build will actually compile.
        val moduleMapSource = fs.readFile(
            "browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/ModuleMap.kt"
        ).takeUnless { it.startsWith("Error:") }
        val parsedModuleMap = moduleMapSource?.let { ModuleMapSource.parse(it) }
        // When the disk snapshot cannot be parsed (mock/foreign workspace), the
        // loaded-class comparison is meaningless (it produces massive false
        // drift) — skip the drift checks and surface a warning instead.
        val staticModules = parsedModuleMap?.modules ?: emptyList()
        val staticDependents = parsedModuleMap?.dependents ?: emptyMap()
        val staleFallbackNote = if (parsedModuleMap == null) {
            "\n⚠ ModuleMap.kt could not be read from disk — ModuleMap drift checks are skipped; " +
                "sync browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/ModuleMap.kt if you expect them."
        } else ""

        val result = RepoConsistencyCheck.check(
            versionContent = versionContent,
            rootPom = rootPom,
            bomPom = bomPom,
            moduleExists = { m -> fs.exists(m) },
            onDiskModuleDirs = onDiskModuleDirs,
            pluginManifestContents = pluginManifestContents,
            staticModuleMap = staticModules,
            liveModuleDirs = liveModules,
            staticDependents = staticDependents,
            liveDependentsOf = liveDependentsOf,
            moduleMapSource = moduleMapSource,
        )
        return Outcome(result, staleFallbackNote)
    }

    /**
     * Collect every in-repo `META-INF/browser4-plugin.json`, skipping build
     * output, dependency trees and hidden directories before descending.
     */
    private fun collectPluginManifests(root: java.nio.file.Path): List<String> {
        val manifests = mutableListOf<String>()
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(
                dir: java.nio.file.Path,
                attrs: java.nio.file.attribute.BasicFileAttributes,
            ): java.nio.file.FileVisitResult {
                if (dir != root) {
                    val name = dir.fileName?.toString().orEmpty()
                    if (name.startsWith(".") || name in ModuleGraph.SKIPPED_DIRS) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(
                file: java.nio.file.Path,
                attrs: java.nio.file.attribute.BasicFileAttributes,
            ): java.nio.file.FileVisitResult {
                if (file.fileName?.toString() == "browser4-plugin.json" &&
                    RepoConsistencyCheck.isPluginManifestPath(file)
                ) {
                    runCatching { Files.readString(file) }.getOrNull()?.let { manifests += it }
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: java.nio.file.Path,
                exc: java.io.IOException,
            ): java.nio.file.FileVisitResult = java.nio.file.FileVisitResult.CONTINUE
        })
        return manifests
    }
}
