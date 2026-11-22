package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * `accepted=true` only ever proved that a steering line was QUEUED: the delivery happens
 * at the agent's next model round-trip (steering) or stop point (a follow-up), and until
 * now the caller had no way to tell that the `steer` step it observed was its own message
 * (issue #652, P1-5).
 *
 * The delivered step therefore carries the ids the API handed out. These tests pin the
 * payload shape — a machine consumer reads `detail.inputIds`, not the event text.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("delivered steering / follow-up correlation ids")
class DeliveredInputDetailTest {

    @Test
    @DisplayName("a single delivered message carries its input id")
    fun aSingleMessageCarriesItsId() {
        val detail = deliveredInputDetail(listOf(PendingUserMessage("input-7", "focus on the tests")))

        assertEquals(listOf("input-7"), detail["inputIds"], "the caller's id must be repeated: $detail")
    }

    @Test
    @DisplayName("a drained batch lists every id, oldest first")
    fun aBatchListsEveryId() {
        // Several lines can be queued between two model round-trips; they are injected as
        // ONE user message, so the payload has to name all of them or a caller whose line
        // was batched with someone else's could never recognise it.
        val detail = deliveredInputDetail(
            listOf(
                PendingUserMessage("input-1", "also the prices"),
                PendingUserMessage("input-2", "and the publish date"),
            )
        )

        assertEquals(listOf("input-1", "input-2"), detail["inputIds"])
    }

    @Test
    @DisplayName("the payload is a plain map so it survives the event sanitizer unchanged")
    fun thePayloadSurvivesTheEventSanitizer() {
        val detail = deliveredInputDetail(listOf(PendingUserMessage("input-7", "focus")))

        assertTrue(detail.keys == setOf("inputIds"), "no free-text fields: $detail")
    }
}
