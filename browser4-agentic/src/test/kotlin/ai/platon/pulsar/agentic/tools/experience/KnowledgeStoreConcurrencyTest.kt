package ai.platon.pulsar.agentic.tools.experience

import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.*

/**
 * Tests for concurrent access to KnowledgeStore.
 *
 * Verifies that per-domain [Mutex] serialization in [KnowledgeStore.saveFacts]
 * prevents data corruption when multiple coroutines write to the same domain
 * simultaneously, while writes to different domains proceed concurrently.
 */
@OptIn(ExperimentalPathApi::class)
@DisplayName("KnowledgeStore — Concurrent Access")
class KnowledgeStoreConcurrencyTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: KnowledgeStore

    @BeforeEach
    fun setUp() {
        store = KnowledgeStore(tempDir)
        store.initializeStore()
    }

    @AfterEach
    fun tearDown() {
        try { tempDir.deleteRecursively() } catch (_: Exception) {}
    }

    @Nested
    @DisplayName("concurrent writes to same domain serialize")
    inner class SameDomainConcurrency {
        @Test
        @DisplayName("multiple coroutines writing to same domain do not corrupt data")
        fun testConcurrentSameDomain() = runBlocking {
            val iterations = 20
            val completed = AtomicInteger(0)
            val errors = mutableListOf<String>()

            val jobs = List(iterations) { i ->
                launch(Dispatchers.Default) {
                    try {
                        val facts = KnowledgeFacts(
                            intent = "buy",
                            domain = "amazon.com",
                            urlPattern = "/dp/*",
                            status = VerificationStatus.HYPOTHESIS,
                            siteFacts = SiteFacts(domain = "amazon.com"),
                        )
                        store.saveFacts(facts)
                        completed.incrementAndGet()
                    } catch (e: Exception) {
                        synchronized(errors) {
                            errors.add("Coroutine $i failed: ${e.message}")
                        }
                    }
                }
            }

            jobs.joinAll()
            assertEquals(0, errors.size, "Expected no errors but got: $errors")
            assertEquals(iterations, completed.get())

            // Verify final state is intact
            val loaded = store.loadFacts("amazon.com", "buy")
            assertNotNull(loaded, "Facts should exist after concurrent writes")
            assertEquals("amazon.com", loaded!!.domain)
        }

        @Test
        @DisplayName("concurrent trace saves to same domain do not lose records")
        fun testConcurrentTraceSaves() = runBlocking {
            val count = 10
            val jobs = List(count) { i ->
                launch(Dispatchers.Default) {
                    store.saveTrace(
                        TraceRecord(
                            intent = "search",
                            domain = "amazon.com",
                            url = "https://amazon.com/s?k=test$i",
                            urlPattern = "/s?k=*",
                            outcome = "success",
                        )
                    )
                    store.updateStats(
                        TraceRecord(
                            intent = "search",
                            domain = "amazon.com",
                            url = "https://amazon.com/s?k=test$i",
                            urlPattern = "/s?k=*",
                            outcome = "success",
                        )
                    )
                }
            }
            jobs.joinAll()

            val traces = store.listTraces(domain = "amazon.com", pageSize = 100)
            assertEquals(count, traces.size, "All $count traces should be saved")

            val stats = store.loadStats("amazon.com", "search")
            assertEquals(count, stats.successes)
        }
    }

    @Nested
    @DisplayName("concurrent writes to different domains run concurrently")
    inner class DifferentDomainConcurrency {
        @Test
        @DisplayName("writes to different domains do not block each other")
        fun testConcurrentDifferentDomains() = runBlocking {
            val domains = listOf("amazon.com", "ebay.com", "walmart.com", "etsy.com", "target.com")
            val completed = AtomicInteger(0)

            val jobs = domains.map { domain ->
                launch(Dispatchers.Default) {
                    repeat(5) { i ->
                        val facts = KnowledgeFacts(
                            intent = "buy",
                            domain = domain,
                            urlPattern = "/product/*",
                            status = VerificationStatus.HYPOTHESIS,
                            siteFacts = SiteFacts(domain = domain),
                        )
                        store.saveFacts(facts)
                    }
                    completed.incrementAndGet()
                }
            }

            jobs.joinAll()
            assertEquals(domains.size, completed.get())

            // Verify each domain's data is intact
            for (domain in domains) {
                val loaded = store.loadFacts(domain, "buy")
                assertNotNull(loaded, "Facts should exist for $domain")
                assertEquals(domain, loaded!!.domain)
            }
        }
    }

    @Nested
    @DisplayName("mixed read/write concurrency")
    inner class MixedReadWriteConcurrency {
        @Test
        @DisplayName("reads during writes return consistent state")
        fun testReadDuringWrites() = runBlocking {
            // Pre-populate facts
            store.saveFacts(
                KnowledgeFacts(
                    intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                    status = VerificationStatus.CANDIDATE,
                    siteFacts = SiteFacts(domain = "amazon.com"),
                )
            )

            val writerErrors = mutableListOf<String>()
            val readerResults = mutableListOf<KnowledgeFacts?>()

            val writer = launch(Dispatchers.Default) {
                repeat(10) {
                    try {
                        val facts = KnowledgeFacts(
                            intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                            status = VerificationStatus.CANDIDATE,
                            siteFacts = SiteFacts(domain = "amazon.com", siteFamily = "amazon-like"),
                        )
                        store.saveFacts(facts)
                    } catch (e: Exception) {
                        synchronized(writerErrors) {
                            writerErrors.add("Writer failed: ${e.message}")
                        }
                    }
                }
            }

            val reader = launch(Dispatchers.Default) {
                repeat(20) {
                    val facts = store.loadFacts("amazon.com", "buy")
                    synchronized(readerResults) {
                        readerResults.add(facts)
                    }
                    delay(1) // Small delay to interleave
                }
            }

            writer.join()
            reader.join()

            assertEquals(0, writerErrors.size, "Writers should not error: $writerErrors")
            // All reads should return either the initial or updated facts — never null or corrupt
            for (result in readerResults) {
                assertNotNull(result, "Reader should always get valid facts")
                assertEquals("amazon.com", result!!.domain)
                assertEquals("buy", result.intent)
            }
        }

        @Test
        @DisplayName("query during concurrent updates returns consistent results")
        fun testQueryDuringUpdates() = runBlocking {
            // Pre-populate
            store.saveFacts(
                KnowledgeFacts(
                    intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                    status = VerificationStatus.VERIFIED,
                    siteFacts = SiteFacts(domain = "amazon.com"),
                    selectors = mapOf("title" to VerifiedSelector(primary = "h1")),
                )
            )
            // Add stats for non-P5 tier
            repeat(5) {
                store.updateStats(
                    TraceRecord(
                        intent = "buy", domain = "amazon.com",
                        url = "https://amazon.com/dp/test$it", urlPattern = "/dp/*",
                        outcome = "success", actions = emptyList(),
                    )
                )
            }

            val updateDone = AtomicInteger(0)
            val queryResults = mutableListOf<String>()

            val updater = launch(Dispatchers.Default) {
                repeat(10) {
                    store.updateStats(
                        TraceRecord(
                            intent = "buy", domain = "amazon.com",
                            url = "https://amazon.com/dp/new$it", urlPattern = "/dp/*",
                            outcome = "success", actions = emptyList(),
                        )
                    )
                    updateDone.incrementAndGet()
                }
            }

            val querier = launch(Dispatchers.Default) {
                repeat(15) {
                    val result = store.query("https://amazon.com/dp/test", "buy product")
                    synchronized(queryResults) { queryResults.add(result.tier) }
                    delay(1)
                }
            }

            updater.join()
            querier.join()

            assertEquals(10, updateDone.get())
            assertTrue(queryResults.isNotEmpty())
            // All queries should return a valid tier (never P5 since we have data)
            for (tier in queryResults) {
                assertTrue(tier in listOf("P1", "P2", "P3", "P4"), "Unexpected tier: $tier")
            }
        }
    }

    @Nested
    @DisplayName("stress: many coroutines, many domains")
    inner class StressConcurrency {
        @Test
        @DisplayName("100 concurrent saves across 10 domains complete without errors")
        fun testStressConcurrent() = runBlocking {
            val domainCount = 10
            val savesPerDomain = 10
            val errors = mutableListOf<String>()

            val jobs = (0 until domainCount).flatMap { d ->
                (0 until savesPerDomain).map { i ->
                    launch(Dispatchers.Default) {
                        try {
                            val domain = "site${d}.com"
                            val facts = KnowledgeFacts(
                                intent = "extract",
                                domain = domain,
                                urlPattern = "/data/*",
                                status = VerificationStatus.HYPOTHESIS,
                            )
                            store.saveFacts(facts)
                            store.updateStats(
                                TraceRecord(
                                    intent = "extract", domain = domain,
                                    url = "https://$domain/data/$i", urlPattern = "/data/*",
                                    outcome = "success",
                                )
                            )
                        } catch (e: Exception) {
                            synchronized(errors) {
                                errors.add("d${d}-i$i failed: ${e.message}")
                            }
                        }
                    }
                }
            }

            jobs.joinAll()
            assertEquals(0, errors.size, "Expected 0 errors but got: $errors")

            // Verify all domains persisted correctly
            for (d in 0 until domainCount) {
                val domain = "site${d}.com"
                val facts = store.loadFacts(domain, "extract")
                assertNotNull(facts, "Facts missing for $domain")
                val stats = store.loadStats(domain, "extract")
                assertEquals(savesPerDomain, stats.totalAttempts,
                    "Expected $savesPerDomain attempts for $domain")
            }
        }
    }
}
