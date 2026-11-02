package ai.platon.pulsar.chrome

import ai.platon.pulsar.api.BrowserProtocol
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.wheneverBlocking

/**
 * [StealthAwareBrowserProtocol] is the single hook that turns Browser4's page-world stealth
 * payload off. The base library's only production call to
 * [BrowserProtocol.addScriptToEvaluateOnNewDocument] registers that payload, so the wrapper's
 * contract is binary: delegate when the active
 * [ai.platon.browser4.behavior.stealth.BrowserStealthProvider] allows the baseline payload,
 * no-op (with a non-null identifier) when it suppresses it.
 */
@DisplayName("StealthAwareBrowserProtocol")
class StealthAwareBrowserProtocolTest {

    @Test
    @DisplayName("delegates addScriptToEvaluateOnNewDocument when the baseline payload is enabled")
    fun delegatesWhenBaselinePayloadEnabled() = runBlocking {
        val delegate = mock<BrowserProtocol>()
        wheneverBlocking { delegate.addScriptToEvaluateOnNewDocument("payload") }.thenReturn("real-id")

        val wrapper = StealthAwareBrowserProtocol(delegate, baselinePageWorldScriptsEnabled = true)

        assertEquals("real-id", wrapper.addScriptToEvaluateOnNewDocument("payload"))
        verify(delegate).addScriptToEvaluateOnNewDocument("payload")
        Unit
    }

    @Test
    @DisplayName("suppresses addScriptToEvaluateOnNewDocument when the baseline payload is disabled")
    fun suppressesWhenBaselinePayloadDisabled() = runBlocking {
        val delegate = mock<BrowserProtocol>()
        val wrapper = StealthAwareBrowserProtocol(delegate, baselinePageWorldScriptsEnabled = false)

        val id = wrapper.addScriptToEvaluateOnNewDocument("payload")

        // The base stores the returned identifier but never removes it, so any non-null,
        // non-blank string is a valid stand-in for a real CDP registration id.
        assertNotEquals("", id)
        verifyNoInteractions(delegate)
    }
}
