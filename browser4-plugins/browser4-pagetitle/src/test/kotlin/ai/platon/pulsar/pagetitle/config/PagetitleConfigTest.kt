package ai.platon.pulsar.pagetitle.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class PagetitleConfigTest {

    @Test
    @DisplayName("default configuration has max length 200")
    fun defaultMaxLength() {
        assertEquals(200, PagetitleConfig().maxLength)
    }

    @Test
    @DisplayName("default configuration is enabled")
    fun defaultEnabled() {
        assertTrue(PagetitleConfig().enabled)
    }
}
