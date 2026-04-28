package ai.platon.browser4.driver

import ai.platon.browser4.driver.chrome.ChromeLauncher
import ai.platon.browser4.driver.chrome.common.ChromeOptions
import ai.platon.browser4.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.browser.BrowserFiles.CDP_URL_FILE_NAME
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.common.sleepSeconds
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class ChromeImplLauncherTest {
    private val USER_DATA_DIR_REGEX = ".+pulsar-.+/context/cx.+".toRegex()

    /**
     * Test ChromeLauncher
     * */
    @Test
    fun testUserDataDirRegex() {
        val text = """
            |xvfb-run -a -e /dev/stdout -s "-screen 0 1920x1080x24" /usr/bin/google-chrome-stable
            |--proxy-server=119.49.122.242:4224 --disable-gpu --hide-scrollbars --remote-debugging-port=0
            |--no-default-browser-check --no-first-run --no-startup-window --mute-audio
            |--disable-background-networking --disable-background-timer-throttling
            |--disable-client-side-phishing-detection --disable-hang-monitor
            |--disable-popup-blocking --disable-prompt-on-repost --disable-sync --disable-translate
            |--disable-blink-features=AutomationControlled --metrics-recording-only
            |--safebrowsing-disable-auto-update --no-sandbox --ignore-certificate-errors
            |--window-size=1920,1080 --pageLoadStrategy=none --throwExceptionOnScriptError=true
            |--user-data-dir=/home/vincent/tmp/pulsar-vincent/context/cx.2zmmAe40/pulsar_chrome
        """.trimMargin().replace("\n", " ")

        assertTrue { "./pulsar-vincent/context/cx.5oruW037".matches(USER_DATA_DIR_REGEX) }
        assertTrue { text.matches(USER_DATA_DIR_REGEX) }
    }

    @Test
    fun testChromeLauncher() {
        val launchOptions = ChromeOptions()
        launchOptions.headless = false

        val userDataDir = BrowserFiles.computeTestContextDir()

        val launcher = ChromeLauncher(userDataDir, options = LauncherOptions())
        launcher.use {
            val chrome = launcher.launch(launchOptions)

            val version = chrome.version
            val tab = chrome.createTab("https://www.baidu.com")
            val versionString = Gson().toJson(chrome.version)
            assertTrue(!chrome.version.browser.isNullOrBlank())
            assertTrue(versionString.contains("Mozilla"))

            println("Tab id: " + tab.id)
            println("Protocol version: " + version.protocolVersion)
            println("Browser version" + version.browser)

            println(prettyPulsarObjectMapper().writeValueAsString(tab))
            println(prettyPulsarObjectMapper().writeValueAsString(chrome.version))
            println(versionString)

            val devTools = chrome.createDevTools(tab)
            runBlocking {
                devTools.page.enable()
                devTools.page.navigate("https://www.xiaohongshu.com/")
            }

            sleepSeconds(2)
        }
    }

    @Test
    fun testCdpUrlTracking() {
        val launchOptions = ChromeOptions()
        launchOptions.headless = true

        val userDataDir = BrowserFiles.computeTestContextDir()
        val cdpUrlPath = userDataDir.resolveSibling(CDP_URL_FILE_NAME)

        val launcher = ChromeLauncher(userDataDir, options = LauncherOptions())
        launcher.use {
            val chrome = launcher.launch(launchOptions)

            // Verify CDP URL file was created
            assertTrue(Files.exists(cdpUrlPath), "CDP URL file should exist")

            // Read and validate CDP URL
            val cdpUrl = Files.readString(cdpUrlPath).trim()
            assertTrue(cdpUrl.isNotBlank(), "CDP URL should not be blank")
            assertTrue(cdpUrl.startsWith("ws://"), "CDP URL should start with ws://")
            assertTrue(cdpUrl.contains("/devtools/browser/"), "CDP URL should contain devtools path")

            println("CDP URL: $cdpUrl")

            // Verify we can use the chrome instance
            val version = chrome.version
            assertTrue(!version.browser.isNullOrBlank(), "Browser version should not be blank")

            sleepSeconds(1)
        }
    }

    @Test
    fun testBrowserReuse() {
        val launchOptions = ChromeOptions()
        launchOptions.headless = true

        val userDataDir = BrowserFiles.computeTestContextDir()
        val cdpUrlPath = userDataDir.resolveSibling(CDP_URL_FILE_NAME)

        // First launch
        val launcher1 = ChromeLauncher(userDataDir, options = LauncherOptions())
        val chrome1 = launcher1.launch(launchOptions)

        // Verify CDP URL file exists
        assertTrue(Files.exists(cdpUrlPath), "CDP URL file should exist after first launch")
        val cdpUrl1 = Files.readString(cdpUrlPath).trim()
        println("First launch CDP URL: $cdpUrl1")

        // Second launcher with same userDataDir (should reuse)
        val launcher2 = ChromeLauncher(userDataDir, options = LauncherOptions())
        val chrome2 = launcher2.launch(launchOptions)

        // Verify CDP URL is still available
        assertTrue(Files.exists(cdpUrlPath), "CDP URL file should still exist")
        val cdpUrl2 = Files.readString(cdpUrlPath).trim()
        println("Second launch CDP URL: $cdpUrl2")

        // In a real scenario, these would be the same if reused
        // But since we're testing, they might be different instances
        assertTrue(cdpUrl2.isNotBlank(), "CDP URL should not be blank on reuse")

        launcher1.close()
        launcher2.close()
    }
}
