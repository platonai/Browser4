package ai.platon.pulsar.agentic.tools.executors

import ai.platon.pulsar.agentic.common.AgentFileSystem
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.ToolCall
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
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
        val exec = AgentToolExecutor()
        val tc = ToolCall(domain = "agent", method = "act", arguments = mutableMapOf("action" to "Do it"))

        runBlocking { exec.execute(tc, agent) }
        coVerify { agent.act("Do it") }

        // missing param throws
        runBlocking {
            val r = exec.execute(ToolCall("agent", "act", mutableMapOf()), agent)
            assertNotNull(r.exception)
        }
    }

    @Test
    fun fs_readString_uses_named_args() {
        val fs = mockk<AgentFileSystem>(relaxed = true)
        val exec = FileSystemToolExecutor()
        val tc = ToolCall(domain = "fs", method = "readString", arguments = mutableMapOf("filename" to "a.txt", "external" to "true"))

        runBlocking { exec.execute(tc, fs) }
        coVerify { fs.readString("a.txt", true) }
    }

    @Test
    fun browser_switchTab_with_tabId_string() {
        val browser = mockk<ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractBrowser>(relaxed = true)
        val driver = mockk<ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver>(relaxed = true)
        every { browser.drivers } returns linkedMapOf("abc" to driver)
        val exec = BrowserToolExecutor()
        val tc = ToolCall(domain = "browser", method = "switchTab", arguments = mutableMapOf("tabId" to "abc"))

        runBlocking { exec.execute(tc, browser) }
        coVerify { driver.bringToFront() }
    }

    @Test
    fun driver_click_uses_named_args() {
        val driver = mockk<WebDriver>(relaxed = true)
        val exec = WebDriverToolExecutor()
        val tc = ToolCall(domain = "driver", method = "click", arguments = mutableMapOf("selector" to "#ok", "count" to "2"))

        runBlocking { exec.execute(tc, driver) }
        coVerify { driver.click(selector = "#ok", count = 2) }
    }
}
