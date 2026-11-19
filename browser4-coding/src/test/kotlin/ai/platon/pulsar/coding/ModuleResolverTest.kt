package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ModuleResolverTest {

    @Test
    @DisplayName("top-level, nested and module-root paths all resolve to a known module")
    fun resolvesModules() {
        assertEquals("browser4-coding", ModuleResolver.moduleOf("browser4-coding/src/main/kotlin/X.kt"))
        assertEquals(
            "browser4-core/browser4-common",
            ModuleResolver.moduleOf("browser4-core/browser4-common/src/main/kotlin/X.kt"),
        )
        assertEquals("browser4-rest", ModuleResolver.moduleOf("browser4-rest/pom.xml"))
        assertEquals(
            "browser4-core/browser4-common",
            ModuleResolver.moduleOf("browser4-core\\browser4-common\\src\\main\\kotlin\\X.kt"),
        )
        assertNull(ModuleResolver.moduleOf("pom.xml"), "the root aggregator is not a module")
    }

    @Test
    @DisplayName("an absolute path inside a known module resolves to the same module")
    fun resolvesAbsolutePaths() {
        val path = "D:/work/Browser4/browser4-agentic/src/main/kotlin/A.kt"
        assertEquals("browser4-agentic", ModuleResolver.moduleOf(path))
    }

    @Test
    @DisplayName("the CLI crate maps to its pseudo module")
    fun resolvesCliCrate() {
        assertEquals(
            ModuleMap.CLI_CRATE,
            ModuleResolver.moduleOf("cli/browser4-cli/src/main.rs"),
        )
    }

    @Test
    @DisplayName("a file nested below a module belongs to that module, not to its own directory")
    fun resolvesFilesNestedBelowAModule() {
        // The 2026-11-19 stranded run: examples/scripts/weibo-ego-lite.mjs resolved to the
        // phantom module "scripts", so the isolation gate demanded coding.verify(module="scripts")
        // and `mvn -pl scripts -am` ("Could not find the selected project in the reactor: scripts")
        // could never pass — while the verdict that HAD passed, module="examples", was ignored.
        assertEquals("examples", ModuleResolver.moduleOf("examples/scripts/weibo-ego-lite.mjs"))
        assertEquals("examples", ModuleResolver.moduleOf("examples/scripts/deep/nested/probe.mjs"))
        assertEquals("examples", ModuleResolver.moduleOf("D:/work/Browser4/examples/scripts/probe.mjs"))
        assertEquals("browser4-tests", ModuleResolver.moduleOf("browser4-tests/tests-production/smoke.ps1"))
        // The nested module is still preferred over its aggregator parent.
        assertEquals(
            "examples/browser4-examples",
            ModuleResolver.moduleOf("examples/browser4-examples/scripts/run.mjs"),
        )
    }

    @Test
    @DisplayName("a Rust file nested inside the CLI crate resolves to the crate")
    fun resolvesNestedCliCrateFiles() {
        // Before the containment fix this answered "e2e" / "tests" — names cargo has no target for.
        assertEquals(ModuleMap.CLI_CRATE, ModuleResolver.moduleOf("cli/browser4-cli/tests/e2e/mod.rs"))
        assertEquals(ModuleMap.CLI_CRATE, ModuleResolver.moduleOf("cli/browser4-cli/Cargo.toml"))
    }

    @Test
    @DisplayName("paths no module contains are left out of the verifier's module set")
    fun knownModulesIgnoreUnownedPaths() {
        assertNull(ModuleResolver.knownModuleOf("bin/test.ps1"), "bin/ is not a Maven module")
        assertNull(ModuleResolver.knownModuleOf("chrome-extension/manifest.json"))
        assertNull(ModuleResolver.knownModuleOf("pom.xml"), "the root aggregator is not a module")
        assertEquals(
            setOf("browser4-coding"),
            ModuleResolver.knownModulesOf(
                listOf("bin/test.ps1", "browser4-coding/src/main/kotlin/X.kt", "README.md"),
            ),
        )
        // `coding.mvnBuild` keeps its best-effort answer, so the build tool still works on
        // paths outside the reactor; only the gate's requirement drops them.
        assertEquals("bin", ModuleResolver.moduleOf("bin/test.ps1"))
    }

    @Test
    @DisplayName("documentation is never a module requirement, whatever its directory is called")
    fun documentationIsNotAModuleRequirement() {
        // `docs-dev/examples/**` and `skills/browser4-coding/**` contain real module names; a note
        // cannot break a build, so counting them demanded an unrelated module's `-am` build.
        assertTrue(ModuleResolver.knownModulesOf(listOf("docs-dev/examples/weibo/run.ps1")).isEmpty())
        assertTrue(ModuleResolver.knownModulesOf(listOf("skills/browser4-coding/SKILL.md")).isEmpty())
        assertEquals(
            setOf("browser4-coding"),
            ModuleResolver.knownModulesOf(
                listOf("browser4-coding/src/main/kotlin/X.kt", "docs/agent-permissions.md"),
            ),
        )
    }

    @Test
    @DisplayName("a workspace graph wins over the static Browser4 snapshot")
    fun workspaceGraphIsAuthoritative() {
        val graph = demoGraph()

        // `examples/` exists in most repositories and is a Browser4 module name. This reactor has
        // no such module, so naming it would demand `mvn -pl examples -am` — a build Maven
        // rejects before compiling anything, the failure this resolver exists to prevent.
        assertNull(ModuleResolver.knownModuleOf("examples/demo.js", graph))
        // Without a graph the static snapshot still answers (Browser4's own tree, `coding.mvnBuild`).
        assertEquals("examples", ModuleResolver.knownModuleOf("examples/demo.js"))
        assertEquals("lib", ModuleResolver.knownModuleOf("lib/src/main/java/A.java", graph))
        // The Rust crate is in no pom, so the graph path still resolves it.
        assertEquals(ModuleMap.CLI_CRATE, ModuleResolver.knownModuleOf("cli/browser4-cli/src/main.rs", graph))
    }

    @Test
    @DisplayName("documentation is detected by directory and suffix")
    fun detectsDocumentation() {
        assertTrue(ModuleResolver.isDocumentation("docs/config.md"))
        assertTrue(ModuleResolver.isDocumentation("browser4-coding/README.md"))
        assertTrue(ModuleResolver.isDocumentation("VERSION.txt"))
        assertFalse(ModuleResolver.isDocumentation("browser4-coding/src/main/kotlin/X.kt"))
        assertTrue(ModuleResolver.requiresVerification(listOf("browser4-coding/src/main/kotlin/X.kt")))
        assertFalse(ModuleResolver.requiresVerification(listOf("docs/config.md", "README.md")))
    }

    @Test
    @DisplayName("a verifier covers its own module and modules it depends on")
    fun coversDependencies() {
        assertTrue(ModuleResolver.covers("browser4-coding", setOf("browser4-coding")))
        // browser4-agentic depends on browser4-coding, so `-pl browser4-agentic -am`
        // compiles it.
        assertTrue(ModuleResolver.covers("browser4-agentic", setOf("browser4-coding")))
        assertFalse(
            ModuleResolver.covers("browser4-coding", setOf("browser4-agentic")),
            "verifying the upstream module does not compile the dependent one",
        )
        assertTrue(ModuleResolver.covers(null, emptySet()), "no changed modules needs no verifier")
        assertFalse(ModuleResolver.covers(null, setOf("browser4-coding")))
    }

    @Test
    @DisplayName("modulesOf drops paths outside every module")
    fun modulesOfChangedFiles() {
        val modules = ModuleResolver.modulesOf(
            listOf(
                "browser4-coding/src/main/kotlin/X.kt",
                "browser4-rest/src/main/kotlin/Y.kt",
                "README.md",
            )
        )
        assertEquals(setOf("browser4-coding", "browser4-rest"), modules)
    }

    // -- a foreign workspace is judged by its own graph -------------------------

    private fun pom(artifactId: String, vararg dependencies: String): String = buildString {
        append("<project><artifactId>").append(artifactId).append("</artifactId>")
        if (dependencies.isNotEmpty()) {
            append("<dependencies>")
            dependencies.forEach {
                // groupId deliberately NOT ai.platon: this is somebody else's repo.
                append("<dependency><groupId>demo</groupId><artifactId>").append(it).append("</artifactId></dependency>")
            }
            append("</dependencies>")
        }
        append("</project>")
    }

    private fun demoGraph(): ModuleGraph.Graph = ModuleGraph.build(
        linkedMapOf("lib" to pom("lib"), "app" to pom("app", "lib"))
    )

    @Test
    @DisplayName("a workspace graph lets a dependent verifier cover a foreign repo's library")
    fun coversWithWorkspaceGraph() {
        val graph = demoGraph()

        // app depends on lib, so `mvn -pl app -am` compiles both.
        assertTrue(ModuleResolver.covers("app", setOf("lib", "app"), graph))
        assertFalse(
            ModuleResolver.covers("lib", setOf("lib", "app"), graph),
            "verifying the library does not compile its dependent",
        )
        assertFalse(
            ModuleResolver.covers("app", setOf("lib", "app")),
            "the static Browser4 snapshot cannot know a foreign repo's edges — that was the bug",
        )
    }

    @Test
    @DisplayName("a workspace graph resolves nested foreign modules that the heuristic truncates")
    fun nestedForeignModules() {
        val graph = ModuleGraph.build(
            linkedMapOf("svc/core" to pom("core"), "svc/api" to pom("api", "core"))
        )

        // The old heuristic returned "core": a name `mvn -pl` cannot select as a module.
        assertEquals("svc/core", ModuleResolver.moduleOf("svc/core/src/main/java/A.java", graph))
        assertEquals("svc/core", ModuleResolver.moduleOf("D:/work/x/svc/core/src/main/java/A.java", graph))
        assertTrue(ModuleResolver.covers("svc/api", setOf("svc/core", "svc/api"), graph))
        assertEquals(setOf("svc/core", "svc/api"), ModuleResolver.modulesOf(
            listOf("svc/core/src/main/java/A.java", "svc/api/src/main/java/B.java"), graph,
        ))
    }

    @Test
    @DisplayName("a graph that does not know the verifier keeps the static behaviour")
    fun foreignVerifierFallsBackToStatic() {
        val graph = demoGraph()
        // Browser4 verdicts are not nodes of the demo graph, so the static snapshot answers.
        assertTrue(ModuleResolver.covers("browser4-agentic", setOf("browser4-coding"), graph))
        assertTrue(ModuleResolver.covers(ModuleMap.CLI_CRATE, setOf(ModuleMap.CLI_CRATE), graph))
        assertTrue(ModuleResolver.covers("app", emptySet(), graph), "an empty change set needs no verifier")
    }

    // -- suggesting which module to verify --------------------------------------

    @Test
    @DisplayName("the suggestion names the smallest module whose -am build covers every change")
    fun suggestsCoveringVerifier() {
        // The 2026-11-18 failure: 2 files in browser4-coding + 1 in browser4-agentic, verified
        // browser4-coding only. browser4-agentic depends on browser4-coding, so its -am build
        // compiles both — that is the verdict that would have merged.
        assertEquals(
            "browser4-agentic",
            ModuleResolver.suggestVerifier(setOf("browser4-coding", "browser4-agentic")),
        )
        assertEquals("browser4-agentic", ModuleResolver.suggestVerifier(setOf("browser4-agentic")))
        // The crate is not a Maven module: the Maven half can still be named, the Rust half cannot.
        assertEquals(
            "browser4-coding",
            ModuleResolver.suggestVerifier(setOf("browser4-coding", ModuleMap.CLI_CRATE)),
        )
        assertNull(
            ModuleResolver.suggestVerifier(setOf(ModuleMap.CLI_CRATE)),
            "cargo is not a Maven module and always needs its own verdict",
        )
    }

    @Test
    @DisplayName("a foreign graph suggests the module that depends on the library it changed")
    fun suggestsForeignVerifier() {
        assertEquals("app", ModuleResolver.suggestVerifier(setOf("lib", "app"), demoGraph()))
        assertNull(
            ModuleResolver.suggestVerifier(setOf("lib", "app")),
            "the static Browser4 snapshot cannot cover a foreign repo's modules — guessing would " +
                "name a module the workspace does not have",
        )
    }

    @Test
    @DisplayName("the suggestion never names a module the workspace does not have")
    fun suggestionIsAlwaysSelectable() {
        // 2026-11-19: the refusal told the run to verify "scripts", a directory with no pom.
        // Following that instruction could only fail inside Maven, so the hint must stay silent
        // instead of proposing a phantom module.
        assertNull(ModuleResolver.suggestVerifier(setOf("scripts")))
        assertNull(ModuleResolver.suggestVerifier(setOf("bin")))
        assertEquals("examples", ModuleResolver.suggestVerifier(setOf("examples")))
        assertNull(ModuleResolver.suggestVerifier(setOf("scripts"), demoGraph()))
    }
}
