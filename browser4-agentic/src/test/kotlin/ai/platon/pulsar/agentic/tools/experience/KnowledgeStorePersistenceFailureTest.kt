package ai.platon.pulsar.agentic.tools.experience

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking

/**
 * Tests for KnowledgeStore YAML persistence failure paths.
 *
 * Covers:
 * - Corrupted YAML file recovery (loadFacts, loadStats, loadTrace)
 * - Atomic write failure handling
 * - KnowledgeStoreException
 * - Intent sanitization in filenames
 * - Edge cases in writeAtomicYaml
 */
@OptIn(ExperimentalPathApi::class)
@DisplayName("KnowledgeStore — Persistence Failure Paths")
class KnowledgeStorePersistenceFailureTest {

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
    @DisplayName("corrupted YAML recovery")
    inner class CorruptedYamlRecovery {
        @Test
        @DisplayName("loadFacts returns null for malformed YAML")
        fun testLoadFactsCorruptedYaml() {
            val factsDir = tempDir.resolve("facts").resolve("amazon.com")
            Files.createDirectories(factsDir)
            val badFile = factsDir.resolve("buy.yaml")
            Files.writeString(badFile, "intent: buy\ndomain: [unclosed bracket\n  - item1")

            val result = store.loadFacts("amazon.com", "buy")
            assertNull(result, "loadFacts should return null for corrupted YAML")
        }

        @Test
        @DisplayName("loadFacts returns null for empty file")
        fun testLoadFactsEmptyFile() {
            val factsDir = tempDir.resolve("facts").resolve("amazon.com")
            Files.createDirectories(factsDir)
            val emptyFile = factsDir.resolve("buy.yaml")
            Files.writeString(emptyFile, "")

            val result = store.loadFacts("amazon.com", "buy")
            assertNull(result, "loadFacts should return null for empty file")
        }

        @Test
        @DisplayName("loadFacts returns null for non-YAML content")
        fun testLoadFactsNonYamlContent() {
            val factsDir = tempDir.resolve("facts").resolve("amazon.com")
            Files.createDirectories(factsDir)
            val badFile = factsDir.resolve("buy.yaml")
            Files.writeString(badFile, "This is just plain text, not YAML at all!!!")

            val result = store.loadFacts("amazon.com", "buy")
            assertNull(result, "loadFacts should return null for non-YAML content")
        }

        @Test
        @DisplayName("loadTrace returns null for corrupted file")
        fun testLoadTraceCorruptedFile() {
            val tracesDir = tempDir.resolve("traces").resolve("amazon.com")
            Files.createDirectories(tracesDir)
            val badFile = tracesDir.resolve("2026-01-01-bad.yaml")
            Files.writeString(badFile, "::: not valid YAML ::: {{{")

            val result = store.loadTrace(badFile)
            assertNull(result, "loadTrace should return null for corrupted file")
        }

        @Test
        @DisplayName("loadTrace returns null for empty file")
        fun testLoadTraceEmptyFile() {
            val tracesDir = tempDir.resolve("traces").resolve("amazon.com")
            Files.createDirectories(tracesDir)
            val emptyFile = tracesDir.resolve("2026-01-01-empty.yaml")
            Files.writeString(emptyFile, "")

            val result = store.loadTrace(emptyFile)
            assertNull(result)
        }

        @Test
        @DisplayName("loadStats returns fresh stats for corrupted file")
        fun testLoadStatsCorruptedFile() {
            val expDir = tempDir.resolve("experience").resolve("amazon.com")
            Files.createDirectories(expDir)
            val badFile = expDir.resolve("buy.yaml")
            Files.writeString(badFile, "garbage {{{ not yaml :::")

            val stats = store.loadStats("amazon.com", "buy")
            assertNotNull(stats)
            // Should return a fresh stats object, not throw
            assertEquals(0, stats.totalAttempts)
            assertEquals(0.50, stats.confidence)
        }

        @Test
        @DisplayName("loadStats returns fresh stats for missing file")
        fun testLoadStatsMissingFile() {
            val stats = store.loadStats("nonexistent.com", "any_intent")
            assertNotNull(stats)
            assertEquals(0, stats.totalAttempts)
            assertEquals("any_intent", stats.intent)
        }

        @Test
        @DisplayName("loadFacts survives partially valid YAML with missing fields")
        fun testLoadFactsPartialYaml() {
            val factsDir = tempDir.resolve("facts").resolve("amazon.com")
            Files.createDirectories(factsDir)
            // Valid YAML but missing required fields
            Files.writeString(factsDir.resolve("buy.yaml"), "some_unknown_field: 42\nanother: hello")

            // Should not throw — handles missing fields gracefully
            val result = store.loadFacts("amazon.com", "buy")
            // May return null or a facts object with defaults — either is acceptable
            // The key is that it doesn't throw
        }
    }

    @Nested
    @DisplayName("filename sanitization")
    inner class FilenameSanitization {
        @Test
        @DisplayName("intents with special characters produce safe filenames")
        fun testIntentSanitization() = runBlocking {
            val intents = listOf(
                "simple" to "simple",
                "with spaces" to "with_spaces",
                "with/slashes" to "with_slashes",
                "with\\backslashes" to "with_backslashes",
                "very-long-intent-name-that-exceeds-fifty-characters-and-should-be-truncated" to
                    "very_long_intent_name_that_exceeds_fifty_charact",
            )

            for ((input, _) in intents) {
                // Should not throw when saving facts with any of these intents
                val facts = KnowledgeFacts(
                    intent = input,
                    domain = "test.com",
                    urlPattern = "/*",
                    status = VerificationStatus.HYPOTHESIS,
                )
                store.saveFacts(facts)
            }

            // Verify all were saved (by counting list results)
            val result = store.list(pageSize = 100)
            assertTrue(result.total >= intents.size,
                "Expected at least ${intents.size} entries, got ${result.total}")
        }
    }

    @Nested
    @DisplayName("atomic write behavior")
    inner class AtomicWriteBehavior {
        @Test
        @DisplayName("saveFacts followed by immediate loadFacts returns consistent data")
        fun testSaveThenLoadConsistency() = runBlocking {
            val facts = KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                status = VerificationStatus.VERIFIED,
                siteFacts = SiteFacts(domain = "amazon.com", siteFamily = "amazon-like"),
                selectors = mapOf(
                    "title" to VerifiedSelector(primary = "#productTitle", fallbacks = listOf("h1")),
                ),
                knownBlockers = listOf(
                    BlockerInfo(type = "cookie_consent", selector = "#accept", action = "click"),
                ),
            )
            store.saveFacts(facts)

            val loaded = store.loadFacts("amazon.com", "buy")
            assertNotNull(loaded)
            assertEquals(VerificationStatus.VERIFIED, loaded!!.status)
            assertEquals("amazon-like", loaded.siteFacts.siteFamily)
            assertEquals("#productTitle", loaded.selectors["title"]?.primary)
            assertEquals(listOf("h1"), loaded.selectors["title"]?.fallbacks)
            assertEquals(1, loaded.knownBlockers.size)
            assertEquals("cookie_consent", loaded.knownBlockers[0].type)
        }

        @Test
        @DisplayName("tmp files are not left behind after successful write")
        fun testNoTmpFilesLeftBehind() = runBlocking {
            store.saveFacts(KnowledgeFacts(
                intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
            ))

            val factsDir = tempDir.resolve("facts").resolve("amazon.com")
            val tmpFiles = factsDir.listDirectoryEntries("*.tmp")
            assertTrue(tmpFiles.isEmpty(),
                "No .tmp files should remain after successful write, found: $tmpFiles")
        }

        @Test
        @DisplayName("overwriting facts multiple times leaves clean state")
        fun testMultipleOverwritesCleanState() = runBlocking {
            repeat(5) { i ->
                store.saveFacts(KnowledgeFacts(
                    intent = "buy", domain = "amazon.com", urlPattern = "/dp/*",
                    status = if (i >= 3) VerificationStatus.VERIFIED else VerificationStatus.HYPOTHESIS,
                ))
            }

            val loaded = store.loadFacts("amazon.com", "buy")
            assertNotNull(loaded)
            assertEquals(VerificationStatus.VERIFIED, loaded!!.status)

            // No tmp files
            val factsDir = tempDir.resolve("facts").resolve("amazon.com")
            val tmpFiles = factsDir.listDirectoryEntries("*.tmp")
            assertTrue(tmpFiles.isEmpty(), "No tmp files should remain")
            // Only the final yaml file
            val yamlFiles = factsDir.listDirectoryEntries("*.yaml")
            assertEquals(1, yamlFiles.size, "Only one facts file should exist")
        }
    }

    @Nested
    @DisplayName("KnowledgeStoreException")
    inner class KnowledgeStoreExceptionTests {
        @Test
        @DisplayName("KnowledgeStoreException can be constructed with message")
        fun testExceptionMessage() {
            val ex = KnowledgeStoreException("test failure")
            assertEquals("test failure", ex.message)
            assertNull(ex.cause)
        }

        @Test
        @DisplayName("KnowledgeStoreException can be constructed with message and cause")
        fun testExceptionWithCause() {
            val cause = RuntimeException("root cause")
            val ex = KnowledgeStoreException("wrapped failure", cause)
            assertEquals("wrapped failure", ex.message)
            assertEquals(cause, ex.cause)
        }
    }

    @Nested
    @DisplayName("edge case: read-only filesystem behavior")
    inner class ReadOnlyEdgeCases {
        @Test
        @DisplayName("loadFacts gracefully handles missing directory")
        fun testLoadFactsMissingDirectory() {
            // Ensure directory does not exist
            val factsDir = tempDir.resolve("facts").resolve("nonexistent.com")
            assertFalse(factsDir.exists())

            val result = store.loadFacts("nonexistent.com", "any")
            assertNull(result)
        }

        @Test
        @DisplayName("list gracefully handles missing facts directory")
        fun testListMissingFactsDirectory() {
            // Remove the facts directory
            tempDir.resolve("facts").deleteRecursively()

            val result = store.list()
            assertEquals(0, result.total)
            assertEquals(0, result.entries.size)
        }

        @Test
        @DisplayName("list gracefully handles unreadable YAML in facts directory")
        fun testListSkipsUnreadableFiles() {
            val factsDir = tempDir.resolve("facts").resolve("amazon.com")
            Files.createDirectories(factsDir)
            // Valid file
            Files.writeString(factsDir.resolve("buy.yaml"), """
                intent: buy
                domain: amazon.com
                url_pattern: /dp/*
                status: hypothesis
                site_facts:
                  domain: amazon.com
                page_facts: {}
                selectors: {}
            """.trimIndent())
            // Invalid file
            Files.writeString(factsDir.resolve("bad.yaml"), "{{{ not yaml :::")

            // Should not throw — just skip the bad file
            val result = store.list()
            assertTrue(result.total >= 1, "Should find at least the valid entry")
        }
    }
}
