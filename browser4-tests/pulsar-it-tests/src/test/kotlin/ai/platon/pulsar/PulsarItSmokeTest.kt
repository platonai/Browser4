package ai.platon.pulsar

import ai.platon.pulsar.test.server.MockSiteLauncher
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Smoke test for the pulsar-it-tests module.
 *
 * The full integration-test suite has been migrated into the base library
 * (browser4-core and pulsar-tests-common). This module is kept as a thin
 * placeholder so existing build wiring and CI references keep working during
 * the migration; the single smoke test below verifies that the shared
 * mock-site test infrastructure still resolves on the test classpath.
 */
class PulsarItSmokeTest {

    @Test
    @DisplayName("Smoke: shared mock-site infrastructure is on the test classpath")
    fun smokeSharedMockSiteInfrastructureIsOnClasspath() {
        assertFalse(MockSiteLauncher.isRunning())
    }
}
