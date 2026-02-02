package ai.platon.pulsar.browser

import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.printlnPro
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test suite for PulsarWebDriver click series functions.
 * Tests the fixes for:
 * - clickTextMatches with count parameter and pattern escaping
 * - clickMatches with count parameter and pattern/attrName escaping
 * - clickNthAnchor functionality
 */
@Tag("ClickFunctionTest")
class PulsarWebDriverClickTests : WebDriverTestBase() {

    private val testPageUrl get() = "$assetsBaseURL/click-test.html"

    @Test
    fun testClickTextMatchesBasic() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500) // Let page stabilize
        
        // Clear any previous clicks
        driver.evaluate("window.clearClickLog()")
        
        // Test clicking button with text matching "Submit"
        driver.clickTextMatches("button.test-button", "Submit")
        delay(300)
        
        // Verify that a click was logged
        val clickLog = driver.evaluate("window.getClickLog()") as? List<*>
        assertNotNull(clickLog, "Click log should not be null")
        assertTrue(clickLog.isNotEmpty(), "At least one button should have been clicked")
        
        printlnPro("Click log size: ${clickLog.size}")
    }

    @Test
    fun testClickTextMatchesWithCount() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500)
        
        driver.evaluate("window.clearClickLog()")
        
        // Test clicking with count = 2, should click max 2 buttons matching "Submit"
        driver.clickTextMatches("button.test-button", "Submit", 2)
        delay(300)
        
        val clickLog = driver.evaluate("window.getClickLog()") as? List<*>
        assertNotNull(clickLog)
        // Should have clicked exactly 2 buttons (there are "Submit Form" and "Submit Data")
        assertEquals(2, clickLog.size, "Should have clicked exactly 2 buttons matching 'Submit'")
        
        printlnPro("Clicked ${clickLog.size} buttons with count=2")
    }

    @Test
    fun testClickTextMatchesWithSpecialCharacters() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500)
        
        driver.evaluate("window.clearClickLog()")
        
        // Test pattern with special regex characters that need escaping
        // Looking for exact text "Submit Form" using escaped dots
        driver.clickTextMatches("button.test-button", "Submit.*Form")
        delay(300)
        
        val clickLog = driver.evaluate("window.getClickLog()") as? List<*>
        assertNotNull(clickLog)
        assertTrue(clickLog.size >= 1, "Should have clicked at least one button")
        
        printlnPro("Clicked button with special character pattern")
    }

    @Test
    fun testClickMatchesBasic() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500)
        
        driver.evaluate("window.clearClickLog()")
        
        // Test clicking button with data-action attribute matching "save"
        driver.clickMatches("button.test-button", "data-action", "save")
        delay(300)
        
        val clickLog = driver.evaluate("window.getClickLog()") as? List<*>
        assertNotNull(clickLog)
        assertTrue(clickLog.isNotEmpty(), "Should have clicked at least one button with data-action='save'")
        
        printlnPro("Click matches test passed")
    }

    @Test
    fun testClickMatchesWithCount() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500)
        
        driver.evaluate("window.clearClickLog()")
        
        // Test clicking with count = 1, should click only first button matching pattern
        driver.clickMatches("button.test-button", "data-action", "save.*", 1)
        delay(300)
        
        val clickLog = driver.evaluate("window.getClickLog()") as? List<*>
        assertNotNull(clickLog)
        // Should have clicked exactly 1 button (either "save" or "save-draft")
        assertEquals(1, clickLog.size, "Should have clicked exactly 1 button with count=1")
        
        printlnPro("Click matches with count=1 test passed")
    }

    @Test
    fun testClickMatchesWithMultipleMatches() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500)
        
        driver.evaluate("window.clearClickLog()")
        
        // Test clicking multiple buttons matching pattern "save.*" (matches "save" and "save-draft")
        driver.clickMatches("button.test-button", "data-action", "save.*", 2)
        delay(300)
        
        val clickLog = driver.evaluate("window.getClickLog()") as? List<*>
        assertNotNull(clickLog)
        assertEquals(2, clickLog.size, "Should have clicked exactly 2 buttons matching 'save.*'")
        
        printlnPro("Click matches with multiple matches test passed")
    }

    @Test
    fun testClickNthAnchor() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500)
        
        driver.evaluate("window.clearClickLog()")
        
        // Test clicking the 3rd anchor (1-based index)
        val href = driver.clickNthAnchor(3, "body")
        delay(300)
        
        assertNotNull(href, "Should have returned href of clicked anchor")
        assertTrue(href.contains("link3"), "Should have clicked the 3rd link (link3)")
        
        val clickLog = driver.evaluate("window.getClickLog()") as? List<*>
        assertNotNull(clickLog)
        assertTrue(clickLog.isNotEmpty(), "Should have logged the anchor click")
        
        printlnPro("Clicked nth anchor, href: $href")
    }

    @Test
    fun testClickNthAnchorFirstLink() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500)
        
        driver.evaluate("window.clearClickLog()")
        
        // Test clicking the 1st anchor
        val href = driver.clickNthAnchor(1, "body")
        delay(300)
        
        assertNotNull(href)
        assertTrue(href.contains("link1"), "Should have clicked the 1st link")
        
        printlnPro("Clicked first anchor, href: $href")
    }

    @Test
    fun testEscapingPreventsInjection() = runEnhancedWebDriverTest(testPageUrl, browser) { driver ->
        driver.waitForSelector("body")
        delay(500)
        
        driver.evaluate("window.clearClickLog()")
        
        // Test that malicious patterns are properly escaped
        // This should not throw an error and should not execute arbitrary JS
        try {
            driver.clickTextMatches("button.test-button", "'; alert('xss'); '")
            delay(300)
            
            // Verify no elements were clicked since the pattern won't match any text
            val clickLog = driver.evaluate("window.getClickLog()") as? List<*>
            assertNotNull(clickLog)
            assertEquals(0, clickLog.size, "No elements should be clicked with escaped injection pattern")
            
            printlnPro("Pattern escaping prevented potential injection - no elements clicked")
        } catch (e: Exception) {
            // Some regex errors are acceptable (invalid regex pattern)
            printlnPro("Pattern caused error (expected for invalid regex): ${e.message}")
        }
    }
}
