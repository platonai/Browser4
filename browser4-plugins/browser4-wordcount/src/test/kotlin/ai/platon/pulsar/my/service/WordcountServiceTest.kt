package ai.platon.pulsar.my.service

import ai.platon.pulsar.my.config.WordcountConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordcountServiceTest {

    private val service = WordcountService()

    @Test
    fun `default config enables wordcount`() {
        val config = WordcountConfig()
        assertTrue(config.enabled)
    }

    @Test
    fun `wordcount can be disabled via configuration`() {
        val config = WordcountConfig(enabled = false)
        assertFalse(config.enabled)
    }

    @Test
    fun `counts words chars and lines for regular text`() {
        val result = service.getWordCount("Hello world 123")

        assertEquals(3, result.words)
        assertEquals(15, result.chars)
        assertEquals(13, result.charsNoSpaces)
        assertEquals(1, result.lines)
    }

    @Test
    fun `empty text returns zeros`() {
        val result = service.getWordCount("")

        assertEquals(0, result.words)
        assertEquals(0, result.chars)
        assertEquals(0, result.charsNoSpaces)
        assertEquals(0, result.lines)
    }

    @Test
    fun `counts multiline text`() {
        val text = "one two\nthree\n\nfour five"
        val result = service.getWordCount(text)

        assertEquals(5, result.words)
        assertEquals(text.length, result.chars)
        assertEquals(text.count { !it.isWhitespace() }, result.charsNoSpaces)
        assertEquals(4, result.lines)
    }
}
