package ai.platon.browser4.chrome.manage

import ai.platon.browser4.chrome.ChromeLauncher
import ai.platon.pulsar.common.browser.BrowserFiles.PID_FILE_NAME
import org.apache.commons.lang3.SystemUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.*

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

    @Test
    fun testDestroyForciblyKillsRecordedProcessPid() {
        val tempDir = Files.createTempDirectory("chrome-launcher-test-")
        val userDataDir = tempDir.resolve("profile")
        val launcher = ChromeLauncher(userDataDir)
        val process = startLockingProcess(tempDir, userDataDir)

        try {
            Files.writeString(
                userDataDir.resolveSibling(PID_FILE_NAME),
                process.pid().toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            launcher.destroyForcibly()

            assertTrue(
                process.waitFor(15, TimeUnit.SECONDS),
                "destroyForcibly should kill the recorded process pid through the stronger cleanup path"
            )
        } finally {
            destroyProcessIfAlive(process)
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun startLockingProcess(tempDir: Path, userDataDir: Path): Process {
        val command = if (SystemUtils.IS_OS_WINDOWS) {
            val script = tempDir.resolve("chrome-lock-target.ps1")
            val chromeExecutable = tempDir.resolve("chrome.exe")
            val powershellExecutable = Path.of(
                System.getenv("WINDIR") ?: "C:\\Windows",
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe"
            )
            Files.writeString(
                script,
                "param([string]\$Marker, [string]\$UserDataDir)\nwhile (\$true) { Start-Sleep -Seconds 1 }\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            Files.copy(powershellExecutable, chromeExecutable, StandardCopyOption.REPLACE_EXISTING)

            listOf(
                chromeExecutable.toString(),
                "-NoProfile",
                "-File",
                script.toString(),
                "-Marker",
                "chrome",
                "-UserDataDir",
                userDataDir.toAbsolutePath().toString()
            )
        } else {
            val script = tempDir.resolve("chrome-lock-target.sh")
            Files.writeString(
                script,
                """
                #!/usr/bin/env bash
                while true; do
                    sleep 1
                done
                """.trimIndent(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            listOf(
                "bash",
                script.toString(),
                "chrome",
                userDataDir.toAbsolutePath().toString()
            )
        }

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        Thread.sleep(250)
        assertTrue(process.isAlive, "helper locking process should stay alive during the test")
        return process
    }

    private fun destroyProcessIfAlive(process: Process) {
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}

