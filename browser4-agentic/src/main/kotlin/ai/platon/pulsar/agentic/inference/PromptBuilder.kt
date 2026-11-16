package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.api.model.DOMSerializer
import ai.platon.pulsar.agentic.inference.action.OBSERVE_RESPONSE_COMPLETE_SCHEMA
import ai.platon.pulsar.agentic.inference.history.DefaultHistoryRenderStrategy
import ai.platon.pulsar.agentic.inference.history.HistoryRenderStrategy
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.model.AgentState
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

        // The 0-based viewport to see.
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
   - unprocessed viewport data still remains (`viewportsTotal > processingViewport + 1`)

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
         * The 0-based next chunk to see, each chunk is a viewport height.
         * */
        val browserUseState = agentState.browserUseState
        val scrollState = browserUseState.browserState.scrollState
        // Height in pixels of the page area above the current viewport.
        val hiddenTopHeight = scrollState.hiddenTopHeight
        val hiddenBottomHeight = scrollState.hiddenBottomHeight
        val viewportHeight = scrollState.viewportHeight

        // The 0-based viewport to see.
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
