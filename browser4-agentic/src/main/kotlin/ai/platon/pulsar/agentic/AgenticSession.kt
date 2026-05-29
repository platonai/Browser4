package ai.platon.pulsar.agentic

import ai.platon.pulsar.agentic.agents.RobustBrowserAgent
import ai.platon.pulsar.agentic.context.GenericAgenticContext
import ai.platon.pulsar.agentic.context.StaticAgenticContext
import ai.platon.pulsar.agentic.inference.SessionActExecutor
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.ql.SessionConfig
import ai.platon.pulsar.ql.h2.AbstractH2SQLSession
import ai.platon.pulsar.ql.h2.H2SessionDelegate
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext
import ai.platon.pulsar.skeleton.session.AbstractPulsarSession
import ai.platon.pulsar.skeleton.session.PulsarSession

interface AgenticSession : PulsarSession {

    val companionAgent: PerceptiveAgent

    /**
     * Instructs the webdriver to perform a series of actions based on the given prompt.
     * This function converts the prompt into a sequence of webdriver actions, which are then executed.
     *
     * @param action The textual prompt that describes the actions to be performed by the webdriver.
     * @return The response from the model, though in this implementation, the return value is not explicitly used.
     */
    suspend fun act(action: String): List<ToolCallResult>
}

abstract class AbstractAgenticSession(
    context: AbstractPulsarContext,
    sessionConfig: VolatileConfig,
    id: Long = nextId()
) : AbstractPulsarSession(context, sessionConfig, id = id), AgenticSession {
}

open class BasicAgenticSession(
    context: AbstractPulsarContext,
    sessionConfig: VolatileConfig,
    id: Long = nextId()
) : AbstractAgenticSession(context, sessionConfig, id) {
    private val logger = getLogger(GenericAgenticSession::class)

    override val companionAgent: PerceptiveAgent by lazy { createCompanionAgent() }

    private val executor by lazy { SessionActExecutor(this) }

    override suspend fun act(action: String) = executor.performActs(action)

    @Synchronized
    private fun createCompanionAgent(): RobustBrowserAgent {
        if (isActive) {
            runCatching { getOrCreateBoundDriver() }.onFailure { logger.warn("Failed to get or create bound driver for session $id", it) }
        }
        return RobustBrowserAgent(this).also { registerClosable(it) }
    }
}

open class GenericAgenticSession(
    context: GenericAgenticContext,
    sessionConfig: VolatileConfig,
    id: Long = nextId()
) : AbstractAgenticSession(context, sessionConfig, id) {
    private val logger = getLogger(GenericAgenticSession::class)

    override val companionAgent: PerceptiveAgent by lazy { createCompanionAgent() }

    private val executor by lazy { SessionActExecutor(this) }

    override suspend fun act(action: String) = executor.performActs(action)

    @Synchronized
    private fun createCompanionAgent(): RobustBrowserAgent {
        if (isActive) {
            runCatching { getOrCreateBoundDriver() }.onFailure { logger.warn("Failed to get or create bound driver for session $id", it) }
        }
        return RobustBrowserAgent(this).also { registerClosable(it) }
    }
}

open class AbstractAgenticQLSession(
    context: AbstractPulsarContext,
    sessionDelegate: H2SessionDelegate,
    config: SessionConfig
) : AbstractH2SQLSession(context, sessionDelegate, config), AgenticSession {
    private val logger = getLogger(BasicAgenticSession::class)

    override val companionAgent: PerceptiveAgent by lazy { createCompanionAgent() }

    private val executor by lazy { SessionActExecutor(this) }

    override suspend fun act(action: String) = executor.performActs(action)

    @Synchronized
    private fun createCompanionAgent(): RobustBrowserAgent {
        if (isActive) {
            runCatching { getOrCreateBoundDriver() }.onFailure { logger.warn("Failed to get or create bound driver for session $id", it) }
        }
        return RobustBrowserAgent(this).also { registerClosable(it) }
    }
}

open class AgenticQLSession(
    context: AbstractPulsarContext,
    sessionDelegate: H2SessionDelegate,
    config: SessionConfig
) : AbstractAgenticQLSession(context, sessionDelegate, config)

class StaticAgenticSession(
    context: StaticAgenticContext,
    sessionConfig: VolatileConfig,
): GenericAgenticSession(context, sessionConfig) {

}
