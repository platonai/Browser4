package ai.platon.pulsar.rest.api.support

import ai.platon.pulsar.common.B4Constants.DEFAULT_SESSION_ID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The rule every endpoint reads a session id through: a session id is a non-blank string.
 *
 * These are one-liners by design — the value is in the rule being stated once and pinned,
 * not in the code it takes to apply it.
 * */
@Tag("Unit")
@Tag("Fast")
@DisplayName("which session a REST request addresses")
class SessionResolutionTest {

    @Test
    @DisplayName("the first value that names a session wins, so the body beats the query")
    fun theFirstNamedValueWins() {
        assertEquals("body", SessionResolution.firstNamed("body", "query"))
        assertEquals("query", SessionResolution.firstNamed(null, "query"))
        assertEquals("s1", SessionResolution.firstNamed("", "s1"))
    }

    @Test
    @DisplayName("null, empty and whitespace all mean 'no session was named'")
    fun blankValuesDoNotNameASession() {
        // The regression this pins: read literally, `""` is a *session* whose id is the
        // empty string — one the caller never opened — so every "fall back to the default"
        // site has to agree that a blank means "not named".
        for (blank in listOf(null, "", "   ", "\t")) {
            assertNull(SessionResolution.firstNamed(blank), "value='$blank'")
            assertNull(SessionResolution.firstNamed(blank, null), "value='$blank'")
            assertEquals(DEFAULT_SESSION_ID, SessionResolution.firstNamedOrDefault(blank), "value='$blank'")
        }
    }

    @Test
    @DisplayName("a named session is never replaced by the default")
    fun aNamedSessionSurvives() {
        assertEquals("s1", SessionResolution.firstNamedOrDefault("s1"))
        assertEquals("s1", SessionResolution.firstNamedOrDefault(null, "s1"))
        assertEquals(DEFAULT_SESSION_ID, SessionResolution.firstNamedOrDefault())
    }
}
