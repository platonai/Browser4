package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end guard: the static [ModuleMap] snapshot must match the LIVE module
 * graph rebuilt from the repository's real pom.xml files.
 *
 * Skips silently when the working directory is not inside a Browser4 checkout.
 * Failing means the module topology changed and the snapshot is stale — the
 * failure message names the modules to sync (regenerate with [ModuleGraph]).
 */
class ModuleMapDriftE2ETest {

    @Test
    @DisplayName("static ModuleMap snapshot matches the live pom graph")
    fun snapshotMatchesLiveGraph() {
        val root = findRepoRoot() ?: return
        val graph = scanGraph(root)

        // 1. Every real module must be in the static snapshot.
        val missing = ModuleGraph.drift(graph, ModuleMap.MODULES)
        assertTrue(missing.isEmpty(),
            "Static ModuleMap.MODULES drifted from the real pom graph. Sync these modules " +
                "(regenerate with ModuleGraph): ${missing.joinToString(", ")}")

        // 2. Every static reverse edge must exist in the live graph, and no live
        //    dependent may be missing from the snapshot (both directions, as sets).
        val liveDependents: (String) -> Set<String> = { m ->
            graph.nodes.filterValues { node -> node.dependencies.contains(m) }.keys.toSet()
        }
        ModuleMap.DEPENDENTS.forEach { (module, staticDeps) ->
            assertEquals(liveDependents(module), staticDeps.toSet(),
                "ModuleMap.DEPENDENTS[$module] drifted from the live pom graph")
        }
        graph.nodes.keys.forEach { m ->
            assertEquals(liveDependents(m), ModuleMap.DEPENDENTS[m].orEmpty().toSet(),
                "ModuleMap.DEPENDENTS missing/inaccurate for module $m")
        }
    }

    /** Walk up from the working directory to find the Browser4 repo root. */
    private fun findRepoRoot(): Path? {
        var dir: Path? = Path.of("").toAbsolutePath()
        repeat(6) {
            val d = dir ?: return null
            if (Files.isRegularFile(d.resolve("VERSION")) && Files.isRegularFile(d.resolve("pom.xml"))) {
                return d
            }
            dir = d.parent
        }
        return null
    }

    /** Scan the repo's real poms into a graph (mirrors the executor's scan). */
    private fun scanGraph(root: Path): ModuleGraph.Graph {
        return ModuleGraph.build(ModuleGraph.scanPoms(root))
    }
}
