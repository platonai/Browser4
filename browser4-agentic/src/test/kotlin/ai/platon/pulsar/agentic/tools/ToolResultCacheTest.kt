package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The cache is only safe if three things hold: what it caches is genuinely
 * idempotent, a state change drops what it cached, and a caller can always opt out.
 */
@DisplayName("Tool result cache")
class ToolResultCacheTest {

    private var nowMillis = 1_000L

    private fun cache(
        enabled: Boolean = true,
        maxEntries: Int = 100,
        multiplier: Double = 1.0,
    ) = ToolResultCache(
        enabledProvider = { enabled },
        ttlMultiplierProvider = { multiplier },
        maxEntriesProvider = { maxEntries },
        clock = { nowMillis },
    )

    private fun spec(domain: String, method: String) = ToolSpec(domain = domain, method = method, description = "test")

    private fun ToolResultCache.store(spec: ToolSpec, args: Map<String, Any?> = emptyMap(), text: String = "v1") =
        put(spec, SESSION, args, text, success = true)

    // ---------------------------------------------------------------------
    // What is cacheable
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("only idempotent reads are cached by default")
    fun onlyIdempotentReadsAreCacheable() {
        assertEquals(ToolCachePolicy.PAGE_READ_TTL_MS, ToolCachePolicy.ttlMs(spec("tab", "title")))
        assertEquals(ToolCachePolicy.PAGE_READ_TTL_MS, ToolCachePolicy.ttlMs(spec("tab", "currentUrl")))
        assertEquals(ToolCachePolicy.TASK_STATUS_TTL_MS, ToolCachePolicy.ttlMs(spec("crawl", "status")))

        assertNull(ToolCachePolicy.ttlMs(spec("tab", "navigate")), "navigation changes the page")
        assertNull(ToolCachePolicy.ttlMs(spec("tab", "click")))
        assertNull(ToolCachePolicy.ttlMs(spec("crawl", "submit")))
        assertNull(ToolCachePolicy.ttlMs(spec("html_snapshot", "capture")), "megabyte payloads stay out of the cache")
        assertNull(ToolCachePolicy.ttlMs(spec("tab", "screenshot")))
    }

    @Test
    @DisplayName("a spec overrides the derived policy in both directions")
    fun specOverridesThePolicy() {
        assertEquals(
            5_000L,
            ToolCachePolicy.ttlMs(spec("custom", "read").copy(cacheable = true, cacheTtlMs = 5_000)),
            "an unknown tool can declare itself cacheable",
        )
        assertNull(
            ToolCachePolicy.ttlMs(spec("tab", "title").copy(cacheable = false)),
            "a tool can forbid caching",
        )
        assertNull(
            ToolCachePolicy.ttlMs(spec("tab", "title").copy(cacheTtlMs = 0)),
            "a zero TTL means never cache",
        )
        assertEquals(
            2_000L,
            ToolCachePolicy.ttlMs(spec("tab", "title"), multiplier = 2.0),
            "the deployment can stretch every TTL",
        )
    }

    // ---------------------------------------------------------------------
    // Serving, expiry, keys
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a repeated read is served from the cache, with its age")
    fun repeatedReadsAreServed() {
        val cache = cache()
        val title = spec("tab", "title")
        val miss = cache.get(title, SESSION, emptyMap())
        assertNull(miss, "nothing cached yet")

        cache.store(title, text = "Example Domain")

        val hit = cache.get(title, SESSION, emptyMap())
        assertNotNull(hit)
        assertEquals("Example Domain", hit!!.text)
        assertEquals(0L, hit.ageMs)
        assertEquals(1L, cache.stats().hits)
        assertEquals(1L, cache.stats().misses)
    }

    @Test
    @DisplayName("an entry expires after its TTL")
    fun entriesExpire() {
        val cache = cache()
        val title = spec("tab", "title")
        cache.store(title)

        nowMillis += ToolCachePolicy.PAGE_READ_TTL_MS - 1
        assertNotNull(cache.get(title, SESSION, emptyMap()), "still inside the TTL")

        nowMillis += 2
        assertNull(cache.get(title, SESSION, emptyMap()), "expired entries are not served")
        assertEquals(0, cache.stats().entries, "and are dropped")
    }

    @Test
    @DisplayName("the key covers the session, the tool and the arguments")
    fun keyCoversSessionToolAndArguments() {
        val cache = cache()
        val title = spec("tab", "title")
        cache.store(title, mapOf("selector" to "#a"), text = "A")

        assertNotNull(cache.get(title, SESSION, mapOf("selector" to "#a")), "the same question hits")
        assertNull(cache.get(title, SESSION, mapOf("selector" to "#b")), "a different question misses")
        assertNull(cache.get(title, "other-session", mapOf("selector" to "#a")), "another session misses")
        assertNull(cache.get(spec("tab", "getText"), SESSION, mapOf("selector" to "#a")), "another tool misses")
    }

    @Test
    @DisplayName("equivalent argument spellings share one entry, and sessionId never splits it")
    fun argumentsAreCanonicalised() {
        val cache = cache()
        val spec = spec("tab", "getText")
        cache.store(spec, mapOf("a" to 1, "b" to mapOf("y" to 2, "x" to 1)), text = "v")

        val hit = cache.get(spec, SESSION, mapOf("b" to mapOf("x" to 1, "y" to 2), "a" to 1))
        assertNotNull(hit, "map iteration order must not create a second entry")

        val withSession = cache.get(spec, SESSION, mapOf("a" to 1, "b" to mapOf("y" to 2, "x" to 1), "sessionId" to SESSION))
        assertNotNull(withSession, "the transport argument is not part of the question")
    }

    @Test
    @DisplayName("a failed call is never cached")
    fun failuresAreNotCached() {
        val cache = cache()
        val title = spec("tab", "title")
        cache.put(title, SESSION, emptyMap(), "boom", success = false)

        assertNull(cache.get(title, SESSION, emptyMap()))
    }

    // ---------------------------------------------------------------------
    // Invalidation
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a state-changing call drops the session's entries")
    fun stateChangesInvalidateTheSession() {
        val cache = cache()
        val title = spec("tab", "title")
        cache.store(title, text = "before")
        assertNotNull(cache.get(title, SESSION, emptyMap()))

        // A click may have changed what every read reports.
        cache.put(spec("tab", "click"), SESSION, mapOf("selector" to "#a"), "clicked", success = true)

        assertNull(cache.get(title, SESSION, emptyMap()), "the cached read is gone")
        assertTrue(cache.stats().entries == 0)
    }

    @Test
    @DisplayName("a failed state-changing call still invalidates")
    fun failedStateChangesInvalidate() {
        val cache = cache()
        val title = spec("tab", "title")
        cache.store(title)

        cache.put(spec("tab", "navigate"), SESSION, mapOf("url" to "https://x"), "nope", success = false)

        assertNull(cache.get(title, SESSION, emptyMap()), "a half-failed click may still have moved the page")
    }

    @Test
    @DisplayName("invalidating one session leaves the others alone")
    fun invalidationIsPerSession() {
        val cache = cache()
        val title = spec("tab", "title")
        cache.put(title, "a", emptyMap(), "A", success = true)
        cache.put(title, "b", emptyMap(), "B", success = true)

        cache.invalidateSession("a")

        assertNull(cache.get(title, "a", emptyMap()))
        assertEquals("B", cache.get(title, "b", emptyMap())?.text)
    }

    // ---------------------------------------------------------------------
    // Escape hatches and bounds
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("cache:false bypasses the read and refreshes the stored answer")
    fun cacheFalseIsHonoured() {
        val cache = cache()
        val title = spec("tab", "title")
        cache.store(title, text = "cached")

        assertNull(cache.get(title, SESSION, emptyMap(), bypass = true), "the caller asked for fresh data")

        cache.put(title, SESSION, emptyMap(), "fresh", success = true, bypass = true)
        assertEquals(
            "fresh", cache.get(title, SESSION, emptyMap())?.text,
            "a forced refresh is exactly when the cache should learn the new value",
        )
    }

    @Test
    @DisplayName("the deployment can switch caching off entirely")
    fun disabledCacheIsInert() {
        val cache = cache(enabled = false)
        val title = spec("tab", "title")
        cache.put(title, SESSION, emptyMap(), "v", success = true)

        assertNull(cache.get(title, SESSION, emptyMap()))
        assertEquals(0, cache.stats().entries)
        assertFalse(cache.stats().enabled)
    }

    @Test
    @DisplayName("a self-managed tool neither stores nor invalidates")
    fun selfManagedToolsAreLeftAlone() {
        val cache = cache()
        val title = spec("tab", "title")
        cache.store(title, text = "before")

        // batch.run decides per step: invalidating here would wipe the reads its own
        // read-only steps just refreshed.
        val batch = spec("batch", "run")
        assertNull(cache.get(batch, SESSION, emptyMap()), "the batch does its own lookups")
        cache.put(batch, SESSION, emptyMap(), "batch result", success = true)

        assertEquals("before", cache.get(title, SESSION, emptyMap())?.text, "the session's reads survive")
        assertEquals(0, cache.stats().entries.let { it - 1 }, "only the read is cached")
    }

    @Test
    @DisplayName("the cache stays bounded and reports evictions")
    fun cacheIsBounded() {
        val cache = cache(maxEntries = 3)
        (1..5).forEach { index ->
            nowMillis += 1
            cache.store(spec("tab", "getText"), mapOf("selector" to "#$index"), text = "v$index")
        }

        assertTrue(cache.stats().entries <= 3, "saw ${cache.stats().entries} entries")
        assertEquals("v5", cache.get(spec("tab", "getText"), SESSION, mapOf("selector" to "#5"))?.text)
    }

    private companion object {
        private const val SESSION = "session-1"
    }
}
