package ai.platon.pulsar.agentic.inference.detail

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for DOM stability strategies.
 */
class DOMStabilityStrategiesTest {

    private lateinit var mockSession: AgenticSession
    private lateinit var mockDriver: WebDriver
    private lateinit var mockPageStateTracker: PageStateTracker

    @BeforeEach
    fun setUp() {
        mockSession = mockk(relaxed = true)
        mockDriver = mockk(relaxed = true)
        mockPageStateTracker = mockk(relaxed = true)

        every { mockSession.getOrCreateBoundDriver() } returns mockDriver
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `NetworkIdleStrategy should pass when no inflight requests`() = runTest {
        // Arrange
        val config = StabilityConfig(
            networkIdleTime = 100,
            maxInflightRequests = 0,
            timeout = 5000
        )
        val strategy = NetworkIdleStrategy(mockSession, config)

        // Mock: No inflight requests
        every { mockDriver.evaluateValue(any()) } returns 0

        // Act
        val result = strategy.check()

        // Assert
        assertTrue(result, "Should pass when no inflight requests")
    }

    @Test
    fun `DOMStabilityStrategy should delegate to PageStateTracker`() = runTest {
        // Arrange
        val config = StabilityConfig(timeout = 5000, checkIntervalMs = 100)
        val strategy = DOMStabilityStrategy(mockPageStateTracker, config)

        // Mock: PageStateTracker succeeds
        coEvery {
            mockPageStateTracker.waitForDOMSettle(any(), any())
        } just Runs

        // Act
        val result = strategy.check()

        // Assert
        assertTrue(result, "Should pass when PageStateTracker succeeds")
        coVerify(exactly = 1) {
            mockPageStateTracker.waitForDOMSettle(config.timeout, config.checkIntervalMs)
        }
    }

    @Test
    fun `ContentQualityStrategy should pass with sufficient content`() = runTest {
        // Arrange
        val config = StabilityConfig(
            minHeight = 1000,
            minElements = 10,
            minAnchors = 5,
            minImages = 2
        )
        val strategy = ContentQualityStrategy(mockSession, config)

        // Mock: Sufficient content
        every { mockDriver.evaluateValue(any()) } returns mapOf(
            "height" to 1500,
            "elements" to 50,
            "anchors" to 10,
            "images" to 5
        )

        // Act
        val result = strategy.check()

        // Assert
        assertTrue(result, "Should pass with sufficient content")
    }

    @Test
    fun `StabilityConfig presets should have expected values`() {
        // DEFAULT
        val default = StabilityConfig.DEFAULT
        assertEquals(30_000, default.timeout)
        assertEquals(StabilityMode.ANY_N, default.mode)

        // FAST
        val fast = StabilityConfig.FAST
        assertEquals(10_000, fast.timeout)
        assertEquals(1, fast.requiredStrategies)

        // THOROUGH
        val thorough = StabilityConfig.THOROUGH
        assertEquals(60_000, thorough.timeout)
        assertEquals(StabilityMode.ALL, thorough.mode)
        assertEquals(5, thorough.domStableChecks)

        // SPA
        val spa = StabilityConfig.SPA
        assertEquals(20_000, spa.timeout)
        assertEquals(0, spa.maxInflightRequests)
    }
}
