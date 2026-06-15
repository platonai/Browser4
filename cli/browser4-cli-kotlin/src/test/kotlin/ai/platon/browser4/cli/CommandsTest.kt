package ai.platon.browser4.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CommandsTest {

    @Test
    fun `all command names are unique`() {
        val names = allCommands().map { it.name }
        assertEquals(names.size, names.toSet().size, "Duplicate command names found: $names")
    }

    @Test
    fun `expected core commands exist`() {
        val cmdMap = commandsMap()
        assertNotNull(cmdMap["open"])
        assertNotNull(cmdMap["goto"])
        assertNotNull(cmdMap["close"])
        assertNotNull(cmdMap["list"])
        assertNotNull(cmdMap["snapshot"])
        assertNotNull(cmdMap["screenshot"])
        assertNotNull(cmdMap["help"])
    }

    @Test
    fun `open resolves tool name based on url presence`() {
        val cmd = commandsMap()["open"]!!
        assertEquals("browser_navigate", cmd.toolNameFn(mapOf("url" to "https://example.com")))
        assertEquals("browser_snapshot", cmd.toolNameFn(emptyMap()))
    }

    @Test
    fun `batch has empty tool name and params`() {
        val cmd = commandsMap()["batch"]!!
        assertEquals("", cmd.toolNameFn(emptyMap()))
        assertEquals(emptyMap<String, Any>(), cmd.toolParamsFn(emptyMap()))
    }

    @Test
    fun `every command has a category`() {
        for (cmd in allCommands()) {
            assertNotNull(cmd.category, "Command '${cmd.name}' has no category")
        }
    }

    @Test
    fun `every command has a description`() {
        for (cmd in allCommands()) {
            assertTrue(
                cmd.description.isNotBlank(),
                "Command '${cmd.name}' has an empty description"
            )
        }
    }
}
