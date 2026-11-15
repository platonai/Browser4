package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ModuleGraph] — the runtime module graph rebuilt from real poms
 * (anti-staleness for [ModuleMap]). Pure pom parsing, always runs.
 */
class ModuleGraphTest {

    private val poms: Map<String, String> = linkedMapOf(
        "browser4" to """
            <project>
                <parent>
                    <groupId>ai.platon</groupId>
                    <artifactId>pulsar-parent</artifactId>
                    <version>4.5.0</version>
                </parent>
                <artifactId>browser4</artifactId>
                <packaging>pom</packaging>
            </project>
        """.trimIndent(),
        "browser4-pdk" to """
            <project>
                <parent>
                    <artifactId>browser4</artifactId>
                    <version>4.13.4-SNAPSHOT</version>
                </parent>
                <artifactId>browser4-pdk</artifactId>
            </project>
        """.trimIndent(),
        "browser4-coding" to """
            <project>
                <parent>
                    <artifactId>browser4</artifactId>
                    <version>4.13.4-SNAPSHOT</version>
                </parent>
                <artifactId>browser4-coding</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent(),
        "browser4-agentic" to """
            <project>
                <parent>
                    <artifactId>browser4</artifactId>
                </parent>
                <artifactId>browser4-agentic</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>ai.platon.pulsar</groupId>
                        <artifactId>browser4-coding</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>ai.platon.pulsar</groupId>
                        <artifactId>browser4-common</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent(),
        "browser4-plugins/browser4-seo" to """
            <project>
                <parent>
                    <artifactId>browser4-pdk</artifactId>
                    <version>4.13.4-SNAPSHOT</version>
                </parent>
                <artifactId>browser4-seo</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>ai.platon.pulsar</groupId>
                        <artifactId>browser4-agentic</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent(),
    )

    @Test
    @DisplayName("build parses modules, parents, and internal dependencies")
    fun buildGraph() {
        val graph = ModuleGraph.build(poms)
        assertEquals(5, graph.nodes.size)
        val coding = graph.nodes["browser4-coding"]!!
        assertEquals("browser4-coding", coding.artifactId)
        assertTrue(coding.dependencies.contains("browser4"), "parent must be an edge")
        val seo = graph.nodes["browser4-plugins/browser4-seo"]!!
        assertTrue(seo.dependencies.contains("browser4-pdk"), "plugin parent edge")
        assertTrue(seo.dependencies.contains("browser4-agentic"), "plugin dependency edge")
    }

    @Test
    @DisplayName("external dependencies are dropped")
    fun externalDepsDropped() {
        val graph = ModuleGraph.build(poms)
        val coding = graph.nodes["browser4-coding"]!!
        assertFalse(coding.dependencies.contains("slf4j-api"), "external deps must not be edges")
        assertFalse(coding.dependencies.contains("browser4-common"), "unresolved artifact must be dropped")
    }

    @Test
    @DisplayName("a foreign reactor keeps its edges: the groupId is not a filter")
    fun foreignReactorKeepsEdges() {
        // Somebody else's two-module project: no ai.platon groupId anywhere. Filtering the
        // declared dependencies by groupId made every node edgeless here, which is what made
        // the isolation gate refuse the A/B smoke run (evaluation §11).
        val demo = linkedMapOf(
            "lib" to """
                <project>
                    <groupId>demo</groupId>
                    <artifactId>lib</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent(),
            "app" to """
                <project>
                    <groupId>demo</groupId>
                    <artifactId>app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>demo</groupId>
                            <artifactId>lib</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent(),
        )

        val graph = ModuleGraph.build(demo)

        assertTrue(graph.nodes["app"]!!.dependencies.contains("lib"), "demo:lib is an edge: ${graph.nodes["app"]}")
        assertEquals(listOf("lib", "app"), ModuleGraph.transitiveDependents(graph, "lib"))
    }

    @Test
    @DisplayName("a foreign dependency outside the reactor is still not an edge")
    fun foreignExternalStillDropped() {
        val graph = ModuleGraph.build(
            linkedMapOf(
                "app" to """
                    <project>
                        <groupId>demo</groupId>
                        <artifactId>app</artifactId>
                        <dependencies>
                            <dependency>
                                <groupId>com.google.guava</groupId>
                                <artifactId>guava</artifactId>
                            </dependency>
                        </dependencies>
                    </project>
                """.trimIndent(),
            )
        )

        assertTrue(graph.nodes["app"]!!.dependencies.isEmpty(), "unresolved external must vanish")
    }

    @Test
    @DisplayName("transitiveDependents closes over reverse edges")
    fun transitiveDependents() {
        val graph = ModuleGraph.build(poms)
        // browser4-coding ← browser4-agentic ← browser4-seo
        val affected = ModuleGraph.transitiveDependents(graph, "browser4-coding")
        assertTrue(affected.contains("browser4-coding"))
        assertTrue(affected.contains("browser4-agentic"), "direct dependent: $affected")
        assertTrue(affected.contains("browser4-plugins/browser4-seo"), "transitive dependent: $affected")
    }

    @Test
    @DisplayName("unknown module yields itself only")
    fun unknownModule() {
        val graph = ModuleGraph.build(poms)
        assertEquals(listOf("browser4-ghost"), ModuleGraph.transitiveDependents(graph, "browser4-ghost"))
    }

    @Test
    @DisplayName("drift reports real modules missing from a stale snapshot")
    fun driftDetection() {
        val graph = ModuleGraph.build(poms)
        // Simulate a stale snapshot (like the pre-sync ModuleMap).
        val stale = ModuleMap.MODULES.filter { !it.startsWith("browser4-pdk") && !it.startsWith("browser4-plugins/") }
        val missing = ModuleGraph.drift(graph, stale)
        assertTrue(missing.contains("browser4-pdk"), "pdk missing from the stale snapshot: $missing")
        assertTrue(missing.contains("browser4-plugins/browser4-seo"), "plugin modules missing: $missing")
        assertFalse(missing.contains("browser4-coding"), "browser4-coding is in the stale snapshot")
        // The CURRENT synced snapshot must have zero drift against the fixture.
        assertTrue(ModuleGraph.drift(graph, ModuleMap.MODULES).isEmpty(),
            "current snapshot must match: ${ModuleGraph.drift(graph, ModuleMap.MODULES)}")
    }

    @Test
    @DisplayName("parsePom skips the parent artifact as the own artifactId")
    fun parsePomOwnArtifact() {
        val info = ModuleGraph.parsePom(poms["browser4-pdk"]!!)!!
        assertEquals("browser4-pdk", info.artifactId, "own artifact, not the parent's")
        assertEquals("browser4", info.parentArtifactId)
    }

    @Test
    @DisplayName("the pom scan never descends into kept worktrees or agent scratch dirs")
    fun scanSkipsManagedWorktrees(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        // A kept coding worktree is a FULL checkout under `.browser4/worktrees/<id>`.
        // Scanning it made every module of every finished task a live module of this
        // repository, which corrupted `coding.moduleGraph`, `coding.impact` and the
        // dev-task planner on any machine that had run a coding task (and made the
        // module-map drift check fail on a developer's own checkout).
        fun write(relative: String) {
            val dir = tmp.resolve(relative)
            java.nio.file.Files.createDirectories(dir)
            java.nio.file.Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>$relative</artifactId></project>")
        }
        write("")
        write("browser4-coding")
        write(".browser4/worktrees/task-1/browser4-coding")
        write(".b4scratch/helper")
        write(".b4build/bundle")

        val scanned = ModuleGraph.scanPomFiles(tmp).keys

        assertEquals(setOf("browser4", "browser4-coding"), scanned, "agent bookkeeping is not a module: $scanned")
    }
}
