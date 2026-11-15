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

    // -- branches the graph must NOT take ---------------------------------

    @Test
    @DisplayName("a pom without its own artifactId is not a module")
    fun pomWithoutOwnArtifactIsNotAModule() {
        // A parent-only pom (or a truncated file) names no module of its own. It is
        // skipped rather than registered under its parent's name — otherwise one
        // broken pom would overwrite a real module in the artifact index.
        assertNull(ModuleGraph.parsePom("<project><parent><artifactId>p</artifactId></parent></project>"))

        val graph = ModuleGraph.build(
            linkedMapOf(
                "root" to "<project><artifactId>root</artifactId></project>",
                "broken" to "<project><parent><artifactId>root</artifactId></parent></project>",
                "junk" to "not a pom at all",
            )
        )

        assertEquals(setOf("root"), graph.nodes.keys, "only the parseable pom is a module: ${graph.nodes.keys}")
    }

    @Test
    @DisplayName("a <parent> block without an artifactId adds no parent edge")
    fun parentWithoutArtifactAddsNoEdge() {
        val info = ModuleGraph.parsePom(
            "<project><parent><groupId>g</groupId></parent><artifactId>m</artifactId></project>"
        )!!
        assertEquals("m", info.artifactId)
        assertNull(info.parentArtifactId, "no artifactId in <parent>, no parent edge")
    }

    @Test
    @DisplayName("dependencyManagement and build dependencies are not compile edges")
    fun nonEdgeBlocksAreIgnored() {
        // Version pinning is not a dependency, and a plugin's <dependencies> is a
        // plugin classpath: treating either as an edge makes `impact` over-report.
        val pom = """
            <project>
                <artifactId>app</artifactId>
                <dependencyManagement>
                    <dependencies>
                        <dependency><artifactId>lib</artifactId></dependency>
                    </dependencies>
                </dependencyManagement>
                <build>
                    <plugins>
                        <plugin>
                            <dependencies>
                                <dependency><artifactId>plugin-lib</artifactId></dependency>
                            </dependencies>
                        </plugin>
                    </plugins>
                </build>
                <dependencies>
                    <dependency><artifactId>real</artifactId></dependency>
                    <dependency><groupId>no-artifact</groupId></dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val info = ModuleGraph.parsePom(pom)!!

        assertEquals(listOf("real"), info.declaredDependencies, "only the real compile dependency: ${info.declaredDependencies}")
    }

    @Test
    @DisplayName("two modules sharing an artifactId index the first one")
    fun duplicateArtifactIdKeepsTheFirstModule() {
        // `putIfAbsent` keeps the first path, and a dependent then resolves to that
        // module — deterministic instead of "whichever pom was read last".
        val graph = ModuleGraph.build(
            linkedMapOf(
                "a" to "<project><artifactId>dup</artifactId></project>",
                "b" to "<project><artifactId>dup</artifactId></project>",
                "c" to """
                    <project>
                        <artifactId>c</artifactId>
                        <dependencies><dependency><artifactId>dup</artifactId></dependency></dependencies>
                    </project>
                """.trimIndent(),
            )
        )

        assertEquals("a", graph.artifactIndex["dup"], "first writer wins")
        assertEquals(listOf("a"), graph.nodes["c"]!!.dependencies)
    }

    @Test
    @DisplayName("transitiveDependents terminates on a dependency cycle")
    fun cyclesTerminate() {
        // Two modules depending on each other is a build error Maven rejects, but
        // the graph is also built from foreign workspaces — the walk must not spin.
        val graph = ModuleGraph.build(
            linkedMapOf(
                "a" to """
                    <project>
                        <artifactId>a</artifactId>
                        <dependencies><dependency><artifactId>b</artifactId></dependency></dependencies>
                    </project>
                """.trimIndent(),
                "b" to """
                    <project>
                        <artifactId>b</artifactId>
                        <dependencies><dependency><artifactId>a</artifactId></dependency></dependencies>
                    </project>
                """.trimIndent(),
            )
        )

        assertEquals(setOf("a", "b"), ModuleGraph.transitiveDependents(graph, "a").toSet())
    }

    // -- the cached scan ---------------------------------------------------

    @Test
    @DisplayName("the cached graph is rebuilt only when a pom changes")
    fun cachedGraphTracksPomChanges(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        fun write(relative: String, content: String) {
            val dir = tmp.resolve(relative)
            java.nio.file.Files.createDirectories(dir)
            java.nio.file.Files.writeString(dir.resolve("pom.xml"), content)
        }
        ModuleGraph.clearCache()
        try {
            write("lib", "<project><artifactId>lib</artifactId></project>")
            write("app", "<project><artifactId>app</artifactId></project>")

            val cold = ModuleGraph.cachedGraph(tmp)
            assertSame(cold, ModuleGraph.cachedGraph(tmp), "an untouched tree is answered from memory")
            assertTrue(cold.nodes["app"]!!.dependencies.isEmpty())

            // Adding a dependency changes the pom's size, so the signature changes and
            // the next lookup rebuilds. This is what keeps `code devtask` warm without
            // ever serving a graph from a pom that no longer exists in that shape.
            write(
                "app",
                """
                    <project>
                        <artifactId>app</artifactId>
                        <dependencies><dependency><artifactId>lib</artifactId></dependency></dependencies>
                    </project>
                """.trimIndent(),
            )
            val warm = ModuleGraph.cachedGraph(tmp)
            assertNotSame(cold, warm, "a changed pom must rebuild the graph")
            assertEquals(listOf("lib"), warm.nodes["app"]!!.dependencies)

            ModuleGraph.clearCache()
            assertNotSame(warm, ModuleGraph.cachedGraph(tmp), "clearCache drops the memoised graph")
        } finally {
            ModuleGraph.clearCache()
        }
    }

    @Test
    @DisplayName("scanPoms reads the module poms in place")
    fun scanPomsReadsModulePoms(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        fun write(relative: String, content: String) {
            val dir = tmp.resolve(relative)
            java.nio.file.Files.createDirectories(dir)
            java.nio.file.Files.writeString(dir.resolve("pom.xml"), content)
        }
        write("", "<project><artifactId>root</artifactId></project>")
        write("lib", "<project><artifactId>lib</artifactId></project>")
        // A non-pom file (and a non-pom named almost like one) must not become a module.
        java.nio.file.Files.writeString(tmp.resolve("pom.xml.bak"), "<project/>")
        java.nio.file.Files.createDirectories(tmp.resolve("lib/resources"))
        java.nio.file.Files.writeString(tmp.resolve("lib/resources/readme.txt"), "hello")

        val poms = ModuleGraph.scanPoms(tmp)

        assertEquals(setOf("browser4", "lib"), poms.keys, "only real module poms: ${poms.keys}")
        assertTrue(poms["lib"]!!.contains("lib"), poms["lib"]!!)
    }

    // -- rendering ---------------------------------------------------------

    @Test
    @DisplayName("format renders edges, the drift warning, and modules with no edges")
    fun formatRendersBothVerdicts() {
        val graph = ModuleGraph.build(poms)

        val synced = ModuleGraph.format(graph, graph.nodes.keys.toList())
        assertTrue(synced.contains("Module graph: 5 modules"), synced)
        assertTrue(synced.contains("(none)"), "an edgeless module says so instead of printing nothing: $synced")
        assertTrue(synced.contains("[browser4-coding]"), "each line names the module and its artifact: $synced")
        assertTrue(synced.contains("✓ Static ModuleMap snapshot matches"), synced)

        // A snapshot that missed a module is the condition `drift` exists for; the
        // rendered form must name what is missing, not just "there is drift".
        val drifted = ModuleGraph.format(graph, listOf("browser4", "browser4-pdk", "browser4-coding", "browser4-agentic"))
        assertTrue(drifted.contains("⚠ Modules found in real poms but MISSING"), drifted)
        assertTrue(drifted.contains("browser4-plugins/browser4-seo"), drifted)
    }
}

