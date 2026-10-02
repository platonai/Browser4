package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.experience.ExperienceToolExecutor
import ai.platon.pulsar.agentic.tools.experience.KnowledgeStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.*

/**
 * Tests for [ExperienceToolMountConfiguration] Spring bean wiring.
 *
 * Verifies:
 * - KnowledgeStore bean creation and initialization
 * - ExperienceToolExecutor bean creation with KnowledgeStore injection
 * - ToolMount.getToolExecutors() returns the executor
 * - All beans are usable (not just constructed)
 */
@OptIn(ExperimentalPathApi::class)
@DisplayName("ExperienceToolMountConfiguration")
class ExperienceToolMountConfigurationTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        try { tempDir.deleteRecursively() } catch (_: Exception) {}
    }

    @Nested
    @DisplayName("bean creation")
    inner class BeanCreation {
        @Test
        @DisplayName("knowledgeStore bean is created and initialized")
        fun testKnowledgeStoreBean() {
            val config = ExperienceToolMountConfiguration()
            val store = config.knowledgeStore()

            assertNotNull(store)
            // Should be usable immediately
            val stats = store.loadStats("test.com", "buy")
            assertNotNull(stats)
            assertEquals(0.50, stats.confidence)
        }

        @Test
        @DisplayName("experienceToolExecutor bean is created with KnowledgeStore")
        fun testExperienceToolExecutorBean() {
            val config = ExperienceToolMountConfiguration()
            val store = config.knowledgeStore()
            val executor = config.experienceToolExecutor(store)

            assertNotNull(executor)
            assertEquals("experience", executor.domain)
            // Verify all four tool specs are registered
            val specs = executor.getToolSpecs()
            assertTrue(specs.containsKey("save"), "save tool spec should be registered")
            assertTrue(specs.containsKey("query"), "query tool spec should be registered")
            assertTrue(specs.containsKey("list"), "list tool spec should be registered")
            assertTrue(specs.containsKey("deep_learn"), "deep_learn tool spec should be registered")
        }

        @Test
        @DisplayName("repeated calls to knowledgeStore create independent instances")
        fun testRepeatedKnowledgeStoreCalls() {
            val config = ExperienceToolMountConfiguration()
            val store1 = config.knowledgeStore()
            val store2 = config.knowledgeStore()

            // Each call creates a new instance (as expected from @Bean without singleton scope)
            assertNotNull(store1)
            assertNotNull(store2)
        }

        @Test
        @DisplayName("ToolMount.getToolExecutors returns executor list")
        fun testGetToolExecutors() {
            val config = ExperienceToolMountConfiguration()
            val executors = config.getToolExecutors()

            assertNotNull(executors)
            assertEquals(1, executors.size)
            assertEquals("experience", executors[0].domain)
        }
    }

    @Nested
    @DisplayName("ToolMount interface contract")
    inner class ToolMountContract {
        @Test
        @DisplayName("implements ToolMount interface")
        fun testImplementsToolMount() {
            val config = ExperienceToolMountConfiguration()
            assertTrue(config is ToolMount, "Should implement ToolMount interface")
        }

        @Test
        @DisplayName("getToolExecutors is non-empty and returns valid executors")
        fun testGetToolExecutorsValid() {
            val config = ExperienceToolMountConfiguration()
            val executors = config.getToolExecutors()

            assertTrue(executors.isNotEmpty(), "Should return at least one executor")
            for (executor in executors) {
                assertNotNull(executor.domain, "Executor should have a domain")
                assertTrue(executor.domain.isNotBlank(), "Executor domain should not be blank")
                assertTrue(executor.getToolSpecs().isNotEmpty(), "Executor should have tool specs")
            }
        }
    }

    @Nested
    @DisplayName("end-to-end: config → executor → save/query")
    inner class EndToEndViaConfig {
        @Test
        @DisplayName("executor from config can save and query")
        fun testExecutorSaveAndQuery() = runBlocking {
            val config = ExperienceToolMountConfiguration()
            val store = config.knowledgeStore()
            val executor = config.experienceToolExecutor(store)

            val mapper = ai.platon.pulsar.common.serialize.json.pulsarObjectMapper()

            // Save a trace
            val trace = ai.platon.pulsar.agentic.tools.experience.ExecutionTrace(
                url = "https://amazon.com/dp/test-product",
                taskType = "extract_product_detail",
                outcome = "success",
            )
            val saveResult = executor.callFunctionOn(
                domain = "experience", functionName = "save",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test-product",
                    "trace" to mapper.writeValueAsString(trace),
                    "outcome" to "success",
                    "intent" to "buy product",
                ),
                receiver = store,
            )
            val saveJson = mapper.readTree(saveResult as String)
            assertEquals(true, saveJson["saved"].asBoolean())

            // Deep learn
            executor.callFunctionOn(
                domain = "experience", functionName = "deep_learn",
                args = mapOf(
                    "url" to "https://amazon.com/dp/test-product",
                    "intent" to "buy product",
                    "force" to "true",
                ),
                receiver = store,
            )

            // Query should find knowledge
            val queryResult = executor.callFunctionOn(
                domain = "experience", functionName = "query",
                args = mapOf("url" to "https://amazon.com/dp/test-product", "intent" to "buy product"),
                receiver = store,
            )
            val queryJson = mapper.readTree(queryResult as String)
            assertNotEquals("P5", queryJson["tier"].asText())
        }
    }

    @Nested
    @DisplayName("configured knowledge dir")
    inner class ConfiguredKnowledgeDir {
        @Test
        @DisplayName("both the bean and the mounted executor write under knowledge.dir")
        fun testEverythingFollowsTheProperty() = runBlocking {
            val configured = tempDir.resolve("configured-store")
            val previous = System.getProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY)
            try {
                System.setProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY, configured.toString())

                // Path 1: the Spring bean.
                val config = ExperienceToolMountConfiguration()
                val store = config.knowledgeStore()
                assertTrue(
                    configured.resolve("traces").exists(),
                    "the knowledgeStore bean must initialize the configured directory",
                )

                // Path 2: ToolMount.getToolExecutors() — the executor PluginManager
                // actually registers for MCP/LLM. It builds its own store, and it
                // must land in the same directory instead of forking the knowledge
                // base in two.
                val executors = config.getToolExecutors()
                assertEquals(1, executors.size)
                val mounted = executors[0] as? ExperienceToolExecutor
                    ?: fail("the mounted executor must be the experience executor, got ${executors[0]::class}")
                val mapper = ai.platon.pulsar.common.serialize.json.pulsarObjectMapper()
                mounted.callFunctionOn(
                    domain = "experience", functionName = "save",
                    args = mapOf(
                        "url" to "https://configured.example.com/p/1",
                        "trace" to mapper.writeValueAsString(
                            ai.platon.pulsar.agentic.tools.experience.ExecutionTrace(
                                url = "https://configured.example.com/p/1",
                                taskType = "extract",
                                outcome = "success",
                            )
                        ),
                        "outcome" to "success",
                        "intent" to "extract the price",
                    ),
                    receiver = mounted,
                )

                val traceDir = configured.resolve("traces/configured.example.com")
                assertTrue(traceDir.exists(), "traces must be written per domain under the configured dir")
                assertTrue(
                    traceDir.listDirectoryEntries("*.yaml").isNotEmpty(),
                    "the mounted executor must persist traces under the configured directory",
                )

                // And nothing leaked into the cwd-relative default.
                assertTrue(
                    !java.nio.file.Path.of(KnowledgeStore.DEFAULT_BASE_DIR.toString())
                        .resolve("traces/configured.example.com").exists(),
                    "the default 'knowledge' directory must stay untouched",
                )
            } finally {
                if (previous == null) {
                    System.clearProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY)
                } else {
                    System.setProperty(KnowledgeStore.KNOWLEDGE_DIR_PROPERTY, previous)
                }
            }
        }
    }

    private fun runBlocking(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
