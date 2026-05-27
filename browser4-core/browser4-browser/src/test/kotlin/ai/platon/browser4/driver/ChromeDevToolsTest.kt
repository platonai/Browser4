package ai.platon.browser4.driver

import ai.platon.browser4.driver.chrome.protocol.BrowserProtocol
import ai.platon.browser4.driver.chrome.ChromeLauncher
import ai.platon.browser4.driver.chrome.RemoteChrome
import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.browser4.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.sleepSeconds
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChromeDevToolsTest {

    private lateinit var launcher: ChromeLauncher
    private lateinit var chrome: RemoteChrome
    private lateinit var devTools: RemoteDevTools
    private lateinit var browserProtocol: BrowserProtocol

    @BeforeTest
    fun createDevTools() {
        val userDataDir = BrowserFiles.computeTestContextDir()

        launcher = ChromeLauncher(userDataDir, options = LauncherOptions())
        chrome = launcher.launch()

        val tab = chrome.createTab()
        val versionString = Gson().toJson(chrome.version)
        assertTrue(!chrome.version.browser.isNullOrBlank())
        assertTrue(versionString.contains("Mozilla"))

        devTools = chrome.createDevTools(tab)
        browserProtocol = BrowserProtocol(devTools)

        runBlocking { browserProtocol.pageEnable() }
    }

    @AfterTest
    fun closeBrowser() {
        chrome.close()
        launcher.close()
    }

    @Test
    fun testDevTools() {
        runBlocking {
            browserProtocol.navigate("https://vercel.com/")
            val navigate = browserProtocol.navigate("https://www.example.com/")
            assertNotNull(navigate)
        }

        sleepSeconds(2)
    }
}
