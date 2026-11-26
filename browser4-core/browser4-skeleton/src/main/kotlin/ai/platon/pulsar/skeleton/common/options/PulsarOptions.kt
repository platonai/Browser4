package ai.platon.pulsar.skeleton.common.options

import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.common.options.OptionUtils
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import org.apache.commons.lang3.StringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Created by Vincent on 17-4-12.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
open class PulsarOptions(
    /**
     * The argument vector
     * */
    val argv: Array<String>
) : Parameterized {
    protected val logger: Logger = LoggerFactory.getLogger(PulsarOptions::class.java)

    init { normalize(argv) }

    var expandAtSign = true
    var acceptUnknownOptions = true
    var allowParameterOverwriting = true
    // arguments
    val args: String get() = argv.joinToString(DEFAULT_DELIMETER)
    private val registeredObjects: MutableSet<Any> = HashSet()
    protected lateinit var jc: JCommander

    open var isHelp: Boolean = false

    init { addObjects(this) }

    constructor(): this(arrayOf())

    constructor(args: String): this(split(args.trim()))

    constructor(argv: Map<String, String>)
            : this(argv.entries.joinToString(DEFAULT_DELIMETER) { it.key + DEFAULT_DELIMETER + it.value })

    fun setObjects(vararg objects: Any) {
        this.registeredObjects.clear()
        addObjects(objects)
    }

    fun addObjects(vararg objects: Any) {
        objects.toCollection(this.registeredObjects)
    }

    open fun parse(): Boolean {
        try {
            doParse()
        } catch (e: Throwable) {
            logger.warn("Failed to parse \n$args", e)
            return false
        }

        return true
    }

    open fun parseOrExit() {
        parseOrExit(mutableSetOf())
    }

    protected open fun parseOrExit(objects: Set<Any>) {
        try {
            addObjects(objects)
            doParse()

            if (isHelp) {
                jc.usage()
                exitProcess(0)
            }
        } catch (e: ParameterException) {
            println(e.toString())
            exitProcess(0)
        }
    }

    private fun doParse() {
        registeredObjects.add(this)

        jc = JCommander.newBuilder()
                .acceptUnknownOptions(acceptUnknownOptions)
                .allowParameterOverwriting(allowParameterOverwriting)
                .expandAtSign(expandAtSign).build()
        registeredObjects.forEach { jc.addObject(it) }

        if (argv.isNotEmpty()) {
            jc.parse(*argv)
        }
    }

    open fun usage() {
        jc.usage()
    }

    open fun toCmdLine(): String {
        return params.withKVDelimiter(" ").formatAsLine()
                .replace("\\s+".toRegex(), " ")
    }

    open fun toArgsMap(): Map<String, String> {
        return params.asStringMap()
    }

    open fun toMutableArgsMap(): MutableMap<String, String> {
        return params.asStringMap()
    }

    open fun toArgv(): Array<String> {
        return params.withKVDelimiter(" ").formatAsLine()
                .split("\\s+".toRegex())
                .toTypedArray()
    }

    override fun hashCode(): Int {
        return args.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        return other is PulsarOptions && args == other.args
    }

    override fun toString(): String {
        return args
    }

    companion object {
        const val DEFAULT_DELIMETER = " "

        /**
         * Normalize the raw arguments, convert old version args to current version
         * */
        @JvmOverloads
        fun normalize(args: String, seps: String = ","): String {
            var args1 = StringUtils.replaceChars(args, seps, StringUtils.repeat(' ', seps.length))
            // in old version, -cacheContent has arity 0, but current version is 1, we need a convert
            args1 = OptionUtils.arity0ToArity1(args1, "-cacheContent")
            args1 = OptionUtils.arity0ToArity1(args1, "-storeContent")

            return args1
        }

        /**
         * Since space can not appear in dynamic parameters in command line, we use % instead.
         * */
        fun normalize(argv: Array<String>) {
            for (i in argv.indices) {
                argv[i] = argv[i]
                    .replace("%", " ")
                    .replace("%20", " ")
            }
        }

        /**
         * Split a command line into argument vector (argv).
         *
         * A double-quoted segment (with `\`-escapes) stays one token including its
         * quotes; anything else splits on whitespace — the same contract as the
         * regex `"[^"\\]*(?:\\.[^"\\]*)*"|\S+` this replaces.
         *
         * The regex had to go: Java compiles a quantified group `(?:X)*` to a
         * recursive matcher, one stack frame per iteration, so a quoted argument
         * holding ~2000 escape sequences overflowed a 1 MB thread stack. Long
         * plain text was unaffected because a character-class quantifier such as
         * `[^"\\]*` iterates instead. This scan is O(n) time and O(1) stack and
         * keeps the regex semantics, including the `\S+` fallback when a quote
         * never closes.
         */
        fun split(args: String): Array<String> {
            val s = normalize(args)
            val result = ArrayList<String>()
            val n = s.length
            var i = 0
            while (i < n) {
                if (isRegexSpace(s[i])) {
                    i++
                    continue
                }
                val start = i
                if (s[i] == '"') {
                    val end = quotedEnd(s, i)
                    if (end > 0) {
                        result.add(s.substring(start, end))
                        i = end
                        continue
                    }
                }
                // \S+ — also the fallback for an unterminated quote, which the
                // regex would match as a plain run of non-whitespace.
                var k = i
                while (k < n && !isRegexSpace(s[k])) k++
                result.add(s.substring(start, k))
                i = k
            }
            return result.toTypedArray()
        }

        /**
         * Index just past the closing quote of the segment starting at [start]
         * (`s[start] == '"'`), or -1 if it never closes. Mirrors the regex: a `\`
         * is only consumable as an escape when followed by a non-line-terminator
         * (`.` excludes line terminators), and any char other than `"` and `\` —
         * line terminators included — is ordinary content for `[^"\\]`.
         */
        private fun quotedEnd(s: String, start: Int): Int {
            var j = start + 1
            while (j < s.length) {
                val c = s[j]
                when {
                    c == '"' -> return j + 1
                    c == '\\' -> {
                        if (j + 1 >= s.length || isLineTerminator(s[j + 1])) return -1
                        j += 2
                    }
                    else -> j++
                }
            }
            return -1
        }

        /** Java regex `\s` without UNICODE_CHARACTER_CLASS: ASCII whitespace only. */
        private fun isRegexSpace(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

        /** What regex `.` refuses to match by default (no DOTALL). */
        private fun isLineTerminator(c: Char): Boolean =
            c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'
    }
}
