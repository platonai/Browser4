package ai.platon.pulsar.rest.config

import ai.platon.pulsar.common.getLogger
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Publishes this backend's own URL as the `browser4.server.url` system property
 * (design M0). The CLI engine's [ai.platon.pulsar.agentic.cli.CliProcessManager]
 * injects it as `BROWSER4_CLI_SERVER` into browser4-cli subprocesses so they
 * always target THIS backend and can never auto-start/restart a server.
 *
 * The port is read from Spring's `server.port` (the CLI daemon always launches
 * with `--server.port=<port>`), falling back to 8182. An explicit
 * `-Dbrowser4.server.url=...` override wins.
 */
@Component
// The app uses spring.main.lazy-initialization=true; this bean must be created
// at startup (not on first use) so the URL is published before any agent task.
@Lazy(false)
class Browser4ServerUrlPublisher(
    private val environment: Environment,
) {
    @PostConstruct
    fun publish() {
        if (System.getProperty(BROWSER4_SERVER_URL_KEY) != null) return
        val port = environment.getProperty("server.port", "8182")
        val host = System.getenv("BROWSER4_SERVER_HOST") ?: "localhost"
        val url = "http://$host:$port"
        System.setProperty(BROWSER4_SERVER_URL_KEY, url)
        logger.info("Published {}={}", BROWSER4_SERVER_URL_KEY, url)
    }

    companion object {
        const val BROWSER4_SERVER_URL_KEY = "browser4.server.url"
        private val logger = getLogger(Browser4ServerUrlPublisher::class)
    }
}
