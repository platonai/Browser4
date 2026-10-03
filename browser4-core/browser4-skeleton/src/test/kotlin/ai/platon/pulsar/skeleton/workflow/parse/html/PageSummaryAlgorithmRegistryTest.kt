package ai.platon.pulsar.skeleton.workflow.parse.html

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PageSummaryAlgorithmRegistry")
class PageSummaryAlgorithmRegistryTest {

    private class FakeAlgorithm(
        override val id: String,
        override val displayName: String = "Fake: $id",
        override val description: String = "fake algorithm",
    ) : PageSummaryAlgorithm {
        override fun generate(input: PageSummaryInput): String = "fake:$id"
    }

    private val registry = PageSummaryAlgorithmRegistry.instance

    @BeforeEach
    fun reset() {
        registry.clear()
        registry.register(WpsiPageSummaryAlgorithm)
    }

    @AfterEach
    fun restoreDefaults() {
        registry.clear()
        registry.register(WpsiPageSummaryAlgorithm)
    }

    @Test
    @DisplayName("built-in wpsi algorithm is registered by default")
    fun defaultWpsiPresent() {
        assertEquals(1, registry.size())
        assertSame(WpsiPageSummaryAlgorithm, registry.get(PageSummaryAlgorithmRegistry.DEFAULT_ID))
        assertTrue(registry.availableIds().contains("wpsi"))
    }

    @Test
    @DisplayName("resolve with null or blank id falls back to the default algorithm")
    fun resolveBlankFallsBackToDefault() {
        assertSame(WpsiPageSummaryAlgorithm, registry.resolve(null))
        assertSame(WpsiPageSummaryAlgorithm, registry.resolve(""))
        assertSame(WpsiPageSummaryAlgorithm, registry.resolve("   "))
    }

    @Test
    @DisplayName("resolve returns the registered algorithm for a known id")
    fun resolveKnownId() {
        val custom = FakeAlgorithm("my-summary")
        assertTrue(registry.register(custom))
        assertSame(custom, registry.resolve("my-summary"))
    }

    @Test
    @DisplayName("resolve returns null for an unknown id")
    fun resolveUnknownId() {
        assertNull(registry.resolve("does-not-exist"))
    }

    @Test
    @DisplayName("register adds the algorithm to list and availableIds")
    fun registerAndList() {
        registry.register(FakeAlgorithm("zebra"))
        registry.register(FakeAlgorithm("alpha"))

        assertEquals(listOf("alpha", "wpsi", "zebra"), registry.availableIds())
        assertEquals(listOf("alpha", "wpsi", "zebra"), registry.list().map { it.id })
    }

    @Test
    @DisplayName("duplicate id is rejected without replacing the first registration")
    fun duplicateIdKeepsFirst() {
        val first = FakeAlgorithm("dup", displayName = "First")
        val second = FakeAlgorithm("dup", displayName = "Second")

        assertTrue(registry.register(first))
        assertFalse(registry.register(second))
        assertEquals("First", registry.get("dup")?.displayName)
        assertEquals(2, registry.size())
    }

    @Test
    @DisplayName("re-registering wpsi does not replace the built-in instance")
    fun cannotOverrideBuiltInId() {
        val shadow = FakeAlgorithm("wpsi")
        assertFalse(registry.register(shadow))
        assertSame(WpsiPageSummaryAlgorithm, registry.get("wpsi"))
    }

    @Test
    @DisplayName("invalid ids are rejected")
    fun invalidIds() {
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(FakeAlgorithm(""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(FakeAlgorithm("UPPER"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(FakeAlgorithm("with space"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(FakeAlgorithm("-leading-hyphen"))
        }
    }

    @Test
    @DisplayName("clear removes every algorithm")
    fun clearRemovesAll() {
        registry.register(FakeAlgorithm("temp"))
        registry.clear()
        assertEquals(0, registry.size())
        assertNull(registry.get("wpsi"))
    }

    @Test
    @DisplayName("setDefaultId redirects resolve(null) to the configured algorithm")
    fun setDefaultIdOverridesResolution() {
        val custom = FakeAlgorithm("my-default")
        registry.register(custom)

        registry.setDefaultId("my-default")

        assertEquals("my-default", registry.defaultId())
        assertSame(custom, registry.resolve(null))
        assertSame(custom, registry.resolve("  "))
        // Explicit id still wins over the configured default.
        assertSame(WpsiPageSummaryAlgorithm, registry.resolve("wpsi"))
    }

    @Test
    @DisplayName("setDefaultId with an unregistered id fails fast on resolve(null)")
    fun setDefaultIdToUnknownIdFailsFast() {
        registry.setDefaultId("not-installed")

        assertNull(registry.resolve(null))
        assertSame(WpsiPageSummaryAlgorithm, registry.resolve("wpsi"))
    }

    @Test
    @DisplayName("setDefaultId rejects invalid id syntax and keeps the previous default")
    fun setDefaultIdRejectsInvalidSyntax() {
        assertThrows(IllegalArgumentException::class.java) {
            registry.setDefaultId("UPPER")
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.setDefaultId("")
        }
        assertEquals("wpsi", registry.defaultId())
    }

    @Test
    @DisplayName("clear also resets the default id override")
    fun clearResetsDefaultOverride() {
        registry.setDefaultId("not-installed")
        registry.clear()
        registry.register(WpsiPageSummaryAlgorithm)

        assertEquals("wpsi", registry.defaultId())
        assertSame(WpsiPageSummaryAlgorithm, registry.resolve(null))
    }
}
