package ai.platon.pulsar.my.service

import kotlin.text.isWhitespace

/**
 * Result of counting words, characters, characters without spaces, and lines in plain text.
 */
data class WordCountResult(
    val words: Int,
    val chars: Int,
    val charsNoSpaces: Int,
    val lines: Int
)

/**
 * Pure text word-count service. It does not depend on a browser, WebDriver, or JavaScript.
 */
class WordcountService {

    /**
     * Counts words, characters, characters without whitespace, and lines in [text].
     *
     * - words: number of tokens separated by whitespace
     * - chars: total number of characters, `text.length`
     * - charsNoSpaces: number of non-whitespace characters
     * - lines: number of newline characters plus one; zero for an empty string
     */
    fun getWordCount(text: String): WordCountResult {
        if (text.isEmpty()) {
            return WordCountResult(words = 0, chars = 0, charsNoSpaces = 0, lines = 0)
        }

        val words = text.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .size
        val chars = text.length
        val charsNoSpaces = text.count { !it.isWhitespace() }
        val lines = text.count { it == '\n' } + 1

        return WordCountResult(
            words = words,
            chars = chars,
            charsNoSpaces = charsNoSpaces,
            lines = lines
        )
    }
}
