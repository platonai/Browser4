package ai.platon.pulsar.agentic.inference.action

import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.ObserveElement
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.api.model.Locator
import ai.platon.pulsar.api.model.MergedDOMTreeNode
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.external.ModelResponse
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Parses the model's observe response into [ActionDescription]s.
 *
 * The one-shot `generateActions(action, driver)` entry point was removed on
 * 2026-11-12 together with `SessionActExecutor` / `AgenticSession.act()`: it
 * called the model directly, parsed the answer with a raw `jacksonObjectMapper()`
 * (no code-fence repair, no unknown-property tolerance) and executed the tool
 * calls without the tool loop's timeouts, error semantics or observability.
 * Model responses are now parsed by [modelResponseToActionDescription] (the
 * tolerant path used by [ContextToAction]) and executed by the CLI tool loop.
 */
open class TextToAction(
    val conf: ImmutableConfig
) {
    private val logger = getLogger(this)

    @Deprecated("Use the CLI tool-loop engine path instead")
    fun modelResponseToActionDescription(
        instruction: String, agentState: AgentState, modelResponse: ModelResponse
    ): ActionDescription {
        try {
            val actionDescription = modelResponseToActionDescription0(instruction, agentState, modelResponse)

            val revised = normalizeActionDescription(actionDescription)

            agentState.actionDescription = revised

            return revised
        } catch (e: Exception) {
            logger.warn("Exception while parsing model response", e)
            return ActionDescription(instruction, modelResponse = modelResponse, exception = e)
        }
    }

    @Deprecated("Use the CLI tool-loop engine path instead")
    private fun modelResponseToActionDescription0(
        instruction: String, agentState: AgentState, modelResponse: ModelResponse
    ): ActionDescription {
        val modelResponse = reviseModelResponse(modelResponse)
        val content = modelResponse.content.trim()

        val contentStart = Strings.compactWhitespaces(content.take(30))

        val mapper = pulsarObjectMapper()
        return when {
            contentStart.contains("\"taskComplete\"") -> {
                val complete: ModelObserveResponseComplete = mapper.readValue(content)
                ActionDescription(
                    instruction = instruction,
                    isDecidedComplete = complete.taskComplete,
                    errorCause = complete.errorCause,
                    summary = complete.summary,
                    keyFindings = complete.keyFindings,
                    nextSuggestions = complete.nextSuggestions,
                    gates = complete.gates,
                    filesChanged = complete.filesChanged,
                    deletedFiles = complete.deletedFiles,
                    problems = complete.problems,
                    modelResponse = modelResponse
                )
            }

            contentStart.contains("\"elements\"") -> {
                val elements: ModelObserveResponseElements = mapper.readValue(content)
                toActionDescription(instruction, elements, agentState, modelResponse)
            }

            else -> ActionDescription(instruction, modelResponse = modelResponse)
        }
    }

    private fun reviseModelResponse(modelResponse: ModelResponse): ModelResponse {
        var content = modelResponse.content.trim()

        val errorMessage =
            "Non-compliant response. You must output in a valid JSON format as required by the `## Output Format`. " +
                    "The client has been corrected, but you must strictly adhere to the format requirements for all future outputs."
        val heading20 = content.take(30)
        val tailing20 = content.takeLast(30)

        val modelError = when {
            heading20.contains("[{\"elements") -> errorMessage
            heading20.contains("output_act") -> errorMessage
            tailing20.contains("/output_act") -> errorMessage
            tailing20.contains("output_act") -> errorMessage
            else -> null
        }

        if (modelError != null) {
            val jsonStart = content.indexOf('{')
            val jsonEnd = content.lastIndexOf('}')
            if (jsonStart in 0..<jsonEnd) {
                content = content.substring(jsonStart, jsonEnd + 1)
            } else {
                logger.warn("Unable to extract JSON from model response; keeping original content")
            }
        }

        return if (modelError != null) {
            logger.info("""🖌️ Model response revised""")
            modelResponse.copy(content = content, modelError = modelError)
        } else modelResponse
    }

    fun normalizeActionDescription(action: ActionDescription): ActionDescription {
        requireNotNull(action.modelResponse) { "ModelResponse is required to reviseActionDescription" }

        if (action.exception != null) {
            return action
        }

        if (action.isDecidedComplete) {
            return action
        }

        val observeElements = action.observeElements?.map { normalizeObserveElement(it, action) }
        return action.copy(observeElements = observeElements)
    }

    private fun normalizeObserveElement(observeElement: ObserveElement, action: ActionDescription): ObserveElement {
        requireNotNull(action.modelResponse) { "ModelResponse is required to reviseObserveElement" }
        if (action.exception != null) {
            return observeElement
        }

        val agentState = requireNotNull(action.agentState) { "Agent state has to be available to reviseObserveElement" }
        val toolCall = observeElement.toolCall ?: return observeElement

        // 2. revise selector
        val domain = toolCall.domain
        val method = toolCall.method

        // The `driver`, `WebDriver` domains are deprecated and replaced by `tab`
        // Only when the `tab` domain requires a locator for tool calls
        if (!domain.equals("tab", true)) {
            return observeElement
        }

        val ref = observeElement.ref
        val arguments = toolCall.arguments

        var node: MergedDOMTreeNode? = null
        if (!ref.isNullOrBlank()) {
            val locator = if (ref.startsWith("e")) {
                // playwright compatible format: `e123`, where 123 is the backend node id
                Locator(Locator.Type.BACKEND_NODE_ID, ref.substring(1).trim())
            } else {
                // backward compatibility: try to find FBN locator with ref as simplified backend node id
                agentState.browserUseState.domState.getAbsoluteFBNLocator(ref)
            }

            if (locator != null) {
                node = agentState.browserUseState.domState.locatorMap[locator]
                if ("selector" in arguments) {
                    // revise selector
                    arguments["selector"] = locator.absoluteSelector
                }
            } else {
                logger.warn("Locator not found. method={}, locator={}", method, locator)
            }
        }

        // CSS friendly expression
        val cssSelector = node?.cssSelector()
        val expression = toolCall.weakTypeExpression
        val cssFriendlyExpression = if (ref != null && cssSelector != null) {
            expression.replace(ref, cssSelector)
        } else null

        // 3. copy new object
        val revisedObserveElement = observeElement.copy(
            node = node,
            backendNodeId = node?.backendNodeId,
            toolCall = toolCall,
            cssSelector = cssSelector,
            cssFriendlyExpression = cssFriendlyExpression,
        )

        return revisedObserveElement
    }

    companion object {
        @Deprecated("Use the CLI tool-loop engine path instead")
        fun toActionDescription(
            instruction: String,
            elements: ModelObserveResponseElements,
            agentState: AgentState,
            response: ModelResponse
        ): ActionDescription {
            val observeElements = elements.elements?.map { toObserveElement(it, response) } ?: emptyList()
            return ActionDescription(
                instruction,
                observeElements = observeElements,
                agentState = agentState,
                modelResponse = response
            )
        }

        @Deprecated("Use the CLI tool-loop engine path instead")
        fun toObserveElement(ele: ModelObserveResponseElement, response: ModelResponse): ObserveElement {
            val arguments = ele.arguments
                ?.mapNotNull { arg -> arg?.get("name") to arg?.get("value") }
                ?.filter { it.first != null }
                ?.associate { it.first.toString() to it.second }

            val observeElement = ObserveElement(
                ref = ele.ref?.removeSurrounding("[", "]"),

                screenshotContentSummary = ele.screenshotContentSummary,
                currentPageContentSummary = ele.currentPageContentSummary,
                evaluationPreviousGoal = ele.evaluationPreviousGoal,
                nextGoal = ele.nextGoal,
                thinking = ele.thinking,

                toolCall = ToolCall(
                    domain = ele.domain ?: "",
                    method = ele.method ?: "",
                    arguments = arguments?.toMutableMap() ?: mutableMapOf(),
                ),

                modelResponse = response.content,
            )

            return observeElement
        }
    }
}
