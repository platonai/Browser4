package ai.platon.browser4.driver.chrome

import ai.platon.browser4.chrome.ChromeDestroyer
import ai.platon.pulsar.common.browser.BrowserFiles.CDP_URL_FILE_NAME
import ai.platon.pulsar.common.browser.BrowserFiles.PID_FILE_NAME
import ai.platon.pulsar.common.browser.BrowserFiles.PORT_FILE_NAME
import org.apache.commons.lang3.SystemUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChromeDestroyerTest {
    @Test
    fun testIsZombieDetectsMatchingProcessWhenBackupMarkerExists() {
        val tempDir = Files.createTempDirectory("chrome-destroyer-test-")
        val userDataDir = tempDir.resolve("profile")
        val destroyer = ChromeDestroyer(userDataDir)
        val process = startZombieCandidateProcess(tempDir, userDataDir)

        try {
            Files.writeString(
                userDataDir.resolveSibling("$PID_FILE_NAME.bak"),
                process.pid().toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            assertTrue(destroyer.isZombie())
        } finally {
            destroyForciblyProcessIfAlive(process)
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testIsZombieRequiresBackupMarker() {
        val tempDir = Files.createTempDirectory("chrome-destroyer-test-")
        val userDataDir = tempDir.resolve("profile")
        val destroyer = ChromeDestroyer(userDataDir)
        val process = startZombieCandidateProcess(tempDir, userDataDir)

        try {
            Files.writeString(
                userDataDir.resolveSibling(PID_FILE_NAME),
                process.pid().toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            Files.writeString(
                userDataDir.resolveSibling(PORT_FILE_NAME),
                "9222",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            Files.writeString(
                userDataDir.resolveSibling(CDP_URL_FILE_NAME),
                "ws://127.0.0.1:9222/devtools/browser/test",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            assertFalse(destroyer.isZombie())
        } finally {
            destroyForciblyProcessIfAlive(process)
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testDestroyForciblyUsesBackupPidMarkerWhenPrimaryPidIsUnavailable() {
        val tempDir = Files.createTempDirectory("chrome-destroyer-test-")
        val userDataDir = tempDir.resolve("profile")
        val destroyer = ChromeDestroyer(userDataDir)
        val process = startZombieCandidateProcess(tempDir, userDataDir)

        try {
            Files.writeString(
                userDataDir.resolveSibling("$PID_FILE_NAME.bak"),
                process.pid().toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            destroyer.destroyForcibly()

            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
        } finally {
            destroyForciblyProcessIfAlive(process)
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testKillProcessSkipsExitedCandidatePid() {
        val tempDir = Files.createTempDirectory("chrome-destroyer-test-")
        val userDataDir = tempDir.resolve("profile")
        val destroyer = ChromeDestroyer(userDataDir)
        val process = startZombieCandidateProcess(tempDir, userDataDir)

        try {
            val pid = process.pid()
            destroyForciblyProcessIfAlive(process)

            assertFalse(process.isAlive, "helper zombie process should be terminated before invoking killProcess")
            assertEquals(0, destroyer.killProcess(listOf(pid)))
        } finally {
            destroyForciblyProcessIfAlive(process)
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testKillProcessByPidSkipsExitedPid() {
        val tempDir = Files.createTempDirectory("chrome-destroyer-test-")
        val userDataDir = tempDir.resolve("profile")
        val destroyer = ChromeDestroyer(userDataDir)
        val process = startZombieCandidateProcess(tempDir, userDataDir)

        try {
            val pid = process.pid()
            destroyForciblyProcessIfAlive(process)

            assertFalse(process.isAlive, "helper zombie process should be terminated before invoking killProcessByPid")

            val method = ChromeDestroyer::class.java.getDeclaredMethod("killProcessByPid", Long::class.javaPrimitiveType, String::class.java)
            method.isAccessible = true

            val killed = method.invoke(destroyer, pid, "test") as Boolean
            assertFalse(killed)
        } finally {
            destroyForciblyProcessIfAlive(process)
            tempDir.toFile().deleteRecursively()
        }
    }

    private fun startZombieCandidateProcess(tempDir: Path, userDataDir: Path): Process {
        val command = if (SystemUtils.IS_OS_WINDOWS) {
            val script = tempDir.resolve("chrome-zombie-target.ps1")
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
            val script = tempDir.resolve("chrome-zombie-target.sh")
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
        assertTrue(process.isAlive, "helper zombie process should stay alive during the test")
        return process
    }

    private fun destroyForciblyProcessIfAlive(process: Process) {
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}

