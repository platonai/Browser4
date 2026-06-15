package ai.platon.browser4.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class HelpTest {

    @Test
    fun `global help contains expected sections`() {
        val help = generateHelp()
        assertTrue(help.contains("Usage:"))
        assertTrue(help.contains("browser4-cli"))
        assertTrue(help.contains("Navigation:"))
        assertTrue(help.contains("Core:"))
    }

    @Test
    fun `global help lists goto command`() {
        val help = generateHelp()
        assertTrue(help.contains("goto"), "Global help should list 'goto'")
        assertTrue(help.contains("Navigate to a URL"), "Should show goto description")
    }

    @Test
    fun `global help contains global options section`() {
        val help = generateHelp()
        assertTrue(help.contains("Global options:"))
        assertTrue(help.contains("--json"))
        assertTrue(help.contains("--server=<url>"))
    }

    @Test
    fun `global help does not list hidden commands`() {
        val help = generateHelp()
        // No hidden commands defined yet, but this validates the filter works
        val hiddenCmds = allCommands().filter { it.hidden }
        for (cmd in hiddenCmds) {
            assertFalse(
                help.contains("  ${cmd.name} "),
                "Hidden command '${cmd.name}' should not appear in global help"
            )
        }
    }

    @Test
    fun `per-command help for open shows syntax`() {
        val cmd = commandsMap()["open"]!!
        val help = generateCommandHelp(cmd)
        assertTrue(help.contains("browser4-cli open"))
        assertTrue(help.contains("[url]"))
    }

    @Test
    fun `per-command help for goto shows required arg`() {
        val cmd = commandsMap()["goto"]!!
        val help = generateCommandHelp(cmd)
        assertTrue(help.contains("<url>"))
    }
}
