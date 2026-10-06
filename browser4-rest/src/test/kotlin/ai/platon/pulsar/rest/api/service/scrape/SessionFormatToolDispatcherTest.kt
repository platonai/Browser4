package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.rest.mcp.controller.CustomToolTargets
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock

/**
 * The dispatch routes, and specifically the one that was missing.
 *
 * The built-in domains (`tab`, `browser`, …) are **not** in `CustomToolRegistry` —
 * they live in the session's [AgentToolManager]. A dispatcher that only consulted the
 * registry reported every built-in method as unsupported, so `screenshot` degraded
 * with "tab.screenshot is not supported in this deployment" while the tool was right
 * there. That is what the first real run of the live stage printed.
 *
 * The suspend dispatch path itself is covered by the real-browser e2e scenario
 * (`test_e2e_scrape_formats`); what is worth pinning here is capability resolution,
 * which is a plain function and is where the bug actually was.
 */
@DisplayName("SessionFormatToolDispatcher")
class SessionFormatToolDispatcherTest {

    private val customToolTargets = CustomToolTargets(mock())

    private fun dispatcher(manager: AgentToolManager?) =
        SessionFormatToolDispatcher(customToolTargets, "s1") { manager }

    @Test
    @DisplayName("a built-in domain is supported when the session's agent tool manager has it")
    fun builtInDomainsResolveThroughTheAgentToolManager() {
        val manager = Mockito.mock(AgentToolManager::class.java)
        Mockito.`when`(manager.getToolSpec("tab", "screenshot"))
            .thenReturn(ToolSpec("tab", "screenshot", emptyList(), returnType = "String"))

        assertTrue(dispatcher(manager).supports("tab", "screenshot"))
        // A method the manager does not know stays unsupported, so the engine degrades
        // it with a warning instead of calling into nothing.
        assertFalse(dispatcher(manager).supports("tab", "noSuchMethod"))
    }

    @Test
    @DisplayName("without an agent tool manager a built-in domain is unsupported, not silently called")
    fun withoutAManagerBuiltInsAreUnsupported() {
        // The session may not exist yet, or may have no agent; either way "unsupported"
        // is the honest answer, and the engine turns it into a warning.
        assertFalse(dispatcher(null).supports("tab", "screenshot"))
    }

    @Test
    @DisplayName("an unknown domain fails by name rather than pretending to have run")
    fun unknownDomainFailsByName() {
        assertFalse(dispatcher(null).supports("no_such_domain", "whatever"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dispatcher(null).call("no_such_domain", "whatever", emptyMap()) }
        }
        assertTrue(error.message!!.contains("no_such_domain"), error.message)
    }
}
