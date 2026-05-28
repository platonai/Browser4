package ai.platon.pulsar.driver.chrome

import ai.platon.pulsar.chrome.BrowserFileSystem
import ai.platon.pulsar.chrome.ChromeDestroyer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChromeDestroyerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testClearProcessMarkersDelegatesToBrowserFileSystem() {
        val userDataDir = createUserDataDir()
        val browserFileSystem = BrowserFileSystem(userDataDir)
        val chromeDestroyer = ChromeDestroyer(userDataDir)

        browserFileSystem.writePort(9222)
        browserFileSystem.writePid(12345L)
        browserFileSystem.writeCdpUrl("ws://127.0.0.1:9222/devtools/browser/test")

        chromeDestroyer.clearProcessMarkers()

        assertFalse(Files.exists(browserFileSystem.portPath))
        assertFalse(Files.exists(browserFileSystem.pidPath))
        assertFalse(Files.exists(browserFileSystem.cdpUrlPath))
        assertTrue(browserFileSystem.hasBackupMarks())
        assertTrue(chromeDestroyer.hasZombieProcessMarkers())
    }

    @Test
    fun testDestroyUsesRecordedPidFromPrimaryOrBackupMarker() {
        val userDataDir = createUserDataDir()
        val browserFileSystem = BrowserFileSystem(userDataDir)
        val chromeDestroyer = ChromeDestroyer(userDataDir)

        browserFileSystem.writePid(22222L)
        assertEquals(listOf(33333L, 22222L), chromeDestroyer.distinctPositivePids(33333L, browserFileSystem.readPid()))

        chromeDestroyer.clearProcessMarkers()
        assertEquals(22222L, browserFileSystem.readPid(preferBackup = true))
        assertTrue(chromeDestroyer.hasZombieProcessMarkers())
    }

    private fun createUserDataDir(): Path {
        val userDataDir = tempDir.resolve("contexts").resolve("group").resolve("isolated").resolve("cx.1").resolve("chrome")
        Files.createDirectories(userDataDir)
        return userDataDir
    }
}

