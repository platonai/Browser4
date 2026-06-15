package ai.platon.pulsar.browser.common

import kotlin.random.Random

object Utils {
    const val ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun nextAlphabetic(length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0..<length) {
            sb.append(ALPHABET[Random.nextInt(ALPHABET.length)])
        }
        return sb.toString()
    }

    /**
     *
     * Abbreviates a String to the length passed, replacing the middle characters with the supplied
     * replacement String.
     *
     *
     * This abbreviation only occurs if the following criteria is met:
     *
     *  * Neither the String for abbreviation nor the replacement String are null or empty
     *  * The length to truncate to is less than the length of the supplied String
     *  * The length to truncate to is greater than 0
     *  * The abbreviated String will have enough room for the length supplied replacement String
     * and the first and last characters of the supplied String for abbreviation
     *
     * Otherwise, the returned String will be the same as the supplied String for abbreviation.
     *
     *
     * <pre>
     * StringUtils.abbreviateMiddle(null, null, 0)      = null
     * StringUtils.abbreviateMiddle("abc", null, 0)      = "abc"
     * StringUtils.abbreviateMiddle("abc", ".", 0)      = "abc"
     * StringUtils.abbreviateMiddle("abc", ".", 3)      = "abc"
     * StringUtils.abbreviateMiddle("abcdef", ".", 4)     = "ab.f"
    </pre> *
     *
     * @param str  the String to abbreviate, may be null
     * @param middle the String to replace the middle characters with, may be null
     * @param length the length to abbreviate `str` to.
     * @return the abbreviated String if the above criteria is met, or the original String supplied for abbreviation.
     * @since 2.5
     */
    fun abbreviateMiddle(str: String, middle: String, length: Int): String {
        if (str.isEmpty() || middle.isEmpty()) {
            return str
        }

        if (length >= str.length || length < (middle.length + 2)) {
            return str
        }

        val targetSting = length - middle.length
        val startOffset = targetSting / 2 + targetSting % 2
        val endOffset = str.length - targetSting / 2

        val builder = StringBuilder(length)
        builder.append(str.substring(0, startOffset))
        builder.append(middle)
        builder.append(str.substring(endOffset))

        return builder.toString()
    }
}
