package ai.platon.pulsar.rest.api.controller

/**
 * Thrown by the thin swarm REST facade when no [ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacade]
 * is registered — i.e. the `browser4-swarm` plugin is not installed.
 *
 * The default message carries the *dev-mode* recovery recipe on purpose: the
 * plugin ships in the runtime bundle, and a source-tree bundle silently omitted
 * it whenever the Maven local repository was not the one the bundler scanned
 * (`build-runtime-bundle.ps1` used to look only in `~/.m2/repository`).  A bare
 * "not installed" left the caller with a 503 and no next step, so the message
 * names the plugin, the install command and how to get a bundle that contains it.
 */
class SwarmNotInstalledException(
    message: String = DEFAULT_MESSAGE,
) : RuntimeException(message) {
    companion object {
        /**
         * What a caller can actually do about a 503 from the swarm facade.
         */
        const val DEFAULT_MESSAGE: String =
            "Swarm backend is not installed (the browser4-swarm plugin is not loaded), so every swarm " +
                "endpoint returns 503. Install it and restart the server: " +
                "`mvn install -pl browser4-plugins/browser4-swarm -am -DskipTests`, then rebuild the " +
                "runtime bundle with `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 browser4-cli stop && " +
                "browser4-cli open <url>` (or `./bin/test.ps1 mock-site` from the repo root). " +
                "`browser4-cli doctor status` reports the loaded plugin count."
    }
}
