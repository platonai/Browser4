package ai.platon.pulsar.protocol.browser.emulator.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiPrivacyContextManagerRetryTest {
    @Test
    fun `privacy retry limit accepts zero and defaults invalid values to five`() {
        assertEquals(0, MultiPrivacyContextManager.maxPrivacyRetries("0"))
        assertEquals(1, MultiPrivacyContextManager.maxPrivacyRetries("1"))
        assertEquals(5, MultiPrivacyContextManager.maxPrivacyRetries("5"))
        assertEquals(5, MultiPrivacyContextManager.maxPrivacyRetries("-1"))
        assertEquals(5, MultiPrivacyContextManager.maxPrivacyRetries("invalid"))
        assertEquals(5, MultiPrivacyContextManager.maxPrivacyRetries(null))
    }

    @Test
    fun `privacy retry limit allows exactly the configured number of retries`() {
        assertFalse(MultiPrivacyContextManager.isPrivacyRetryAllowed(1, 0))
        assertTrue(MultiPrivacyContextManager.isPrivacyRetryAllowed(1, 1))
        assertFalse(MultiPrivacyContextManager.isPrivacyRetryAllowed(2, 1))
        assertTrue(MultiPrivacyContextManager.isPrivacyRetryAllowed(5, 5))
        assertFalse(MultiPrivacyContextManager.isPrivacyRetryAllowed(6, 5))
    }
}
