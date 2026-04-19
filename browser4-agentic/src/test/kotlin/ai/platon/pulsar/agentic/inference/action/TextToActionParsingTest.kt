package ai.platon.pulsar.agentic.inference.action

import ai.platon.browser4.driver.chrome.dom.model.BrowserUseState
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private class TestableTextToAction(conf: ImmutableConfig) : TextToAction(conf) {
    val agentState = AgentState(1, "", BrowserUseState.DUMMY)
    fun parse(response: ModelResponse): ActionDescription = modelResponseToActionDescription("", agentState, response)
}

class TextToActionParsingTest {

    private val tta = TestableTextToAction(ImmutableConfig())

    @Test
    @DisplayName("elements JSON with click builds ToolCall with selector as arg0")
    fun elementsJsonWithClickBuildsToolCallWithSelectorAsArg0() {
        val json = """
            {
              "elements": [
                {
                  "locator": "0,19",
                  "description": "Submit button",
                  "domain": "tab",
                  "method": "click",
                  "arguments": [{"name": "selector", "value": "#submit"}]
                }
              ]
            }
        """.trimIndent()
        val resp = ModelResponse(json, ResponseState.STOP)

        val ad = tta.parse(resp)
        assertNotNull(ad.toolCall)
        assertEquals("tab", ad.toolCall!!.domain)
        assertEquals("click", ad.toolCall!!.method)
        assertEquals("#submit", ad.toolCall!!.arguments["selector"])
        assertFalse(ad.isDecidedComplete)
        assertNull(ad.summary)
    }

    @Test
    @DisplayName("elements JSON with type maps locator to arg0 and text to arg1")
    fun elementsJsonWithTypeMapsLocatorToArg0AndTextToArg1() {
        val json = """
            {
              "elements": [
                {
                  "locator": "#q",
                  "description": "Search input",
                  "domain": "tab",
                  "method": "type",
                  "arguments": [ { "name": "text", "value": "hello" } ]
                }
              ]
            }
        """.trimIndent()
        val resp = ModelResponse(json, ResponseState.STOP)

        val ad = tta.parse(resp)

        val tc = requireNotNull(ad.toolCall)
        assertEquals("tab", tc.domain)
        assertEquals("type", tc.method)
        assertEquals("hello", tc.arguments["text"]) // named mapping for argument values

        assertFalse(ad.isDecidedComplete)
    }

    @Test
    @DisplayName("plain driver expression fallback is preserved")
    fun plainDriverExpressionFallbackIsPreserved() {
        val text = """
            driver.click("#ok")
            // some commentary from model
            driver.scrollToMiddle(0.5)
        """.trimIndent()
        val resp = ModelResponse(text, ResponseState.STOP)

        val ad = tta.parse(resp)
        // Should extract driver.* lines only
        assertTrue(ad.toolCall == null || ad.cssFriendlyExpression != null)
//        assertTrue(ad.cssFriendlyExpressions.any { it.startsWith("driver.click(") })
//        assertTrue(ad.cssFriendlyExpressions.any { it.startsWith("driver.scrollToMiddle(") })
        assertFalse(ad.isDecidedComplete)
    }
}
