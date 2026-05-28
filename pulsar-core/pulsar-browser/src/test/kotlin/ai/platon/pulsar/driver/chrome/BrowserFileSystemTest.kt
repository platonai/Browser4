package ai.platon.pulsar.driver.chrome

import ai.platon.pulsar.chrome.BrowserFileSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserFileSystemTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testReadWriteMarkersAndArtifacts() {
        val browserFileSystem = BrowserFileSystem(createUserDataDir())

        browserFileSystem.writeStartingPort()
        browserFileSystem.writePid(12345L)
        browserFileSystem.writeCdpUrl("ws://127.0.0.1:9222/devtools/browser/test")
        val argumentsPath = browserFileSystem.writeLaunchArguments(
            "chrome.exe",
            listOf("--headless=new", "--remote-debugging-port=0")
        )
        val reportPaths = browserFileSystem.writeLaunchReport(
            "{" + "\"ok\"" + ":true}",
            LocalDateTime.of(2026, 5, 27, 10, 11, 12, 0)
        )
        val outputPath = browserFileSystem.writeLastProcessOutput("DevTools listening on ws://127.0.0.1:9222/devtools/browser/test")

        assertEquals(0, browserFileSystem.readPort())
        assertEquals(12345L, browserFileSystem.readPid())
        assertEquals("ws://127.0.0.1:9222/devtools/browser/test", browserFileSystem.readCdpUrl())
        assertNotNull(argumentsPath)
        assertNotNull(reportPaths)
        assertNotNull(outputPath)
        assertTrue(Files.exists(argumentsPath))
        assertTrue(Files.exists(reportPaths.first))
        assertTrue(Files.exists(reportPaths.second))
        assertTrue(Files.exists(outputPath))
        assertEquals(
            listOf("chrome.exe", "--headless=new", "--remote-debugging-port=0"),
            Files.readAllLines(argumentsPath)
        )
        assertEquals("DevTools listening on ws://127.0.0.1:9222/devtools/browser/test", browserFileSystem.readLastProcessOutput())
    }

    @Test
    fun testClearAndRestoreProcessMarkers() {
        val browserFileSystem = BrowserFileSystem(createUserDataDir())

        browserFileSystem.writePort(9222)
        browserFileSystem.writePid(22334L)
        browserFileSystem.writeCdpUrl("ws://127.0.0.1:9222/devtools/browser/test")

        browserFileSystem.clearProcessMarkers()

        assertFalse(Files.exists(browserFileSystem.portPath))
        assertFalse(Files.exists(browserFileSystem.pidPath))
        assertFalse(Files.exists(browserFileSystem.cdpUrlPath))
        assertTrue(browserFileSystem.hasBackupMarks())
        assertEquals("9222", Files.readString(browserFileSystem.portBakPath).trim())
        assertEquals("22334", Files.readString(browserFileSystem.pidBakPath).trim())

        assertTrue(browserFileSystem.restoreProcessMarkersFromBackup())
        assertEquals(9222, browserFileSystem.readPositivePort())
        assertEquals(22334L, browserFileSystem.readPid())
        assertEquals("ws://127.0.0.1:9222/devtools/browser/test", browserFileSystem.readCdpUrl())

        browserFileSystem.deleteBackupMarks()
        assertFalse(browserFileSystem.hasBackupMarks())
    }

    @Test
    fun testClearUserDataDirRemovesTransientEntriesOnly() {
        val userDataDir = createUserDataDir()
        val browserFileSystem = BrowserFileSystem(userDataDir)
        val singletonLock = userDataDir.resolve("SingletonLock")
        val devToolsActivePort = userDataDir.resolve("DevToolsActivePort")
        val preferences = userDataDir.resolve("Preferences")

        Files.writeString(singletonLock, "lock")
        Files.writeString(devToolsActivePort, "9222")
        Files.writeString(preferences, "persist")

        browserFileSystem.clearUserDataDir()

        assertFalse(Files.exists(singletonLock))
        assertFalse(Files.exists(devToolsActivePort))
        assertTrue(Files.exists(preferences))
    }

    @Test
    fun testConcurrentClearProcessMarkersKeepsConsistentBackups() {
        val browserFileSystem = BrowserFileSystem(createUserDataDir())
        writeProcessMarkers(browserFileSystem, 9222, 22334L, "ws://127.0.0.1:9222/devtools/browser/test")

        val threadCount = 4
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)

        try {
            val futures = (1..threadCount).map {
                executor.submit {
                    assertTrue(startLatch.await(5, TimeUnit.SECONDS))
                    browserFileSystem.clearProcessMarkers()
                }
            }

            startLatch.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }

        assertFalse(Files.exists(browserFileSystem.portPath))
        assertFalse(Files.exists(browserFileSystem.pidPath))
        assertFalse(Files.exists(browserFileSystem.cdpUrlPath))
        assertTrue(browserFileSystem.hasBackupMarks())
        assertEquals("9222", Files.readString(browserFileSystem.portBakPath).trim())
        assertEquals("22334", Files.readString(browserFileSystem.pidBakPath).trim())
        assertEquals(
            "ws://127.0.0.1:9222/devtools/browser/test",
            Files.readString(browserFileSystem.cdpUrlBakPath).trim()
        )
    }

    @Test
    fun testRestoreProcessMarkersRespectsOverwriteFlag() {
        val browserFileSystem = BrowserFileSystem(createUserDataDir())
        writeProcessMarkers(browserFileSystem, 9222, 22334L, "ws://127.0.0.1:9222/devtools/browser/original")

        browserFileSystem.clearProcessMarkers()
        writeProcessMarkers(browserFileSystem, 9333, 33445L, "ws://127.0.0.1:9333/devtools/browser/current")

        assertTrue(browserFileSystem.restoreProcessMarkersFromBackup(overwrite = false))
        assertEquals(9333, browserFileSystem.readPositivePort())
        assertEquals(33445L, browserFileSystem.readPid())
        assertEquals("ws://127.0.0.1:9333/devtools/browser/current", browserFileSystem.readCdpUrl())

        assertTrue(browserFileSystem.restoreProcessMarkersFromBackup(overwrite = true))
        assertEquals(9222, browserFileSystem.readPositivePort())
        assertEquals(22334L, browserFileSystem.readPid())
        assertEquals("ws://127.0.0.1:9222/devtools/browser/original", browserFileSystem.readCdpUrl())
    }

    @Test
    fun testCleanupInvalidProcessFilesRemovesMarkersWithoutCreatingBackups() {
        val browserFileSystem = BrowserFileSystem(createUserDataDir())
        writeProcessMarkers(browserFileSystem, 9222, 22334L, "ws://127.0.0.1:9222/devtools/browser/test")

        assertEquals(0, browserFileSystem.cleanupInvalidProcessFiles())

        assertFalse(Files.exists(browserFileSystem.portPath))
        assertFalse(Files.exists(browserFileSystem.pidPath))
        assertFalse(Files.exists(browserFileSystem.cdpUrlPath))
        assertFalse(browserFileSystem.hasBackupMarks())
        assertNull(browserFileSystem.readPositivePort())
        assertNull(browserFileSystem.readPid())
        assertNull(browserFileSystem.readCdpUrl())
    }

    private fun writeProcessMarkers(browserFileSystem: BrowserFileSystem, port: Int, pid: Long, cdpUrl: String) {
        browserFileSystem.writePort(port)
        browserFileSystem.writePid(pid)
        browserFileSystem.writeCdpUrl(cdpUrl)
    }

    private fun createUserDataDir(): Path {
        val userDataDir = tempDir.resolve("contexts").resolve("group").resolve("isolated").resolve("cx.1").resolve("chrome")
        Files.createDirectories(userDataDir)
        return userDataDir
    }
}

