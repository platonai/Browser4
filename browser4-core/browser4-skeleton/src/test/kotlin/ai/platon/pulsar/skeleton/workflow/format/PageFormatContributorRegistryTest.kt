package ai.platon.pulsar.skeleton.workflow.format

import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DisplayName("PageFormatContributorRegistry")
class PageFormatContributorRegistryTest {

    private class FakeContributor(
        override val id: String,
        override val displayName: String = "Fake: $id",
        override val description: String = "fake format",
        override val requires: Set<FormatInput> = setOf(FormatInput.RAW_HTML),
        private val available: Boolean = true,
        private val reason: String? = null,
    ) : PageFormatContributor {
        override fun isAvailable(): Boolean = available
        override fun unavailableReason(): String? = reason
        override suspend fun contribute(ctx: FormatContext): Any? = "fake:$id"
    }

    private val registry = PageFormatContributorRegistry.instance

    @BeforeEach
    fun reset() {
        registry.clear()
    }

    @AfterEach
    fun restore() {
        registry.clear()
    }

    @Test
    @DisplayName("a stock install has no contributors registered")
    fun emptyByDefault() {
        assertEquals(0, registry.size())
        assertTrue(registry.availableIds().isEmpty())
        assertNull(registry.get("branding"))
        assertFalse(registry.contains("branding"))
    }

    @Test
    @DisplayName("register, get and contains resolve a claimed format id")
    fun registerAndResolve() {
        val branding = FakeContributor("branding")
        assertTrue(registry.register(branding))

        assertSame(branding, registry.get("branding"))
        assertTrue(registry.contains("branding"))
        assertEquals(1, registry.size())
        assertNull(registry.get("product"))
    }

    @Test
    @DisplayName("list and availableIds are ordered by id for stable listings")
    fun stableOrdering() {
        registry.register(FakeContributor("menu"))
        registry.register(FakeContributor("branding"))
        registry.register(FakeContributor("product"))

        assertEquals(listOf("branding", "menu", "product"), registry.availableIds())
        assertEquals(listOf("branding", "menu", "product"), registry.list().map { it.id })
    }

    @Test
    @DisplayName("an id this build does not offer a contributor is refused at registration")
    fun unknownContributedIdIsRefused() {
        // The engine offers a contributor only the ids it knows (`PageFormats.CONTRIBUTED`),
        // so a novel id would register successfully and then never be called — a plugin
        // author would have no way to tell why. Refusing here names the accepted ids.
        val error = assertThrows(IllegalArgumentException::class.java) {
            registry.register(FakeContributor("myFormat"))
        }
        assertTrue(error.message!!.contains("myFormat"), error.message)
        assertTrue(error.message!!.contains("branding"), error.message)
        assertEquals(0, registry.size())
    }

    @Test
    @DisplayName("a contributor writing a field it does not own is refused at registration")
    fun unwritableOutputFieldIsRefused() {
        // `outputField` cannot be changed by the engine, and an id may legally differ
        // from its field — so the check belongs on the field, not on the id.
        val error = assertThrows(IllegalArgumentException::class.java) {
            registry.register(object : PageFormatContributor {
                override val id = "branding"
                override val displayName = "Branding"
                override val description = "writes somewhere it should not"
                override val outputField = "summary"
                override val requires: Set<FormatInput> = emptySet()
                override suspend fun contribute(ctx: FormatContext): Any? = "x"
            })
        }
        assertTrue(error.message!!.contains("summary"), error.message)
        assertEquals(0, registry.size())
    }

    @Test
    @DisplayName("a contributor may write a sibling contributed field")
    fun aContributedFieldOtherThanTheIdIsAllowed() {
        // The rule is "one of the four contributed fields", not "the field named after
        // the id" — that is what `outputField` exists for.
        val accepted = registry.register(object : PageFormatContributor {
            override val id = "product"
            override val displayName = "Product"
            override val description = "reports through branding"
            override val outputField = "branding"
            override val requires: Set<FormatInput> = emptySet()
            override suspend fun contribute(ctx: FormatContext): Any? = "x"
        })

        assertTrue(accepted)
        assertEquals("branding", registry.get("product")?.outputField)
    }

    @Test
    @DisplayName("an id that does not match the pattern is refused")
    fun invalidId() {
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeContributor("")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeContributor("Branding")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeContributor("brand-ing")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeContributor("1branding")) }
        assertEquals(0, registry.size())
    }

    @Test
    @DisplayName("a core or deprecated format id cannot be contributed")
    fun reservedIds() {
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeContributor("markdown")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeContributor("screenshot")) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(FakeContributor("query")) }
        assertEquals(0, registry.size())
    }

    @Test
    @DisplayName("registration is first wins: a duplicate id keeps the original")
    fun duplicateKeepsFirst() {
        val first = FakeContributor("branding", displayName = "first")
        val second = FakeContributor("branding", displayName = "second")

        assertTrue(registry.register(first))
        assertFalse(registry.register(second))
        assertSame(first, registry.get("branding"))
        assertEquals(1, registry.size())
    }

    @Test
    @DisplayName("unavailable reports contributors that cannot run right now")
    fun unavailableListing() {
        registry.register(FakeContributor("branding"))
        registry.register(FakeContributor("product", available = false, reason = "service not configured"))
        registry.register(FakeContributor("menu", available = false, reason = "menuBeta flag missing"))

        assertEquals(listOf("menu", "product"), registry.unavailable().map { it.id })
        assertEquals("service not configured", registry.get("product")!!.unavailableReason())
        assertNull(registry.get("branding")!!.unavailableReason())
    }

    @Test
    @DisplayName("clear unregisters everything")
    fun clearRemovesAll() {
        registry.register(FakeContributor("branding"))
        registry.clear()
        assertEquals(0, registry.size())
        assertNull(registry.get("branding"))
    }

    @Test
    @DisplayName("contribute receives the shared capture context")
    fun contributeReceivesContext() = runBlocking {
        registry.register(FakeContributor("branding"))
        val ctx = FormatContext(
            snapshotKey = "s1",
            url = "https://example.com",
            rawHtml = "<html></html>",
            options = mapOf("mode" to "fast"),
        )

        assertEquals("fake:branding", registry.get("branding")!!.contribute(ctx))
        assertEquals("s1", ctx.snapshotKey)
        assertEquals("fast", ctx.options["mode"])
    }

    @Test
    @DisplayName("concurrent registration of one id succeeds exactly once")
    fun concurrentRegistration() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = (1..8).map { index ->
                pool.submit(Callable {
                    start.await()
                    registry.register(FakeContributor("branding", displayName = "contributor-$index"))
                })
            }
            start.countDown()

            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it }, "exactly one registration must win")
            assertEquals(1, registry.size())
        } finally {
            pool.shutdownNow()
        }
    }
}
