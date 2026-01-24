package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.*
import ai.platon.pulsar.agentic.inference.detail.ExecutionContext
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import io.mockk.mockk
import io.mockk.every
import java.time.Instant

/**
 * Test utilities for BrowserPerceptiveAgent testing.
 * 
 * Provides factory methods and builders to simplify test setup,
 * reducing the complexity of creating test instances with all
 * required dependencies.
 */
object BrowserPerceptiveAgentTestUtils {
    
    /**
     * Creates a minimal mock AgenticSession for testing.
     * 
     * @param withBoundDriver Whether to include a bound WebDriver
     * @return Mocked AgenticSession
     */
    fun createMockSession(withBoundDriver: Boolean = false): AgenticSession {
        val session = mockk<AgenticSession>(relaxed = true)
        
        if (withBoundDriver) {
            val driver = mockk<WebDriver>(relaxed = true)
            every { session.boundDriver } returns driver
        } else {
            every { session.boundDriver } returns null
        }
        
        return session
    }
    
    /**
     * Creates a minimal AgentConfig for testing with reasonable defaults.
     * 
     * @param maxSteps Maximum steps for the test scenario
     * @param maxRetries Maximum retries for the test scenario
     * @return AgentConfig suitable for testing
     */
    fun createTestConfig(
        maxSteps: Int = 5,
        maxRetries: Int = 1,
        enableStructuredLogging: Boolean = false,
        enablePerformanceMetrics: Boolean = false,
        enableTodoWrites: Boolean = false
    ): AgentConfig {
        return AgentConfig(
            maxSteps = maxSteps,
            maxRetries = maxRetries,
            consecutiveNoOpLimit = 3,
            enableStructuredLogging = enableStructuredLogging,
            enablePerformanceMetrics = enablePerformanceMetrics,
            enableTodoWrites = enableTodoWrites,
            logInferenceToFile = false,
            enableDebugMode = false,
            memoryCleanupIntervalSteps = 10,
            maxHistorySize = 20,
            // Shorter timeouts for tests
            actTimeoutMs = 5_000,
            llmInferenceTimeoutMs = 5_000,
            resolveTimeoutMs = 10_000,
            actionGenerationTimeoutMs = 3_000,
            screenshotCaptureTimeoutMs = 1_000,
            domSettleTimeoutMs = 1_000
        )
    }
    
    /**
     * Creates a minimal ExecutionContext for testing.
     * 
     * @param instruction The instruction to execute
     * @param step Current step number
     * @return ExecutionContext suitable for testing
     */
    fun createTestContext(
        instruction: String = "test instruction",
        step: Int = 0,
        targetUrl: String = "https://example.com"
    ): ExecutionContext {
        val agentState = AgentState(
            instruction = instruction,
            step = step,
            targetUrl = targetUrl,
            stepStartTime = Instant.now()
        )
        
        return ExecutionContext(
            instruction = instruction,
            agentState = agentState,
            step = step,
            targetUrl = targetUrl,
            stepStartTime = Instant.now(),
            sid = "test-sid-${System.currentTimeMillis()}"
        )
    }
    
    /**
     * Creates a testable BrowserPerceptiveAgent with mocked dependencies.
     * 
     * This method creates an agent instance that can be used in unit tests
     * without requiring a real browser or external services.
     * 
     * @param config Agent configuration
     * @param withBoundDriver Whether to include a mocked bound driver
     * @return Testable BrowserPerceptiveAgent instance
     */
    fun createTestAgent(
        config: AgentConfig = createTestConfig(),
        withBoundDriver: Boolean = false
    ): TestableBrowserPerceptiveAgent {
        val session = createMockSession(withBoundDriver)
        return TestableBrowserPerceptiveAgent(session, config.maxSteps, config)
    }
}

/**
 * Testable version of BrowserPerceptiveAgent that exposes internal methods
 * for unit testing while maintaining the original implementation.
 * 
 * This class makes protected methods accessible for testing without
 * changing the visibility of the production code.
 */
class TestableBrowserPerceptiveAgent(
    session: AgenticSession,
    maxSteps: Int = 100,
    config: AgentConfig = AgentConfig(maxSteps = maxSteps)
) : BrowserPerceptiveAgent(session, maxSteps, config) {
    
    /**
     * Expose classifyError for testing.
     */
    fun testClassifyError(e: Exception, step: Int) = classifyError(e, step)
    
    /**
     * Expose shouldRetryError for testing.
     */
    fun testShouldRetryError(e: Exception) = shouldRetryError(e)
    
    /**
     * Expose calculateRetryDelay for testing.
     */
    fun testCalculateRetryDelay(attempt: Int) = calculateRetryDelay(attempt)
    
    /**
     * Expose cleanupPartialState for testing.
     */
    suspend fun testCleanupPartialState(context: ExecutionContext) = cleanupPartialState(context)
    
    /**
     * Expose performMemoryCleanup for testing.
     */
    suspend fun testPerformMemoryCleanup(context: ExecutionContext) = performMemoryCleanup(context)
    
    /**
     * Access to internal state for verification in tests.
     */
    fun getStepExecutionTimesSize() = stepExecutionTimes.size
    
    /**
     * Access to performance metrics for verification.
     */
    fun getPerformanceMetrics() = performanceMetrics
    
    /**
     * Access to circuit breaker state for verification.
     */
    fun getCircuitBreakerFailures() = circuitBreaker.getFailureCounts()
}
