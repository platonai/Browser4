package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractBrowser
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class BrowserToolExecutorTest {

    private lateinit var browser: AbstractBrowser
    private lateinit var executor: BrowserToolExecutor

    @BeforeEach
    fun setUp() {
        browser = mockk(relaxed = true)
        executor = BrowserToolExecutor()
    }

    @Test
        @DisplayName("help returns available methods")
    fun helpReturnsAvailableMethods() {
        val help = executor.help()

        assertNotNull(help)
        assertTrue(help.isNotBlank())
        assertTrue(help.contains("Switch to a specific browser tab"))
    }

    @Test
        @DisplayName("help for switchTab method returns detailed help")
    fun helpForSwitchtabMethodReturnsDetailedHelp() {
        val help = executor.help("switchTab")

        assertNotNull(help)
        assertTrue(help.contains("Switch to a specific browser tab"))
        assertTrue(help.contains("switchTab"))
    }

    @Test
        @DisplayName("help for unknown method returns empty string")
    fun helpForUnknownMethodReturnsEmptyString() {
        val help = executor.help("unknownMethod")

        assertEquals("", help)
    }

    @Test
        @DisplayName("switchTab with invalid tab returns exception")
    fun switchtabWithInvalidTabReturnsException() = runBlocking {
        every { browser.findDriverById(any()) } returns null
        every { browser.drivers } returns mutableMapOf()

        val tc = ToolCall(
            domain = "browser",
            method = "switchTab",
            arguments = mutableMapOf("tabId" to "999")
        )

        val result = executor.callFunctionOn(tc, browser)

        assertNotNull(result.exception)
        assertTrue(result.exception?.cause?.message?.contains("not found") == true)
    }

    @Test
        @DisplayName("domain property is browser")
    fun domainPropertyIsBrowser() {
        assertEquals("browser", executor.domain)
    }
}
