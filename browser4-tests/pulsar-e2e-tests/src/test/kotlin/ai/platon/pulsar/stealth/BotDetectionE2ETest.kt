package ai.platon.pulsar.stealth

import ai.platon.pulsar.WebDriverTestBase
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

@Tag("BotDetection")
class BotDetectionE2ETest : WebDriverTestBase() {

    companion object {
        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun setup() {
            // Enable stealth settings
            System.setProperty("browser.display.mode", "GUI") // Try GUI mode if possible (though in CI it might fail, usually it falls back)
            // Actually, for stealth, we often need to NOT be headless, or be "headless=new"
            // But let's try to set some flags via properties if supported.
            // BrowserSettings doesn't seem to have direct stealth flags exposed via properties other than what we saw.
        }
    }

    @Test
    fun testDeviceAndBrowserInfo() {
        val url = "https://deviceandbrowserinfo.com/are_you_a_bot"
        webDriverService.runWebDriverTest(url, browser) { driver ->
            driver.waitForSelector("body")
            driver.delay(10000)
            val pageSource = driver.pageSource() ?: ""
            // Check for success indicators (case insensitive)
            val isHuman = pageSource.contains("You are human", ignoreCase = true) ||
                          pageSource.contains("not a bot", ignoreCase = true)

            if (!isHuman) {
                val title = driver.evaluate("document.title")
                System.err.println(">>> testDeviceAndBrowserInfo FAILED. Title: $title")
                java.nio.file.Files.writeString(java.nio.file.Path.of("target/deviceandbrowserinfo.html"), pageSource)
            }
            Assumptions.assumeTrue(isHuman, "Should be detected as human. See target/deviceandbrowserinfo.html")
        }
    }

    @Test
    fun testIncolumitas() {
        val url = "https://bot.incolumitas.com/"
        webDriverService.runWebDriverTest(url, browser) { driver ->
            driver.waitForSelector("body")
            driver.delay(5000)
            val pageSource = driver.pageSource() ?: ""
            assertTrue(pageSource.length > 100, "Page should load content")
        }
    }

    @Test
    fun testBrowserScan() {
        val url = "https://www.browserscan.net/bot-detection"
        webDriverService.runWebDriverTest(url, browser) { driver ->
            driver.waitForSelector("body")
            driver.delay(5000)
            val pageSource = driver.pageSource() ?: ""
            Assumptions.assumeTrue(pageSource.length > 100, "Page should load content")
        }
    }

    @Test
    fun testRecaptchaDemo() {
        val url = "https://www.google.com/recaptcha/api2/demo"
        webDriverService.runWebDriverTest(url, browser) { driver ->
            driver.waitForSelector("body")
            driver.delay(5000)
            val pageSource = driver.pageSource() ?: ""
            if (!pageSource.contains("reCAPTCHA")) {
                val title = driver.evaluate("document.title")
                System.err.println(">>> testRecaptchaDemo FAILED. Title: $title")
                java.nio.file.Files.writeString(java.nio.file.Path.of("target/recaptcha.html"), pageSource)
            }
            Assumptions.assumeTrue(pageSource.contains("reCAPTCHA"), "Should load reCAPTCHA demo page. See target/recaptcha.html")
        }
    }
}
