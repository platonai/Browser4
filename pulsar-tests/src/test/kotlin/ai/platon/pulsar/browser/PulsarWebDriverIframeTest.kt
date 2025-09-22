package ai.platon.pulsar.browser

import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Comprehensive test suite for iframe functionality in PulsarWebDriver.
 * Tests frame switching, element interaction, JavaScript execution, and event handling.
 */
class PulsarWebDriverIframeTest : WebDriverTestBase() {

    override val webDriverService get() = WebDriverService(browserFactory, requiredPageSize = 10)

    @Test
    fun testVisitPage() {
        runBlocking {
            browser.newDriver().use { driver ->
                driver.navigateTo("https://www.baidu.com/")
            }
        }
    }

    @Test
    fun testBasicIframeSwitching() = runWebDriverTest("$assetsBaseURL/frames/nested-frames.html", browser) { driver ->
        try {
            assertFalse(driver.isInFrame(), "Should start in main frame")
            assertNull(driver.getCurrentFrame(), "Current frame should be null in main frame")

            // Get frames and ensure we have at least two
            val frames = driver.getFrames()
            assertTrue(frames.size >= 2, "Expected at least 2 frames, got ${'$'}{frames.size}")

            // Switch to first frame by index
            val switchResult = driver.switchToFrame(0)
            assertTrue(switchResult, "Should successfully switch to iframe by index 0")
            assertTrue(driver.isInFrame(), "Should be in iframe after switching")

            val currentFrame1 = driver.getCurrentFrame()
            assertNotNull(currentFrame1, "Current frame should not be null after switching")

            // Remember frame ID for stability check
            val firstFrameId = currentFrame1.frameId

            // Switch back to main frame
            val switchBackResult = driver.switchToDefaultContent()
            assertTrue(switchBackResult, "Should successfully switch back to main frame")
            assertFalse(driver.isInFrame(), "Should be in main frame after switching back")

            // Switch again to first frame to ensure deterministic ordering
            val switchResultAgain = driver.switchToFrame(0)
            assertTrue(switchResultAgain, "Should switch again to iframe index 0")
            val currentFrame2 = driver.getCurrentFrame()
            assertNotNull(currentFrame2)
            assertEquals(firstFrameId, currentFrame2.frameId, "Frame index ordering should be deterministic")

            // Back to default
            driver.switchToDefaultContent()
        } finally {
            driver.close()
        }
    }

    @Test
    fun testSwitchByNameAndParent() = runWebDriverTest("$assetsBaseURL/frames/nested-frames.html", browser) { driver ->
        try {
            // Switch by name '2frames'
            val byName = driver.switchToFrame("2frames")
            assertTrue(byName, "Should switch to frame named '2frames'")
            val frameA = driver.getCurrentFrame()
            assertNotNull(frameA)
            assertEquals("2frames", frameA.name, "Current frame name should be 2frames")

            // Switch back to default content
            val toDefault = driver.switchToDefaultContent()
            assertTrue(toDefault)
            assertFalse(driver.isInFrame())

            // Switch to another named frame 'aframe'
            val byName2 = driver.switchToFrame("aframe")
            assertTrue(byName2, "Should switch to frame named 'aframe'")
            val frameB = driver.getCurrentFrame()
            assertNotNull(frameB)
            assertEquals("aframe", frameB.name)

            // Parent frame of an immediate child should be default -> switching parent should land in default
            val toParent = driver.switchToParentFrame()
            assertTrue(toParent)
            assertFalse(driver.isInFrame(), "After switching to parent we should be back in main frame")
        } finally {
            driver.close()
        }
    }

    @Test
    fun testInvalidFrameSwitching() = runWebDriverTest("$assetsBaseURL/frames/nested-frames.html", browser) { driver ->
        try {
            // Large out-of-range index
            val result = driver.switchToFrame(9999)
            assertFalse(result, "Switching to a non-existent frame index should fail")
            assertFalse(driver.isInFrame())

            // Non-existent name
            val result2 = driver.switchToFrame("__no_such_frame__")
            assertFalse(result2, "Switching to a non-existent frame name should fail")
            assertFalse(driver.isInFrame())
        } finally {
            driver.close()
        }
    }

    @Test
    fun testEvaluateInFrame() = runWebDriverTest("$assetsBaseURL/frames/nested-frames.html", browser) { driver ->
        try {
            val switched = driver.switchToFrame(0)
            assertTrue(switched)
            assertTrue(driver.isInFrame())

            // Evaluate a simple JS expression inside the frame
            val value = driver.evaluateInFrame("document.body ? 'ok' : 'fail'")
            assertEquals("ok", value, "Should evaluate JS within the iframe context")

            // Ensure returning to default resets context
            driver.switchToDefaultContent()
            assertFalse(driver.isInFrame())
        } finally {
            driver.close()
        }
    }
}
