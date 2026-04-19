package ai.platon.pulsar.agentic.inference.action

import ai.platon.browser4.driver.chrome.dom.Locator
import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.pulsar.agentic.inference.AgentMessageList
import ai.platon.pulsar.agentic.inference.PromptBuilder.Companion.SINGLE_WEB_DRIVER_ACTION_GENERATION_PROMPT
import ai.platon.pulsar.agentic.inference.PromptBuilder.Companion.buildObserveResultSchema
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.ObserveElement
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationRenderer
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.ai.llm.PromptTemplate
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.external.BrowserChatModel
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files

open class TextToAction(
    val conf: ImmutableConfig
) {
    private val logger = getLogger(this)

    val chatModel: BrowserChatModel get() = ChatModelFactory.getOrCreate(conf)

    /**
     * Generate EXACT ONE WebDriver action with interactive elements.
     *
     * @param action The action descriptions
     * @param driver The driver to use to collect the context, such as interactive elements
     * @return The action description
     * */
    open suspend fun generateActions(
        action: String, driver: WebDriver, screenshotB64: String? = null
    ): List<ActionDescription> {
        require(driver is AbstractWebDriver)
        val snapshotService = requireNotNull(driver.snapshotService)

        val snapshotOptions = SnapshotOptions(
            maxDepth = 1000,
            includeAX = true,
            includeSnapshot = true,
            includeStyles = true,
            includePaintOrder = true,
            includeDOMRects = true,
            includeScrollAnalysis = true,
            includeVisibility = true,
            includeInteractivity = true
        )

        val browserUseState = snapshotService.getBrowserUseState(snapshotOptions = snapshotOptions)
        val domState = browserUseState.domState
        val agentState = AgentState(1, "", browserUseState = browserUseState)
        val toolCallExpressions = ToolCallSpecificationRenderer.render(includeCustomDomains = true)

        val promptTemplate = PromptTemplate(SINGLE_WEB_DRIVER_ACTION_GENERATION_PROMPT)
        val message = promptTemplate.render(
            mapOf(
                "ACTION_DESCRIPTIONS" to action,
                "TOOL_CALL_SPECIFICATION" to toolCallExpressions,
                "ARIA_ACCESSIBILITY_TREE" to domState.ariaSnapshot,
                "OUTPUT_SCHEMA_ACT" to buildObserveResultSchema(true),
            )
        )

        val messages = AgentMessageList()
        messages.addUser(message)

        val systemMessage = messages.systemMessages().joinToString("\n")
        val userMessage = messages.userMessages().joinToString("\n")
        val response = if (screenshotB64 != null) {
            chatModel.call(systemMessage, userMessage, null, screenshotB64, "image/jpeg")
        } else {
            chatModel.call(systemMessage, userMessage)
        }

        val mapper = jacksonObjectMapper()
        val content = response.content
        val elements: ModelObserveResponseElements = mapper.readValue(content)

        return toActionDescription(action, elements, agentState, response).toActionDescriptions()
    }

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
        val baseDir = AppPaths.get("tta")

        init {
            Files.createDirectories(baseDir)
        }

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
