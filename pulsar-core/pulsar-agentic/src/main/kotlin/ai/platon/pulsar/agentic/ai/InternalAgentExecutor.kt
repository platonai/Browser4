package ai.platon.pulsar.agentic.ai

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.ai.support.ActionExecutionService
import ai.platon.pulsar.agentic.ai.support.ToolCallExecutor
import ai.platon.pulsar.agentic.ai.tta.ActionDescription
import ai.platon.pulsar.agentic.ai.tta.ToolCallResults
import ai.platon.pulsar.agentic.ai.tta.TextToAction
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.ai.ActResult
import ai.platon.pulsar.skeleton.ai.ActionOptions
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver

internal class InternalAgentExecutor(
    val session: AgenticSession,
    val driver: WebDriver,
    val conf: ImmutableConfig
) {
    constructor(session: AgenticSession): this(
        session,
        session.getOrCreateBoundDriver(),
        session.sessionConfig
    )

    private val actionExecutionService = ActionExecutionService()

    val agent = BrowserPerceptiveAgent(driver, session)

    suspend fun resolve(action: String): ActResult {
        return agent.resolve(action)
    }

    suspend fun act(action: String): ActResult {
        return act(ActionOptions(action = action))
    }

    suspend fun act(action: ActionOptions): ActResult {
        return agent.act(action)
    }

    suspend fun performAct(action: ActionDescription): ToolCallResults {
        val toolCall = action.toolCall
        if (action.expressions.isEmpty() && toolCall == null) {
            return ToolCallResults(action = action)
        }

        val result = if (toolCall != null) {
            actionExecutionService.execute(toolCall, driver)
        } else {
            action.expressions.take(1).map<String, Any?> { expr -> 
                actionExecutionService.execute(expr, driver) 
            }.firstOrNull()
        }

        return ToolCallResults(
            action.expressions,
            functionResults = listOf(result),
            action = action
        )
    }

    suspend fun execute(action: ActionDescription) = performAct(action)

    @Deprecated("Use act instead", replaceWith = ReplaceWith("act(action)"))
    suspend fun instruct(prompt: String): ToolCallResults {
        // Converts the prompt into a sequence of webdriver actions using TextToAction.
        val tta = TextToAction(conf)

        val action = tta.generate(prompt, driver)

        return performAct(action)
    }
}
