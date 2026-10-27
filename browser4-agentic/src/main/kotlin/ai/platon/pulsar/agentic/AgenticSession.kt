package ai.platon.pulsar.agentic

import ai.platon.pulsar.agentic.agents.RobustBrowserAgent
import ai.platon.pulsar.agentic.context.GenericAgenticContext
import ai.platon.pulsar.agentic.context.StaticAgenticContext
import ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext
import ai.platon.pulsar.agentic.inference.SessionActExecutor
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.common.config.VolatileConfig
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
    // createBoundDriver() is inherited from AbstractPulsarSession, which
    // already swaps in Browser4WebDriver via Browser4WebDriver.from().
    // No override needed here.
}

/**
 * The three [AgenticSession] implementations below share one rule: creating the
 * companion agent must **not** touch the browser.
 *
 * A driver is created lazily by the first page tool that needs it
 * ([ai.platon.pulsar.agentic.agents.BasicBrowserAgent.activeDriver] /
 * [ai.platon.pulsar.agentic.tools.AgentToolManager.driver]). Binding one here used
 * to launch a default browser for every agent task — including tasks that never
 * open a page (`agent run --coding`, or a `b4.*` task that drives an attached
 * browser through the CLI) — and for every session materialized just to list tools.
 */
open class AbstractAgenticQLSession(
    context: AbstractPulsarContext,
    sessionDelegate: H2SessionDelegate,
    config: SessionConfig
) : AbstractH2SQLSession(context, sessionDelegate, config), AgenticSession {

    override val companionAgent: PerceptiveAgent by lazy { createCompanionAgent() }

    private val executor by lazy { SessionActExecutor(this) }

    override suspend fun act(action: String) = executor.performActs(action)

    @Synchronized
    private fun createCompanionAgent(): RobustBrowserAgent {
        return RobustBrowserAgent(this).also { registerClosable(it) }
    }
}

/**
 * An [AgenticQLSession] is a [ai.platon.pulsar.ql.SQLSession] that also implements [AgenticSession], allowing it to
 * perform agentic actions in addition to SQL operations.
 *
 * > **NOTE:** This session is designed to work with H2 databases and is created by SQL engine, it is not intended to
 * > be instantiated directly by users.
 * */
open class AgenticQLSession(
    context: AbstractBrowser4SQLContext,
    sessionDelegate: H2SessionDelegate,
    config: SessionConfig
) : AbstractAgenticQLSession(context, sessionDelegate, config)

open class BasicAgenticSession(
    context: AbstractPulsarContext,
    sessionConfig: VolatileConfig,
    id: Long = nextId()
) : AbstractAgenticSession(context, sessionConfig, id) {

    override val companionAgent: PerceptiveAgent by lazy { createCompanionAgent() }

    private val executor by lazy { SessionActExecutor(this) }

    override suspend fun act(action: String) = executor.performActs(action)

    @Synchronized
    private fun createCompanionAgent(): RobustBrowserAgent {
        return RobustBrowserAgent(this).also { registerClosable(it) }
    }
}

open class GenericAgenticSession(
    context: GenericAgenticContext,
    sessionConfig: VolatileConfig,
    id: Long = nextId()
) : AbstractAgenticSession(context, sessionConfig, id) {

    override val companionAgent: PerceptiveAgent by lazy { createCompanionAgent() }

    private val executor by lazy { SessionActExecutor(this) }

    override suspend fun act(action: String) = executor.performActs(action)

    @Synchronized
    private fun createCompanionAgent(): RobustBrowserAgent {
        return RobustBrowserAgent(this).also { registerClosable(it) }
    }
}

class StaticAgenticSession(
    context: StaticAgenticContext,
    sessionConfig: VolatileConfig,
) : GenericAgenticSession(context, sessionConfig) {

}
