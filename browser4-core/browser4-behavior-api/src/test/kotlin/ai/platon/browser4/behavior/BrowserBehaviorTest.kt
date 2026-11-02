package ai.platon.browser4.behavior

import ai.platon.browser4.common.B4Constants
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BrowserBehavior] — the registry that picks the active stealth provider and
 * interaction humanizer.
 *
 * The providers are the ones in `TestBehaviorProviders`, registered through this module's test
 * classpath (`src/test/resources/META-INF/services`). They are what makes the selection rules
 * testable without the real implementation module: priority ordering, explicit id selection,
 * case-insensitive ids and the unknown-id fallback are all observable through the public
 * registry API.
 */
@DisplayName("BrowserBehavior")
class BrowserBehaviorTest {

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
    @DisplayName("the highest-priority stealth provider wins when nothing is configured")
    fun highestPriorityStealthProviderWinsByDefault() {
        assertEquals(HighPriorityTestStealthProvider.ID, BrowserBehavior.stealth().id)
        assertEquals(HighPriorityTestStealthProvider.ID, BrowserBehavior.stealth(ImmutableConfig()).id)
    }

    @Test
    @DisplayName("installed stealth providers are listed in resolution order")
    fun installedStealthProvidersAreOrderedByPriority() {
        val ids = BrowserBehavior.installedStealthProviders().map { it.id }

        assertEquals(
            listOf(HighPriorityTestStealthProvider.ID, LowPriorityTestStealthProvider.ID),
            ids,
            "highest priority first, and both test providers must be discovered",
        )
    }

    @Test
    @DisplayName("a configured id selects that provider even when another has a higher priority")
    fun configuredIdSelectsThePinnedProvider() {
        val config = configWith(B4Constants.BEHAVIOR_STEALTH_PROVIDER, LowPriorityTestStealthProvider.ID)

        assertEquals(LowPriorityTestStealthProvider.ID, BrowserBehavior.stealth(config).id)
    }

    @Test
    @DisplayName("a configured id is matched case-insensitively")
    fun configuredIdIsCaseInsensitive() {
        val config = configWith(
            B4Constants.BEHAVIOR_STEALTH_PROVIDER,
            LowPriorityTestStealthProvider.ID.uppercase(),
        )

        assertEquals(LowPriorityTestStealthProvider.ID, BrowserBehavior.stealth(config).id)
    }

    @Test
    @DisplayName("an unknown configured id falls back to the highest-priority provider")
    fun unknownConfiguredIdFallsBackToTheHighestPriorityProvider() {
        val config = configWith(B4Constants.BEHAVIOR_STEALTH_PROVIDER, "no-such-provider")

        assertEquals(HighPriorityTestStealthProvider.ID, BrowserBehavior.stealth(config).id)
    }

    @Test
    @DisplayName("a blank configured id means 'highest priority'")
    fun blankConfiguredIdMeansHighestPriority() {
        val config = configWith(B4Constants.BEHAVIOR_STEALTH_PROVIDER, "   ")

        assertEquals(HighPriorityTestStealthProvider.ID, BrowserBehavior.stealth(config).id)
    }

    @Test
    @DisplayName("humanizer selection follows the same rules")
    fun humanizerSelectionFollowsTheSameRules() {
        assertEquals(HighPriorityTestHumanizer.ID, BrowserBehavior.humanizer().id)

        val pinned = configWith(B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER, LowPriorityTestHumanizer.ID)
        assertEquals(LowPriorityTestHumanizer.ID, BrowserBehavior.humanizer(pinned).id)

        val unknown = configWith(B4Constants.BEHAVIOR_HUMANIZATION_PROVIDER, "no-such-humanizer")
        assertEquals(HighPriorityTestHumanizer.ID, BrowserBehavior.humanizer(unknown).id)
    }

    @Test
    @DisplayName("installed humanizers are listed in resolution order")
    fun installedHumanizersAreOrderedByPriority() {
        val ids = BrowserBehavior.installedHumanizers().map { it.id }

        assertEquals(listOf(HighPriorityTestHumanizer.ID, LowPriorityTestHumanizer.ID), ids)
        assertTrue(ids.isNotEmpty())
    }
}
