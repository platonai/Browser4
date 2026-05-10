package ai.platon.pulsar.skeleton.common.options

import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.common.options.OptionUtils
import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.util.regex.Pattern
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
    protected val logger = LoggerFactory.getLogger(PulsarOptions::class.java)

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
        val CMD_SPLIT_PATTERN = Pattern.compile("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|\\S+")

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
         * @see {https://stackoverflow.com/questions/36292591/splitting-a-nested-string-keeping-quotation-marks/36292778}
         */
        fun split(args: String): Array<String> {
            val matcher = CMD_SPLIT_PATTERN.matcher(normalize(args))
            matcher.reset()
            val result = ArrayList<String>()
            while (matcher.find()) {
                result.add(matcher.group(0))
            }
            return result.toTypedArray()
        }
    }
}
