package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.AgentAccessPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Guards the permission matrix in [AgentAccessPolicy] against drift.
 *
 * Every `coding.*` method registered by [CodingToolExecutor] must be explicitly classified; an
 * unclassified method silently falls back to `FULL` only, which would quietly widen the surface of
 * `read` / `read-build` / `write` instead of failing the build.
 */
class CodingToolMatrixCoverageTest {

    private val policy = AgentAccessPolicy()

    @Test
    @DisplayName("every registered coding method is classified in the permission matrix")
    fun testEveryMethodIsClassified() {
        val methods = CodingToolExecutor().getToolSpecs().keys
        assertTrue(methods.size > 40, "expected the coding tool set, got ${methods.size} methods")

        val unclassified = methods.filter { policy.gateOf(it) == null }.sorted()
        assertTrue(
            unclassified.isEmpty(),
            "unclassified coding tools (register them in AgentAccessPolicy): $unclassified",
        )
    }

    @Test
    @DisplayName("the tool set still contains the methods the matrix depends on")
    fun testRepresentativeMethodsExist() {
        val methods = CodingToolExecutor().getToolSpecs().keys
        listOf("read", "write", "mvnBuild", "verify", "shell", "runCode", "protect").forEach { method ->
            assertNotNull(methods.firstOrNull { it == method }, "missing expected coding tool: $method")
        }
        assertEquals(methods.size, methods.distinct().size)
    }
}
