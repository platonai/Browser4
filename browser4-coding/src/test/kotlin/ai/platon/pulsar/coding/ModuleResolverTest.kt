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
}
