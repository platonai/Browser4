package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.common.AgentFileSystem
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ExecutorsNamedArgsTest {

    @Test
    fun agent_act_uses_named_args() {
        val agent = mockk<PerceptiveAgent>(relaxed = true)
        val executor = AgentToolExecutor()
        val tc = ToolCall(domain = "agent", method = "act", arguments = mutableMapOf("action" to "Do it"))

        runBlocking { executor.callFunctionOn(tc, agent) }
        coVerify { agent.act("Do it") }

        // missing param throws
        runBlocking {
            val r = executor.callFunctionOn(ToolCall("agent", "act", mutableMapOf()), agent)
            assertNotNull(r.exception)
        }
    }

    @Test
    fun fs_readString_uses_named_args() {
        val fs = mockk<AgentFileSystem>(relaxed = true)
        val executor = FileSystemToolExecutor()
        val tc = ToolCall(domain = "fs", method = "readString", arguments = mutableMapOf("filename" to "a.txt", "external" to "true"))

        runBlocking { executor.callFunctionOn(tc, fs) }
        coVerify { fs.readString("a.txt", true) }
    }

    @Test
    fun browser_switchTab_with_tabId_string() {
        val browser = mockk<ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractBrowser>(relaxed = true)
        val driver = mockk<ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver>(relaxed = true)
        every { browser.drivers } returns linkedMapOf("abc" to driver)
        val executor = BrowserToolExecutor()
        val tc = ToolCall(domain = "browser", method = "switchTab", arguments = mutableMapOf("tabId" to "abc"))

        runBlocking { executor.callFunctionOn(tc, browser) }
        coVerify { driver.bringToFront() }
    }

    @Test
    fun driver_click_uses_named_args() {
        val driver = mockk<WebDriver>(relaxed = true)
        val executor = BrowserTabToolExecutor()
        val tc = ToolCall(domain = "tab", method = "click", arguments = mutableMapOf("selector" to "#ok", "count" to "2"))

        runBlocking { executor.callFunctionOn(tc, driver) }
        coVerify { driver.click(selector = "#ok", count = 2) }
    }

    @Test
    fun driver_drag_uses_named_args() {
        val driver = mockk<WebDriver>(relaxed = true)
        coEvery { driver.drag(any(), any()) } returns Unit
        val executor = BrowserTabToolExecutor()
        val tc = ToolCall(
            domain = "tab",
            method = "drag",
            arguments = mutableMapOf("sourceSelector" to "#from", "targetSelector" to "#to")
        )

        runBlocking { executor.callFunctionOn(tc, driver) }
        coVerify { driver.drag(sourceSelector = "#from", targetSelector = "#to") }
    }

}
