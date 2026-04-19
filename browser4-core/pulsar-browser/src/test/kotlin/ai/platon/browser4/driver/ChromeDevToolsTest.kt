package ai.platon.browser4.driver

import ai.platon.browser4.driver.chrome.ChromeLauncher
import ai.platon.browser4.driver.chrome.RemoteChrome
import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.browser4.driver.chrome.common.LauncherOptions
import ai.platon.browser4.driver.chrome.invoke
import ai.platon.cdt.kt.protocol.types.page.Navigate
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

        runBlocking { devTools.page.enable() }
    }

    @AfterTest
    fun closeBrowser() {
        chrome.close()
        launcher.close()
    }

    @Test
    fun testDevTools() {
        runBlocking {
            devTools.page.navigate("https://www.xiaohongshu.com/")
            // ▶ Send {"id":1,"method":"Page.navigate","params":{"url":"https://www.aliyun.com","id":"4"}}
            //  Accept {"id":1,"result":{"frameId":"5209F155E679677705D979C8F6DBF6A5","loaderId":"CEEE5FEC31BD255B9ECBB55CB75FB172","isDownload":false}}
            val navigate: Navigate? = devTools.invoke("Page.navigate", mapOf("url" to "https://www.aliyun.com"))
            assertNotNull(navigate)
        }

        sleepSeconds(2)
    }
}
