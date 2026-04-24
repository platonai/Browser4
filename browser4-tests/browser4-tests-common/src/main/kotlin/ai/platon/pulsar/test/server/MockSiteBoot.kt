package ai.platon.pulsar.test.server

import java.time.Duration

/**
 * Simple standalone launcher with a main() entrypoint so you can start the mock site easily.
 *
 * Usage examples:
 *   ./mvnw -pl browser4-tests/browser4-tests-common -am spring-boot:run -Dspring-boot.run.mainClass=ai.platon.pulsar.test.server.MockSiteBoot
 *
 * System properties / environment variables:
 *   -Dmock.site.port=9090 (or env MOCK_SITE_PORT)  Desired port (default 8080, 0 = random)
 *   -Dmock.site.waitSec=8  Wait seconds for readiness (default 6)
 */
fun main(args: Array<String>) {
    val port = (System.getProperty("mock.site.port")
        ?: System.getenv("MOCK_SITE_PORT")
        ?: "18080").toIntOrNull() ?: 18080
    val waitSeconds = (System.getProperty("mock.site.waitSec")
        ?: System.getenv("MOCK_SITE_WAIT_SEC")
        ?: "6").toLongOrNull() ?: 6L

    println("[MockSiteBoot] Starting mock site on port=$port (0 means random)")
    MockSiteLauncher.start(port = port)
    val ok = MockSiteLauncher.awaitReady(Duration.ofSeconds(waitSeconds))
    val actual = MockSiteLauncher.port()
    if (ok) {
        println("[MockSiteBoot] Mock site is ready at http://localhost:$actual/")
    } else {
        println("[MockSiteBoot][WARN] Mock site not reported ready within ${waitSeconds}s (last port=$actual)")
    }

    // Keep JVM alive if launched without a surrounding process supervisor.
    // If running via spring-boot:run, Spring keeps it alive; if executed directly this ensures availability.
    if (args.contains("--block")) {
        println("[MockSiteBoot] Blocking mode: Press Ctrl+C to exit.")
        while (true) Thread.sleep(60_000)
    }
}
