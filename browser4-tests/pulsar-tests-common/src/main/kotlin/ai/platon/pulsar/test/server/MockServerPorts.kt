package ai.platon.pulsar.test.server

/**
 * Central authority for resolving the mock server port.
 *
 * The port is communicated via the `mock.server.port` system property, which is set by:
 * - [MockEcServerConfiguration] after starting a separate mock server (Pattern B)
 * - Or manually via `-Dmock.server.port=<port>` for externally-managed servers
 */
object MockServerPorts {
    private const val PROP_MOCK = "mock.server.port"

    /** Resolve the port the mock server is actually listening on. */
    fun port(): Int {
        return System.getProperty(PROP_MOCK)?.toIntOrNull()
            ?: error(
                "Mock server port not found — " +
                    "set -D$PROP_MOCK=<port> or ensure the server has started."
            )
    }

    /** Base URL for the mock server, e.g. `http://localhost:51234`. */
    fun baseUrl(): String = "http://localhost:${port()}"
}
