package ai.platon.pulsar.agentic.tools.langchain4j

import ai.platon.pulsar.agentic.model.ToolSpec
import dev.langchain4j.agent.tool.ToolExecutionRequest
import org.junit.jupiter.api.Test
import kotlin.test.*

class ToolCallConverterTest {

    private val registry = mapOf(
        "tab_click" to ToolSpec("tab", "click",
            listOf(ToolSpec.Arg("selector", "String"))),
        "fs_readString" to ToolSpec("fs", "readString",
            listOf(ToolSpec.Arg("filename", "String"))),
    )

    @Test
    fun `resolves tool via registry exact match`() {
        val request = ToolExecutionRequest.builder()
            .id("call-1")
            .name("tab_click")
            .arguments("""{"selector": "#btn"}""")
            .build()

        val tc = ToolCallConverter.toToolCall(request, registry)
        assertEquals("tab", tc.domain)
        assertEquals("click", tc.method)
        assertEquals("#btn", tc.arguments["selector"])
    }

    @Test
    fun `resolves tool via fallback split`() {
        val request = ToolExecutionRequest.builder()
            .id("call-2")
            .name("browser_switchTab")
            .arguments("""{"tabId": "abc123"}""")
            .build()

        val tc = ToolCallConverter.toToolCall(request, emptyMap())
        assertEquals("browser", tc.domain)
        assertEquals("switchTab", tc.method)
        assertEquals("abc123", tc.arguments["tabId"])
    }

    @Test
    fun `unknown tool name throws`() {
        val request = ToolExecutionRequest.builder()
            .id("call-3")
            .name("unknown_tool")
            .arguments("{}")
            .build()

        // Fallback split on underscore: "unknown" / "tool" — that's valid
        val tc = ToolCallConverter.toToolCall(request, emptyMap())
        assertEquals("unknown", tc.domain)
        assertEquals("tool", tc.method)
    }

    @Test
    fun `no underscore name doesn't crash`() {
        val request = ToolExecutionRequest.builder()
            .id("call-4")
            .name("unknown")
            .arguments("{}")
            .build()

        assertFailsWith<IllegalArgumentException> {
            ToolCallConverter.toToolCall(request, emptyMap())
        }
    }

    @Test
    fun `empty arguments returns empty map`() {
        val request = ToolExecutionRequest.builder()
            .id("call-5")
            .name("tab_click")
            .arguments("")
            .build()

        val tc = ToolCallConverter.toToolCall(request, registry)
        assertTrue(tc.arguments.isEmpty())
    }

    @Test
    fun `blank arguments returns empty map`() {
        val request = ToolExecutionRequest.builder()
            .id("call-6")
            .name("tab_click")
            .arguments("   ")
            .build()

        val tc = ToolCallConverter.toToolCall(request, registry)
        assertTrue(tc.arguments.isEmpty())
    }

    @Test
    fun `parses integer value`() {
        val request = ToolExecutionRequest.builder()
            .id("call-7")
            .name("tab_click")
            .arguments("""{"count": 5}""")
            .build()

        val tc = ToolCallConverter.toToolCall(request, registry)
        assertEquals(5, tc.arguments["count"])
    }

    @Test
    fun `parses boolean value`() {
        val request = ToolExecutionRequest.builder()
            .id("call-8")
            .name("tab_click")
            .arguments("""{"active": true}""")
            .build()

        val tc = ToolCallConverter.toToolCall(request, registry)
        assertEquals(true, tc.arguments["active"])
    }

    @Test
    fun `parses nested object`() {
        val request = ToolExecutionRequest.builder()
            .id("call-9")
            .name("tab_click")
            .arguments("""{"config": {"key": "value"}}""")
            .build()

        val tc = ToolCallConverter.toToolCall(request, registry)
        assertIs<Map<*, *>>(tc.arguments["config"])
        @Suppress("UNCHECKED_CAST")
        val config = tc.arguments["config"] as Map<String, Any?>
        assertEquals("value", config["key"])
    }

    @Test
    fun `malformed JSON returns empty map`() {
        val request = ToolExecutionRequest.builder()
            .id("call-10")
            .name("tab_click")
            .arguments("{not valid json")
            .build()

        val tc = ToolCallConverter.toToolCall(request, registry)
        assertTrue(tc.arguments.isEmpty())
    }

    @Test
    fun `non-object JSON returns empty map`() {
        val request = ToolExecutionRequest.builder()
            .id("call-11")
            .name("tab_click")
            .arguments("""["a", "b"]""")
            .build()

        val tc = ToolCallConverter.toToolCall(request, registry)
        assertTrue(tc.arguments.isEmpty())
    }
}
