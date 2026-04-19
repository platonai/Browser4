package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.inference.action.TextToAction
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.ToolCallResult
import ai.platon.pulsar.agentic.tools.BasicToolCallExecutor
import ai.platon.pulsar.agentic.tools.builtin.BrowserToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.BrowserTabToolExecutor
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver

internal class SessionActExecutor(
    val session: AgenticSession,
    val driver: WebDriver,
    val conf: ImmutableConfig
) {
    constructor(session: AgenticSession) : this(
        session,
        session.getOrCreateBoundDriver(),
        session.sessionConfig
    )

    private val executors = listOf(
        BrowserTabToolExecutor(),
        BrowserToolExecutor()
    )
    private val toolCallExecutor = BasicToolCallExecutor(executors)

    suspend fun performAct(action: ActionDescription): ToolCallResult {
        val toolCall = action.toolCall ?: return ToolCallResult.NO_OP

        val evaluate = when (toolCall.domain) {
            "tab" -> toolCallExecutor.callFunctionOn(toolCall, driver)
            "browser" -> toolCallExecutor.callFunctionOn(toolCall, driver.browser)
            else -> throw IllegalArgumentException("❓ Unsupported domain: ${toolCall.domain} | $toolCall")
        }

        return ToolCallResult(
            evaluate = evaluate,
            message = "performAct",
            actionDescription = action,
        )
    }

    suspend fun performActs(actionDescriptions: String): List<ToolCallResult> {
        // Converts the prompt into a sequence of webdriver actions using TextToAction.
        val tta = TextToAction(conf)

        val actions = tta.generateActions(actionDescriptions, driver)

        return actions.map { performAct(it) }
    }
}
