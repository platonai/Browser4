package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [ToolExposeMode] — default is native TOOL_CALLING after the
 * feedback-loop overhaul (P5); explicit `text`/`chat` remain selectable.
 */
class ToolExposeModeTest {

    private fun conf(vararg kv: Pair<String, String>): MutableConfig =
        MutableConfig().apply { kv.forEach { (k, v) -> set(k, v) } }

    @Test
    @DisplayName("default is TOOL_CALLING when the config key is absent or unknown")
    fun testDefaultIsToolCalling() {
        assertEquals(ToolExposeMode.TOOL_CALLING, ToolExposeMode.from(MutableConfig()))
        assertEquals(ToolExposeMode.TOOL_CALLING, ToolExposeMode.from(conf("agent.tool.expose.mode" to "bogus")))
    }

    @Test
    @DisplayName("explicit text/chat/toolCalling are honored")
    fun testExplicitModes() {
        assertEquals(ToolExposeMode.TEXT, ToolExposeMode.from(conf("agent.tool.expose.mode" to "text")))
        assertEquals(ToolExposeMode.CHAT, ToolExposeMode.from(conf("agent.tool.expose.mode" to "chat")))
        assertEquals(ToolExposeMode.TOOL_CALLING, ToolExposeMode.from(conf("agent.tool.expose.mode" to "toolCalling")))
        assertEquals(ToolExposeMode.TOOL_CALLING, ToolExposeMode.from(conf("agent.tool.expose.mode" to "langchain4j")))
    }

    @Test
    @DisplayName("nativeToolCalling and prompt tool-list flags agree")
    fun testModeFlags() {
        assertFalse(ToolExposeMode.TEXT.nativeToolCalling)
        assertTrue(ToolExposeMode.TEXT.includeToolListInPrompt)
        assertTrue(ToolExposeMode.TOOL_CALLING.nativeToolCalling)
        assertFalse(ToolExposeMode.TOOL_CALLING.includeToolListInPrompt)
    }
}
