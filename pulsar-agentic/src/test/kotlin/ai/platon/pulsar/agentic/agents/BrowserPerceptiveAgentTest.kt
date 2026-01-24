package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.agentic.agents.BrowserPerceptiveAgentTestUtils.createTestAgent
import ai.platon.pulsar.agentic.agents.BrowserPerceptiveAgentTestUtils.createTestConfig
import ai.platon.pulsar.agentic.agents.BrowserPerceptiveAgentTestUtils.createTestContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for BrowserPerceptiveAgent core functionality.
 * 
 * These tests focus on the call chain methods and internal logic
 * that can be tested without requiring a real browser.
 */
class BrowserPerceptiveAgentTest {
    
    private var agent: TestableBrowserPerceptiveAgent? = null
    
    @AfterEach
    fun cleanup() {
        agent?.close()
        agent = null
    }
    
    @Test
    fun testAgentCanBeCreatedWithMinimalDependencies() {
        agent = createTestAgent()
        assertFalse(agent!!.isClosed)
    }
    
    @Test
    fun testAgentCloseSetsFlagAndIsClosed() {
        agent = createTestAgent()
        assertFalse(agent!!.isClosed)
        
        agent!!.close()
        assertTrue(agent!!.isClosed)
        
        // Calling close again should be idempotent
        agent!!.close()
        assertTrue(agent!!.isClosed)
    }
    
    @Test
    fun testCalculateRetryDelayFollowsExponentialBackoff() {
        agent = createTestAgent(
            createTestConfig(
                maxRetries = 5
            )
        )
        
        val delay0 = agent!!.testCalculateRetryDelay(0)
        val delay1 = agent!!.testCalculateRetryDelay(1)
        val delay2 = agent!!.testCalculateRetryDelay(2)
        
        // Each delay should be greater than the previous (exponential backoff)
        assertTrue(delay1 > delay0)
        assertTrue(delay2 > delay1)
    }
    
    @Test
    fun testShouldRetryErrorForTransientErrors() {
        agent = createTestAgent()
        
        // Transient errors should be retryable
        val timeoutException = TimeoutCancellationException("timeout")
        assertTrue(agent!!.testShouldRetryError(timeoutException))
    }
    
    @Test
    fun testMemoryCleanupReducesStepExecutionTimesSize() = runBlocking {
        val config = createTestConfig(
            maxSteps = 10,
            memoryCleanupIntervalSteps = 5,
            maxHistorySize = 10
        )
        agent = createTestAgent(config)
        
        val context = createTestContext()
        
        // Simulate adding many step execution times
        repeat(250) { step ->
            agent!!.stepExecutionTimes[step] = 100L
        }
        
        assertTrue(agent!!.getStepExecutionTimesSize() > 200)
        
        // Trigger cleanup
        agent!!.testPerformMemoryCleanup(context)
        
        // After cleanup, size should be reduced
        assertTrue(agent!!.getStepExecutionTimesSize() <= 100)
    }
    
    @Test
    fun testCleanupPartialStateResetsCircuitBreaker() = runBlocking {
        agent = createTestAgent()
        val context = createTestContext()
        
        // Record some failures to trigger circuit breaker
        repeat(3) {
            runCatching { 
                agent!!.circuitBreaker.recordFailure(ai.platon.pulsar.agentic.inference.detail.CircuitBreaker.FailureType.LLM_FAILURE)
            }
        }
        
        // Verify there are failures recorded
        val failuresBeforeCleanup = agent!!.getCircuitBreakerFailures()
        assertTrue(failuresBeforeCleanup.values.any { it > 0 })
        
        // Cleanup should reset the circuit breaker
        agent!!.testCleanupPartialState(context)
        
        val failuresAfterCleanup = agent!!.getCircuitBreakerFailures()
        assertTrue(failuresAfterCleanup.values.all { it == 0 })
    }
    
    @Test
    fun testPerformanceMetricsInitializedToZero() {
        agent = createTestAgent(
            createTestConfig(enablePerformanceMetrics = true)
        )
        
        val metrics = agent!!.getPerformanceMetrics()
        assertEquals(0, metrics.totalSteps)
        assertEquals(0, metrics.successfulActions)
        assertEquals(0, metrics.failedActions)
        assertEquals(0.0, metrics.averageActionTimeMs)
    }
}
