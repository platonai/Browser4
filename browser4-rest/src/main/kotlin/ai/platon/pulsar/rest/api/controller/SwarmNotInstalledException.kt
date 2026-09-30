package ai.platon.pulsar.rest.api.controller

/**
 * Thrown by the thin swarm REST facade when no [ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacade]
 * is registered — i.e. the `browser4-swarm` plugin is not installed.
 */
class SwarmNotInstalledException(
    message: String = "Swarm backend is not installed. Install the browser4-swarm plugin and restart the server.",
) : RuntimeException(message)
