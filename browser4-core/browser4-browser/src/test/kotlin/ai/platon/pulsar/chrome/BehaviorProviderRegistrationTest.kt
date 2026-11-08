package ai.platon.pulsar.chrome

import ai.platon.browser4.behavior.BrowserBehavior
import ai.platon.browser4.behavior.humanization.DelaySource
import ai.platon.browser4.behavior.humanization.PointerMoveRequest
import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The behavior modules are runtime dependencies of the driver, so this module's test classpath
 * sees what a product build sees: the community default and the optional `ghostcursor` provider
 * side by side. These tests go through the real [java.util.ServiceLoader] registry — the
 * provider modules only assert on their own class, which cannot catch a wrong `META-INF/services`
 * entry or a lost tie-break.
 */
@DisplayName("Behavior provider registration")
@Tag("Unit")
@Tag("Fast")
class BehaviorProviderRegistrationTest {
    private val humanizerKey = B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER

    private fun configWith(key: String, value: String): ImmutableConfig =
        MutableConfig(loadDefaults = true).apply { set(key, value) }

    @BeforeEach
    fun setUp() {
        BrowserBehavior.reset()
    }

    @AfterEach
    fun tearDown() {
        BrowserBehavior.reset()
    }

    @Test
    @DisplayName("both humanizers are discovered from the classpath")
    fun bothHumanizersAreDiscovered() {
        val ids = BrowserBehavior.installedHumanizers().map { it.id }
        assertTrue("default" in ids, "installed humanizers: $ids")
        assertTrue("ghostcursor" in ids, "installed humanizers: $ids")
    }

    @Test
    @DisplayName("the community default wins the tie until the provider is pinned")
    fun defaultWinsTheTieUntilPinned() {
        // ghostcursor ships with priority 0 like the default, and a tie is broken by id
        // ascending, so merely installing the module must not change the active policy.
        assertEquals("default", BrowserBehavior.installedHumanizers().first().id)
        assertEquals("default", BrowserBehavior.humanizer().id)
        assertEquals("ghostcursor", BrowserBehavior.humanizer(configWith(humanizerKey, "ghostcursor")).id)
        // The configured id is documented as case-insensitive.
        assertEquals("ghostcursor", BrowserBehavior.humanizer(configWith(humanizerKey, "GhostCursor")).id)
    }

    @Test
    @DisplayName("an unknown provider id falls back to the installed default")
    fun unknownProviderFallsBackToTheDefault() {
        assertEquals("default", BrowserBehavior.humanizer(configWith(humanizerKey, "not-installed")).id)
    }

    @Test
    @DisplayName("the pinned provider plans waypoints the default does not")
    fun pinnedProviderPlansWaypoints() {
        val request = PointerMoveRequest(fromX = 0.0, fromY = 0.0, toX = 800.0, toY = 400.0, targetWidth = 120.0)
        val delays = DelaySource { 0L }

        val ghost = BrowserBehavior.humanizer(configWith(humanizerKey, "ghostcursor")).pointerMove(request, delays)
        val default = BrowserBehavior.humanizer().pointerMove(request, delays)

        assertTrue(ghost.waypoints.isNotEmpty(), "ghostcursor must plan an explicit trajectory")
        assertEquals(800.0, ghost.waypoints.last().x, 1e-9)
        // The default keeps the historical interpolation path, which is what makes the driver
        // change a no-op for every deployment that does not pin the provider.
        assertTrue(default.waypoints.isEmpty(), "the community default must keep the interpolation path")
    }
}
