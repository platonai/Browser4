package ai.platon.pulsar.driver

import ai.platon.pulsar.chrome.ChromeDestroyer
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.chrome.BrowserFileSystem
import ai.platon.pulsar.chrome.ChromeLauncher
import ai.platon.pulsar.chrome.util.ChromeOptions
import ai.platon.pulsar.chrome.util.LauncherOptions
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChromeRecoveryIntegrationTest {
    @Test
    fun testConcurrentLaunchersCompeteForSameUserDataDirButReuseSingleBrowser() {
        val userDataDir = BrowserFiles.computeTestContextDir()
        val browserFileSystem = BrowserFileSystem(userDataDir)
        val chromeOptions = ChromeOptions().also { it.headless = true }
        val launchers = List(3) { ChromeLauncher(userDataDir, options = LauncherOptions()) }
        val executor = Executors.newFixedThreadPool(launchers.size)
        val startLatch = CountDownLatch(1)

        try {
            val futures = launchers.map { launcher ->
                executor.submit<Int> {
                    assertTrue(startLatch.await(10, TimeUnit.SECONDS))
                    launcher.launch(chromeOptions).port
                }
            }

            startLatch.countDown()
            val ports = futures.map { it.get(90, TimeUnit.SECONDS) }
            val distinctPorts = ports.distinct()

            assertEquals(1, distinctPorts.size, "Concurrent launchers should converge on one browser port")
            assertEquals(distinctPorts.single(), browserFileSystem.readPositivePort())
            assertNotNull(browserFileSystem.readPid())
            assertNotNull(browserFileSystem.readCdpUrl())
            assertEquals(1, launchers.count { it.isAlive }, "Only one launcher should own the spawned process")
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            launchers.asReversed().forEach { it.close() }
        }
    }

    @Test
    fun testLauncherCanReuseBrowserAfterMarkersAreRestoredFromBackup() {
        val userDataDir = BrowserFiles.computeTestContextDir()
        val browserFileSystem = BrowserFileSystem(userDataDir)
        val chromeOptions = ChromeOptions().also { it.headless = true }

        val launcher1 = ChromeLauncher(userDataDir, options = LauncherOptions())
        val launcher2 = ChromeLauncher(userDataDir, options = LauncherOptions())

        try {
            launcher1.launch(chromeOptions)

            val originalPort = browserFileSystem.readPositivePort()
            val originalPid = browserFileSystem.readPid()
            val originalCdpUrl = browserFileSystem.readCdpUrl()
            assertNotNull(originalPort)
            assertNotNull(originalPid)
            assertNotNull(originalCdpUrl)

            browserFileSystem.clearProcessMarkers()
            assertTrue(browserFileSystem.hasBackupMarks())
            assertFalse(browserFileSystem.portPath.toFile().exists())
            assertFalse(browserFileSystem.pidPath.toFile().exists())
            assertFalse(browserFileSystem.cdpUrlPath.toFile().exists())

            assertTrue(browserFileSystem.restoreProcessMarkersFromBackup(overwrite = true))
            assertEquals(originalPort, browserFileSystem.readPositivePort())
            assertEquals(originalPid, browserFileSystem.readPid())
            assertEquals(originalCdpUrl, browserFileSystem.readCdpUrl())

            browserFileSystem.deleteBackupMarks()
            assertFalse(browserFileSystem.hasBackupMarks())

            launcher2.launch(chromeOptions)

            assertEquals(originalPort, browserFileSystem.readPositivePort())
            assertEquals(originalPid, browserFileSystem.readPid())
            assertEquals(originalCdpUrl, browserFileSystem.readCdpUrl())
        } finally {
            launcher2.close()
            launcher1.close()
        }
    }

    @Test
    fun testDestroyerCanRecoverBackupOnlyZombieState() {
        val userDataDir = BrowserFiles.computeTestContextDir()
        val browserFileSystem = BrowserFileSystem(userDataDir)
        val chromeDestroyer = ChromeDestroyer(userDataDir)
        val launcher = ChromeLauncher(userDataDir, options = LauncherOptions())

        try {
            launcher.launch(ChromeOptions().also { it.headless = true })

            assertTrue(launcher.isAlive)
            browserFileSystem.clearProcessMarkers()

            assertTrue(browserFileSystem.hasBackupMarks())
            assertTrue(chromeDestroyer.hasZombieProcessMarkers())
            assertTrue(chromeDestroyer.isZombie())

            chromeDestroyer.destroyZombie()

            assertTrue(waitForCondition(timeoutMillis = 10_000) { !launcher.isAlive })
            assertFalse(chromeDestroyer.isZombie())
            assertFalse(browserFileSystem.portPath.toFile().exists())
            assertFalse(browserFileSystem.pidPath.toFile().exists())
            assertFalse(browserFileSystem.cdpUrlPath.toFile().exists())
        } finally {
            launcher.close()
        }
    }

    private fun waitForCondition(timeoutMillis: Long, intervalMillis: Long = 200, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(intervalMillis)
        }
        return condition()
    }
}

