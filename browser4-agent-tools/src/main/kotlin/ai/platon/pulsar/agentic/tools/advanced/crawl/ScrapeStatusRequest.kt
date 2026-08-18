package ai.platon.pulsar.agentic.tools.advanced.crawl

/**
 * Request to fetch the status/result of a previously submitted scrape task.
 *
 * Lives next to the other scrape models so the swarm SPI (and plugins
 * implementing it) do not need to depend on the REST module.
 */
data class ScrapeStatusRequest(
    val id: String,
)
