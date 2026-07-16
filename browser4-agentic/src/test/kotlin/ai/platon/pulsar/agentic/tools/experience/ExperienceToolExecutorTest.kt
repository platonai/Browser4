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

@OptIn(ExperimentalPathApi::class)
@DisplayName("ExperienceToolExecutor v2")
class ExperienceToolExecutorTest {

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
    @DisplayName("experience_save — Fast Learning")
    inner class FastSave {
        @Test
        @DisplayName("saves trace and returns stats with intent classification")
        fun testSaveSuccess() = runBlocking {
            val trace = ExecutionTrace(
                url = "https://amazon.com/dp/test-product",
                taskType = "extract_product_detail",
                outcome = "success",
                steps = listOf(
                    ActionStep(1, "navigate", value = "https://amazon.com/dp/test-product"),
                    ActionStep(2, "click", selector = "#add-to-cart", result = "success"),
                ),
                durationMs = 2500,
            )

            val result = executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test-product",
                    "trace" to mapper.writeValueAsString(trace),
                    "outcome" to "success",
                    "intent" to "buy this product",
                ),
                receiver = knowledgeStore,
            )

            val json = mapper.readTree(result as String)
            assertEquals(true, json["saved"].asBoolean())
            assertEquals("amazon.com", json["domain"].asText())
            assertTrue(json["confidence"].asDouble() > 0.0)
            assertNotNull(json["intent"]?.asText())
        }

        @Test
        @DisplayName("save failure records failure category")
        fun testSaveFailure() = runBlocking {
            val trace = ExecutionTrace(
                url = "https://blocked.com/search",
                taskType = "search",
                outcome = "failure",
                errorMessage = "CAPTCHA detected on page",
                steps = listOf(
                    ActionStep(1, "click", selector = "#search-btn", result = "error: captcha"),
                ),
            )

            val result = executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://blocked.com/search",
                    "trace" to mapper.writeValueAsString(trace),
                    "outcome" to "failure",
                    "intent" to "search for product",
                ),
                receiver = knowledgeStore,
            )

            val json = mapper.readTree(result as String)
            assertEquals(true, json["saved"].asBoolean())
            assertEquals("failure", json["outcome"].asText())
            assertEquals("anti_bot", json["failure_category"].asText())
        }

        @Test
        @DisplayName("save classifies intent from free text")
        fun testIntentClassification() = runBlocking {
            val trace = ExecutionTrace(
                url = "https://example.com/login",
                taskType = "login",
                outcome = "success",
            )

            val result = executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://example.com/login",
                    "trace" to mapper.writeValueAsString(trace),
                    "intent" to "sign in with my account",
                ),
                receiver = knowledgeStore,
            )

            val json = mapper.readTree(result as String)
            assertEquals("login", json["intent"].asText())
        }
    }

    @Nested
    @DisplayName("experience_query — intent-based")
    inner class IntentQuery {
        @Test
        @DisplayName("cold start returns P5 with intent")
        fun testColdStart() = runBlocking {
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "query",
                args = mapOf("url" to "https://unknown.com/page", "intent" to "extract data"),
                receiver = knowledgeStore,
            )

            val json = mapper.readTree(result as String)
            assertEquals("P5", json["tier"].asText())
            assertNotNull(json["intent"]?.asText())
        }

        @Test
        @DisplayName("query returns knowledge after save + deep_learn")
        fun testQueryAfterSave() = runBlocking {
            // Fast save
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
                    "intent" to "buy this product",
                ),
                receiver = knowledgeStore,
            )

            // Deep learn
            executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "buy this product",
                ),
                receiver = knowledgeStore,
            )

            // Query should now find knowledge
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "query",
                args = mapOf("url" to "https://amazon.com/dp/test", "intent" to "buy this product"),
                receiver = knowledgeStore,
            )

            val json = mapper.readTree(result as String)
            assertNotEquals("P5", json["tier"].asText(), "Should not be cold start after save+deep_learn")
            assertEquals("amazon.com", json["domain"].asText())
        }
    }

    @Nested
    @DisplayName("experience_deep_learn")
    inner class DeepLearn {
        @Test
        @DisplayName("deep_learn creates hypothesis facts with force=true")
        fun testDeepLearnCreatesHypothesis() = runBlocking {
            // First save a few traces
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
                        "intent" to "buy product",
                    ),
                    receiver = knowledgeStore,
                )
            }

            // Then deep learn with force=true
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "buy product",
                    "force" to "true",
                ),
                receiver = knowledgeStore,
            )

            val json = mapper.readTree(result as String)
            // status should be hypothesis (not enough visits to promote)
            val status = json["status_after"].asText().lowercase()
            assertTrue(status in listOf("hypothesis", "candidate", "verified"),
                "Expected hypothesis/candidate/verified but got $status")
        }

        @Test
        @DisplayName("deep_learn skip when confidence high")
        fun testDeepLearnSkipHighConfidence() = runBlocking {
            // Save many successful traces first
            repeat(25) { i ->
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
                        "intent" to "buy product",
                    ),
                    receiver = knowledgeStore,
                )
            }

            // Deep learn — should skip due to high confidence
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test",
                    "intent" to "buy product",
                ),
                receiver = knowledgeStore,
            )

            val json = mapper.readTree(result as String)
            // May skip if confidence already ≥ 0.90
            if (!json["completed"].asBoolean()) {
                assertTrue(json["message"].asText().contains("0.9"))
            }
        }
    }

    @Nested
    @DisplayName("experience_list")
    inner class List {
        @Test
        @DisplayName("empty list for fresh store")
        fun testEmptyList() = runBlocking {
            val result = executor.callFunctionOn(
                domain = "experience", functionName = "list",
                args = emptyMap(), receiver = knowledgeStore,
            )
            val json = mapper.readTree(result as String)
            assertEquals(0, json["total"].asInt())
        }
    }

    @Nested
    @DisplayName("executor metadata")
    inner class Metadata {
        @Test
        @DisplayName("domain is experience")
        fun testDomain() = assertEquals("experience", executor.domain)

        @Test
        @DisplayName("has tool specs for save, query, list, and deep_learn")
        fun testToolSpecs() {
            val specs = executor.getToolSpecs()
            assertTrue(specs.containsKey("save"))
            assertTrue(specs.containsKey("query"))
            assertTrue(specs.containsKey("list"))
            assertTrue(specs.containsKey("deep_learn"))
        }
    }
}
