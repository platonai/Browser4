package ai.platon.pulsar.rest.mcp.controller

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `attach_browser` must treat `extension` as a strict transport switch.
 *
 * The old parse (`ext is Boolean && ext || ext.toString().let { it == "true" ||
 * it.isNotBlank() }`) answered TRUE for `extension = false`, because the string
 * "false" is not "true" but *is* not blank. It also answered true for
 * `extension = "msedge"`, silently dropping the channel and leaving the session
 * on the default chrome family — the "asked for Edge, got Chrome" confusion.
 */
@DisplayName("attach_browser extension transport flag")
class AttachBrowserTransportFlagTest {

    @Test
    fun `missing flag means a CDP attach`() {
        assertFalse(isExtensionTransportFlag(null))
    }

    @Test
    fun `booleans are taken literally`() {
        assertTrue(isExtensionTransportFlag(true))
        assertFalse(isExtensionTransportFlag(false))
    }

    @Test
    fun `string spellings are accepted`() {
        assertTrue(isExtensionTransportFlag("true"))
        assertTrue(isExtensionTransportFlag("TRUE"))
        assertTrue(isExtensionTransportFlag(" true "))
        assertFalse(isExtensionTransportFlag("false"))
        assertFalse(isExtensionTransportFlag("False"))
        assertFalse(isExtensionTransportFlag(""))
        assertFalse(isExtensionTransportFlag("   "))
    }

    @Test
    fun `a channel name is refused instead of silently becoming the extension transport`() {
        for (value in listOf("msedge", "chrome", "msedge-beta", "edge")) {
            val error = assertThrows<IllegalArgumentException> { isExtensionTransportFlag(value) }
            assertTrue(
                error.message.orEmpty().contains("boolean transport switch"),
                "the error must say the flag is a boolean switch: ${error.message}"
            )
            assertTrue(
                error.message.orEmpty().contains("channel=\"$value\""),
                "the error must point at the channel argument: ${error.message}"
            )
        }
    }
}
