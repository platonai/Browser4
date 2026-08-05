package ai.platon.pulsar.chrome.dom

import ai.platon.pulsar.FastWebDriverService
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.api.WebDriver
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("E2ETest")
class PulsarWebDriverCheckTests : WebDriverTestBase() {

    override val webDriverService get() = FastWebDriverService(browserFactory)

    companion object {
        private const val MEDIUM_TIMEOUT = 2_000L
    }

    private val checkboxUrl get() = "$assetsBaseURL/input/checkbox.html"

    @Test
    @DisplayName("check and uncheck work without pulsar utils")
    fun testCheckAndUncheckWithoutPulsarUtils() = runWebDriverTestAndCompute(checkboxUrl, browser) { driver ->
        driver.waitForSelector("#agree")
        removePulsarUtils(driver)

        assertFalse(driver.isChecked("#agree"), "Checkbox should start unchecked")
        assertFalse(driver.evaluate("document.querySelector('#agree')?.checked === true", false))

        driver.check("#agree")
        driver.waitUntil(MEDIUM_TIMEOUT) { driver.isChecked("#agree") }
        assertTrue(driver.isChecked("#agree"), "Checkbox should be checked after driver.check()")
        assertTrue(driver.evaluate("document.querySelector('#agree')?.checked === true", false))

        driver.uncheck("#agree")
        driver.waitUntil(MEDIUM_TIMEOUT) { !driver.isChecked("#agree") }
        assertFalse(driver.isChecked("#agree"), "Checkbox should be unchecked after driver.uncheck()")
        assertFalse(driver.evaluate("document.querySelector('#agree')?.checked === true", false))
    }

    private suspend fun removePulsarUtils(driver: WebDriver) {
        val isolatedWorldType = driver.evaluate(
            "delete window.__pulsar_utils__; typeof window.__pulsar_utils__",
            ""
        )
        assertEquals("undefined", isolatedWorldType, "Isolated world should no longer expose __pulsar_utils__")

        val pageWorldType = driver.evaluate(
            """
                (() => {
                    const attr = 'data-page-world-pulsar-utils';
                    const script = document.createElement('script');
                    script.textContent = "delete window.__pulsar_utils__; document.documentElement.setAttribute('" + attr + "', typeof window.__pulsar_utils__);";
                    document.documentElement.appendChild(script);
                    script.remove();
                    return document.documentElement.getAttribute(attr);
                })()
            """.trimIndent(),
            ""
        )
        assertEquals("undefined", pageWorldType, "Page world should no longer expose __pulsar_utils__")
    }
}

