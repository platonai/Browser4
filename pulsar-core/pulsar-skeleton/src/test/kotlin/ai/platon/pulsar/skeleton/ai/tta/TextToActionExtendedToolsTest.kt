package ai.platon.pulsar.skeleton.ai.tta

import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TextToActionExtendedToolsTest {
    private val tta = TextToAction(ImmutableConfig())

    @Test
    fun `TOOL_CALL_LIST should include newly added advanced tools and stop`() {
        val list = tta.TOOL_CALL_LIST
        list.contains("clickTextMatches").also { assertTrue(it, "clickTextMatches missing") }
        list.contains("clickMatches").also { assertTrue(it, "clickMatches missing") }
        list.contains("clickNthAnchor").also { assertTrue(it, "clickNthAnchor missing") }
        list.contains("scrollToScreen").also { assertTrue(it, "scrollToScreen missing") }
        list.contains("stop()").also { assertTrue(it, "stop() missing") }
    }

    @Test
    fun `parseToolCalls should parse extended tools`() {
        val json = """
            {"tool_calls":[
              {"name":"scrollToScreen","args":{"screenNumber":2}},
              {"name":"clickTextMatches","args":{"selector":"#list","pattern":"Item ","count":2}},
              {"name":"clickMatches","args":{"selector":"div.card","attrName":"data-id","pattern":"^prod-","count":1}},
              {"name":"clickNthAnchor","args":{"n":3,"rootSelector":"#nav"}},
              {"name":"stop","args":{}}
            ]}
        """.trimIndent()
        val calls = tta.parseToolCalls(json)
        assertEquals(5, calls.size)
        assertEquals("scrollToScreen", calls[0].name)
        assertEquals(2, calls[0].args["screenNumber"])
        assertEquals("clickTextMatches", calls[1].name)
        assertEquals("#list", calls[1].args["selector"])
        assertEquals("Item ", calls[1].args["pattern"])
        assertEquals(2, calls[1].args["count"])
        assertEquals("clickMatches", calls[2].name)
        assertEquals("data-id", calls[2].args["attrName"])
        assertEquals("^prod-", calls[2].args["pattern"])
        assertEquals("clickNthAnchor", calls[3].name)
        assertEquals(3, calls[3].args["n"])
        assertEquals("#nav", calls[3].args["rootSelector"])
        assertEquals("stop", calls[4].name)
    }

    @Test
    fun `toolCallToDriverLine should map extended tools`() {
        val json = """{"tool_calls":[{"name":"clickTextMatches","args":{"selector":"#list","pattern":"Foo","count":1}}]}""".trimIndent()
        val calls = tta.parseToolCalls(json)
        val line = tta.toolCallToDriverLine(calls.first())
        assertEquals("driver.clickTextMatches(\"#list\", \"Foo\", 1)", line)

        val json2 = """{"tool_calls":[{"name":"scrollToScreen","args":{"screenNumber":4}}]}""".trimIndent()
        val calls2 = tta.parseToolCalls(json2)
        val line2 = tta.toolCallToDriverLine(calls2.first())
        assertEquals("driver.scrollToScreen(4)", line2)
    }
}

