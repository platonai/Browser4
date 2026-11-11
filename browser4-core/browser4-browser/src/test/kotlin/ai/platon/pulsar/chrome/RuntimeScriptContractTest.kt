package ai.platon.pulsar.chrome

import ai.platon.pulsar.common.ResourceLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RuntimeScriptContract] — the guard against a runtime whose
 * injected `vi` scripts come from different generations.
 *
 * The real classpath is asserted too: whatever is packaged must pass the check,
 * which is what makes a half-wired bundle fail at build/test time rather than
 * only in a manual browser run.
 */
@DisplayName("RuntimeScriptContract")
class RuntimeScriptContractTest {

    // The three bodies the build actually ships, in the generations the
    // production bundle must agree on.
    private val documentDomRect = """
        function getElementClientRect(node) { return node.getBoundingClientRect(); }
        function getDocumentRect(node) { return getElementClientRect(node); }
    """.trimIndent()

    private val legacyDomRect = """
        function getElementClientRect(node) { return subtract(node.getBoundingClientRect(), bodyRect); }
        var bodyRect = document.body.getBoundingClientRect();
    """.trimIndent()

    private val documentNodeOps = "var r = __pulsar_utils__.getDocumentRect(node);"
    private val legacyNodeOps = "var r = __pulsar_utils__.getClientRect(node);"
    private val documentUtils = "if (this._computeDone) { return }"
    private val legacyUtils = "if (document.body.getAttribute('data-error')) { return }"

    @Test
    @DisplayName("accepts one document-coordinate generation")
    fun acceptsDocumentGeneration() {
        val problems = RuntimeScriptContract.validate(
            documentDomRect, documentNodeOps, documentUtils
        )

        assertTrue(problems.isEmpty(), "expected no problems, got $problems")
    }

    @Test
    @DisplayName("rejects a document dom_rect.js with the legacy node_ops.js")
    fun rejectsDocumentDomRectWithLegacyNodeOps() {
        // The exact state a rebuilt bundle without the base upgrade used to
        // produce: `vi` silently becomes viewport coordinates.
        val problems = RuntimeScriptContract.validate(
            documentDomRect, legacyNodeOps, documentUtils
        )

        assertTrue(problems.any { it.contains(RuntimeScriptContract.NODE_OPS_RESOURCE) }, "$problems")
        assertTrue(problems.any { it.contains("viewport") }, "$problems")
    }

    @Test
    @DisplayName("rejects a legacy dom_rect.js with the document node_ops.js")
    fun rejectsLegacyDomRectWithDocumentNodeOps() {
        // The opposite half-wiring: node_ops calls a helper the vendored copy
        // does not provide.
        val problems = RuntimeScriptContract.validate(
            legacyDomRect, documentNodeOps, documentUtils
        )

        assertTrue(problems.any { it.contains(RuntimeScriptContract.DOM_RECT_RESOURCE) }, "$problems")
    }

    @Test
    @DisplayName("rejects a new dom_rect.js with the once-per-document __pulsar_utils__.js")
    fun rejectsDocumentDomRectWithLegacyUtils() {
        val problems = RuntimeScriptContract.validate(
            documentDomRect, documentNodeOps, legacyUtils
        )

        assertTrue(problems.any { it.contains(RuntimeScriptContract.UTILS_RESOURCE) }, "$problems")
        assertTrue(problems.any { it.contains("scroll-to-top") }, "$problems")
    }

    @Test
    @DisplayName("rejects a consistent but stale legacy bundle")
    fun rejectsStaleLegacyBundle() {
        val problems = RuntimeScriptContract.validate(
            legacyDomRect, legacyNodeOps, legacyUtils
        )

        assertTrue(problems.any { it.contains("predates the document-coordinate model") }, "$problems")
    }

    @Test
    @DisplayName("reports missing runtime scripts instead of passing silently")
    fun reportsMissingScripts() {
        val problems = RuntimeScriptContract.validate("", documentNodeOps, documentUtils)

        assertTrue(problems.any { it.contains(RuntimeScriptContract.DOM_RECT_RESOURCE) }, "$problems")
    }

    @Test
    @DisplayName("the packaged scripts form one document-coordinate generation")
    fun packagedScriptsAgree() {
        RuntimeScriptContract.verify()

        val domRect = ResourceLoader.readAllLinesNoFilter(RuntimeScriptContract.DOM_RECT_RESOURCE)
            .joinToString("\n")
        val nodeOps = ResourceLoader.readAllLinesNoFilter(RuntimeScriptContract.NODE_OPS_RESOURCE)
            .joinToString("\n")
        val utils = ResourceLoader.readAllLinesNoFilter(RuntimeScriptContract.UTILS_RESOURCE)
            .joinToString("\n")

        assertTrue(domRect.contains(RuntimeScriptContract.DOCUMENT_MARKER), "vendored dom_rect.js is stale")
        assertTrue(
            !domRect.contains(RuntimeScriptContract.LEGACY_DOM_RECT_MARKER),
            "vendored dom_rect.js still carries the body-relative cache"
        )
        assertTrue(nodeOps.contains(RuntimeScriptContract.DOCUMENT_MARKER), "node_ops.js is stale")
        assertTrue(utils.contains(RuntimeScriptContract.LAYOUT_FRESHNESS_MARKER), "__pulsar_utils__.js is stale")
    }
}
