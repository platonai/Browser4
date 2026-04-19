package ai.platon.browser4.driver.chrome.dom

import ai.platon.pulsar.FastWebDriverService
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end coverage for PulsarWebDriver click overloads against the current interactive screens page.
 */
@Tag("E2ETest")
class PulsarWebDriverClickTests : WebDriverTestBase() {

    override val webDriverService get() = FastWebDriverService(browserFactory)

    companion object {
        private const val SHORT_TIMEOUT = 1_000L
        private const val MEDIUM_TIMEOUT = 2_000L
    }

    @Test
    @DisplayName("click with explicit count triggers calculator button")
    fun testClickSingleCount() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#calculatorSection")

        prepareCalculator(driver, "2", "3")

        driver.bringToFront()
        driver.click("#addButton", 1)

        assertCalculatorResult(driver, "Result: 5")
    }

    @Test
    @DisplayName("click uses default count when omitted")
    fun testClickDefaultCount() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#toggleSection")
        assertMessageHidden(driver)

        driver.bringToFront()
        driver.click("#toggleMessageButton")

        assertMessageVisible(driver)
    }

    @Test
    @DisplayName("click count two toggles message twice")
    fun testClickCountTwo() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#toggleSection")
        assertMessageHidden(driver)

        driver.bringToFront()
        driver.click("#toggleMessageButton", 2)

        assertMessageHidden(driver)
    }

    @Test
    @DisplayName("click count three toggles message three times")
    fun testClickCountThree() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#toggleSection")
        assertMessageHidden(driver)

        driver.bringToFront()
        driver.click("#toggleMessageButton", 3)

        assertMessageVisible(driver)
    }

    @Test
    @DisplayName("click sequential different elements on current screen")
    fun testClickSequentialDifferentElements() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            driver.waitForSelector("#calculatorSection")

            driver.scrollTo("#calculatorSection")
            driver.bringToFront()
            driver.click("#addButton", 1)
            driver.waitUntil(MEDIUM_TIMEOUT) {
                driver.selectFirstTextOrNull("#sumResult") == "Result: Please enter valid numbers"
            }
            assertTrue(
                driver.selectFirstTextOrNull("#sumResult") == "Result: Please enter valid numbers",
                "Calculator button should update the current page state before the next click"
            )

            driver.scrollTo("#toggleSection")
            assertMessageHidden(driver)
            driver.bringToFront()
            driver.click("#toggleMessageButton", 1)
            assertMessageVisible(driver)
        }

    @Test
    @DisplayName("click repeated on same element keeps calculator result stable")
    fun testClickRapidSequentialSameElement() =
        runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
            driver.waitForSelector("#calculatorSection")

            prepareCalculator(driver, "1.5", "2.5")

            repeat(3) {
                driver.bringToFront()
                driver.click("#addButton", 1)
            }

            assertCalculatorResult(driver, "Result: 4")
        }

    @Test
    @DisplayName("click can target out of view element by auto-scrolling")
    fun testClickElementScrolledOutOfView() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#contactSection")
        driver.scrollToBottom()
        assertMessageHidden(driver)

        driver.bringToFront()
        driver.click("#toggleMessageButton", 1)

        assertMessageVisible(driver)
    }

    @Test
    @DisplayName("click remains functional after navigation")
    fun testClickAfterNavigation() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#calculatorSection")

        prepareCalculator(driver, "3", "4")
        driver.bringToFront()
        driver.click("#addButton", 1)
        assertCalculatorResult(driver, "Result: 7")

        driver.navigate(multiScreensInteractiveUrl)
        driver.waitForSelector("#calculatorSection")

        prepareCalculator(driver, "9", "6")
        driver.bringToFront()
        driver.click("#addButton", 1)
        assertCalculatorResult(driver, "Result: 15")
    }

    @Test
    @DisplayName("click with shift modifier still triggers button")
    fun testClickWithShiftModifier() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#toggleSection")
        assertMessageHidden(driver)

        driver.bringToFront()
        driver.click("#toggleMessageButton", "Shift")

        assertMessageVisible(driver)
    }

    @Test
    @DisplayName("click with control modifier still triggers button")
    fun testClickWithControlModifier() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#calculatorSection")

        prepareCalculator(driver, "4", "5")

        driver.bringToFront()
        driver.click("#addButton", "Control")

        assertCalculatorResult(driver, "Result: 9")
    }

    @Test
    @DisplayName("click with alt modifier still triggers button")
    fun testClickWithAltModifier() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#toggleSection")
        assertMessageHidden(driver)

        driver.bringToFront()
        driver.click("#toggleMessageButton", "Alt")

        assertMessageVisible(driver)
    }

    @Test
    @DisplayName("click on non-existent element is a no-op")
    fun testClickNonExistentElement() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#toggleSection")
        assertMessageHidden(driver)

        driver.bringToFront()
        driver.click("[data-testid='missing-button']", 1)

        assertMessageHidden(driver)
        assertTrue(
            driver.selectFirstAttributeOrNull("#toggleMessageButton", "aria-expanded") == "false",
            "A missing-element click should not change page state"
        )
    }

    @Test
    @DisplayName("click on disabled button does not execute onclick handler")
    fun testClickDisabledButton() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.waitForSelector("#calculatorSection")

        prepareCalculator(driver, "10", "5")
        driver.evaluate("document.querySelector('#addButton').disabled = true")

        driver.bringToFront()
        driver.click("#addButton", 1)

        driver.waitUntil(SHORT_TIMEOUT) {
            driver.selectFirstTextOrNull("#sumResult").isNullOrBlank()
        }
        assertTrue(
            driver.selectFirstTextOrNull("#sumResult").isNullOrBlank(),
            "Disabled button should not update result"
        )
    }

    private suspend fun prepareCalculator(driver: WebDriver, first: String, second: String) {
        driver.fill("#num1", first)
        driver.fill("#num2", second)
    }

    private suspend fun assertCalculatorResult(driver: WebDriver, expected: String) {
        driver.waitUntil(MEDIUM_TIMEOUT) {
            driver.selectFirstTextOrNull("#sumResult") == expected
        }
        assertTrue(driver.selectFirstTextOrNull("#sumResult") == expected, "Expected calculator result: $expected")
    }

    private suspend fun assertMessageVisible(driver: WebDriver) {
        driver.waitUntil(MEDIUM_TIMEOUT) {
            !driver.exists("#hiddenMessage.hidden") &&
                    driver.selectFirstAttributeOrNull("#toggleMessageButton", "aria-expanded") == "true" &&
                    driver.selectFirstAttributeOrNull("#hiddenMessage", "aria-hidden") == "false"
        }

        assertFalse(driver.exists("#hiddenMessage.hidden"), "Hidden message should be visible")
        assertTrue(
            driver.selectFirstTextOrNull("#hiddenMessage")?.contains("Surprise!") == true,
            "Visible message text should match the current page content"
        )
    }

    private suspend fun assertMessageHidden(driver: WebDriver) {
        driver.waitUntil(MEDIUM_TIMEOUT) {
            driver.exists("#hiddenMessage.hidden") &&
                    driver.selectFirstAttributeOrNull("#toggleMessageButton", "aria-expanded") == "false" &&
                    driver.selectFirstAttributeOrNull("#hiddenMessage", "aria-hidden") == "true"
        }

        assertTrue(driver.exists("#hiddenMessage.hidden"), "Hidden message should not be visible")
    }
}
