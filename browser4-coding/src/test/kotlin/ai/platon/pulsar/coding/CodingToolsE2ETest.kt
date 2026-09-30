package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tool-level end-to-end smoke tests against the REAL Browser4 checkout —
 * deliberately lightweight (pure file reads + regex analysis, NO maven/cargo,
 * no network), so it fits the repo test policy. Each test exercises a coding
 * tool on real repo artifacts.
 *
 * Skips silently when the working directory is not inside a Browser4 checkout.
 */
class CodingToolsE2ETest {

    @Test
    @DisplayName("live-template extractDir on the real browser4-seo plugin")
    fun liveTemplateOnRealPlugin() {
        val plugin = repoRoot()?.resolve("browser4-plugins/browser4-seo") ?: return
        val files = textFilesUnder(plugin)
        assertTrue(files.size >= 5, "expected pom + kotlin + plugin.json files, got ${files.size}")

        val set = SkeletonExtractor.extractDir(files)
        assertTrue(set.parameters["className"] == "SeoToolExecutor",
            "className must be the executor (domain-declaring class): ${set.parameters}")
        assertTrue(set.parameters["domain"] == "seo", "domain: ${set.parameters}")
        assertTrue(set.parameters["artifactId"] == "browser4-seo",
            "artifactId must be the module's own (parent BOM excluded): ${set.parameters}")
        assertTrue(set.parameters["stem"] == "Seo", "stem: ${set.parameters}")

        val out = SkeletonExtractor.instantiate(set, mapOf("className" to "WeatherToolExecutor"))
        val executorFile = out.entries.first { it.key.endsWith("SeoToolExecutor.kt") }
        assertTrue(executorFile.value.contains("open class WeatherToolExecutor"), executorFile.value.take(200))
        val service = out.entries.first { it.key.endsWith("SeoService.kt") }
        assertTrue(service.value.contains("WeatherService"),
            "sibling must follow the stem rename: ${service.value.take(200)}")
    }

    @Test
    @DisplayName("single-file SkeletonExtractor on the real SeoToolExecutor.kt")
    fun singleFileExtractOnRealFile() {
        val file = repoRoot()?.resolve(
            "browser4-plugins/browser4-seo/src/main/kotlin/ai/platon/pulsar/seo/tools/SeoToolExecutor.kt") ?: return
        if (!Files.isRegularFile(file)) return
        val skeleton = SkeletonExtractor.extract(Files.readString(file), "SeoToolExecutor.kt")
        assertTrue(skeleton.parameters["className"] == "SeoToolExecutor", skeleton.parameters.toString())
        assertTrue(skeleton.parameters["domain"] == "seo", skeleton.parameters.toString())
    }

    @Test
    @DisplayName("DevTaskPlanner resolves mentions against the real module graph")
    fun plannerWithLiveGraph() {
        val root = repoRoot() ?: return
        val graph = ModuleGraph.build(scanPoms(root))
        val plan = DevTaskPlanner.plan(
            "add a tool to browser4-plugins/browser4-seo and fix PulsarWebDriver.kt " +
                "under browser4-core/browser4-browser/src",
            knownModules = graph.nodes.keys.toList())
        assertTrue("browser4-plugins/browser4-seo" in plan.modules, plan.modules.toString())
        assertTrue("browser4-core/browser4-browser" in plan.modules, plan.modules.toString())
        assertTrue(plan.driverFiles.isNotEmpty(), "driver file must be flagged")
        assertTrue(plan.steps.any { it.tool == "coding.trapCheck" }, "trapCheck step expected")
    }

    @Test
    @DisplayName("CdpTrapCheck runs cleanly on the real browser driver")
    fun trapCheckOnRealDriver() {
        val file = repoRoot()?.resolve(
            "browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/browser/PulsarWebDriver.kt") ?: return
        if (!Files.isRegularFile(file)) return
        // Must not throw; the driver may already apply the documented fixes (either outcome is fine).
        assertFalse(CdpTrapCheck.format(Files.readString(file)).isBlank())
    }

    @Test
    @DisplayName("RepoConsistencyCheck passes on the real checkout")
    fun repoConsistencyOnRealCheckout() {
        val root = repoRoot() ?: return
        val versionContent = Files.readString(root.resolve("VERSION"))
        val rootPom = Files.readString(root.resolve("pom.xml"))
        val bomPom = Files.readString(root.resolve("browser4-dependencies/pom.xml"))
        val pluginManifestContents = Files.walk(root).use { stream ->
            stream.filter { RepoConsistencyCheck.isPluginManifestPath(it) }
                .map { Files.readString(it) }
                .toList()
        }
        val result = RepoConsistencyCheck.check(
            versionContent, rootPom, bomPom,
            moduleExists = { Files.isDirectory(root.resolve(it)) },
            pluginManifestContents = pluginManifestContents,
        )
        assertTrue(result.valid, "live repo must be consistent: ${result.issues}")
    }

    // ==================== helpers ====================

    private fun repoRoot(): Path? {
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

    private fun textFilesUnder(base: Path): Map<String, String> {
        val files = linkedMapOf<String, String>()
        Files.walk(base).use { s ->
            s.filter { Files.isRegularFile(it) }
                .filter {
                    it.fileName.toString().endsWith(".kt") || it.fileName.toString() == "pom.xml" ||
                        it.fileName.toString().endsWith(".json") || it.fileName.toString().endsWith(".md")
                }
                .forEach { f ->
                    val rel = base.relativize(f).toString().replace('\\', '/')
                    files[rel] = Files.readString(f)
                }
        }
        return files
    }

    private fun scanPoms(root: Path): Map<String, String> = ModuleGraph.scanPoms(root)
}
