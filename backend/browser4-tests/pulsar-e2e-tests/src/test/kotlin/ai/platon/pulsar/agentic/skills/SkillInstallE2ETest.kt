@file:Suppress("UNCHECKED_CAST")

package ai.platon.pulsar.agentic.skills

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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end test for skill searching and installation.
 *
 * This test validates the complete skill discovery and installation workflow by:
 * 1. Creating an agentic session with a browser-based agent
 * 2. Running a task that instructs the agent to search for and install skills
 * 3. Tracking agent execution via event handlers
 * 4. Verifying the agent completed its task and progress events were captured
 *
 * The skills to be searched and installed:
 * - self-improving-agent (自我迭代)
 * - skill-creator (技能创造)
 * - find-skills (发现新技能)
 * - skills-vetter (保证技能安全)
 * - automation-workflows (把技能串起来当工作流)
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
 * ./mvnw test -P all-modules -pl :pulsar-e2e-tests -am -Dtest=SkillInstallE2ETest
 * ```
 *
 * Or run all E2E tests:
 * ```bash
 * ./mvnw test -P all-modules -Dgroups=E2ETest -Dsurefire.excludedGroups="" -DfailIfNoTests=false
 * ```
 */
@Tag("E2ETest")
@Tag("Slow")
@Tag("ManualOnly")
@Tag("skills")
@Disabled("ManualOnly")
class SkillInstallE2ETest {

    private val logger = getLogger(this)

    companion object {
        /**
         * Maximum steps before auto-completing for test scenarios.
         * Skill installation tasks require more steps than simple navigation.
         */
        private const val MAX_TEST_STEPS = 35
        private const val EVENT_PROCESSING_DELAY_MS = 500L

        private val capturedEvents = ConcurrentHashMap<String, MutableList<Map<String, Any?>>>()
        private val runStepCount = AtomicInteger(0)
        private val observeCount = AtomicInteger(0)
        private val actCount = AtomicInteger(0)
        private val eventLogger = getLogger("SkillInstallE2ETest.EventLogger")

        @BeforeAll
        @JvmStatic
        fun setupEventHandlers() {
            // Register event handlers for progress logging

            AgentEventBus.agentEventHandlers?.agentFlowHandlers?.onWillObserve?.addLast { options ->
                eventLogger.debug("👀 Will observe - instruction: {}", Strings.compactInline(options.instruction))
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
     * Test that the agent can search for and install skills.
     *
     * This test:
     * 1. Creates an agent session
     * 2. Provides a task to search for and install skills using a browser
     * 3. Runs the agent with the task
     * 4. Verifies the agent executed and progress events were captured
     * 5. Checks the agent history for meaningful state information
     *
     * Note: This test requires LLM configuration and will be skipped if not configured.
     */
    @Test
    @DisplayName("test agent searches and installs skills")
    fun testAgentSearchesAndInstallsSkills() = runBlocking {
        // Step 1: Create agent session
        val session = AgenticContexts.getOrCreateSession()

        // Check if LLM is configured, skip test if not
        val isLLMConfigured = ChatModelFactory.isModelConfigured(session.sessionConfig)
        Assumptions.assumeTrue(isLLMConfigured,
            "Skipping test: LLM not configured. See docs/config/llm/llm-config.md")

        val agent = session.companionAgent
        assertNotNull(agent, "Agent should be created")

//        skill-creator（技能创造）
//        find-skills（发现新技能）
//        skills-vetter（保证技能安全）
//        automation-workflows（把技能串起来当工作流）

        // Step 2: Define the skill installation task
        val task = """
            Search and install the following SKILLS:

            self-improving-agent

            You should search for the skills using a browser, find the installation instructions, and then install them.
            After installation, verify that they are working correctly by running a simple test command for each skill.
            Document the entire process, including any challenges faced and how they were overcome.
        """.trimIndent()

        // Step 3: Run the agent with the task
        logger.info("Starting skill installation agent run...")
        logger.info("Task:\n{}", task)
        val history = agent.run(task)

        // Allow time for event processing
        delay(EVENT_PROCESSING_DELAY_MS)

        // Step 4: Verify the agent ran and progress events were captured
        assertNotNull(history, "History should not be null")
        logger.info("Agent history - Total steps: {}, isDone: {}, isSuccess: {}",
            history.totalSteps, history.isDone, history.isSuccess)

        // Verify ON_WILL_RUN event was captured
        val runWillEvents = capturedEvents[AgenticEvents.PerceptiveAgent.ON_WILL_RUN]
        assertNotNull(runWillEvents, "ON_WILL_RUN events should be captured")
        assertTrue(runWillEvents!!.isNotEmpty(), "At least one ON_WILL_RUN event should be captured")

        // Verify ON_DID_RUN event was captured
        val runDidEvents = capturedEvents[AgenticEvents.PerceptiveAgent.ON_DID_RUN]
        assertNotNull(runDidEvents, "ON_DID_RUN events should be captured")
        assertTrue(runDidEvents!!.isNotEmpty(), "At least one ON_DID_RUN event should be captured")

        // Verify ON_DID_GENERATE events were captured (progress tracking)
        val generateEvents = capturedEvents[AgenticEvents.ContextToAction.ON_DID_GENERATE]
        assertNotNull(generateEvents, "ON_DID_GENERATE events should be captured")
        assertTrue(generateEvents!!.isNotEmpty(), "At least one ON_DID_GENERATE event should be captured")

        // Verify that the history contains valid state information
        assertTrue(history.states.isNotEmpty(), "History should contain at least one state")

        // Step 5: Verify the final state
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
     * Test that the agent can run a simpler skill search task.
     *
     * This test uses a smaller scope to verify the event tracking mechanism
     * works correctly for skill-related tasks.
     */
    @Test
    @DisplayName("test agent event tracking for skill search task")
    fun testAgentEventTrackingForSkillSearchTask() = runBlocking {
        // Create agent session
        val session = AgenticContexts.getOrCreateSession()

        // Check if LLM is configured, skip test if not
        val isLLMConfigured = ChatModelFactory.isModelConfigured(session.sessionConfig)
        Assumptions.assumeTrue(isLLMConfigured,
            "Skipping test: LLM not configured. See docs/config/llm/llm-config.md")

        val agent = session.companionAgent
        assertNotNull(agent, "Agent should be created")

        // A simpler task focused on just searching for skills
        val task = "Search for the 'find-skills' SKILL and describe its installation instructions."

        // Run the agent
        logger.info("Running skill search task: {}", task)
        val history = agent.run(task)

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
