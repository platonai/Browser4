package ai.platon.pulsar.agentic.inference

import ai.platon.browser4.driver.chrome.dom.DOMSerializer
import ai.platon.browser4.driver.chrome.dom.model.TabState
import ai.platon.pulsar.agentic.inference.action.OBSERVE_RESPONSE_COMPLETE_SCHEMA
import ai.platon.pulsar.agentic.inference.action.OBSERVE_RESPONSE_ELEMENT_SCHEMA
import ai.platon.pulsar.agentic.inference.history.DefaultHistoryRenderStrategy
import ai.platon.pulsar.agentic.inference.history.HistoryRenderStrategy
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.agentic.prompts.buildMainSystemPromptV1
import ai.platon.pulsar.agentic.prompts.buildToolUseSections
import ai.platon.pulsar.agentic.tools.specs.ToolSpecification
import ai.platon.pulsar.common.KStrings
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.ai.llm.PromptTemplate
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.serialize.json.Pson

/**
 * Description:
 * Builder for language-localized prompt snippets used by agentic browser tasks.
 *
 * Prompt key points:
 * - Locale-aware (CN/EN) output
 * - Produces structured fragments for system/user roles
 * - Minimizes extra text to steer LLM behavior
 */
class PromptBuilder(
    private val historyRenderStrategy: HistoryRenderStrategy = DefaultHistoryRenderStrategy()
) {

    companion object {
        /**
         * The working language for the prompt. English is generally better for LLM understanding.
         * */
        const val workingLanguage = "EN"

        fun buildResponseSchema(): String {
            return buildObserveResultSchema(returnAction = true)
        }

        /**
         * Build the JSON schema for observing results.
         *
         * See [ai.platon.pulsar.agentic.inference.action.ModelObserveResponseElements]
         * */
        fun buildObserveResultSchema(returnAction: Boolean): String {
            // English is better for LLM to understand JSON
            val schema1 = OBSERVE_RESPONSE_ELEMENT_SCHEMA

            val schema2 = """
{
  "elements": [
    {
      "ref": string,
      "description": string
    }
  ]
}
""".let { Strings.compactWhitespaces(it) }

            return if (returnAction) schema1 else schema2
        }

        val TOOL_CALL_RULE_CONTENT_V1 = """
Tool call rules:

- `domain`: tool domain such as `tab`, `browser`, or `skill.debug.scraping`; subdomains use dots.
- `method`: tool method such as `click`, `fill`, or `extract`.
- When selecting a node, always set `selector` to the same value as `ref` in aria snapshot, e.g., click(selector=`e123`).
- `ref` must exactly match either the interactive element list or the relevant accessibility-tree node attributes.
- Output JSON only. Do not add any explanatory text.
- Read all open-tab information from `## Browser State`.
- Open a new tab for side lookups instead of reusing the current tab.
- Use `click(selector, "Ctrl")` to open a link in a new tab. On macOS, `Ctrl` maps to `Meta`.
- If a page opens in a new tab, switch with `browser.switchTab(tabId: String)` using the `tabId` from `## Browser State`.
- If an expected element is missing, try refresh, scroll, or back.
- When entering text, do not pre-scroll or pre-focus. You may still need to press Enter, click Search, or choose a dropdown option.
- If typing changes the page, decide whether new elements now require interaction.
- If a page change interrupted a planned sequence, continue the unfinished steps on the next turn.
- Keep the final objective in `<user_request>` as the top priority. Explicit user steps override your own plan.
- If `<user_request>` includes concrete filters such as type, rating, price, or location, use page filters when available.
- Avoid login unless it is necessary, and never attempt login without credentials.
- Classify the task first:
  1. **Specific step-by-step instructions**: follow them exactly and do not skip steps.
  2. **Open-ended task**: plan autonomously, and if blocked by login or CAPTCHA, try alternative ways to complete the goal.
""".trimIndent()

        val TOOL_CALL_RULE_CONTENT_V2 = """
Tool call rules:

- `domain`: tool domain such as `tab`, `browser`, or `skill.debug.scraping`; subdomains use dots.
- The `ref` attribute in aria snapshot is a unique node reference, format: [ref=e123], prefer `ref` to locate DOM nodes when possible.
  - click a node with [ref=e123] -> tab.click('e123')
  - fill a node with [ref=e123] -> tab.fill('e123', 'hello')
- Output JSON only. No explanatory text.
- When entering text, do not pre-scroll or pre-focus. You may still need to press Enter, click Search, or choose a dropdown option.
- If typing changes the page, decide whether new elements now require interaction.
- Keep the final objective in `<user_request>` as the top priority. Explicit user steps override your own plan.
- Avoid login unless it is necessary, and never attempt login without credentials.
- Classify the task first:
  1. **Specific step-by-step instructions**: follow them exactly and do not skip steps.
  2. **Open-ended task**: plan autonomously, and if blocked by login or CAPTCHA, try alternative ways to complete the goal.
""".trimIndent()

        val TOOL_CALL_RULE_CONTENT = TOOL_CALL_RULE_CONTENT_V2

        /**
         * TODO: move to skill
         * */
        val EXTRACTION_TOOL_NOTE_CONTENT = """
Use `agent.extract` only for advanced extraction cases that cannot be satisfied by `textContent` or `selectFirstTextOrNull`.

Parameters:

1. `instruction`: clearly describe the extraction goal and constraints.
2. `schema`: define the required JSON output shape using the structure below.
3. `instruction` defines intent, while `schema` defines structure. If they conflict, follow `schema`.

Schema structure:
```
class ExtractionField(
    val name: String,
    val type: String = "string",                 // JSON schema primitive or 'object' / 'array'
    val description: String,
    val required: Boolean = true,
    val objectMemberProperties: List<ExtractionField> = emptyList(), // define the schema of member properties if type == object
    val arrayElements: ExtractionField? = null                    // define the schema of elements if type == array
)
class ExtractionSchema(val fields: List<ExtractionField>)
```

Example:
```
{
  "fields": [
    {
      "name": "product",
      "type": "object",
      "description": "Product info",
      "objectMemberProperties": [
        {
          "name": "name",
          "type": "string",
          "description": "Product name",
          "required": true
        },
        {
          "name": "variants",
          "type": "array",
          "required": false,
          "arrayElements": {
            "name": "variant",
            "type": "object",
            "required": false,
            "objectMemberProperties": [
              { "name": "sku", "type": "string", "required": false },
              { "name": "price", "type": "number", "required": false }
            ]
          }
        }
      ]
    }
  ]
}
```

"""

        const val SINGLE_WEB_DRIVER_ACTION_GENERATION_PROMPT = """
Choose the single best tool call for the requested browser action.

## Action Description

{{ACTION_DESCRIPTIONS}}

---

## Tool List

```kotlin
{{TOOL_CALL_SPECIFICATION}}
```

---

## ARIA Snapshot

{{ARIA_ACCESSIBILITY_TREE}}

---

## Output Requirements

- Output JSON only. No extra text.
- `domain` must be `tab`.
- `method` and `arguments` must match the function expressions in `## Tool List`.

Output format:
{{OUTPUT_SCHEMA_ACT}}

---

        """

        val OBSERVE_GUIDE_OUTPUT_SCHEMA = """
{
  "elements": [
    {
      "ref": "Web page node reference, e.g., `e123`",
      "description": "Description of the current node selected and tool selection",
      "screenshotContentSummary": "Summary of the current screenshot content if provided",
      "currentPageContentSummary": "Summary of the current web page text content, based on the aria snapshot or web content extraction results",
      "memory": "1-3 specific sentences describing this step and the overall progress. This should include information helpful for future progress tracking, such as the number of pages visited or items found.",
      "thinking": "A structured <think>-style reasoning block.",
      "evaluationPreviousGoal": "A concise one-sentence analysis of the previous action, clearly stating success, failure, or uncertainty.",
      "nextGoal": "A clear one-sentence statement of the next direct goal and action to take."
    }
  ]
}
        """.trimIndent()

        val OBSERVE_GUIDE_OUTPUT_SCHEMA_RETURN_ACTIONS = """
{
  "elements": [
    {
      "ref": "Web page node reference",
      "description": "Description of the current selected node and tool selection",
      "domain": "Tool domain, such as `tab`",
      "method": "Method name, such as `click`",
      "arguments": [
        {
          "name": "Parameter name, such as `selector`",
          "value": "Parameter value, such as `ref123`"
        }
      ],
      "screenshotContentSummary": "Summary of the current screenshot content if provided",
      "currentPageContentSummary": "Summary of the current web page text content, based on the aria snapshot or web content extraction results",
      "memory": "1-3 specific sentences describing this step and the overall progress. This should include information helpful for future progress tracking, such as the number of pages visited or items found.",
      "thinking": "A structured <think>-style reasoning block.",
      "evaluationPreviousGoal": "A concise one-sentence analysis of the previous action, clearly stating success, failure, or uncertainty.",
      "nextGoal": "A clear one-sentence statement of the next direct goal and action to take."
    }
  ]
}
        """.trimIndent()

        val OBSERVE_GUIDE_SYSTEM_MESSAGE = """
## Goal

Identify the page elements that best match the observation request and support browser automation.

You will receive:
- an instruction describing the target element
- a list of interactive page elements
- a hierarchical accessibility tree that combines DOM and accessibility information

Return an array of matching elements, or an empty array when no suitable match exists.

---

## Browser State

Browser state includes:
- the current URL
- all open tabs with their IDs

---

## Output Requirements

- Use the JSON format below exactly and output JSON only.
- Return at most one element, and never leave `domain` or `method` empty.

Output format:
{{OUTPUT_SCHEMA_PLACEHOLDER}}

---

${buildToolUseSections()}

"""

        fun compactPromptForHuman(prompt: String, maxWidth: Int = 200): String {
            val boundaries = """
Identify the page elements that best match the observation request
Return an array of matching elements

## ARIA Snapshot
---
            """.trimIndent()

            val boundaryPairs = boundaries.split("\n").filter { it.isNotBlank() }.chunked(2).map { it[0] to it[1] }

            val compacted = KStrings.replaceContentInSections(prompt, boundaryPairs, "\n...\n\n")

            return Strings.compactInline(compacted, maxWidth)
        }
    }

    fun buildOperatorSystemPrompt(): String {
        return """
${buildMainSystemPromptV1()}
        """.trimIndent()
    }

    fun buildMultistepAgentMessageListAll(context: ExecutionContext): AgentMessageList {
        // Prepare messages for model
        val messages = AgentMessageList()

        initObserveUserInstruction(context.instruction, messages)

        buildMultistepMessageListStart(context, context.stateHistory, messages)

        // browser state, viewport info, interactive elements, DOM
        buildBrowserStateMessageForBrowserInteraction(messages, context)

        return messages
    }

    fun buildSingleObserveMessageListAll(params: ObserveParams, context: ExecutionContext): AgentMessageList {
        // Prepare messages for model
        val messages = AgentMessageList()

        // observe guide
        buildSingleObserveSystemPrompt(messages, params)
        // browser state, viewport info, interactive elements, DOM
        buildBrowserStateMessageForBrowserInteraction(messages, context)

        return messages
    }

    fun buildMultistepMessageListStart(
        context: ExecutionContext, stateHistory: AgentHistory,
        messages: AgentMessageList,
    ): AgentMessageList {
        val instruction = context.instruction

        val systemMsg = buildOperatorSystemPrompt()

        messages.addSystem(systemMsg)
        messages.addLastIfAbsent("user", buildUserRequestMessage(instruction), name = "user_request")
        messages.addUser(buildAgentStateHistoryMessage(stateHistory, context.stateHistoryPath))
        if (context.screenshotB64 != null) {
            messages.addUser(buildBrowserVisionInfo())
        }

        val prevTCResult = context.agentState.prevState?.toolCallResult
        if (prevTCResult != null) {
            messages.addUser(buildPrevToolCallResultMessage(context))
        }

        return messages
    }

    fun buildObserveGuideSystemExtraPrompt(userProvidedInstructions: String?): SimpleMessage? {
        if (userProvidedInstructions.isNullOrBlank()) return null

        val content = """
## User Preferences

Keep these user-provided instructions in mind while acting. Ignore them if they are unrelated to the current task.

User instructions:
$userProvidedInstructions

---

""".trim()

        return SimpleMessage("system", content)
    }

    fun buildAgentStateHistoryMessage(agentHistory: AgentHistory, stateHistoryPath: String? = null): String {
        return historyRenderStrategy.render(agentHistory, stateHistoryPath)
    }

    fun buildBrowserVisionInfo(): String {
        val visionInfo = """
## Visual Evidence

- The screenshot below was captured after a browser-interaction action (e.g., click, navigate, scroll, fill, switchTab).
- Screenshots are not captured after non-browser actions (e.g., file I/O, data extraction, system queries) to save tokens.
- If you need visual confirmation after a non-browser action, use `tab.ariaSnapshot()` or request a screenshot explicitly.

[Current page screenshot provided as base64 image]

---

""".trimIndent()

        return visionInfo
    }

    fun buildPrevToolCallResultMessage(context: ExecutionContext): String {
        val agentState = requireNotNull(context.agentState)
        val toolCallResult = requireNotNull(context.agentState.prevState?.toolCallResult)
        val evaluate = toolCallResult.evaluate
        val evalResult = evaluate.value?.toString()
        val exception = evaluate.exception?.cause
        val evalMessage = when {
            exception != null -> "[Execution Error]\n" + exception.brief()
            evalResult.isNullOrBlank() -> "[Execution Succeeded]"
            else -> "[Execution Succeeded] Output: $evalResult"
        }.let { Strings.compactInline(it, 5000) }
        val help = evaluate.exception?.help?.takeIf { it.isNotBlank() }
        val helpMessageOrEmpty = help?.let { "Help:\n```\n$it\n```" } ?: ""
        val lastModelError = agentState.actionDescription?.modelResponse?.modelError
        val lastModelErrorOrEmpty = if (lastModelError != null) {
            """
Previous model error:

$lastModelError

        """
        } else ""

        return """
## Previous Step Result

Previous action: ${agentState.prevState?.actionDescription?.pseudoExpression}
Expected result: ${agentState.prevState?.nextGoal}

Execution result:
```
$evalMessage
```

$helpMessageOrEmpty
$lastModelErrorOrEmpty
---
        """.trimIndent()
    }

    fun buildUserRequestMessage(userRequest: String): String {
        val msg = """
# Current Task

## User Request
<user_request>
$userRequest
</user_request>

---

                """.trimIndent()

        return msg
    }

    fun initExtractUserInstruction(instruction: String? = null): String {
        if (instruction.isNullOrBlank()) {
            return """
Extract the key data structure from the page.

- The source data is the accessibility-tree DOM nodes for one viewport-height chunk at a time.

""".trimIndent()
        }

        return instruction
    }

    fun buildExtractSystemPrompt(userProvidedInstructions: String? = null): SimpleMessage {
        val userInstructions = buildObserveGuideSystemExtraPrompt(userProvidedInstructions)

        val content = """
# System Instructions

Extract content on the user's behalf. If the user asks for a list or for all results, return all requested information.

You will receive:
1. an instruction
2. a list of DOM elements to extract from

- Reproduce exact text from the DOM, including symbols, characters, and line breaks.
- If no new information is found, return `null` or an empty string.

<user_request>
$userInstructions
</user_request>

"""

        return SimpleMessage(role = "system", content = content)
    }

    fun buildExtractUserRequestPrompt(params: ExtractParams): String {
        return """
## User Request
<user_request>
${params.instruction}
</user_request>
        """.trimIndent()
    }

    fun buildExtractPageContentUserPrompt(params: ExtractParams): SimpleMessage {
        val browserState = params.agentState.browserUseState.browserState

        val scrollState = browserState.scrollState
        // Height in pixels of the page area above the current viewport.
        val hiddenTopHeight = scrollState.hiddenTopHeight
        val hiddenBottomHeight = scrollState.hiddenBottomHeight
        val viewportHeight = scrollState.viewportHeight
        val domState = params.agentState.browserUseState.domState

        // The 1-based viewport to see.
        val processingViewport = scrollState.processingViewport
        val viewportsTotal = scrollState.viewportsTotal
        val viewPortJson = Pson.toJson(
            "processingViewport" to processingViewport,
            "viewportHeight" to viewportHeight,
            "viewportsTotal" to viewportsTotal,
            "hiddenTopHeight" to hiddenTopHeight,
            "hiddenBottomHeight" to hiddenBottomHeight
        )
        val startY = scrollState.y.coerceAtLeast(0.0)
        val endY = (scrollState.y + viewportHeight).coerceAtLeast(0.0)
        val nanoTree = domState.serializableTree.toNanoTreeInRange(startY, endY)

        val schema = params.schema

        val content = """
## Viewport State

$viewPortJson

---

## ARIA Snapshot

- Current viewport range only
- `ref` is a unique node reference that can be used for tab tools to locate the node, e.g., click node with [ref=e123] -> tab.click('e123')

```yaml
${nanoTree.ariaSnapshot}
```

---

## Output Requirements
Return a valid JSON object that strictly matches the following JSON Schema. Do not include extra explanation.

${schema.toJsonSchema()}

        """.trimIndent()

        return SimpleMessage(role = "user", content = content)
    }

    fun buildExtractionEvaluationSystemPrompt(): SimpleMessage {
        val metadataSystemPromptCN: String = """
You are an AI assistant that evaluates extraction progress and completion status.

- Each extraction covers only the current viewport range.
- Data above the current viewport has already been processed.
- Data below the current viewport has not been processed yet.

Analyze the extraction response and decide whether the task is complete or whether more information is needed.
Follow these rules exactly:
1. If the current extraction response already satisfies the instruction, set completion to `true` and stop, even if more viewports remain.
2. Set completion to `false` only when both conditions are true:
   - the instruction is not yet satisfied
   - unprocessed viewport data still remains (`viewportsTotal > processingViewport`)

""".trimIndent()

        return SimpleMessage(
            role = "system",
            content = metadataSystemPromptCN,
        )
    }

    fun buildExtractionEvaluationUserPrompt(
        instruction: String,
        extractionResponse: Any,
        agentState: AgentState,
    ): SimpleMessage {
        /**
         * The 1-based next chunk to see, each chunk is a viewport height.
         * */
        val browserUseState = agentState.browserUseState
        val scrollState = browserUseState.browserState.scrollState
        // Height in pixels of the page area above the current viewport.
        val hiddenTopHeight = scrollState.hiddenTopHeight
        val hiddenBottomHeight = scrollState.hiddenBottomHeight
        val viewportHeight = scrollState.viewportHeight

        // The 1-based viewport to see.
        val processingViewport = scrollState.processingViewport
        val viewportsTotal = scrollState.viewportsTotal

        val viewPortJson = Pson.toJson(
            "processingViewport" to processingViewport,
            "viewportHeight" to viewportHeight,
            "viewportsTotal" to viewportsTotal,
            "hiddenTopHeight" to hiddenTopHeight,
            "hiddenBottomHeight" to hiddenBottomHeight
        )

        val extractedJson = DOMSerializer.MAPPER.writeValueAsString(extractionResponse)

        val content =
            """
## User Request

<user_request>
$instruction
</user_request>

## Viewport State

$viewPortJson

---

## Extraction Result

$extractedJson

---

""".trim()

        return SimpleMessage(role = "user", content = content)
    }

    private fun buildSingleObserveSystemPrompt(messages: AgentMessageList, params: ObserveParams) {
        val schema =
            if (params.returnAction) OBSERVE_GUIDE_OUTPUT_SCHEMA_RETURN_ACTIONS else OBSERVE_GUIDE_OUTPUT_SCHEMA

        val observeSystemPrompt = PromptTemplate(OBSERVE_GUIDE_SYSTEM_MESSAGE).render(
            mapOf("OUTPUT_SCHEMA_PLACEHOLDER" to schema)
        )

        messages.addLast("system", observeSystemPrompt)

        val extra = buildObserveGuideSystemExtraPrompt(params.userProvidedInstructions)?.content
        if (extra != null) {
            messages.addLast("system", extra)
        }
    }

    fun initObserveUserInstruction(
        instruction: String?,
        messages: AgentMessageList = AgentMessageList()
    ): AgentMessageList {
        val instruction2 = when {
            !instruction.isNullOrBlank() -> instruction
            else -> """
Based on the context and current progress, select the most appropriate tool to advance the task toward user completion.
                """.trimIndent()
        }

        messages.addUser(instruction2, name = "user_request")
        return messages
    }

    private fun buildBrowserStateMessageForBrowserInteraction(messages: AgentMessageList, context: ExecutionContext) {
        val step = context.step
        val lastToolCall = context.prevAgentState?.actionDescription?.toolCall
        val lastDomain = lastToolCall?.domain
        if (step > 1 && !ToolSpecification.isBrowserInteraction(lastDomain)) {
            return
        }

        val prevBrowserState = context.agentState.prevState?.browserUseState?.browserState
        val browserState = context.agentState.browserUseState.browserState

        val prevTabs = prevBrowserState?.tabs ?: emptyList()
        val currentTabs = browserState.tabs
        val newTabs: List<TabState> = if (prevTabs.size != currentTabs.size) {
            currentTabs - prevTabs.toSet()
        } else emptyList()
        val newTabsJson = if (newTabs.isNotEmpty()) DOMSerializer.toJson(newTabs) else null
        val newTabsMessage = if (newTabs.isEmpty()) "" else {
            """
Tabs opened in the previous step:

$newTabsJson

            """.trimIndent()
        }

        val scrollState = browserState.scrollState
        // Height in pixels of the page area above the current viewport.
        val hiddenTopHeight = scrollState.hiddenTopHeight
        val hiddenBottomHeight = scrollState.hiddenBottomHeight
        val viewportHeight = scrollState.viewportHeight
        val domState = context.agentState.browserUseState.domState

        // The 1-based viewport to see.
        val processingViewport = scrollState.processingViewport
        val viewportsTotal = scrollState.viewportsTotal

        // TODO: should we provide the interactive elements?
        // val interactiveElements = context.agentState.browserUseState.getInteractiveElements()
        val viewPortInfo = Pson.toJson(
            "processingViewport" to processingViewport,
            "viewportHeight" to viewportHeight,
            "viewportsTotal" to viewportsTotal,
            "hiddenTopHeight" to hiddenTopHeight,
            "hiddenBottomHeight" to hiddenBottomHeight
        )
        val delta = viewportHeight * 0.5
        val startY = (scrollState.y - delta).coerceAtLeast(0.0)
        val endY = (scrollState.y + viewportHeight + delta).coerceAtLeast(0.0)
        val nanoTree = domState.serializableTree.toNanoTreeInRange(startY, endY)

        fun contentEN() = """
## Browser State

<browser_state>
${browserState.lazyJson}
</browser_state>

$newTabsMessage

---

## Viewport State

$viewPortInfo

- By default, inspect one viewport-height chunk of DOM nodes at a time.
- Viewport position and numbering may change whenever the page content changes.
- The provided accessibility tree focuses on viewport `i` and includes a small amount of nearby off-screen context.
- To inspect the next viewport, call `scrollBy(viewportHeight)`.

---

## ARIA Snapshot

Focused on nodes in viewport ${processingViewport}.

```yaml
${nanoTree.ariaSnapshot}
```

---
"""

        val content = contentEN()

        messages.addLast("user", content)
    }

    fun buildObserveActToolUsePrompt(action: String): String {
        val instruction =
            """
## User Request

Choose one tool to execute this action: $action. Find the page element most relevant to the action, tool, and goal. Consider the expected result and likely impact after execution.

---

"""

        return instruction
    }

    fun buildSummaryPrompt(goal: String, agentHistory: AgentHistory): Pair<String, String> {
        val system = "Generate a JSON summary based on the execution trajectory for the original goal."

        val history = buildAgentStateHistoryMessage(agentHistory)

        val user = """
## Original Goal

$goal

---

$history

## Output Schema

$OBSERVE_RESPONSE_COMPLETE_SCHEMA

---

        """.trimIndent()

        return system to user
    }

    fun tr(text: String) = translate(text)

    /**
     * Translate to another language, reserved
     * */
    fun translate(text: String): String {
        return text
    }
}
