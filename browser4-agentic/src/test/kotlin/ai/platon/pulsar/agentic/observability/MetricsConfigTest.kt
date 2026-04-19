package ai.platon.pulsar.agentic.observability

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Unit tests for MetricsConfig.
 */
@Tag("observability")
class MetricsConfigTest {

    @BeforeEach
    fun setUp() {
        // Clean environment before each test
        System.clearProperty("METRICS_ENABLED")
        System.clearProperty("METRICS_PREFIX")
    }

    @AfterEach
    fun tearDown() {
        // No explicit cleanup needed
    }

    @Test
    fun registryIsNotNull() {
        val registry = MetricsConfig.registry
        assertNotNull(registry, "Registry should not be null")
    }

    @Test
    fun canCreateCounter() {
        val registry = MetricsConfig.registry
        val counter = registry.counter("test.counter", "test", "value")

        assertNotNull(counter, "Counter should not be null")
        counter.increment()
        assertTrue(counter.count() > 0, "Counter should have been incremented")
    }

    @Test
    fun canCreateTimer() {
        val registry = MetricsConfig.registry
        val timer = registry.timer("test.timer", "test", "value")

        assertNotNull(timer, "Timer should not be null")
        timer.record(1L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(timer.count() > 0, "Timer should have recorded at least one execution")
    }

    @Test
    fun scrapeReturnsPrometheusFormat() {
        val registry = MetricsConfig.registry

        // Create a test metric
        registry.counter("test.scrape.counter").increment()

        val scrapeData = MetricsConfig.scrape()
        assertNotNull(scrapeData, "Scrape data should not be null")

        // If metrics are enabled and using Prometheus registry, should contain metrics
        if (System.getenv("METRICS_ENABLED") != "false") {
            assertTrue(scrapeData.isNotEmpty(), "Scrape data should not be empty when metrics are enabled")
        }
    }

    @Test
    fun commonTagsAreApplied() {
        val registry = MetricsConfig.registry
        val counter = registry.counter("test.tags.counter")

        counter.increment()

        // Verify the counter has tags
        val id = counter.id
        assertNotNull(id, "Counter ID should not be null")

        // Check that common tags exist
        val tags = id.tags
        assertNotNull(tags, "Tags should not be null")
        assertTrue(tags.isNotEmpty(), "Tags should not be empty")
    }
}
