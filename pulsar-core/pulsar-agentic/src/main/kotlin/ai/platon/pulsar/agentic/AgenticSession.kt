package ai.platon.pulsar.agentic

import ai.platon.pulsar.agentic.ai.SessionActExecutor
import ai.platon.pulsar.agentic.context.AbstractAgenticContext
import ai.platon.pulsar.agentic.skills.SkillManager
import ai.platon.pulsar.agentic.skills.Skills
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.ql.SessionConfig
import ai.platon.pulsar.ql.h2.AbstractH2SQLSession
import ai.platon.pulsar.ql.h2.H2SessionDelegate
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.skeleton.session.AbstractPulsarSession
import ai.platon.pulsar.skeleton.session.PulsarSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

interface AgenticSession : PulsarSession {

    val companionAgent: PerceptiveAgent

    /**
     * Perform an action described by [action].
     *
     * @param action The action description that describes the action to be performed by the webdriver.
     * @return The response from the model, though in this implementation, the return value is not explicitly used.
     */
    suspend fun performAct(action: ActionDescription): ToolCallResult

    /**
     * Instructs the webdriver to perform a series of actions based on the given prompt.
     * This function converts the prompt into a sequence of webdriver actions, which are then executed.
     *
     * @param actionDescriptions The textual prompt that describes the actions to be performed by the webdriver.
     * @return The response from the model, though in this implementation, the return value is not explicitly used.
     */
    suspend fun plainActs(actionDescriptions: String): List<ToolCallResult>

    /**
     * Execute a skill by name with the provided parameters.
     *
     * @param skillName The name of the skill to execute.
     * @param parameters Input parameters for the skill execution.
     * @param timeout Optional timeout override for the skill execution.
     * @return Result of the skill execution.
     */
    suspend fun executeSkill(
        skillName: String,
        parameters: Map<String, Any> = emptyMap(),
        timeout: Duration = 5.minutes
    ): ActResult {
        return Skills.execute(skillName, this, parameters)
    }

    /**
     * Get the skill manager for this session's context.
     * Can be used to register custom skills or query available skills.
     *
     * @return The global SkillManager instance.
     */
    fun getSkillManager(): SkillManager = Skills.getManager()
}

abstract class AbstractAgenticSession(
    context: AbstractPulsarContext,
    sessionConfig: VolatileConfig,
    id: Long = generateNextInProcessId()
) : AbstractPulsarSession(context, sessionConfig, id = id), AgenticSession

open class BasicAgenticSession(
    context: AbstractAgenticContext,
    sessionConfig: VolatileConfig,
    id: Long = generateNextInProcessId()
) : AbstractAgenticSession(context, sessionConfig, id) {

    override val companionAgent: PerceptiveAgent by lazy { ObserveActBrowserAgent(this) }

    private val executor by lazy { SessionActExecutor(this) }

    override suspend fun performAct(action: ActionDescription) = executor.performAct(action)

    override suspend fun plainActs(actionDescriptions: String) = executor.performActs(actionDescriptions)
}

open class AbstractAgenticQLSession(
    context: AbstractPulsarContext,
    sessionDelegate: H2SessionDelegate,
    config: SessionConfig
) : AbstractH2SQLSession(context, sessionDelegate, config), AgenticSession {

    override val companionAgent: PerceptiveAgent by lazy { ObserveActBrowserAgent(this) }

    private val executor by lazy { SessionActExecutor(this) }

    override suspend fun performAct(action: ActionDescription) = executor.performAct(action)

    override suspend fun plainActs(actionDescriptions: String) = executor.performActs(actionDescriptions)
}

open class AgenticQLSession(
    context: AbstractPulsarContext,
    sessionDelegate: H2SessionDelegate,
    config: SessionConfig
) : AbstractAgenticQLSession(context, sessionDelegate, config)
