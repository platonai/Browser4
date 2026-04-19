package ai.platon.browser4.driver.chrome.dom

import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Slow")
@Tag("E2ETest")
class DOMInteractiveDynamicE2ETest : WebDriverTestBase() {
    private val testURL get() = "$generatedAssetsBaseURL/interactive-dynamic.html"

    @Test
    @DisplayName("test loading dynamic content")
    fun testLoadingDynamicContent() = runEnhancedWebDriverTest(testURL) { driver ->
        driver.waitForSelector("h1")
        val title = driver.selectFirstTextOrNull("h1")
        assertTrue(title?.contains("Dynamic Content Test Page") == true)
        // Load users
        driver.bringToFront()
        driver.click("[data-testid='tta-load-users']")
        val remainingTime = driver.waitForSelector("#dynamicContent.loaded")
        assertTrue { remainingTime.toMillis() > 0 }
        // Verify users list
        assertTrue(driver.exists("#dynamicContent [data-testid^='tta-user-']"))
    }

    @Test
    @DisplayName("test interactions on dynamic content")
    fun testExerciseAsyncLoading() = runEnhancedWebDriverTest(testURL) { driver ->
        // Basic smoke: title and hero content present
        driver.waitForSelector("h1")
        val title = driver.selectFirstTextOrNull("h1")
        assertTrue(title?.contains("Dynamic Content Test Page") == true)

        // 1) Async loading: users -> products -> error -> clear
        exerciseAsyncLoading(driver)
    }

    @Test
    @DisplayName("test interactions on dynamic content")
    fun testExerciseDynamicList() = runEnhancedWebDriverTest(testURL) { driver ->
        // Basic smoke: title and hero content present
        driver.waitForSelector("h1")

        // 2) List management: add, edit, delete, bulk add, clear

        // Add one item
        driver.bringToFront()
        driver.fill("#newItemInput", "Hello Item")
        assertTrue(driver.exists("[data-testid='tta-add-item']"))
        driver.click("[data-testid='tta-add-item']")
        driver.waitUntil(2000) { driver.exists("#itemList [data-testid='tta-item-3']") }

        assertTrue(driver.exists("#itemList [data-testid='tta-item-3']"))
        var listStatus = driver.selectFirstTextOrNull("#listStatus span")
        assertTrue(listStatus?.contains("items in list") == true)

        // Edit item 1
        driver.bringToFront()
        driver.click("[data-testid='tta-edit-1']")
        driver.waitForSelector("#itemList [data-id='1'] input[type='text']")
        driver.fill("#itemList [data-id='1'] input[type='text']", "Edited Item 1")
        driver.press("#itemList [data-id='1'] input[type='text']", "Enter")
        driver.waitUntil(2000) {
            val txt = driver.selectFirstTextOrNull("#itemList [data-id='1'] span")
            txt?.contains("Edited Item 1") == true
        }

        // Delete item 2
        driver.bringToFront()
        driver.click("[data-testid='tta-delete-2']")
        driver.waitUntil(2000) { !driver.exists("#itemList [data-testid='tta-item-2']") }
        listStatus = driver.selectFirstTextOrNull("#listStatus span")
        assertTrue(listStatus?.contains("items in list") == true)

        // Add multiple (5) items
        driver.bringToFront()
        driver.click("[data-testid='tta-add-multiple']")
        driver.waitUntil(2000) {
            val count =
                (driver.evaluateValue("document.querySelectorAll('#itemList .list-item').length") as? Number)?.toInt()
                    ?: 0
            count >= 7 // starting 2 -> +1 -> -1 -> +5 => 7
        }

        // Clear all items
        driver.bringToFront()
        driver.click("[data-testid='tta-clear-all']")
        driver.waitUntil(2000) {
            val count =
                (driver.evaluateValue("document.querySelectorAll('#itemList .list-item').length") as? Number)?.toInt()
                    ?: 0
            count == 0
        }
        listStatus = driver.selectFirstTextOrNull("#listStatus span")
        assertTrue(listStatus?.contains("0 items in list") == true)
    }

    @Test
    @DisplayName("test exercise layz images on dynamic content")
    fun testExerciseLazyImages() = runEnhancedWebDriverTest(testURL) { driver ->
        // Basic smoke: title and hero content present
        driver.waitForSelector("h1")
        val title = driver.selectFirstTextOrNull("h1")
        assertTrue(title?.contains("Dynamic Content Test Page") == true)

        // 3) Lazy images: add more, verify count, clear
        // Add 6 more images
        driver.bringToFront()
        driver.click("[data-testid='tta-add-images']")
        driver.waitUntil(2000) {
            val cnt =
                (driver.evaluateValue("document.querySelectorAll('#imageGrid .lazy-image').length") as? Number)?.toInt()
                    ?: 0
            cnt >= 9 // initial 3 + 6 added
        }
        val total = driver.evaluateValue("document.querySelectorAll('#imageGrid .lazy-image').length") as? Number
        assertTrue((total?.toInt() ?: 0) >= 9)

        // Scroll images into view to trigger IO (no-op if already visible)
        driver.evaluate("window.scrollTo({ top: document.getElementById('imageGrid').offsetTop, behavior: 'instant' })")

        // Clear images
        driver.bringToFront()
        driver.click("[data-testid='tta-clear-images']")
        driver.waitUntil(2000) {
            val cnt =
                (driver.evaluateValue("document.querySelectorAll('#imageGrid .lazy-image').length") as? Number)?.toInt()
                    ?: 0
            cnt == 0
        }
        val cleared = driver.evaluateValue("document.querySelectorAll('#imageGrid .lazy-image').length") as? Number
        assertEquals(0, cleared?.toInt() ?: -1)
    }

    @Test
    @DisplayName("test exercise virtual scrolling on dynamic content")
    fun testExerciseVirtualScrolling() = runEnhancedWebDriverTest(testURL) { driver ->
        // Basic smoke: title and hero content present
        driver.waitForSelector("h1")
        val title = driver.selectFirstTextOrNull("h1")
        assertTrue(title?.contains("Dynamic Content Test Page") == true)

        // 4) Virtual scrolling: generate, scroll, click tail item button

        // Generate 100 items
        driver.bringToFront()
        // TODO: driver.click(selector) fails but evaluate('element.click()') works
        driver.evaluate("""document.querySelector('[data-testid=\'tta-generate-100\']').click()""")
        // driver.click("[data-testid='tta-generate-100']")
        driver.scrollTo("#virtualScrollContent")
        driver.waitUntil(2000) { driver.exists("#virtualScrollContent [data-testid^='tta-virtual-']") }

        assertTrue(driver.exists("#virtualScrollContent [data-testid='tta-virtual-1']"))
        driver.click("[data-testid='tta-virtual-btn-1']")
        driver.waitUntil(2000) {
            val txt = driver.selectFirstTextOrNull("#testStatus span")
            txt?.contains("Virtual item 1 clicked") == true
        }

        // Generate 1000 items
        driver.bringToFront()
        driver.click("[data-testid='tta-generate-1000']")
        driver.waitUntil(2000) { driver.exists("#virtualScrollContent [data-testid^='tta-virtual-']") }

        // Scroll to bottom within the container to reveal tail items
        driver.evaluate(
            """
            const c = document.getElementById('virtualScrollContainer');
            c.scrollTop = c.scrollHeight;
            """.trimIndent()
        )
        // Wait for any tail item to appear
        driver.waitUntil(2000) {
            driver.exists("#virtualScrollContent [data-testid='tta-virtual-1000']") || driver.exists("#virtualScrollContent [data-testid='tta-virtual-999']") || driver.exists(
                "#virtualScrollContent [data-testid='tta-virtual-998']"
            )
        }
        // Click whichever is visible
        val targetId = when {
            driver.exists("#virtualScrollContent [data-testid='tta-virtual-1000']") -> 1000
            driver.exists("#virtualScrollContent [data-testid='tta-virtual-999']") -> 999
            else -> 998
        }
        driver.bringToFront()
        driver.click("[data-testid='tta-virtual-btn-$targetId']")
        driver.waitUntil(2000) {
            val txt = driver.selectFirstTextOrNull("#testStatus span")
            txt?.contains("Virtual item $targetId clicked") == true
        }

        // Clear list
        driver.bringToFront()
        driver.click("[data-testid='tta-clear-virtual']")
        driver.waitUntil(2000) {
            val txt = driver.selectFirstTextOrNull("#virtualScrollContent p")
            txt?.contains("Click a button to generate items") == true
        }
    }

    @Test
    @DisplayName("test exercise error handing on dynamic content")
    fun testExerciseErrorHandling() = runEnhancedWebDriverTest(testURL) { driver ->
        // Basic smoke: title and hero content present
        driver.waitForSelector("h1")
        val title = driver.selectFirstTextOrNull("h1")
        assertTrue(title?.contains("Dynamic Content Test Page") == true)

        // 5) Error handling: trigger + network error + clear
        exerciseErrorHandling(driver)
    }

    // ...existing code...
    private suspend fun exerciseAsyncLoading(driver: WebDriver) {
        // Load users
        driver.bringToFront()
        driver.click("[data-testid='tta-load-users']")
        driver.waitForSelector("#dynamicContent.loaded")
        // Verify users list
        assertTrue(driver.exists("#dynamicContent [data-testid^='tta-user-']"))
        val usersCount =
            driver.evaluateValue("document.querySelectorAll('#dynamicContent [data-testid^=\\'tta-user-\\']').length") as? Number
        assertTrue((usersCount?.toInt() ?: 0) >= 3)
        // Status success
        val loadStatus = driver.selectFirstTextOrNull("#loadingStatus span")
        assertTrue(loadStatus?.contains("loaded successfully") == true)

        // Load products
        driver.bringToFront()
        driver.click("[data-testid='tta-load-products']")
        driver.waitForSelector("#dynamicContent.loaded")
        assertTrue(driver.exists("#dynamicContent [data-testid^='tta-product-']"))
        val productsCount =
            driver.evaluateValue("document.querySelectorAll('#dynamicContent [data-testid^=\\'tta-product-\\']').length") as? Number
        assertTrue((productsCount?.toInt() ?: 0) >= 3)

        // Load error
        driver.bringToFront()
        driver.click("[data-testid='tta-load-error']")
        driver.waitUntil(2000) {
            val txt = driver.selectFirstTextOrNull("#dynamicContent h3")
            txt?.contains("Error Loading Content") == true
        }
        val errStatus = driver.selectFirstTextOrNull("#loadingStatus span")
        assertTrue(errStatus?.contains("Loading failed") == true)

        // Clear content
        driver.bringToFront()
        driver.click("[data-testid='tta-clear-content']")
        driver.waitUntil(2000) {
            val txt = driver.selectFirstTextOrNull("#dynamicContent p")
            txt?.contains("Click a button to load content") == true
        }
        val ready = driver.selectFirstTextOrNull("#loadingStatus span")
        assertTrue(ready?.contains("Ready") == true)
    }

    // ...existing code...
    private suspend fun exerciseDynamicList(driver: WebDriver) {
    }

    // ...existing code...
    private suspend fun exerciseLazyImages(driver: WebDriver) {
    }

    // ...existing code...
    private suspend fun exerciseVirtualScrolling(driver: WebDriver) {
    }

    // ...existing code...
    private suspend fun exerciseErrorHandling(driver: WebDriver) {
        // Trigger error and verify boundary
        driver.bringToFront()
        driver.click("[data-testid='tta-trigger-error']")
        driver.waitUntil(2000) { driver.exists("#errorBoundary.show") }
        val errMsg = driver.selectFirstTextOrNull("#errorMessage")
        assertTrue(errMsg?.contains("simulated error") == true)

        // Clear error
        driver.bringToFront()
        driver.click("[data-testid='tta-clear-error']")
        driver.waitUntil(2000) { !driver.exists("#errorBoundary.show") }
        val ok = driver.selectFirstTextOrNull("#testStatus span")
        assertTrue(ok?.contains("All tests ready") == true)

        // Simulate network error
        driver.bringToFront()
        driver.click("[data-testid='tta-network-error']")
        driver.waitUntil(2000) {
            val txt = driver.selectFirstTextOrNull("#testStatus span")
            txt?.contains("Network error occurred") == true
        }
        val netErr = driver.selectFirstTextOrNull("#errorMessage")
        assertTrue(netErr?.contains("Network request failed") == true)

        // Clear again
        driver.bringToFront()
        driver.click("[data-testid='tta-clear-error']")
        driver.waitUntil(2000) { !driver.exists("#errorBoundary.show") }
    }
}

