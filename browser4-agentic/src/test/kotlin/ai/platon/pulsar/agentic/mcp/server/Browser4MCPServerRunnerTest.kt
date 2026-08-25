package ai.platon.pulsar.agentic.mcp.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [parseMcpServerOptions].
 *
 * The key contract: the MCP server defaults to HEADLESS (the standalone
 * runner has no Spring-wired server configuration, so without an explicit
 * headless preference the session browser would launch HEADED), and
 * `--headed` opts back into a visible window.
 */
@DisplayName("Browser4MCPServerRunner options")
class Browser4MCPServerRunnerTest {

    @Test
    @DisplayName("headless defaults to true")
    fun headlessDefaultsToTrue() {
        val options = parseMcpServerOptions(emptyArray())
        assertNotNull(options)
        assertTrue(options!!.headless)
    }

    @Test
    @DisplayName("--headless keeps headless mode on")
    fun explicitHeadlessKeepsHeadlessOn() {
        val options = parseMcpServerOptions(arrayOf("--headless"))
        assertNotNull(options)
        assertTrue(options!!.headless)
    }

    @Test
    @DisplayName("--headed turns headless mode off")
    fun headedTurnsHeadlessOff() {
        val options = parseMcpServerOptions(arrayOf("--headed"))
        assertNotNull(options)
        assertFalse(options!!.headless)
    }

    @Test
    @DisplayName("the last of --headless/--headed wins")
    fun lastDisplayFlagWins() {
        val options = parseMcpServerOptions(arrayOf("--headless", "--headed"))
        assertNotNull(options)
        assertFalse(options!!.headless)

        val backToHeadless = parseMcpServerOptions(arrayOf("--headed", "--headless"))
        assertNotNull(backToHeadless)
        assertTrue(backToHeadless!!.headless)
    }

    @Test
    @DisplayName("transport and port are parsed")
    fun transportAndPortAreParsed() {
        val options = parseMcpServerOptions(arrayOf("--transport", "http", "--port", "9090"))
        assertNotNull(options)
        assertEquals("http", options!!.transport)
        assertEquals(9090, options.port)
    }

    @Test
    @DisplayName("--help and -h request usage (null options)")
    fun helpRequestsUsage() {
        assertNull(parseMcpServerOptions(arrayOf("--help")))
        assertNull(parseMcpServerOptions(arrayOf("-h")))
    }

    @Test
    @DisplayName("an unknown option requests usage (null options)")
    fun unknownOptionRequestsUsage() {
        assertNull(parseMcpServerOptions(arrayOf("--bogus")))
    }
}
