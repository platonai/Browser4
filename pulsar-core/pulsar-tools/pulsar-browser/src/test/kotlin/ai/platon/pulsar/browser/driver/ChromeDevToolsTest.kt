package ai.platon.pulsar.browser.driver

import ai.platon.pulsar.browser.driver.chrome.ChromeLauncher
import ai.platon.pulsar.browser.driver.chrome.RemoteChrome
import ai.platon.pulsar.browser.driver.chrome.RemoteDevTools
import ai.platon.pulsar.browser.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.sleepSeconds
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("SkippableLowerLevelTest")
class ChromeDevToolsTest {
    companion object {

    }

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
            val received = devTools.sendAndReceive("Page.navigate", mapOf("url" to "https://www.aliyun.com"))
            println(received)
        }

        sleepSeconds(2)
    }
}
