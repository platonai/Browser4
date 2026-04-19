package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

class WebDriverExToolExecutorTest {

    private lateinit var driver: AbstractWebDriver
    private lateinit var executor: WebDriverExToolExecutor

    @BeforeEach
    fun setUp() {
        driver = mockk(relaxed = true)
        executor = WebDriverExToolExecutor()
    }

    @Test
        @DisplayName("help returns available methods")
    fun helpReturnsAvailableMethods() {
        val help = executor.help()

        assertNotNull(help)
        assertTrue(help.isNotBlank())
        assertTrue(help.contains("Extract text content"))
    }

    @Test
        @DisplayName("help for extract method returns detailed help")
    fun helpForExtractMethodReturnsDetailedHelp() {
        val help = executor.help("extract")

        assertNotNull(help)
        assertTrue(help.contains("Extract text content"))
        assertTrue(help.contains("extract"))
    }

    @Test
        @DisplayName("help for unknown method returns empty string")
    fun helpForUnknownMethodReturnsEmptyString() {
        val help = executor.help("unknownMethod")

        assertEquals("", help)
    }

    @Test
        @DisplayName("extract calls selectTextAll with union selector")
    fun extractCallsSelecttextallWithUnionSelector() = runBlocking {
        coEvery { driver.selectTextAll(any()) } returns listOf("text1", "text2")

        val tc = ToolCall(
            domain = "driverEx",
            method = "extract",
            arguments = mutableMapOf("selectors" to ".class1,.class2,#id1")
        )

        executor.callFunctionOn(tc, driver)

        coVerify { driver.selectTextAll(".class1,.class2,#id1") }
    }

    @Test
        @DisplayName("extract with comma-separated string selectors")
    fun extractWithCommaSeparatedStringSelectors() = runBlocking {
        coEvery { driver.selectTextAll(any()) } returns listOf("text")

        val tc = ToolCall(
            domain = "driverEx",
            method = "extract",
            arguments = mutableMapOf("selectors" to ".class1,.class2")
        )

        executor.callFunctionOn(tc, driver)

        coVerify { driver.selectTextAll(".class1,.class2") }
    }

    @Test
        @DisplayName("unsupported method returns exception")
    fun unsupportedMethodReturnsException() = runBlocking {
        val tc = ToolCall(
            domain = "driverEx",
            method = "unsupportedMethod",
            arguments = mutableMapOf()
        )

        val result = executor.callFunctionOn(tc, driver)

        assertNotNull(result.exception)
        assertTrue(result.exception?.cause?.message?.contains("Unsupported") == true)
    }

    @Test
        @DisplayName("domain property is driverEx")
    fun domainPropertyIsDriverex() {
        assertEquals("driverEx", executor.domain)
    }
}
