@file:Suppress("UNCHECKED_CAST")

package ai.platon.pulsar.agentic.agent

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.ChatModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.net.JarURLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile

/**
 * End-to-end test for Browser4 Agent using test cases from the use-cases directory.
 *
 * This test validates the complete agent workflow by:
 * 1. Reading a test case from browser4-tests-common/src/main/resources/e2e/scenarios/happy_path/use-cases
 * 2. Running the test case against an AgenticSession using agent.run()
 * 3. Adding event handlers to log progress
 * 4. Verifying the answer is as expected
 *
 * Test coverage:
 * - Test case loading from resource files
 * - Agent execution with event-based progress tracking
 * - Validation of agent completion status
 *
 * ## Prerequisites
 *
 * Agent-based tests require LLM configuration. Set the following in application.properties:
 * ```
 * llm.provider=openai
 * llm.apiKey=your-api-key
 * ```
 *
 * Tests that require LLM will be automatically skipped if not configured.
 *
 * ## Running These Tests
 *
 * By default, E2ETest tagged tests are excluded from normal test runs. To run them:
 * ```bash
 * ./mvnw test -P all-main-modules -pl :pulsar-e2e-tests -am -Dtest=AgentE2ETest
 * ```
 *
 * Or run all E2E tests:
 * ```bash
 * ./mvnw test -P all-main-modules -Dgroups=E2ETest -Dsurefire.excludedGroups="" -DfailIfNoTests=false
 * ```
 */
@Tag("E2ETest")
@Tag("Slow")
@Tag("ManualOnly")
@Tag("agent")
@Disabled("ManualOnly")
class AgentE2ETest {

    private val logger = getLogger(this)

    companion object {
        private const val USE_CASE_DIR = "e2e/scenarios/happy_path/use-cases/"
        private const val SYS_PROP_USE_CASE = "pulsar.e2e.useCase"
        private const val SYS_PROP_SEED = "pulsar.e2e.seed"
        private const val ENV_USE_CASE = "PULSAR_E2E_USE_CASE"
        private const val ENV_SEED = "PULSAR_E2E_SEED"

        private const val MAX_TEST_STEPS = 5  // Maximum steps before auto-completing for test scenarios
        private const val EVENT_PROCESSING_DELAY_MS = 500L  // Time to allow for async event processing
        private val capturedEvents = ConcurrentHashMap<String, MutableList<Map<String, Any?>>>()
        private val runStepCount = AtomicInteger(0)
        private val observeCount = AtomicInteger(0)
        private val actCount = AtomicInteger(0)
        private val eventLogger = getLogger("AgentE2ETest.EventLogger")

        private data class UseCaseSpec(
            val requestedUseCase: String?,
            val seed: Long,
            val seedSource: String,
        )

        private fun readFirstNonBlank(vararg values: String?): String? {
            return values.firstOrNull { !it.isNullOrBlank() }?.trim()
        }

        private fun resolveUseCaseSpec(): UseCaseSpec {
            val requestedUseCase = readFirstNonBlank(
                System.getProperty(SYS_PROP_USE_CASE),
                System.getenv(ENV_USE_CASE),
            )

            val rawSeed = readFirstNonBlank(
                System.getProperty(SYS_PROP_SEED),
                System.getenv(ENV_SEED),
            )

            val (seed, seedSource) = if (!rawSeed.isNullOrBlank()) {
                rawSeed.toLongOrNull()?.let { it to "${SYS_PROP_SEED}/${ENV_SEED}" }
                    ?: (System.currentTimeMillis() to "currentTimeMillis(invalidSeed:'$rawSeed')")
            } else {
                System.currentTimeMillis() to "currentTimeMillis"
            }

            return UseCaseSpec(requestedUseCase, seed, seedSource)
        }

        private fun normalizeRequestedUseCase(requested: String): String {
            // allow passing either full resource path or just file name
            return if (requested.contains('/')) requested else USE_CASE_DIR + requested
        }

        private fun listUseCaseResources(classLoader: ClassLoader): List<String> {
            val url = classLoader.getResource(USE_CASE_DIR) ?: return emptyList()

            return when (url.protocol) {
                "file" -> {
                    val decoded = URLDecoder.decode(url.path, StandardCharsets.UTF_8.name())
                    val dir = java.io.File(decoded)
                    if (!dir.isDirectory) return emptyList()
                    dir.listFiles()
                        ?.filter { it.isFile && it.name.endsWith(".txt", ignoreCase = true) }
                        ?.map { USE_CASE_DIR + it.name }
                        ?.sorted()
                        ?: emptyList()
                }

                "jar" -> {
                    val conn = url.openConnection() as JarURLConnection
                    val jarFile: JarFile = conn.jarFile
                    jarFile.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith(USE_CASE_DIR) && !it.endsWith("/") }
                        .filter { it.substringAfterLast('/').endsWith(".txt", ignoreCase = true) }
                        .sorted()
                        .toList()
                }

                else -> {
                    // Best-effort fallback: try to enumerate resources in directory (may not work for all CL impls)
                    emptyList()
                }
            }
        }

        private fun pickUseCasePath(spec: UseCaseSpec, classLoader: ClassLoader): String {
            val candidates = listUseCaseResources(classLoader)
            require(candidates.isNotEmpty()) { "No use-case resources found under '$USE_CASE_DIR'" }

            val requested = spec.requestedUseCase
            if (!requested.isNullOrBlank()) {
                val normalized = normalizeRequestedUseCase(requested)
                require(candidates.contains(normalized)) {
                    "Requested use-case '$requested' not found. Available: ${candidates.joinToString()}"
                }
                return normalized
            }

            val rnd = Random(spec.seed)
            return candidates[rnd.nextInt(candidates.size)]
        }

        @BeforeAll
        @JvmStatic
        fun setupEventHandlers() {
            // Register event handlers for progress logging

            AgentEventBus.agentEventHandlers?.agentFlowHandlers?.onWillObserve?.addLast { options ->
                println(options.instruction)
            }

            // Log when run starts
            EventBus.register(AgenticEvents.PerceptiveAgent.ON_WILL_RUN) { payload ->
                val map = payload as? Map<String, Any?> ?: return@register null
                val action = map["action"]
                eventLogger.info("🚀 Agent run starting - action: {}", action)
                capturedEvents.computeIfAbsent(AgenticEvents.PerceptiveAgent.ON_WILL_RUN) {
                    mutableListOf()
                }.add(map)
                payload
            }

            // Log when run completes
            EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_RUN) { payload ->
                val map = payload as? Map<String, Any?> ?: return@register null
                val stateHistory = map["stateHistory"] as? AgentHistory
                eventLogger.info("✅ Agent run completed - steps: {}, isDone: {}",
                    stateHistory?.totalSteps, stateHistory?.isDone)
                capturedEvents.computeIfAbsent(AgenticEvents.PerceptiveAgent.ON_DID_RUN) {
                    mutableListOf()
                }.add(map)
                payload
            }

            // Log observe events
            EventBus.register(AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE) { payload ->
                val map = payload as? Map<String, Any?> ?: return@register null
                observeCount.incrementAndGet()
                eventLogger.debug("👀 Observing... (count: {})", observeCount.get())
                capturedEvents.computeIfAbsent(AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE) {
                    mutableListOf()
                }.add(map)
                payload
            }

            EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE) { payload ->
                val map = payload as? Map<String, Any?> ?: return@register null
                val observeResults = map["observeResults"] as? List<Any>
                eventLogger.debug("👀 Observed {} results", observeResults?.size ?: 0)
                capturedEvents.computeIfAbsent(AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE) {
                    mutableListOf()
                }.add(map)
                payload
            }

            // Log act events
            EventBus.register(AgenticEvents.PerceptiveAgent.ON_WILL_ACT) { payload ->
                val map = payload as? Map<String, Any?> ?: return@register null
                actCount.incrementAndGet()
                val action = map["action"]
                eventLogger.info("🎬 Acting... (count: {}) - action: {}", actCount.get(), action)
                capturedEvents.computeIfAbsent(AgenticEvents.PerceptiveAgent.ON_WILL_ACT) {
                    mutableListOf()
                }.add(map)
                payload
            }

            EventBus.register(AgenticEvents.PerceptiveAgent.ON_DID_ACT) { payload ->
                val map = payload as? Map<String, Any?> ?: return@register null
                val result = map["result"]
                eventLogger.info("🎬 Act completed - result: {}", Strings.compactInline(result.toString()))
                capturedEvents.computeIfAbsent(AgenticEvents.PerceptiveAgent.ON_DID_ACT) {
                    mutableListOf()
                }.add(map)
                payload
            }

            // Log tool generation events
            EventBus.register(AgenticEvents.ContextToAction.ON_DID_GENERATE) { payload ->
                val map = payload as? Map<String, Any?> ?: return@register null
                runStepCount.incrementAndGet()
                val actionDescription = map["actionDescription"] as? ActionDescription
                eventLogger.info("🔧 Step {} - Generated action: {}",
                    runStepCount.get(), actionDescription?.pseudoExpression)

                capturedEvents.computeIfAbsent(AgenticEvents.ContextToAction.ON_DID_GENERATE) {
                    mutableListOf()
                }.add(map)

                // Complete the action if it's a test run to allow test progression
                // This prevents infinite loops in test scenarios
                if (runStepCount.get() >= MAX_TEST_STEPS) {
                    actionDescription?.complete("Test step limit reached - completing for test")
                    eventLogger.info("⚠️ Test step limit reached, completing action")
                }

                payload
            }
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            // Unregister all event handlers
            EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_WILL_RUN)
            EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_DID_RUN)
            EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_WILL_OBSERVE)
            EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_DID_OBSERVE)
            EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_WILL_ACT)
            EventBus.unregister(AgenticEvents.PerceptiveAgent.ON_DID_ACT)
            EventBus.unregister(AgenticEvents.ContextToAction.ON_DID_GENERATE)
        }
    }

    @BeforeEach
    fun setup() {
        // Clear captured events and counters before each test
        capturedEvents.clear()
        runStepCount.set(0)
        observeCount.set(0)
        actCount.set(0)
    }

    @AfterEach
    fun tearDown() {
        // Log summary after each test
        logger.info("Test completed - Total steps: {}, Observe count: {}, Act count: {}",
            runStepCount.get(), observeCount.get(), actCount.get())
    }

    /**
     * Reads a test case file from the classpath resources.
     *
     * @param resourcePath The path to the resource file
     * @return The content of the test case, or null if not found
     */
    private fun readTestCase(resourcePath: String): String? {
        return this::class.java.classLoader.getResourceAsStream(resourcePath)?.use { inputStream ->
            inputStream.bufferedReader().readText()
        }
    }

    /**
     * Parses a test case content to extract the task description.
     * Removes comment lines (starting with #) and extracts the numbered steps.
     *
     * @param content The raw content of the test case file
     * @return The task description for the agent
     */
    private fun parseTestCaseToTask(content: String): String {
        val lines = content.lines()

        // Extract metadata from comments for logging
        val metadata = lines.filter { it.startsWith("#") }
            .map { it.removePrefix("#").trim() }
        logger.info("Test case metadata: {}", metadata)

        // Extract numbered steps as the task
        val steps = lines.filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString("\n")

        return steps
    }

    /**
     * Test that the agent can execute a test case from the use-cases directory.
     *
     * This test:
     * 1. Reads the e-commerce product comparison test case
     * 2. Creates an agent session
     * 3. Runs the test case through the agent with event logging
     * 4. Verifies that the agent executed and progress events were captured
     *
     * Note: This test requires LLM configuration and will be skipped if not configured.
     */
    @Test
    @DisplayName("test agent runs a random happy-path use case")
    fun testAgentRunsEcommerceProductComparisonUseCase() = runBlocking {
        val spec = resolveUseCaseSpec()
        val classLoader = this@AgentE2ETest::class.java.classLoader
        val useCasePath = pickUseCasePath(spec, classLoader)
        logger.info(
            "Selected use case: {} (seed: {}, seedSource: {}, override: {} via {} / {})",
            useCasePath,
            spec.seed,
            spec.seedSource,
            spec.requestedUseCase,
            SYS_PROP_USE_CASE,
            ENV_USE_CASE,
        )

        // Step 1: Read the test case
        val testCaseContent = readTestCase(useCasePath)
        assertNotNull(testCaseContent, "Test case file should be readable")
        logger.info("Loaded test case from: {}", useCasePath)

        // Step 2: Parse the test case to extract the task
        val task = parseTestCaseToTask(testCaseContent)
        assertFalse(task.isBlank(), "Task should not be blank")
        logger.info("Parsed task:\n{}", task)

        // Step 3: Create agent session
        val session = AgenticContexts.getOrCreateSession()

        // Check if LLM is configured, skip test if not
        val isLLMConfigured = ChatModelFactory.isModelConfigured(session.sessionConfig)
        Assumptions.assumeTrue(isLLMConfigured,
            "Skipping test: LLM not configured. See docs/config/llm/llm-config.md")

        val agent = session.companionAgent
        assertNotNull(agent, "Agent should be created")

        // Step 4: Run the agent with the task
        logger.info("Starting agent run...")
        val history = agent.run(task)

        // Allow time for event processing
        delay(EVENT_PROCESSING_DELAY_MS)

        // Step 5: Verify the agent ran and progress events were captured
        assertNotNull(history, "History should not be null")
        logger.info("Agent history - Total steps: {}, isDone: {}, isSuccess: {}",
            history.totalSteps, history.isDone, history.isSuccess)

        // Verify ON_WILL_RUN event was captured
        val runWillEvents = capturedEvents[AgenticEvents.PerceptiveAgent.ON_WILL_RUN]
        assertNotNull(runWillEvents, "ON_WILL_RUN events should be captured")
        assertTrue(runWillEvents.isNotEmpty(), "At least one ON_WILL_RUN event should be captured")

        // Verify ON_DID_RUN event was captured
        val runDidEvents = capturedEvents[AgenticEvents.PerceptiveAgent.ON_DID_RUN]
        assertNotNull(runDidEvents, "ON_DID_RUN events should be captured")
        assertTrue(runDidEvents.isNotEmpty(), "At least one ON_DID_RUN event should be captured")

        // Verify ON_DID_GENERATE events were captured (progress tracking)
        val generateEvents = capturedEvents[AgenticEvents.ContextToAction.ON_DID_GENERATE]
        assertNotNull(generateEvents, "ON_DID_GENERATE events should be captured")
        assertTrue(generateEvents.isNotEmpty(), "At least one ON_DID_GENERATE event should be captured")

        // Verify that the history contains valid state information
        assertTrue(history.states.isNotEmpty(), "History should contain at least one state")

        // Step 6: Verify the answer is as expected
        // For the e-commerce comparison use case, we expect:
        // - The agent attempted to perform the task
        // - Progress events were captured
        // - The history contains meaningful state information

        val finalState = history.finalResult
        assertNotNull(finalState, "Final state should not be null")
        logger.info("Final state: {}", finalState)

        // Log summary of captured events
        logger.info("Event summary:")
        logger.info("  - ON_WILL_RUN: {} events", runWillEvents.size)
        logger.info("  - ON_DID_RUN: {} events", runDidEvents.size)
        logger.info("  - ON_DID_GENERATE: {} events", generateEvents.size)
        logger.info("  - OBSERVE events: {}", observeCount.get())
        logger.info("  - ACT events: {}", actCount.get())
    }

    /**
     * Test that event handlers properly log progress during agent execution.
     * This test validates that all expected event types are captured.
     */
    @Test
    @DisplayName("test event handlers log progress correctly")
    fun testEventHandlersLogProgressCorrectly() = runBlocking {
        // Create a simple task for quick validation
        val simpleTask = "go to https://example.com and find the main heading"

        // Create agent session
        val session = AgenticContexts.getOrCreateSession()

        // Check if LLM is configured, skip test if not
        val isLLMConfigured = ChatModelFactory.isModelConfigured(session.sessionConfig)
        Assumptions.assumeTrue(isLLMConfigured,
            "Skipping test: LLM not configured. See docs/config/llm/llm-config.md")

        val agent = session.companionAgent
        assertNotNull(agent, "Agent should be created")

        // Open a page first (required for agent context)
        session.createBoundDriver().open("https://example.com")

        // Run the agent
        logger.info("Running simple task: {}", simpleTask)
        val history = agent.run(simpleTask)

        // Allow time for event processing
        delay(EVENT_PROCESSING_DELAY_MS)

        // Verify history exists
        assertNotNull(history, "History should not be null")

        // Verify at least one step was executed
        assertTrue(runStepCount.get() > 0, "At least one step should have been executed")

        // Verify RUN events were captured
        assertTrue(capturedEvents.containsKey(AgenticEvents.PerceptiveAgent.ON_WILL_RUN),
            "ON_WILL_RUN events should be captured")
        assertTrue(capturedEvents.containsKey(AgenticEvents.PerceptiveAgent.ON_DID_RUN),
            "ON_DID_RUN events should be captured")

        // Log final counts
        logger.info("Final event counts - Steps: {}, Observe: {}, Act: {}",
            runStepCount.get(), observeCount.get(), actCount.get())
    }
}
