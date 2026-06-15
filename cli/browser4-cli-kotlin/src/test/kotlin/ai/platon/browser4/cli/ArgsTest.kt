package ai.platon.browser4.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ArgsTest {

    // ---- Global flags ----

    @Test
    fun `parseGlobalFlags with no arguments`() {
        val flags = parseGlobalFlags(emptyList())
        assertEquals(emptyList<String>(), flags.args)
        assertNull(flags.sessionName)
        assertFalse(flags.json)
        assertFalse(flags.quiet)
    }

    @Test
    fun `parseGlobalFlags session name via -s=`() {
        val flags = parseGlobalFlags(listOf("-s=mysession", "open", "https://example.com"))
        assertEquals("mysession", flags.sessionName)
        assertEquals(listOf("open", "https://example.com"), flags.args)
    }

    @Test
    fun `parseGlobalFlags session name via --session name`() {
        val flags = parseGlobalFlags(listOf("--session", "mysession", "open"))
        assertEquals("mysession", flags.sessionName)
        assertEquals(listOf("open"), flags.args)
    }

    @Test
    fun `parseGlobalFlags server url`() {
        val flags = parseGlobalFlags(listOf("--server=http://localhost:9999", "open"))
        assertEquals("http://localhost:9999", flags.serverUrl)
    }

    @Test
    fun `parseGlobalFlags json and quiet`() {
        val flags = parseGlobalFlags(listOf("--json", "-q", "snapshot"))
        assertTrue(flags.json)
        assertTrue(flags.quiet)
        assertEquals(listOf("snapshot"), flags.args)
    }

    @Test
    fun `parseGlobalFlags does not treat --json after command as global`() {
        val flags = parseGlobalFlags(listOf("batch", "--json"))
        assertFalse(flags.json) // after command, not global
        assertEquals(listOf("batch", "--json"), flags.args)
    }

    @Test
    fun `parseGlobalFlags respects BROWSER4_CLI_SESSION env var`() {
        // Note: this test does not actually set the env var; it tests the
        // fallback path.  The env-var path is covered by integration tests.
        val flags = parseGlobalFlags(listOf("open"))
        assertNull(flags.sessionName)
    }

    // ---- Raw args ----

    @Test
    fun `parseRawArgs positional args stored in _`() {
        val result = parseRawArgs(listOf("goto", "https://example.com"))
        assertEquals("goto,https://example.com", result["_"])
    }

    @Test
    fun `parseRawArgs named options`() {
        val result = parseRawArgs(listOf("--headed", "--profile-mode=temporary"))
        assertEquals("true", result["headed"])
        assertEquals("temporary", result["profile-mode"])
    }

    @Test
    fun `parseRawArgs key value separated`() {
        val result = parseRawArgs(listOf("--profile", "/path/to/profile"))
        assertEquals("/path/to/profile", result["profile"])
    }

    // ---- buildCommandArgs ----

    @Test
    fun `buildCommandArgs maps positional to arg names`() {
        val raw = mapOf("_" to "goto,https://example.com")
        val args = buildCommandArgs(raw, listOf("url"))
        assertEquals("https://example.com", args["url"])
    }

    @Test
    fun `buildCommandArgs throws on too many positional args`() {
        val raw = mapOf("_" to "goto,a,b,c,d")
        assertThrows(IllegalArgumentException::class.java) {
            buildCommandArgs(raw, listOf("url"))
        }
    }
}
