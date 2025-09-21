package ai.platon.pulsar.browser

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.browser.driver.cdt.FrameInfo
import ai.platon.pulsar.browser.driver.cdt.FrameLoadState
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.test.TestBase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.time.Duration
import kotlin.test.*

/**
 * Comprehensive test suite for iframe functionality in PulsarWebDriver.
 * Tests frame switching, element interaction, JavaScript execution, and event handling.
 */
class IframeTest : TestBase() {

    private val testPageUrl by lazy {
        val testPage = File("src/main/resources/static/generated/iframe-test.html")
        testPage.parentFile.mkdirs()
        testPage.writeText(createTestPage())
        testPage.toURI().toString()
    }

    private val crossOriginTestPageUrl by lazy {
        val testPage = File("src/main/resources/static/generated/iframe-cross-origin-test.html")
        testPage.parentFile.mkdirs()
        testPage.writeText(createCrossOriginTestPage())
        testPage.toURI().toString()
    }

    private val nestedIframeTestPageUrl by lazy {
        val testPage = File("src/main/resources/static/generated/iframe-nested-test.html")
        testPage.parentFile.mkdirs()
        testPage.writeText(createNestedIframeTestPage())
        testPage.toURI().toString()
    }

    @Test
    fun testBasicIframeSwitching() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(testPageUrl)
            delay(1000) // Wait for page to load

            // Initially should be in main frame
            assertFalse(driver.isInFrame(), "Should start in main frame")
            assertNull(driver.getCurrentFrame(), "Current frame should be null in main frame")

            // Test switching to iframe by index
            val switchResult = driver.switchToFrame(0)
            assertTrue(switchResult, "Should successfully switch to iframe by index")
            assertTrue(driver.isInFrame(), "Should be in iframe after switching")

            val currentFrame = driver.getCurrentFrame()
            assertNotNull(currentFrame, "Current frame should not be null after switching")
            assertEquals("iframe1", currentFrame.context.name, "Should be in iframe1")

            // Test switching back to main frame
            val switchBackResult = driver.switchToDefaultContent()
            assertTrue(switchBackResult, "Should successfully switch back to main frame")
            assertFalse(driver.isInFrame(), "Should be in main frame after switching back")

        } finally {
            driver.close()
        }
    }

    @Test
    fun testIframeSwitchingByName() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(testPageUrl)
            delay(1000)

            // Test switching to iframe by name
            val switchResult = driver.switchToFrame("iframe2")
            assertTrue(switchResult, "Should successfully switch to iframe by name")

            val currentFrame = driver.getCurrentFrame()
            assertNotNull(currentFrame)
            assertEquals("iframe2", currentFrame.context.name)

        } finally {
            driver.close()
        }
    }

    @Test
    fun testElementInteractionInIframes() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(testPageUrl)
            delay(1000)

            // Switch to first iframe
            driver.switchToFrame(0)

            // Test element interaction within iframe
            val elementExists = driver.exists("#iframe-input")
            assertTrue(elementExists, "Element should exist in iframe")

            // Test typing in iframe element
            driver.type("#iframe-input", "Test input from iframe")
            val inputValue = driver.selectFirstAttributeOrNull("#iframe-input", "value")
            assertEquals("Test input from iframe", inputValue, "Input value should be set correctly")

            // Test clicking in iframe
            driver.click("#iframe-button")
            delay(500)

            // Verify click effect
            val buttonText = driver.selectFirstAttributeOrNull("#iframe-button", "data-clicked")
            assertEquals("true", buttonText, "Button should be marked as clicked")

        } finally {
            driver.close()
        }
    }

    @Test
    fun testJavaScriptExecutionInIframes() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(testPageUrl)
            delay(1000)

            // Switch to iframe
            driver.switchToFrame(0)

            // Test JavaScript execution in iframe
            val result = driver.evaluateInFrame("document.title")
            assertEquals("Iframe Test Page", result, "Should be able to access iframe document properties")

            // Test modifying iframe content
            driver.evaluateInFrame("document.body.style.backgroundColor = 'lightblue'")
            delay(200)

            val bgColor = driver.evaluateInFrame("document.body.style.backgroundColor")
            assertEquals("lightblue", bgColor, "Should be able to modify iframe styles")

        } finally {
            driver.close()
        }
    }

    @Test
    fun testGetAllFrames() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(testPageUrl)
            delay(1000)

            val frames = driver.getFrames()
            assertEquals(2, frames.size, "Should find 2 iframes")

            val frameNames = frames.map { it.context.name }
            assertTrue(frameNames.contains("iframe1"), "Should contain iframe1")
            assertTrue(frameNames.contains("iframe2"), "Should contain iframe2")

            // Verify frame properties
            val iframe1 = frames.find { it.context.name == "iframe1" }
            assertNotNull(iframe1)
            assertTrue(iframe1.isAccessible, "iframe1 should be accessible")
            assertEquals(FrameLoadState.LOADED, iframe1.loadState, "iframe1 should be loaded")

        } finally {
            driver.close()
        }
    }

    @Test
    fun testNestedIframeSwitching() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(nestedIframeTestPageUrl)
            delay(1500) // Wait for nested iframes to load

            // Switch to parent iframe
            driver.switchToFrame("parent-iframe")
            assertTrue(driver.isInFrame(), "Should be in parent iframe")

            val parentFrame = driver.getCurrentFrame()
            assertNotNull(parentFrame)
            assertEquals("parent-iframe", parentFrame.context.name)

            // Switch to nested iframe
            driver.switchToFrame("nested-iframe")
            assertTrue(driver.isInFrame(), "Should be in nested iframe")

            val nestedFrame = driver.getCurrentFrame()
            assertNotNull(nestedFrame)
            assertEquals("nested-iframe", nestedFrame.context.name)

            // Test element interaction in deeply nested iframe
            val nestedElementExists = driver.exists("#nested-element")
            assertTrue(nestedElementExists, "Should find element in nested iframe")

            // Switch back to parent frame
            driver.switchToParentFrame()
            val currentFrame = driver.getCurrentFrame()
            assertNotNull(currentFrame)
            assertEquals("parent-iframe", currentFrame.context.name)

            // Switch to main frame
            driver.switchToDefaultContent()
            assertFalse(driver.isInFrame(), "Should be back in main frame")

        } finally {
            driver.close()
        }
    }

    @Test
    fun testFrameNavigationEvents() = runBlocking {
        val driver = session.newDriver()
        val navigationEvents = mutableListOf<String>()

        try {
            // Set up frame navigation event handler
            driver.onFrameNavigated = { frame ->
                navigationEvents.add("Frame ${frame.name} navigated to ${frame.url}")
            }

            driver.navigateTo(testPageUrl)
            delay(1000)

            // Navigate within an iframe
            driver.switchToFrame(0)
            driver.evaluateInFrame("window.location.href = 'data:text/html,<html><body>Navigated</body></html>'")
            delay(1000)

            assertTrue(navigationEvents.isNotEmpty(), "Should have captured frame navigation events")

        } finally {
            driver.close()
        }
    }

    @Test
    fun testWaitForFrameLoad() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(testPageUrl)

            val frames = driver.getFrames()
            assertTrue(frames.isNotEmpty(), "Should have frames to test")

            val frameId = frames.first().context.frameId
            val loadResult = driver.waitForFrameLoad(frameId, Duration.ofSeconds(5))
            assertTrue(loadResult, "Frame should load within timeout")

        } finally {
            driver.close()
        }
    }

    @Test
    fun testInvalidFrameSwitching() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(testPageUrl)
            delay(1000)

            // Test switching to non-existent frame by name
            val invalidSwitch = driver.switchToFrame("non-existent-frame")
            assertFalse(invalidSwitch, "Should fail to switch to non-existent frame")

            // Test switching to invalid index
            val invalidIndexSwitch = driver.switchToFrame(999)
            assertFalse(invalidIndexSwitch, "Should fail to switch to invalid index")

            // Should still be in main frame
            assertFalse(driver.isInFrame(), "Should remain in main frame after failed switch")

        } finally {
            driver.close()
        }
    }

    @Test
    fun testCrossOriginFrameHandling() = runBlocking {
        val driver = session.newDriver()
        try {
            driver.navigateTo(crossOriginTestPageUrl)
            delay(2000) // Wait longer for cross-origin content

            val frames = driver.getFrames()
            assertTrue(frames.isNotEmpty(), "Should find cross-origin frames")

            // Test switching to cross-origin frame
            val crossOriginFrame = frames.find { it.context.name == "cross-origin-frame" }
            if (crossOriginFrame != null) {
                val switchResult = driver.switchToFrame("cross-origin-frame")
                // Note: Cross-origin switching may be restricted by browser security
                // The test should handle both success and failure cases gracefully
                if (switchResult) {
                    assertTrue(driver.isInFrame(), "Should be in cross-origin frame")
                }
            }

        } finally {
            driver.close()
        }
    }

    // Helper functions to create test HTML pages

    private fun createTestPage(): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Iframe Test Page</title>
        </head>
        <body>
            <h1>Main Frame Content</h1>
            <p>This is the main frame content.</p>

            <iframe id="iframe1" name="iframe1" src="data:text/html,<html><head><title>Iframe Test Page</title></head><body><h2>Iframe 1 Content</h2><input id='iframe-input' type='text' placeholder='Type here'><button id='iframe-button' onclick='this.dataset.clicked=true'>Click Me</button></body></html>"></iframe>
            <iframe id="iframe2" name="iframe2" src="data:text/html,<html><body><h2>Iframe 2 Content</h2><p>This is iframe 2</p></body></html>"></iframe>
        </body>
        </html>
    """.trimIndent()

    private fun createCrossOriginTestPage(): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Cross-Origin Iframe Test</title>
        </head>
        <body>
            <h1>Cross-Origin Frame Test</h1>
            <iframe name="cross-origin-frame" src="https://example.com" width="400" height="300"></iframe>
        </body>
        </html>
    """.trimIndent()

    private fun createNestedIframeTestPage(): String = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Nested Iframe Test</title>
        </head>
        <body>
            <h1>Main Frame with Nested Iframes</h1>
            <iframe name="parent-iframe" src="data:text/html,<html><head><title>Parent Iframe</title></head><body><h2>Parent Iframe Content</h2><iframe name='nested-iframe' src='data:text/html,<html><body><h3>Nested Iframe Content</h3><p id='nested-element'>This is deeply nested</p></body></html>'></iframe></body></html>"></iframe>
        </body>
        </html>
    """.trimIndent()
}