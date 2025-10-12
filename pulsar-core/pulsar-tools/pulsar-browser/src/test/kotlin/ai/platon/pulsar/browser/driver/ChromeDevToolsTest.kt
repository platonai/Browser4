package ai.platon.pulsar.browser.driver

import ai.platon.pulsar.browser.driver.chrome.ChromeLauncher
import ai.platon.pulsar.browser.driver.chrome.RemoteChrome
import ai.platon.pulsar.browser.driver.chrome.RemoteDevTools
import ai.platon.pulsar.browser.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.browser.driver.chrome.invoke
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.sleepSeconds
import com.github.kklisura.cdt.protocol.v2023.types.page.Navigate
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.*

@Tag("SkippableLowerLevelTest")
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
        devTools.page.enable()
    }

    @AfterTest
    fun closeBrowser() {
        chrome.close()
        launcher.close()
    }

    @Test
    fun testDevTools() {
        devTools.page.navigate("https://www.xiaohongshu.com/")

        runBlocking {
            // ▶ Send {"id":1,"method":"Page.navigate","params":{"url":"https://www.aliyun.com","id":"4"}}
            //  Accept {"id":1,"result":{"frameId":"5209F155E679677705D979C8F6DBF6A5","loaderId":"CEEE5FEC31BD255B9ECBB55CB75FB172","isDownload":false}}
            val navigate: Navigate? = devTools.invoke("Page.navigate", mapOf("url" to "https://www.aliyun.com"))
            assertNotNull(navigate)
        }

        sleepSeconds(2)
    }
}
