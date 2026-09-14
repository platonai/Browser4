package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.observability.ToolMetrics
import ai.platon.pulsar.common.getLogger
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Binds the MCP tool metrics to the application's own Micrometer registry
 * (requirement 8).
 *
 * `ToolMetrics` lives in `browser4-agentic`, which cannot see Spring's
 * registry: left alone it collects into `MetricsConfig.registry`, a private
 * registry nobody scrapes. This component re-points it at the `MeterRegistry`
 * Spring Boot auto-configures, so `tool.calls.total`,
 * `tool.errors.by.code`, `tool.execution.duration.by.name` … show up in
 * `/actuator/metrics` and — when the optional `micrometer-registry-prometheus`
 * artifact is in the bundle — in `/actuator/prometheus` as well.
 *
 * Binding happens on [ApplicationReadyEvent] rather than at bean creation for
 * one reason: `spring.main.lazy-initialization=true` means a `@Configuration`
 * bean nobody injects is never created, so an `@Autowired` method here would
 * silently never run (same reason [StartupWarmer] is event-driven).
 *
 * The rebinding is a no-op when no `MeterRegistry` bean exists (a slim
 * deployment without the metrics starter): the standalone registry keeps
 * working and `GET /api/mcp/stats` still reports the numbers.
 */
@Component
class McpToolMetricsConfiguration(
    /**
     * Resolved lazily: the metrics auto-configuration may run before or after
     * this component, and a missing bean must not fail the context.
     */
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
) {
    private val logger = getLogger(this)

    @EventListener(ApplicationReadyEvent::class)
    fun bindToolMetrics() {
        val meterRegistry = meterRegistryProvider.ifAvailable
        if (meterRegistry == null) {
            logger.info("No Spring MeterRegistry bean available; MCP tool metrics stay on the standalone registry")
            return
        }

        if (ToolMetrics.bindTo(meterRegistry)) {
            logger.info(
                "MCP tool metrics bound to {} — visible via /api/mcp/stats and /actuator/metrics",
                meterRegistry.javaClass.simpleName,
            )
        } else {
            logger.info("MCP tool metrics are already bound to {}", meterRegistry.javaClass.simpleName)
        }
    }
}
