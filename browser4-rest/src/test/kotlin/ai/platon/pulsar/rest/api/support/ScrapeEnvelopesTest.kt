package ai.platon.pulsar.rest.api.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The `{success, …}` envelope, pinned as a shape rather than as "three maps that agree".
 *
 * Key order travels to the wire for an ordered map, and the null rules decide how a client
 * reads a refusal — so both are asserted here instead of being left to each call site.
 * */
@Tag("Unit")
@Tag("Fast")
@DisplayName("the {success, …} scrape envelope")
class ScrapeEnvelopesTest {

    @Test
    @DisplayName("the key order is the shape, so it is asserted rather than assumed")
    fun keyOrderIsPinned() {
        assertEquals(
            listOf("success", "data"),
            ScrapeEnvelopes.success(mapOf("markdown" to "# Title")).keys.toList(),
        )
        assertEquals(
            listOf("success", "error", "message"),
            ScrapeEnvelopes.failure(ScrapeEnvelopes.BAD_REQUEST, "Unknown format 'markdwon'").keys.toList(),
        )
        assertEquals(
            listOf("success", "error", "retryable", "hint", "message"),
            ScrapeEnvelopes.failure(
                "TARGET_UNAVAILABLE", "audio is not available",
                "retryable" to false,
                "hint" to "install the audio contributor",
            ).keys.toList(),
        )
    }

    @Test
    @DisplayName("a missing message is the empty string, never null")
    fun aMissingMessageIsEmpty() {
        // A client that prints `message` should never have to test for it first.
        assertEquals("", ScrapeEnvelopes.failure(ScrapeEnvelopes.NOT_FOUND, null)["message"])
    }

    @Test
    @DisplayName("a null detail is carried as null instead of being dropped")
    fun aNullDetailIsCarried() {
        // Absence and null are different answers on the wire: a caller that branches on
        // `retryable` must find the key, even when the failure has no value for it.
        val body = ScrapeEnvelopes.failure("UPSTREAM_ERROR", "the upstream call failed", "retryable" to null)

        assertTrue(body.containsKey("retryable"), body.toString())
        assertNull(body["retryable"])
    }

    @Test
    @DisplayName("success is true only on the success answer")
    fun successIsTrueOnlyOnSuccess() {
        assertEquals(true, ScrapeEnvelopes.success(emptyList<String>())["success"])
        assertEquals(false, ScrapeEnvelopes.failure(ScrapeEnvelopes.BAD_REQUEST, "typo")["success"])
    }
}
