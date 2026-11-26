package ai.platon.pulsar.agentic.agents

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.response.ChatResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [stepIntentOf] decides whether a model response carries a "next step, and why" line.
 *
 * The rule is what keeps the new `model.intent` step from duplicating `model.text`: the
 * intent is the text that accompanies tool calls, so a text-only answer (the run's
 * closing report) and a text-less tool request must both yield nothing.
 */
@DisplayName("step intent extraction")
class StepIntentTest {

    private fun response(text: String?, toolCalls: Int): ChatResponse {
        val requests = (1..toolCalls).map { index ->
            ToolExecutionRequest.builder()
                .id("call-$index")
                .name("b4.run")
                .arguments("{}")
                .build()
        }
        val message = if (text == null) {
            AiMessage.from(requests)
        } else {
            AiMessage.from(text, requests)
        }
        return ChatResponse.builder().aiMessage(message).build()
    }

    @Test
    @DisplayName("text next to a tool call is the intent, trimmed")
    fun textWithToolCallsIsTheIntent() {
        assertEquals(
            "Intent: open the product page — to read its price",
            stepIntentOf(response("  Intent: open the product page — to read its price\n", 1)),
        )
    }

    @Test
    @DisplayName("a text-only answer is the closing report, not an intent")
    fun textOnlyAnswerHasNoIntent() {
        assertNull(stepIntentOf(response("all done", 0)))
    }

    @Test
    @DisplayName("a tool request without text has no intent")
    fun textlessToolCallHasNoIntent() {
        assertNull(stepIntentOf(response(null, 1)))
        assertNull(stepIntentOf(response("   \n", 1)))
    }
}
