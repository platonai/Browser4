package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import org.apache.commons.lang3.StringUtils
import java.nio.charset.Charset
import java.util.*

/**
 * # Null-safe string operations
 *
 * This UDF group mainly delegates to [StringUtils] and is intended for use from H2 SQL.
 * Unless otherwise stated, methods follow the exact behavior of `org.apache.commons.lang3.StringUtils`.
 *
 * ## Covered categories
 *
 * - **isEmpty / isBlank**
 *   - checks whether a string contains text
 * - **trim / strip**
 *   - removes leading and trailing whitespace
 * - **equals / compare**
 *   - compares two strings in a null-safe manner
 * - **startsWith**
 *   - checks whether a string starts with a prefix in a null-safe manner
 * - **endsWith**
 *   - checks whether a string ends with a suffix in a null-safe manner
 * - **indexOf / lastIndexOf / contains**
 *   - null-safe index-of checks
 * - **indexOfAny / lastIndexOfAny / indexOfAnyBut / lastIndexOfAnyBut**
 *   - index-of any of a set of strings
 * - **containsOnly / containsNone / containsAny**
 *   - checks if a string contains only / none / any of the given characters
 * - **substring / left / right / mid**
 *   - null-safe substring extractions
 * - **substringBefore / substringAfter / substringBetween**
 *   - substring extraction relative to other strings
 * - **split / join**
 *   - splits a string into an array of substrings and vice versa
 * - **remove / delete**
 *   - removes part of a string
 * - **replace / overlay**
 *   - searches a string and replaces one string with another
 * - **chomp / chop**
 *   - removes the last part of a string
 * - **appendIfMissing**
 *   - appends a suffix to the end of the string if not present
 * - **prependIfMissing**
 *   - prepends a prefix to the start of the string if not present
 * - **leftPad / rightPad / center / repeat**
 *   - pads a string
 * - **upperCase / lowerCase / swapCase / capitalize / uncapitalize**
 *   - changes the case of a string
 * - **countMatches**
 *   - counts the number of occurrences of one string in another
 * - **isAlpha / isNumeric / isWhitespace / isAsciiPrintable**
 *   - checks the characters in a string
 * - **defaultString**
 *   - protects against a null input string
 * - **rotate**
 *   - rotates (circular shift) a string
 * - **reverse / reverseDelimited**
 *   - reverses a string
 * - **abbreviate**
 *   - abbreviates a string using ellipses or another given string
 * - **difference**
 *   - compares strings and reports on their differences
 * - **levenshteinDistance**
 *   - the number of changes needed to change one string into another
 *
 * ## Terminology
 *
 * - **null**: `null`
 * - **empty**: a zero-length string (`""`)
 * - **space**: the space character (`' '`, char 32)
 * - **whitespace**: characters defined by [Character.isWhitespace]
 * - **trim**: the characters `<= 32` as in [String.trim]
 *
 * ## Notes on `null` handling
 *
 * `StringUtils` handles `null` input strings quietly.
 * That is, a `null` input typically returns `null`. Where a `boolean` or `int` is returned,
 * details vary by method.
 *
 * A side effect of this `null` handling is that a [NullPointerException] should be considered a bug
 * in [StringFunctions].
 *
 * The sample docs in upstream `StringUtils` sometimes use `*` to indicate any input, including `null`.
 */
@SuppressWarnings("unused")
@UDFGroup(namespace = "STR")
object StringFunctions {

    @UDFunction @JvmStatic fun capitalize(str: String?): String? = StringUtils.capitalize(str)
    @UDFunction @JvmStatic fun isAlpha(str: String?): Boolean = StringUtils.isAlpha(str)
    @UDFunction @JvmStatic fun isNumeric(str: String?): Boolean = StringUtils.isNumeric(str)
    @UDFunction @JvmStatic fun difference(str: String?, arg1: String): String? = StringUtils.difference(str, arg1)
    @UDFunction @JvmStatic fun remove(str: String?, arg1: String): String? = StringUtils.remove(str, arg1)
//    @UDFunction @JvmStatic fun remove(str: String?, arg1: Char): String? = StringUtils.remove(str, arg1)
    @UDFunction @JvmStatic fun equals(str: String?, arg1: String): Boolean = StringUtils.equals(str, arg1)
    @UDFunction @JvmStatic fun length(str: String?): Int = StringUtils.length(str)
    @UDFunction @JvmStatic fun toString(str: ByteArray, arg1: String): String? = StringUtils.toString(str, arg1)
    @UDFunction @JvmStatic fun mid(str: String?, arg1: Int, arg2: Int): String? = StringUtils.mid(str, arg1, arg2)
//    @UDFunction @JvmStatic fun indexOf(str: String?, arg1: Int): Int = StringUtils.indexOf(str, arg1)
//    @UDFunction @JvmStatic fun indexOf(str: String?, arg1: Int, arg2: Int): Int = StringUtils.indexOf(str, arg1, arg2)
    @UDFunction @JvmStatic fun indexOf(str: String?, arg1: String): Int = StringUtils.indexOf(str, arg1)
    @UDFunction @JvmStatic fun indexOf(str: String?, arg1: String, arg2: Int): Int = StringUtils.indexOf(str, arg1, arg2)
    @UDFunction @JvmStatic fun isWhitespace(str: String?): Boolean = StringUtils.isWhitespace(str)
    @UDFunction @JvmStatic fun isEmpty(str: String?): Boolean = StringUtils.isEmpty(str)
    @UDFunction @JvmStatic fun equalsIgnoreCase(str: String?, arg1: String): Boolean = StringUtils.equalsIgnoreCase(str, arg1)
    @UDFunction @JvmStatic fun startsWith(str: String?, arg1: String): Boolean = StringUtils.startsWith(str, arg1)
    @UDFunction @JvmStatic fun endsWith(str: String?, arg1: String): Boolean = StringUtils.endsWith(str, arg1)
//    @UDFunction @JvmStatic fun lastIndexOf(str: String?, arg1: Int): Int = StringUtils.lastIndexOf(str, arg1)
    @UDFunction @JvmStatic fun lastIndexOf(str: String?, arg1: String): Int = StringUtils.lastIndexOf(str, arg1)
//    @UDFunction @JvmStatic fun lastIndexOf(str: String?, arg1: Int, arg2: Int): Int = StringUtils.lastIndexOf(str, arg1, arg2)
    @UDFunction @JvmStatic fun lastIndexOf(str: String?, arg1: String, arg2: Int): Int = StringUtils.lastIndexOf(str, arg1, arg2)
    @UDFunction @JvmStatic fun substring(str: String?, start: Int): String? = StringUtils.substring(str, start)
    @UDFunction @JvmStatic fun substring(str: String?, start: Int, end: Int): String? = StringUtils.substring(str, start, end)
    @UDFunction @JvmStatic fun replace(str: String?, arg1: String, arg2: String): String? = StringUtils.replace(str, arg1, arg2)
    @UDFunction @JvmStatic fun replace(str: String?, arg1: String, arg2: String, arg3: Int): String? = StringUtils.replace(str, arg1, arg2, arg3)
    @UDFunction @JvmStatic fun contains(str: String?, arg1: String): Boolean = StringUtils.contains(str, arg1)
    @UDFunction @JvmStatic fun contains22(str: String?, arg1: Int): Boolean = StringUtils.contains(str, arg1)
    @UDFunction @JvmStatic fun split(str: String?, arg1: String, arg2: Int): Array<String> = StringUtils.split(str, arg1, arg2)
    @UDFunction @JvmStatic fun split(str: String?): Array<String> = StringUtils.split(str)
    @UDFunction @JvmStatic fun split(str: String?, arg1: String): Array<String> = StringUtils.split(str, arg1)
    @UDFunction @JvmStatic fun split22(str: String?, arg1: Char): Array<String> = StringUtils.split(str, arg1)
//    @UDFunction @JvmStatic fun join(str: CharArray, arg1: Char): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: IntArray, arg1: Char): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: ShortArray, arg1: Char): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: ByteArray, arg1: Char): String? = StringUtils.join(str, arg1)
    @UDFunction @JvmStatic fun join(str: Array<String>): String? = StringUtils.join(str)
//    @UDFunction @JvmStatic fun join(str: Array<String>, arg1: Char): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: LongArray, arg1: Char): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: Iterator<String>, arg1: String): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: Iterable<String>, arg1: Char): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: Iterable<String>, arg1: String): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: DoubleArray, arg1: Char, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
//    @UDFunction @JvmStatic fun join(str: FloatArray, arg1: Char, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
//    @UDFunction @JvmStatic fun join(str: CharArray, arg1: Char, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
//    @UDFunction @JvmStatic fun join(str: Array<String>, arg1: String, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
//    @UDFunction @JvmStatic fun join(str: LongArray, arg1: Char, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
//    @UDFunction @JvmStatic fun join(str: Array<String>, arg1: Char, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
//    @UDFunction @JvmStatic fun join(str: DoubleArray, arg1: Char): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: FloatArray, arg1: Char): String? = StringUtils.join(str, arg1)
//    @UDFunction @JvmStatic fun join(str: IntArray, arg1: Char, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
//    @UDFunction @JvmStatic fun join(str: ByteArray, arg1: Char, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
//    @UDFunction @JvmStatic fun join(str: ShortArray, arg1: Char, arg2: Int, arg3: Int): String? = StringUtils.join(str, arg1, arg2, arg3)
    @UDFunction @JvmStatic fun join(str: Array<String>, arg1: String): String? = StringUtils.join(str, arg1)
    @UDFunction @JvmStatic fun trim(str: String?): String? = StringUtils.trim(str)
    @UDFunction @JvmStatic fun strip(str: String?, arg1: String): String? = StringUtils.strip(str, arg1)
    @UDFunction @JvmStatic fun strip(str: String?): String? = StringUtils.strip(str)
    @UDFunction @JvmStatic fun isBlank(str: String?): Boolean = StringUtils.isBlank(str)
    @UDFunction @JvmStatic fun repeat(str: String?, arg1: String, arg2: Int): String? = StringUtils.repeat(str, arg1, arg2)
//    @UDFunction @JvmStatic fun repeat(str: Char, arg1: Int): String? = StringUtils.repeat(str, arg1)
    @UDFunction @JvmStatic fun repeat(str: String?, arg1: Int): String? = StringUtils.repeat(str, arg1)
    @UDFunction @JvmStatic fun reverse(str: String?): String? = StringUtils.reverse(str)
    @UDFunction @JvmStatic fun left(str: String?, arg1: Int): String? = StringUtils.left(str, arg1)
    @UDFunction @JvmStatic fun right(str: String?, arg1: Int): String? = StringUtils.right(str, arg1)
    @UDFunction @JvmStatic fun isAnyEmpty(str: Array<String>): Boolean = StringUtils.isAnyEmpty(*str)
    @UDFunction @JvmStatic fun isNoneEmpty(str: Array<String>): Boolean = StringUtils.isNoneEmpty(*str)
    @UDFunction @JvmStatic fun isNotBlank(str: String?): Boolean = StringUtils.isNotBlank(str)
    @UDFunction @JvmStatic fun isAnyBlank(str: Array<String>): Boolean = StringUtils.isAnyBlank(*str)
    @UDFunction @JvmStatic fun isNoneBlank(str: Array<String>): Boolean = StringUtils.isNoneBlank(*str)
    @UDFunction @JvmStatic fun trimToNull(str: String?): String? = StringUtils.trimToNull(str)
    @UDFunction @JvmStatic fun trimToEmpty(str: String?): String? = StringUtils.trimToEmpty(str)
    @UDFunction @JvmStatic fun stripToNull(str: String?): String? = StringUtils.stripToNull(str)
    @UDFunction @JvmStatic fun stripToEmpty(str: String?): String? = StringUtils.stripToEmpty(str)
    @UDFunction @JvmStatic fun stripStart(str: String?, arg1: String): String? = StringUtils.stripStart(str, arg1)
    @UDFunction @JvmStatic fun stripEnd(str: String?, arg1: String): String? = StringUtils.stripEnd(str, arg1)
    @UDFunction @JvmStatic fun stripAll(str: Array<String>): Array<String> = StringUtils.stripAll(*str)
    @UDFunction @JvmStatic fun stripAll(str: Array<String>, arg1: String): Array<String> = StringUtils.stripAll(str, arg1)
    @UDFunction @JvmStatic fun stripAccents(str: String?): String? = StringUtils.stripAccents(str)
    @UDFunction @JvmStatic fun isNotEmpty(str: String?): Boolean = StringUtils.isNotEmpty(str)
    @UDFunction @JvmStatic fun ordinalIndexOf(str: String?, arg1: String, arg2: Int): Int = StringUtils.ordinalIndexOf(str, arg1, arg2)
    @UDFunction @JvmStatic fun indexOfIgnoreCase(str: String?, arg1: String): Int = StringUtils.indexOfIgnoreCase(str, arg1)
    @UDFunction @JvmStatic fun indexOfIgnoreCase(str: String?, arg1: String, arg2: Int): Int = StringUtils.indexOfIgnoreCase(str, arg1, arg2)
    @UDFunction @JvmStatic fun lastOrdinalIndexOf(str: String?, arg1: String, arg2: Int): Int = StringUtils.lastOrdinalIndexOf(str, arg1, arg2)
    @UDFunction @JvmStatic fun lastIndexOfIgnoreCase(str: String?, arg1: String): Int = StringUtils.lastIndexOfIgnoreCase(str, arg1)
    @UDFunction @JvmStatic fun lastIndexOfIgnoreCase(str: String?, arg1: String, arg2: Int): Int = StringUtils.lastIndexOfIgnoreCase(str, arg1, arg2)
    @UDFunction @JvmStatic fun containsIgnoreCase(str: String?, arg1: String): Boolean = StringUtils.containsIgnoreCase(str, arg1)
    @UDFunction @JvmStatic fun containsWhitespace(str: String?): Boolean = StringUtils.containsWhitespace(str)
    @UDFunction @JvmStatic fun indexOfAny(str: String?, arg1: String): Int = StringUtils.indexOfAny(str, arg1)
    @UDFunction @JvmStatic fun containsAny(str: String?, arg1: String): Boolean = StringUtils.containsAny(str, arg1)
    @UDFunction @JvmStatic fun indexOfAnyBut(str: String?, arg1: String): Int = StringUtils.indexOfAnyBut(str, arg1)
    @UDFunction @JvmStatic fun containsOnly(str: String?, arg1: String): Boolean = StringUtils.containsOnly(str, arg1)
    @UDFunction @JvmStatic fun containsNone(str: String?, arg1: String): Boolean = StringUtils.containsNone(str, arg1)
    @UDFunction @JvmStatic fun substringBefore(str: String?, arg1: String): String? = StringUtils.substringBefore(str, arg1)
    @UDFunction @JvmStatic fun substringAfter(str: String?, arg1: String): String? = StringUtils.substringAfter(str, arg1)
    @UDFunction @JvmStatic fun substringBeforeLast(str: String?, arg1: String): String? = StringUtils.substringBeforeLast(str, arg1)
    @UDFunction @JvmStatic fun substringAfterLast(str: String?, arg1: String): String? = StringUtils.substringAfterLast(str, arg1)
    @UDFunction @JvmStatic fun substringBetween(str: String?, arg1: String): String? = StringUtils.substringBetween(str, arg1)
    @UDFunction @JvmStatic fun substringBetween(str: String?, arg1: String, arg2: String): String? = StringUtils.substringBetween(str, arg1, arg2)
    @UDFunction @JvmStatic fun substringsBetween(str: String?, arg1: String, arg2: String): Array<String> = StringUtils.substringsBetween(str, arg1, arg2)
    @UDFunction @JvmStatic fun splitByWholeSeparator(str: String?, arg1: String, arg2: Int): Array<String> = StringUtils.splitByWholeSeparator(str, arg1, arg2)
    @UDFunction @JvmStatic fun splitByWholeSeparator(str: String?, arg1: String): Array<String> = StringUtils.splitByWholeSeparator(str, arg1)
    @UDFunction @JvmStatic fun splitByWholeSeparatorPreserveAllTokens(str: String?, arg1: String, arg2: Int): Array<String> = StringUtils.splitByWholeSeparatorPreserveAllTokens(str, arg1, arg2)
    @UDFunction @JvmStatic fun splitByWholeSeparatorPreserveAllTokens(str: String?, arg1: String): Array<String> = StringUtils.splitByWholeSeparatorPreserveAllTokens(str, arg1)
    @UDFunction @JvmStatic fun splitPreserveAllTokens(str: String?): Array<String> = StringUtils.splitPreserveAllTokens(str)
    @UDFunction @JvmStatic fun splitPreserveAllTokens(str: String?, arg1: String): Array<String> = StringUtils.splitPreserveAllTokens(str, arg1)
    @UDFunction @JvmStatic fun splitPreserveAllTokens2(str: String?, arg1: Char): Array<String> = StringUtils.splitPreserveAllTokens(str, arg1)
    @UDFunction @JvmStatic fun splitPreserveAllTokens(str: String?, arg1: String, arg2: Int): Array<String> = StringUtils.splitPreserveAllTokens(str, arg1, arg2)
    @UDFunction @JvmStatic fun splitByCharacterType(str: String?): Array<String> = StringUtils.splitByCharacterType(str)
    @UDFunction @JvmStatic fun splitByCharacterTypeCamelCase(str: String?): Array<String> = StringUtils.splitByCharacterTypeCamelCase(str)
    @UDFunction @JvmStatic fun deleteWhitespace(str: String?): String? = StringUtils.deleteWhitespace(str)
    @UDFunction @JvmStatic fun removeStart(str: String?, arg1: String): String? = StringUtils.removeStart(str, arg1)
    @UDFunction @JvmStatic fun removeStartIgnoreCase(str: String?, arg1: String): String? = StringUtils.removeStartIgnoreCase(str, arg1)
    @UDFunction @JvmStatic fun removeEnd(str: String?, arg1: String): String? = StringUtils.removeEnd(str, arg1)
    @UDFunction @JvmStatic fun removeEndIgnoreCase(str: String?, arg1: String): String? = StringUtils.removeEndIgnoreCase(str, arg1)
    @UDFunction @JvmStatic fun replaceOnce(str: String?, arg1: String, arg2: String): String? = StringUtils.replaceOnce(str, arg1, arg2)
    @UDFunction @JvmStatic fun replacePattern(str: String?, arg1: String, arg2: String): String? = StringUtils.replacePattern(str, arg1, arg2)
    @UDFunction @JvmStatic fun removePattern(str: String?, arg1: String): String? = StringUtils.removePattern(str, arg1)
    @UDFunction @JvmStatic fun replaceEach(str: String?, arg1: Array<String>, arg2: Array<String>): String? = StringUtils.replaceEach(str, arg1, arg2)
    @UDFunction @JvmStatic fun replaceEachRepeatedly(str: String?, arg1: Array<String>, arg2: Array<String>): String? = StringUtils.replaceEachRepeatedly(str, arg1, arg2)
    @UDFunction @JvmStatic fun replaceChars2(str: String?, arg1: Char, arg2: Char): String? = StringUtils.replaceChars(str, arg1, arg2)
    @UDFunction @JvmStatic fun replaceChars(str: String?, arg1: String, arg2: String): String? = StringUtils.replaceChars(str, arg1, arg2)
    @UDFunction @JvmStatic fun overlay(str: String?, arg1: String, arg2: Int, arg3: Int): String? = StringUtils.overlay(str, arg1, arg2, arg3)
    @UDFunction @JvmStatic fun chomp(str: String?): String? = StringUtils.chomp(str)
    @UDFunction @JvmStatic fun chomp(str: String?, arg1: String): String? = StringUtils.chomp(str, arg1)
    @UDFunction @JvmStatic fun chop(str: String?): String? = StringUtils.chop(str)
    @UDFunction @JvmStatic fun rightPad2(str: String?, arg1: Int, arg2: Char): String? = StringUtils.rightPad(str, arg1, arg2)
    @UDFunction @JvmStatic fun rightPad(str: String?, arg1: Int): String? = StringUtils.rightPad(str, arg1)
    @UDFunction @JvmStatic fun rightPad(str: String?, arg1: Int, arg2: String): String? = StringUtils.rightPad(str, arg1, arg2)
    @UDFunction @JvmStatic fun leftPad2(str: String?, arg1: Int, arg2: Char): String? = StringUtils.leftPad(str, arg1, arg2)
    @UDFunction @JvmStatic fun leftPad(str: String?, arg1: Int): String? = StringUtils.leftPad(str, arg1)
    @UDFunction @JvmStatic fun leftPad(str: String?, arg1: Int, arg2: String): String? = StringUtils.leftPad(str, arg1, arg2)
    @UDFunction @JvmStatic fun center(str: String?, arg1: Int): String? = StringUtils.center(str, arg1)
    @UDFunction @JvmStatic fun center2(str: String?, arg1: Int, arg2: Char): String? = StringUtils.center(str, arg1, arg2)
    @UDFunction @JvmStatic fun center(str: String?, arg1: Int, arg2: String): String? = StringUtils.center(str, arg1, arg2)
    @UDFunction @JvmStatic fun upperCase(str: String?, arg1: Locale): String? = StringUtils.upperCase(str, arg1)
    @UDFunction @JvmStatic fun upperCase(str: String?): String? = StringUtils.upperCase(str)
    @UDFunction @JvmStatic fun lowerCase(str: String?, arg1: Locale): String? = StringUtils.lowerCase(str, arg1)
    @UDFunction @JvmStatic fun lowerCase(str: String?): String? = StringUtils.lowerCase(str)
    @UDFunction @JvmStatic fun uncapitalize(str: String?): String? = StringUtils.uncapitalize(str)
    @UDFunction @JvmStatic fun swapCase(str: String?): String? = StringUtils.swapCase(str)
    @UDFunction @JvmStatic fun countMatches(str: String?, arg1: String): Int = StringUtils.countMatches(str, arg1)
    @UDFunction @JvmStatic fun isAlphaSpace(str: String?): Boolean = StringUtils.isAlphaSpace(str)
    @UDFunction @JvmStatic fun isAlphanumeric(str: String?): Boolean = StringUtils.isAlphanumeric(str)
    @UDFunction @JvmStatic fun isAlphanumericSpace(str: String?): Boolean = StringUtils.isAlphanumericSpace(str)
    @UDFunction @JvmStatic fun isAsciiPrintable(str: String?): Boolean = StringUtils.isAsciiPrintable(str)
    @UDFunction @JvmStatic fun isNumericSpace(str: String?): Boolean = StringUtils.isNumericSpace(str)
    @UDFunction @JvmStatic fun isAllLowerCase(str: String?): Boolean = StringUtils.isAllLowerCase(str)
    @UDFunction @JvmStatic fun isAllUpperCase(str: String?): Boolean = StringUtils.isAllUpperCase(str)
    @UDFunction @JvmStatic fun defaultString(str: String?): String? = StringUtils.defaultString(str)
    @UDFunction @JvmStatic fun defaultString(str: String?, arg1: String): String? = StringUtils.defaultString(str, arg1)
    @UDFunction @JvmStatic fun defaultIfBlank(str: String?, arg1: String): String? = StringUtils.defaultIfBlank(str, arg1)
    @UDFunction @JvmStatic fun defaultIfEmpty(str: String?, arg1: String): String? = StringUtils.defaultIfEmpty(str, arg1)
    @UDFunction @JvmStatic fun reverseDelimited(str: String?, arg1: Char): String? = StringUtils.reverseDelimited(str, arg1)
    @UDFunction @JvmStatic fun abbreviate(str: String?, arg1: Int, arg2: Int): String? = StringUtils.abbreviate(str, arg1, arg2)
    @UDFunction @JvmStatic fun abbreviate(str: String?, arg1: Int): String? = StringUtils.abbreviate(str, arg1)
    @UDFunction @JvmStatic fun abbreviateMiddle(str: String?, arg1: String, arg2: Int): String? = StringUtils.abbreviateMiddle(str, arg1, arg2)
    @UDFunction @JvmStatic fun indexOfDifference(str: Array<String>): Int = StringUtils.indexOfDifference(*str)
    @UDFunction @JvmStatic fun indexOfDifference(str: String?, arg1: String): Int = StringUtils.indexOfDifference(str, arg1)
    @UDFunction @JvmStatic fun getCommonPrefix(str: Array<String>): String? = StringUtils.getCommonPrefix(*str)
    @UDFunction @JvmStatic fun getLevenshteinDistance(str: String?, arg1: String): Int = StringUtils.getLevenshteinDistance(str, arg1)
    @UDFunction @JvmStatic fun getLevenshteinDistance(str: String?, arg1: String, arg2: Int): Int = StringUtils.getLevenshteinDistance(str, arg1, arg2)
    @UDFunction @JvmStatic fun getJaroWinklerDistance(str: String?, arg1: String): Double = StringUtils.getJaroWinklerDistance(str, arg1)
    @UDFunction @JvmStatic fun startsWithIgnoreCase(str: String?, arg1: String): Boolean = StringUtils.startsWithIgnoreCase(str, arg1)
    @UDFunction @JvmStatic fun startsWithAny(str: String?, arg1: Array<String>): Boolean = StringUtils.startsWithAny(str, *arg1)
    @UDFunction @JvmStatic fun endsWithIgnoreCase(str: String?, arg1: String): Boolean = StringUtils.endsWithIgnoreCase(str, arg1)
    @UDFunction @JvmStatic fun normalizeSpace(str: String?): String? = StringUtils.normalizeSpace(str)
    @UDFunction @JvmStatic fun endsWithAny(str: String?, arg1: Array<String>): Boolean = StringUtils.endsWithAny(str, *arg1)
    @UDFunction @JvmStatic fun appendIfMissing(str: String?, arg1: String, arg2: Array<String>): String? = StringUtils.appendIfMissing(str, arg1, *arg2)
    @UDFunction @JvmStatic fun appendIfMissingIgnoreCase(str: String?, arg1: String, arg2: Array<String>): String? = StringUtils.appendIfMissingIgnoreCase(str, arg1, *arg2)
    @UDFunction @JvmStatic fun prependIfMissing(str: String?, arg1: String, arg2: Array<String>): String? = StringUtils.prependIfMissing(str, arg1, *arg2)
    @UDFunction @JvmStatic fun prependIfMissingIgnoreCase(str: String?, arg1: String, arg2: Array<String>): String? = StringUtils.prependIfMissingIgnoreCase(str, arg1, *arg2)
    @UDFunction @JvmStatic fun toEncodedString(str: ByteArray, arg1: Charset): String? = StringUtils.toEncodedString(str, arg1)

    @UDFunction(description = "Get the first integer in the given string")
    @JvmStatic
    fun firstInteger(str: String?, defaultValue: Int): Int = Strings.getFirstInteger(str, defaultValue)

    @UDFunction(description = "Get the first float in the given string")
    @JvmStatic
    fun firstFloat(str: String?, defaultValue: Float): Float = Strings.getFirstFloatNumber(str, defaultValue)

    @UDFunction(description = "Get the first float number in the given string")
    @JvmStatic
    fun getFirstFloatNumber(str: String?, defaultValue: Float): Float {
        return Strings.getFirstFloatNumber(str, defaultValue)
    }
}
