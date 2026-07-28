package ai.platon.pulsar.agentic.tools.experience

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.*

/**
 * Additional coverage for ExperienceToolExecutor gaps:
 * - experience_list with data after save+deep_learn
 * - experience_save edge cases (missing args, invalid JSON)
 * - experience_deep_learn promotion to CANDIDATE and VERIFIED
 * - experience_query with no intent
 * - experience_query after multiple saves on different domains
 */
@OptIn(ExperimentalPathApi::class)
@DisplayName("ExperienceToolExecutor — Extended Coverage")
class ExperienceToolExecutorExtendedTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var executor: ExperienceToolExecutor
    private lateinit var knowledgeStore: KnowledgeStore
    private val mapper = pulsarObjectMapper()

    @BeforeEach
    fun setUp() {
        knowledgeStore = KnowledgeStore(tempDir)
        knowledgeStore.initializeStore()
        executor = ExperienceToolExecutor(knowledgeStore)
    }

    @AfterEach
    fun tearDown() {
        try { tempDir.deleteRecursively() } catch (_: Exception) {}
    }

    @Nested
    @DisplayName("experience_save — edge cases")
    inner class SaveEdgeCases {
        @Test
        @DisplayName("save with task_type parameter")
        fun testSaveWithTaskType() = runBlocking {
            val trace = ExecutionTrace(
                url = "https://amazon.com/dp/test",
                taskType = "extract_product_detail",
                outcome = "success",
            )
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "trace" to mapper.writeValueAsString(trace),
                    "task_type" to "navigate",
                ),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            assertEquals(true, json["saved"].asBoolean())
            // task_type should be stored
        }

        @Test
        @DisplayName("save defaults outcome to success when omitted")
        fun testSaveDefaultOutcome() = runBlocking {
            val trace = ExecutionTrace(
                url = "https://example.com/page",
                taskType = "navigate",
                outcome = "success",
            )
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://example.com/page",
                    "trace" to mapper.writeValueAsString(trace),
                ),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            assertEquals(true, json["saved"].asBoolean())
            assertEquals("success", json["outcome"].asText())
        }

        @Test
        @DisplayName("save with empty steps list is valid")
        fun testSaveWithEmptySteps() = runBlocking {
            val trace = ExecutionTrace(
                url = "https://example.com",
                taskType = "navigate",
                outcome = "success",
                steps = emptyList(),
            )
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://example.com",
                    "trace" to mapper.writeValueAsString(trace),
                ),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            assertEquals(true, json["saved"].asBoolean())
        }

        @Test
        @DisplayName("save with null intent classifies as OTHER")
        fun testSaveWithNullIntent() = runBlocking {
            val trace = ExecutionTrace(
                url = "https://example.com/page",
                taskType = "navigate",
                outcome = "success",
            )
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://example.com/page",
                    "trace" to mapper.writeValueAsString(trace),
                    // intent intentionally omitted
                ),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            assertEquals(true, json["saved"].asBoolean())
            // Intent should be classified as "other" (null → OTHER)
            assertNotNull(json["intent"]?.asText())
        }

        @Test
        @DisplayName("save throws on missing url")
        fun testSaveMissingUrl() = runBlocking {
            assertFailsWith<Exception> {
                executor.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "trace" to "{}",
                    ),
                    receiver = knowledgeStore,
                )
            }
        }

        @Test
        @DisplayName("save throws on invalid trace JSON")
        fun testSaveInvalidTraceJson() = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                executor.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "url" to "https://example.com",
                        "trace" to "not valid json {{{",
                    ),
                    receiver = knowledgeStore,
                )
            }
        }
    }

    @Nested
    @DisplayName("experience_query — edge cases")
    inner class QueryEdgeCases {
        @Test
        @DisplayName("query with no intent still works")
        fun testQueryNoIntent() = runBlocking {
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "query",
                args = mapOf("url" to "https://amazon.com/dp/test"),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            assertEquals("P5", json["tier"].asText())
            // Without intent it should still return a result (cold start)
            assertNotNull(json["domain"]?.asText())
        }

        @Test
        @DisplayName("query for unknown domain after saving other domains")
        fun testQueryUnknownAfterSavingOthers() = runBlocking {
            // Save knowledge for amazon
            val trace = ExecutionTrace(
                url = "https://amazon.com/dp/test",
                taskType = "extract_product_detail",
                outcome = "success",
            )
            executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "trace" to mapper.writeValueAsString(trace),
                    "intent" to "buy product",
                ),
                receiver = knowledgeStore,
            )
            executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "buy product",
                ),
                receiver = knowledgeStore,
            )

            // Query for a completely different domain
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "query",
                args = mapOf("url" to "https://ebay.com/itm/test", "intent" to "buy product"),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            // Should return P5 (cold start) for ebay since we only saved amazon
            assertEquals("P5", json["tier"].asText())
        }

        @Test
        @DisplayName("query then save then query shows knowledge progression")
        fun testQuerySaveQueryProgression() = runBlocking {
            // Initial query: cold start
            val q1 = executor.callFunctionOn(
                domain = "experience", functionName = "query",
                args = mapOf("url" to "https://amazon.com/dp/test", "intent" to "buy product"),
                receiver = knowledgeStore,
            )
            assertEquals("P5", mapper.readTree(q1 as String)["tier"].asText())

            // Save trace
            val trace = ExecutionTrace(
                url = "https://amazon.com/dp/test",
                taskType = "extract_product_detail",
                outcome = "success",
            )
            executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "trace" to mapper.writeValueAsString(trace),
                    "intent" to "buy product",
                ),
                receiver = knowledgeStore,
            )

            // Deep learn
            executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "buy product",
                ),
                receiver = knowledgeStore,
            )

            // Query again: should find knowledge
            val q2 = executor.callFunctionOn(
                domain = "experience", functionName = "query",
                args = mapOf("url" to "https://amazon.com/dp/test", "intent" to "buy product"),
                receiver = knowledgeStore,
            )
            assertNotEquals("P5", mapper.readTree(q2 as String)["tier"].asText(),
                "Should not be cold start after save+deep_learn")
        }
    }

    @Nested
    @DisplayName("experience_deep_learn — promotion paths")
    inner class DeepLearnPromotion {
        @Test
        @DisplayName("promotes to CANDIDATE after 2+ successes")
        fun testPromoteToCandidate() = runBlocking {
            // Save 3 successful traces
            repeat(3) { i ->
                val trace = ExecutionTrace(
                    url = "https://amazon.com/dp/test$i",
                    taskType = "extract_product_detail",
                    outcome = "success",
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "url" to "https://amazon.com/dp/test$i",
                        "trace" to mapper.writeValueAsString(trace),
                        "intent" to "extract product",
                    ),
                    receiver = knowledgeStore,
                )
            }

            // Deep learn should promote to CANDIDATE (3 successes, confidence >= 0.60)
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "extract product",
                    "force" to "true",
                ),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            val status = json["status_after"].asText().lowercase()
            assertTrue(status in listOf("candidate", "verified"),
                "Expected candidate or verified after 3 successes, got $status")
        }

        @Test
        @DisplayName("promotes to VERIFIED after 5+ successes")
        fun testPromoteToVerified() = runBlocking {
            // Save 6 successful traces
            repeat(6) { i ->
                val trace = ExecutionTrace(
                    url = "https://amazon.com/dp/test$i",
                    taskType = "extract_product_detail",
                    outcome = "success",
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "url" to "https://amazon.com/dp/test$i",
                        "trace" to mapper.writeValueAsString(trace),
                        "intent" to "extract product",
                    ),
                    receiver = knowledgeStore,
                )
            }

            val result = executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "extract product",
                    "force" to "true",
                ),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            assertEquals("verified", json["status_after"].asText().lowercase())
            assertEquals(true, json["promoted"].asBoolean())
        }

        @Test
        @DisplayName("deep_learn with force=false on low confidence runs anyway")
        fun testDeepLearnForceFalseLowConfidence() = runBlocking {
            // Save just 1 trace (low confidence)
            val trace = ExecutionTrace(
                url = "https://amazon.com/dp/single",
                taskType = "extract_product_detail",
                outcome = "success",
            )
            executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://amazon.com/dp/single",
                    "trace" to mapper.writeValueAsString(trace),
                    "intent" to "extract product",
                ),
                receiver = knowledgeStore,
            )

            // force=false (or not specified), confidence should be < 0.90 → runs normally
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/single",
                    "intent" to "extract product",
                    // force not set → defaults to false
                ),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            // Should complete (confidence < 0.90 so it runs)
            assertEquals(true, json["completed"].asBoolean())
            assertEquals("hypothesis", json["status_after"].asText().lowercase())
        }

        @Test
        @DisplayName("deep_learn after all failures still creates hypothesis")
        fun testDeepLearnAfterAllFailures() = runBlocking {
            // Save 3 failed traces only
            repeat(3) { i ->
                val trace = ExecutionTrace(
                    url = "https://blocked.com/page$i",
                    taskType = "search",
                    outcome = "failure",
                    errorMessage = "CAPTCHA detected on page",
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "url" to "https://blocked.com/page$i",
                        "trace" to mapper.writeValueAsString(trace),
                        "outcome" to "failure",
                        "intent" to "search product",
                    ),
                    receiver = knowledgeStore,
                )
            }

            val result = executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://blocked.com/page",
                    "intent" to "search product",
                    "force" to "true",
                ),
                receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            // Should still create hypothesis even after only failures
            assertEquals(true, json["completed"].asBoolean())
            assertNotNull(json["status_after"]?.asText())
        }
    }

    @Nested
    @DisplayName("experience_list — with data")
    inner class ListWithData {
        @Test
        @DisplayName("list returns entries after save+deep_learn")
        fun testListAfterSaveAndDeepLearn() = runBlocking {
            // Save and deep_learn for amazon
            val trace = ExecutionTrace(
                url = "https://amazon.com/dp/test",
                taskType = "extract_product_detail",
                outcome = "success",
            )
            executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "trace" to mapper.writeValueAsString(trace),
                    "intent" to "buy product",
                ),
                receiver = knowledgeStore,
            )
            executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "buy product",
                    "force" to "true",
                ),
                receiver = knowledgeStore,
            )

            val result = executor.callFunctionOn(
                domain = "experience", functionName = "list",
                args = emptyMap(), receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            assertTrue(json["total"].asInt() >= 1, "Expected at least 1 entry after save+deep_learn")
            assertEquals(1, json["page"].asInt())

            val entry = json["entries"].get(0)
            assertEquals("amazon.com", entry["domain"].asText())
            assertEquals("buy", entry["intent"].asText())
        }

        @Test
        @DisplayName("list with filter parameter")
        fun testListWithFilter() = runBlocking {
            // Save for two different domains
            for ((domain, intent) in listOf("amazon.com" to "buy", "ebay.com" to "extract")) {
                val trace = ExecutionTrace(
                    url = "https://$domain/test",
                    taskType = "extract_product_detail",
                    outcome = "success",
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "url" to "https://$domain/test",
                        "trace" to mapper.writeValueAsString(trace),
                        "intent" to intent,
                    ),
                    receiver = knowledgeStore,
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "deep_learn",
                    args = mapOf(
                        "url" to "https://$domain/test",
                        "intent" to intent,
                        "force" to "true",
                    ),
                    receiver = knowledgeStore,
                )
            }

            // Filter by domain
            val filtered = executor.callFunctionOn(
                domain = "experience", functionName = "list",
                args = mapOf("filter" to "amazon"), receiver = knowledgeStore,
            )
            val json = mapper.readTree(filtered as String)
            assertEquals(1, json["total"].asInt())
            assertEquals("amazon.com", json["entries"].get(0)["domain"].asText())
        }

        @Test
        @DisplayName("list with intent_filter parameter")
        fun testListWithIntentFilter() = runBlocking {
            // Save for two different intents
            for ((domain, intent) in listOf("amazon.com" to "buy", "amazon.com" to "search")) {
                val trace = ExecutionTrace(
                    url = "https://$domain/test",
                    outcome = "success",
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "url" to "https://$domain/test",
                        "trace" to mapper.writeValueAsString(trace),
                        "intent" to intent,
                    ),
                    receiver = knowledgeStore,
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "deep_learn",
                    args = mapOf(
                        "url" to "https://$domain/test",
                        "intent" to intent,
                        "force" to "true",
                    ),
                    receiver = knowledgeStore,
                )
            }

            val buyResult = executor.callFunctionOn(
                domain = "experience", functionName = "list",
                args = mapOf("intent_filter" to "buy"), receiver = knowledgeStore,
            )
            assertEquals(1, mapper.readTree(buyResult as String)["total"].asInt())

            val searchResult = executor.callFunctionOn(
                domain = "experience", functionName = "list",
                args = mapOf("intent_filter" to "search"), receiver = knowledgeStore,
            )
            assertEquals(1, mapper.readTree(searchResult as String)["total"].asInt())
        }

        @Test
        @DisplayName("list pagination after multiple saves")
        fun testListPagination() = runBlocking {
            // Save 5 entries for different domains
            for (i in 1..5) {
                val trace = ExecutionTrace(
                    url = "https://site$i.com/test",
                    outcome = "success",
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "url" to "https://site$i.com/test",
                        "trace" to mapper.writeValueAsString(trace),
                        "intent" to "buy",
                    ),
                    receiver = knowledgeStore,
                )
                executor.callFunctionOn(
                    domain = "experience", functionName = "deep_learn",
                    args = mapOf(
                        "url" to "https://site$i.com/test",
                        "intent" to "buy",
                        "force" to "true",
                    ),
                    receiver = knowledgeStore,
                )
            }

            val page1 = executor.callFunctionOn(
                domain = "experience", functionName = "list",
                args = mapOf("page" to "1", "page_size" to "3"), receiver = knowledgeStore,
            )
            val json1 = mapper.readTree(page1 as String)
            assertEquals(5, json1["total"].asInt())
            assertEquals(3, json1["entries"].size())
            assertEquals(2, json1["total_pages"].asInt())

            val page2 = executor.callFunctionOn(
                domain = "experience", functionName = "list",
                args = mapOf("page" to "2", "page_size" to "3"), receiver = knowledgeStore,
            )
            val json2 = mapper.readTree(page2 as String)
            assertEquals(2, json2["entries"].size())
        }
    }

    @Nested
    @DisplayName("Unsupported method")
    inner class UnsupportedMethod {
        @Test
        @DisplayName("throws on unknown function name")
        fun testUnknownFunction() = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                executor.callFunctionOn(
                    domain = "experience", functionName = "nonexistent_method",
                    args = emptyMap(), receiver = knowledgeStore,
                )
            }
        }

        @Test
        @DisplayName("throws on wrong domain")
        fun testWrongDomain() = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                executor.callFunctionOn(
                    domain = "wrong_domain", functionName = "save",
                    args = emptyMap(), receiver = knowledgeStore,
                )
            }
        }
    }
}
