package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end: [ModuleGraph] against the REAL Browser4 checkout — key edges must
 * resolve, and the transitive closure must NOT explode to the whole repo
 * (which would indicate dependencyManagement/parent noise leaking into edges).
 *
 * Skips silently when the working directory is not inside a Browser4 checkout.
 */
class ModuleGraphE2ETest {

    @Test
    @DisplayName("real repo graph has the key dependency edges")
    fun keyEdgesPresent() {
        val root = findRepoRoot() ?: return
        val graph = scanGraph(root)

        // agentic depends on the coding module (self-development wiring).
        assertTrue("browser4-coding" in graph.nodes["browser4-agentic"]!!.dependencies,
            "browser4-agentic must depend on browser4-coding")

        // A real plugin depends on the PDK parent and on agentic.
        val seo = graph.nodes["browser4-plugins/browser4-seo"]!!
        assertTrue("browser4-pdk" in seo.dependencies, "seo must inherit from browser4-pdk")
        assertTrue("browser4-agentic" in seo.dependencies, "seo must depend on browser4-agentic")

        // browser4-pdk is a real module whose changes affect every plugin.
        val pdkAffected = ModuleGraph.transitiveDependents(graph, "browser4-pdk")
        assertTrue("browser4-plugins/browser4-seo" in pdkAffected, "pdk changes must reach plugins: $pdkAffected")

        // browser4-browser changes reach the agentic layer and the plugins...
        val browserAffected = ModuleGraph.transitiveDependents(graph, "browser4-core/browser4-browser")
        assertTrue("browser4-agentic" in browserAffected, "browser changes must reach agentic")
        assertTrue("browser4-plugins/browser4-seo" in browserAffected, "browser changes must reach plugins")

        // ...but NOT the coding module or the root aggregator — guards against
        // dependencyManagement/BOM pins leaking into edges (full-repo explosion).
        assertFalse("browser4-coding" in browserAffected, "coding must not be affected by browser changes: $browserAffected")
        assertFalse("browser4" in browserAffected, "root aggregator must not appear in the closure: $browserAffected")
    }

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

    private fun scanGraph(root: Path): ModuleGraph.Graph {
        val poms = mutableMapOf<String, String>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }
                .filter { !it.toString().contains("\\target\\") && !it.toString().contains("/target/") }
                .filter { !it.toString().contains("archetype-resources") }
                .forEach { pom ->
                    val rel = root.relativize(pom.parent).toString().replace('\\', '/')
                    poms[if (rel.isEmpty()) "browser4" else rel] = Files.readString(pom)
                }
        }
        return ModuleGraph.build(poms)
    }
}
