package ai.platon.pulsar.rest.config

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/**
 * Pre-warms critical beans in a background thread after the server is
 * accepting connections.
 *
 * Without this, [spring.main.lazy-initialization=true] defers ALL bean
 * creation to first request — making the first CLI command (~2s after
 * startup) pay the full creation cost.  By eagerly touching the MCP
 * dispatch chain in the background, the first real request usually
 * finds the beans already warmed.
 *
 * Warm-up runs on a daemon thread so it never blocks shutdown; failures
 * are logged but do not affect the application — the beans will just be
 * created lazily on first use instead.
 */
@Component
class StartupWarmer(
    private val applicationContext: ApplicationContext,
) {
    private val logger = LoggerFactory.getLogger(StartupWarmer::class.java)

    /**
     * Bean names covering the critical path from HTTP request →
     * MCP dispatch → session → browser → fetch.
     *
     * Order matters: beans are touched in sequence so Spring resolves
     * each dependency chain before moving to the next.
     *
     * With [spring.main.lazy-initialization=true] the first request that
     * touches a lazy bean pays the full creation cost.  Warming these
     * eagerly in the background keeps the primary scenario (CLI browser
     * automation via MCP dispatch) fast on the first command after boot.
     *
     * The fetch/protocol layer stays on the warm-up list because scrape
     * requests depend on registered protocol handlers — skipping it would
     * reintroduce "Protocol not found (1600)" errors on cold starts.
     *
     * Crawl ([crawlService]) and swarm ([swarmService]) infrastructure are
     * deliberately NOT warmed: they are non-primary scenarios with heavy
     * initialization (browser pools, worker pools), and their first request
     * can tolerate the lazy-creation cost.  They fall back to plain lazy
     * initialization on first use.
     */
    private val warmupBeanNames = listOf(
        // REST layer
        "mcpToolController",
        // Session infrastructure
        "sessionManager",
        // Agentic context (H2 DB init, etc.)
        "agenticContext",
        // Fetch / protocol layer — ensures protocol handlers are registered
        // before the first scrape request, avoiding race conditions where
        // FetchComponent reports "Protocol not found (1600)".
        "protocolFactory",
        "fetchComponent",
    )

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        CompletableFuture.runAsync {
            logger.info("Background warm-up started ({} beans)", warmupBeanNames.size)
            val start = System.currentTimeMillis()
            var warmed = 0
            for (name in warmupBeanNames) {
                try {
                    // getBean() forces creation of the lazy proxy
                    applicationContext.getBean(name)
                    warmed++
                } catch (e: Exception) {
                    logger.warn(
                        "Warm-up: failed to initialize '{}' — {} (will be created lazily on first use)",
                        name, e.message
                    )
                }
            }
            val elapsed = System.currentTimeMillis() - start
            logger.info(
                "Background warm-up complete: {} / {} beans in {}ms",
                warmed, warmupBeanNames.size, elapsed
            )
        }.exceptionally { ex ->
            logger.error("Background warm-up failed: {}", ex.message, ex)
            null
        }
    }
}
