package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ModuleMap] — the Browser4 module topology used by coding.impact.
 */
class ModuleMapTest {

    @Test
    @DisplayName("transitiveDependents includes the module and its dependents")
    fun transitiveDependents() {
        val affected = ModuleMap.transitiveDependents("browser4-coding")
        assertTrue(affected.contains("browser4-coding"))
        assertTrue(affected.contains("browser4-agentic"), "agentic depends on coding")
        assertTrue(affected.contains("browser4-boot"), "boot depends on agentic")
        assertTrue(affected.contains("browser4-rest"), "rest depends on agentic")
    }

    @Test
    @DisplayName("transitiveDependents of a leaf includes only itself")
    fun transitiveDependentsLeaf() {
        val affected = ModuleMap.transitiveDependents("browser4-dependencies")
        assertEquals(listOf("browser4-dependencies"), affected)
    }

    @Test
    @DisplayName("test commands are well-formed")
    fun testCommands() {
        assertTrue(ModuleMap.mavenTestCommand("browser4-rest").contains("mvn test -pl browser4-rest"))
        assertTrue(ModuleMap.cargoTestCommand().contains("cargo test --bin browser4-cli"))
    }

    @Test
    @DisplayName("known modules are listed")
    fun modulesKnown() {
        assertTrue(ModuleMap.MODULES.contains("browser4-agentic"))
        assertTrue(ModuleMap.MODULES.contains("browser4-core/browser4-common"))
        assertTrue(ModuleMap.MODULES.contains("browser4-rest"))
    }
}
