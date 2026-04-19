package ai.platon.browser4.driver.chrome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChromeLauncherTest {
    @Test
    fun testCommandLineContainsUserDataDirNormalizesWindowsSeparators() {
        val cmdLine = "\"C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe\" --user-data-dir=C:\\Users\\tester\\AppData\\Local\\Browser4\\profile"
        val userDataDir = "C:/Users/tester/AppData/Local/Browser4/profile"

        assertTrue(ChromeLauncher.commandLineContainsUserDataDir(cmdLine, userDataDir, ignoreCase = true))
    }

    @Test
    fun testCommandLineContainsUserDataDirRejectsBlankValues() {
        assertFalse(ChromeLauncher.commandLineContainsUserDataDir("", "/tmp/profile"))
        assertFalse(ChromeLauncher.commandLineContainsUserDataDir("chrome --user-data-dir=/tmp/profile", ""))
    }

    @Test
    fun testParseProcessListingLine() {
        val parsed = ChromeLauncher.parseProcessListingLine("  12345   /usr/bin/google-chrome --user-data-dir=/tmp/profile  ")

        assertNotNull(parsed)
        assertEquals(12345L, parsed.first)
        assertEquals("/usr/bin/google-chrome --user-data-dir=/tmp/profile", parsed.second)
    }

    @Test
    fun testParseProcessListingLineRejectsInvalidLines() {
        assertNull(ChromeLauncher.parseProcessListingLine(""))
        assertNull(ChromeLauncher.parseProcessListingLine("CommandLine ProcessId"))
        assertNull(ChromeLauncher.parseProcessListingLine("chrome.exe --user-data-dir=C:/tmp/profile"))
    }
}

