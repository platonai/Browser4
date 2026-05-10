package ai.platon.pulsar.browser

import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.AppFiles
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.printlnPro
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class PulsarWebDriverScreenshotTests : WebDriverTestBase() {
    private val screenshotDir = AppPaths.TEST_DIR.resolve("screenshot")

    @Test
    fun testCaptureScreenshot() = runEnhancedWebDriverTest(interactiveUrl, browser) { driver ->
        driver.waitForSelector("#userInformation")
        assertTrue { driver.exists("#userInformation") }
        val pageSource = driver.pageSource()
        assertNotNull(pageSource)

        val fieldSelectors = mapOf(
            "body" to "body",
            "header" to "header",
            "userInformation" to "#userInformation",
        )
        val paths = mutableListOf<Path>()
        fieldSelectors.entries.take(3).forEach { (name, selector) ->
            val screenshot = driver.runCatching { screenshot(selector) }
                .onFailure { logger.info("Failed to screenshot | $name - $selector") }
                .getOrNull()

            if (screenshot != null) {
                val path = exportScreenshot("$name.jpg", screenshot)
                paths.add(path)
                delay(1000.milliseconds)
            }
        }

        if (paths.isNotEmpty()) {
            printlnPro(String.format("%d screenshots are saved | %s", paths.size, paths[0].parent))
        }
    }

    @Throws(IOException::class)
    private fun exportScreenshot(filename: String, screenshot: String): Path {
        val path = screenshotDir.resolve(filename)
        val bytes = Base64.getDecoder().decode(screenshot)
        return AppFiles.saveTo(bytes, path, true)
    }
}
