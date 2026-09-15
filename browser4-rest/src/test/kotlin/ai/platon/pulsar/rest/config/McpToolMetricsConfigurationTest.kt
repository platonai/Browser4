package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.observability.ToolMetrics
import ai.platon.pulsar.rest.api.service.CrawlService
import ai.platon.pulsar.rest.session.PulsarSessionManager
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import java.util.UUID

/**
 * Tool metrics must land in the registry the application actually exports
 * (requirement 8) — the agentic module cannot see Spring's registry on its own.
 */
@Tag("observability")
@DisplayName("MCP tool metrics binding")
class McpToolMetricsConfigurationTest {

    private val tool = "test.binding.${UUID.randomUUID().toString().take(8)}"

    @AfterEach
    fun restoreStandaloneBinding() {
        // Never leave the process bound to a registry that a closed Spring
        // context owns: later tests would record into a dead registry.
        ToolMetrics.bindTo(SimpleMeterRegistry())
    }

    @Test
    @DisplayName("with a Spring registry, every tool meter is written there")
    fun metricsFollowTheSpringRegistry() {
        AnnotationConfigApplicationContext(WebMetrics::class.java).use { context ->
            configuration(context).bindToolMetrics()
            val springRegistry = context.getBean(MeterRegistry::class.java)

            assertSame(springRegistry, ToolMetrics.currentRegistry(), "the binding must move to Spring's registry")

            ToolMetrics.recordToolCall(tool, success = true, durationMs = 5, errorCode = null)

            assertEquals(
                1.0, springRegistry.find("tool.calls.total").counter()?.count(),
                "the call must be counted in Spring's registry",
            )
            assertNotNull(
                springRegistry.find("tool.active.calls").gauge(),
                "the in-flight gauge must be re-registered on the new registry",
            )
        }
    }

    @Test
    @DisplayName("deployment-only gauges survive the rebinding")
    fun supplierGaugesFollowTheSpringRegistry() {
        AnnotationConfigApplicationContext(WebMetrics::class.java).use { context ->
            // Registered on the standalone registry first: the rebinding has to
            // carry them over, or a dashboard would watch a gauge nobody writes.
            ToolMetrics.registerSessionCountSupplier { 3 }
            ToolMetrics.registerAsyncTaskCountSupplier { 7 }

            configuration(context).bindToolMetrics()
            val springRegistry = context.getBean(MeterRegistry::class.java)

            assertEquals(
                3.0, springRegistry.find("session.active").gauge()?.value(),
                "session.active must be re-registered on Spring's registry",
            )
            assertEquals(
                7.0, springRegistry.find("async.queue.depth").gauge()?.value(),
                "async.queue.depth must be re-registered on Spring's registry",
            )
        }
    }

    @Test
    @DisplayName("without a registry bean the standalone registry keeps working")
    fun bindingIsSkippedWithoutARegistryBean() {
        AnnotationConfigApplicationContext(NoMetrics::class.java).use { context ->
            val before = ToolMetrics.currentRegistry()

            configuration(context).bindToolMetrics()

            assertSame(before, ToolMetrics.currentRegistry(), "a slim deployment must not lose its metrics")
        }
    }

    /**
     * The configuration as Spring builds it; the optional beans are genuinely
     * absent in these contexts, which is the deployment shape the guards exist for.
     */
    private fun configuration(context: AnnotationConfigApplicationContext) = McpToolMetricsConfiguration(
        context.getBeanProvider(MeterRegistry::class.java),
        context.getBeanProvider(PulsarSessionManager::class.java),
        context.getBeanProvider(CrawlService::class.java),
    )

    @Test
    @DisplayName("the binding runs on ApplicationReadyEvent, not on bean creation")
    fun bindingIsEventDriven() {
        // `spring.main.lazy-initialization=true` means a configuration bean that
        // nobody injects is never created — an @Autowired binding method would
        // silently never run. Pin the annotation so the wiring can't regress.
        val method = McpToolMetricsConfiguration::class.java.getMethod("bindToolMetrics")
        val listener = method.getAnnotation(EventListener::class.java)

        assertNotNull(listener, "bindToolMetrics must be an @EventListener to survive lazy initialization")
        val events = (listener.value + listener.classes).map { it.toString() }
        assertTrue(
            events.any { it == ApplicationReadyEvent::class.java.toString() },
            "the binding must run once the application is ready, saw $events",
        )
    }

    @Configuration
    open class WebMetrics {
        @Bean
        open fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Configuration
    open class NoMetrics
}
